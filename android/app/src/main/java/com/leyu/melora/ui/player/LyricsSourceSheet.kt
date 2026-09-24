package com.leyu.melora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.leyu.melora.ui.common.sourceAliasDisplay
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
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(colors.textSecondary.copy(alpha = 0.35f), RoundedCornerShape(2.dp)))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)
            .testTag("lyrics-source-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("歌词来源", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = !loading && !choosing,
                    onClick = { scope.launch { findCandidate(true); PlaybackController.refreshLyrics(track.uid) } },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.textPrimary, disabledContentColor = colors.textSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.width(100.dp).heightIn(min = 48.dp).testTag("lyrics-source-search")) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新查找", fontSize = 13.sp)
                }
            }
            LyricsCandidateSummary(track, candidate, loading)
            if (local != null) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.cardSurface)
                    .padding(4.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    LyricSourceMode.entries.forEach { option ->
                        val enabled = preferenceReady && !choosing && when (option) {
                            LyricSourceMode.Auto -> true
                            LyricSourceMode.Embedded -> embedded != null
                            LyricSourceMode.Matched -> !loading && candidate?.lines?.any { it.words.isNotEmpty() } == true
                        }
                        val isSelected = preferenceReady && mode == option && (option != LyricSourceMode.Matched ||
                            (candidate != null && candidate?.lines == current?.lines && candidate?.source == current?.source))
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) colors.textPrimary else Color.Transparent)
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
                            }.testTag("lyrics-source-${option.storageValue}").padding(horizontal = 4.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text(when (option) {
                                LyricSourceMode.Auto -> "自动"
                                LyricSourceMode.Embedded -> "内嵌"
                                LyricSourceMode.Matched -> "匹配逐字"
                            }, color = if (isSelected) colors.sheetBackground else colors.textPrimary.copy(alpha = if (enabled) 1f else 0.4f),
                                fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
                Text(when {
                    tagExtension(local.mimeType, local.uri) == null -> "此格式暂不支持写入，仅支持 MP3 / FLAC。"
                    !canWrite -> "当前歌曲有可用歌词后，即可写入文件。"
                    mode == LyricSourceMode.Auto -> "优先内嵌歌词，仅为同句同时间补充逐字信息。"
                    mode == LyricSourceMode.Embedded -> "只使用歌曲文件里的歌词，不使用匹配结果。"
                    else -> "重查不会替换已固定的歌词，点选「匹配逐字」可更新。"
                }, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp, minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                FilledTonalButton(enabled = canWrite && !writing, onClick = { confirmWrite = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lyrics-source-write"),
                    shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.cardSurface, contentColor = colors.textPrimary,
                        disabledContainerColor = colors.cardSurface, disabledContentColor = colors.textPrimary.copy(alpha = 0.4f))) {
                    Text("写入当前歌词", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
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

/** 固定两行歌曲信息和一行状态，不展示歌词首行，加载完成也不推挤下方控件。 */
@Composable
internal fun LyricsCandidateSummary(track: UiTrack, candidate: PlayerLyric?, loading: Boolean) {
    val fixedLineStyle = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false))
    val titleStyle = fixedLineStyle.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    // 使用相同排版器测量两行，不能把合计 sp 当高度（大字号是非线性缩放）。
    val titleHeight = with(LocalDensity.current) { rememberTextMeasurer().measure("国Ag\n国Ag", titleStyle).size.height.toDp() }
    Column(Modifier.fillMaxWidth().testTag("lyrics-source-candidate"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(listOf(candidate?.title ?: track.title, candidate?.artist ?: track.artist).filter(String::isNotBlank).joinToString(" · "),
            style = titleStyle, color = LocalPlayerColors.current.textPrimary, modifier = Modifier.height(titleHeight),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(when {
            loading -> "正在查找匹配歌词…"
            candidate == null -> "暂未找到匹配，保留原有歌词"
            else -> "${sourceAliasDisplay(candidate.source, platformLabel(candidate.source))} · ${if (candidate.lines.any { it.words.isNotEmpty() }) "逐字歌词" else "普通歌词"}"
        }, style = fixedLineStyle.copy(color = LocalPlayerColors.current.textSecondary, fontSize = 12.sp, lineHeight = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
