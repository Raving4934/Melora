package com.leyu.melora.ui.my

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.PlaylistSyncPreview
import com.leyu.melora.playback.sdk.identity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.leyu.melora.playback.sdk.PlaylistImportProgress
import com.leyu.melora.playback.sdk.PlaylistImportResult
import com.leyu.melora.playback.sdk.PlaylistImporter
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.rememberSheetDismiss
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val IMPORT_FEEDBACK_DELAY_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistImportSheet(
    onDismiss: () -> Unit,
    onImported: (UserLibrary.UserPlaylist) -> Unit,
    onUpdated: (UserLibrary.UserPlaylist) -> Unit = onImported,
    library: StateFlow<List<UserLibrary.UserPlaylist>> = UserLibrary.playlists,
    readPlaylist: suspend (Context, String, (PlaylistImportProgress) -> Unit) -> PlaylistImportResult = PlaylistImporter::load,
    savePlaylist: suspend (String, PlaylistImportResult, Boolean) -> UserLibrary.UserPlaylist = UserLibrary::saveImportedPlaylist,
    commitUpdate: suspend (PlaylistSyncPreview) -> UserLibrary.UserPlaylist = UserLibrary::commitPlaylistSync,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var sourceExpanded by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PlaylistImportResult?>(null) }
    var progress by remember { mutableStateOf<PlaylistImportProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var duplicateSeen by remember { mutableStateOf(false) }
    var updateTarget by remember { mutableStateOf<UserLibrary.UserPlaylist?>(null) }
    val available by library.collectAsStateWithLifecycle()
    val coordinator = remember(library, savePlaylist) { PlaylistImportCoordinator({ library.value }, savePlaylist) }
    val choices = remember(available, result, selectedId, coordinator) {
        result?.let { coordinator.choices(it, selectedId) } ?: PlaylistImportChoices(emptyList(), null)
    }
    val duplicate = duplicateSeen || choices.matches.isNotEmpty()
    LaunchedEffect(choices.matches) { if (choices.matches.isNotEmpty()) duplicateSeen = true }
    val reader by rememberUpdatedState(readPlaylist)
    val committer by rememberUpdatedState(commitUpdate)
    val updateController = remember(updateTarget?.id) {
        updateTarget?.let { target ->
            PlaylistUpdateController(
                { result?.let { coordinator.updateTarget(it, target.id) } },
                { input, progress -> reader(context, input, progress) },
                { committer(it) },
            )
        }
    }
    val busy = loading || saving
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !saving && updateController?.state?.value?.saving != true })
    val closeSheet = rememberSheetDismiss(sheet)
    val dismiss = { if (!saving) { job?.cancel(); closeSheet(onDismiss) } }
    fun edit(value: String) {
        if (loading || saving) return
        text = value
        result = null
        selectedId = null
        duplicateSeen = false
        error = null
        progress = null
    }
    fun read() {
        if (loading || saving || text.isBlank()) return
        // 重试不移除旧错误或预览；busy立即生效，只有持续读取才显示进度。
        progress = null
        showProgress = false
        loading = true
        val input = text
        job = scope.launch {
            var visibleAt = 0L
            val feedback = launch {
                delay(IMPORT_FEEDBACK_DELAY_MS)
                visibleAt = SystemClock.elapsedRealtime()
                showProgress = true
            }
            try {
                val outcome = try {
                    Result.success(readPlaylist(context, input) { progress = it })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
                // 已显示的进度至少停留300ms；瞬时失败/缓存命中不人为延迟。
                if (showProgress) {
                    delay((IMPORT_FEEDBACK_DELAY_MS - (SystemClock.elapsedRealtime() - visibleAt)).coerceAtLeast(0))
                }
                outcome.fold(onSuccess = { loaded ->
                    name = loaded.name
                    result = loaded
                    selectedId = null
                    duplicateSeen = coordinator.choices(loaded, null).matches.isNotEmpty()
                    error = null
                    sourceExpanded = false
                }, onFailure = { failure ->
                    error = (if (result != null) "读取失败，保留上次预览。" else "") +
                        (failure.message?.takeIf(String::isNotBlank)?.take(200) ?: "读取失败，请检查歌单链接后重试。")
                })
            } finally {
                feedback.cancel()
                loading = false
                showProgress = false
            }
        }
    }
    fun save(allowCopy: Boolean = false) {
        val ready = result ?: return
        if (loading || saving || sheet.targetValue == SheetValue.Hidden || name.isBlank()) return
        saving = true
        error = null
        job = scope.launch {
            try {
                when (val outcome = coordinator.save(name.trim(), ready, allowCopy)) {
                    is PlaylistImportSave.Created -> {
                        saving = false
                        closeSheet { onImported(outcome.playlist) }
                    }
                    is PlaylistImportSave.Duplicate -> {
                        duplicateSeen = true
                        error = "此来源已有 ${outcome.matches.size} 个歌单，请选择更新，或另存为副本。"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "保存失败，原有歌单未覆盖。${failure.message.orEmpty().take(100)}"
            } finally { saving = false }
        }
    }
    fun updateExisting() {
        if (busy) return
        val ready = result ?: return
        val id = choices.target?.id ?: return
        try {
            updateTarget = coordinator.updateTarget(ready, id)
        } catch (failure: IllegalStateException) {
            error = failure.message
        }
    }
    MeloraBottomSheet(
        onDismissRequest = { job?.cancel(); onDismiss() }, sheetState = sheet, containerColor = MeloraAppearance.canvas,
        dragHandle = { PlaylistSheetHandle() },
    ) {
        AnimatedContent(
            targetState = updateTarget,
            contentKey = { it?.id },
            transitionSpec = {
                (fadeIn(tween(160)) togetherWith fadeOut(tween(100)))
                    .using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(220) }))
            },
            label = "playlistImportStep",
        ) { target ->
            if (target != null) {
                // 同一窗体内进入完整的读取/差异/确认流程，不直接提交导入预览。
                PlaylistUpdateContent(target, checkNotNull(updateController),
                    onDismiss = { closeSheet(onDismiss) },
                    onUpdated = { updated -> closeSheet { onUpdated(updated) } })
            } else {
                // 输入框属于抽屉窗口，键盘控制器也从这个窗口取，不能使用背后页面的控制器。
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) { keyboard?.hide() }
                val duplicateNameCounts = choices.matches.groupingBy { it.name }.eachCount()
                Column(
                    Modifier.fillMaxWidth().imePadding().heightIn(max = 440.dp).fillMaxHeight()
                        .testTag("playlist-import-sheet").padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("playlist-import-header"),
                        verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) {
                            PlaylistSheetHeader(Icons.Outlined.Link, "导入歌单", "粘贴分享链接，收藏整张歌单")
                        }
                        IconButton(onClick = dismiss, enabled = !saving) {
                            Icon(Icons.Outlined.Close, "关闭导入歌单", tint = TextSub, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).testTag("playlist-import-body"),
                    ) {
                        val expanded = result == null || sourceExpanded
                        Surface(shape = RoundedCornerShape(16.dp), color = MeloraAppearance.card,
                            border = MeloraAppearance.cardBorder,
                            modifier = Modifier.fillMaxWidth().then(if (!expanded) Modifier.testTag("playlist-import-link-preview") else Modifier)) {
                            Column {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp)
                                    .testTag("playlist-import-progress-slot"), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        if (showProgress) {
                                            CircularProgressIndicator(Modifier.size(16.dp).testTag("playlist-import-progress"), color = BrandBlue, strokeWidth = 1.5.dp)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(if (showProgress) progress?.let {
                                            "已读取 ${it.loaded}${it.total?.let { total -> " / $total" }.orEmpty()} 首"
                                        } ?: "正在读取歌单…" else if (expanded) "分享链接" else text,
                                            color = TextSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (expanded) {
                                        TextButton(onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                            if (pasted.isNullOrBlank()) error = "剪贴板没有可粘贴的文字。" else edit(pasted)
                                        }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                            Text("粘贴", color = if (busy) TextSub else BrandBlue, fontSize = 13.sp)
                                        }
                                    }
                                    if (result != null) {
                                        IconButton(onClick = { keyboard?.hide(); sourceExpanded = !sourceExpanded }, enabled = !busy,
                                            modifier = Modifier.testTag(if (expanded) "playlist-import-collapse-link" else "playlist-import-edit-link")) {
                                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.Edit,
                                                if (expanded) "收起链接" else "编辑链接", tint = TextSub, modifier = Modifier.size(19.dp))
                                        }
                                        IconButton(onClick = { keyboard?.hide(); read() }, enabled = !busy) {
                                            Icon(Icons.Outlined.Refresh, "重新读取", tint = if (busy) TextSub else BrandBlue, modifier = Modifier.size(21.dp))
                                        }
                                    }
                                }
                                if (expanded) BasicTextField(
                                    value = text, onValueChange = { edit(it) }, enabled = !busy, maxLines = 3,
                                    textStyle = TextStyle(color = TextMain, fontSize = 14.sp, lineHeight = 21.sp),
                                    cursorBrush = SolidColor(BrandBlue),
                                    modifier = Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("playlist-import-link"),
                                    decorationBox = { field ->
                                        Box {
                                            if (text.isBlank()) Text("粘贴链接或整段分享文字", color = MeloraAppearance.textMuted, fontSize = 14.sp, lineHeight = 21.sp)
                                            field()
                                        }
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().testTag("playlist-import-status"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val ready = result
                            if (ready != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SongArtwork(ready.cover ?: ready.songs.firstOrNull()?.img, seed = ready.name,
                                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if (duplicate) "副本名称 · 可修改" else "歌单名称 · 可修改", fontSize = 11.sp, color = TextSub)
                                        BasicTextField(
                                            value = name, onValueChange = { name = it }, singleLine = true, enabled = !busy,
                                            textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextMain),
                                            cursorBrush = SolidColor(BrandBlue),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("playlist-import-name"),
                                        )
                                        Text("${ready.platform} · ${ready.songs.size} 首${if (ready.duplicates > 0) " · 去重 ${ready.duplicates} 首" else ""}",
                                            fontSize = 12.sp, color = TextSub)
                                    }
                                }
                                if (duplicate) {
                                    Text("已导入此来源", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    val targetMessage = when {
                                        choices.matches.isEmpty() -> "已有歌单已不可用，请重新读取后再操作。"
                                        choices.matches.size == 1 -> "更新前会再次读取并展示差异，不会直接覆盖。"
                                        else -> "选择一个已有歌单，读取差异后再确认更新。"
                                    }
                                    Text(targetMessage, color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                                    choices.matches.forEachIndexed { index, candidate ->
                                        val candidateTitle = if ((duplicateNameCounts[candidate.name] ?: 0) > 1) {
                                            "${candidate.name} · 副本${index + 1}"
                                        } else candidate.name
                                        val selected = choices.target?.id == candidate.id
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                                                .background(if (selected) MeloraAppearance.tintBlue else Color.Transparent)
                                                .testTag("playlist-import-target-${candidate.id}")
                                                .selectable(selected = selected, enabled = !busy, role = Role.RadioButton) { selectedId = candidate.id }
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = selected, onClick = null, enabled = !busy,
                                                colors = RadioButtonDefaults.colors(selectedColor = BrandBlue, unselectedColor = TextSub))
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(candidateTitle, color = TextMain, fontSize = 13.sp,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text("${candidate.songs.size} 首", color = TextSub, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    ready.warning?.let { Text(it, color = MeloraAppearance.accent, fontSize = 12.sp, lineHeight = 18.sp) }
                                } else if (error == null) {
                                    Text(ready.warning ?: "保存为独立歌单并记录来源，可从歌单菜单手动更新；不会自动同步。",
                                        color = if (ready.warning == null) TextSub else MeloraAppearance.accent, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                                if (ready.importSource?.identity()?.isCanonical == false) {
                                    Text("此链接暂仅按原链接精确防重，不会跨短链或别名合并。", color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            } else if (error == null) {
                                Text("支持五大平台的公开歌单", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("网易云、QQ音乐、酷我、酷狗、咪咕。无需登录，仅导入歌曲信息；播放与下载仍需可用音源。",
                                    color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            error?.let {
                                if (ready == null) Text("暂未读取到歌单", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(it, color = MeloraAppearance.accent, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.testTag("playlist-import-error"))
                                if (ready == null) Text("请检查分享链接是否完整，并确认歌单可公开访问。",
                                    color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val copyAvailable = duplicate && result != null
                    PlaylistSheetActions(
                        onSecondary = { keyboard?.hide(); if (copyAvailable) save(allowCopy = true) else dismiss() }, onConfirm = {
                            keyboard?.hide()
                            if (result == null) read() else if (duplicate) updateExisting() else save()
                        },
                        confirmText = when {
                            saving -> "保存中…"
                            showProgress -> "读取中…"
                            duplicate -> if (choices.target == null) "选择要更新的歌单" else "更新已有歌单"
                            result?.warning != null -> "仅导入已获取的 ${result!!.songs.size} 首"
                            result != null -> "导入 ${result!!.songs.size} 首"
                            error != null -> "重试读取"
                            else -> "读取歌单"
                        },
                        confirmEnabled = if (result == null) text.isNotBlank() else if (duplicate) choices.target != null else name.isNotBlank(),
                        busy = busy, secondaryEnabled = if (copyAvailable) !busy && name.isNotBlank() else !saving,
                        secondaryText = if (copyAvailable) {
                            if (result!!.warning == null) "另存为副本" else "另存已获取的 ${result!!.songs.size} 首"
                        } else "取消",
                        secondaryTag = if (copyAvailable) "playlist-import-copy" else "playlist-import-cancel",
                        modifier = Modifier.testTag("playlist-import-actions"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistSheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MeloraAppearance.divider))
    }
}

@Composable
internal fun PlaylistSheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onImportClick: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MeloraAppearance.tintBlue), Alignment.Center) {
            Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextMain,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onImportClick != null) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.heightIn(min = 32.dp)
                            .clickable(role = Role.Button, onClick = onImportClick),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Link, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("从链接导入", fontSize = 13.sp, lineHeight = 18.sp, color = BrandBlue, maxLines = 1)
                    }
                }
            }
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = TextSub, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun PlaylistSheetActions(
    onSecondary: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    confirmEnabled: Boolean,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    secondaryEnabled: Boolean = true,
    secondaryText: String = "取消",
    submitTag: String = "playlist-import-submit",
    secondaryTag: String = "playlist-import-cancel",
) {
    val submitEnabled = confirmEnabled && !busy
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Max).heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(onClick = onSecondary, enabled = secondaryEnabled, shape = RoundedCornerShape(24.dp),
            color = MeloraAppearance.card, border = MeloraAppearance.cardBorder,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag(secondaryTag)) {
            Box(contentAlignment = Alignment.Center) {
                Text(secondaryText, color = if (secondaryEnabled) TextMain else MeloraAppearance.textMuted, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Surface(onClick = onConfirm, enabled = submitEnabled, shape = RoundedCornerShape(24.dp),
            color = if (submitEnabled) BrandBlue else MeloraAppearance.softFill,
            modifier = Modifier.weight(1.6f).fillMaxHeight().testTag(submitTag)) {
            Box(contentAlignment = Alignment.Center) {
                Text(confirmText, color = if (submitEnabled) Color.White else TextSub, fontSize = 14.sp,
                    lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}
