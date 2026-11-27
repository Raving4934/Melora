package com.leyu.melora.ui.settings

import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.MeloraSheetHandle
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.ui.common.rememberSheetDismiss
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.lx.LxInspector
import com.leyu.melora.playback.lx.LxInspector.LinkStatus
import com.leyu.melora.playback.lx.LxSourceUpdate
import com.leyu.melora.playback.lx.LxScript
import com.leyu.melora.playback.lx.LxSourceImporter
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.sdk.SourceResolver
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.meloraSwitchColors
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.player.platformLabel
import com.leyu.melora.ui.player.playerSpecificationLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val TextMain: Color get() = MeloraAppearance.textMain
private val TextSub: Color get() = MeloraAppearance.textSub
private val CardBg: Color get() = MeloraAppearance.card
private val BrandBlue: Color get() = MeloraAppearance.brand

private data class SourceActionStatus(
    val message: String,
    val busy: Boolean = false,
    val result: LinkStatus? = null,
)

private data class SourceImportFailure(val draft: String, val message: String)

// 自定义源管理：自动换源开关 + 已装音源（启停/检查/导出/删除）+ 导入导出。
@Composable
fun LxSourceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LxScriptStore(context) }
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(store.list()) }
    var sourceStatuses by remember { mutableStateOf<Map<String, SourceActionStatus>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var sourceOperationId by remember { mutableIntStateOf(0) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var importChooserOpen by remember { mutableStateOf(false) }
    var onlineImportOpen by remember { mutableStateOf(false) }
    var validityTestScript by remember { mutableStateOf<LxScript?>(null) }
    val sourceImporter = remember(context, store) { LxSourceImporter(store) }
    val sourceAlias by MeloraSettings.sourceAliasEnabled.collectAsStateWithLifecycle()
    val autoSwitch by MeloraSettings.autoSwitchSource.collectAsStateWithLifecycle()

    fun notify(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun updateSourceStatus(scriptId: String, status: SourceActionStatus) {
        sourceStatuses = sourceStatuses + (scriptId to status)
    }

    fun reloadAllSources(message: String) {
        val operationId = ++sourceOperationId
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable { LxScriptPool.reload(context) }
            SourceResolver.clearCache()
            val refreshed = store.list()
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                scripts = refreshed
                busy = false
                notify(result.fold(
                    onSuccess = { "$message，已加载 $it 个音源脚本" },
                    onFailure = { "$message，但重新加载失败：${it.message ?: "未知错误"}" },
                ))
            }
        }
    }

    fun refreshChangedSources(changedIds: Set<String>, message: String) {
        val operationId = ++sourceOperationId
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable { LxScriptPool.refresh(context, changedIds) }
            SourceResolver.clearCache()
            val refreshed = store.list()
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                scripts = refreshed
                notify(result.fold(
                    onSuccess = { "$message，后台初始化完成" },
                    onFailure = { "$message，但后台初始化失败：${it.message ?: "未知错误"}" },
                ))
            }
        }
    }

    fun completeSourceImport(imported: List<LxScript>, onSuccess: () -> Unit = {}) {
        busy = false
        scripts = store.list()
        sourceStatuses = sourceStatuses - imported.map { it.id }.toSet()
        onSuccess()
        val message = "已导入 ${imported.joinToString("、") { it.name }}"
        refreshChangedSources(imported.mapTo(linkedSetOf()) { it.id }, message)
    }

    fun launchSourceImport(
        import: suspend () -> List<LxScript>,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = ::notify,
    ) {
        val operationId = ++sourceOperationId
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable { import() }
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                result.fold(
                    onSuccess = { imported -> completeSourceImport(imported, onSuccess) },
                    onFailure = { error ->
                        busy = false
                        onFailure(error.message ?: "未知错误")
                    },
                )
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchSourceImport(import = {
            val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: uri.lastPathSegment
            val input = context.contentResolver.openInputStream(uri) ?: error("读取所选文件失败")
            sourceImporter.importLocal(input, displayName)
        })
    }

    fun writeExport(uri: Uri, success: String, failure: String, payload: () -> String) {
        val operationId = ++sourceOperationId
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable {
                val bytes = payload().toByteArray()
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入文件")
            }
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                notify(if (result.isSuccess) success else failure)
                busy = false
            }
        }
    }

    val singleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/javascript"),
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            val name = scripts.firstOrNull { it.id == id }?.name ?: id
            writeExport(uri, "已导出「$name」", "导出「$name」失败") { store.code(id) ?: error("脚本不存在") }
        }
    }

    val bundleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val selected = scripts
        writeExport(uri, "已导出 ${selected.size} 个音源脚本", "导出失败") {
            val entries = JSONArray()
            selected.forEach { script ->
                val code = store.code(script.id) ?: error("脚本不存在：${script.name}")
                entries.put(JSONObject().put("name", script.id).put("code", code))
            }
            JSONObject().put("app", "melora").put("type", "lx-sources").put("scripts", entries).toString(2)
        }
    }

    fun applySourceUpdate(update: LxSourceUpdate) {
        val id = update.scriptId
        if (sourceStatuses[id]?.busy == true) return
        updateSourceStatus(id, SourceActionStatus("正在应用更新…", busy = true))
        scope.launch {
            val status = runCatchingCancellable {
                sourceImporter.applyUpdate(update)
                val refresh = withContext(Dispatchers.IO) {
                    SourceResolver.clearCache()
                    runCatchingCancellable { LxScriptPool.refresh(context, setOf(id)) }
                }
                scripts = withContext(Dispatchers.IO) { store.list() }
                refresh.fold(
                    onSuccess = { SourceActionStatus("音源已更新", result = LinkStatus.VALID) },
                    onFailure = { SourceActionStatus("已更新，重新加载失败 · ${it.message}", result = LinkStatus.INVALID) },
                )
            }.getOrElse { SourceActionStatus("更新失败 · ${it.message ?: "请重新检查"}", result = LinkStatus.INVALID) }
            updateSourceStatus(id, status)
            notify(status.message)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = com.leyu.melora.ui.common.chromeContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionCaption("音源行为") }
        item {
            SettingsCard(contentPadding = PaddingValues(0.dp)) {
                SwitchItem(
                    title = "自动换源",
                    subtitle = if (autoSwitch) {
                        "已开启的源解析失败时，自动尝试其他已安装音源"
                    } else {
                        "仅使用已开启的源，解析失败不切换"
                    },
                    checked = autoSwitch,
                    subtitleSize = 12.sp,
                    labelEndPadding = 12.dp,
                    onCheckedChange = { MeloraSettings.updateAutoSwitchSource(it) },
                )
                SettingsDivider(Modifier.padding(horizontal = 14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "歌曲来源显示名称",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(shape = RoundedCornerShape(16.dp), color = MeloraAppearance.segmentTrack) {
                        Row(modifier = Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("original" to "原名", "alias" to "别名").forEach { (value, label) ->
                                val selected = (value == "alias") == sourceAlias
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) MeloraAppearance.card else Color.Transparent,
                                    shadowElevation = if (selected) 1.dp else 0.dp,
                                    modifier = Modifier.clickable(
                                        indication = null,
                                        interactionSource = remember(value) { MutableInteractionSource() },
                                    ) { MeloraSettings.updateSourceAlias(value == "alias") },
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) TextMain else TextSub,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionCaption(if (scripts.isEmpty()) "已安装音源" else "已安装音源 · ${scripts.size}") }
        if (scripts.isEmpty()) {
            item {
                SettingsCard(contentPadding = PaddingValues(0.dp)) {
                    InfoText("还没有音源脚本。点击下方「导入音源脚本」选择音源脚本文件（.js）即可开始使用。")
                }
            }
        } else {
            item {
                SettingsCard(contentPadding = PaddingValues(0.dp)) {
                    scripts.forEachIndexed { index, script ->
                        key(script.id) {
                            if (index > 0) SettingsDivider(Modifier.padding(start = 68.dp, end = 16.dp))
                            SourceRow(
                                script = script,
                                enabled = !busy,
                                actionStatus = sourceStatuses[script.id],
                                onToggle = { enabled ->
                                    ++sourceOperationId
                                    val enabledIds = store.setEnabled(script.id, enabled)
                                    scripts = scripts.map { item ->
                                        item.copy(enabled = item.id in enabledIds)
                                    }
                                    SourceResolver.clearCache()
                                },
                                onTestValidity = { validityTestScript = script },
                                checkUpdate = script.originUrl?.let { { sourceImporter.checkUpdate(script) } },
                                onApplyUpdate = ::applySourceUpdate,
                                onExport = {
                                    pendingExportId = script.id
                                    singleExporter.launch(script.id)
                                },
                                onDelete = {
                                    val operationId = ++sourceOperationId
                                    val message = "已删除「${script.name}」"
                                    busy = true
                                    scope.launch(Dispatchers.IO) {
                                        val deleteResult = runCatchingCancellable {
                                            store.remove(script.id)
                                            check(store.code(script.id) == null) { "脚本文件未能删除" }
                                            SourceResolver.clearCache()
                                        }
                                        val refreshResult = deleteResult
                                            .takeIf { it.isSuccess }
                                            ?.let { runCatchingCancellable { LxScriptPool.refresh(context) } }
                                        val refreshed = runCatchingCancellable { store.list() }.getOrNull()
                                        withContext(Dispatchers.Main) {
                                            if (operationId != sourceOperationId) return@withContext
                                            refreshed?.let { scripts = it }
                                            if (deleteResult.isSuccess) {
                                                sourceStatuses = sourceStatuses - script.id
                                            }
                                            notify(deleteResult.fold(
                                                onSuccess = {
                                                    refreshResult?.fold(
                                                        onSuccess = { "$message，音源引擎已释放" },
                                                        onFailure = { error -> "$message，但引擎刷新失败：${error.message ?: "未知错误"}" },
                                                    ) ?: message
                                                },
                                                onFailure = { error -> "删除「${script.name}」失败：${error.message ?: "未知错误"}" },
                                            ))
                                            busy = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item { SectionCaption("导入与导出") }
        item {
            SettingsCard(contentPadding = PaddingValues(0.dp)) {
                ActionRow(
                    icon = Icons.Rounded.Add,
                    label = "导入音源脚本",
                    subtitle = "支持本地文件与 HTTP/HTTPS 直链导入",
                    tint = Color(0xFF1E88E5),
                    enabled = !busy,
                ) {
                    importChooserOpen = true
                }
                SettingsDivider(Modifier.padding(start = 68.dp, end = 16.dp))
                ActionRow(
                    icon = Icons.Rounded.Upload,
                    label = if (scripts.isEmpty()) "导出全部音源" else "导出全部音源（${scripts.size} 个）",
                    subtitle = "将当前已安装音源打包为 .json 备份",
                    tint = Color(0xFF7C3AED),
                    enabled = scripts.isNotEmpty() && !busy,
                ) { bundleExporter.launch("melora-sources.json") }
                SettingsDivider(Modifier.padding(start = 68.dp, end = 16.dp))
                ActionRow(
                    icon = Icons.Rounded.Refresh,
                    label = "重新加载音源",
                    subtitle = "清空解析缓存并重新初始化脚本引擎",
                    tint = Color(0xFF0284C7),
                    enabled = !busy,
                ) {
                    reloadAllSources("已重新加载音源脚本并清空解析缓存")
                }
            }
        }
    }

    if (importChooserOpen) {
        ImportSourceSheet(
            onDismiss = { importChooserOpen = false },
            onLocalFile = { mimeTypes ->
                importChooserOpen = false
                importer.launch(mimeTypes)
            },
            onOnlineUrl = {
                importChooserOpen = false
                onlineImportOpen = true
            },
        )
    }
    if (onlineImportOpen) {
        OnlineSourceImportSheet(
            busy = busy,
            onDismiss = { if (!busy) onlineImportOpen = false },
            onImportSuccess = { onlineImportOpen = false },
            onImport = { url, onImportSuccess, onImportFailure ->
                launchSourceImport(
                    import = { sourceImporter.importUrl(url) },
                    onSuccess = onImportSuccess,
                    onFailure = onImportFailure,
                )
            },
        )
    }
    validityTestScript?.let { script ->
        ValidityTestSheet(
            store, script,
            onFinished = { updateSourceStatus(script.id, it) },
            onDismiss = { validityTestScript = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceModalSheet(
    onDismiss: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.(closeSheet: (() -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { MeloraSheetHandle(color = MeloraAppearance.divider) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (MeloraAppearance.isDark) Color(0xFF222226) else BrandBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = BrandBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = { content(closeSheet) },
            )
        }
    }
}

@Composable
internal fun ImportSourceSheet(
    onDismiss: () -> Unit,
    onLocalFile: (Array<String>) -> Unit,
    onOnlineUrl: () -> Unit,
) {
    SourceModalSheet(
        onDismiss = onDismiss,
        icon = Icons.Outlined.FolderOpen,
        title = "导入音源脚本",
        subtitle = "选择从本地文件选取或从网络链接导入兼容 JS 音源",
    ) { closeSheet ->
        SourceSheetAction(
            icon = Icons.Outlined.FolderOpen,
            iconTint = BrandBlue,
            iconBg = BrandBlue.copy(alpha = 0.12f),
            title = "选择 JS 文件",
            subtitle = "仅选择设备存储中的 .js 音源脚本",
            titleColor = TextMain,
            onClick = {
                closeSheet {
                    onLocalFile(arrayOf("application/javascript", "text/javascript", "application/x-javascript"))
                }
            },
        )
        SourceSheetAction(
            icon = Icons.Outlined.Link,
            iconTint = Color(0xFF059669),
            iconBg = Color(0xFF059669).copy(alpha = 0.12f),
            title = "从链接导入",
            subtitle = "粘贴 HTTP/HTTPS 音源脚本直链",
            titleColor = TextMain,
            onClick = { closeSheet(onOnlineUrl) },
        )
        SourceSheetAction(
            icon = Icons.Rounded.Refresh,
            iconTint = Color(0xFF7C3AED),
            iconBg = Color(0xFF7C3AED).copy(alpha = 0.12f),
            title = "恢复音源备份",
            subtitle = "选择此前导出的 .json 音源合集",
            titleColor = TextMain,
            onClick = { closeSheet { onLocalFile(arrayOf("application/json")) } },
        )
    }
}

@Composable
private fun OnlineSourceImportSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onImportSuccess: () -> Unit,
    onImport: (url: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) -> Unit,
) {
    val urlField = remember { TextFieldState() }
    var failure by remember { mutableStateOf<SourceImportFailure?>(null) }
    val url = urlField.text.toString()
    val currentFailure = failure?.takeIf { it.draft == url }
    if (failure != currentFailure) {
        SideEffect {
            if (failure?.draft != urlField.text.toString()) failure = null
        }
    }
    val errorMessage = currentFailure?.message
    val isCleartextHttp = url.trim().startsWith("http://", ignoreCase = true)
    val helperMessage = errorMessage ?: if (isCleartextHttp) {
        "HTTP 是明文连接，脚本内容可能在传输中被篡改。"
    } else {
        ""
    }

    SourceModalSheet(
        onDismiss = onDismiss,
        icon = Icons.Outlined.Link,
        title = "从链接导入",
        subtitle = "支持 HTTP/HTTPS 直链；HTTP 会显示明文传输警告",
    ) { closeSheet ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardBg,
            border = if (errorMessage != null || isCleartextHttp) {
                BorderStroke(1.dp, MeloraAppearance.accent)
            } else {
                MeloraAppearance.cardBorder
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    tint = if (errorMessage != null || isCleartextHttp) MeloraAppearance.accent else BrandBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    state = urlField,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    enabled = !busy,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = TextMain,
                        lineHeight = 18.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    cursorBrush = SolidColor(BrandBlue),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                    decorator = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (url.isEmpty()) {
                                Text(
                                    text = "输入或长按粘贴脚本链接 (http(s)://...)",
                                    fontSize = 13.sp,
                                    color = MeloraAppearance.textMuted,
                                    lineHeight = 18.sp,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    ),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (url.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MeloraAppearance.softFill)
                            .clickable { urlField.clearText() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = MeloraAppearance.textMuted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (helperMessage.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MeloraAppearance.accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = helperMessage,
                        fontSize = 11.sp,
                        color = MeloraAppearance.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        val canSubmit = !busy && url.trim().isNotBlank()
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { closeSheet(onDismiss) },
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                color = MeloraAppearance.softFill,
                border = MeloraAppearance.chipBorder,
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextSub)
                }
            }
            Surface(
                onClick = {
                    val submitted = urlField.text.toString()
                    failure = null
                    onImport(
                        submitted.trim(),
                        { closeSheet(onImportSuccess) },
                        { failure = SourceImportFailure(submitted, it) },
                    )
                },
                enabled = canSubmit,
                shape = RoundedCornerShape(12.dp),
                color = if (canSubmit) colors.primary else colors.primary.copy(alpha = 0.22f),
                contentColor = if (canSubmit) colors.onPrimary else Color.White.copy(alpha = 0.65f),
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = colors.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            text = if (isCleartextHttp) "仍要导入" else "立即导入",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceRow(
    script: LxScript,
    enabled: Boolean,
    actionStatus: SourceActionStatus?,
    onToggle: (Boolean) -> Unit,
    onTestValidity: () -> Unit,
    checkUpdate: (suspend () -> LxSourceUpdate?)?,
    onApplyUpdate: (LxSourceUpdate) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var sheetOpen by remember(script.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BrandBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Extension,
                contentDescription = null,
                tint = BrandBlue,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = script.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    script.version.takeIf { it.isNotBlank() }?.let {
                        if (it.startsWith("v", true)) it else "v$it"
                    },
                    script.description.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "音源脚本" },
                fontSize = 12.sp,
                color = TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = script.enabled,
            modifier = Modifier.semantics { contentDescription = script.name },
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = meloraSwitchColors(),
        )
        IconButton(
            onClick = { sheetOpen = true },
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "更多操作",
                tint = TextSub,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (sheetOpen) {
        val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
        val closeSheet = rememberSheetDismiss(sheetState)
        val actionBusy = actionStatus?.busy == true
        val metrics = LxScriptPool.metrics(script.id)
        MeloraBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            dragHandle = { MeloraSheetHandle(color = MeloraAppearance.divider) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(BrandBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Extension,
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = script.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (script.enabled) Color(0xFFE8F5E9) else MeloraAppearance.softFill,
                            ) {
                                Text(
                                    text = if (script.enabled) "已启用" else "已停用",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (script.enabled) Color(0xFF2E7D32) else TextSub,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = listOfNotNull(
                                script.version.takeIf { it.isNotBlank() }?.let {
                                    if (it.startsWith("v", true)) it else "v$it"
                                },
                                script.description.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { "本地沙箱音源脚本" },
                            fontSize = 12.sp,
                            color = TextSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                SourceStatusSummary(actionStatus = actionStatus, metrics = metrics)
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SourceSheetAction(
                        icon = Icons.Rounded.Build,
                        iconTint = BrandBlue,
                        iconBg = BrandBlue.copy(alpha = 0.10f),
                        title = "有效性测试",
                        subtitle = "检查脚本协议，逐平台验证播放链接",
                        titleColor = TextMain,
                        enabled = !actionBusy,
                    ) {
                        closeSheet {
                            sheetOpen = false
                            onTestValidity()
                        }
                    }

                    if (checkUpdate != null) {
                        LinkedSourceUpdateAction(script, !actionBusy, checkUpdate, onApplyUpdate)
                    }

                    SourceSheetAction(
                        icon = Icons.Rounded.Upload,
                        iconTint = Color(0xFF7C3AED),
                        iconBg = Color(0xFF7C3AED).copy(alpha = 0.10f),
                        title = "导出此源",
                        subtitle = "导出为独立 .js 脚本文件到本地存储",
                        titleColor = TextMain,
                        enabled = !actionBusy,
                    ) {
                        closeSheet {
                            sheetOpen = false
                            onExport()
                        }
                    }

                    SourceSheetAction(
                        icon = Icons.Rounded.Delete,
                        iconTint = Color(0xFFD1606A),
                        iconBg = Color(0xFFD1606A).copy(alpha = 0.10f),
                        title = "删除此源",
                        subtitle = "从本地沙箱完全移除此音源脚本",
                        titleColor = Color(0xFFD1606A),
                        enabled = !actionBusy,
                    ) {
                        closeSheet {
                            sheetOpen = false
                            onDelete()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedSourceUpdateAction(
    script: LxScript,
    enabled: Boolean,
    checkUpdate: suspend () -> LxSourceUpdate?,
    onApplyUpdate: (LxSourceUpdate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember(script) { mutableStateOf<Result<LxSourceUpdate?>?>(null) }
    val update = checked?.getOrNull()
    val error = checked?.exceptionOrNull()
    val subtitle = when {
        checking -> "正在从导入链接检查…"
        error != null -> "检查失败 · ${error.message ?: "请重试"}"
        update != null -> when {
            update.currentVersion.isNotBlank() && update.remoteVersion.isNotBlank() &&
                update.currentVersion != update.remoteVersion -> "${update.currentVersion} → ${update.remoteVersion}"
            else -> "远端脚本内容已变更，点按应用"
        }
        checked != null -> "已是最新版本"
        script.originUrl.orEmpty().startsWith("http://", ignoreCase = true) -> "从原 HTTP 明文链接检查更新"
        else -> "与导入链接中的脚本比较"
    }
    SourceSheetAction(
        icon = Icons.Rounded.Refresh,
        iconTint = BrandBlue,
        iconBg = BrandBlue.copy(alpha = 0.10f),
        title = if (update == null) "检查更新" else "应用更新",
        subtitle = subtitle,
        titleColor = TextMain,
        enabled = enabled && !checking,
    ) {
        if (update != null) {
            checked = null
            onApplyUpdate(update)
        } else {
            checking = true
            scope.launch {
                try {
                    checked = runCatchingCancellable { checkUpdate() }
                } finally {
                    checking = false
                }
            }
        }
    }
}

private val LinkStatus?.textColor: Color
    get() = when {
        this == null || this == LinkStatus.INCONCLUSIVE -> TextSub
        MeloraAppearance.isEInk -> TextMain
        this == LinkStatus.INVALID -> if (MeloraAppearance.isDark) MeloraAppearance.accent else Color(0xFFB42318)
        MeloraAppearance.isDark -> Color(0xFF73D69A)
        else -> Color(0xFF187241)
    }

private fun LxInspector.ValidityTest.summary(): SourceActionStatus {
    if (platforms.isEmpty()) return SourceActionStatus("脚本协议通过 · 未声明在线播放", result = LinkStatus.VALID)
    val valid = platforms.count { it.result?.status == LinkStatus.VALID }
    val invalid = platforms.count { it.result?.status == LinkStatus.INVALID }
    val unknown = platforms.size - valid - invalid
    val counts = listOfNotNull(
        "有效 ${valid} 项".takeIf { valid > 0 },
        "失效 ${invalid} 项".takeIf { invalid > 0 },
        "待确认 ${unknown} 项".takeIf { unknown > 0 },
    )
    val status = when (platforms.size) {
        valid -> LinkStatus.VALID
        invalid -> LinkStatus.INVALID
        else -> LinkStatus.INCONCLUSIVE
    }
    return SourceActionStatus("测试链接 · ${counts.joinToString("，")}", result = status)
}

@Composable
private fun ValidityTestSheet(
    store: LxScriptStore,
    script: LxScript,
    onFinished: (SourceActionStatus) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SourceModalSheet(
        onDismiss = onDismiss,
        icon = Icons.Rounded.Build,
        title = "有效性测试",
        subtitle = script.name,
    ) { closeSheet ->
        var attempt by remember { mutableIntStateOf(0) }
        var running by remember(attempt) { mutableStateOf(true) }
        var failure by remember(attempt) { mutableStateOf<String?>(null) }
        var report by remember(attempt) { mutableStateOf<LxInspector.ValidityTest?>(null) }
        LaunchedEffect(script.id, attempt) {
            val outcome = runCatchingCancellable {
                LxInspector.testValidity(context, store, script).collect { report = it }
            }
            failure = outcome.exceptionOrNull()?.let { it.message ?: "未知错误" }
            running = false
            val message = failure
            if (message != null) {
                val status = if (report == null) LinkStatus.INVALID else LinkStatus.INCONCLUSIVE
                val label = if (report == null) "脚本协议未通过" else "测试中断"
                onFinished(SourceActionStatus("$label · $message", result = status))
            } else {
                report?.let { onFinished(it.summary()) }
            }
        }
        val snapshot = report
        val tests = snapshot?.platforms.orEmpty()
        val current = tests.firstOrNull { it.result == null }?.source
        Column(modifier = Modifier.fillMaxWidth().height(392.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val status = when {
                        snapshot != null -> LinkStatus.VALID
                        failure != null -> LinkStatus.INVALID
                        else -> null
                    }
                    Text(
                        text = when (status) {
                            LinkStatus.VALID -> "脚本协议通过 · 声明 ${snapshot?.declaredPlatforms.orEmpty().joinToString("、")}"
                            LinkStatus.INVALID -> "脚本协议未通过 · $failure"
                            else -> "正在检查脚本协议…"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = status.textColor,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (tests.isNotEmpty()) {
                    item { Text("测试曲目 · ${LxInspector.sampleLabel}", fontSize = 12.sp, color = TextSub) }
                    items(tests, key = { it.source }) { test ->
                        PlaybackTestRow(test, running && test.source == current)
                    }
                } else if (snapshot != null) {
                    item { Text("未声明在线平台的播放能力", fontSize = 12.sp, color = TextSub) }
                }
                if (snapshot != null && failure != null) {
                    item { Text("测试中断 · $failure", fontSize = 12.sp, color = TextSub) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { attempt++ }, enabled = !running) { Text("重新测试") }
                TextButton(onClick = { closeSheet(onDismiss) }) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun PlaybackTestRow(test: LxInspector.PlatformTest, active: Boolean) {
    val result = test.result
    val request = listOfNotNull(
        test.requestedQuality?.let { "档位 $it" },
        test.elapsedMs?.let { "$it ms" },
    ).joinToString(" · ")
    val outcome = listOfNotNull(
        when (result?.status) {
            LinkStatus.VALID -> "链接有效"
            LinkStatus.INVALID -> "链接无效"
            LinkStatus.INCONCLUSIVE -> "待确认"
            null -> if (active) "正在测试…" else "等待测试"
        },
        result?.message,
        result?.spec?.playerSpecificationLabel()?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 44.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sourceAliasDisplay(test.source, platformLabel(test.source)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                if (request.isNotEmpty()) Text(request, fontSize = 12.sp, color = TextSub)
            }
            Text(
                text = outcome,
                fontSize = 12.sp,
                color = result?.status.textColor,
            )
        }
    }
}

@Composable
private fun SourceStatusSummary(
    actionStatus: SourceActionStatus?,
    metrics: LxScriptPool.ScriptMetrics?,
) {
    val statusColor = if (actionStatus?.busy == true) BrandBlue else actionStatus?.result.textColor
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (actionStatus?.busy == true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = BrandBlue,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = actionStatus?.message ?: "尚未运行有效性测试",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (actionStatus?.result != null) statusColor else TextMain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = metrics?.let {
                    "本次运行命中 ${it.hitCount} 次 · 平均延迟 ${it.averageLatencyMs} ms"
                } ?: "本次运行暂无播放命中",
                fontSize = 11.sp,
                color = TextSub,
                modifier = Modifier.padding(start = 15.dp),
            )
        }
    }
}

// 音源操作面板行：现代化圆角卡片式动作行，支持图标底色容器与副标题说明
@Composable
private fun SourceSheetAction(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    titleColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val dark = MeloraAppearance.isDark
    val destructive = titleColor == Color(0xFFD1606A)
    val warning = iconTint == MeloraAppearance.accent
    val resolvedIconBg = when {
        !dark -> iconBg
        destructive || warning -> MeloraAppearance.tintRed
        else -> Color(0xFF222226)
    }
    val resolvedIconTint = when {
        !dark -> iconTint
        destructive || warning -> MeloraAppearance.accent
        else -> MeloraAppearance.textMain.copy(alpha = 0.86f)
    }
    val resolvedTitleColor = if (dark && destructive) MeloraAppearance.accent else titleColor
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(resolvedIconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = resolvedIconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = resolvedTitleColor)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = if (destructive) resolvedTitleColor.copy(alpha = 0.78f) else TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSub.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background((if (enabled) tint else TextSub).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) tint else TextSub,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) TextMain else TextSub,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSub.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = TextSub,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
