package com.leyu.melora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.WebDavBackup
import com.leyu.melora.playback.WebDavConfig
import com.leyu.melora.playback.WebDavSync
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.MeloraSheetActionBar
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.ui.common.rememberSheetDismiss
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.theme.MeloraAppearance
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.launch

@Composable
internal fun WebDavConnectionEntry(
    config: WebDavConfig?,
    ready: Boolean,
    enabled: Boolean = true,
    onSaved: (WebDavConfig) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    SettingsEntryItem(
        icon = Icons.Outlined.CloudQueue,
        iconTint = SettingsBrandBlue,
        title = "配置",
        desc = when {
            !ready -> "正在读取连接配置…"
            config == null -> "设置服务器地址、账号与目录"
            else -> "${config.server.toHttpUrl().host} / ${config.folder}"
        },
        enabled = ready && enabled,
        onClick = { editing = true },
    )
    if (editing) {
        WebDavConnectionEditor(config, onDismiss = { editing = false }, onSaved = onSaved)
    }
}

private enum class ConnectionAction { Test, Save }

private data class ConnectionFeedback(val draft: WebDavConfig, val text: String, val failed: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebDavConnectionEditor(
    config: WebDavConfig?,
    onDismiss: () -> Unit,
    onSaved: (WebDavConfig) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val savedCallback by rememberUpdatedState(onSaved)
    // 口令只保留在当前编辑窗口，避免写入 Activity 的恢复状态。
    val server = remember { TextFieldState(config?.server.orEmpty()) }
    val folder = remember { TextFieldState(config?.folder ?: WebDavConfig.DEFAULT_FOLDER) }
    val account = remember { TextFieldState(config?.account.orEmpty()) }
    val password = remember { TextFieldState(config?.password.orEmpty()) }
    var visiblePassword by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ConnectionAction?>(null) }
    var feedback by remember { mutableStateOf<ConnectionFeedback?>(null) }
    val sheet = rememberMeloraBottomSheetState(skipPartiallyExpanded = true) {
        it != SheetValue.Hidden || action != ConnectionAction.Save
    }
    val close = rememberSheetDismiss(sheet)

    fun currentDraft() = WebDavConfig(
        server = server.text.toString(),
        account = account.text.toString(),
        password = password.text.toString(),
        folder = folder.text.toString(),
    )

    val draft = currentDraft()
    val draftFeedback = feedback?.takeIf { it.draft == draft }
    if (feedback != draftFeedback) {
        SideEffect {
            if (feedback?.draft != currentDraft()) feedback = null
        }
    }

    fun runAction(next: ConnectionAction) {
        if (action != null) return
        val submitted = currentDraft()
        action = next
        feedback = null
        visiblePassword = false
        scope.launch {
            try {
                runCatchingCancellable {
                    when (next) {
                        ConnectionAction.Test -> {
                            val cleanupNotice = WebDavBackup.test(submitted)
                            feedback = ConnectionFeedback(
                                submitted,
                                listOfNotNull("测试通过，可使用网络备份和多设备同步。", cleanupNotice)
                                    .joinToString("\n"),
                            )
                        }
                        ConnectionAction.Save -> {
                            val saved = WebDavSync.saveConfig(context, submitted)
                            savedCallback(saved)
                            action = null
                            close(onDismiss)
                        }
                    }
                }.onFailure {
                    feedback = ConnectionFeedback(submitted, it.localizedMessage ?: "操作失败，请重试。", failed = true)
                }
            } finally {
                action = null
            }
        }
    }

    MeloraBottomSheet(
        onDismissRequest = { if (action != ConnectionAction.Save) onDismiss() },
        sheetState = sheet,
    ) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val busy = action != null
        Column(
            modifier = Modifier.fillMaxWidth()
                .imePadding()
                .heightIn(max = 600.dp)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                .semantics { paneTitle = "WebDAV 配置" },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WebDavIcon()
                Text(
                    "WebDAV 配置",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsTextMain,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard(contentPadding = PaddingValues(0.dp)) {
                    ConnectionField(
                        state = server,
                        label = "服务器地址",
                        placeholder = "https://nas.example.com/dav/",
                        icon = Icons.Outlined.Link,
                        enabled = !busy,
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingsDivider(Modifier.padding(start = 48.dp))
                    ConnectionField(
                        state = folder,
                        label = "子文件夹名称",
                        placeholder = WebDavConfig.DEFAULT_FOLDER,
                        icon = Icons.Outlined.Folder,
                        enabled = !busy,
                    )
                }
                SettingsCard(contentPadding = PaddingValues(0.dp)) {
                    ConnectionField(
                        state = account,
                        label = "账号",
                        placeholder = "服务器要求登录时填写",
                        icon = Icons.Outlined.Person,
                        enabled = !busy,
                    )
                    SettingsDivider(Modifier.padding(start = 48.dp))
                    ConnectionField(
                        state = password,
                        label = "密码",
                        placeholder = "密码或应用专用密码",
                        icon = Icons.Outlined.Lock,
                        enabled = !busy,
                        passwordObfuscation = if (visiblePassword) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                        trailingContent = {
                            IconButton(
                                enabled = !busy && password.text.isNotEmpty(),
                                onClick = { visiblePassword = !visiblePassword },
                            ) {
                                Icon(
                                    if (visiblePassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (visiblePassword) "隐藏密码" else "显示密码",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }
            WebDavFeedback(draftFeedback, action)
            val canSubmit = !busy && draft.server.isNotBlank()
            MeloraSheetActionBar(
                secondaryText = "测试配置",
                onSecondary = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    runAction(ConnectionAction.Test)
                },
                secondaryEnabled = canSubmit,
                secondaryWorking = action == ConnectionAction.Test,
                primaryText = "保存配置",
                onPrimary = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    runAction(ConnectionAction.Save)
                },
                primaryEnabled = canSubmit && draft != config,
                primaryWorking = action == ConnectionAction.Save,
                minHeight = 48.dp,
            )
        }
    }
}

@Composable
private fun WebDavIcon() {
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (MeloraAppearance.isDark) Color(0xFF222226) else MeloraAppearance.tintBlue),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.CloudQueue, null, tint = SettingsBrandBlue, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ConnectionField(
    state: TextFieldState,
    label: String,
    placeholder: String,
    icon: ImageVector,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    passwordObfuscation: TextObfuscationMode? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val fieldColor = if (focused) SettingsBrandBlue else SettingsTextSub
    val modifier = Modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = label }
    val textStyle = TextStyle(color = SettingsTextMain, fontSize = 15.sp, lineHeight = 22.sp)
    val cursorBrush = SolidColor(SettingsBrandBlue)
    val decorator = TextFieldDecorator { innerField ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .background(if (focused) MeloraAppearance.tintBlue else SettingsCardBg)
                .padding(
                    start = 16.dp,
                    end = if (trailingContent == null) 16.dp else 4.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = fieldColor, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fieldColor)
                Box(Modifier.fillMaxWidth()) {
                    if (state.text.isEmpty()) {
                        Text(
                            placeholder,
                            color = SettingsTextSub,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerField()
                }
            }
            trailingContent?.invoke()
        }
    }
    if (passwordObfuscation != null) {
        BasicSecureTextField(
            state = state,
            modifier = modifier,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            decorator = decorator,
            textObfuscationMode = passwordObfuscation,
        )
    } else {
        BasicTextField(
            state = state,
            modifier = modifier,
            enabled = enabled,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            decorator = decorator,
        )
    }
}

@Composable
private fun WebDavFeedback(feedback: ConnectionFeedback?, action: ConnectionAction?) {
    val message = when (action) {
        ConnectionAction.Test -> "正在测试目录读写和列举…"
        ConnectionAction.Save -> "正在保存配置…"
        null -> feedback?.text ?: "填写后即可测试目录读写和列举权限。"
    }
    val failed = feedback?.failed == true
    val color = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        feedback != null -> SettingsBrandBlue
        else -> SettingsTextSub
    }
    val fill = when {
        failed -> MeloraAppearance.tintRed
        feedback != null -> MeloraAppearance.tintBlue
        else -> MeloraAppearance.softFill
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(top = 12.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .heightIn(min = 50.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (feedback != null) {
            Icon(
                if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(message, color = color, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
