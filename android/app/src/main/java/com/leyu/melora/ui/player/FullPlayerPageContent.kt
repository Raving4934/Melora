package com.leyu.melora.ui.player

import com.leyu.melora.ui.common.submitLibraryWrite
import com.leyu.melora.ui.common.LocalPageActive

import com.leyu.melora.playback.LyricsUiConfig

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.leyu.melora.ui.audiobook.BookAuthorPage
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.playback.AudioSpecification

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
import com.leyu.melora.ui.common.MeloraSheetHandle
import com.leyu.melora.ui.common.albumHeaderCopy
import com.leyu.melora.ui.common.artistHeaderCopy
import com.leyu.melora.ui.common.componentHeadersBlurred
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.theme.DarkGlass
import com.leyu.melora.ui.theme.LightSheetFrost
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.WithMeloraSheetTheme
import com.leyu.melora.ui.theme.currentMeloraSheetPalette
import com.leyu.melora.ui.theme.meloraSwitchColors
import com.leyu.melora.ui.common.LocalChromeTopInset
import com.leyu.melora.ui.common.chromeContentPadding
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import com.leyu.melora.ui.common.rememberDesktopLyricsToggle
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
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.AudioEffects
import com.leyu.melora.playback.canonicalBookId
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.ErrorState
import com.leyu.melora.ui.common.OnlineSongRow
import com.leyu.melora.ui.common.rememberFastScrollToTop
import com.leyu.melora.ui.common.titleScrollToTop
import com.leyu.melora.ui.common.CollectionHeroArtwork
import com.leyu.melora.ui.common.CollectionShowcaseActions
import com.leyu.melora.ui.common.SkeletonSongList
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.common.PlaylistDetailContent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch


// VerticalPager Page 0: 全屏播放页
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullPlayerPageContent(
    state: PlayerUiState,
    sampledPosition: State<Long>,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    lyricPosition: androidx.compose.runtime.State<Long>,
    motionEnabled: Boolean,
    lyricFrame: androidx.compose.runtime.State<com.leyu.melora.playback.LyricFrame>,
    lyricLines: List<LyricLine>,
    onOpenQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    queuePagerState: PagerState,
    onArtworkPositioned: (LayoutCoordinates) -> Unit,
    artworkAlpha: () -> Float,
    playerArtwork: PlayerArtwork,
    coverStyle: PlayerCoverStyle,
    artworkRotation: () -> Float,
    onPageVisualChanged: (showsCover: Boolean, isLight: Boolean, blocksCollapse: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tonearmAngle: (() -> Float)? = null,
    recordChange: VinylRecordChange? = null,
    isVisible: Boolean = true,
    isCollapsed: Boolean = false,
    onCollapse: () -> Unit = {},
    queueMotionEnabled: Boolean = motionEnabled,
    onSeek: (Long) -> Unit = { PlaybackController.seekTo(it) },
    onPrevious: () -> Unit = { PlaybackController.previous() },
    onNext: () -> Unit = { PlaybackController.next() },
) {
    val track = state.current
    val autoSwitch by MeloraSettings.autoSwitchSource.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerColors = LocalPlayerColors.current
    val immersion = animateFloatAsState(
        if (immersive) 1f else 0f,
        if (motionEnabled) spring(dampingRatio = 1f, stiffness = 220f) else snap(),
        label = "playerImmersion",
    )

    // 保存业务页面而非Pager位置：宽屏只显示歌词、信息，窄屏保留原来的三页。
    var selectedPage by rememberSaveable { mutableIntStateOf(1) }

    // 弹窗状态管理
    val lyricsConfig by MeloraSettings.playerLyrics.collectAsStateWithLifecycle()
    var showTimerSettings by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showAudioEffects by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var showLyricsSource by remember { mutableStateOf(false) }
    LaunchedEffect(track?.uid, isVisible, isCollapsed) { showLyricsSource = false }
    LaunchedEffect(isVisible, isCollapsed) { if (!isVisible || isCollapsed) showCoverPicker = false }
    // 音乐聚合页保留旧详情；听书专辑统一进入公共目录页。
    var openedDetail by remember(isCollapsed) { mutableStateOf<FullPlayerDetailTarget?>(null) }
    var pendingBookCollection by remember(isCollapsed) { mutableStateOf<SongsCollection?>(null) }
    LaunchedEffect(pendingBookCollection) {
        val pending = pendingBookCollection ?: return@LaunchedEffect
        val destination = runCatchingCancellable {
            playerAlbumDestination(pending, emptyList()) ?: playerAlbumDestination(
                pending,
                KwBookApi.search(pending.albumName ?: pending.keyword, 1).items,
            )
        }.getOrNull()
        if (pendingBookCollection == pending) {
            pendingBookCollection = null
            if (destination != null) openedDetail = destination
            else Toast.makeText(context, "未能找到对应的有声专辑", Toast.LENGTH_SHORT).show()
        }
    }

    // 音效预设与歌词由全局设置/播放层统一提供，切换歌曲不会丢失。
    val audioEffectPreset by MeloraSettings.audioEffectPreset.collectAsStateWithLifecycle()
    val audioEffectState by AudioEffects.state.collectAsStateWithLifecycle()
    val miniLyricsEnabled by MeloraSettings.miniLyricsEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(immersive) { if (immersive) queuePagerState.scrollToPage(0) }

    // 聚合页与“我的列表”复用，跟随App主题；返回后恢复播放器自己的主题。
    val detailTarget = openedDetail
    val pageIsLight = playerPageIsLight(detailTarget != null, playerColors.isDark, MeloraAppearance.isDark)
    DetailPageHost(
        target = detailTarget,
        modifier = modifier,
        contentKey = { target -> when (target) {
            is FullPlayerDetailTarget.Collection -> "collection:${target.value.source}:${target.value.keyword}:${target.value.title}"
            is FullPlayerDetailTarget.Playlist -> "playlist:${target.value.source}:${target.value.id}"
        } },
        detail = { target ->
            when (target) {
                is FullPlayerDetailTarget.Collection -> SongsCollectionPage(
                    collection = target.value,
                    onBack = { openedDetail = null },
                    standalone = true,
                )
                is FullPlayerDetailTarget.Playlist -> PlaylistDetailContent(
                    playlist = target.value,
                    onBack = { openedDetail = null },
                    emptyHint = "该有声专辑暂无可播放音频",
                    standalone = true,
                )
            }
        },
    ) {
    val anchors = remember { LandscapeArtworkAnchors() }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().onPlaced { anchors.root = it },
    ) {
        val expandedLayout = playerUsesExpandedLayout(maxWidth.value, maxHeight.value)
        val compactLandscape = playerUsesCompactLandscape(maxWidth.value, maxHeight.value)
        val twoPanes = expandedLayout || compactLandscape
        val compactControlsScroll = rememberScrollState()
        val compactLandscapeNormal = compactLandscape && !immersive
        val expandedNormal = expandedLayout && !immersive
        val landscapeImmersivePager = twoPanes && immersive
        val splitTwoPages = expandedNormal || compactLandscapeNormal
        val pages = remember(splitTwoPages) { if (splitTwoPages) listOf(2, 0) else listOf(0, 1, 2) }
        // 只保存 selectedPage；分页索引与页数只在当前布局中有效。
        val coverPagerState = remember(pages) {
            val targetPage = if (landscapeImmersivePager) 1 else selectedPage
            PagerState(currentPage = pages.indexOf(targetPage).coerceAtLeast(0), pageCount = { pages.size })
        }
        val currentPage = pages[coverPagerState.currentPage]
        val compactQueueOpen = compactLandscape && queuePagerState.targetPage == 1
        val coverPageVisible = expandedNormal || compactLandscapeNormal || currentPage == 1
        val largePortrait = !twoPanes && maxWidth >= 600.dp
        val portraitStageModifier = if (largePortrait) Modifier.widthIn(max = LargePortraitPlayerStageWidth) else Modifier
        val posterMode = !miniLyricsEnabled && !twoPanes
        val coverPageIndex = pages.indexOf(1)
        val awayFromCover by remember(coverPagerState, coverPageIndex) {
            derivedStateOf {
                if (coverPageIndex < 0) 1f
                else abs((coverPagerState.currentPage + coverPagerState.currentPageOffsetFraction) - coverPageIndex)
                    .coerceIn(0f, 1f)
            }
        }
        LaunchedEffect(coverPagerState) {
            // 横屏用歌词补齐缺失的封面页，沉浸态也有自己的初始页。只记住后续实际停稳的分页。
            if (!landscapeImmersivePager) snapshotFlow { coverPagerState.settledPage }.drop(1).collect { selectedPage = pages[it] }
        }
        fun selectReadingTab(index: Int) {
            if (coverPagerState.settledPage == index) selectedPage = pages[index]
            scope.launch { coverPagerState.animateScrollToPage(index, animationSpec = PlayerPageSnapSpec) }
        }
        LaunchedEffect(track?.uid, currentPage, expandedNormal, compactLandscapeNormal, isVisible) {
            if (isVisible && coverPageVisible) PlaybackController.ensureCurrentArtwork()
        }
        // 播放分页是同级视图，不消费返回；仅沉浸态先退出，再由外层收起播放器。
        BackHandler(enabled = isVisible && immersive) { onImmersiveChange(false) }
        LaunchedEffect(detailTarget != null, currentPage, coverPagerState.isScrollInProgress, pageIsLight, immersive, expandedNormal, compactLandscapeNormal) {
            onPageVisualChanged(
                !immersive && detailTarget == null && coverPageVisible && (expandedNormal || compactLandscapeNormal || !coverPagerState.isScrollInProgress),
                pageIsLight,
                // 宽屏封面区允许下拉；右侧阅读区在自己的边界消费剩余滚动。
                detailTarget != null || (!expandedNormal && !compactLandscapeNormal && currentPage == 2),
            )
        }
        val stageLyricsMode = immersive && twoPanes
        val lyricsPageIndex = pages.indexOf(2)
        val stagePageFraction by remember(coverPagerState, lyricsPageIndex) {
            derivedStateOf {
                if (lyricsPageIndex < 0) 0f
                else (1f - abs((coverPagerState.currentPage + coverPagerState.currentPageOffsetFraction) - lyricsPageIndex))
                    .coerceIn(0f, 1f)
            }
        }
        val t = immersion.value
        val permanentInsets = WindowInsets.displayCutout.union(WindowInsets.ime).union(WindowInsets.captionBar)
        val playerInsets = (if (landscapeImmersivePager) permanentInsets else WindowInsets.safeDrawing)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        val playerTopInset by animateDpAsState(
            if (immersive) 0.dp else WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
            if (motionEnabled) spring(dampingRatio = 1f, stiffness = 220f) else snap(), label = "playerTopInset",
        )
        // 竖屏保持纯净；横屏普通页和封面沉浸展示歌名纹理，滑到第3页剧场歌词时平滑让位给两侧流动大字。
        if (!MeloraAppearance.isEInk && t > 0f && maxWidth > maxHeight) ImmersiveTitleWatermark(
            title = track?.title.orEmpty(), immersion = immersion,
            playing = state.positionAdvancing, motionEnabled = motionEnabled && isVisible,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (stageLyricsMode) 1f - stagePageFraction else 1f }
                .testTag("immersive-title-watermark"),
        )
        val pagePadding = when {
            compactLandscapeNormal -> PaddingValues(start = 12.dp, end = 24.dp)
            compactLandscape -> PaddingValues(horizontal = 16.dp)
            expandedLayout -> PaddingValues(horizontal = 20.dp)
            else -> playerPaneContentPadding(twoPanes = false, controls = false)
        }
        val controlsPadding = playerPaneContentPadding(twoPanes, controls = true)
        val eInk = MeloraAppearance.isEInk
        val heading: @Composable (Modifier) -> Unit = { headingModifier ->
            Row(
                modifier = headingModifier
                    .fillMaxWidth()
                    .padding(top = if (compactLandscape) 0.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (expandedLayout) IconButton(onClick = onCollapse, modifier = Modifier.testTag("player-collapse")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "收起播放页", tint = FullPlayerTextMuted)
                }
                // 只有换曲才滚动标题；进度、循环模式、封面回填不重启动效。
                val topTitleAlpha = if (posterMode) awayFromCover else 1f
                AnimatedContent(
                    targetState = track,
                    contentKey = { it?.uid },
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds()
                        .graphicsLayer { alpha = topTitleAlpha }
                        .then(if (posterMode && awayFromCover < 0.5f) Modifier.clearAndSetSemantics {} else Modifier),
                    transitionSpec = {
                        playerHeadingTransition(eInk)
                    },
                    label = "playerHeading",
                ) { headingTrack ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = headingTrack?.title ?: "未在播放",
                            fontSize = if (compactLandscapeNormal) CompactLandscapeTitleSize else 19.sp,
                            lineHeight = if (compactLandscapeNormal) 25.sp else 24.sp,
                            fontWeight = if (compactLandscapeNormal) FontWeight.Bold else FontWeight.SemiBold,
                            color = FullPlayerTextPrimary,
                            maxLines = if (compactLandscapeNormal) 2 else 1,
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
                    modifier = if (compactLandscapeNormal) Modifier else Modifier.offset(x = 13.dp),
                ) {
                    Icon(
                        Icons.Outlined.Sensors,
                        contentDescription = "播放设备",
                        tint = FullPlayerTextMuted.copy(alpha = if (compactLandscapeNormal) 0.68f else 1f),
                        modifier = Modifier.size(if (compactLandscapeNormal) 20.dp else 22.dp),
                    )
                }
            }

        }
        var headingHeightPx by remember { mutableIntStateOf(0) }
        var transportHeightPx by remember { mutableIntStateOf(0) }
        val portraitCoverSpace = if (twoPanes) null else {
            val hideStatusBar by MeloraSettings.hideStatusBar.collectAsStateWithLifecycle()
            val withNavigation = permanentInsets.union(WindowInsets.navigationBarsIgnoringVisibility)
            val normalInsets = (if (hideStatusBar) withNavigation else withNavigation.union(WindowInsets.statusBarsIgnoringVisibility))
                .asPaddingValues()
            val chromeHeight = with(LocalDensity.current) { (headingHeightPx + transportHeightPx).toDp() }
            PortraitCoverSpace(
                normal = (maxHeight - normalInsets.calculateTopPadding() - normalInsets.calculateBottomPadding() - chromeHeight).coerceAtLeast(0.dp),
                immersive = (maxHeight - permanentInsets.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp),
            )
        }
        val centered = nowPlayingArtworkShape(coverStyle) == NowPlayingArtworkShape.Circle
        val roundness = animateFloatAsState(if (centered) 1f else 0f,
            if (motionEnabled) PlayerCoverMorphSpec else snap(), label = "coverLayout")
        var controlsVisible by remember(immersive) { mutableStateOf(false) }
        var controlInteraction by remember { mutableIntStateOf(0) }
        LaunchedEffect(immersive, isVisible, coverPageVisible, controlsVisible, controlInteraction, track?.uid) {
            if (!immersive || !isVisible || !coverPageVisible) controlsVisible = false
            else if (controlsVisible) { delay(3_000); controlsVisible = false }
        }
        val interact = { controlsVisible = true; controlInteraction++ }
        val tapArtwork: () -> Unit = { if (immersive) interact() }
        val artworkActions = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onLongClickLabel = "选择封面类型", onLongClick = { showCoverPicker = true }, onClick = tapArtwork,
        )
        val artwork: @Composable BoxScope.() -> Unit = {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = artworkAlpha()
                rotationX = -2f * immersion.value
                rotationY = 3f * immersion.value
                rotationZ = -0.5f * immersion.value
                cameraDistance = 16f * density
            }, contentAlignment = Alignment.Center) {
                ImmersiveCoverProgress(lyricPosition, state.durationMs, immersion, roundness = roundness,
                    modifier = Modifier.matchParentSize())
                NowPlayingArtwork(
                    style = coverStyle,
                    modifier = Modifier.fillMaxSize().padding(6.dp * t)
                        .testTag("player-artwork")
                        .semantics { contentDescription = "${track?.title.orEmpty()}，${track?.artist.orEmpty()}" }
                        .onGloballyPositioned(onArtworkPositioned)
                        .graphicsLayer {
                            // 黑胶外圈透明且自带径向投影，不叠加 HWUI 阴影；其余样式用透明度淡出阴影并维持足够海拔，避免 Skia 低海拔圆形阴影退化为八边形切角。
                            val imageAlpha = if (playerArtwork.displayed != null) 1f else playerArtwork.incoming?.opacity?.value ?: 0f
                            val shadowAlpha = if (coverStyle == PlayerCoverStyle.Vinyl || MeloraAppearance.isEInk) 0f else immersion.value.coerceIn(0f, 1f) * imageAlpha
                            shadowElevation = if (shadowAlpha > 0.005f) 14.dp.toPx() * (0.35f + 0.65f * shadowAlpha) else 0f
                            ambientShadowColor = Color.Black.copy(alpha = shadowAlpha)
                            spotShadowColor = Color.Black.copy(alpha = shadowAlpha)
                            shape = if (roundness.value >= 0.999f) CircleShape
                                else RoundedCornerShape((18f * (1f - roundness.value) + size.minDimension / (2f * density) * roundness.value).dp)
                        }
                        .then(
                            if (compactLandscape && !immersive && coverStyle == PlayerCoverStyle.Default) {
                                Modifier.border(0.6.dp, FullPlayerTextPrimary.copy(alpha = 0.08f * (1f - t)), RoundedCornerShape(16.dp))
                            } else if (MeloraAppearance.isEInk && coverStyle != PlayerCoverStyle.Vinyl) {
                                Modifier.border(1.dp, Color.Black, if (centered) CircleShape else RoundedCornerShape(18.dp))
                            } else {
                                Modifier
                            },
                        )
                        .then(if (twoPanes) Modifier.semantics {
                            onClick { tapArtwork(); true }
                            onLongClick("选择封面类型") { showCoverPicker = true; true }
                        } else artworkActions),
                    cornerRadius = if (compactLandscape && !immersive) 16 else 18,
                    artwork = playerArtwork,
                    rotationDegrees = artworkRotation, motionEnabled = motionEnabled,
                    tonearmAngle = tonearmAngle,
                    recordChange = recordChange,
                )
            }
            val eInk = MeloraAppearance.isEInk
            AnimatedVisibility(
                visible = controlsVisible && immersive && isVisible && coverPageVisible,
                enter = if (eInk) EnterTransition.None else fadeIn(tween(120)),
                exit = if (eInk) ExitTransition.None else fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.Center).wrapContentWidth(unbounded = true),
            ) {
                ImmersivePlaybackControls(
                    playing = state.playing,
                    onPrevious = { interact(); PlaybackController.previous() },
                    onToggle = { interact(); PlaybackController.toggle() },
                    onNext = { interact(); PlaybackController.next() },
                    onExit = { onImmersiveChange(false) },
                )
            }
        }
        // 横屏分页承接手势，封面在分页外持续绘制，切换布局时保留已解码的图片和黑胶状态。
        val artworkAnchor = Modifier.onGloballyPositioned { anchors.update(it, immersive) }
            .clearAndSetSemantics {}.then(artworkActions)
        val currentCover by rememberUpdatedState<@Composable () -> Unit> {
            PlayerCoverPage(
                state = state, immersive = immersive, immersion = immersion,
                track = track, lyrics = lyricLines, compact = compactLandscape, lyricsInCover = !expandedLayout,
                largePortrait = largePortrait, portraitSpace = portraitCoverSpace,
                miniLyricsEnabled = miniLyricsEnabled, wordLiftEnabled = lyricsConfig.isWordLiftEnabled,
                posterMode = posterMode, roundness = roundness, centered = centered, twoPanes = twoPanes,
                artwork = { if (twoPanes) Spacer(Modifier.fillMaxSize().then(artworkAnchor)) else artwork() },
                position = lyricPosition, frame = lyricFrame, motionEnabled = motionEnabled,
                onNavigateToLyrics = { scope.launch { coverPagerState.animateScrollToPage(pages.indexOf(2), animationSpec = PlayerPageSnapSpec) } },
            )
        }
        val cover = remember { movableContentOf { currentCover() } }
        val drawPage by rememberUpdatedState<@Composable (Int) -> Unit> { pageIndex ->
            val selfInsetPage = (pageIndex == 2 && stageLyricsMode) || (pageIndex == 1 && expandedLayout && immersive)
            val pageInsetsModifier = if (landscapeImmersivePager && !selfInsetPage) {
                Modifier.windowInsetsPadding(playerInsets)
            } else Modifier
            // 先按整页边界裁切，再留正文内距：不串页，也不在屏幕内侧提前硬切。
            Box(
                Modifier.fillMaxSize().testTag("player-page-$pageIndex").clipToBounds()
                    .then(if (landscapeImmersivePager && pageIndex == 1) Modifier.onPlaced { anchors.page = it } else Modifier),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier.fillMaxHeight().then(portraitStageModifier).fillMaxWidth()
                        .then(pageInsetsModifier)
                        .padding(if (selfInsetPage) PaddingValues(0.dp) else pagePadding),
                ) {
                    when (pageIndex) {
                        0 -> AudioInfoPage(
                            immersive = immersive,
                            compactSpacing = compactLandscapeNormal,
                            largePortrait = largePortrait,
                            track = track,
                            audioSpec = state.audioSpec,
                            sourceLabel = playbackSourceLabel(state, autoSwitch),
                            onOpenAlbum = { album ->
                                onImmersiveChange(false)
                                val raw = track?.raw
                                val isBookChapter = raw?.optBoolean("isBookChapter") == true
                                val bookAlbumId = raw?.optString("albumId")
                                    .orEmpty()
                                    .takeIf { isBookChapter && it.isNotBlank() }
                                val onlineSource = track?.source
                                    ?.takeIf { it.isNotBlank() && it != "local" } ?: "kw"
                                val collection = SongsCollection(
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
                                pendingBookCollection = null
                                if (isBookChapter) {
                                    playerAlbumDestination(collection, emptyList())?.let { openedDetail = it }
                                        ?: run { pendingBookCollection = collection }
                                } else {
                                    openedDetail = FullPlayerDetailTarget.Collection(collection)
                                }
                            },
                            onOpenArtist = { artist ->
                                onImmersiveChange(false)
                                pendingBookCollection = null
                                openedDetail = FullPlayerDetailTarget.Collection(artistCollection(track, artist))
                            },
                        )
                        1 -> if (expandedLayout && immersive) {
                            val hasLyrics = lyricLines.isNotEmpty()
                            val immersiveLines = remember(track?.uid, track?.title, track?.artist, lyricLines) {
                                fullPlayerLyricsOrFallback(track, lyricLines)
                            }
                            Row(
                                Modifier.fillMaxSize().windowInsetsPadding(playerInsets)
                                    .padding(horizontal = ExpandedPlayerPanePadding, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(ExpandedPlayerPaneGap),
                            ) {
                                Box(Modifier.weight(ExpandedPlayerArtworkWeight).fillMaxHeight()) { cover() }
                                Box(Modifier.weight(1f - ExpandedPlayerArtworkWeight).fillMaxHeight()) {
                                    key(track?.uid, lyricContentKey(immersiveLines)) {
                                        LyricsViewport(
                                            lines = immersiveLines,
                                            position = lyricPosition,
                                            config = lyricsConfig,
                                            frameState = lyricFrame,
                                            motionEnabled = motionEnabled,
                                            immersive = true,
                                            modifier = Modifier.fillMaxSize().testTag("player-full-lyrics")
                                                .graphicsLayer { alpha = immersion.value }.lyricViewportFade(),
                                            onLineClick = if (hasLyrics) seekToLyric else null,
                                            onPlayLine = if (hasLyrics) playFromLyric else null,
                                            onTogglePlayback = if (hasLyrics) PlaybackController::toggle else null,
                                        )
                                    }
                                }
                            }
                        } else {
                            cover()
                        }
                        2 -> LyricsPage(
                            toolsPadding = when {
                                expandedLayout -> PaddingValues(top = 6.dp, bottom = 10.dp)
                                compactLandscapeNormal -> PaddingValues(top = 4.dp, bottom = 6.dp)
                                else -> PaddingValues(0.dp)
                            },
                            track = track,
                            durationMs = state.durationMs,
                            lyrics = lyricLines,
                            position = lyricPosition,
                            frame = lyricFrame,
                            motionEnabled = motionEnabled,
                            config = lyricsConfig,
                            immersive = immersive, immersion = immersion,
                            onConfigChange = MeloraSettings::updatePlayerLyrics,
                            onOpenSource = { showLyricsSource = true },
                            stageMode = stageLyricsMode,
                            playing = state.playing,
                            isVisible = isVisible && currentPage == 2,
                            onImmersiveChange = onImmersiveChange,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            largeDisplay = largePortrait || expandedLayout,
                            compactLandscape = compactLandscapeNormal,
                            lyricsTestTag = if (expandedLayout && immersive) "player-stage-lyrics" else "player-full-lyrics",
                        )
                    }
                }
            }
        }
        // 同一分页实例始终使用自己的页数和索引映射；页面正文按业务 ID 更新。
        val pane = remember(coverPagerState) {
            movableContentOf<Modifier> { paneModifier ->
                HorizontalPager(
                    state = coverPagerState,
                    flingBehavior = PagerDefaults.flingBehavior(coverPagerState, snapAnimationSpec = PlayerPageSnapSpec),
                    modifier = paneModifier.testTag("player-pages"),
                ) { index -> drawPage(pages[index]) }
            }
        }
        val transport: @Composable () -> Unit = {
            PlayerSeekBar(
                trackUid = track?.uid,
                position = sampledPosition,
                durationMs = state.durationMs,
                onSeek = onSeek,
                activeColor = FullPlayerProgressActive,
                inactiveColor = FullPlayerProgressInactive,
                clockColor = FullPlayerTextMuted.copy(alpha = if (compactLandscapeNormal) 0.78f else 1f),
                clockSize = if (compactLandscapeNormal) 11.sp else 12.sp,
            )

            // 紧凑控制区：进度条与播放控制紧密贴合
            Spacer(Modifier.height(if (compactLandscapeNormal) 2.dp else 4.dp))

            // 主播放控制：上一首 / 纯白大号播放键（无底圆） / 下一首
            FullPlayerPrimaryControls(
                playing = state.playing,
                onPrevious = onPrevious,
                onToggle = { PlaybackController.toggle() },
                onNext = onNext,
                compact = compactLandscapeNormal,
            )

            Spacer(Modifier.height(if (compactLandscapeNormal) 2.dp else 4.dp))

            // 底部 5 个次要控制钮（循环模式、定时设置、音效、队列、更多操作）
            FullPlayerSecondaryControls(
                mode = state.mode,
                effectActive = audioEffectState.active,
                onCycleMode = { PlaybackController.cycleMode() },
                onShowTimer = { showTimerSettings = true },
                onOpenEffects = { showAudioEffects = true },
                onOpenQueue = if (compactQueueOpen) onCloseQueue else onOpenQueue,
                queueOpen = compactQueueOpen,
                onShowMore = { showMoreActions = true },
                compact = compactLandscapeNormal,
            )
        }
        // 手机横屏的队列和阅读内容共用右栏，歌词和信息优先消费自己的纵向滚动。
        val viewConfiguration = LocalViewConfiguration.current
        val verticalViewConfiguration = rememberPlayerVerticalViewConfiguration()
        val queueGestureEnabled by rememberUpdatedState(
            LocalPageActive.current && !immersive && !expandedLayout && detailTarget == null &&
                (!compactLandscape || queuePagerState.currentPage == 1 || queuePagerState.isScrollInProgress),
        )
        val queueFling = PagerDefaults.flingBehavior(queuePagerState, snapAnimationSpec = PlayerPageSnapSpec)
        val defaultQueueScroll = PagerDefaults.pageNestedScrollConnection(queuePagerState, Orientation.Vertical)
        val queueNestedScroll = remember(defaultQueueScroll, queueFling) {
            object : NestedScrollConnection by defaultQueueScroll {
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!queueGestureEnabled || queuePagerState.currentPageOffsetFraction == 0f) return Velocity.Zero
                    // 页面已离开端点：释放速度属于翻页，不能先让内层列表惯性滚动再等余速。
                    // 与直接拖动共用原生吸附；新手势/返回仍可通过Pager的滚动互斥取消它。
                    queuePagerState.scroll {
                        with(queueFling) {
                            performFling(-available.y) { remaining ->
                                val pageSize = queuePagerState.layoutInfo.let { it.pageSize + it.pageSpacing }
                                if (pageSize > 0) with(queuePagerState) {
                                    updateTargetPage(queuePagerState.currentPage + (remaining / pageSize).roundToInt())
                                }
                            }
                        }
                        // 在同一滚动事务内提交终点，避免小数余量让下一次Back误判仍在队列。
                        with(queuePagerState) { updateCurrentPage(queuePagerState.currentPage) }
                    }
                    return Velocity(0f, available.y)
                }
            }
        }
        val queuePaneContent: @Composable (Modifier, @Composable () -> Unit) -> Unit = { pagerModifier, playerContent ->
            CompositionLocalProvider(LocalViewConfiguration provides verticalViewConfiguration) {
                VerticalPager(
                    state = queuePagerState,
                    pageNestedScrollConnection = queueNestedScroll,
                    flingBehavior = queueFling,
                    userScrollEnabled = queueGestureEnabled,
                    modifier = pagerModifier,
                ) { page ->
                    CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                        if (page == 0) {
                            playerContent()
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                QueuePageContent(
                                    state = state,
                                    onClose = onCloseQueue,
                                    isVisible = !isCollapsed && (queuePagerState.currentPage == 1 || queuePagerState.targetPage == 1),
                                    motionEnabled = queueMotionEnabled,
                                    horizontalPadding = if (twoPanes) 0.dp else 18.dp,
                                    modifier = when {
                                        compactLandscape -> Modifier
                                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                                            .padding(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 8.dp)
                                        expandedLayout -> Modifier.padding(controlsPadding)
                                        largePortrait -> Modifier.then(portraitStageModifier).fillMaxSize()
                                        else -> Modifier
                                    },
                                    besideTransport = compactLandscape,
                                )
                            }
                        }
                    }
                }
            }
        }
        val currentQueuePane by rememberUpdatedState(queuePaneContent)
        val playerAndQueue = remember {
            movableContentOf<Modifier, @Composable () -> Unit> { paneModifier, content -> currentQueuePane(paneModifier, content) }
        }
        val readingScroll = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
                override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
            }
        }
        if (expandedNormal) {
            Row(
                Modifier.fillMaxSize().windowInsetsPadding(playerInsets).padding(top = playerTopInset)
                    .padding(horizontal = ExpandedPlayerPanePadding, vertical = 12.dp).testTag("player-expanded-layout"),
                horizontalArrangement = Arrangement.spacedBy(ExpandedPlayerPaneGap),
            ) {
                Box(
                    Modifier.weight(ExpandedPlayerArtworkWeight).fillMaxHeight().testTag("player-artwork-pane"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.widthIn(max = ExpandedPlayerArtworkMaxSide).fillMaxSize()) {
                        Box(Modifier.graphicsLayer { alpha = 1f - immersion.value }.testTag("player-heading")) { heading(Modifier) }
                        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) { cover() }
                        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - immersion.value }.testTag("player-transport")) { transport() }
                    }
                }
                playerAndQueue(Modifier.weight(1f - ExpandedPlayerArtworkWeight).fillMaxHeight().nestedScroll(readingScroll).testTag("player-reading-pane")) {
                    Column(Modifier.fillMaxSize()) {
                        PlayerReadingTabs(
                            pages = pages,
                            selectedPage = currentPage,
                            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - immersion.value }.padding(horizontal = 20.dp, vertical = 8.dp),
                            tabTagPrefix = "player-page-tab-",
                            compact = false,
                            onSelect = ::selectReadingTab,
                        )
                        pane(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        } else if (compactLandscapeNormal) {
            val compactTopInset = compactLandscapeTopInsetDp(playerTopInset.value, maxHeight.value).dp
            Row(
                Modifier.fillMaxSize().padding(top = compactTopInset)
                    .testTag("player-phone-landscape"),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    Modifier.weight(0.43f).fillMaxHeight().clipToBounds()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))
                        .testTag("player-compact-controls")
                        .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)
                        .onGloballyPositioned { viewport ->
                            anchors.normalViewport = anchors.root?.localBoundingBoxOf(viewport)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CompactLandscapeControls(
                        scrollState = compactControlsScroll,
                        heading = { heading(Modifier.testTag("player-heading")) },
                        transport = { Column(Modifier.fillMaxWidth().testTag("player-transport")) { transport() } },
                    ) {
                        Spacer(Modifier.fillMaxSize().then(artworkAnchor))
                    }
                }
                playerAndQueue(Modifier.weight(0.57f).fillMaxHeight().nestedScroll(readingScroll).testTag("player-compact-content")) {
                    Column(
                        Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                            .padding(top = 12.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        PlayerReadingTabs(
                            pages = pages,
                            selectedPage = currentPage,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
                            tabTagPrefix = "player-compact-tab-",
                            compact = true,
                            onSelect = ::selectReadingTab,
                        )
                        pane(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        } else if (landscapeImmersivePager) {
            Box(
                Modifier.fillMaxSize()
                    .testTag(if (expandedLayout) "player-expanded-layout" else "player-phone-landscape"),
            ) {
                pane(Modifier.fillMaxSize())
            }
        } else {
            val bottomInset by animateDpAsState(
                WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                if (motionEnabled) spring(dampingRatio = 1f, stiffness = 220f) else snap(), label = "portraitBottomInset",
            )
            val portraitInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).union(WindowInsets(bottom = bottomInset))
            playerAndQueue(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(portraitInsets).padding(top = playerTopInset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.then(portraitStageModifier).fillMaxWidth().immersionChrome(immersion)
                        .onSizeChanged { headingHeightPx = it.height }.testTag("player-heading")) {
                        heading(Modifier.padding(controlsPadding))
                        Spacer(Modifier.height(16.dp))
                    }
                    pane(Modifier.fillMaxWidth().weight(1f))
                    Column(Modifier.then(portraitStageModifier).fillMaxWidth().immersionChrome(immersion)
                        .onSizeChanged { transportHeightPx = it.height }.testTag("player-transport")) {
                        Spacer(Modifier.height(2.dp))
                        Column(Modifier.padding(controlsPadding)) { transport() }
                    }
                }
            }
        }
        if (twoPanes) LandscapeArtworkLayer(
            anchors, immersion, if (landscapeImmersivePager) coverPagerState else null, compactLandscape, artwork,
        )

    }

    if (showCoverPicker) {
        val playerThemeMode by MeloraSettings.playerThemeMode.collectAsStateWithLifecycle()
        PlayerCoverPicker(
            selected = coverStyle, artwork = playerArtwork, immersive = immersive, motionEnabled = motionEnabled,
            themeMode = playerThemeMode, onThemeModeChange = MeloraSettings::updatePlayerThemeMode,
            onSelect = { style -> MeloraSettings.updatePlayerCoverStyle(style); showCoverPicker = false },
            onToggleImmersive = { showCoverPicker = false; onImmersiveChange(!immersive) },
            onDismiss = { showCoverPicker = false },
        )
    }

    if (showLyricsSource && track != null) {
        key(track.uid) { LyricsSourceSheet(track, onDismiss = { showLyricsSource = false }) }
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
            playing = state.playing,
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
    immersive: Boolean,
    track: UiTrack?,
    audioSpec: AudioSpecification?,
    sourceLabel: String,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    compactSpacing: Boolean = false,
    largePortrait: Boolean = false,
) {
    val platformName = sourceAliasDisplay(track?.source.orEmpty(), platformLabel(track?.source))
    // 只显示已选中音轨的实测徽标，不借目录最高档或请求档冒充实际音质。
    if (immersive) {
        ImmersiveTrackNotes(track, platformName, audioSpec, sourceLabel, onOpenAlbum, onOpenArtist)
        return
    }
    val viewConfiguration = LocalViewConfiguration.current
    val verticalViewConfiguration = rememberPlayerVerticalViewConfiguration()
    CompositionLocalProvider(LocalViewConfiguration provides verticalViewConfiguration) {
        if (largePortrait) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val minViewportHeight = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = minViewportHeight)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                        AudioInfoCard(
                            platformName = platformName,
                            audioSpec = audioSpec,
                            sourceLabel = sourceLabel,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AlbumInfoCard(
                                track = track,
                                onOpenAlbum = onOpenAlbum,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            ArtistInfoCard(
                                track = track,
                                onOpenArtist = onOpenArtist,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactSpacing) 10.dp else 14.dp),
            ) {
                CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                    AudioInfoCard(
                        platformName = platformName,
                        audioSpec = audioSpec,
                        sourceLabel = sourceLabel,
                    )
                    AlbumInfoCard(track = track, onOpenAlbum = onOpenAlbum)
                    ArtistInfoCard(track = track, onOpenArtist = onOpenArtist)
                }
            }
        }
    }
}

/** 沉浸 Page 0 是唱片内页，而不是普通播放器的三张工具卡。解析结果与跳转仍由同一入口提供。 */
@Composable
internal fun ImmersiveTrackNotes(
    track: UiTrack?,
    platformName: String,
    audioSpec: AudioSpecification?,
    sourceLabel: String,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("immersive-track-notes")) {
        val viewportHeight = maxHeight
        val twoColumn = maxWidth >= 600.dp && (maxHeight < 500.dp || maxWidth >= 840.dp)
        val artist = track?.artist?.takeIf { it.isNotBlank() }
        val album = track?.album?.takeIf { it.isNotBlank() }
        val identityBlock: @Composable () -> Unit = {
            Text("唱片内页", fontSize = 11.sp, letterSpacing = 3.sp,
                color = FullPlayerTextMuted.copy(alpha = 0.60f))
            Spacer(Modifier.height(if (twoColumn) 16.dp else 24.dp))
            Text(track?.title?.takeIf { it.isNotBlank() } ?: "未选择歌曲",
                fontSize = if (twoColumn) 30.sp else 32.sp,
                lineHeight = if (twoColumn) 38.sp else 40.sp,
                fontWeight = FontWeight.Bold,
                color = FullPlayerTextPrimary)
            Row(Modifier.fillMaxWidth().testTag("immersive-artist")
                .clickable(enabled = artist != null, role = Role.Button, onClickLabel = "查看歌手歌曲") {
                    artist?.let(onOpenArtist)
                }.padding(vertical = if (twoColumn) 10.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(artist ?: "未知艺术家", fontSize = 18.sp, lineHeight = 26.sp,
                    color = FullPlayerTextMuted, modifier = Modifier.weight(1f))
                if (artist != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                    tint = FullPlayerTextMuted.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
            if (twoColumn) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.width(32.dp).height(1.dp).background(FullPlayerTextPrimary.copy(alpha = 0.25f)))
            }
        }
        val metadataBlock: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().testTag("immersive-album")
                .clickable(enabled = album != null, role = Role.Button, onClickLabel = "查看专辑歌曲") {
                    album?.let(onOpenAlbum)
                }.padding(vertical = 8.dp)) {
                Text("收录专辑", fontSize = 11.sp, color = FullPlayerTextMuted.copy(alpha = 0.65f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(album ?: "未知专辑", fontSize = 20.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Medium, color = FullPlayerTextPrimary, modifier = Modifier.weight(1f))
                    if (album != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                        tint = FullPlayerTextMuted.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(if (twoColumn) 20.dp else 32.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("实际音质", fontSize = 11.sp, color = FullPlayerTextMuted.copy(alpha = 0.65f))
                    Text(
                        audioSpec?.qualityBadge?.label ?: "未识别",
                        fontSize = 16.sp,
                        color = FullPlayerTextPrimary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("音乐平台", fontSize = 11.sp, color = FullPlayerTextMuted.copy(alpha = 0.65f))
                    Text(platformName, fontSize = 16.sp, color = FullPlayerTextPrimary,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            AudioSpecificationLine(audioSpec, Modifier.padding(top = 12.dp, bottom = 6.dp))
            Text(sourceLabel, fontSize = 11.sp, lineHeight = 18.sp,
                color = FullPlayerTextMuted.copy(alpha = 0.65f))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            if (twoColumn) {
                Row(
                    Modifier.widthIn(max = 880.dp).fillMaxWidth().heightIn(min = viewportHeight).padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(0.46f)) { identityBlock() }
                    Box(Modifier.width(1.dp).height(112.dp).background(FullPlayerTextPrimary.copy(alpha = 0.12f)))
                    Column(Modifier.weight(0.54f)) { metadataBlock() }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = viewportHeight).padding(horizontal = 12.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    identityBlock()
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.width(32.dp).height(1.dp).background(FullPlayerTextPrimary.copy(alpha = 0.25f)))
                    Spacer(Modifier.height(24.dp))
                    metadataBlock()
                }
            }
        }
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
    // 播放页听书专辑入口使用真实专辑 ID 导航到公共听书目录。
    val bookAlbumId: String? = null,
    // 听书专辑进入章节；听书创作者进入作品目录，不走音乐单曲搜索。
    val preferBook: Boolean = false,
    val artwork: String? = null,
    val description: String = "",
    val playCountLabel: String = "",
) {
    val isBookAuthor: Boolean get() = preferBook && albumName == null && bookAlbumId == null
}

internal sealed interface FullPlayerDetailTarget {
    data class Collection(val value: SongsCollection) : FullPlayerDetailTarget
    data class Playlist(val value: OnlinePlaylist) : FullPlayerDetailTarget
}

/** 将全屏页的听书专辑入口转换成共享目录需要的真实专辑实体。 */
internal fun playerAlbumDestination(
    collection: SongsCollection,
    searchResults: List<OnlinePlaylist>,
): FullPlayerDetailTarget? {
    if (collection.isBookAuthor) return null
    if (!collection.preferBook && collection.bookAlbumId == null) {
        return FullPlayerDetailTarget.Collection(collection)
    }

    val title = (collection.albumName ?: collection.keyword).trim()
    if (title.isBlank()) return null
    fun canonicalId(source: String, value: String): String? = canonicalBookId(source, value)
        ?.takeUnless { it.equals("null", ignoreCase = true) }

    val knownId = collection.bookAlbumId?.let { canonicalId(collection.source, it) }
    val matched = if (knownId == null) {
        searchResults.firstOrNull { it.isBookAlbum && it.name.trim().equals(title, ignoreCase = true) }
            ?: searchResults.firstOrNull {
                it.isBookAlbum && (it.name.contains(title, ignoreCase = true) || title.contains(it.name, ignoreCase = true))
            }
    } else null
    val id = knownId ?: matched?.let { canonicalId(it.source, it.id) } ?: return null
    val source = if (knownId != null) collection.source else matched?.source?.ifBlank { "kw" } ?: "kw"

    return FullPlayerDetailTarget.Playlist(
        OnlinePlaylist(JSONObject()
            .put("id", "book_album_$id")
            .put("name", matched?.name?.takeIf(String::isNotBlank) ?: collection.title)
            .put("source", source.ifBlank { "kw" })
            .put("kind", "book")
            .put("img", collection.artwork ?: matched?.img.orEmpty())
            .put("author", collection.artistName ?: matched?.author.orEmpty())
            .put("description", collection.description.ifBlank { matched?.description.orEmpty() })
            .put("play_count", collection.playCountLabel.ifBlank { matched?.playCount.orEmpty() })),
    )
}

internal fun artistCollection(track: UiTrack?, artist: String): SongsCollection {
    val book = track?.raw?.optBoolean("isBookChapter") == true
    return SongsCollection(
        title = artist,
        subtitle = if (book) "作者 / 主播 · 听书作品" else "歌手 · 全部歌曲",
        keyword = artist,
        source = track?.source?.takeIf { it.isNotBlank() && it != "local" } ?: "kw",
        artistName = artist,
        preferBook = book,
    )
}

/**
 * 专辑/歌手全部歌曲页：单平台搜索 + 过滤，按页懒加载。
 * 打开只请求第 1 页；滚动接近底部才补下一页；过滤后整页无匹配时最多再补 2 页，避免请求风暴。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun SongsCollectionPage(
    collection: SongsCollection,
    onBack: () -> Unit,
    standalone: Boolean = false,
) {
    if (collection.isBookAuthor) {
        BookAuthorPage(collection.artistName ?: collection.title, onBack, standalone = standalone)
        return
    }
    val context = LocalContext.current
    val listState = key(
        collection.source,
        collection.keyword,
        collection.title,
        collection.albumName,
        collection.artistName,
    ) { rememberLazyListState() }
    val scrollToTop = rememberFastScrollToTop(listState)
    val selection = remember(collection) { SongSelectionState() }
    BackHandler { if (selection.active) selection.finish() else onBack() }
    val controlBarHazeState = rememberHazeState()
    val blurEnabled = componentHeadersBlurred()
    val bodyHazeModifier = if (blurEnabled) Modifier.hazeSource(controlBarHazeState) else Modifier
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
    val favoriteAlbums by UserLibrary.favoriteAlbums.collectAsStateWithLifecycle()
    val favoriteArtists by UserLibrary.favoriteArtists.collectAsStateWithLifecycle()
    val isArtist = collection.albumName == null
    val artistName = collection.artistName?.takeIf { it.isNotBlank() } ?: collection.title
    val artistKey = "${collection.source}_$artistName"
    var artwork by remember(collection) { mutableStateOf(collection.artwork) }
    var albumProfile by remember(collection) { mutableStateOf<AlbumProfile?>(null) }
    var artistProfile by remember(collection) { mutableStateOf<ArtistProfile?>(null) }
    val albumKey = collection.albumName
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
    LaunchedEffect(collection, isArtist, collection.albumName, artistName) {
        val album = collection.albumName
        if (!isArtist && !album.isNullOrBlank()) {
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

    // 播放来源容器：音乐专辑/歌手，登记到「我的 → 最近播放」并可回跳。
    fun playContainer(snapshot: List<OnlineSong>): UserLibrary.PlayContainer? {
        val album = collection.albumName?.trim().orEmpty()
        val img = artwork ?: if (isArtist) null else snapshot.firstOrNull()?.let { CoverLoader.cachedUrl(it) }
        return when {
            albumKey != null && album.isNotEmpty() -> UserLibrary.PlayContainer(
                kind = "album",
                id = "album_${collection.source}_$album",
                name = album,
                img = img,
                source = collection.source,
                queueId = "album.$albumKey",
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

    class PageRequest(val page: Int)
    var request by remember(collection) { mutableStateOf(PageRequest(1)) }

    fun load(targetPage: Int) {
        if (loadingMore) return
        loadingMore = true
        request = PageRequest(targetPage)
    }

    LaunchedEffect(collection, request) {
        val targetPage = request.page
        loadingMore = true
        loadMoreFailed = false
        if (songs.isEmpty()) loading = true
        try {
            error = null
            var current = targetPage
            var extra = 0
            runCatchingCancellable {
                val collected = mutableListOf<OnlineSong>()
                var more = true
                while (more && collected.isEmpty() && extra <= 2) {
                    val result = OnlineRepository.search(context, collection.source, collection.keyword, current, 30)
                    collected += result.entries.filter(::matches)
                    more = result.entries.size >= 30
                    if (collected.isEmpty() && more) {
                        current += 1
                        extra += 1
                    }
                }
                collected to more
            }
                .onSuccess { (list, more) ->
                    songs = if (targetPage > 1) (songs + list).distinctBy { it.uid } else list.distinctBy { it.uid }
                    page = current
                    hasMore = more
                }
                .onFailure {
                    if (songs.isEmpty()) error = it.message ?: "加载失败" else loadMoreFailed = true
                }
        } finally {
            loading = false
            loadingMore = false
        }
    }

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

    val isFavorite = when {
        isArtist -> favoriteArtists.any { it.key == artistKey }
        albumKey != null -> favoriteAlbums.any { it.key == albumKey }
        else -> false
    }
    val onFavorite: (() -> Unit)? = when {
        isArtist -> {
            {
                val artist = UserLibrary.FavoriteArtist(artistName, collection.source, artwork)
                submitLibraryWrite { UserLibrary.toggleFavoriteArtist(artist) }
            }
        }
        albumKey != null -> {
            {
                val album = UserLibrary.FavoriteAlbum(collection.albumName, collection.artistName.orEmpty(), collection.source, artwork)
                submitLibraryWrite { UserLibrary.toggleFavoriteAlbum(album) }
            }
        }
        else -> null
    }
    val header = remember(isArtist, artistProfile, albumProfile, artistName, songs) {
        if (isArtist) artistHeaderCopy(artistProfile, songs)
        else albumHeaderCopy(albumProfile, artistName, songs)
    }
    val showCollapsedTitle by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val playAll = {
        val snapshot = songs
        PlaybackController.playQueue(context, snapshot.toUiTracks(), 0, container = playContainer(snapshot))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        val twoPane = paneWidth >= 840.dp && paneHeight >= 480.dp
        val largeSinglePane = !twoPane && paneWidth >= 600.dp && paneHeight >= 480.dp
        val isLargeScreen = twoPane || largeSinglePane
        val selectionBarModifier = when {
            twoPane -> Modifier.widthIn(max = 1160.dp).padding(horizontal = 16.dp)
            largeSinglePane -> Modifier.widthIn(max = 720.dp)
            else -> Modifier
        }

        ChromeScaffold(
            modifier = Modifier.fillMaxSize(),
            contentSource = controlBarHazeState,
            expectedTopBarHeight = 64.dp,
            topBar = {
                SongSelectionTopBar(selection, songs, modifier = selectionBarModifier) {
                    val topBarContent: @Composable (Modifier, String, Color, FontWeight) -> Unit = { barModifier, titleText, titleTint, titleWeight ->
                        Row(
                            modifier = barModifier.height(64.dp),
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
                                    text = titleText,
                                    fontSize = if (twoPane) 15.sp else 17.sp,
                                    fontWeight = titleWeight,
                                    color = titleTint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    when {
                        twoPane -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            topBarContent(
                                Modifier.widthIn(max = 1160.dp).fillMaxWidth().padding(horizontal = 16.dp),
                                if (isArtist) "歌手" else "专辑",
                                MeloraAppearance.textSub,
                                FontWeight.Medium,
                            )
                        }
                        largeSinglePane -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            topBarContent(
                                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 4.dp),
                                if (showCollapsedTitle) collection.title else if (isArtist) "歌手" else "专辑",
                                if (showCollapsedTitle) MeloraAppearance.textMain else MeloraAppearance.textSub,
                                if (showCollapsedTitle) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                        else -> topBarContent(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            collection.title,
                            MeloraAppearance.textMain,
                            FontWeight.SemiBold,
                        )
                    }
                }
            },
            bottomBar = { SongBatchActionsBar(selection, songs) },
        ) {
            val topInset = LocalChromeTopInset.current
            val snapshot = songs
            val sourceUids = remember(snapshot) { snapshot.map(OnlineSong::uid) }
            fun LazyListScope.collectionTrackItems() {
                when {
                    loading -> item(key = "loading") { SkeletonSongList(modifier = bodyHazeModifier, rows = 7, rowSpacing = 2.dp) }
                    error != null -> item(key = "error") {
                        ErrorState(error!!, onRetry = { load(1) }, modifier = Modifier.fillParentMaxHeight(.65f).then(bodyHazeModifier))
                    }
                    snapshot.isEmpty() -> item(key = "empty") {
                        EmptyState("没有找到相关歌曲", Modifier.fillParentMaxHeight(.65f).then(bodyHazeModifier))
                    }
                    else -> {
                        itemsIndexed(snapshot, key = { index, song -> "${song.uid}:$index" }) { index, song ->
                            Box(Modifier.fillMaxWidth().then(bodyHazeModifier)) {
                                OnlineSongRow(
                                    song = song,
                                    showAlbum = isArtist,
                                    isFavorite = song.uid in favoriteUids,
                                    onMore = { moreSong = song },
                                    selectionMode = selection.active,
                                    selected = song.uid in selection.selectedUids,
                                    leadingIndex = if (isLargeScreen && !isArtist) index + 1 else null,
                                    highlightContainer = isLargeScreen,
                                    onClick = {
                                        if (selection.active) selection.toggle(song.uid)
                                        else PlaybackController.playFromCollection(
                                            context,
                                            selected = UiTrack.fromOnline(song),
                                            startIndex = index,
                                            container = playContainer(snapshot),
                                            sourceUids = sourceUids,
                                        ) { trackIndex -> UiTrack.fromOnline(snapshot[trackIndex]) }
                                    },
                                )
                            }
                        }
                        if (loadingMore || loadMoreFailed || (hasMore && autoBudget == 0)) {
                            item(key = "loadMore") {
                                val interactive = !loadingMore && hasMore
                                Box(Modifier.fillMaxWidth().then(bodyHazeModifier).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = when {
                                            loadMoreFailed -> "加载更多失败，点击重试"
                                            loadingMore -> "正在加载…"
                                            else -> "点击加载更多"
                                        },
                                        fontSize = 13.sp,
                                        color = if (interactive) MeloraAppearance.brand else MeloraAppearance.textMuted,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = interactive) { load(page + 1) }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (twoPane) {
                Box(Modifier.fillMaxSize().testTag("collection-two-pane"), contentAlignment = Alignment.TopCenter) {
                    Row(
                        Modifier.widthIn(max = 1160.dp).fillMaxSize().padding(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Column(
                            Modifier
                                .width(300.dp)
                                .fillMaxHeight()
                                .then(bodyHazeModifier)
                                .verticalScroll(rememberScrollState())
                                .padding(top = topInset + 16.dp, bottom = 28.dp)
                                .testTag("collection-showcase-pane"),
                        ) {
                            CollectionHeroArtwork(
                                image = artwork,
                                artist = isArtist,
                                size = 196.dp,
                                cornerRadius = 18,
                            )
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = collection.title,
                                fontSize = 24.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = MeloraAppearance.textMain,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = header.primary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = MeloraAppearance.textSub,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (header.secondary.isNotBlank()) {
                                Text(
                                    text = header.secondary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MeloraAppearance.textMuted,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            Spacer(Modifier.height(22.dp))
                            CollectionShowcaseActions(
                                canPlay = songs.isNotEmpty(),
                                favorite = isFavorite,
                                favoriteEnabled = onFavorite != null,
                                selectionActive = selection.active,
                                loading = loading,
                                onPlay = playAll,
                                onFavorite = onFavorite,
                                onSelect = selection::start,
                            )
                        }
                        Box(
                            Modifier
                                .padding(top = topInset + 16.dp, bottom = 28.dp)
                                .width(0.6.dp)
                                .fillMaxHeight()
                                .background(MeloraAppearance.divider.copy(alpha = 0.65f)),
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("collection-tracklist-pane"),
                            contentPadding = chromeContentPadding(PaddingValues(top = 10.dp, bottom = 28.dp)),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (!loading && error == null && songs.isNotEmpty()) {
                                item(key = "tracklistHeader") {
                                    Row(
                                        Modifier.fillMaxWidth().then(bodyHazeModifier).padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (isArtist) "热门作品" else "专辑曲目",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MeloraAppearance.textSub,
                                        )
                                        Text(
                                            text = "${songs.size}${if (hasMore) "+" else ""} 首",
                                            fontSize = 12.sp,
                                            color = MeloraAppearance.textMuted,
                                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                                        )
                                    }
                                }
                            }
                            collectionTrackItems()
                        }
                    }
                }
            } else {
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
                val stageModifier = if (largeSinglePane) {
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("collection-large-single-pane")
                } else {
                    Modifier.fillMaxWidth()
                }
                val controls: @Composable (Boolean) -> Unit = { stuck ->
                    ChromeFloatingBar(
                        state = controlBarHazeState, topOffset = topInset, height = PlaylistControlsHeight,
                        pinned = stuck, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth().background(chromeHeaderColor())) {
                            CollectionActionsRow(
                                canPlay = songs.isNotEmpty(), favorite = isFavorite,
                                onPlay = playAll, onFavorite = onFavorite, onSelect = selection::start,
                            )
                            Box(Modifier.fillMaxWidth().height(.6.dp).alpha(dividerAlpha).background(MeloraAppearance.divider))
                        }
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.then(stageModifier).fillMaxHeight(),
                        contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!selection.active) {
                            item(key = "detailHeader") {
                                if (largeSinglePane) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().then(bodyHazeModifier)
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CollectionHeroArtwork(
                                            image = artwork,
                                            artist = isArtist,
                                            size = 120.dp,
                                            cornerRadius = 16,
                                        )
                                        Spacer(Modifier.width(20.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = collection.title,
                                                fontSize = 22.sp,
                                                lineHeight = 28.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MeloraAppearance.textMain,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = header.primary,
                                                fontSize = 13.5.sp,
                                                lineHeight = 19.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MeloraAppearance.textSub,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (header.secondary.isNotBlank()) {
                                                Text(
                                                    text = header.secondary,
                                                    fontSize = 12.5.sp,
                                                    lineHeight = 17.sp,
                                                    color = MeloraAppearance.textMuted,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    CollectionInfoHeader(
                                        image = artwork, artist = isArtist,
                                        primary = header.primary,
                                        secondary = header.secondary,
                                        primaryMaxLines = header.primaryMaxLines,
                                        modifier = bodyHazeModifier,
                                    )
                                }
                            }
                            item(key = "controlBar", contentType = "controls") {
                                Box(Modifier.fillMaxWidth().height(PlaylistControlsHeight)) { if (!pinned) controls(false) }
                            }
                        }
                        collectionTrackItems()
                    }
                    if (pinned && !selection.active) {
                        Box(Modifier.then(stageModifier).align(Alignment.TopCenter).padding(top = topInset)) { controls(true) }
                    }
                }
            }
        }
    }
    moreSong?.let { song -> SongMoreSheet(song) { moreSong = null } }
}

@Composable
private fun PlayerReadingTabs(
    pages: List<Int>,
    selectedPage: Int,
    modifier: Modifier,
    tabTagPrefix: String,
    compact: Boolean,
    onSelect: (Int) -> Unit,
) {
    val ink = FullPlayerTextPrimary
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = if (compact) Arrangement.spacedBy(20.dp) else Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEachIndexed { index, page ->
            val active = selectedPage == page
            val title = when (page) { 0 -> "信息"; 1 -> "封面"; else -> "歌词" }
            Column(
                Modifier
                    .then(if (compact) Modifier.height(28.dp) else Modifier.widthIn(min = 56.dp).height(48.dp))
                    .testTag("$tabTagPrefix$page")
                    .selectable(selected = active, role = Role.Tab) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(index)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    fontSize = if (compact) 13.5.sp else 14.5.sp,
                    maxLines = 1,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) ink else FullPlayerTextMuted.copy(alpha = 0.62f),
                )
                Box(
                    Modifier
                        .padding(top = if (compact) 3.dp else 4.dp)
                        .width(if (compact) 14.dp else 16.dp)
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(if (active) ink.copy(alpha = 0.72f) else Color.Transparent),
                )
            }
        }
    }
}

// 封面与歌词使用同一个约束计算宽度，不依赖测量回调后二次改布局。
internal fun playerCoverSideDp(width: Float, height: Float, lyricAreaHeight: Float): Float =
    minOf(width, (height - lyricAreaHeight).coerceAtLeast(0f), if (width >= 600f) 480f else width) * 0.92f

internal data class LandscapeImmersiveLayout(
    val leftPaneWidthDp: Float,
    val coverSideDp: Float,
    val coverXDp: Float,
    val coverYDp: Float,
    val lyricsXDp: Float,
    val lyricsYDp: Float,
    val lyricsWidthDp: Float,
    val lyricsHeightDp: Float,
)

internal fun landscapeImmersiveLayout(widthDp: Float, heightDp: Float): LandscapeImmersiveLayout {
    val leftPaneWidth = widthDp * 0.42f
    val gap = 20f
    val coverSide = minOf(leftPaneWidth * 0.82f, (heightDp - 58f).coerceAtLeast(0f) * 0.82f, 280f)
    val coverX = ((leftPaneWidth - coverSide) / 2f).coerceAtLeast(0f)
    val coverY = ((heightDp - coverSide - 26f) / 2f).coerceAtLeast(12f)
    val lyricsX = leftPaneWidth + gap
    val lyricsWidth = (widthDp - lyricsX).coerceAtLeast(0f)
    val lyricsHeight = (heightDp - 24f).coerceAtLeast(0f)
    return LandscapeImmersiveLayout(
        leftPaneWidthDp = leftPaneWidth,
        coverSideDp = coverSide,
        coverXDp = coverX,
        coverYDp = coverY,
        lyricsXDp = lyricsX,
        lyricsYDp = 12f,
        lyricsWidthDp = lyricsWidth,
        lyricsHeightDp = lyricsHeight,
    )
}

private fun AnimatedContentTransitionScope<UiTrack?>.playerHeadingTransition(eInk: Boolean): ContentTransform {
    val enter = if (eInk) EnterTransition.None else
        slideInHorizontally(tween(320, easing = LinearOutSlowInEasing)) { it } + fadeIn(tween(160))
    val exit = if (eInk) ExitTransition.None else
        slideOutHorizontally(tween(220, easing = LinearOutSlowInEasing)) { -it } + fadeOut(tween(120))
    return (enter togetherWith exit).using(null)
}

private data class PortraitCoverSpace(val normal: Dp, val immersive: Dp)

// 端点均为播放器视口内的像素。沉浸端点相对第 1 页测量，翻页偏移仅在放置时计入。
private class LandscapeArtworkAnchors {
    var root: LayoutCoordinates? = null
    var page: LayoutCoordinates? = null
    var lastPageOffset = 0f
    var normalViewport by mutableStateOf<Rect?>(null)
    private var normal by mutableStateOf<Rect?>(null)
    private var full by mutableStateOf<Rect?>(null)

    fun update(slot: LayoutCoordinates, immersive: Boolean) {
        val parent = (if (immersive) page else root)?.takeIf { it.isAttached } ?: return
        val bounds = parent.localBoundingBoxOf(slot, clipBounds = false)
        if (immersive) full = bounds else normal = bounds
    }

    fun bounds(progress: Float): Rect {
        val from = normal ?: full ?: return Rect.Zero
        return lerp(from, full ?: from, progress)
    }
}

@Composable
private fun LandscapeArtworkLayer(
    anchors: LandscapeArtworkAnchors,
    immersion: State<Float>,
    immersivePager: PagerState?,
    compact: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    fun pageOffset() = if (immersivePager == null) anchors.lastPageOffset else
        (1 - immersivePager.currentPage - immersivePager.currentPageOffsetFraction) * direction
    val modifier = Modifier.fillMaxSize().clipToBounds().drawWithContent {
        val viewport = anchors.normalViewport
        val window = Rect(Offset.Zero, size)
        val clip = when {
            immersivePager != null -> window.translate(pageOffset() * size.width, 0f)
            compact && viewport != null -> lerp(viewport, window, immersion.value)
            else -> window
        }
        clipRect(clip.left, clip.top, clip.right, clip.bottom) { this@drawWithContent.drawContent() }
    }.layout { measurable, constraints ->
        val progress = immersion.value
        // 从不可见的相邻页退出时，在普通位置淡入，避免先画出整张沉浸封面。
        val fromOtherPage = immersivePager == null && abs(anchors.lastPageOffset) >= 1f
        val bounds = anchors.bounds(if (fromOtherPage) 0f else progress)
        val side = bounds.width.roundToInt().coerceAtLeast(0)
        val face = measurable.measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val offset = when {
                immersivePager != null -> pageOffset().also { anchors.lastPageOffset = it }
                fromOtherPage -> 0f
                else -> anchors.lastPageOffset * progress
            }
            val inViewport = !compact || progress > 0f || anchors.normalViewport?.overlaps(bounds) != false
            if (side > 0 && abs(offset) < 1f && inViewport) {
                face.placeWithLayer((bounds.left + constraints.maxWidth * offset).roundToInt(), bounds.top.roundToInt()) {
                    alpha = if (fromOtherPage) 1f - progress else 1f
                }
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center, content = content)
}

private val seekToLyric: (LyricLine) -> Unit = { PlaybackController.seekTo(it.startMs) }
private val playFromLyric: (LyricLine) -> Unit = { PlaybackController.seekTo(it.startMs, play = true) }

// 单独更新逐字时间时保留浏览位置，更换曲目或歌词行时重置。
@Composable
private fun lyricContentKey(lines: List<LyricLine>) = remember(lines) { lines.map { it.startMs to it.text } }

// 播放页 Page 1：默认封面歌词对齐左边沿，圆形/黑胶歌词居中。
@Composable
private fun PlayerCoverPage(
    state: PlayerUiState,
    immersive: Boolean,
    immersion: State<Float>,
    track: UiTrack?,
    lyrics: List<LyricLine>,
    compact: Boolean,
    miniLyricsEnabled: Boolean,
    wordLiftEnabled: Boolean,
    lyricsInCover: Boolean,
    largePortrait: Boolean,
    posterMode: Boolean,
    portraitSpace: PortraitCoverSpace?,
    centered: Boolean,
    twoPanes: Boolean,
    roundness: State<Float>,
    artwork: @Composable BoxScope.() -> Unit,
    position: State<Long>,
    motionEnabled: Boolean,
    frame: State<com.leyu.melora.playback.LyricFrame>,
    onNavigateToLyrics: () -> Unit,
) {
    val lines = remember(track?.uid, track?.title, track?.artist, lyrics) { fullPlayerLyricsOrFallback(track, lyrics) }
    val canPlayLyrics = immersive && lyrics.isNotEmpty()
    val c = roundness.value
    // 横屏的两个独立布局只报告各自端点；连续插值由共有的封面完成。
    val t = if (twoPanes) (if (immersive) 1f else 0f) else immersion.value
    val miniFontSize = if (largePortrait) 24f else if (compact) 12f else 16f
    val previewHeight = if (compact) 28.dp else with(LocalDensity.current) { maxOf(24f, miniFontSize * 1.5f).sp.toDp() * 5 } + 12.dp
    val normalLyricsVisible = miniLyricsEnabled && lyricsInCover
    val normalLyricsHeight = if (normalLyricsVisible) previewHeight + 16.dp else 0.dp
    val posterTitleVisible = posterMode && !normalLyricsVisible
    val posterTitleHeight = with(LocalDensity.current) { 28.sp.toDp() + 19.sp.toDp() + 4.dp }
    val posterBaseTopGap = 20.dp
    val posterBaseBottomGap = 16.dp
    val posterReservedHeight = if (posterTitleVisible) posterBaseTopGap + posterTitleHeight + posterBaseBottomGap else 0.dp
    val normalContentHeight = if (normalLyricsVisible) normalLyricsHeight else posterReservedHeight
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidth = maxWidth
        val targetLandscape = if (compact) landscapeImmersiveLayout(paneWidth.value, maxHeight.value) else null
        // 进出时用完整控件和系统栏的端点，避免从变化中的剩余空间重复推算封面大小。
        val normalPaneHeight = if (!immersive && t == 0f) maxHeight else portraitSpace?.normal ?: maxHeight
        val immersivePaneHeight = portraitSpace?.immersive ?: maxHeight
        val squareSide = minOf(maxWidth, (normalPaneHeight - normalContentHeight).coerceAtLeast(0.dp), if (maxWidth >= 600.dp) 480.dp else maxWidth)
        val circleSide = if (posterTitleVisible) {
            squareSide * 0.96f
        } else {
            playerCoverSideDp(maxWidth.value, normalPaneHeight.value, normalLyricsHeight.value).dp
        }
        val normalSide = squareSide * (1f - c) + circleSide * c
        val floatingSide = when {
            targetLandscape != null -> targetLandscape.coverSideDp.dp
            lyricsInCover -> minOf(maxWidth * 0.75f, immersivePaneHeight * 0.40f)
            else -> minOf(maxWidth * 0.85f, maxHeight * 0.70f, 420.dp)
        }
        val side = normalSide * (1f - t) + floatingSide * t
        val normalBelowCover = if (normalLyricsVisible) previewHeight + 24.dp else 12.dp
        // 普通态留白只能由普通态封面计算；用插值中的 side 会把缩小释放的空间再次加到顶部，导致先下沉再上移。
        val remaining = (normalPaneHeight - normalSide - if (posterTitleVisible) posterReservedHeight else normalBelowCover).coerceAtLeast(0.dp)
        val extraCoverToTitle = if (posterTitleVisible) (remaining * 0.25f).coerceAtMost(14.dp) else 0.dp
        val coverToTitleGap = posterBaseTopGap + extraCoverToTitle
        // 与歌词共用外层窗口判定，不能用扣除页内边距后的宽度再次判断断点。无歌词时也居中。
        val centering = if (!normalLyricsVisible || largePortrait || compact) 1f else c
        val roomyLyrics = largePortrait && normalLyricsVisible
        val normalTop = when {
            posterTitleVisible -> (remaining - extraCoverToTitle).coerceAtLeast(0.dp) * 0.55f
            roomyLyrics -> minOf(12.dp, remaining)
            else -> remaining * (1f - centering * 0.5f)
        }
        val normalLyricHeight = if (roomyLyrics) (normalPaneHeight - normalSide - normalTop - 20.dp).coerceAtLeast(0.dp) else previewHeight
        val immersiveTop = immersivePaneHeight * 0.04f
        val immersiveTimeHeight = 26.dp
        val timeHeight = immersiveTimeHeight * t
        val topSpace = if (lyricsInCover) lerp(normalTop, immersiveTop, t)
            else ((maxHeight - side - timeHeight) / 2f).coerceAtLeast(0.dp)
        val normalLyricWidth = if (roomyLyrics) maxWidth else normalSide
        // 歌词焦点在视口中线。直接插值两端高度，收回时不会越过普通焦点再回弹。
        val immersiveLyricHeight = (immersivePaneHeight - immersiveTop - floatingSide - immersiveTimeHeight - 24.dp).coerceAtLeast(0.dp)
        val lyricHeight = lerp(if (miniLyricsEnabled) normalLyricHeight else 0.dp, immersiveLyricHeight, t)
        val lyricWidth = lerp(maxWidth * (1f - centering) + normalLyricWidth * centering, maxWidth, t)
        val lyricsContent: @Composable (Modifier) -> Unit = { lyricsModifier ->
            key(track?.uid, lyricContentKey(lines)) {
                LyricsViewport(
                    lines = lines, position = position,
                    config = LyricsUiConfig(fontSizeSp = miniFontSize, isWordLiftEnabled = wordLiftEnabled),
                    mini = !immersive, immersive = immersive,
                    frameState = frame, motionEnabled = motionEnabled,
                    centered = (centered && (!compact || immersive)) || largePortrait,
                    modifier = lyricsModifier.testTag("player-mini-lyrics")
                        .then(
                            when {
                                immersive || roomyLyrics -> Modifier.lyricViewportFade()
                                !compact -> Modifier.lyricViewportFade(0.14f)
                                else -> Modifier
                            },
                        )
                        .graphicsLayer { alpha = if (miniLyricsEnabled) 1f else immersion.value },
                    onLineClick = when {
                        !immersive -> { _ -> onNavigateToLyrics() }
                        canPlayLyrics -> seekToLyric
                        else -> null
                    },
                    onPlayLine = if (canPlayLyrics) playFromLyric else null,
                    onTogglePlayback = if (canPlayLyrics) PlaybackController::toggle else null,
                )
            }
        }
        if (targetLandscape != null) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.offset(x = targetLandscape.coverXDp.dp, y = targetLandscape.coverYDp.dp).width(side),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(side), contentAlignment = Alignment.Center, content = artwork)
                    ImmersiveTrackTime(position, state.durationMs,
                        Modifier.height(timeHeight).graphicsLayer { alpha = immersion.value })
                }
                lyricsContent(Modifier.offset(x = targetLandscape.lyricsXDp.dp, y = targetLandscape.lyricsYDp.dp)
                    .size(targetLandscape.lyricsWidthDp.dp, targetLandscape.lyricsHeightDp.dp)
                    .graphicsLayer { alpha = immersion.value })
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(topSpace))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(side), contentAlignment = Alignment.Center, content = artwork)
                }
                if (t > 0f) ImmersiveTrackTime(position, state.durationMs,
                    Modifier.height(timeHeight).graphicsLayer { alpha = immersion.value })
                if (posterTitleVisible && t < 0.99f) {
                    val posterAlpha = (1f - t * 2f).coerceIn(0f, 1f)
                    Column(
                        modifier = (if (centered) Modifier.fillMaxWidth() else Modifier.width(side))
                            .graphicsLayer { alpha = posterAlpha }
                            .then(if (immersive) Modifier.clearAndSetSemantics {} else Modifier),
                        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        Spacer(Modifier.height(coverToTitleGap * (1f - t)))
                        AnimatedContent(
                            targetState = track,
                            contentKey = { it?.uid },
                            modifier = Modifier.fillMaxWidth().clipToBounds().testTag("player-poster-heading"),
                            transitionSpec = {
                                playerHeadingTransition(MeloraAppearance.isEInk)
                            },
                            label = "playerPosterHeading",
                        ) { posterTrack ->
                            val align = if (centered) TextAlign.Center else TextAlign.Start
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                            ) {
                                Text(
                                    text = posterTrack?.title ?: "未在播放",
                                    fontSize = 21.sp,
                                    lineHeight = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FullPlayerTextPrimary,
                                    textAlign = align,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(posterTrack?.artist, posterTrack?.album.takeIf { !it.isNullOrBlank() })
                                        .joinToString(" · "),
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                    color = FullPlayerTextMuted,
                                    textAlign = align,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (lyricsInCover && ((miniLyricsEnabled && !posterTitleVisible) || t > 0f)) {
                    Spacer(Modifier.height(12.dp))
                    lyricsContent(Modifier.width(lyricWidth).height(lyricHeight))
                }
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
    immersive: Boolean,
    immersion: androidx.compose.runtime.State<Float>,
    onConfigChange: (LyricsUiConfig) -> Unit,
    onOpenSource: () -> Unit,
    toolsPadding: PaddingValues = PaddingValues(0.dp),
    durationMs: Long = 0L,
    stageMode: Boolean = false,
    playing: Boolean = false,
    isVisible: Boolean = true,
    onImmersiveChange: (Boolean) -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    largeDisplay: Boolean = false,
    compactLandscape: Boolean = false,
    lyricsTestTag: String = "player-full-lyrics",
) {
    val hasLyrics = lyrics.isNotEmpty()
    val lines = remember(track?.uid, track?.title, track?.artist, lyrics) { fullPlayerLyricsOrFallback(track, lyrics) }
    val contentKey = lyricContentKey(lines)
    if (stageMode) {
        val eInk = MeloraAppearance.isEInk
        var controlsVisible by remember(stageMode) { mutableStateOf(false) }
        var controlInteraction by remember { mutableIntStateOf(0) }
        LaunchedEffect(stageMode, isVisible, controlsVisible, controlInteraction, track?.uid) {
            if (!stageMode || !isVisible) controlsVisible = false
            else if (controlsVisible) { delay(3_000); controlsVisible = false }
        }
        val interact = { controlsVisible = true; controlInteraction++ }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { interact() },
        ) {
            key(track?.uid, contentKey) {
                ImmersiveStageLyrics(
                    track = track,
                    durationMs = durationMs,
                    playing = playing,
                    isVisible = isVisible,
                    lines = lines,
                    position = position,
                    frameState = frame,
                    config = config,
                    motionEnabled = motionEnabled,
                    modifier = Modifier.fillMaxSize().testTag(lyricsTestTag),
                )
            }
            AnimatedVisibility(
                visible = controlsVisible && isVisible,
                enter = if (eInk) EnterTransition.None else fadeIn(tween(120)),
                exit = if (eInk) ExitTransition.None else fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            ) {
                ImmersivePlaybackControls(
                    playing = playing,
                    onPrevious = { interact(); onPrevious() },
                    onToggle = { interact(); PlaybackController.toggle() },
                    onNext = { interact(); onNext() },
                    onExit = { onImmersiveChange(false) },
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        val effectiveConfig = when {
            largeDisplay && !immersive -> config.copy(fontSizeSp = (config.fontSizeSp + 4f).coerceAtMost(34f))
            compactLandscape && !immersive -> config.copy(fontSizeSp = (config.fontSizeSp + 2f).coerceAtMost(30f))
            else -> config
        }
        key(track?.uid, contentKey) {
            LyricsViewport(
                lines = lines,
                position = position,
                config = effectiveConfig,
                frameState = frame,
                motionEnabled = motionEnabled,
                immersive = immersive,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(lyricsTestTag)
                    .lyricViewportFade(0.16f),
                onLineClick = if (hasLyrics) seekToLyric else null,
                onPlayLine = if (hasLyrics) playFromLyric else null,
                onTogglePlayback = if (hasLyrics) PlaybackController::toggle else null,
            )
        }
        Box(Modifier.immersionChrome(immersion).padding(toolsPadding), contentAlignment = Alignment.CenterStart) {
            LyricsSettingsTools(config = config, onConfigChange = onConfigChange, onOpenSource = onOpenSource)
        }
    }
}

/** 固定高度的行内工具条；展开只占用右侧空白，不移动歌词或进度条。 */
@Composable
private fun LyricsSettingsTools(
    config: LyricsUiConfig,
    onConfigChange: (LyricsUiConfig) -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val desktopLyricsEnabled by MeloraSettings.showDesktopLyrics.collectAsStateWithLifecycle()
    val onDesktopLyricsChanged = rememberDesktopLyricsToggle()
    var expanded by remember { mutableStateOf(false) }
    val accent = FullPlayerSheetPrimaryBlue
    val ink = FullPlayerTextPrimary
    val chipInteraction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
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
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    expanded = !expanded
                }
                .semantics { contentDescription = if (expanded) "收起歌词设置" else "展开歌词设置" },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (expanded) accent.copy(alpha = 0.16f) else ink.copy(alpha = 0.10f))
                    .border(
                        width = 0.5.dp,
                        color = if (expanded) accent.copy(alpha = 0.36f) else ink.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "词",
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (expanded) ink else ink.copy(alpha = 0.85f),
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
        Spacer(Modifier.width(2.dp))
        // 外层由起点揭示；横向滚动不越界
        Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.CenterStart) {
            LyricsToolsPanel(expanded, config, onConfigChange, onOpenSource, desktopLyricsEnabled, onDesktopLyricsChanged)
        }
    }
}

@Composable
internal fun LyricsToolsPanel(
    expanded: Boolean,
    config: LyricsUiConfig,
    onConfigChange: (LyricsUiConfig) -> Unit,
    onOpenSource: () -> Unit,
    desktopLyricsEnabled: Boolean,
    onDesktopLyricsChanged: (Boolean) -> Unit,
) {
    val eInk = MeloraAppearance.isEInk
    val disabledEffects = "墨水屏模式下关闭".takeIf { eInk }
    AnimatedVisibility(
        visible = expanded,
        enter = if (eInk) EnterTransition.None
            else expandHorizontally(tween(220, easing = LinearOutSlowInEasing), expandFrom = Alignment.Start) + fadeIn(tween(140)),
        exit = if (eInk) ExitTransition.None
            else shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start) + fadeOut(tween(120)),
    ) {
        Row(
            Modifier.height(36.dp).horizontalScroll(rememberScrollState()).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsToolButton(
                Icons.Outlined.PictureInPictureAlt,
                if (desktopLyricsEnabled) "关闭桌面歌词" else "开启桌面歌词",
                isSelected = desktopLyricsEnabled,
            ) { onDesktopLyricsChanged(!desktopLyricsEnabled) }
            LyricsToolButton(Icons.Outlined.MusicNote, "歌词来源与写入", onClick = onOpenSource)
            LyricsToolDivider()
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
            LyricsToolButton(
                Icons.Rounded.BlurOn, "虚化非当前歌词", iconSize = 20.dp,
                isSelected = !eInk && config.isBlurEnabled, disabledReason = disabledEffects,
            ) {
                onConfigChange(config.copy(isBlurEnabled = !config.isBlurEnabled))
            }
            LyricsToolButton(
                Icons.Rounded.Animation, "逐字微浮起", iconSize = 20.dp,
                isSelected = !eInk && config.isWordLiftEnabled, disabledReason = disabledEffects,
            ) {
                onConfigChange(config.copy(isWordLiftEnabled = !config.isWordLiftEnabled))
            }
        }
    }
}

@Composable
private fun LyricsToolDivider() {
    Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(16.dp).background(FullPlayerTextMuted.copy(alpha = 0.22f)))
}

@Composable
private fun LyricsToolButton(
    icon: ImageVector,
    label: String,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    isSelected: Boolean? = null,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val accent = FullPlayerSheetPrimaryBlue
    val haptics = LocalHapticFeedback.current
    val canClick = enabled && disabledReason == null
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = canClick, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics {
                contentDescription = label
                isSelected?.let { selected = it }
                disabledReason?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = when {
                !canClick -> FullPlayerTextMuted.copy(alpha = 0.35f)
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
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val timerState = remember { SliderState(value = 30f, steps = 16, trackRange = 5f..90f) }
    var extendToFinishTrack by remember { mutableStateOf(true) }
    val sleepRemaining by PlaybackController.sleepRemaining.collectAsStateWithLifecycle()
    val sleepTimerEnabled = sleepRemaining != null

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    val colors = LocalPlayerColors.current

    WithMeloraSheetTheme(darkTheme = colors.isDark) {
        val palette = currentMeloraSheetPalette()
        MeloraBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.sheetBackground,
            lightFrostTint = if (!colors.isDark) LightSheetFrost.tint else Color.Unspecified,
            dragHandle = { MeloraSheetHandle(color = colors.sheetTextMuted.copy(alpha = 0.25f)) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                            .background(if (palette.isDark) Color(0xFF222226) else palette.brand.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = palette.brand,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "睡眠与播放设置",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textMain,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (sleepRemaining == 0) {
                                "播完当前歌曲后停止"
                            } else if (sleepTimerEnabled) {
                                val minutes = sleepRemaining?.div(60) ?: 0
                                val seconds = sleepRemaining?.rem(60) ?: 0
                                "定时中 · 剩余 %d 分 %d 秒后停止".format(minutes, seconds)
                            } else {
                                "播放倍速与定时停止"
                            },
                            fontSize = 12.sp,
                            color = palette.textSub,
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))

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
                        Text("播放倍速", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textMain)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = palette.segmentTrack,
                        ) {
                            Row(modifier = Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                speedOptions.forEach { speed ->
                                    val isSelected = Math.abs(currentSpeed - speed) < 0.05f
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) palette.card else Color.Transparent,
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
                                            color = if (isSelected) palette.textMain else palette.textSub,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
                            Text("睡眠定时", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textMain)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (sleepRemaining == 0) {
                                    "播完当前歌曲后停止"
                                } else if (sleepTimerEnabled) {
                                    val minutes = sleepRemaining?.div(60) ?: 0
                                    val seconds = sleepRemaining?.rem(60) ?: 0
                                    "剩余 %d 分 %d 秒后自动停止播放".format(minutes, seconds)
                                } else {
                                    "到期自动暂停播放"
                                },
                                fontSize = 12.sp,
                                color = palette.textSub,
                            )
                        }
                        Switch(
                            checked = sleepTimerEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) PlaybackController.setSleepTimer(timerState.value.toInt(), extendToFinishTrack)
                                else PlaybackController.cancelSleepTimer()
                            },
                            colors = meloraSwitchColors(),
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
                                Text("定时时长", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textMain)
                                Text("${timerState.value.toInt()} 分钟", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.brand)
                            }
                            Slider(
                                state = timerState,
                                onValueChange = { timerState.value = it },
                                onValueChangeFinished = {
                                    PlaybackController.setSleepTimer(timerState.value.toInt(), extendToFinishTrack)
                                },
                                thumb = {
                                    Box(
                                        Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(palette.brand),
                                    )
                                },
                                colors = SliderDefaults.colors(
                                    activeTrackColor = palette.brand,
                                    inactiveTrackColor = palette.softFill,
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
                            Text("播完当前歌曲后再停止", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textMain)
                            Spacer(Modifier.height(2.dp))
                            Text("倒计时结束后，等当前歌曲播完再暂停", fontSize = 12.sp, color = palette.textSub)
                        }
                        Switch(
                            checked = extendToFinishTrack,
                            onCheckedChange = { value ->
                                extendToFinishTrack = value
                                if (sleepTimerEnabled) PlaybackController.setSleepTimer(timerState.value.toInt(), value)
                            },
                            colors = meloraSwitchColors(),
                        )
                    }
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
    playing: Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val selectedPreset = AudioEffects.preset(current)
    val effectState by AudioEffects.state.collectAsStateWithLifecycle()
    val colors = LocalPlayerColors.current
    val eInk = MeloraAppearance.isEInk
    val accent = when {
        eInk -> Color.Black
        colors.isDark -> DarkGlass.selectedText
        else -> LightSheetFrost.blue
    }

    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.sheetBackground,
        lightFrostTint = LightSheetFrost.tint,
        dragHandle = { MeloraSheetHandle(color = colors.sheetTextMuted.copy(alpha = 0.25f)) },
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
                        color = colors.sheetText,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = when {
                            eInk -> Color(0xFFE8E8E8)
                            colors.isDark -> DarkGlass.selectedFill.copy(alpha = 0.18f)
                            else -> accent.copy(alpha = 0.11f)
                        },
                        border = when {
                            eInk -> BorderStroke(1.dp, Color.Black)
                            colors.isDark -> BorderStroke(0.5.dp, DarkGlass.selectedEdge.copy(alpha = 0.28f))
                            else -> null
                        },
                    ) {
                        Text(
                            text = selectedPreset.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                AudioVisualizerBars(
                    active = playing && effectState.active,
                    color = if (effectState.active) accent else colors.sheetTextMuted.copy(alpha = 0.30f),
                )
            }

            Text(
                text = AudioEffects.statusText(current, effectState),
                color = if (effectState.phase == AudioEffects.Phase.Failed) {
                    if (colors.isDark) Color(0xFFF2635C) else FullPlayerSheetAccentRed
                } else colors.sheetTextMuted,
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
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AudioEffects.effectPresets.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { item ->
                            val isSelected = current == item.id
                            val selectedDark = !eInk && colors.isDark && isSelected
                            val labelColor = when {
                                eInk -> Color.Black
                                selectedDark -> Color(0xFFC7DBFF)
                                isSelected -> LightSheetFrost.activeText
                                else -> colors.sheetText
                            }

                            Surface(
                                onClick = { onToggle(item.id) },
                                shape = RoundedCornerShape(17.dp),
                                color = when {
                                    eInk -> if (isSelected) Color(0xFFE8E8E8) else Color.White
                                    selectedDark -> DarkGlass.selectedFill
                                    colors.isDark -> DarkGlass.card
                                    isSelected -> LightSheetFrost.activeCard
                                    else -> LightSheetFrost.card
                                },
                                border = when {
                                    eInk -> BorderStroke(if (isSelected) 1.5.dp else 1.dp, Color.Black)
                                    selectedDark -> BorderStroke(0.5.dp, DarkGlass.selectedEdge.copy(alpha = 0.36f))
                                    colors.isDark -> BorderStroke(0.5.dp, DarkGlass.border)
                                    isSelected -> LightSheetFrost.activeBorder
                                    else -> LightSheetFrost.cardBorder
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
                                                    when {
                                                        eInk -> if (isSelected) Color.Black else Color(0xFFF2F2F2)
                                                        selectedDark -> DarkGlass.selectedEdge.copy(alpha = 0.22f)
                                                        colors.isDark -> DarkGlass.icon
                                                        isSelected -> LightSheetFrost.blue
                                                        else -> LightSheetFrost.iconWell
                                                    },
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = audioEffectIcon(item.id),
                                                contentDescription = null,
                                                tint = when {
                                                    eInk -> if (isSelected) Color.White else Color.Black
                                                    selectedDark -> Color(0xFFB4CEFF)
                                                    isSelected -> Color.White
                                                    else -> colors.sheetText
                                                },
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = item.label,
                                            fontSize = 14.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = labelColor,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // 保留勾选位，开关音效时标题不换行、不跳动。
                                        Box(Modifier.size(18.dp)) {
                                            if (isSelected) Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = accent,
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
                                        color = if (selectedDark) labelColor.copy(alpha = 0.72f) else colors.sheetTextMuted,
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
private fun AudioVisualizerBars(active: Boolean, color: Color) {
    val animatedHeights = if (active && !MeloraAppearance.isEInk) {
        val transition = rememberInfiniteTransition(label = "audio-visualizer")
        List(6) { index ->
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
    } else null
    Row(
        modifier = Modifier
            .width(42.dp)
            .height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (index in 0 until 6) {
            val h = animatedHeights?.get(index)?.value ?: (6f + (index % 3) * 2f)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
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
        LaunchedEffect(track?.uid) { onDismiss() }
        return
    }
    WithMeloraSheetTheme(darkTheme = LocalPlayerColors.current.isDark) {
        com.leyu.melora.ui.common.SongMoreSheet(song = song, onDismiss = onDismiss)
    }
}


/** 保留同一组控件与测量，缩短其占位同时向下淡出；退出从当前进度反向恢复。 */
private fun Modifier.immersionChrome(progress: androidx.compose.runtime.State<Float>): Modifier =
    this.clipToBounds().then(if (progress.value > 0.99f) Modifier.clearAndSetSemantics {} else Modifier)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val visible = 1f - progress.value
            layout(placeable.width, (placeable.height * visible).roundToInt()) {
                placeable.placeRelativeWithLayer(0, (placeable.height * (1f - visible) * 0.3f).roundToInt()) {
                    alpha = visible
                }
            }
        }

@Composable
private fun ImmersiveTrackTime(position: androidx.compose.runtime.State<Long>, durationMs: Long, modifier: Modifier) {
    val seconds by remember(position) { derivedStateOf { position.value / 1_000L } }
    Text("${formatClock(seconds * 1_000L)} / ${formatClock(durationMs)}", modifier = modifier,
        color = FullPlayerTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun ImmersivePlaybackControls(
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val eInk = MeloraAppearance.isEInk
    Row(Modifier.clip(RoundedCornerShape(24.dp))
        .background(if (eInk) Color.White else LocalPlayerColors.current.cardSurface.copy(alpha = 0.92f))
        .then(if (eInk) Modifier.border(1.dp, Color.Black, RoundedCornerShape(24.dp)) else Modifier)
        .padding(horizontal = 4.dp).testTag("immersive-playback-controls"),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "上一首", tint = FullPlayerTextPrimary) }
        IconButton(onClick = onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "暂停" else "播放", tint = FullPlayerTextPrimary) }
        IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "下一首", tint = FullPlayerTextPrimary) }
        IconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "退出沉浸播放", tint = FullPlayerTextPrimary) }
    }
}
