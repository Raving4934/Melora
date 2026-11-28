package com.leyu.melora.ui.settings

import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.MeloraSheetActionBar
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.openPickedDocument
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
    var scripts by remember { mutableStateOf(emptyList<LxScript>()) }
    var sourceStatuses by remember { mutableStateOf<Map<String, SourceActionStatus>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var operationInProgress by remember { mutableStateOf(false) }
    val busy by remember { derivedStateOf { loading || operationInProgress } }
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

    LaunchedEffect(store) {
        val initial = runCatchingCancellable { withContext(Dispatchers.IO) { store.list() } }
        if (loading) {
            initial.onSuccess { scripts = it }
                .onFailure { notify(it.message ?: "无法读取自定义音源") }
            loading = false
        }
    }

    fun updateSourceStatus(scriptId: String, status: SourceActionStatus) {
        sourceStatuses = sourceStatuses + (scriptId to status)
    }

    fun reloadAllSources(message: String) {
        val operationId = ++sourceOperationId
        operationInProgress = true
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable { LxScriptPool.reload(context) }
            SourceResolver.clearCache()
            val refreshed = store.list()
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                scripts = refreshed
                operationInProgress = false
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

    fun completeSourceImport(imported: List<LxScript>, refreshed: List<LxScript>, onSuccess: () -> Unit) {
        scripts = refreshed
        loading = false
        operationInProgress = false
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
        operationInProgress = true
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable { import() to store.list() }
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                result.fold(
                    onSuccess = { (imported, refreshed) -> completeSourceImport(imported, refreshed, onSuccess) },
                    onFailure = { error ->
                        operationInProgress = false
                        onFailure(error.message ?: "未知错误")
                    },
                )
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchSourceImport(import = {
            val resolver = context.contentResolver
            val displayName = runCatchingCancellable {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            val input = resolver.openPickedDocument(uri, "读取所选文件失败，请重新选择")
            sourceImporter.importLocal(input, displayName)
        })
    }

    fun writeExport(uri: Uri, failure: String, payload: () -> Pair<String, String>) {
        val operationId = ++sourceOperationId
        operationInProgress = true
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable {
                val (document, success) = payload()
                val bytes = document.toByteArray()
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入文件")
                success
            }
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                notify(result.getOrDefault(failure))
                operationInProgress = false
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
            writeExport(uri, "导出「$name」失败") { store.exportScript(id) to "已导出「$name」" }
        }
    }

    val bundleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        writeExport(uri, "导出失败") {
            val (document, count) = store.exportBundle()
            document to "已导出 $count 个音源脚本"
        }
    }

    fun applySourceUpdate(update: LxSourceUpdate) {
        val id = update.scriptId
        if (busy || sourceStatuses[id]?.busy == true) return
        ++sourceOperationId
        operationInProgress = true
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
            operationInProgress = false
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
                    InfoText(if (loading) "正在读取音源脚本…" else "还没有音源脚本。点击下方「导入音源脚本」选择音源脚本文件（.js）即可开始使用。")
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
                                    if (!busy) {
                                        ++sourceOperationId
                                        operationInProgress = true
                                        scope.launch {
                                            runCatchingCancellable {
                                                withContext(Dispatchers.IO) {
                                                    val enabledIds = store.setEnabled(script.id, enabled)
                                                    SourceResolver.clearCache()
                                                    enabledIds
                                                }
                                            }.onSuccess { ids ->
                                                scripts = scripts.map { it.copy(enabled = it.id in ids) }
                                            }.onFailure { notify(it.message ?: "无法切换音源") }
                                            operationInProgress = false
                                        }
                                    }
                                },
                                onTestValidity = { validityTestScript = script },
                                checkUpdate = script.originUrl?.let { { sourceImporter.checkUpdate(script) } },
                                onApplyUpdate = ::applySourceUpdate,
                                onExport = {
                                    pendingExportId = script.id
                                    context.launchSystemFilePicker(onUnavailable = { pendingExportId = null }) {
                                        singleExporter.launch(script.id)
                                    }
                                },
                                onDelete = {
                                    val operationId = ++sourceOperationId
                                    val message = "已删除「${script.name}」"
                                    operationInProgress = true
                                    scope.launch(Dispatchers.IO) {
                                        val deleteResult = runCatchingCancellable {
                                            store.remove(script.id)
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
                                            operationInProgress = false
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
                    subtitle = "导出 .json 合集，保留音源更新链接",
                    tint = Color(0xFF7C3AED),
                    enabled = scripts.isNotEmpty() && !busy,
                ) { context.launchSystemFilePicker { bundleExporter.launch("melora-sources.json") } }
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
                context.launchSystemFilePicker { importer.launch(mimeTypes) }
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
    canDismiss: () -> Boolean = { true },
    content: @Composable ColumnScope.(closeSheet: (() -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true) {
        it != SheetValue.Hidden || canDismiss()
    }
    val closeSheet = rememberSheetDismiss(sheetState)
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { MeloraSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(
                    icon = icon, tint = BrandBlue,
                    background = if (MeloraAppearance.isDark) Color(0xFF222226) else BrandBlue.copy(alpha = 0.12f),
                    size = 44.dp, iconSize = 22.dp, radius = 12.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
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
    var imported by remember { mutableStateOf(false) }
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
        canDismiss = { !busy || imported },
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
                    enabled = !busy && !imported,
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

        val canSubmit = !busy && !imported && url.trim().isNotBlank()
        MeloraSheetActionBar(
            secondaryText = "取消",
            onSecondary = { closeSheet(onDismiss) },
            secondaryEnabled = !busy && !imported,
            primaryText = if (isCleartextHttp) "仍要导入" else "立即导入",
            onPrimary = {
                val submitted = urlField.text.toString()
                failure = null
                onImport(
                    submitted.trim(),
                    {
                        imported = true
                        closeSheet(onImportSuccess)
                    },
                    { failure = SourceImportFailure(submitted, it) },
                )
            },
            primaryEnabled = canSubmit,
            primaryWorking = busy,
            minHeight = 42.dp,
        )
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
        SourceIcon(Icons.Rounded.Extension, BrandBlue, size = 40.dp, iconSize = 22.dp, radius = 12.dp)
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
        val eInk = MeloraAppearance.isEInk
        val dark = MeloraAppearance.isDark
        val deleteColor = when {
            eInk -> TextMain
            dark -> MeloraAppearance.accent
            else -> Color(0xFFD14343)
        }
        MeloraBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            dragHandle = { MeloraSheetHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceIcon(
                        icon = Icons.Rounded.Extension, tint = BrandBlue,
                        background = if (MeloraAppearance.isDark) Color(0xFF222226) else BrandBlue.copy(alpha = 0.12f),
                        size = 44.dp, iconSize = 22.dp, radius = 12.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = script.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    eInk -> Color.White
                                    script.enabled && dark -> Color(0xFF193022)
                                    script.enabled -> Color(0xFFE8F5E9)
                                    else -> MeloraAppearance.softFill
                                },
                                border = if (eInk) MeloraAppearance.chipBorder else null,
                            ) {
                                Text(
                                    text = if (script.enabled) "已启用" else "已停用",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        eInk -> if (script.enabled) TextMain else TextSub
                                        script.enabled && dark -> Color(0xFF73D69A)
                                        script.enabled -> Color(0xFF2E7D32)
                                        else -> TextSub
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
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

                Spacer(Modifier.height(8.dp))
                SourceStatusSummary(actionStatus = actionStatus, metrics = metrics)
                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardBg,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SourceGroupActionRow(
                                icon = Icons.Rounded.Build,
                                iconTint = BrandBlue,
                                iconBg = if (dark) Color(0xFF222226) else BrandBlue.copy(alpha = 0.10f),
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
                            HorizontalDivider(
                                color = MeloraAppearance.divider,
                                thickness = 0.6.dp,
                                modifier = Modifier.padding(start = 64.dp, end = 14.dp),
                            )
                            if (checkUpdate != null) {
                                LinkedSourceUpdateAction(script, !actionBusy, checkUpdate, onApplyUpdate)
                                HorizontalDivider(
                                    color = MeloraAppearance.divider,
                                    thickness = 0.6.dp,
                                    modifier = Modifier.padding(start = 64.dp, end = 14.dp),
                                )
                            }
                            SourceGroupActionRow(
                                icon = Icons.Rounded.Upload,
                                iconTint = TextMain.copy(alpha = 0.78f),
                                iconBg = MeloraAppearance.softFill,
                                title = "导出此源",
                                subtitle = if (script.originUrl != null) "导出 .js 文件，包含原更新链接" else "导出为独立 .js 脚本文件到本地存储",
                                titleColor = TextMain,
                                enabled = !actionBusy,
                            ) {
                                closeSheet {
                                    sheetOpen = false
                                    onExport()
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardBg,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SourceGroupActionRow(
                            icon = Icons.Outlined.DeleteOutline,
                            iconTint = deleteColor,
                            iconBg = MeloraAppearance.tintRed,
                            destructive = true,
                            title = "删除此源",
                            subtitle = "从本地沙箱完全移除此音源脚本",
                            titleColor = deleteColor,
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
    SourceGroupActionRow(
        icon = Icons.Rounded.Refresh,
        iconTint = BrandBlue,
        iconBg = if (MeloraAppearance.isDark) Color(0xFF222226) else BrandBlue.copy(alpha = 0.10f),
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
    val unknown = platforms.count { it.result?.status == LinkStatus.INCONCLUSIVE }
    val pending = platforms.size - valid - invalid - unknown
    val counts = listOfNotNull(
        "有效 ${valid} 项".takeIf { valid > 0 },
        "失效 ${invalid} 项".takeIf { invalid > 0 },
        "待确认 ${unknown} 项".takeIf { unknown > 0 },
        "未测试 ${pending} 项".takeIf { pending > 0 },
    )
    val status = when (platforms.size) {
        valid -> LinkStatus.VALID
        invalid -> LinkStatus.INVALID
        else -> LinkStatus.INCONCLUSIVE
    }
    return SourceActionStatus("测试链接 · ${counts.joinToString("，")}", result = status)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValidityTestSheet(
    store: LxScriptStore,
    script: LxScript,
    onFinished: (SourceActionStatus) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    var attempt by remember(script.id) { mutableIntStateOf(0) }
    var running by remember(script.id, attempt) { mutableStateOf(true) }
    var protocolChecked by remember(script.id, attempt) { mutableStateOf(false) }
    var failure by remember(script.id, attempt) { mutableStateOf<String?>(null) }
    var report by remember(script.id) { mutableStateOf<LxInspector.ValidityTest?>(null) }
    LaunchedEffect(script.id, attempt) {
        val outcome = runCatchingCancellable {
            LxInspector.testValidity(context, store, script).collect {
                report = it
                protocolChecked = true
            }
        }
        if (!protocolChecked) report = null
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
    val current = if (protocolChecked) tests.firstOrNull { it.result == null }?.source else null
    val eInk = MeloraAppearance.isEInk
    val dark = MeloraAppearance.isDark

    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { MeloraSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .fillMaxHeight(0.84f)
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp)
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "有效性测试",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (tests.isNotEmpty()) "${script.name} · 试播 ${LxInspector.sampleLabel}" else script.name,
                        fontSize = 12.sp,
                        color = TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tests.isNotEmpty()) {
                    val doneCount = tests.count { it.result != null }
                    val summary = snapshot?.summary()
                    val summaryLabel = summary?.message?.removePrefix("测试链接 · ").orEmpty()
                    val pillBg = when {
                        running -> if (eInk) Color.White else MeloraAppearance.softFill
                        summary?.result == LinkStatus.VALID -> when {
                            eInk -> Color.White
                            dark -> Color(0xFF172D20)
                            else -> Color(0xFFE7F6EC)
                        }
                        summary?.result == LinkStatus.INVALID -> when {
                            eInk -> Color.White
                            dark -> Color(0xFF311A1D)
                            else -> Color(0xFFFDECEC)
                        }
                        else -> if (eInk) Color.White else MeloraAppearance.softFill
                    }
                    val pillBorder = if (eInk) MeloraAppearance.chipBorder else null
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = pillBg,
                        border = pillBorder,
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            if (running) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(11.dp),
                                        strokeWidth = 1.5.dp,
                                        color = if (eInk) TextMain else BrandBlue,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "已测 $doneCount/${tests.size}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextSub,
                                        style = TextStyle(fontFeatureSettings = "tnum"),
                                    )
                                }
                            } else if (summary != null) {
                                Text(
                                    text = summaryLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = summary.result.textColor,
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ValidityProtocolBanner(snapshot, protocolChecked, failure)
                if (tests.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardBg,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            tests.forEachIndexed { index, test ->
                                PlaybackTestRow(test, running, active = running && test.source == current)
                                if (index < tests.lastIndex) {
                                    HorizontalDivider(
                                        color = MeloraAppearance.divider,
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(start = 44.dp, end = 14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            MeloraSheetActionBar(
                secondaryText = "重新测试",
                onSecondary = {
                    report = snapshot?.copy(platforms = tests.map { it.copy(result = null, elapsedMs = null) })
                    attempt++
                },
                secondaryEnabled = !running,
                primaryText = "关闭",
                onPrimary = { closeSheet(onDismiss) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ValidityProtocolBanner(snapshot: LxInspector.ValidityTest?, protocolChecked: Boolean, failure: String?) {
    val eInk = MeloraAppearance.isEInk
    val dark = MeloraAppearance.isDark
    val protocolStatus = when {
        protocolChecked -> LinkStatus.VALID
        failure != null -> LinkStatus.INVALID
        else -> null
    }
    val bannerBg = when (protocolStatus) {
        LinkStatus.VALID -> when {
            eInk -> Color.White
            dark -> Color(0xFF17241C)
            else -> Color(0xFFF3FAF5)
        }
        LinkStatus.INVALID -> when {
            eInk -> Color.White
            dark -> Color(0xFF28181A)
            else -> Color(0xFFFEF4F4)
        }
        else -> CardBg
    }
    val bannerBorder = when {
        eInk || protocolStatus == null -> MeloraAppearance.cardBorder
        protocolStatus == LinkStatus.VALID -> BorderStroke(
            0.6.dp,
            if (dark) Color(0xFF23392B) else Color(0xFFD4EEDB),
        )
        else -> BorderStroke(
            0.6.dp,
            if (dark) Color(0xFF442528) else Color(0xFFF6D2D2),
        )
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bannerBg,
        border = bannerBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (protocolStatus) {
                        LinkStatus.VALID -> Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = protocolStatus.textColor,
                            modifier = Modifier.size(15.dp),
                        )
                        LinkStatus.INVALID -> Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = protocolStatus.textColor,
                            modifier = Modifier.size(15.dp),
                        )
                        else -> CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.6.dp,
                            color = if (eInk) TextMain else BrandBlue,
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = when (protocolStatus) {
                        LinkStatus.VALID -> "脚本协议通过"
                        LinkStatus.INVALID -> "脚本协议未通过 · $failure"
                        else -> "正在检查脚本协议…"
                    },
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = protocolStatus.textColor,
                    modifier = Modifier.weight(1f),
                )
            }
            if (snapshot != null) {
                val declared = snapshot.declaredPlatforms.map { id ->
                    sourceAliasDisplay(id, platformLabel(id, fallback = id))
                }
                Text(
                    text = (if (protocolChecked) "声明平台 · " else "上次声明 · ") + declared.joinToString("、"),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = TextSub,
                    modifier = Modifier.padding(start = 22.dp, top = 3.dp),
                )
                if (snapshot.platforms.isEmpty()) {
                    Text(
                        text = "未声明在线平台的播放能力",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = TextSub,
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                    )
                }
            }
            if (snapshot != null && failure != null) {
                Text(
                    text = "测试中断 · $failure",
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = LinkStatus.INVALID.textColor,
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaybackTestRow(test: LxInspector.PlatformTest, running: Boolean, active: Boolean) {
    val result = test.result
    val eInk = MeloraAppearance.isEInk
    val dark = MeloraAppearance.isDark
    val status = when (result?.status) {
        LinkStatus.VALID -> "链接有效"
        LinkStatus.INVALID -> "链接无效"
        LinkStatus.INCONCLUSIVE -> "待确认"
        null -> when {
            active -> "正在测试…"
            running -> "等待测试"
            else -> "未测试"
        }
    }
    val detail = result?.spec?.playerSpecificationLabel()?.takeIf(String::isNotBlank) ?: result?.message
    val statusTextColor = when (result?.status) {
        LinkStatus.VALID -> LinkStatus.VALID.textColor
        LinkStatus.INVALID -> LinkStatus.INVALID.textColor
        LinkStatus.INCONCLUSIVE -> if (eInk) TextMain else if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
        null -> TextSub
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                result?.status == LinkStatus.VALID -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = LinkStatus.VALID.textColor,
                    modifier = Modifier.size(16.dp),
                )
                result?.status == LinkStatus.INVALID -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = LinkStatus.INVALID.textColor,
                    modifier = Modifier.size(16.dp),
                )
                result?.status == LinkStatus.INCONCLUSIVE -> Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                eInk -> TextSub
                                dark -> Color(0xFFFBBF24)
                                else -> Color(0xFFD97706)
                            },
                        ),
                )
                active -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = if (eInk) TextMain else BrandBlue,
                )
                else -> Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MeloraAppearance.textMuted.copy(alpha = 0.4f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sourceAliasDisplay(test.source, platformLabel(test.source)),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                test.requestedQuality?.let { quality ->
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MeloraAppearance.softFill.copy(alpha = 0.7f),
                        border = MeloraAppearance.chipBorder,
                    ) {
                        Text(
                            text = quality,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSub,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 0.5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = statusTextColor, fontWeight = FontWeight.Medium)) {
                        append(status)
                    }
                    if (!detail.isNullOrBlank()) {
                        withStyle(SpanStyle(color = MeloraAppearance.textMuted)) { append(" · ") }
                        withStyle(SpanStyle(color = TextSub)) { append(detail) }
                    }
                },
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = if (result?.status == LinkStatus.VALID) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        test.elapsedMs?.let { ms ->
            Spacer(Modifier.width(10.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = if (eInk) TextMain else TextMain.copy(alpha = 0.82f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append(ms.toString())
                    }
                    withStyle(
                        SpanStyle(
                            color = MeloraAppearance.textMuted,
                            fontSize = 10.5.sp,
                        ),
                    ) {
                        append(" ms")
                    }
                },
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun SourceStatusSummary(
    actionStatus: SourceActionStatus?,
    metrics: LxScriptPool.ScriptMetrics?,
) {
    val busy = actionStatus?.busy == true
    val eInk = MeloraAppearance.isEInk
    val statusColor = if (busy) (if (eInk) TextMain else BrandBlue) else actionStatus?.result.textColor
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardBg,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        busy -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = statusColor,
                        )
                        actionStatus?.result == LinkStatus.VALID -> Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(15.dp),
                        )
                        actionStatus?.result == LinkStatus.INVALID -> Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(15.dp),
                        )
                        else -> Box(
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (actionStatus?.result != null) statusColor else TextMain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = metrics?.let {
                    "本次运行命中 ${it.hitCount} 次 · 平均延迟 ${it.averageLatencyMs} ms"
                } ?: "本次运行暂无播放命中",
                fontSize = 12.sp,
                color = TextSub,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}

@Composable
private fun SourceIcon(
    icon: ImageVector,
    tint: Color,
    size: Dp,
    iconSize: Dp,
    radius: Dp,
    background: Color = tint.copy(alpha = 0.12f),
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val ink = MeloraAppearance.isEInk
    val reverse = ink && destructive && enabled
    val fill = when {
        reverse -> TextMain
        ink -> MeloraAppearance.softFill
        else -> background
    }
    val foreground = when {
        !enabled -> if (ink) MeloraAppearance.textMuted else TextSub
        reverse -> CardBg
        ink -> TextMain
        else -> tint
    }
    Box(
        modifier = Modifier.size(size).background(fill, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun SourceGroupActionRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    titleColor: Color,
    enabled: Boolean = true,
    destructive: Boolean = false,
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(
                icon, iconTint, size = 36.dp, iconSize = 19.dp, radius = 10.dp,
                background = iconBg, enabled = enabled, destructive = destructive,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) titleColor else TextSub,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextSub,
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

// 音源操作面板行：共用分组动作行并套用独立卡片边框（供导入选择抽屉使用）
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
    val resolvedIconBg = if (dark) Color(0xFF222226) else iconBg
    val resolvedIconTint = if (dark) MeloraAppearance.textMain.copy(alpha = 0.86f) else iconTint
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SourceGroupActionRow(
            icon = icon,
            iconTint = resolvedIconTint,
            iconBg = resolvedIconBg,
            title = title,
            subtitle = subtitle,
            titleColor = titleColor,
            enabled = enabled,
            onClick = onClick,
        )
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
            SourceIcon(
                icon, tint = if (enabled) tint else TextSub,
                size = 38.dp, iconSize = 20.dp, radius = 11.dp, enabled = enabled,
            )
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
