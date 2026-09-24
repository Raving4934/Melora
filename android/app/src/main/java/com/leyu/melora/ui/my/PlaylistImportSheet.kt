package com.leyu.melora.ui.my

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportProgress
import com.leyu.melora.playback.sdk.PlaylistImportResult
import com.leyu.melora.playback.sdk.PlaylistImporter
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val IMPORT_FEEDBACK_DELAY_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistImportSheet(
    onDismiss: () -> Unit,
    onImported: (UserLibrary.UserPlaylist) -> Unit,
    readPlaylist: suspend (Context, String, (PlaylistImportProgress) -> Unit) -> PlaylistImportResult = PlaylistImporter::load,
    savePlaylist: suspend (String, List<OnlineSong>) -> UserLibrary.UserPlaylist = { name, songs ->
        withContext(Dispatchers.IO) { UserLibrary.createPlaylist(name, songs) }
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PlaylistImportResult?>(null) }
    var progress by remember { mutableStateOf<PlaylistImportProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = loading || saving
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !saving })
    val dismiss = { if (!saving) { job?.cancel(); onDismiss() } }
    fun edit(value: String) {
        if (loading || saving) return
        text = value
        result = null
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
                    error = null
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
    fun save() {
        val ready = result ?: return
        if (loading || saving || name.isBlank()) return
        saving = true
        error = null
        job = scope.launch {
            try {
                val playlist = savePlaylist(name.trim(), ready.songs)
                onImported(playlist)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "保存失败，原有歌单未覆盖。${failure.message.orEmpty().take(100)}"
            } finally { saving = false }
        }
    }
    MeloraBottomSheet(
        onDismissRequest = dismiss, sheetState = sheet, containerColor = MeloraAppearance.canvas,
        dragHandle = { PlaylistSheetHandle() },
    ) {
        // 输入框属于抽屉窗口，键盘控制器也从这个窗口取，不能使用背后页面的控制器。
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) { keyboard?.hide() }
        Column(
            Modifier.fillMaxWidth().testTag("playlist-import-sheet").heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            PlaylistSheetHeader(Icons.Outlined.Link, "导入歌单", "网易云 / QQ音乐 / 酷我 / 酷狗 / 咪咕")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("歌单分享链接", color = TextSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (pasted.isNullOrBlank()) error = "剪贴板没有可粘贴的文字。" else edit(pasted)
                }, enabled = !busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                    Text("粘贴", color = if (busy) TextSub else BrandBlue, fontSize = 13.sp)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MeloraAppearance.card, border = MeloraAppearance.cardBorder) {
                BasicTextField(
                    value = text, onValueChange = { edit(it) }, enabled = !busy,
                    textStyle = TextStyle(color = TextMain, fontSize = 14.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(BrandBlue),
                    modifier = Modifier.fillMaxWidth().height(84.dp).padding(12.dp).testTag("playlist-import-link"),
                    decorationBox = { field ->
                        Box {
                            if (text.isBlank()) Text("粘贴链接或整段分享文字", color = MeloraAppearance.textMuted, fontSize = 14.sp, lineHeight = 21.sp)
                            field()
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            // 紧凑固定状态区：保留旧内容，长错误可滚动；状态切换不顶动操作栏。
            Column(Modifier.fillMaxWidth().height(136.dp).testTag("playlist-import-status")) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val ready = result
                    if (ready != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongArtwork(ready.cover ?: ready.songs.firstOrNull()?.img, seed = ready.name,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("歌单名称 · 可修改", fontSize = 11.sp, color = TextSub)
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
                        if (error == null) {
                            Text(ready.warning ?: "保存为独立歌单，不覆盖已有歌单，也不随原平台同步。",
                                color = if (ready.warning == null) TextSub else MeloraAppearance.accent, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    } else if (error == null) {
                        Text("支持公开的个人歌单", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("不登录平台，不读取私密歌单。仅导入歌曲信息，播放与下载仍需可用音源。",
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
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (showProgress) {
                            CircularProgressIndicator(Modifier.size(14.dp).testTag("playlist-import-progress"), color = BrandBlue, strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(progress?.let { "已读取 ${it.loaded}${it.total?.let { total -> " / $total" }.orEmpty()} 首" } ?: "正在读取歌单…",
                                color = TextSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (result != null) {
                        TextButton(onClick = { keyboard?.hide(); read() }, enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("重新读取", color = if (busy) TextSub else BrandBlue, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PlaylistSheetActions(
                onDismiss = dismiss, onConfirm = { keyboard?.hide(); if (result == null) read() else save() },
                confirmText = when {
                    saving -> "保存中…"
                    showProgress -> "读取中…"
                    result?.warning != null -> "仅导入已获取的 ${result!!.songs.size} 首"
                    result != null -> "导入 ${result!!.songs.size} 首"
                    error != null -> "重试读取"
                    else -> "读取歌单"
                },
                confirmEnabled = if (result == null) text.isNotBlank() else name.isNotBlank(),
                busy = busy, cancelEnabled = !saving,
            )
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
internal fun PlaylistSheetHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MeloraAppearance.tintBlue), Alignment.Center) {
            Icon(icon, null, tint = BrandBlue, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextMain)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = TextSub, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun PlaylistSheetActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String,
    confirmEnabled: Boolean,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    cancelEnabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(onClick = onDismiss, enabled = cancelEnabled, shape = RoundedCornerShape(22.dp), color = MeloraAppearance.softFill,
            border = MeloraAppearance.chipBorder, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(contentAlignment = Alignment.Center) { Text("取消", color = TextSub, fontSize = 14.sp) }
        }
        Surface(onClick = onConfirm, enabled = confirmEnabled && !busy, shape = RoundedCornerShape(22.dp),
            color = BrandBlue.copy(alpha = if (confirmEnabled) 1f else 0.35f),
            modifier = Modifier.weight(1.6f).fillMaxHeight().testTag("playlist-import-submit")) {
            Box(contentAlignment = Alignment.Center) {
                Text(confirmText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}
