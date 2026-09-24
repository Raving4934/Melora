package com.leyu.melora.ui.my

import android.content.ClipboardManager
import android.content.Context
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { keyboard?.hide() }
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
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
        keyboard?.hide()
        error = null
        result = null
        progress = null
        loading = true
        val input = text
        job = scope.launch {
            try {
                result = readPlaylist(context, input) { progress = it }.also { name = it.name }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(200) ?: "读取失败，请检查歌单链接后重试。"
            } finally { loading = false }
        }
    }
    fun save() {
        val ready = result ?: return
        if (loading || saving || name.isBlank()) return
        keyboard?.hide()
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
    MeloraBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = MeloraAppearance.canvas) {
        Column(
            Modifier.fillMaxWidth().testTag("playlist-import-sheet").heightIn(max = 580.dp).verticalScroll(rememberScrollState())
                .imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(MeloraAppearance.tintBlue), Alignment.Center) {
                    Icon(Icons.Outlined.Link, null, tint = BrandBlue, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("导入歌单", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextMain)
                    Text("网易云 / QQ音乐 / 酷我 / 酷狗 / 咪咕", fontSize = 12.sp, color = TextSub)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MeloraAppearance.card, border = MeloraAppearance.cardBorder) {
                BasicTextField(
                    value = text, onValueChange = { edit(it) }, enabled = !busy,
                    textStyle = TextStyle(color = TextMain, fontSize = 14.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(BrandBlue),
                    modifier = Modifier.fillMaxWidth().height(92.dp).padding(14.dp).testTag("playlist-import-link"),
                    decorationBox = { field ->
                        Box {
                            if (text.isBlank()) Text("粘贴歌单分享链接或分享文字", color = MeloraAppearance.textMuted, fontSize = 14.sp, lineHeight = 21.sp)
                            field()
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (pasted.isNullOrBlank()) error = "剪贴板没有可粘贴的文字。" else edit(pasted)
                }, enabled = !busy) { Text("粘贴", color = if (busy) TextSub else BrandBlue) }
            }
            // 为读取前、读取中、预览及错误态预留同一高度；不切换骨架，不顶动底部按钮。
            Column(
                Modifier.fillMaxWidth().height(180.dp).testTag("playlist-import-status")
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val ready = result
                if (ready != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SongArtwork(ready.cover ?: ready.songs.firstOrNull()?.img, seed = ready.name, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("歌单名称", fontSize = 12.sp, color = TextSub)
                            BasicTextField(
                                value = name, onValueChange = { name = it }, singleLine = true, enabled = !busy,
                                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextMain),
                                cursorBrush = SolidColor(BrandBlue),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("playlist-import-name"),
                            )
                            Text("${ready.platform} · ${ready.songs.size} 首${if (ready.duplicates > 0) " · 去重 ${ready.duplicates} 首" else ""}", fontSize = 12.sp, color = TextSub)
                        }
                    }
                    ready.warning?.let { Text(it, color = MeloraAppearance.accent, fontSize = 13.sp) }
                    if (ready.warning == null) Text("将保存为独立自建歌单，不覆盖已有歌单，不跟随原平台同步。", color = TextSub, fontSize = 12.sp)
                } else if (loading) {
                    Text("正在读取歌单…", color = TextMain, fontSize = 15.sp)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BrandBlue)
                    Text(progress?.let { "第 ${it.page} 页 · 已读取 ${it.loaded} 首${it.total?.let { total -> " / 平台标注 $total 首" }.orEmpty()}" } ?: "正在识别分享链接", color = TextSub, fontSize = 13.sp)
                    Text("可取消；确认导入前不会写入歌单。", color = TextSub, fontSize = 12.sp)
                } else if (error == null) {
                    Text("支持可公开访问的个人歌单", color = TextMain, fontSize = 15.sp)
                    Text("不登录平台账号，也不读取私密歌单。导入的是歌曲信息，播放与下载仍需可用音源。", color = TextSub, fontSize = 13.sp)
                }
                error?.let { Text(it, color = MeloraAppearance.accent, fontSize = 13.sp, modifier = Modifier.testTag("playlist-import-error")) }
            }
            if (result != null) {
                TextButton(onClick = { read() }, enabled = !busy, modifier = Modifier.align(Alignment.End).height(40.dp)) {
                    Text("重新读取", color = BrandBlue)
                }
            } else Spacer(Modifier.height(40.dp))
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = dismiss, enabled = !saving, shape = RoundedCornerShape(22.dp), color = MeloraAppearance.softFill,
                    border = MeloraAppearance.chipBorder, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(contentAlignment = Alignment.Center) { Text("取消", color = TextSub, fontSize = 14.sp) }
                }
                val enabled = !busy && if (result == null) text.isNotBlank() else name.isNotBlank()
                Surface(onClick = { if (result == null) read() else save() }, enabled = enabled,
                    shape = RoundedCornerShape(22.dp), color = BrandBlue.copy(alpha = if (enabled) 1f else 0.35f),
                    modifier = Modifier.weight(1.7f).fillMaxHeight().testTag("playlist-import-submit")) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(when {
                            saving -> "保存中…"
                            loading -> "读取中…"
                            result?.warning != null -> "仅导入已获取的 ${result!!.songs.size} 首"
                            result != null -> "导入 ${result!!.songs.size} 首"
                            else -> "读取歌单"
                        }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}
