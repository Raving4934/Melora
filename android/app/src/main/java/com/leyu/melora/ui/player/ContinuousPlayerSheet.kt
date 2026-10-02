package com.leyu.melora.ui.player

import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.animateToWithDecay
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.ui.common.LocalPageActive
import com.leyu.melora.ui.theme.SystemBarsAppearance
import com.leyu.melora.ui.theme.LocalForceHideStatusBar
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun formatClockMs(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinuousPlayerSheet(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
    backState: PlayerSheetBackState = rememberPlayerSheetBackState(),
) {
    // 迷你条、过渡封面和全屏共用当前曲目；空队列不能保留已失效的旧封面。
    val track = state.current ?: return
    // loading 保留上一幅环境背景；loader 统一发布新图+色调，失败/无封面才清空。
    var playerBackdropEntry by remember { mutableStateOf<PlayerBackdropCacheEntry?>(null) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val rootThemeMode by MeloraSettings.themeMode.collectAsStateWithLifecycle()
    val playerThemeMode by MeloraSettings.playerThemeMode.collectAsStateWithLifecycle()
    val keepScreenAwake by MeloraSettings.keepScreenAwake.collectAsStateWithLifecycle()
    val playerCoverStyle by MeloraSettings.playerCoverStyle.collectAsStateWithLifecycle()
    val vinylRotation = rememberVinylRotation(state.playing, playerCoverStyle)
    val playerIsDark = playerThemeMode.isDark(rootThemeMode.isDark(isSystemInDarkTheme()))
    // 迷你条属于普通页面，全屏页面主题不能改变其底色、前景和系统栏。
    val miniColors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val playerMotionEnabled = rememberPlayerMotionEnabled(isVisible = true)
    val artworkRoundness = animateFloatAsState(
        if (nowPlayingArtworkShape(playerCoverStyle) == NowPlayingArtworkShape.Circle) 1f else 0f,
        if (playerMotionEnabled) PlayerCoverMorphSpec else snap(), label = "sharedCoverRoundness",
    )
    val playerLyric by PlaybackController.lyric.collectAsStateWithLifecycle()
    val lyricLines = playerLyricLines(state.current?.uid, playerLyric)
    val lyricPosition = rememberLyricPosition(state, visible = lyricLines.isNotEmpty())
    val displayLyricLines = remember(state.current, lyricLines) { fullPlayerLyricsOrFallback(state.current, lyricLines) }
    val lyricFrameState = rememberLyricFrame(displayLyricLines, lyricPosition)
    val lyricFrame by lyricFrameState
    val sheetState = backState.sheet
    val swipeOffset = remember { Animatable(0f) }
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
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val miniBarContentHeight = 64.dp
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val travel = with(density) { (maxHeight - miniBarContentHeight - bottomInset).toPx() }.coerceAtLeast(1f)
        val topInset = WindowInsets.statusBars.getTop(density).toFloat()
        val velocityThreshold = with(density) { 125.dp.toPx() }
        SideEffect {
            sheetState.updateAnchors(
                DraggableAnchors {
                    PlayerSheetAnchor.Expanded at 0f
                    PlayerSheetAnchor.Collapsed at travel
                },
                newTarget = sheetState.targetValue,
            )
        }
        // Read animated values in drawing/gesture lambdas, not throughout the player composition.
        val offset = remember(sheetState, travel) {
            { sheetState.offset.takeIf(Float::isFinite)?.coerceIn(0f, travel) ?: travel }
        }
        val progress = remember(offset, travel) { { playerSheetProgress(offset(), travel) } }
        val playbackPage = remember(verticalPagerState, twoPanes, expandedLayout) {
            { (twoPanes && !expandedLayout) || (verticalPagerState.currentPage == 0 && abs(verticalPagerState.currentPageOffsetFraction) < 0.001f) }
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

        val baseIsLight = miniColors.surface.luminance() > 0.5f
        val darkStatusIcons by remember(offset, topInset, baseIsLight) {
            derivedStateOf { if (offset() > topInset * 0.5f) baseIsLight else pageIsLight }
        }
        val darkNavIcons by remember(progress, baseIsLight) {
            derivedStateOf { if (progress() < 0.12f) baseIsLight else pageIsLight }
        }
        SystemBarsAppearance(
            darkStatusIcons = darkStatusIcons,
            darkNavigationIcons = darkNavIcons,
            keepScreenAwake = keepScreenAwake && expanded,
            forceHideStatusBar = immersive && expanded,
        )

        val maxSwipePx = with(density) { 88.dp.toPx() }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }
        val swipeDragState = rememberDraggableState { delta ->
            scope.launch { swipeOffset.snapTo((swipeOffset.value + delta).coerceIn(-maxSwipePx, maxSwipePx)) }
        }
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
                        val rounding = playerMotionPhase(p, 0f, 0.04f) * (1f - playerMotionPhase(p, 0.85f, 1f))
                        shape = RoundedCornerShape(topStart = 22.dp * rounding, topEnd = 22.dp * rounding)
                        clip = true
                    }
                    // 端点坐标位于位移层内部，不能把容器 translationY 再算入封面轨迹。
                    .onPlaced { sheetCoordinates = it }
                    .nestedScroll(nestedScroll)
                    .anchoredDraggable(sheetState, Orientation.Vertical, enabled = canDrag, flingBehavior = fling)
                    .background(miniColors.surface),
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
                    ) {
                        PlayerBackdrop(
                            artwork = track.artwork,
                            isVisible = expanded,
                            playing = state.positionAdvancing,
                            motionEnabled = playerMotionEnabled,
                            entry = playerBackdropEntry,
                            onEntryReady = { playerBackdropEntry = it },
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                alpha = playerMotionPhase(progress(), 0f, PlayerMiniFadeEnd)
                            },
                        )
                        // 只提前退出正文；背景始终由根面板承接，不能让底页从播放器内部透出。
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            alpha = playerMotionPhase(progress(), PlayerContentFadeStart, 1f)
                        }) {
                            FullPlayerPageContent(
                                state = state,
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
                                coverStyle = playerCoverStyle,
                                artworkRotation = vinylRotation,
                                onPageVisualChanged = { cover, light, blocksCollapse -> pageShowsCover = cover; pageIsLight = light; pageBlocksCollapse = blocksCollapse },
                            )
                        }
                    }
                    }
                    Surface(
                        Modifier.fillMaxWidth().align(Alignment.TopStart)
                            // 始终测量迷你端点，旋转后直接恢复展开态也能连续收起。
                            .zIndex(if (showMini) 1f else -1f)
                            .then(if (showMini) Modifier else Modifier.clearAndSetSemantics {})
                            .graphicsLayer { alpha = 1f - playerMotionPhase(progress(), 0f, PlayerMiniFadeEnd) },
                        color = Color.Transparent,
                    ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                    .navigationBarsPadding(),
                            ) {
                            HorizontalDivider(color = miniColors.outlineVariant.copy(alpha = 0.4f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(miniBarContentHeight)
                                    .padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 封面 + 双行信息：整块左右滑切歌、点击展开全屏播放页
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer { translationX = swipeOffset.value }
                                        .clickable(
                                            enabled = showMini,
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                        ) {
                                            backState.transitionTo(scope, PlayerSheetTransition.Expand) {
                                                verticalPagerState.scrollToPage(0)
                                                sheetState.animateTo(PlayerSheetAnchor.Expanded, PlayerPageSnapSpec)
                                            }
                                        }
                                        .draggable(
                                            enabled = showMini,
                                            state = swipeDragState,
                                            orientation = Orientation.Horizontal,
                                            onDragStopped = { velocity ->
                                                val offset = swipeOffset.value
                                                when {
                                                    velocity < -600f || offset < -swipeThresholdPx -> {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        PlaybackController.next()
                                                    }
                                                    velocity > 600f || offset > swipeThresholdPx -> {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        PlaybackController.previous()
                                                    }
                                                }
                                                scope.launch {
                                                    swipeOffset.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
                                                    )
                                                }
                                            },
                                        )
                                        .padding(end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NowPlayingArtwork(
                                        url = track.artwork,
                                        seed = track.uid,
                                        style = playerCoverStyle,
                                        rotationDegrees = vinylRotation, motionEnabled = playerMotionEnabled,
                                        modifier = Modifier.size(46.dp)
                                            .onGloballyPositioned { child ->
                                                val parent = sheetCoordinates
                                                if (parent != null && parent.isAttached && child.isAttached) {
                                                    miniBounds = parent.localBoundingBoxOf(child, clipBounds = false)
                                                        .translate(Offset(-swipeOffset.value, 0f))
                                                }
                                            }
                                            .graphicsLayer { alpha = if (morphing()) 0f else 1f },
                                        cornerRadius = 10,
                                    )
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
                                            if (playbackStatus == null && currentLine != null) {
                                                TimedLyricText(
                                                    line = currentLine, position = lyricPosition,
                                                    active = lyricFrame.focusIndex in lyricFrame.activeIndices,
                                                    color = miniColors.onSurfaceVariant,
                                                    style = LocalTextStyle.current.copy(fontSize = 12.sp),
                                                    modifier = Modifier.weight(1f), maxLines = 1,
                                                    inactiveAlpha = 1f,
                                                )
                                            } else Text(
                                                text = subtitleText, fontSize = 12.sp,
                                                color = miniColors.onSurfaceVariant, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "${formatClockMs(state.positionMs)} / ${formatClockMs(state.durationMs)}",
                                                fontSize = 11.sp,
                                                color = miniColors.onSurfaceVariant.copy(alpha = 0.8f),
                                            )
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
                                IconButton(enabled = showMini, onClick = {
                                    queueReturnTarget = PlayerSheetAnchor.Collapsed
                                    backState.transitionTo(scope, PlayerSheetTransition.OpenQueue) {
                                        verticalPagerState.scrollToPage(1)
                                        sheetState.animateTo(PlayerSheetAnchor.Expanded, PlayerPageSnapSpec)
                                    }
                                }) {
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
                    // A single visible artwork layer connects the two measured endpoints.
                    // Endpoint images stay attached/cache-warm, but are hidden during the morph.
                    val full = fullBounds
                    val mini = miniBounds
                    if (full != null && mini != null) {
                        val fullSide = with(density) { full.width.toDp() }
                        NowPlayingArtwork(
                            url = track.artwork,
                            seed = track.uid,
                            style = playerCoverStyle,
                            rotationDegrees = vinylRotation, motionEnabled = playerMotionEnabled,
                            modifier = Modifier.size(fullSide).zIndex(2f).graphicsLayer {
                                val p = progress()
                                val rect = playerArtworkBounds(mini.translate(Offset(swipeOffset.value, 0f)), full, p)
                                val scale = (rect.width / full.width).coerceAtLeast(0.001f)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = rect.left
                                translationY = rect.top
                                scaleX = scale
                                scaleY = scale
                                val rounded = (10.dp + 8.dp * p) / scale
                                shape = RoundedCornerShape(rounded * (1f - artworkRoundness.value) +
                                    (size.minDimension / (2f * this.density)).dp * artworkRoundness.value)
                                clip = true
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
