package com.leyu.melora.ui.settings

import com.leyu.melora.ui.theme.SystemBarsVisibility
import android.net.Uri
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.NetworkState
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.lx.LxScript
import com.leyu.melora.playback.lx.LxScriptEngine
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.sdk.OnlineRepository
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

// 自定义源管理：自动换源开关 + 已装音源（启停/检查/导出/删除）+ 导入导出。
@Composable
fun LxSourceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LxScriptStore(context) }
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(store.list()) }
    var status by remember { mutableStateOf("音源脚本完全由用户导入，在本地沙箱中运行。") }
    var busy by remember { mutableStateOf(false) }
    var sourceOperationId by remember { mutableIntStateOf(0) }
    var pendingExport by remember { mutableStateOf<LxScript?>(null) }
    val sourceAlias by MeloraSettings.sourceAliasEnabled.collectAsStateWithLifecycle()
    val autoSwitch by MeloraSettings.autoSwitchSource.collectAsStateWithLifecycle()

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
                status = result.fold(
                    onSuccess = { "$message，已加载 $it 个音源脚本" },
                    onFailure = { "$message，但重新加载失败：${it.message ?: "未知错误"}" },
                )
                busy = false
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
                status = result.fold(
                    onSuccess = { "$message，后台初始化完成，当前可用 $it 个音源脚本" },
                    onFailure = { "$message，但后台初始化失败：${it.message ?: "未知错误"}" },
                )
            }
        }
    }

    // 导入文件落盘后立即恢复交互，仅在后台初始化新增或覆盖的脚本。
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val operationId = ++sourceOperationId
        busy = true
        status = "正在导入音源脚本…"
        scope.launch(Dispatchers.IO) {
            val result = runCatchingCancellable {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?.takeIf { it.isNotBlank() }
                    ?: error("读取所选文件失败")
                val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                } ?: uri.lastPathSegment
                val imported = importPayload(store, text, displayName)
                imported to store.list()
            }
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                result.fold(
                    onSuccess = { (imported, refreshed) ->
                        busy = false
                        if (imported.isEmpty()) {
                            status = "未识别到可导入的音源脚本"
                        } else {
                            scripts = refreshed
                            val message = "已导入 ${imported.joinToString("、") { it.name }}"
                            status = "$message，正在后台初始化…"
                            refreshChangedSources(imported.mapTo(linkedSetOf()) { it.id }, message)
                        }
                    },
                    onFailure = {
                        status = it.message ?: "导入音源脚本失败"
                        busy = false
                    },
                )
            }
        }
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
                    status = if (ok) "已导出「${target.name}」" else "导出「${target.name}」失败"
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
                status = if (ok) "已导出 ${exportedScripts.size} 个音源脚本" else "导出失败"
                busy = false
            }
        }
    }

    fun inspectScript(target: LxScript, resolveSample: Boolean) {
        val operationId = ++sourceOperationId
        busy = true
        scope.launch(Dispatchers.IO) {
            val outcome = runCatchingCancellable {
                LxScriptEngine(context).use { engine ->
                    val inited = engine.initialize(store.code(target.id) ?: error("脚本文件不存在"), target.id, 8_000)
                    val sources = inited?.optJSONObject("sources")
                    val names = sources?.keys()?.asSequence()?.toList().orEmpty()
                    if (names.isEmpty()) error("脚本未声明音源")
                    if (!resolveSample) return@use Triple(names, "", "")
                    val sourceId = names.first()
                    val qualitys = sources!!.optJSONObject(sourceId)?.optJSONArray("qualitys")
                        ?.let { array -> (0 until array.length()).map { array.optString(it) } }
                        .orEmpty()
                    val testSong = OnlineRepository.search(context, sourceId, "晴天 周杰伦", 1, 5).list
                        .firstOrNull { it.qualitys.isNotEmpty() } ?: error("无法获取测试歌曲")
                    val targetQuality = qualitys.firstOrNull { it in testSong.qualitys } ?: "128k"
                    val data = engine.request(
                        sourceId,
                        "musicUrl",
                        JSONObject()
                            .put("type", targetQuality)
                            .put("musicInfo", testSong.raw),
                        25_000,
                    )
                    val url = when (data) {
                        is String -> data
                        is JSONObject -> data.optString("url")
                        else -> ""
                    }
                    if (url.isBlank()) error("未返回有效地址")
                    Triple(names, sourceId, "「${testSong.name}」$targetQuality -> ${url.take(120)}")
                }
            }
            withContext(Dispatchers.Main) {
                if (operationId != sourceOperationId) return@withContext
                busy = false
                outcome
                    .onSuccess { (names, sourceId, detail) ->
                        status = if (detail.isEmpty()) {
                            "「${target.name}」声明音源：${names.joinToString("、")}"
                        } else {
                            "「${target.name}」$sourceId 解析成功：$detail"
                        }
                    }
                    .onFailure { status = "「${target.name}」检查失败：${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    fun testPipeline() {
        if (scripts.none { it.enabled }) {
            status = "请先开启至少一个音源脚本"
            return
        }
        val operationId = ++sourceOperationId
        busy = true
        scope.launch {
            val outcome = runCatchingCancellable {
                val song = OnlineRepository.search(context, "kw", "晴天 周杰伦", 1, 5).list.firstOrNull()
                    ?: error("无法获取测试歌曲")
                SourceResolver.resolve(
                    context = context,
                    song = song,
                    preferredQuality = NetworkState.playQuality(context),
                    allowSwitch = MeloraSettings.autoSwitchSource.value,
                    isRefresh = true,
                )
            }
            if (operationId != sourceOperationId) return@launch
            busy = false
            outcome
                .onSuccess { resolved ->
                    status = "整链路解析成功：${resolved.song.name} · ${resolved.quality}\n${resolved.url.take(120)}"
                    PlaybackController.playTrack(context, UiTrack.fromOnline(resolved.song))
                }
                .onFailure { status = "整链路解析失败：${it.message ?: it.javaClass.simpleName}" }
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
                        if (index > 0) SettingsDivider()
                        SourceRow(
                            script = script,
                            enabled = !busy,
                            onToggle = { enabled ->
                                ++sourceOperationId
                                val enabledIds = store.setEnabled(script.id, enabled)
                                scripts = scripts.map { item ->
                                    item.copy(enabled = item.id in enabledIds)
                                }
                                SourceResolver.clearCache()
                                status = if (enabled) "已开启「${script.name}」" else "已关闭「${script.name}」"
                            },
                            onInspect = { inspectScript(script, resolveSample = script.enabled) },
                            onExport = {
                                pendingExport = script
                                singleExporter.launch(script.id)
                            },
                            onDelete = {
                                val operationId = ++sourceOperationId
                                val message = "已删除「${script.name}」"
                                busy = true
                                status = "正在删除「${script.name}」并释放对应引擎…"
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
                                        status = deleteResult.fold(
                                            onSuccess = {
                                                refreshResult?.fold(
                                                    onSuccess = { count -> "$message，当前可用 $count 个音源脚本" },
                                                    onFailure = { error -> "$message，但引擎刷新失败：${error.message ?: "未知错误"}" },
                                                ) ?: "$message，当前可用脚本列表已更新"
                                            },
                                            onFailure = { error -> "删除「${script.name}」失败：${error.message ?: "未知错误"}" },
                                        )
                                        busy = false
                                    }
                                }
                            },
                        )
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
                    subtitle = "选择本地 .js 脚本或 .json 音源合集导入",
                    tint = Color(0xFF1E88E5),
                    enabled = !busy,
                ) {
                    importer.launch(arrayOf("application/javascript", "application/json", "text/*", "*/*"))
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
                    status = "正在重新加载音源脚本…"
                    reloadAllSources("已重新加载音源脚本并清空解析缓存")
                }
                SettingsDivider()
                ActionRow(
                    icon = Icons.Rounded.PlayArrow,
                    label = "测试解析并试听",
                    subtitle = "请求首个音源进行全链路解析与播放验证",
                    tint = Color(0xFF43A047),
                    enabled = !busy,
                ) { testPipeline() }
            }
        }

        item { SectionCaption("状态") }
        item { SettingsCard { StatusCard(status = status, isBusy = busy) } }
    }
}

/** 导入载荷：JSON 合集按条导入，否则视为单个音源脚本。 */
private fun importPayload(store: LxScriptStore, text: String, fallbackName: String?): List<LxScript> {
    val bundle = runCatchingCancellable { JSONObject(text) }.getOrNull()
    val array = bundle?.optJSONArray("scripts")
    if (array != null) {
        val imported = mutableListOf<LxScript>()
        for (index in 0 until array.length()) {
            val node = array.optJSONObject(index) ?: continue
            val code = node.optString("code")
            if (code.isBlank()) continue
            val name = node.optString("name").ifBlank { "source-$index.js" }
            imported += store.import(name, code)
        }
        return imported
    }
    val name = fallbackName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "imported.js"
    return listOf(store.import(name, text))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceRow(
    script: LxScript,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onInspect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier.weight(1f),
        ) {
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
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = MeloraAppearance.canvas,
            dragHandle = {
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
            },
        ) {
            SystemBarsVisibility()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                // 音源头部卡片信息
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
                                script.version.takeIf { it.isNotBlank() }?.let { if (it.startsWith("v", true)) it else "v$it" },
                                script.description.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { "本地沙箱音源脚本" },
                            fontSize = 12.sp,
                            color = TextSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = DividerSoft, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SourceSheetAction(
                        icon = Icons.Rounded.Build,
                        iconTint = Color(0xFF1E88E5),
                        iconBg = Color(0xFF1E88E5).copy(alpha = 0.10f),
                        title = "检查脚本",
                        subtitle = "测试脚本语法及各平台音源解析可用性",
                        titleColor = TextMain,
                    ) {
                        sheetOpen = false
                        onInspect()
                    }

                    SourceSheetAction(
                        icon = Icons.Rounded.Upload,
                        iconTint = Color(0xFF7C3AED),
                        iconBg = Color(0xFF7C3AED).copy(alpha = 0.10f),
                        title = "导出此源",
                        subtitle = "导出为独立 .js 脚本文件到本地存储",
                        titleColor = TextMain,
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
                    ) {
                        sheetOpen = false
                        onDelete()
                    }
                }
            }
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
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
private fun StatusCard(status: String, isBusy: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isBusy) BrandBlue else Color(0xFF43A047)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = status,
            fontSize = 12.sp,
            color = TextSub,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
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
