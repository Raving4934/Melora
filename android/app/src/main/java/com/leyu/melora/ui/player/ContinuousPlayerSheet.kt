package com.leyu.melora.ui.player

import androidx.compose.ui.graphics.Color
import com.leyu.melora.ui.common.submitLibraryWrite
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.animateToWithDecay
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.LyricTimeline
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlayMode
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.AccentRed
import com.leyu.melora.ui.common.LocalPageActive
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.MeloraCardDark
import com.leyu.melora.ui.theme.SystemBarsAppearance
import com.leyu.melora.ui.theme.LocalForceHideStatusBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ContinuousPlayerSheet(
    playback: State<PlayerUiState>,
    modifier: Modifier = Modifier,
    backState: PlayerSheetBackState = rememberPlayerSheetBackState(),
    onSeek: (Long) -> Unit = { PlaybackController.seekTo(it) },
    onPrevious: () -> Unit = { PlaybackController.previous() },
    onNext: () -> Unit = { PlaybackController.next() },
) {
    val sampledPosition = remember(playback) { derivedStateOf { playback.value.positionMs } }
    val state by remember(playback) {
        derivedStateOf {
            playback.value.copy(positionMs = 0, positionSampleRealtimeMs = 0, lyricPositionFloorMs = 0)
        }
    }
    // 迷你条、过渡封面和全屏共用当前曲目；空队列不能保留已失效的旧封面。
    val track = state.current ?: return
    // loading 保留上一幅环境背景；loader 统一发布新图+色调，失败/无封面才清空。
    var playerBackdropEntry by remember { mutableStateOf<PlayerBackdropCacheEntry?>(null) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val rootThemeMode by MeloraSettings.themeMode.collectAsStateWithLifecycle()
    val playerThemeMode by MeloraSettings.playerThemeMode.collectAsStateWithLifecycle()
    val eInkMode by MeloraSettings.eInkMode.collectAsStateWithLifecycle()
    val keepScreenAwake by MeloraSettings.keepScreenAwake.collectAsStateWithLifecycle()
    val playerCoverStyle by MeloraSettings.playerCoverStyle.collectAsStateWithLifecycle()
    val playerMotionEnabled = rememberPlayerMotionEnabled(isVisible = true)
    val vinylRotation = rememberVinylRotation(state.playing && playerMotionEnabled, playerCoverStyle)
    val playerIsDark = !eInkMode && playerThemeMode.isDark(rootThemeMode.isDark(isSystemInDarkTheme()))
    // 迷你条属于普通页面，全屏页面主题不能改变其底色、前景和系统栏。
    val miniColors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val playerLyric by PlaybackController.lyric.collectAsStateWithLifecycle()
    val lyricLines = playerLyricLines(track.uid, playerLyric)
    val displayLyricLines = remember(track, lyricLines) { fullPlayerLyricsOrFallback(track, lyricLines) }
    val timeline = remember(displayLyricLines) { LyricTimeline(displayLyricLines) }
    val lyricPosition = rememberLyricPosition(
        playback,
        visible = eInkMode || lyricLines.isNotEmpty(),
        eInkTimeline = timeline.takeIf { eInkMode },
    )
    val lyricFrameState = rememberLyricFrame(displayLyricLines, lyricPosition, timeline)
    val lyricFrame by lyricFrameState
    val sheetState = backState.sheet
    val swipeOffset = remember(track.uid) { mutableFloatStateOf(0f) }
    val currentPrevious by rememberUpdatedState(onPrevious)
    val currentNext by rememberUpdatedState(onNext)
    val verticalPagerState = rememberPagerState(pageCount = { 2 })
    var queueReturnTarget by rememberSaveable { mutableStateOf(PlayerSheetAnchor.Expanded) }
    LaunchedEffect(verticalPagerState.settledPage) {
        if (verticalPagerState.settledPage == 0) queueReturnTarget = PlayerSheetAnchor.Expanded
    }
    var pageShowsCover by remember { mutableStateOf(true) }
    var pageBlocksCollapse by remember { mutableStateOf(false) }
    var pageIsLight by remember(playerIsDark) { mutableStateOf(!playerIsDark) }
    var sheetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    var fullBounds by remember { mutableStateOf<Rect?>(null) }
    val decay = rememberSplineBasedDecay<Float>()
    val fling = AnchoredDraggableDefaults.flingBehavior(
        state = sheetState,
        positionalThreshold = { distance -> distance * 0.35f },
        animationSpec = PlayerPageSnapSpec,
    )

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val twoPanes = playerUsesTwoPanes(maxWidth.value, maxHeight.value)
        val expandedLayout = playerUsesExpandedLayout(maxWidth.value, maxHeight.value)
        val desktopMiniBar = playerUsesDesktopMiniBar(maxWidth.value, maxHeight.value)
        val desktopCenterControlsWidth = if (maxWidth >= 900.dp) 420.dp else 280.dp
        val miniBarHeight = if (desktopMiniBar) DesktopMiniPlayerHeight else MiniPlayerContentHeight
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val travel = with(density) { (maxHeight - miniBarHeight - bottomInset).toPx() }.coerceAtLeast(1f)
        val topInset = WindowInsets.statusBars.getTop(density).toFloat()
        val velocityThreshold = with(density) { 125.dp.toPx() }
        val anchors = remember(travel) {
            DraggableAnchors {
                PlayerSheetAnchor.Expanded at 0f
                PlayerSheetAnchor.Collapsed at travel
            }
        }
        SideEffect { sheetState.updateAnchors(anchors, newTarget = sheetState.targetValue) }
        // Read animated values in drawing/gesture lambdas, not throughout the player composition.
        fun expandPlayer() {
            backState.transitionTo(scope, PlayerSheetTransition.Expand) {
                verticalPagerState.scrollToPage(0)
                sheetState.animateTo(PlayerSheetAnchor.Expanded, PlayerPageSnapSpec)
            }
        }
        LaunchedEffect(backState.expandRequest) {
            if (backState.expandRequest != 0) expandPlayer()
        }
        val offset = remember(sheetState, travel) {
            { sheetState.offset.takeIf(Float::isFinite)?.coerceIn(0f, travel) ?: travel }
        }
        val progress = remember(offset, travel) { { playerSheetProgress(offset(), travel) } }
        val coverPersistsAcrossQueue = twoPanes && !expandedLayout
        val playbackPage = remember(verticalPagerState, coverPersistsAcrossQueue) {
            {
                // 手机横屏队列会占用封面的位置；只有封面归位后才允许拉动整张播放页。
                coverPersistsAcrossQueue ||
                    (verticalPagerState.currentPage == 0 && abs(verticalPagerState.currentPageOffsetFraction) < 0.001f)
            }
        }
        val expanded by remember(progress) { derivedStateOf { progress() >= 0.99f } }
        val collapsed by remember(progress, sheetState) {
            derivedStateOf { playerSheetIsCollapsed(sheetState.settledValue, sheetState.targetValue, progress()) }
        }
        val showMini by remember(progress) { derivedStateOf { progress() < 0.22f } }
        LaunchedEffect(collapsed) {
            if (collapsed) immersive = false
            // 全屏接管交互时结束底层搜索输入；只在展开边界执行，不干扰播放器内的输入弹窗。
            else focusManager.clearFocus(force = true)
        }
        val canCollapse = remember(playbackPage, backState) {
            { backState.transition == null && playbackPage() && !pageBlocksCollapse && !immersive }
        }
        val canDrag by remember(offset, canCollapse, backState) {
            derivedStateOf { backState.transition == null && (offset() > 0.5f || canCollapse()) }
        }
        val morphing = remember(progress, playbackPage) {
            {
                val p = progress()
                p > 0f && p < 1f && playbackPage() && pageShowsCover && miniBounds != null && fullBounds != null
            }
        }
        val artworkAlpha = remember(morphing) { { if (morphing()) 0f else 1f } }
        val vinylMotionEnabled = playerCoverStyle == PlayerCoverStyle.Vinyl && playerMotionEnabled &&
            !collapsed && (pageShowsCover || immersive) && (twoPanes || verticalPagerState.currentPage == 0)
        val recordChange = if (playerCoverStyle == PlayerCoverStyle.Vinyl) {
            rememberVinylRecordChange(track.uid, track.artwork, enabled = vinylMotionEnabled)
        } else null
        val baseIsLight = miniColors.surface.luminance() > 0.5f
        val backdropAlpha = remember(progress) {
            { playerMotionPhase(progress(), PlayerMiniFadeEnd, PlayerContentFadeStart) }
        }
        // 与底色淡入同步，只在绘制音符时读取拖动进度。
        val playerInk = if (playerIsDark) DarkPlayerColors.textPrimary else LightPlayerColors.textPrimary
        val sameThemeInk = lerp(miniColors.onSurfaceVariant, playerInk, 0.5f).copy(alpha = 0.48f)
        val miniInk = miniColors.onSurface.copy(alpha = 0.68f)
        val fullInk = playerInk.copy(alpha = 0.68f)
        val playerArtwork = rememberPlayerArtwork(
            track.artwork, track.uid, playerArtworkRequestSidePx(maxWidth, maxHeight, density),
            retryOnError = expanded && (pageShowsCover || immersive) && (twoPanes || verticalPagerState.currentPage == 0),
            motionEnabled = playerMotionEnabled,
            placeholderTint = {
                when {
                    eInkMode -> EInkPlayerColors.textSecondary
                    baseIsLight == !playerIsDark -> sameThemeInk
                    else -> lerp(miniInk, fullInk, backdropAlpha())
                }
            },
            recordChange = recordChange,
        )
        val tonearmAngle = rememberVinylTonearmAngle(
            playing = state.playing,
            motionEnabled = vinylMotionEnabled,
            recordChange = recordChange,
        )

        val nestedScroll = remember(sheetState, travel, velocityThreshold, decay, canCollapse, offset) {
            object : NestedScrollConnection {
                suspend fun settle(velocity: Float): Velocity {
                    val target = playerSheetTarget(offset(), travel, velocity, velocityThreshold, sheetState.settledValue)
                    val consumed = sheetState.animateToWithDecay(target, velocity, PlayerPageSnapSpec, decay)
                    return Velocity(0f, consumed)
                }
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && backState.transition == null && offset() > 0.5f) {
                        Offset(0f, sheetState.dispatchRawDelta(available.y))
                    } else Offset.Zero

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0f && canCollapse()) {
                        Offset(0f, sheetState.dispatchRawDelta(available.y))
                    } else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (backState.transition == null && offset() > 0.5f && offset() < travel) settle(available.y) else Velocity.Zero

                // 只接续播放器实际发生的位移；详情页遗留的惯性不能在返回后突然收起播放器。
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                    if (canCollapse() && offset() > 0.5f) settle(available.y)
                    else Velocity.Zero
            }
        }
        // 返回所有权覆盖整个可见转场，不跟随用于渲染的 expanded 阈值开关。
        val backAction = remember(backState, verticalPagerState) {
            {
                backState.action(
                    queueReturnTarget = queueReturnTarget.takeIf {
                        backState.transition == PlayerSheetTransition.OpenQueue ||
                            verticalPagerState.currentPage != 0 || verticalPagerState.targetPage != 0 ||
                            verticalPagerState.currentPageOffsetFraction != 0f
                    },
                )
            }
        }
        val currentBackAction by remember(backAction) { derivedStateOf { backAction() } }
        val handleBack = {
            // 首次返回可以反转进入/队列翻页；只有同一退出过程的重复返回被合并。
            val action = backAction()
            when (action) {
                PlayerSheetBackAction.Collapse -> backState.transitionTo(scope, PlayerSheetTransition.Collapse) {
                    sheetState.animateTo(PlayerSheetAnchor.Collapsed, PlayerPageSnapSpec)
                }
                PlayerSheetBackAction.ReturnToPlayer -> backState.transitionTo(scope, PlayerSheetTransition.ReturnToPlayer) {
                    verticalPagerState.animateScrollToPage(0, animationSpec = PlayerPageSnapSpec)
                }
                else -> Unit
            }
        }

        BackHandler(enabled = currentBackAction != PlayerSheetBackAction.PassThrough, onBack = handleBack)

        val miniSurface = if (baseIsLight) miniColors.surface else MeloraCardDark
        val darkStatusIcons by remember(offset, topInset, baseIsLight, pageIsLight) {
            derivedStateOf { if (offset() > topInset * 0.5f) baseIsLight else pageIsLight }
        }
        val darkNavIcons by remember(backdropAlpha, baseIsLight, pageIsLight) {
            derivedStateOf { if (backdropAlpha() < 0.5f) baseIsLight else pageIsLight }
        }
        SystemBarsAppearance(
            darkStatusIcons = darkStatusIcons,
            darkNavigationIcons = darkNavIcons,
            keepScreenAwake = keepScreenAwake && expanded,
            forceHideStatusBar = immersive && expanded,
        )

        val maxSwipePx = with(density) { 88.dp.toPx() }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }
        // 迷你条第二行：当前歌词（无歌词/间奏时给出占位）
        val currentLine = lyricLines.getOrNull(lyricFrame.focusIndex)
        val currentLyricText = currentLine?.text?.trim()?.ifBlank { "♪" }
            ?: if (lyricLines.isEmpty()) "暂无歌词" else "♪"
        // 播放状态优先于歌词：缓冲/解析/换源时给出明确状态，真正播放后才展示歌词
        val playbackStatus = when {
            state.message?.contains("重新解析") == true || state.message?.contains("换源") == true ->
                "源不可用，正在自动换源…"
            state.message?.contains("失败") == true || state.message?.contains("不可用") == true ->
                "源不可用"
            state.resolving -> "正在解析音源…"
            state.buffering -> "缓冲中…"
            else -> null
        }
        val subtitleText = playbackStatus ?: currentLyricText

        val viewConfiguration = LocalViewConfiguration.current
        val verticalViewConfiguration = rememberPlayerVerticalViewConfiguration()
        CompositionLocalProvider(LocalViewConfiguration provides verticalViewConfiguration) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val p = progress()
                        translationY = offset()
                        shape = playerSheetShape(p, translationY.toDp())
                        clip = true
                    }
                    // 端点坐标位于位移层内部，不能把容器 translationY 再算入封面轨迹。
                    .onPlaced { sheetCoordinates = it }
                    .nestedScroll(nestedScroll)
                    .anchoredDraggable(sheetState, Orientation.Vertical, enabled = canDrag, flingBehavior = fling)
                    .background(miniSurface),
            ) {
                CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                    CompositionLocalProvider(
                        LocalForceHideStatusBar provides (immersive && expanded),
                        // 静止展开时保留内部弹窗/歌词/详情的返回优先级；转场时只由外层消费。
                        LocalPageActive provides (LocalPageActive.current && expanded &&
                            backState.transition == null),
                    ) {
                    PlayerAppearanceProvider(
                        dark = playerIsDark,
                        artworkColor = playerBackdropEntry?.representativeColor,
                        eInk = eInkMode,
                    ) {
                        PlayerBackdrop(
                            artwork = track.artwork,
                            isVisible = expanded,
                            playing = state.positionAdvancing,
                            motionEnabled = playerMotionEnabled,
                            entry = playerBackdropEntry,
                            onEntryReady = { playerBackdropEntry = it },
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                alpha = backdropAlpha()
                            },
                        )
                        // 只提前退出正文；背景始终由根面板承接，不能让底页从播放器内部透出。
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            alpha = playerMotionPhase(progress(), PlayerContentFadeStart, 1f)
                        }) {
                            FullPlayerPageContent(
                                state = state,
                                sampledPosition = sampledPosition,
                                immersive = immersive,
                                onImmersiveChange = { immersive = it },
                                onCollapse = { backState.transitionTo(scope, PlayerSheetTransition.Collapse) {
                                    verticalPagerState.scrollToPage(0)
                                    sheetState.animateTo(PlayerSheetAnchor.Collapsed, PlayerPageSnapSpec)
                                } },
                                lyricPosition = lyricPosition,
                                motionEnabled = playerMotionEnabled && expanded && (twoPanes || verticalPagerState.currentPage == 0),
                                queueMotionEnabled = playerMotionEnabled && expanded,
                                lyricFrame = lyricFrameState,
                                lyricLines = lyricLines,
                                isCollapsed = collapsed,
                                isVisible = expanded && (twoPanes || verticalPagerState.currentPage == 0),
                                queuePagerState = verticalPagerState,
                                onOpenQueue = {
                                    queueReturnTarget = PlayerSheetAnchor.Expanded
                                    backState.transitionTo(scope, PlayerSheetTransition.OpenQueue) {
                                        verticalPagerState.animateScrollToPage(1, animationSpec = PlayerPageSnapSpec)
                                    }
                                },
                                onCloseQueue = handleBack,
                                onArtworkPositioned = { child ->
                                    val parent = sheetCoordinates
                                    if (parent != null && parent.isAttached && child.isAttached && pageShowsCover) {
                                        val rect = parent.localBoundingBoxOf(child, clipBounds = false)
                                        if (rect.width > 0f && rect.height > 0f && rect.left >= 0f && rect.right <= screenWidthPx + 1f && rect.bottom <= screenHeightPx + 1f) {
                                            fullBounds = rect
                                        }
                                    }
                                },
                                artworkAlpha = artworkAlpha,
                                playerArtwork = playerArtwork,
                                coverStyle = playerCoverStyle,
                                artworkRotation = vinylRotation,
                                tonearmAngle = tonearmAngle,
                                recordChange = recordChange,
                                onPageVisualChanged = { cover, light, blocksCollapse -> pageShowsCover = cover; pageIsLight = light; pageBlocksCollapse = blocksCollapse },
                                onSeek = onSeek,
                                onPrevious = onPrevious,
                                onNext = onNext,
                            )
                        }
                    }
                    }
                    val miniTrackModifier = Modifier
                        .graphicsLayer { translationX = swipeOffset.floatValue }
                        .testTag("player-mini-track")
                        .clickable(
                            enabled = showMini,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            expandPlayer()
                        }
                        .pointerInput(track.uid, showMini, maxSwipePx, swipeThresholdPx) {
                            if (!showMini) return@pointerInput
                            coroutineScope {
                                var resetJob: Job? = null
                                val velocityTracker = VelocityTracker()
                                fun resetSwipe() {
                                    resetJob?.cancel()
                                    resetJob = launch {
                                        animate(
                                            initialValue = swipeOffset.floatValue,
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
                                        ) { value, _ -> swipeOffset.floatValue = value }
                                    }
                                }
                                try {
                                    detectDragGestures(
                                        orientationLock = Orientation.Horizontal,
                                        onDragStart = { down, _, _ ->
                                            resetJob?.cancel()
                                            velocityTracker.resetTracking()
                                            velocityTracker.addPointerInputChange(down)
                                        },
                                        onDrag = { change, delta ->
                                            velocityTracker.addPointerInputChange(change)
                                            swipeOffset.floatValue = (swipeOffset.floatValue + delta.x)
                                                .coerceIn(-maxSwipePx, maxSwipePx)
                                        },
                                        onDragEnd = { up ->
                                            velocityTracker.addPointerInputChange(up)
                                            val velocity = velocityTracker.calculateVelocity().x
                                            val offset = swipeOffset.floatValue
                                            when {
                                                velocity < -600f || offset < -swipeThresholdPx -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    currentNext()
                                                }
                                                velocity > 600f || offset > swipeThresholdPx -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    currentPrevious()
                                                }
                                            }
                                            resetSwipe()
                                        },
                                        // 取消不是零速度松手；只能复位，不能按位移阈值切歌。
                                        onDragCancel = { resetSwipe() },
                                    )
                                } finally {
                                    // 切歌、迷你条退场或卸载同时终止手势与回弹。
                                    resetJob?.cancel()
                                    swipeOffset.floatValue = 0f
                                }
                            }
                        }
                    val miniTrackArtwork: @Composable (Dp, Int, Modifier) -> Unit = { artworkSize, cornerRadius, extraModifier ->
                        NowPlayingArtwork(
                            style = playerCoverStyle,
                            artwork = playerArtwork,
                            rotationDegrees = vinylRotation,
                            motionEnabled = playerMotionEnabled,
                            modifier = Modifier
                                .size(artworkSize)
                                .then(extraModifier)
                                .onGloballyPositioned { child ->
                                    val parent = sheetCoordinates
                                    if (parent != null && parent.isAttached && child.isAttached) {
                                        miniBounds = parent.localBoundingBoxOf(child, clipBounds = false)
                                            .translate(Offset(-swipeOffset.floatValue, 0f))
                                    }
                                }
                                .graphicsLayer { alpha = if (morphing()) 0f else 1f },
                            cornerRadius = cornerRadius,
                        )
                    }
                    val miniSubtitle: @Composable (Modifier) -> Unit = { subtitleModifier ->
                        if (playbackStatus == null && currentLine != null) {
                            TimedLyricText(
                                line = currentLine,
                                position = lyricPosition,
                                active = lyricFrame.focusIndex in lyricFrame.activeIndices,
                                color = miniColors.onSurfaceVariant,
                                style = LocalTextStyle.current.copy(fontSize = 12.sp),
                                modifier = subtitleModifier,
                                maxLines = 1,
                                inactiveAlpha = 1f,
                            )
                        } else {
                            Text(
                                text = subtitleText,
                                fontSize = 12.sp,
                                color = miniColors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = subtitleModifier,
                            )
                        }
                    }
                    val openMiniQueue = {
                        queueReturnTarget = PlayerSheetAnchor.Collapsed
                        backState.transitionTo(scope, PlayerSheetTransition.OpenQueue) {
                            verticalPagerState.scrollToPage(1)
                            sheetState.animateTo(PlayerSheetAnchor.Expanded, PlayerPageSnapSpec)
                        }
                    }
                    if (desktopMiniBar) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart)
                                .zIndex(if (showMini) 1f else -1f)
                                .then(if (showMini) Modifier else Modifier.clearAndSetSemantics {})
                                .graphicsLayer { alpha = 1f - playerMotionPhase(progress(), 0f, PlayerMiniFadeEnd) }
                                .testTag("player-mini-bar")
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .navigationBarsPadding(),
                        ) {
                            HorizontalDivider(
                                thickness = if (eInkMode) 1.dp else 0.8.dp,
                                color = if (eInkMode) MeloraAppearance.cardBorderColor else miniColors.outlineVariant.copy(alpha = 0.45f),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(DesktopMiniPlayerHeight)
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                // 左段：封面 + 歌名 · 歌手 + 实时逐字歌词
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(miniTrackModifier),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val desktopArtworkBorder = if (playerCoverStyle == PlayerCoverStyle.Default) {
                                        Modifier.border(
                                            0.5.dp,
                                            miniColors.outlineVariant.copy(alpha = 0.25f),
                                            RoundedCornerShape(10.dp),
                                        )
                                    } else {
                                        Modifier
                                    }
                                    miniTrackArtwork(48.dp, 10, desktopArtworkBorder)
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp),
                                    ) {
                                        Text(
                                            text = buildAnnotatedString {
                                                withStyle(SpanStyle(color = miniColors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)) {
                                                    append(track.title)
                                                }
                                                if (track.artist.isNotBlank()) {
                                                    withStyle(SpanStyle(color = miniColors.onSurfaceVariant, fontWeight = FontWeight.Normal, fontSize = 12.5.sp)) {
                                                        append(" · ")
                                                        append(track.artist)
                                                    }
                                                }
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        miniSubtitle(Modifier.fillMaxWidth().padding(top = 3.dp))
                                    }
                                }

                                // 中段：视觉居中上一首 · 播放/暂停 · 下一首 + 可拖细进度条
                                Column(
                                    modifier = Modifier.width(desktopCenterControlsWidth),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(enabled = showMini, onClick = onPrevious, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                Icons.Rounded.SkipPrevious,
                                                contentDescription = "上一首",
                                                tint = miniColors.onSurface.copy(alpha = 0.78f),
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                        IconButton(enabled = showMini, onClick = { PlaybackController.toggle() }, modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = if (state.playing) "暂停" else "播放",
                                                tint = miniColors.onSurface,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                        IconButton(enabled = showMini, onClick = onNext, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                Icons.Rounded.SkipNext,
                                                contentDescription = "下一首",
                                                tint = miniColors.onSurface.copy(alpha = 0.78f),
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                    }
                                    PlayerSeekBar(
                                        trackUid = track.uid,
                                        position = sampledPosition,
                                        durationMs = state.durationMs,
                                        onSeek = onSeek,
                                        activeColor = miniColors.onSurface.copy(alpha = 0.88f),
                                        inactiveColor = miniColors.onSurface.copy(alpha = 0.12f),
                                        clockColor = miniColors.onSurfaceVariant,
                                        clockSize = 11.sp,
                                        enabled = showMini,
                                        inlineClocks = true,
                                    )
                                }

                                // 右段：克制留白的收藏、循环模式与播放队列
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
                                    val favoriteSong = remember(track.uid, track.raw) { track.raw?.let(OnlineSong::from) }
                                    val isFavorite = favoriteSong != null && favoriteSong.uid in favoriteUids
                                    if (favoriteSong != null) {
                                        IconButton(
                                            enabled = showMini,
                                            onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                submitLibraryWrite { UserLibrary.toggleFavorite(favoriteSong) }
                                            },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                                tint = if (isFavorite) AccentRed else miniColors.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    IconButton(enabled = showMini, onClick = { PlaybackController.cycleMode() }, modifier = Modifier.size(36.dp)) {
                                        Icon(
                                            imageVector = when (state.mode) {
                                                PlayMode.List -> Icons.Rounded.Repeat
                                                PlayMode.Single -> Icons.Rounded.RepeatOne
                                                PlayMode.Shuffle -> Icons.Rounded.Shuffle
                                            },
                                            contentDescription = "循环模式",
                                            tint = miniColors.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                    IconButton(enabled = showMini, onClick = openMiniQueue, modifier = Modifier.size(36.dp)) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = "播放队列",
                                            tint = miniColors.onSurface,
                                            modifier = Modifier.size(23.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().align(Alignment.TopStart)
                                // 始终测量迷你端点，旋转后直接恢复展开态也能连续收起。
                                .zIndex(if (showMini) 1f else -1f)
                                .then(if (showMini) Modifier else Modifier.clearAndSetSemantics {})
                                .graphicsLayer { alpha = 1f - playerMotionPhase(progress(), 0f, PlayerMiniFadeEnd) }
                                .testTag("player-mini-bar")
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .navigationBarsPadding(),
                        ) {
                            HorizontalDivider(
                                thickness = if (eInkMode) 1.dp else Dp.Hairline,
                                color = if (eInkMode) MeloraAppearance.cardBorderColor else miniColors.outlineVariant.copy(alpha = 0.4f),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(MiniPlayerContentHeight)
                                    .padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 封面 + 双行信息：整块左右滑切歌、点击展开全屏播放页
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(miniTrackModifier)
                                        .padding(end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    miniTrackArtwork(46.dp, 10, Modifier)
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp),
                                    ) {
                                        Text(
                                            text = listOf(track.title, track.artist)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" - "),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = miniColors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            miniSubtitle(Modifier.weight(1f))
                                            Spacer(Modifier.width(8.dp))
                                            MiniPlayerClock(sampledPosition, state.durationMs, miniColors.onSurfaceVariant)
                                        }
                                    }
                                }
                                IconButton(enabled = showMini, onClick = { PlaybackController.toggle() }) {
                                    Icon(
                                        imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (state.playing) "暂停" else "播放",
                                        tint = miniColors.onSurface,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                                IconButton(enabled = showMini, onClick = openMiniQueue) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = "播放队列",
                                        tint = miniColors.onSurface,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                        }
                    }
                    // 两端让出绘制，过渡层沿测得的位置绘制同一份封面。
                    val full = fullBounds
                    val mini = miniBounds
                    if (full != null && mini != null) {
                        val fullSide = with(density) { full.width.toDp() }
                        val sharedBounds by remember(mini, full, swipeOffset, progress) {
                            derivedStateOf { playerArtworkBounds(mini.translate(Offset(swipeOffset.floatValue, 0f)), full, progress()) }
                        }
                        fun scaleFor(rect: Rect) = (rect.width / full.width).coerceAtLeast(0.001f)
                        NowPlayingArtwork(
                            style = playerCoverStyle,
                            artwork = playerArtwork,
                            rotationDegrees = vinylRotation, motionEnabled = playerMotionEnabled,
                            tonearmAngle = tonearmAngle,
                            tonearmAlpha = progress,
                            recordChange = recordChange,
                            frameCornerRadius = { (10.dp + 8.dp * progress()) / scaleFor(sharedBounds) },
                            modifier = Modifier.size(fullSide).zIndex(2f).graphicsLayer {
                                val rect = sharedBounds
                                val scale = scaleFor(rect)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = rect.left
                                translationY = rect.top
                                scaleX = scale
                                scaleY = scale
                                alpha = if (morphing()) 1f else 0f
                            },
                            cornerRadius = 0,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerClock(position: State<Long>, durationMs: Long, color: Color) {
    val current = formatClock(position.value, padMinutes = false)
    Text(
        text = if (durationMs > 0) "$current / ${formatClock(durationMs, padMinutes = false)}" else current,
        fontSize = 11.sp,
        color = color,
        maxLines = 1,
    )
}
