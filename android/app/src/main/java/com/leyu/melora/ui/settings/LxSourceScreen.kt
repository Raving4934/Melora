package com.leyu.melora.ui.settings

import com.leyu.melora.ui.common.MeloraBottomSheet
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.lx.LxScript
import com.leyu.melora.playback.lx.LxScriptEngine
import com.leyu.melora.playback.lx.LxSourceImporter
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.sdk.SourceResolver
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val TextMain: Color get() = MeloraAppearance.textMain
private val TextSub: Color get() = MeloraAppearance.textSub
private val CardBg: Color get() = MeloraAppearance.card
private val BrandBlue: Color get() = MeloraAppearance.brand
private val DividerSoft: Color get() = MeloraAppearance.divider

private data class SourceActionStatus(
    val message: String,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

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
    var pendingExport by remember { mutableStateOf<LxScript?>(null) }
    var importChooserOpen by remember { mutableStateOf(false) }
    var onlineImportOpen by remember { mutableStateOf(false) }
    var onlineImportError by remember { mutableStateOf<String?>(null) }
    val sourceImporter = remember(store) { LxSourceImporter(store) }
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
        if (imported.isEmpty()) {
            notify("未识别到可导入的音源脚本")
            return
        }
        scripts = store.list()
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

    val singleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/javascript"),
    ) { uri: Uri? ->
        val target = pendingExport
        if (uri != null && target != null) {
            val operationId = ++sourceOperationId
            busy = true
            scope.launch(Dispatchers.IO) {
                val ok = runCatchingCancellable {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write((store.code(target.id) ?: error("脚本不存在")).toByteArray())
                    } ?: error("无法写入文件")
                }.isSuccess
                withContext(Dispatchers.Main) {
                    if (operationId != sourceOperationId) return@withContext
                    notify(if (ok) "已导出「${target.name}」" else "导出「${target.name}」失败")
                    pendingExport = null
                    busy = false
                }
            }
        } else {
            pendingExport = null
        }
    }

    val bundleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exportedScripts = scripts
        val operationId = ++sourceOperationId
        busy = true
        scope.launch(Dispatchers.IO) {
            val ok = runCatchingCancellable {
                val array = JSONArray()
                exportedScripts.forEach { script ->
                    array.put(JSONObject().put("name", script.id).put("code", store.code(script.id) ?: error("脚本不存在：${script.name}")))
                }
                val payload = JSONObject()
                    .put("app", "melora")
                    .put("type", "lx-sources")
                    .put("scripts", array)
                    .toString(2)
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                    ?: error("无法写入文件")
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                notify(if (ok) "已导出 ${exportedScripts.size} 个音源脚本" else "导出失败")
                busy = false
            }
        }
    }

    fun inspectScript(target: LxScript) {
        if (sourceStatuses[target.id]?.busy == true) return
        updateSourceStatus(target.id, SourceActionStatus("正在检查脚本…", busy = true))
        scope.launch(Dispatchers.IO) {
            val outcome = runCatchingCancellable {
                LxScriptEngine(context).use { engine ->
                    val initialized = engine.initialize(
                        code = store.code(target.id) ?: error("脚本文件不存在"),
                        fileName = target.id,
                        timeoutMs = 8_000,
                    )
                    val sources = initialized?.optJSONObject("sources")
                    val names = sources?.keys()?.asSequence()?.toList().orEmpty()
                    if (names.isEmpty()) error("脚本未声明音源")
                    names
                }
            }
            withContext(Dispatchers.Main) {
                updateSourceStatus(
                    target.id,
                    outcome.fold(
                        onSuccess = { names -> SourceActionStatus("检查通过 · 支持 ${names.joinToString("、")}") },
                        onFailure = { error ->
                            SourceActionStatus("检查失败 · ${error.message ?: error.javaClass.simpleName}", failed = true)
                        },
                    ),
                )
            }
        }
    }

    fun updateLinkedSource(target: LxScript) {
        if (sourceStatuses[target.id]?.busy == true) return
        updateSourceStatus(target.id, SourceActionStatus("正在检查远端更新…", busy = true))
        scope.launch(Dispatchers.IO) {
            val outcome = runCatchingCancellable {
                val update = sourceImporter.updateFromOrigin(target)
                val refreshError = if (update.updated) {
                    SourceResolver.clearCache()
                    runCatchingCancellable { LxScriptPool.refresh(context, setOf(target.id)) }.exceptionOrNull()
                } else {
                    null
                }
                Triple(update, refreshError, store.list())
            }
            withContext(Dispatchers.Main) {
                outcome.fold(
                    onSuccess = { (update, refreshError, refreshed) ->
                        scripts = refreshed
                        val message = when {
                            !update.updated -> "已是最新版本"
                            refreshError == null -> "发现更新，已下载并重新加载"
                            else -> "已下载更新，但重新加载失败：${refreshError.message ?: "未知错误"}"
                        }
                        updateSourceStatus(target.id, SourceActionStatus(message, failed = refreshError != null))
                    },
                    onFailure = { error ->
                        updateSourceStatus(
                            target.id,
                            SourceActionStatus("检查更新失败 · ${error.message ?: error.javaClass.simpleName}", failed = true),
                        )
                    },
                )
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = com.leyu.melora.ui.common.chromeContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionCaption("音源行为") }
        item {
            SettingsCard {
                SwitchRow(
                    title = "自动换源",
                    subtitle = if (autoSwitch) {
                        "已开启的源解析失败时，自动尝试其他已安装音源"
                    } else {
                        "仅使用已开启的源，解析失败不切换"
                    },
                    checked = autoSwitch,
                    onCheckedChange = { MeloraSettings.updateAutoSwitchSource(it) },
                )
                HorizontalDivider(color = DividerSoft, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 14.dp))
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
                HorizontalDivider(color = DividerSoft, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 14.dp))
                DataChannelRow()
            }
        }

        item { SectionCaption(if (scripts.isEmpty()) "已安装音源" else "已安装音源 · ${scripts.size}") }
        if (scripts.isEmpty()) {
            item {
                SettingsCard {
                    InfoText("还没有音源脚本。点击下方「导入音源脚本」选择音源脚本文件（.js）即可开始使用。")
                }
            }
        } else {
            item {
                SettingsCard {
                    scripts.forEachIndexed { index, script ->
                        key(script.id) {
                            if (index > 0) SettingsDivider()
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
                                onInspect = { inspectScript(script) },
                                onUpdate = if (script.originUrl != null) ({ updateLinkedSource(script) }) else null,
                                onExport = {
                                    pendingExport = script
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
            SettingsCard {
                ActionRow(
                    icon = Icons.Rounded.Add,
                    label = "导入音源脚本",
                    subtitle = "支持本地文件与 HTTP/HTTPS 直链导入",
                    tint = Color(0xFF1E88E5),
                    enabled = !busy,
                ) {
                    importChooserOpen = true
                }
                SettingsDivider()
                ActionRow(
                    icon = Icons.Rounded.Upload,
                    label = if (scripts.isEmpty()) "导出全部音源" else "导出全部音源（${scripts.size} 个）",
                    subtitle = "将当前已安装音源打包为 .json 备份",
                    tint = Color(0xFF7C3AED),
                    enabled = scripts.isNotEmpty() && !busy,
                ) { bundleExporter.launch("melora-sources.json") }
                SettingsDivider()
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
            onLocalFile = {
                importChooserOpen = false
                importer.launch(arrayOf("application/javascript", "application/json", "text/*", "*/*"))
            },
            onOnlineUrl = {
                importChooserOpen = false
                onlineImportError = null
                onlineImportOpen = true
            },
        )
    }
    if (onlineImportOpen) {
        OnlineSourceImportSheet(
            busy = busy,
            errorMessage = onlineImportError,
            onErrorClear = { onlineImportError = null },
            onDismiss = { if (!busy) onlineImportOpen = false },
            onImport = { url ->
                onlineImportError = null
                launchSourceImport(
                    import = { sourceImporter.importUrl(url) },
                    onSuccess = { onlineImportOpen = false },
                    onFailure = { onlineImportError = it },
                )
            },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CardBg,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
        color = DividerSoft,
        thickness = 0.6.dp,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextMain)
            Text(subtitle, fontSize = 12.sp, color = TextSub, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandBlue,
                uncheckedTrackColor = MeloraAppearance.softFill,
            ),
        )
    }
}

@Composable
private fun SourceSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MeloraAppearance.divider),
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MeloraAppearance.canvas,
        dragHandle = { SourceSheetDragHandle() },
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
                        .background(BrandBlue.copy(alpha = 0.12f)),
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
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(
                color = DividerSoft,
                thickness = 0.6.dp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ImportSourceSheet(
    onDismiss: () -> Unit,
    onLocalFile: () -> Unit,
    onOnlineUrl: () -> Unit,
) {
    SourceModalSheet(
        onDismiss = onDismiss,
        icon = Icons.Outlined.FolderOpen,
        title = "导入音源脚本",
        subtitle = "选择从本地文件选取或从网络链接导入兼容 JS 音源",
    ) {
        SourceSheetAction(
            icon = Icons.Outlined.FolderOpen,
            iconTint = BrandBlue,
            iconBg = BrandBlue.copy(alpha = 0.12f),
            title = "选择本地文件",
            subtitle = "导入设备存储中的 .js 脚本或 .json 音源合集",
            titleColor = TextMain,
            onClick = onLocalFile,
        )
        SourceSheetAction(
            icon = Icons.Outlined.Link,
            iconTint = Color(0xFF059669),
            iconBg = Color(0xFF059669).copy(alpha = 0.12f),
            title = "从链接导入",
            subtitle = "粘贴 HTTP/HTTPS 音源脚本直链",
            titleColor = TextMain,
            onClick = onOnlineUrl,
        )
    }
}

@Composable
private fun OnlineSourceImportSheet(
    busy: Boolean,
    errorMessage: String?,
    onErrorClear: () -> Unit,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
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
    ) {
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
                    value = url,
                    onValueChange = {
                        url = it
                        if (errorMessage != null) onErrorClear()
                    },
                    singleLine = true,
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
                    decorationBox = { innerTextField ->
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
                            .clickable {
                                url = ""
                                onErrorClear()
                            },
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onDismiss,
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
                onClick = { onImport(url.trim()) },
                enabled = canSubmit,
                shape = RoundedCornerShape(12.dp),
                color = if (canSubmit) BrandBlue else BrandBlue.copy(alpha = 0.22f),
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            text = if (isCleartextHttp) "仍要导入" else "立即导入",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (canSubmit) Color.White else Color.White.copy(alpha = 0.65f),
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
    onInspect: () -> Unit,
    onUpdate: (() -> Unit)?,
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
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandBlue,
                uncheckedTrackColor = MeloraAppearance.softFill,
            ),
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val actionBusy = actionStatus?.busy == true
        val metrics = LxScriptPool.metrics(script.id)
        MeloraBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = MeloraAppearance.canvas,
            dragHandle = { SourceSheetDragHandle() },
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
                        iconTint = Color(0xFF1E88E5),
                        iconBg = Color(0xFF1E88E5).copy(alpha = 0.10f),
                        title = "检查脚本",
                        subtitle = "检查脚本语法与平台能力声明",
                        titleColor = TextMain,
                        enabled = !actionBusy,
                        onClick = onInspect,
                    )

                    if (onUpdate != null) {
                        SourceSheetAction(
                            icon = Icons.Rounded.Refresh,
                            iconTint = if (script.originUrl?.startsWith("http://", true) == true) {
                                MeloraAppearance.accent
                            } else {
                                Color(0xFF0284C7)
                            },
                            iconBg = if (script.originUrl?.startsWith("http://", true) == true) {
                                MeloraAppearance.tintRed
                            } else {
                                Color(0xFF0284C7).copy(alpha = 0.10f)
                            },
                            title = "检查更新",
                            subtitle = if (script.originUrl?.startsWith("http://", true) == true) {
                                "HTTP 明文连接，更新内容可能被篡改"
                            } else {
                                "发现新版本时自动替换并重新加载"
                            },
                            titleColor = TextMain,
                            enabled = !actionBusy,
                            onClick = onUpdate,
                        )
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
                        sheetOpen = false
                        onExport()
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
                        sheetOpen = false
                        onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceStatusSummary(
    actionStatus: SourceActionStatus?,
    metrics: LxScriptPool.ScriptMetrics?,
) {
    val statusColor = when {
        actionStatus?.failed == true -> Color(0xFFD1606A)
        actionStatus != null -> BrandBlue
        else -> Color(0xFF10B981) // 绿色正常
    }
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
                    text = actionStatus?.message ?: "检查通过 · 脚本状态正常",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (actionStatus?.failed == true) statusColor else TextMain,
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
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = if (titleColor == Color(0xFFD1606A)) Color(0xFFD1606A).copy(alpha = 0.75f) else TextSub,
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

// 数据通道：auto=App 优先自动回退，app=App 优先，web=网页端优先
@Composable
private fun DataChannelRow() {
    val channel by MeloraSettings.dataChannel.collectAsStateWithLifecycle()
    val options = listOf("auto" to "自动", "app" to "App", "web" to "网页")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("数据通道", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextMain)
            Text(
                text = when (channel) {
                    "app" -> "优先请求各平台 App 端接口，失败回退网页端"
                    "web" -> "优先请求网页端接口，失败回退 App 端"
                    else -> "自动优先 App 轻量接口，失败回退网页端"
                },
                fontSize = 12.sp,
                color = TextSub,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MeloraAppearance.segmentTrack) {
            Row(modifier = Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                options.forEach { (value, label) ->
                    val selected = channel == value
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MeloraAppearance.card else Color.Transparent,
                        shadowElevation = if (selected) 1.dp else 0.dp,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            MeloraSettings.updateDataChannel(value)
                            SourceResolver.clearCache()
                        },
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
