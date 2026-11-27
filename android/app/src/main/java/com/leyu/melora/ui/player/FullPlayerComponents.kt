package com.leyu.melora.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.leyu.melora.ui.common.BadgePill
import com.leyu.melora.ui.common.QualityBadgeHeight
import com.leyu.melora.ui.theme.MeloraAppearance

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.QualityBadgeMode
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayMode
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.TrackRegistry
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.ui.common.SongArtwork
import java.util.Locale

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
    if (state.resolving) return "播放源：正在解析…"
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

internal fun platformLabel(source: String?, fallback: String = "本地音频"): String = when (source) {
    "kw" -> "酷我音乐"
    "kg" -> "酷狗音乐"
    "tx" -> "QQ 音乐"
    "wy" -> "网易云音乐"
    "mg" -> "咪咕音乐"
    "local" -> "本地音频"
    else -> fallback
}

internal fun AudioSpecification.playerSpecificationLabel(): String = buildList {
    if (bitDepth > 0) add("$bitDepth-bit")
    sampleRateLabel?.let { add(it) }
    codecLabel?.let { add(it) }
    val kbps = displayBitrateKbps
    if (kbps != null) add(String.format(Locale.ROOT, "%,d kbps", kbps))
    else if (bitrate > 0) add("$bitrate bps")
}.joinToString(" · ")

@Composable
internal fun AudioSpecificationLine(specification: AudioSpecification?, modifier: Modifier = Modifier) {
    val label = remember(specification) { specification?.playerSpecificationLabel().orEmpty() }
    val lineHeight = 18.sp
    Text(
        text = label,
        modifier = modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { lineHeight.toDp() }),
        color = FullPlayerTextMuted,
        style = TextStyle(
            fontSize = 12.sp,
            lineHeight = lineHeight,
            letterSpacing = 0.15.sp,
            fontFeatureSettings = "tnum",
            lineBreak = LineBreak.Paragraph,
        ),
    )
}

@Composable
internal fun AudioInfoCard(
    platformName: String,
    audioSpec: AudioSpecification?,
    sourceLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FullPlayerCardSurface,
        border = if (MeloraAppearance.isEInk) MeloraAppearance.cardBorder else null,
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
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = QualityBadgeHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = platformName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = FullPlayerTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                audioSpec?.qualityBadge?.let { badge ->
                    BadgePill(badge, mode = QualityBadgeMode.Split, outlined = true)
                }
            }
            AudioSpecificationLine(audioSpec, Modifier.padding(top = 6.dp))
            Text(
                text = sourceLabel,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = FullPlayerTextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
internal fun AlbumInfoCard(
    track: UiTrack?,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albumName = track?.album?.takeIf { it.isNotBlank() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FullPlayerCardSurface,
        border = if (MeloraAppearance.isEInk) MeloraAppearance.cardBorder else null,
        modifier = modifier.fillMaxWidth(),
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
    modifier: Modifier = Modifier,
) {
    val artistName = track?.artist?.takeIf { it.isNotBlank() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FullPlayerCardSurface,
        border = if (MeloraAppearance.isEInk) MeloraAppearance.cardBorder else null,
        modifier = modifier.fillMaxWidth(),
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

internal val CompactLandscapeTitleSize = 20.sp

@Composable
internal fun CompactLandscapeControls(
    scrollState: ScrollState,
    heading: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    artwork: @Composable () -> Unit,
) {
    Layout(
        content = {
            heading()
            artwork()
            transport()
        },
        modifier = Modifier.widthIn(max = 376.dp).fillMaxSize().verticalScroll(scrollState),
    ) { (titleSlot, coverSlot, transportSlot), constraints ->
        // fillMaxSize 在滚动层外保留视口高度。先测实际控制栏，包含换行的次级按钮和系统字体缩放。
        val width = constraints.maxWidth
        val viewport = constraints.minHeight
        val loose = constraints.copy(minHeight = 0, minWidth = 0)
        val controls = transportSlot.measure(loose)
        val room = viewport - controls.height
        val gap = 8.dp.roundToPx()
        val minimumSide = 48.dp.roundToPx()
        val inlineLimit = compactLandscapeInlineCoverLimit(width.toDp(), CompactLandscapeTitleSize.toDp())
        val inline = inlineLimit != null
        val fraction = if (inline) 0.45f else 0.32f
        val preferredSide = (viewport * fraction).roundToInt().coerceIn(minimumSide, 152.dp.roundToPx())
        val inlineSide = inlineLimit?.let {
            minOf(it.roundToPx(), preferredSide, (room - gap).coerceAtLeast(minimumSide))
        }
        val titleOffset = inlineSide?.let { it + CompactLandscapeHeadingGap.roundToPx() } ?: 0
        val title = titleSlot.measure(loose.copy(maxWidth = width - titleOffset))
        val side = inlineSide ?: minOf(width, preferredSide, (room - title.height - 2 * gap).coerceAtLeast(minimumSide))
        val cover = coverSlot.measure(Constraints.fixed(side, side))

        val groupHeight = if (inline) maxOf(title.height, side) else title.height + gap + side
        val height = maxOf(viewport, groupHeight + gap + controls.height)
        val controlsY = height - controls.height
        val groupY = (controlsY - gap - groupHeight) / 2
        layout(width, height) {
            title.placeRelative(titleOffset, if (inline) groupY + (groupHeight - title.height) / 2 else groupY)
            if (inline) cover.placeRelative(0, groupY + (groupHeight - side) / 2)
            else cover.placeRelative((width - side) / 2, groupY + title.height + gap)
            controls.placeRelative(0, controlsY)
        }
    }
}

@Composable
internal fun FullPlayerPrimaryControls(
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean = false,
) {
    val skipBurstGate = remember { PlayerSkipBurstGate() }
    val skipSize = if (compact) 28.dp else 38.dp
    val toggleButtonSize = if (compact) 52.dp else 68.dp
    val toggleIconSize = if (compact) 40.dp else 54.dp
    val skipTint = FullPlayerTextPrimary.copy(alpha = if (compact) 0.84f else 1f)

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
                tint = skipTint,
                modifier = Modifier.size(skipSize),
            )
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(toggleButtonSize),
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = FullPlayerTextPrimary,
                modifier = Modifier.size(toggleIconSize),
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
                tint = skipTint,
                modifier = Modifier.size(skipSize),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FullPlayerSecondaryControls(
    mode: PlayMode,
    effectActive: Boolean,
    onCycleMode: () -> Unit,
    onShowTimer: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenQueue: () -> Unit,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier,
    queueOpen: Boolean = false,
    compact: Boolean = false,
) {
    val iconSize = if (compact) 19.dp else 22.dp
    val buttonModifier = if (compact) Modifier.size(40.dp) else Modifier
    val mutedTint = FullPlayerTextMuted.copy(alpha = if (compact) 0.72f else 1f)

    FlowRow(
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onCycleMode, modifier = buttonModifier) {
            Icon(
                imageVector = when (mode) {
                    PlayMode.List -> Icons.Rounded.Repeat
                    PlayMode.Single -> Icons.Rounded.RepeatOne
                    PlayMode.Shuffle -> Icons.Rounded.Shuffle
                },
                contentDescription = "循环模式",
                tint = mutedTint,
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = onShowTimer, modifier = buttonModifier) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = "定时与播放设置",
                tint = mutedTint,
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = onOpenEffects, modifier = buttonModifier) {
            Icon(
                imageVector = AudioEffectsIcon,
                contentDescription = if (effectActive) "音效已开启" else "音效设置",
                tint = if (effectActive) FullPlayerTextPrimary else mutedTint,
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = onOpenQueue, modifier = buttonModifier) {
            Icon(
                Icons.AutoMirrored.Rounded.FormatListBulleted,
                contentDescription = if (queueOpen) "收起播放队列" else "播放队列",
                tint = if (queueOpen) FullPlayerTextPrimary else mutedTint,
                modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = onShowMore, modifier = buttonModifier) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = "更多操作",
                tint = mutedTint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
