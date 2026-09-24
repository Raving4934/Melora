package com.leyu.melora.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.leyu.melora.ui.common.BadgePill

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.leyu.melora.ui.common.AudioEffectsIcon
import com.leyu.melora.playback.sdk.CatalogMetadata
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayMode
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.TrackRegistry
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.ui.common.SongArtwork

/** 歌曲状态和歌词流分开发射时，绝不能短暂显示上一首歌词。 */
internal fun playerLyricLines(uid: String?, lyric: PlayerLyric?): List<LyricLine> =
    lyric?.takeIf { it.uid == uid }?.lines.orEmpty()

internal fun fullPlayerLyricsOrFallback(track: UiTrack?, lyrics: List<LyricLine>): List<LyricLine> =
    lyrics.ifEmpty {
        listOf(
            LyricLine(0L, "暂无歌词"),
            LyricLine(2_000L, track?.title ?: "纯净音乐体验"),
            LyricLine(4_000L, track?.artist ?: "静候聆听"),
        )
    }

/** 普通/沉浸 page0 共用来源文案；缓存由真实命中标记判断，不根据资源 ID 猜测。 */
internal fun playbackSourceLabel(state: PlayerUiState, autoSwitch: Boolean): String {
    if (state.fromCompleteCache) return "播放源：缓存"
    val id = state.resolvedBy?.trim().orEmpty()
    val source = when {
        id.isBlank() || id.equals("legacy", true) || id.equals("unverified", true) -> "未知/缓存"
        id == "local" -> "本地下载"
        id == "localmedia" -> "本地媒体"
        !id.startsWith("lx:", ignoreCase = true) -> "未知音源"
        else -> id.substring(3).substringBefore(':').trim().let { scriptId ->
            if (scriptId.isBlank()) "未知音源"
            else LxScriptPool.scriptDisplayName(scriptId) ?: scriptId.removeSuffix(".js")
        }
    }
    val switchedPlatform = state.resolvedPlatform
        ?.takeIf { it in setOf("kw", "kg", "wy", "tx", "mg") && it != state.current?.source }
        ?.let { " · ${platformLabel(it)}" }.orEmpty()
    val switchHint = if (autoSwitch && state.resolvedBy !in TrackRegistry.LOCAL_RESOURCE_IDS) " · 已启用自动换源" else ""
    return "播放源：$source$switchedPlatform$switchHint"
}

internal fun platformLabel(source: String?): String = when (source) {
    "kw" -> "酷我音乐"
    "kg" -> "酷狗音乐"
    "tx" -> "QQ 音乐"
    "wy" -> "网易云音乐"
    "mg" -> "咪咕音乐"
    else -> "本地音频"
}

@Composable
internal fun AudioInfoCard(
    platformName: String,
    qualityBadge: String?,
    sourceLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FullPlayerCardSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = FullPlayerTextPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "音频信息",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = FullPlayerTextPrimary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = platformName,
                    fontSize = 13.sp,
                    color = FullPlayerTextPrimary,
                )
                if (qualityBadge != null) {
                    Spacer(Modifier.width(8.dp))
                    val badgeTint = if (qualityBadge == "128K") FullPlayerTextPrimary.copy(alpha = 0.72f) else null
                    BadgePill(qualityBadge, tint = badgeTint)
                }
            }
            Text(
                text = sourceLabel,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = FullPlayerTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun AlbumInfoCard(
    track: UiTrack?,
    onOpenAlbum: (String) -> Unit,
) {
    val albumName = track?.album?.takeIf { it.isNotBlank() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FullPlayerCardSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Album,
                    contentDescription = null,
                    tint = FullPlayerTextPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "出自专辑",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = FullPlayerTextPrimary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = albumName != null) { albumName?.let(onOpenAlbum) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(
                    url = track?.artwork,
                    seed = track?.uid ?: "album",
                    modifier = Modifier.size(46.dp),
                    cornerRadius = 8,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = albumName ?: "未知专辑",
                        fontSize = 14.sp,
                        color = FullPlayerTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track?.artist ?: "未知艺术家",
                        fontSize = 12.sp,
                        color = FullPlayerTextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (albumName != null) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "查看专辑歌曲",
                        tint = FullPlayerTextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ArtistInfoCard(
    track: UiTrack?,
    onOpenArtist: (String) -> Unit,
) {
    val artistName = track?.artist?.takeIf { it.isNotBlank() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FullPlayerCardSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    tint = FullPlayerTextPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "参与创作的艺术家",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = FullPlayerTextPrimary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = artistName != null) { artistName?.let(onOpenArtist) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 仅在音频信息页被组合（即进入全屏播放页 page0）时才查询头像，且按名字做进程内缓存
                var avatarUrl by remember(artistName) { mutableStateOf<String?>(null) }
                LaunchedEffect(artistName) {
                    avatarUrl = artistName?.let { CatalogMetadata.artist(it)?.image }
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(LocalPlayerColors.current.cardSelected),
                    contentAlignment = Alignment.Center,
                ) {
                    val url = avatarUrl
                    if (!url.isNullOrBlank()) {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = FullPlayerTextMuted,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artistName ?: "未知艺术家",
                        fontSize = 14.sp,
                        color = FullPlayerTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "主创艺术家",
                        fontSize = 12.sp,
                        color = FullPlayerTextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (artistName != null) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "查看歌手歌曲",
                        tint = FullPlayerTextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun FullPlayerPrimaryControls(
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val skipBurstGate = remember { PlayerSkipBurstGate() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = {
                if (skipBurstGate.tryAcquire(SystemClock.uptimeMillis())) {
                    onPrevious()
                }
            },
        ) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "上一首",
                tint = FullPlayerTextPrimary,
                modifier = Modifier.size(38.dp),
            )
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(68.dp),
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = FullPlayerTextPrimary,
                modifier = Modifier.size(54.dp),
            )
        }
        IconButton(
            onClick = {
                if (skipBurstGate.tryAcquire(SystemClock.uptimeMillis())) {
                    onNext()
                }
            },
        ) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "下一首",
                tint = FullPlayerTextPrimary,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

@Composable
internal fun FullPlayerSecondaryControls(
    mode: PlayMode,
    effectActive: Boolean,
    onCycleMode: () -> Unit,
    onShowTimer: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenQueue: () -> Unit,
    onShowMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCycleMode) {
            Icon(
                imageVector = when (mode) {
                    PlayMode.List -> Icons.Rounded.Repeat
                    PlayMode.Single -> Icons.Rounded.RepeatOne
                    PlayMode.Shuffle -> Icons.Rounded.Shuffle
                },
                contentDescription = "循环模式",
                tint = FullPlayerTextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onShowTimer) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = "定时与播放设置",
                tint = FullPlayerTextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onOpenEffects) {
            Icon(
                imageVector = AudioEffectsIcon,
                contentDescription = if (effectActive) "音效已开启" else "音效设置",
                tint = FullPlayerTextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onOpenQueue) {
            Icon(
                Icons.AutoMirrored.Rounded.FormatListBulleted,
                contentDescription = "播放队列",
                tint = FullPlayerTextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onShowMore) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = "更多操作",
                tint = FullPlayerTextMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
