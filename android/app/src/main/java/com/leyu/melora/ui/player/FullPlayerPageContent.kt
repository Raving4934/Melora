package com.leyu.melora.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.TrackRegistry

import org.json.JSONObject
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.ExperimentalHazeApi
import androidx.compose.ui.draw.alpha
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.AlbumProfile
import com.leyu.melora.playback.sdk.ArtistProfile
import com.leyu.melora.playback.sdk.CatalogMetadata
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.common.ChromeFloatingBar
import com.leyu.melora.ui.common.shouldPinPlaylistControls
import com.leyu.melora.ui.common.PlaylistControlsHeight
import com.leyu.melora.ui.common.SongBatchActionsBar
import com.leyu.melora.ui.common.SongSelectionTopBar
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.common.CollectionActionsRow
import com.leyu.melora.ui.common.CollectionInfoHeader
import com.leyu.melora.ui.common.albumHeaderCopy
import com.leyu.melora.ui.common.artistHeaderCopy
import com.leyu.melora.ui.common.bookHeaderCopy
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.common.LocalChromeTopInset
import com.leyu.melora.ui.common.chromeContentPadding
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.AudioEffects
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.formatPlayCountLabel
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.ErrorState
import com.leyu.melora.ui.common.OnlineSongRow
import com.leyu.melora.ui.common.rememberFastScrollToTop
import com.leyu.melora.ui.common.titleScrollToTop
import com.leyu.melora.ui.common.SkeletonSongList
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


// 歌词视图配置状态
data class LyricsUiConfig(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val isCentered: Boolean = false,
    val isBold: Boolean = false,
    val isBlurEnabled: Boolean = false,
) {
    fun resizeBy(steps: Int): LyricsUiConfig =
        copy(fontSizeSp = (fontSizeSp + steps * 2f).coerceIn(FONT_SIZE_MIN_SP, FONT_SIZE_MAX_SP))

    fun resetFontSize(): LyricsUiConfig = copy(fontSizeSp = DEFAULT_FONT_SIZE_SP)

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 22f
        const val FONT_SIZE_MIN_SP = 16f
        const val FONT_SIZE_MAX_SP = 30f
    }
}

// VerticalPager Page 0: 全屏播放页
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullPlayerPageContent(
    state: PlayerUiState,
    lyricPosition: androidx.compose.runtime.State<Long>,
    motionEnabled: Boolean,
    lyricFrame: androidx.compose.runtime.State<com.leyu.melora.playback.LyricFrame>,
    lyricLines: List<LyricLine>,
    onOpenQueue: () -> Unit,
    queuePagerState: PagerState,
    onArtworkPositioned: (LayoutCoordinates) -> Unit,
    artworkAlpha: () -> Float,
    coverStyle: PlayerCoverStyle,
    artworkRotation: () -> Float,
    onPageVisualChanged: (Boolean, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    isCollapsed: Boolean = false,
) {
    val track = state.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerColors = LocalPlayerColors.current

    // 中间三页联动 HorizontalPager (0=音频信息, 1=封面+多行微缩歌词, 2=全屏歌词流)
    val coverPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    // 弹窗状态管理
    var lyricsConfig by remember { mutableStateOf(LyricsUiConfig()) }
    var showTimerSettings by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showAudioEffects by remember { mutableStateOf(false) }
    // 专辑/歌手聚合页（从音频信息页点击进入）
    var openedCollection by remember(isCollapsed) { mutableStateOf<SongsCollection?>(null) }

    // 音效预设与歌词由全局设置/播放层统一提供，切换歌曲不会丢失。
    val audioEffectPreset by MeloraSettings.audioEffectPreset.collectAsStateWithLifecycle()
    val audioEffectState by AudioEffects.state.collectAsStateWithLifecycle()
    val miniLyricsEnabled by MeloraSettings.miniLyricsEnabled.collectAsStateWithLifecycle()

    // 重新进入全屏或切回封面页时补齐缺图，不依赖歌词是否已缓存。
    LaunchedEffect(track?.uid, coverPagerState.currentPage, isVisible) {
        if (isVisible && coverPagerState.currentPage == 1) PlaybackController.ensureCurrentArtwork()
    }

    // 队列上滑手势：仅在播放页/黑胶页生效；歌词页与专辑/歌手聚合页内不触发
    val queueSwipeAllowed = openedCollection == null && coverPagerState.currentPage != 2

    // 聚合页与“我的列表”复用，跟随App主题；返回后恢复播放器自己的主题。
    val collection = openedCollection
    val pageIsLight = playerPageIsLight(collection != null, playerColors.isDark, MeloraAppearance.isDark)
    LaunchedEffect(collection != null, coverPagerState.currentPage, coverPagerState.isScrollInProgress, pageIsLight) {
        onPageVisualChanged(
            collection == null && coverPagerState.currentPage == 1 && !coverPagerState.isScrollInProgress,
            pageIsLight,
            collection == null && coverPagerState.currentPage == 2,
        )
    }
    DetailPageHost(
        target = collection,
        modifier = modifier,
        detail = { detailCollection ->
            SongsCollectionPage(
                collection = detailCollection,
                onBack = { openedCollection = null },
            )
        },
    ) {
    // 若不在主封面页，按返回键先回到中心封面页；在中心封面页按返回键收起全屏
    if (isVisible && coverPagerState.currentPage != 1) {
        BackHandler {
            scope.launch { coverPagerState.animateScrollToPage(1) }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val twoPanes = playerUsesTwoPanes(maxWidth.value, maxHeight.value)
        val pagePadding = playerPaneContentPadding(twoPanes, controls = false)
        val controlsPadding = playerPaneContentPadding(twoPanes, controls = true)
        val heading: @Composable (Modifier) -> Unit = { headingModifier ->
            Row(
                modifier = headingModifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 只有换曲才滚动标题；进度、循环模式、封面回填不重启动效。
                AnimatedContent(
                    targetState = track,
                    contentKey = { it?.uid },
                    modifier = Modifier.weight(1f).clipToBounds(),
                    transitionSpec = {
                        ((slideInHorizontally(tween(320, easing = LinearOutSlowInEasing)) { it } +
                            fadeIn(tween(160))) togetherWith
                            (slideOutHorizontally(tween(220, easing = LinearOutSlowInEasing)) { -it } +
                                fadeOut(tween(120)))).using(null)
                    },
                    label = "playerHeading",
                ) { headingTrack ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = headingTrack?.title ?: "未在播放",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FullPlayerTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(headingTrack?.artist, headingTrack?.album.takeIf { !it.isNullOrBlank() })
                                .joinToString(" · "),
                            fontSize = 12.sp,
                            color = FullPlayerTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { showSystemOutputSwitcher(context) },
                    modifier = Modifier.offset(x = 13.dp),
                ) {
                    Icon(
                        Icons.Outlined.Sensors,
                        contentDescription = "播放设备",
                        tint = FullPlayerTextMuted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

        }
        val pane: @Composable (Modifier) -> Unit = { paneModifier ->
            HorizontalPager(
                state = coverPagerState,
                modifier = paneModifier,
                // PageSize.Fill必须取得完整视口，非零contentPadding会让相邻页在静止时露出。
                contentPadding = PaddingValues(0.dp),
            ) { pageIndex ->
                // 先按整页边界裁切，再留正文内距：不串页，也不在屏幕内侧提前硬切。
                Box(Modifier.fillMaxSize().clipToBounds().padding(pagePadding)) {
                    when (pageIndex) {
                        0 -> AudioInfoPage(
                            track = track,
                            resolvedPlatform = state.resolvedPlatform,
                            resolvedBy = state.resolvedBy,
                            audioSpec = state.audioSpec,
                            onOpenAlbum = { album ->
                                val raw = track?.raw
                                val isBookChapter = raw?.optBoolean("isBookChapter") == true
                                val bookAlbumId = raw?.optString("albumId")
                                    .orEmpty()
                                    .takeIf { isBookChapter && it.isNotBlank() }
                                val onlineSource = track?.source
                                    ?.takeIf { it.isNotBlank() && it != "local" } ?: "kw"
                                openedCollection = SongsCollection(
                                    title = album,
                                    subtitle = if (isBookChapter) "有声专辑 · 按集数顺序" else "专辑 · 收录歌曲",
                                    keyword = album,
                                    source = onlineSource,
                                    albumName = album,
                                    artistName = track?.artist?.takeIf { it.isNotBlank() },
                                    bookAlbumId = bookAlbumId,
                                    preferBook = isBookChapter,
                                    artwork = track?.artwork,
                                    description = raw?.optString("description").orEmpty(),
                                    playCountLabel = raw?.optString("play_count").orEmpty(),
                                )
                            },
                            onOpenArtist = { artist ->
                                val onlineSource = track?.source
                                    ?.takeIf { it.isNotBlank() && it != "local" } ?: "kw"
                                openedCollection = SongsCollection(
                                    title = artist,
                                    subtitle = "歌手 · 全部歌曲",
                                    keyword = artist,
                                    source = onlineSource,
                                    artistName = artist,
                                )
                            },
                        )
                        1 -> VinylCoverPage(
                            track = track,
                            lyrics = lyricLines,
                            retryArtwork = isVisible && coverPagerState.currentPage == 1,
                            compact = twoPanes,
                            miniLyricsEnabled = miniLyricsEnabled,
                            coverStyle = coverStyle,
                            artworkAlpha = artworkAlpha,
                            artworkRotation = artworkRotation,
                            onArtworkPositioned = onArtworkPositioned,
                            position = lyricPosition,
                            frame = lyricFrame,
                            motionEnabled = motionEnabled,
                            onNavigateToLyrics = {
                                scope.launch { coverPagerState.animateScrollToPage(2) }
                            },
                        )
                        2 -> LyricsPage(
                            track = track,
                            lyrics = lyricLines,
                            position = lyricPosition,
                            frame = lyricFrame,
                            motionEnabled = motionEnabled,
                            config = lyricsConfig,
                            onConfigChange = { lyricsConfig = it },
                        )
                    }
                }
            }

        }
        val transport: @Composable () -> Unit = {
            // 细长进度线（无圆点极简纯线条设计） + 左右时间（消除48dp隐形留白干扰）
            var dragging by remember { mutableStateOf<Float?>(null) }
            val range = 0f..(state.durationMs.takeIf { it > 0 }?.toFloat() ?: 1f)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Slider(
                    value = dragging ?: state.positionMs.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { PlaybackController.seekTo(it.toLong()) }
                        dragging = null
                    },
                    valueRange = range,
                    enabled = state.durationMs > 0,
                    colors = SliderDefaults.colors(
                        activeTrackColor = FullPlayerProgressActive,
                        inactiveTrackColor = FullPlayerProgressInactive,
                    ),
                    thumb = {},
                    track = { sliderState: SliderState ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(FullPlayerProgressInactive),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(
                                        ((sliderState.value - range.start) / (range.endInclusive - range.start))
                                            .coerceIn(0f, 1f),
                                    )
                                    .height(2.5.dp)
                                    .background(FullPlayerProgressActive),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            ) {
                Text(
                    formatClock(dragging?.toLong() ?: state.positionMs),
                    color = FullPlayerTextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatClock(state.durationMs),
                    color = FullPlayerTextMuted,
                    fontSize = 12.sp,
                )
            }

            // 紧凑控制区：进度条与播放控制紧密贴合
            Spacer(Modifier.height(4.dp))

            // 主播放控制：上一首 / 纯白大号播放键（无底圆） / 下一首
            FullPlayerPrimaryControls(
                playing = state.playing,
                onPrevious = { PlaybackController.previous() },
                onToggle = { PlaybackController.toggle() },
                onNext = { PlaybackController.next() },
            )

            Spacer(Modifier.height(4.dp))

            // 底部 5 个次要控制钮（循环模式、定时设置、音效、队列、更多操作）
            FullPlayerSecondaryControls(
                mode = state.mode,
                effectActive = audioEffectState.active,
                onCycleMode = { PlaybackController.cycleMode() },
                onShowTimer = { showTimerSettings = true },
                onOpenEffects = { showAudioEffects = true },
                onOpenQueue = onOpenQueue,
                onShowMore = { showMoreActions = true },
            )
        }
        // 同一份队列页：竖屏接管整页，横屏只接管右侧控制区。
        val playerAndQueue: @Composable (Modifier, @Composable () -> Unit) -> Unit = { pagerModifier, playerContent ->
            VerticalPager(
                state = queuePagerState,
                userScrollEnabled = twoPanes || queuePagerState.currentPage == 1 || queueSwipeAllowed,
                modifier = pagerModifier,
            ) { page ->
                if (page == 0) {
                    playerContent()
                } else {
                    QueuePageContent(
                        state = state,
                        onBackToPlayer = { scope.launch { queuePagerState.animateScrollToPage(0) } },
                        horizontalPadding = if (twoPanes) 0.dp else 18.dp,
                        modifier = if (twoPanes) Modifier.padding(controlsPadding) else Modifier,
                    )
                }
            }
        }
        if (twoPanes) {
            Row(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                pane(Modifier.weight(1f).fillMaxHeight())
                playerAndQueue(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().padding(controlsPadding).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        heading(Modifier)
                        Spacer(Modifier.height(20.dp))
                        transport()
                    }
                }
            }
        } else {
            playerAndQueue(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    heading(Modifier.padding(controlsPadding))
                    Spacer(Modifier.height(16.dp))
                    pane(Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(2.dp))
                    Column(Modifier.fillMaxWidth().padding(controlsPadding)) { transport() }
                }
            }
        }
    }

    // 1. 定时与倍速设置底部抽屉
    if (showTimerSettings) {
        TimerSettingsBottomSheet(
            currentSpeed = state.speed,
            onSpeedChange = { PlaybackController.setSpeed(it) },
            onDismiss = { showTimerSettings = false },
        )
    }

    // 2. 更多操作底部抽屉（添加歌单、下一首播放、下载）
    if (showMoreActions) {
        MoreActionsBottomSheet(
            track = track,
            onDismiss = { showMoreActions = false },
        )
    }

    // 3. 音效设置底部抽屉（软件均衡/混响预设）
    if (showAudioEffects) {
        AudioEffectsBottomSheet(
            current = audioEffectPreset,
            onToggle = MeloraSettings::toggleAudioEffectPreset,
            onDismiss = { showAudioEffects = false },
        )
    }
    }
}

// 系统原生媒体输出面板（Android 14+）；不可用时退回系统蓝牙设置
private fun showSystemOutputSwitcher(context: Context) {
    val shown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && runCatchingCancellable {
        MediaRouter2.getInstance(context).showSystemOutputSwitcher()
    }.getOrDefault(false)
    if (!shown) {
        runCatchingCancellable {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

// 播放页 Page 0: 封面右划显示音频详细信息、出自专辑、参与创作艺术家（专辑/歌手可点击进入聚合页）
@Composable
private fun AudioInfoPage(
    track: UiTrack?,
    resolvedPlatform: String?,
    resolvedBy: String?,
    audioSpec: AudioSpecification?,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val autoSwitch by MeloraSettings.autoSwitchSource.collectAsStateWithLifecycle()
    val platformName = sourceAliasDisplay(track?.source.orEmpty(), platformLabel(track?.source))
    val playbackSource = resolvedByLabel(resolvedBy) +
        (resolvedPlatform?.takeIf { it in setOf("kw", "kg", "wy", "tx", "mg") && it != track?.source }
            ?.let { " · ${platformLabel(it)}" } ?: "")
    val isLocal = resolvedBy in TrackRegistry.LOCAL_RESOURCE_IDS
    // 只显示已选中音轨的实测徽标；未解析时不借目录最高档或请求档冒充实际音质。
    val qualityBadge = audioSpec?.qualityBadge
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AudioInfoCard(
            platformName = platformName,
            qualityBadge = qualityBadge,
            sourceLabel = if (autoSwitch && !isLocal) "播放源：$playbackSource · 已启用自动换源" else "播放源：$playbackSource",
        )
        AlbumInfoCard(track = track, onOpenAlbum = onOpenAlbum)
        ArtistInfoCard(track = track, onOpenArtist = onOpenArtist)
    }
}

// 专辑/歌手聚合页请求描述
data class SongsCollection(
    val title: String,
    val subtitle: String,
    val keyword: String,
    val source: String,
    val albumName: String? = null,
    val artistName: String? = null,
    // 有声专辑：走 KwBookApi 章节接口，顺序与听书页一致
    val bookAlbumId: String? = null,
    // 旧队列/旧记录缺少 albumId 时，仍按有声专辑处理（先按专辑名反查 id）
    val preferBook: Boolean = false,
    val artwork: String? = null,
    val description: String = "",
    val playCountLabel: String = "",
)

/**
 * 专辑/歌手全部歌曲页：单平台搜索 + 过滤，按页懒加载。
 * 打开只请求第 1 页；滚动接近底部才补下一页；过滤后整页无匹配时最多再补 2 页，避免请求风暴。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun SongsCollectionPage(
    collection: SongsCollection,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = key(
        collection.source,
        collection.keyword,
        collection.title,
        collection.albumName,
        collection.artistName,
        collection.bookAlbumId,
        collection.preferBook,
    ) { rememberLazyListState() }
    val scrollToTop = rememberFastScrollToTop(listState)
    val selection = remember(collection) { SongSelectionState() }
    BackHandler { if (selection.active) selection.finish() else onBack() }
    val controlBarHazeState = rememberHazeState()
    val blurEnabled by MeloraSettings.blurTopBar.collectAsStateWithLifecycle()
    val bodyHazeModifier = if (blurEnabled) Modifier.hazeSource(controlBarHazeState) else Modifier
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
    val favoriteAlbums by UserLibrary.favoriteAlbums.collectAsStateWithLifecycle()
    val favoriteArtists by UserLibrary.favoriteArtists.collectAsStateWithLifecycle()
    val favoritePlaylists by UserLibrary.favoritePlaylists.collectAsStateWithLifecycle()
    val isBook = collection.preferBook || collection.bookAlbumId != null
    val isArtist = !isBook && collection.albumName == null
    val artistName = collection.artistName?.takeIf { it.isNotBlank() } ?: collection.title
    val artistKey = "${collection.source}_$artistName"
    var resolvedBookId by remember(collection) { mutableStateOf(collection.bookAlbumId?.removePrefix("book_album_")) }
    var artwork by remember(collection) { mutableStateOf(collection.artwork) }
    var albumProfile by remember(collection) { mutableStateOf<AlbumProfile?>(null) }
    var artistProfile by remember(collection) { mutableStateOf<ArtistProfile?>(null) }
    var bookIntro by remember(collection) { mutableStateOf(collection.description) }
    var bookHeat by remember(collection) { mutableStateOf(collection.playCountLabel) }
    var bookAuthor by remember(collection) { mutableStateOf(collection.artistName.orEmpty()) }
    val albumKey = collection.albumName
        ?.takeIf { collection.bookAlbumId == null && !collection.preferBook }
        ?.let { "${collection.source}_${it}_${collection.artistName.orEmpty()}" }

    var songs by remember(collection) { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var page by remember(collection) { mutableIntStateOf(1) }
    var hasMore by remember(collection) { mutableStateOf(true) }
    var loading by remember(collection) { mutableStateOf(true) }
    var loadingMore by remember(collection) { mutableStateOf(false) }
    var loadMoreFailed by remember(collection) { mutableStateOf(false) }
    var error by remember(collection) { mutableStateOf<String?>(null) }
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }
    // 自动补页预算：过滤后命中少时避免连续翻页造成请求风暴，滚动后恢复
    var autoBudget by remember(collection) { mutableIntStateOf(2) }

    val firstSong = songs.firstOrNull()
    LaunchedEffect(collection, isArtist, artistName) {
        if (isArtist) {
            artistProfile = CatalogMetadata.artist(artistName)
            if (artwork.isNullOrBlank()) artwork = artistProfile?.image
        }
    }
    LaunchedEffect(collection, isBook, isArtist, collection.albumName, artistName) {
        val album = collection.albumName
        if (!isBook && !isArtist && !album.isNullOrBlank()) {
            albumProfile = CatalogMetadata.album(album, artistName)
            if (artwork.isNullOrBlank()) artwork = albumProfile?.image
        }
    }
    LaunchedEffect(collection, firstSong?.uid) {
        if (!isArtist && artwork.isNullOrBlank() && firstSong != null) artwork = CoverLoader.resolve(context, firstSong)
    }

    val artistParts = remember(collection.artistName) {
        collection.artistName
            ?.split('、', '/', ',', '，', '&')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    fun matches(song: OnlineSong): Boolean {
        val album = collection.albumName
        if (album != null && !song.albumName.contains(album, ignoreCase = true)) return false
        if (artistParts.isNotEmpty() && artistParts.none { song.singer.contains(it, ignoreCase = true) }) return false
        return true
    }

    // 播放来源容器：专辑/歌手/有声专辑，登记到「我的 → 最近播放」并可回跳
    fun playContainer(): UserLibrary.PlayContainer? {
        val album = collection.albumName?.trim().orEmpty()
        val img = artwork ?: if (isArtist) null else songs.firstOrNull()?.let { CoverLoader.cachedUrl(it) }
        val bookId = resolvedBookId
        return when {
            collection.preferBook || bookId != null -> bookId?.let {
                UserLibrary.PlayContainer(
                    kind = "book",
                    id = "book_album_$it",
                    name = album.ifBlank { collection.title },
                    img = img,
                    source = collection.source,
                    queueId = "playlist.${collection.source}.book_album_$it",
                )
            }
            album.isNotEmpty() -> UserLibrary.PlayContainer(
                kind = "album",
                id = "album_${collection.source}_$album",
                name = album,
                img = img,
                source = collection.source,
                queueId = "album.${collection.source}.$album",
                artist = collection.artistName.orEmpty(),
            )
            collection.artistName != null -> UserLibrary.PlayContainer(
                kind = "artist",
                id = "artist_${collection.source}_${collection.artistName}",
                name = collection.artistName,
                img = img,
                source = collection.source,
                queueId = "artist.${collection.source}.${collection.artistName}",
                artist = collection.artistName,
            )
            else -> null
        }
    }

    fun load(targetPage: Int) {
        if (loadingMore) return
        loadingMore = true
        loadMoreFailed = false
        if (songs.isEmpty()) loading = true
        scope.launch {
            error = null
            var current = targetPage
            var extra = 0
            runCatchingCancellable {
                // 旧队列缺 albumId 时，先按专辑名反查有声专辑 id，保证仍走章节顺序
                val target = (collection.albumName ?: collection.keyword).trim()
                val bookAlbumId = resolvedBookId ?: if (collection.preferBook) {
                    runCatchingCancellable {
                        val books = KwBookApi.search(target, 1).items
                        (books.firstOrNull { it.name.trim() == target }
                            ?: books.firstOrNull { it.name.contains(target, true) || target.contains(it.name, true) })
                            ?.id
                            ?.removePrefix("book_album_")
                    }.getOrNull()
                } else null
                if (bookAlbumId != null) {
                    resolvedBookId = bookAlbumId
                    // 有声专辑：直接复用书籍章节接口（100 集/页，顺序正确）
                    val result = KwBookApi.album("book_album_$bookAlbumId", current)
                    if (result.metadata.description.isNotBlank()) bookIntro = result.metadata.description
                    if (result.metadata.playCount > 0) bookHeat = formatPlayCountLabel(result.metadata.playCount.toString())
                    if (result.metadata.author.isNotBlank()) bookAuthor = result.metadata.author
                    if (artwork.isNullOrBlank()) artwork = result.metadata.artwork
                    result.items to result.hasMore
                } else {
                    val collected = mutableListOf<OnlineSong>()
                    var more = true
                    while (more && collected.isEmpty() && extra <= 2) {
                        val result = OnlineRepository.search(context, collection.source, collection.keyword, current, 30)
                        collected += result.list.filter(::matches)
                        more = result.list.size >= 30
                        if (collected.isEmpty() && more) {
                            current += 1
                            extra += 1
                        }
                    }
                    collected to more
                }
            }
                .onSuccess { (list, more) ->
                    songs = if (targetPage > 1) (songs + list).distinctBy { it.uid } else list.distinctBy { it.uid }
                    page = current
                    hasMore = more
                }
                .onFailure {
                    if (songs.isEmpty()) error = it.message ?: "加载失败" else loadMoreFailed = true
                }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(collection) { load(1) }

    // 用户每次滚动后恢复自动补页预算
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) autoBudget = 2 }
    }

    // 懒加载：滚动接近底部时补下一页；预算用尽后由底部"点击加载更多"接管
    LaunchedEffect(listState, songs.size, hasMore, loadMoreFailed, autoBudget) {
        if (!hasMore || loadMoreFailed || autoBudget <= 0) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && !loadingMore && songs.isNotEmpty()) {
                    autoBudget -= 1
                    load(page + 1)
                }
            }
    }

    val bookPlaylist = resolvedBookId?.let { id ->
        OnlinePlaylist(JSONObject().put("id", "book_album_$id").put("name", collection.title)
            .put("source", collection.source).put("kind", "book").put("img", artwork ?: "")
            .put("author", bookAuthor).put("description", bookIntro).put("play_count", bookHeat))
    }
    val isFavorite = when {
        isArtist -> favoriteArtists.any { it.key == artistKey }
        albumKey != null -> favoriteAlbums.any { it.key == albumKey }
        bookPlaylist != null -> favoritePlaylists.any { it.id == bookPlaylist.id && it.source == bookPlaylist.source }
        else -> false
    }
    val onFavorite: (() -> Unit)? = when {
        isArtist -> { { UserLibrary.toggleFavoriteArtist(UserLibrary.FavoriteArtist(artistName, collection.source, artwork)) } }
        albumKey != null -> { { UserLibrary.toggleFavoriteAlbum(UserLibrary.FavoriteAlbum(
            collection.albumName, collection.artistName.orEmpty(), collection.source, artwork,
        )) } }
        bookPlaylist != null -> { { UserLibrary.toggleFavoritePlaylist(bookPlaylist) } }
        else -> null
    }
    ChromeScaffold(
        modifier = Modifier.fillMaxSize(),
        contentSource = controlBarHazeState,
        expectedTopBarHeight = 64.dp,
        topBar = {
            SongSelectionTopBar(selection, songs) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MeloraAppearance.textMain)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 16.dp)
                            .titleScrollToTop(scrollToTop),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            collection.title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MeloraAppearance.textMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        bottomBar = { SongBatchActionsBar(selection, songs) },
    ) {
        val topInset = LocalChromeTopInset.current
        val headerBottom = with(LocalDensity.current) { topInset.roundToPx() }
        val pinned by remember(listState, headerBottom) {
            derivedStateOf {
                !selection.active && shouldPinPlaylistControls(
                    listState.firstVisibleItemIndex,
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "controlBar" }
                        ?.let { it.offset - listState.layoutInfo.viewportStartOffset },
                    headerBottom,
                )
            }
        }
        val dividerAlpha by animateFloatAsState(if (pinned && !blurEnabled) 1f else 0f, tween(200), label = "collectionDivider")
        val controls: @Composable (Boolean) -> Unit = { stuck ->
            ChromeFloatingBar(state = controlBarHazeState, topOffset = topInset, height = PlaylistControlsHeight,
                pinned = stuck, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(chromeHeaderColor())) {
                    CollectionActionsRow(
                        canPlay = songs.isNotEmpty(), favorite = isFavorite,
                        onPlay = { PlaybackController.playQueue(context, songs.toUiTracks(), 0, container = playContainer()) },
                        onFavorite = onFavorite, onSelect = selection::start,
                    )
                    Box(Modifier.fillMaxWidth().height(.6.dp).alpha(dividerAlpha).background(MeloraAppearance.divider))
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!selection.active) {
                item(key = "detailHeader") {
                    val header = when {
                        isBook -> bookHeaderCopy(bookIntro, bookAuthor, bookHeat)
                        isArtist -> artistHeaderCopy(artistProfile, songs)
                        else -> albumHeaderCopy(albumProfile, artistName, songs)
                    }
                    CollectionInfoHeader(
                        image = artwork, seed = "${collection.source}_${collection.title}", artist = isArtist,
                        primary = header.primary,
                        secondary = header.secondary,
                        primaryMaxLines = header.primaryMaxLines,
                        modifier = bodyHazeModifier,
                    )
                }
                item(key = "controlBar", contentType = "controls") {
                    Box(Modifier.fillMaxWidth().height(PlaylistControlsHeight)) { if (!pinned) controls(false) }
                }
            }
            when {
                loading -> item(key = "loading") { SkeletonSongList(modifier = bodyHazeModifier, rows = 7, rowSpacing = 2.dp) }
                error != null -> item(key = "error") {
                    ErrorState(error!!, onRetry = { load(1) }, modifier = Modifier.fillParentMaxHeight(.65f).then(bodyHazeModifier))
                }
                songs.isEmpty() -> item(key = "empty") {
                    EmptyState("没有找到相关歌曲", Modifier.fillParentMaxHeight(.65f).then(bodyHazeModifier))
                }
                else -> {
                    itemsIndexed(songs, key = { index, song -> "${song.uid}:$index" }) { index, song ->
                        Box(Modifier.fillMaxWidth().then(bodyHazeModifier)) {
                            OnlineSongRow(
                                song = song, showAlbum = collection.albumName == null,
                                isFavorite = song.uid in favoriteUids,
                                onMore = { moreSong = song },
                                selectionMode = selection.active, selected = song.uid in selection.selectedUids,
                                onClick = {
                                    if (selection.active) selection.toggle(song.uid)
                                    else PlaybackController.playTrack(context, UiTrack.fromOnline(song), container = playContainer())
                                },
                            )
                        }
                    }
                    if (loadingMore || loadMoreFailed || (hasMore && autoBudget == 0)) {
                        item(key = "loadMore") {
                            val interactive = !loadingMore && hasMore
                            Box(Modifier.fillMaxWidth().then(bodyHazeModifier).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = when { loadMoreFailed -> "加载更多失败，点击重试"; loadingMore -> "正在加载…"; else -> "点击加载更多" },
                                    fontSize = 13.sp, color = if (interactive) MeloraAppearance.brand else MeloraAppearance.textMuted,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = interactive) { load(page + 1) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (pinned && !selection.active) {
            Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = topInset)) { controls(true) }
        }
    }
    moreSong?.let { song -> SongMoreSheet(song) { moreSong = null } }
}

// 封面与歌词使用同一个约束计算宽度，不依赖测量回调后二次改布局。
internal fun playerCoverSideDp(width: Float, height: Float, lyricAreaHeight: Float): Float =
    minOf(width, (height - lyricAreaHeight).coerceAtLeast(0f)) * 0.92f

// 播放页 Page 1：默认封面歌词对齐左边沿，圆形/黑胶歌词居中。
@Composable
private fun VinylCoverPage(
    track: UiTrack?,
    lyrics: List<LyricLine>,
    retryArtwork: Boolean,
    compact: Boolean,
    miniLyricsEnabled: Boolean,
    coverStyle: PlayerCoverStyle,
    artworkAlpha: () -> Float,
    artworkRotation: () -> Float,
    onArtworkPositioned: (LayoutCoordinates) -> Unit,
    position: androidx.compose.runtime.State<Long>,
    motionEnabled: Boolean,
    frame: androidx.compose.runtime.State<com.leyu.melora.playback.LyricFrame>,
    onNavigateToLyrics: () -> Unit,
) {
    val lines = fullPlayerLyricsOrFallback(track, lyrics)
    val centered = nowPlayingArtworkShape(coverStyle) == NowPlayingArtworkShape.Circle
    val previewHeight = if (compact) 28.dp else with(LocalDensity.current) { 24.sp.toDp() * 5 } + 12.dp
    val lyricsHeight = if (miniLyricsEnabled) previewHeight + 16.dp else 0.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (centered) {
            playerCoverSideDp(maxWidth.value, maxHeight.value, lyricsHeight.value).dp
        } else {
            minOf(maxWidth.value, (maxHeight.value - lyricsHeight.value).coerceAtLeast(0f)).dp
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (!centered) {
                // 多余空间全部推给封面到标题栏之间
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(0.5f))
            }
            NowPlayingArtwork(
                url = track?.artwork,
                seed = track?.uid ?: "empty",
                style = coverStyle,
                modifier = Modifier.size(side)
                    .onGloballyPositioned(onArtworkPositioned)
                    .graphicsLayer { alpha = artworkAlpha() },
                cornerRadius = 18,
                retryOnError = retryArtwork,
                smoothChanges = true,
                rotationDegrees = artworkRotation,
            )
            if (miniLyricsEnabled) {
                Spacer(Modifier.height(12.dp))
                key(track?.uid, lines, compact) {
                    LyricsViewport(
                        lines = lines,
                        position = position,
                        config = LyricsUiConfig(fontSizeSp = if (compact) 12f else 16f),
                        mini = true, frameState = frame, motionEnabled = motionEnabled,
                        centered = centered,
                        modifier = (if (centered) Modifier.width(side) else Modifier.fillMaxWidth()).height(previewHeight),
                        onLineClick = { onNavigateToLyrics() },
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            if (centered) {
                Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

// 全屏和 mini 只使用同一渲染核心，外层保留字号/对齐工具与视口遮罩。
@Composable
private fun LyricsPage(
    track: UiTrack?,
    lyrics: List<LyricLine>,
    position: androidx.compose.runtime.State<Long>,
    motionEnabled: Boolean,
    frame: androidx.compose.runtime.State<com.leyu.melora.playback.LyricFrame>,
    config: LyricsUiConfig,
    onConfigChange: (LyricsUiConfig) -> Unit,
) {
    val lines = remember(track?.uid, track?.title, track?.artist, lyrics) { fullPlayerLyricsOrFallback(track, lyrics) }
    Column(Modifier.fillMaxSize()) {
        key(track?.uid, lines) {
            LyricsViewport(lines, position, config, frameState = frame, motionEnabled = motionEnabled,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black,
                            0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
                onLineClick = { if (lyrics.isNotEmpty()) PlaybackController.seekTo(it.startMs) },
            )
        }
        LyricsSettingsTools(config = config, onConfigChange = onConfigChange)
    }
}

/** 固定高度的行内工具条；展开只占用右侧空白，不移动歌词或进度条。 */
@Composable
private fun LyricsSettingsTools(
    config: LyricsUiConfig,
    onConfigChange: (LyricsUiConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = FullPlayerSheetPrimaryBlue
    val chipInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = chipInteraction,
                    indication = null,
                    role = Role.Button,
                ) { expanded = !expanded }
                .semantics { contentDescription = if (expanded) "收起歌词设置" else "展开歌词设置" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = 1.dp,
                        color = if (expanded) accent else FullPlayerTextPrimary.copy(alpha = 0.40f),
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "词",
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (expanded) accent else FullPlayerTextPrimary.copy(alpha = 0.93f),
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                    modifier = Modifier.offset(y = (-0.5).dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // 外层由起点揭示；横向滚动不越界
        Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.CenterStart) {
            LyricsToolsPanel(expanded, config, onConfigChange)
        }
    }
}

@Composable
private fun LyricsToolsPanel(
    expanded: Boolean,
    config: LyricsUiConfig,
    onConfigChange: (LyricsUiConfig) -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandHorizontally(tween(220, easing = LinearOutSlowInEasing), expandFrom = Alignment.Start) + fadeIn(tween(140)),
        exit = shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start) + fadeOut(tween(120)),
    ) {
        Row(
            Modifier.height(36.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsToolButton(
                Icons.Rounded.RestartAlt,
                "重置字号，恢复${LyricsUiConfig.DEFAULT_FONT_SIZE_SP.toInt()}sp",
                iconSize = 19.dp,
                enabled = config.fontSizeSp != LyricsUiConfig.DEFAULT_FONT_SIZE_SP,
            ) { onConfigChange(config.resetFontSize()) }
            LyricsToolButton(Icons.Rounded.FormatSize, "减小字号，当前${config.fontSizeSp.toInt()}sp", iconSize = 19.dp, enabled = config.fontSizeSp > LyricsUiConfig.FONT_SIZE_MIN_SP) {
                onConfigChange(config.resizeBy(-1))
            }
            LyricsToolButton(Icons.Rounded.FormatSize, "增大字号，当前${config.fontSizeSp.toInt()}sp", iconSize = 24.dp, enabled = config.fontSizeSp < LyricsUiConfig.FONT_SIZE_MAX_SP) {
                onConfigChange(config.resizeBy(1))
            }
            LyricsToolDivider()
            LyricsToolButton(
                if (config.isCentered) Icons.Rounded.FormatAlignCenter else Icons.AutoMirrored.Rounded.FormatAlignLeft,
                "居中对齐", iconSize = 20.dp, isSelected = config.isCentered,
            ) { onConfigChange(config.copy(isCentered = !config.isCentered)) }
            LyricsToolButton(Icons.Rounded.FormatBold, "加粗歌词", iconSize = 20.dp, isSelected = config.isBold) {
                onConfigChange(config.copy(isBold = !config.isBold))
            }
            LyricsToolDivider()
            LyricsToolButton(Icons.Rounded.BlurOn, "虚化非当前歌词", iconSize = 20.dp, isSelected = config.isBlurEnabled) {
                onConfigChange(config.copy(isBlurEnabled = !config.isBlurEnabled))
            }
        }
    }
}

@Composable
private fun LyricsToolDivider() {
    Box(Modifier.padding(horizontal = 5.dp).width(1.dp).height(16.dp).background(FullPlayerTextMuted.copy(alpha = 0.22f)))
}

@Composable
private fun LyricsToolButton(
    icon: ImageVector,
    label: String,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    isSelected: Boolean? = null,
    onClick: () -> Unit,
) {
    val accent = FullPlayerSheetPrimaryBlue
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                isSelected?.let { selected = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = when {
                !enabled -> FullPlayerTextMuted.copy(alpha = 0.35f)
                isSelected == true -> accent
                else -> FullPlayerTextMuted
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

// 睡眠定时与播放倍速设置底部抽屉：完全统一全应用抽屉规范
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerSettingsBottomSheet(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var timerMinutes by remember { mutableFloatStateOf(30f) }
    var extendToFinishTrack by remember { mutableStateOf(true) }
    val sleepRemaining by PlaybackController.sleepRemaining.collectAsStateWithLifecycle()
    val sleepTimerEnabled = sleepRemaining != null

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    MeloraBottomSheet(
        onDismissRequest = onDismiss,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            // 统一头部卡片：44dp 质感图标 + 标题 + 副标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MeloraAppearance.brand.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = MeloraAppearance.brand,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "睡眠与播放设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MeloraAppearance.textMain,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (sleepTimerEnabled) {
                            val minutes = sleepRemaining?.div(60) ?: 0
                            val seconds = sleepRemaining?.rem(60) ?: 0
                            "定时中 · 剩余 %d 分 %d 秒后停止".format(minutes, seconds)
                        } else {
                            "播放倍速与定时停止"
                        },
                        fontSize = 12.sp,
                        color = MeloraAppearance.textSub,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MeloraAppearance.divider, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                // 1. 播放倍速调节：Melora 统一分段胶囊设计
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("播放倍速", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MeloraAppearance.textMain)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MeloraAppearance.segmentTrack,
                    ) {
                        Row(modifier = Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            speedOptions.forEach { speed ->
                                val isSelected = Math.abs(currentSpeed - speed) < 0.05f
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) MeloraAppearance.card else Color.Transparent,
                                    shadowElevation = if (isSelected) 1.dp else 0.dp,
                                    modifier = Modifier.clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { onSpeedChange(speed) },
                                ) {
                                    Text(
                                        text = "${speed}x",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MeloraAppearance.textMain else MeloraAppearance.textSub,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 2. 睡眠定时开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("睡眠定时", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MeloraAppearance.textMain)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (sleepTimerEnabled) {
                                val minutes = sleepRemaining?.div(60) ?: 0
                                val seconds = sleepRemaining?.rem(60) ?: 0
                                "剩余 %d 分 %d 秒后自动停止播放".format(minutes, seconds)
                            } else {
                                "到期自动暂停播放"
                            },
                            fontSize = 12.sp,
                            color = MeloraAppearance.textSub,
                        )
                    }
                    Switch(
                        checked = sleepTimerEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) PlaybackController.setSleepTimer(timerMinutes.toInt(), extendToFinishTrack)
                            else PlaybackController.cancelSleepTimer()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MeloraAppearance.brand,
                            uncheckedTrackColor = MeloraAppearance.softFill,
                        ),
                    )
                }

                if (sleepTimerEnabled) {
                    Spacer(Modifier.height(14.dp))
                    // 3. 定时时长调节滑块
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("定时时长", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MeloraAppearance.textMain)
                            Text("${timerMinutes.toInt()} 分钟", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MeloraAppearance.brand)
                        }
                        Slider(
                            value = timerMinutes,
                            onValueChange = { timerMinutes = it },
                            onValueChangeFinished = {
                                PlaybackController.setSleepTimer(timerMinutes.toInt(), extendToFinishTrack)
                            },
                            valueRange = 5f..90f,
                            steps = 16,
                            thumb = {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(MeloraAppearance.brand),
                                )
                            },
                            colors = SliderDefaults.colors(
                                activeTrackColor = MeloraAppearance.brand,
                                inactiveTrackColor = MeloraAppearance.softFill,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 4. 自动延长到整首歌播完开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("播完当前歌曲后再停止", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MeloraAppearance.textMain)
                        Spacer(Modifier.height(2.dp))
                        Text("倒计时结束后，等待正在播放的音频播放结束再暂停", fontSize = 12.sp, color = MeloraAppearance.textSub)
                    }
                    Switch(
                        checked = extendToFinishTrack,
                        onCheckedChange = { value ->
                            extendToFinishTrack = value
                            if (sleepTimerEnabled) PlaybackController.setSleepTimer(timerMinutes.toInt(), value)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MeloraAppearance.brand,
                            uncheckedTrackColor = MeloraAppearance.softFill,
                        ),
                    )
                }
            }
        }
    }
}

private fun audioEffectIcon(id: String): ImageVector = when (id) {
    "pop" -> Icons.Outlined.MusicNote
    "vocal" -> Icons.Outlined.Mic
    "speech" -> Icons.AutoMirrored.Outlined.MenuBook
    "rock" -> Icons.Rounded.GraphicEq
    "concert" -> Icons.Outlined.SurroundSound
    "bass" -> Icons.Outlined.Speaker
    else -> Icons.Outlined.Tune
}

// 音效设置底部抽屉：与 SongMoreSheet / LxSource 完全统一的规范设计
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioEffectsBottomSheet(
    current: String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedPreset = AudioEffects.preset(current)
    val effectState by AudioEffects.state.collectAsStateWithLifecycle()
    val playerState by PlaybackController.state.collectAsStateWithLifecycle()

    MeloraBottomSheet(
        onDismissRequest = onDismiss,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            // 预设选择与实际状态分开展示；跳动条只是播放指示，不冒充频谱分析。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "声学调音",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MeloraAppearance.textMain,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = MeloraAppearance.brand.copy(alpha = 0.11f),
                    ) {
                        Text(
                            text = selectedPreset.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MeloraAppearance.brand,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                AudioVisualizerBars(active = playerState.playing && effectState.active)
            }

            Text(
                text = AudioEffects.statusText(current, effectState),
                color = if (effectState.phase == AudioEffects.Phase.Failed) MeloraAppearance.accent else MeloraAppearance.textSub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(36.dp),
            )

            // 双列微质感预设卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AudioEffects.effectPresets.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { item ->
                            val isSelected = current == item.id

                            Surface(
                                onClick = { onToggle(item.id) },
                                shape = RoundedCornerShape(17.dp),
                                color = when {
                                    isSelected -> MeloraAppearance.brand.copy(alpha = 0.08f)
                                    else -> MeloraAppearance.card
                                },
                                border = when {
                                    isSelected -> BorderStroke(1.5.dp, MeloraAppearance.brand)
                                    else -> MeloraAppearance.cardBorder
                                },
                                shadowElevation = 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(98.dp)
                                    .semantics { selected = isSelected },
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 13.dp, vertical = 11.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(
                                                    if (isSelected) MeloraAppearance.brand.copy(alpha = 0.13f)
                                                    else MeloraAppearance.softFill,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = audioEffectIcon(item.id),
                                                contentDescription = null,
                                                tint = when {
                                                    isSelected -> MeloraAppearance.brand
                                                    else -> MeloraAppearance.textMain
                                                },
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = item.label,
                                            fontSize = 14.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = when {
                                                isSelected -> MeloraAppearance.brand
                                                else -> MeloraAppearance.textMain
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        // 保留勾选位，开关音效时标题不换行、不跳动。
                                        Box(Modifier.size(18.dp)) {
                                            if (isSelected) Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MeloraAppearance.brand,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        text = item.hint,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MeloraAppearance.textSub,
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

        }
    }
}

@Composable
private fun AudioVisualizerBars(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "audio-visualizer")
    val animatedHeights = List(6) { index ->
        transition.animateFloat(
            initialValue = 6f + (index % 3) * 2f,
            targetValue = 19f - kotlin.math.abs(index - 2.5f) * 2.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 360 + index * 45,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(index * 55, StartOffsetType.Delay),
            ),
            label = "bar-$index",
        )
    }
    Row(
        modifier = Modifier
            .width(42.dp)
            .height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        animatedHeights.forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((if (active) height.value else 6f + (index % 3) * 2f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MeloraAppearance.brand),
            )
        }
    }
}

// 更多操作底部抽屉（收藏、下一首播放、添加到歌单、下载）
@Composable
private fun MoreActionsBottomSheet(
    track: UiTrack?,
    onDismiss: () -> Unit,
) {
    val song = track?.raw?.let(OnlineSong::from)
    if (song == null) {
        onDismiss()
        return
    }
    com.leyu.melora.ui.common.SongMoreSheet(song = song, onDismiss = onDismiss)
}
