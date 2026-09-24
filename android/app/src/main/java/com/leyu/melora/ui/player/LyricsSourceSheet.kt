package com.leyu.melora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.*
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalTagFiller
import com.leyu.melora.playback.local.tagExtension
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.theme.SystemBarsVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 来源选择不写音频；物理写入只有独立确认按钮才能发起。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSourceSheet(track: UiTrack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalPlayerColors.current
    val scope = rememberCoroutineScope()
    val local = LocalMediaStore.matchTrack(track)
    val current by PlaybackController.lyric.collectAsStateWithLifecycle()
    var mode by remember(track.uid) { mutableStateOf(LyricSourceMode.Auto) }
    var embedded by remember(track.uid) { mutableStateOf<PlayerLyric?>(null) }
    var candidate by remember(track.uid) { mutableStateOf<PlayerLyric?>(null) }
    var loading by remember(track.uid) { mutableStateOf(true) }
    var preferenceReady by remember(track.uid) { mutableStateOf(false) }
    var confirmWrite by remember(track.uid) { mutableStateOf(false) }
    var choosing by remember(track.uid) { mutableStateOf(false) }
    var writing by remember(track.uid) { mutableStateOf(false) }
    val findCandidate: suspend (Boolean) -> Unit = { force ->
        loading = true
        try {
            candidate = recoverableOrNull { LyricRepository.candidate(context, track, force = force) }
        } finally { loading = false }
    }
    LaunchedEffect(track.uid) {
        mode = withContext(Dispatchers.IO) { LyricRepository.sourceMode(context, track.uid) }
        preferenceReady = true
        embedded = LyricRepository.embedded(context, track)
        findCandidate(false)
    }
    val canWrite = local != null && tagExtension(local.mimeType, local.uri) != null &&
        current?.let { it.uid == track.uid && it.lines.isNotEmpty() } == true
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.sheetBackground,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag("lyrics-source-sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("歌词来源", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(track.title, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (local != null) Column(Modifier.selectableGroup()) {
                LyricSourceMode.entries.forEach { option ->
                    val enabled = preferenceReady && !choosing && when (option) {
                        LyricSourceMode.Auto -> true
                        LyricSourceMode.Embedded -> embedded != null
                        LyricSourceMode.Matched -> !loading && candidate?.lines?.any { it.words.isNotEmpty() } == true
                    }
                    val isSelected = preferenceReady && mode == option && (option != LyricSourceMode.Matched ||
                        (candidate != null && candidate?.lines == current?.lines && candidate?.source == current?.source))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 54.dp)
                            .background(if (isSelected) colors.cardSelected else colors.cardSurface, RoundedCornerShape(12.dp))
                            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
                                val selected = candidate
                                choosing = true
                                scope.launch {
                                    try {
                                        val saved = recoverableOrNull {
                                            LyricRepository.chooseSource(context, track.uid, option, selected)
                                            true
                                        } == true
                                        if (saved) {
                                            mode = option
                                            PlaybackController.refreshLyrics(track.uid)
                                        } else PlaybackController.postMessage(context, "无法保存歌词选择，请重试")
                                    } finally { choosing = false }
                                }
                            }.testTag("lyrics-source-${option.storageValue}").padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(when (option) {
                            LyricSourceMode.Auto -> "自动 · 优先可靠逐字"
                            LyricSourceMode.Embedded -> "使用内嵌歌词"
                            LyricSourceMode.Matched -> "使用这份匹配逐字歌词"
                        }, color = colors.textPrimary.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Box(Modifier.size(20.dp)) {
                            if (isSelected) Icon(Icons.Rounded.Check, null, tint = colors.textPrimary)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // 固定候选区域，加载、空结果与成功不会推挤下面的操作按钮。
            Column(Modifier.fillMaxWidth().height(28.dp + with(LocalDensity.current) { 106.sp.toDp() }), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (loading) "正在匹配…" else candidate?.let { "${it.title} · ${it.artist}" } ?: "暂未找到匹配歌词",
                    color = colors.textPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(candidate?.let { "来源 ${it.source} · ${if (it.lines.any { line -> line.words.isNotEmpty() }) "包含逐字时间轴" else "仅普通歌词"}" }
                    ?: "原有歌词会继续显示，不会因匹配失败被清空", color = colors.textSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(candidate?.lines?.firstOrNull { it.text.isNotBlank() }?.text.orEmpty(), color = colors.textSecondary,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("请核对录音版本；重新查找仅更新候选，手选后固定使用这份歌词。", color = colors.textSecondary,
                    fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !loading && !choosing, onClick = { scope.launch { findCandidate(true); PlaybackController.refreshLyrics(track.uid) } },
                    modifier = Modifier.testTag("lyrics-source-search")) {
                    Text("重新查找", color = colors.textPrimary.copy(alpha = if (loading) 0.4f else 1f))
                }
                TextButton(enabled = canWrite && !writing, onClick = { confirmWrite = true },
                    modifier = Modifier.testTag("lyrics-source-write")) {
                    Text("将当前歌词写入文件", color = colors.textPrimary.copy(alpha = if (canWrite && !writing) 1f else 0.4f))
                }
            }
            if (!canWrite) Text("写入仅支持本地 MP3 / FLAC，且需要当前曲目的有效歌词。",
                color = colors.textSecondary, fontSize = 11.sp)
        }
    }
    if (confirmWrite) AlertDialog(
        onDismissRequest = { confirmWrite = false }, containerColor = colors.sheetBackground,
        title = { SystemBarsVisibility(); Text("写入歌词？", color = colors.textPrimary) },
        text = { Text("只更新歌词，保留其它标签。普通歌词作为兼容底稿，同时保存完整逐字数据（若有）。\n\n为避免影响播放，当前文件会在切歌或清空播放队列后写入，系统可能再请求授权。",
            color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = {
                confirmWrite = false
                val lyric = current?.takeIf { it.uid == track.uid } ?: return@TextButton
                scope.launch {
                    writing = true
                    try {
                        val message = recoverableOrNull {
                            withContext(Dispatchers.IO) { LocalTagFiller.requestLyricWrite(context, track, lyric) }
                        } ?: "无法排队写入歌词，请重试"
                        PlaybackController.postMessage(context, message)
                    } finally { writing = false }
                }
            }, modifier = Modifier.testTag("lyrics-source-confirm-write")) { Text("确认写入", color = colors.textPrimary) }
        },
        dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("取消", color = colors.textSecondary) } },
    )
}
