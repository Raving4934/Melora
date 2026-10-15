package com.leyu.melora.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import kotlin.math.roundToInt

// 保留 MB 存储契约；界面半 G 档对应 512 MB，整 G 对应 1024 MB。
internal fun audioCacheSizeGb(mb: Int): String =
    BigDecimal(mb).divide(BigDecimal(1024)).stripTrailingZeros().toPlainString()

internal fun audioCacheStep(mb: Int): Int =
    ((mb.coerceIn(1024, 10240) - 1024) / 512f).roundToInt()

internal fun audioCacheMbAtStep(step: Int): Int = 1024 + step.coerceIn(0, 18) * 512

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioCacheSizeSetting(maxCacheMb: Int, onSave: (Int) -> Unit) {
    // 只在手动操作结束后写入/裁剪缓存；浏览设置不迁移或截断旧容量。
    var draft by remember(maxCacheMb) { mutableStateOf<Int?>(null) }
    val step = audioCacheStep(draft ?: maxCacheMb)
    val haptics = LocalHapticFeedback.current
    val active = SettingsBrandBlue
    val inactive = SettingsTextSub.copy(alpha = 0.28f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最大缓存大小", fontSize = 13.sp, color = SettingsTextMain)
            Text("${audioCacheSizeGb(draft ?: maxCacheMb)} G", fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = active)
        }
        Slider(
            value = step.toFloat(),
            valueRange = 0f..18f,
            steps = 17,
            onValueChange = { value ->
                val next = value.roundToInt().coerceIn(0, 18)
                if (next != audioCacheStep(draft ?: maxCacheMb)) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                draft = audioCacheMbAtStep(next)
            },
            onValueChangeFinished = {
                draft?.let { if (it != maxCacheMb) onSave(it) }
                draft = null
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "最大缓存大小" },
            thumb = { Box(Modifier.size(16.dp).background(active, CircleShape)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                    fun x(fraction: Float) = size.width * if (rtl) 1f - fraction else fraction
                    val y = size.height / 2
                    drawLine(inactive, Offset(x(0f), y), Offset(x(1f), y), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(active, Offset(x(0f), y), Offset(x(state.value / 18f), y), 2.dp.toPx(), StrokeCap.Round)
                    for (tick in 0..18) {
                        val halfHeight = (if (tick % 2 == 0) 5.dp else 2.5.dp).toPx()
                        drawLine(if (tick <= state.value) active else inactive,
                            Offset(x(tick / 18f), y - halfHeight), Offset(x(tick / 18f), y + halfHeight),
                            1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1 G", fontSize = 11.sp, color = SettingsTextSub)
            Text("10 G", fontSize = 11.sp, color = SettingsTextSub)
        }
    }
}
