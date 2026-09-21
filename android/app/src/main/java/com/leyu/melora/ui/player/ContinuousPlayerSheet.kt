package com.leyu.melora.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.theme.SystemBarsAppearance
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun formatClockMs(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinuousPlayerSheet(state: PlayerUiState, modifier: Modifier = Modifier) {
    var lastTrack by remember { mutableStateOf<UiTrack?>(null) }
    LaunchedEffect(state.current) { if (state.current != null) lastTrack = state.current }
    val track = state.current ?: lastTrack ?: return
    // loading 保留上一幅环境背景；loader 统一发布新图+色调，失败/无封面才清空。
    var playerBackdropEntry by remember { mutableStateOf<PlayerBackdropCacheEntry?>(null) }
    val playerThemeMode by MeloraSettings.playerThemeMode.collectAsStateWithLifecycle()
    val keepScreenAwake by MeloraSettings.keepScreenAwake.collectAsStateWithLifecycle()
    val playerCoverStyle by MeloraSettings.playerCoverStyle.collectAsStateWithLifecycle()
    val vinylRotation = rememberVinylRotation(state.playing, playerCoverStyle)
    val playerIsDark = playerThemeMode.isDark(isSystemInDarkTheme())
    // 迷你条属于普通页面，全屏页面主题不能改变其底色、前景和系统栏。
    val miniColors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val playerMotionEnabled = rememberPlayerMotionEnabled(isVisible = true)
    val playerLyric by PlaybackController.lyric.collectAsStateWithLifecycle()
    val lyricLines = playerLyricLines(state.current?.uid, playerLyric)
    val lyricPosition = rememberLyricPosition(state, visible = lyricLines.isNotEmpty())
    val displayLyricLines = remember(state.current, lyricLines) { fullPlayerLyricsOrFallback(state.current, lyricLines) }
    val lyricFrameState = rememberLyricFrame(displayLyricLines, lyricPosition)
    val lyricFrame by lyricFrameState
    val sheetState = rememberSaveable(saver = AnchoredDraggableState.Saver<PlayerSheetAnchor>()) {
        AnchoredDraggableState(PlayerSheetAnchor.Collapsed)
    }
    val swipeOffset = remember { Animatable(0f) }
    val verticalPagerState = rememberPagerState(pageCount = { 2 })
    var pageShowsCover by remember { mutableStateOf(true) }
    var pageShowsLyrics by remember { mutableStateOf(false) }
    var pageIsLight by remember(playerIsDark) { mutableStateOf(!playerIsDark) }
    var sheetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    var fullBounds by remember { mutableStateOf<Rect?>(null) }
    val fluidSpec = remember { spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMedium) }
    val decay = rememberSplineBasedDecay<Float>()
    val fling = AnchoredDraggableDefaults.flingBehavior(
        state = sheetState,
        positionalThreshold = { distance -> distance * 0.35f },
        animationSpec = fluidSpec,
    )

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val twoPanes = playerUsesTwoPanes(maxWidth.value, maxHeight.value)
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
        val playbackPage = remember(verticalPagerState, twoPanes) {
            { twoPanes || (verticalPagerState.currentPage == 0 && abs(verticalPagerState.currentPageOffsetFraction) < 0.001f) }
        }
        val expanded by remember(progress) { derivedStateOf { progress() >= 0.99f } }
        val collapsed by remember(progress, sheetState) {
            derivedStateOf { playerSheetIsCollapsed(sheetState.settledValue, sheetState.targetValue, progress()) }
        }
        val showMini by remember(progress) { derivedStateOf { progress() < 0.22f } }
        val canCollapse = remember(playbackPage) { { playbackPage() && !pageShowsLyrics } }
        val canDrag by remember(offset, canCollapse) { derivedStateOf { offset() > 0.5f || canCollapse() } }
        val morphing = remember(progress, playbackPage) {
            {
                val p = progress()
                p > 0f && p < 1f && playbackPage() && pageShowsCover && miniBounds != null && fullBounds != null
            }
        }
        val artworkAlpha = remember(morphing) { { if (morphing()) 0f else 1f } }

        val nestedScroll = remember(sheetState, travel, velocityThreshold, fluidSpec, decay, canCollapse, offset) {
            object : NestedScrollConnection {
                suspend fun settle(velocity: Float): Velocity {
                    val target = playerSheetTarget(offset(), travel, velocity, velocityThreshold, sheetState.settledValue)
                    val consumed = sheetState.animateToWithDecay(target, velocity, fluidSpec, decay)
                    return Velocity(0f, consumed)
                }
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && offset() > 0.5f) {
                        Offset(0f, sheetState.dispatchRawDelta(available.y))
                    } else Offset.Zero

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0f && canCollapse()) {
                        Offset(0f, sheetState.dispatchRawDelta(available.y))
                    } else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (offset() > 0.5f && offset() < travel) settle(available.y) else Velocity.Zero

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                    if (canCollapse() && (offset() > 0.5f || available.y > velocityThreshold)) settle(available.y)
                    else Velocity.Zero
            }
        }
        if (expanded && verticalPagerState.currentPage == 1) {
            BackHandler { scope.launch { verticalPagerState.animateScrollToPage(0) } }
        } else if (expanded) {
            BackHandler { scope.launch { sheetState.animateTo(PlayerSheetAnchor.Collapsed, fluidSpec) } }
        }

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
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = playerMotionPhase(progress(), 0.18f, 0.88f) }) {
                    FullPlayerPageContent(
                        state = state,
                        lyricPosition = lyricPosition,
                        motionEnabled = playerMotionEnabled && expanded && (twoPanes || verticalPagerState.currentPage == 0),
                        lyricFrame = lyricFrameState,
                        lyricLines = lyricLines,
                        isCollapsed = collapsed,
                        isVisible = expanded && (twoPanes || verticalPagerState.currentPage == 0),
                        queuePagerState = verticalPagerState,
                        onOpenQueue = { scope.launch { verticalPagerState.animateScrollToPage(1) } },
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
                        onPageVisualChanged = { cover, light, lyrics -> pageShowsCover = cover; pageIsLight = light; pageShowsLyrics = lyrics },
                    )
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
                                    scope.launch { sheetState.animateTo(PlayerSheetAnchor.Expanded, fluidSpec) }
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
                                rotationDegrees = vinylRotation,
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
                                            modifier = Modifier.weight(1f), maxLines = 1, glow = false,
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
                            scope.launch {
                                verticalPagerState.scrollToPage(1)
                                sheetState.animateTo(PlayerSheetAnchor.Expanded, fluidSpec)
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
                    rotationDegrees = vinylRotation,
                    modifier = Modifier.size(fullSide).zIndex(2f).graphicsLayer {
                        val p = progress()
                        val rect = playerArtworkBounds(mini.translate(Offset(swipeOffset.value, 0f)), full, p)
                        val scale = (rect.width / full.width).coerceAtLeast(0.001f)
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = rect.left
                        translationY = rect.top
                        scaleX = scale
                        scaleY = scale
                        shape = when (nowPlayingArtworkShape(playerCoverStyle)) {
                            NowPlayingArtworkShape.Rounded -> RoundedCornerShape((10.dp + 8.dp * p) / scale)
                            NowPlayingArtworkShape.Circle -> CircleShape
                        }
                        clip = true
                        alpha = if (morphing()) 1f else 0f
                    },
                    cornerRadius = 0,
                )
            }
        }
    }
}
