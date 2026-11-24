package com.leyu.melora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.formatCacheBytes
import com.leyu.melora.ui.common.MeloraAlertDialog
import com.leyu.melora.ui.theme.MeloraAppearance
import java.math.BigDecimal
import java.math.RoundingMode

private val mbPerGb = BigDecimal(1024)
private val maxCacheMbValue = BigDecimal(Int.MAX_VALUE)
private val cacheSizePattern = Regex("""(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)""")

internal fun audioCacheMbFromGb(text: String): Int? {
    val input = text.trim().replace(',', '.')
    if (input.length > 32 || !cacheSizePattern.matches(input)) return null
    val value = input.toBigDecimal()
    val mb = value.multiply(mbPerGb).setScale(0, RoundingMode.HALF_UP)
    if (mb > maxCacheMbValue || (mb.signum() == 0 && value.signum() > 0)) return null
    return mb.toInt()
}

/** 用能还原原有 MiB 容量的最短小数显示，避免 102 MiB 显示为 0.099609375 G。 */
internal fun audioCacheSizeGb(mb: Int): String {
    val gb = BigDecimal(mb).divide(mbPerGb)
    for (digits in 0..3) {
        val display = gb.setScale(digits, RoundingMode.HALF_UP).toPlainString()
        if (audioCacheMbFromGb(display) == mb) return display
    }
    return gb.setScale(4, RoundingMode.HALF_UP).toPlainString()
}

@Composable
internal fun AudioCacheSizeSetting(maxCacheMb: Int, usedBytes: Long?, onSave: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val overLimit = usedBytes != null && maxCacheMb > 0 && usedBytes > maxCacheMb * 1024L * 1024L
    val usage = when {
        usedBytes == null -> "正在读取占用…"
        overLimit -> "已用 ${formatCacheBytes(usedBytes)} · 超出上限"
        else -> "已用 ${formatCacheBytes(usedBytes)}"
    }
    SettingsCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { editing = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("播放缓存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SettingsTextMain)
                Text(
                    usage,
                    fontSize = 12.sp,
                    color = if (overLimit) MaterialTheme.colorScheme.error else SettingsTextSub,
                )
            }
            Text(
                if (maxCacheMb == 0) "不限" else "${audioCacheSizeGb(maxCacheMb)} G",
                modifier = Modifier.widthIn(max = 116.dp),
                color = SettingsTextSub,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = SettingsTextSub,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (editing) {
        CacheLimitDialog(maxCacheMb, onDismiss = { editing = false }) {
            editing = false
            if (it != maxCacheMb) onSave(it)
        }
    }
}

@Composable
private fun CacheLimitDialog(maxCacheMb: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val draft = rememberSaveable(maxCacheMb, saver = TextFieldState.Saver) {
        TextFieldState(audioCacheSizeGb(maxCacheMb))
    }
    var focused by remember { mutableStateOf(false) }
    val input = draft.text.toString()
    val candidate = audioCacheMbFromGb(input)
    val invalid = input.isNotBlank() && candidate == null
    val errorMessage = "请输入可用的非负容量。"

    fun saveDraft() {
        audioCacheMbFromGb(draft.text.toString())?.let(onSave)
    }

    MeloraAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("缓存上限", color = SettingsTextMain, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 288.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                val fieldFill = when {
                    invalid -> MeloraAppearance.tintRed
                    focused -> MeloraAppearance.tintBlue
                    else -> MeloraAppearance.softFill
                }
                BasicTextField(
                    state = draft,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                        .semantics {
                            contentDescription = "最大缓存大小"
                            if (invalid) error(errorMessage)
                        },
                    textStyle = TextStyle(color = SettingsTextMain, fontSize = 17.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(SettingsBrandBlue),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    onKeyboardAction = { saveDraft() },
                    decorator = { innerField ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 50.dp)
                                .background(fieldFill, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.weight(1f)) { innerField() }
                            Text("G", color = SettingsTextSub, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    },
                )
                Text(
                    if (invalid) errorMessage else "0 G 表示不限制缓存大小。",
                    fontSize = 12.sp,
                    color = if (invalid) MaterialTheme.colorScheme.error else SettingsTextSub,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::saveDraft,
                enabled = candidate != null,
                colors = ButtonDefaults.textButtonColors(contentColor = SettingsBrandBlue),
            ) { Text("保存", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = SettingsTextSub)) {
                Text("取消")
            }
        },
        containerColor = SettingsCardBg,
        shape = RoundedCornerShape(20.dp),
    )
}
