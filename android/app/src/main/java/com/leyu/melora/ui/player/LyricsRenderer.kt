package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricsUiConfig

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.leyu.melora.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/** 同一时钟供三个展示入口读取；时间只在 draw 或时间线边界派生中消费。 */
@Composable
internal fun rememberLyricPosition(state: PlayerUiState, visible: Boolean): State<Long> {
    val latest by rememberUpdatedState(state)
    val position = remember(state.current?.uid) { mutableLongStateOf(lyricPositionAt(state, SystemClock.elapsedRealtime())) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.current?.uid, state.positionSampleRealtimeMs, state.positionAdvancing, visible) {
        // 采样抵达界面时已过时；重锚与逐帧绘制必须使用同一时刻，不能先回写旧位置再追赶。
        position.longValue = lyricPositionAt(state, SystemClock.elapsedRealtime())
        if (!visible || !state.positionAdvancing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                withFrameNanos { position.longValue = lyricPositionAt(latest, SystemClock.elapsedRealtime()) }
            }
        }
    }
    return position
}

@Composable
internal fun rememberLyricFrame(lines: List<LyricLine>, position: State<Long>): State<LyricFrame> {
    val timeline = remember(lines) { LyricTimeline(lines) }
    return remember(timeline, position) { derivedStateOf { timeline.at(position.value) } }
}

/** 文字仍由 Text 提供测量及无障碍语义；逐词高亮仅重绘，不每帧布局/重组。 */
@Composable
internal fun TimedLyricText(
    line: LyricLine,
    position: State<Long>,
    active: Boolean,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    mutedColor: Color? = null,
    inactiveAlpha: Float = 0.36f,
    marquee: Boolean = false,
    centerFraction: State<Float>? = null,
    liftEnabled: Boolean = false,
) {
    var layout by remember(line.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    val runs = remember(line, layout) { layout?.let { wordRuns(line, it) }.orEmpty() }
    val wordLiftActive = liftEnabled && active && runs.isNotEmpty() &&
        line.words.sumOf { it.text.length } == line.text.length
    val alignment = if (centerFraction != null) Modifier.drawWithContent {
        // 在marquee外侧按视口宽度移动字形；不改变整行点击区域和无障碍边界。
        val text = layout
        val shift = if (text != null && text.lineCount > 0) {
            val left = text.getLineLeft(0)
            val width = text.getLineRight(0) - left
            if (width < size.width) ((size.width - width) * 0.5f - left) * centerFraction.value else 0f
        } else 0f
        translate(left = shift) { this@drawWithContent.drawContent() }
    } else Modifier
    Text(
        text = line.text,
        style = style,
        color = when {
            active && line.words.isEmpty() -> color
            mutedColor != null -> mutedColor
            else -> color.copy(alpha = if (active) 0.4f else inactiveAlpha)
        },
        maxLines = maxLines,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.then(alignment).then(if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
            initialDelayMillis = 1000, repeatDelayMillis = 1200, velocity = 32.dp) else Modifier)
            .drawWithContent {
                val currentLayout = layout
                if (wordLiftActive && currentLayout != null) {
                    val baseMuted = mutedColor ?: color.copy(alpha = 0.4f)
                    val maxLiftPx = (style.fontSize.value * 0.10f).coerceIn(1.6f, 3.0f).dp.toPx()
                    drawTimedWords(currentLayout, runs, position.value, color, mutedColor = baseMuted, maxLiftPx = maxLiftPx)
                } else {
                    drawContent()
                    if (active && runs.isNotEmpty()) currentLayout?.let { drawTimedWords(it, runs, position.value, color) }
                }
            },
    )
}

/** 逐字微浮起曲线：唱起瞬间 100-240ms 快速柔和上浮，延音期停在最高点，唱完 420ms 平缓回落。 */
internal fun lyricWordLift(word: LyricWord, positionMs: Long): Float {
    if (word.endMs <= word.startMs || positionMs <= word.startMs) return 0f
    if (positionMs < word.endMs) {
        val riseMs = (word.endMs - word.startMs).coerceIn(100L, 240L)
        val t = ((positionMs - word.startMs).toFloat() / riseMs).coerceIn(0f, 1f)
        val inv = 1f - t
        return 1f - inv * inv * inv
    }
    val settleT = ((positionMs - word.endMs).toFloat() / 420f).coerceIn(0f, 1f)
    val smooth = settleT * settleT * (3f - 2f * settleT)
    return 1f - smooth
}

/** 单色只在字形内填充；透明端保持同一 RGB，避免黑色透明端混出脏边。 */
internal fun DrawScope.drawTimedWords(
    text: TextLayoutResult,
    runs: List<Pair<LyricWord, List<GlyphRun>>>,
    time: Long,
    color: Color,
    mutedColor: Color = Color.Unspecified,
    maxLiftPx: Float = 0f,
) {
    fun drawGradient(rect: Rect, rtl: Boolean, filled: Float) {
        val edge = if (rtl) rect.right - filled else rect.left + filled
        val feather = minOf(8.dp.toPx(), rect.width * 0.2f)
        val brush = if (rtl) Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color), edge - feather, edge)
            else Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)), edge, edge + feather)
        clipRect(rect.left, rect.top, rect.right, rect.bottom) { drawText(text, brush = brush, alpha = 1f) }
    }
    val lifted = maxLiftPx > 0f && mutedColor != Color.Unspecified
    var pending: GlyphRun? = null
    var left = 0f
    var right = 0f
    var pendingColor = color
    var pendingY = 0f

    fun flush() {
        val span = pending ?: return
        val rect = span.bounds
        if (lifted) {
            translate(top = pendingY) {
                clipRect(left, rect.top, right, rect.bottom) { drawText(text, color = pendingColor) }
            }
        } else {
            drawGradient(Rect(left, rect.top, right, rect.bottom), span.rtl, right - left)
        }
        pending = null
    }

    for ((word, segments) in runs) {
        val progress = lyricWordProgress(word, time)
        if (progress == 0f && !lifted) continue
        val y = if (lifted) -maxLiftPx * lyricWordLift(word, time) else 0f
        var remaining = segments.sumOf { it.bounds.width.toDouble() }.toFloat() * progress
        for (segment in segments) {
            val rect = segment.bounds
            val filled = remaining.coerceIn(0f, rect.width)
            remaining -= rect.width
            if (filled == 0f && !lifted) continue
            if (filled > 0f && filled < rect.width) {
                flush()
                translate(top = y) {
                    if (lifted) clipRect(rect.left, rect.top, rect.right, rect.bottom) { drawText(text, color = mutedColor) }
                    drawGradient(rect, segment.rtl, filled)
                }
                continue
            }

            // 仅合并同色、同位移且严密相接的字形，换行、方向切换和重叠连字保留原绘制顺序。
            val ink = if (filled == 0f) mutedColor else color
            val previous = pending?.bounds
            if (previous != null && pendingColor == ink && pendingY == y && pending?.rtl == segment.rtl &&
                previous.top == rect.top && previous.bottom == rect.bottom &&
                (right == rect.left || rect.right == left)) {
                left = minOf(left, rect.left)
                right = maxOf(right, rect.right)
            } else {
                flush()
                pending = segment
                left = rect.left
                right = rect.right
                pendingColor = ink
                pendingY = y
            }
        }
    }
    flush()
}

internal data class GlyphRun(val bounds: Rect, val rtl: Boolean)

/** 按布局中的视觉行及方向合并字形框，保持换行、连字和 RTL 方向；仅布局变化时计算。 */
internal fun wordRuns(line: LyricLine, layout: TextLayoutResult): List<Pair<LyricWord, List<GlyphRun>>> {
    var offset = 0
    return line.words.map { word ->
        val start = offset
        offset += word.text.length
        val segments = mutableListOf<GlyphRun>()
        var visualLine = -1
        for (index in start until offset.coerceAtMost(line.text.length)) {
            if (line.text[index].isLowSurrogate()) continue
            val row = layout.getLineForOffset(index)
            if (row >= layout.lineCount || index >= layout.getLineEnd(row, visibleEnd = true)) continue
            val box = layout.getBoundingBox(index)
            if (box.width <= 0f) continue
            val rtl = layout.getBidiRunDirection(index) == ResolvedTextDirection.Rtl
            val previous = segments.lastOrNull()
            if (previous != null && row == visualLine && previous.rtl == rtl) {
                segments[segments.lastIndex] = GlyphRun(Rect(
                    minOf(previous.bounds.left, box.left), minOf(previous.bounds.top, box.top),
                    maxOf(previous.bounds.right, box.right), maxOf(previous.bounds.bottom, box.bottom),
                ), rtl)
            } else segments.add(GlyphRun(box, rtl))
            visualLine = row
        }
        word to segments
    }
}

private const val IMMERSIVE_LYRICS_FONT_SIZE_SP = 30f
private const val IMMERSIVE_LYRICS_LINE_HEIGHT_MULTIPLIER = 1.22f
private const val IMMERSIVE_LYRICS_SPACING_MULTIPLIER = 0.44f
internal const val ORDINARY_LYRICS_FOCUS_FRACTION = 0.38f
private const val INTERLUDE_MIN_DURATION_MS = 4_000L
private const val PLAIN_LRC_INTERLUDE_MIN_SPAN_MS = 10_000L
private const val PLAIN_LRC_INTERLUDE_LEAD_MS = 5_000L

internal data class LyricInterlude(
    val anchorIndex: Int,
    val beforeFirst: Boolean,
    val startMs: Long,
    val endMs: Long,
)

internal data class InterludeDotVisual(val alpha: Float, val scale: Float)

internal fun lyricInterludeForFrame(lines: List<LyricLine>, frame: LyricFrame): LyricInterlude? {
    if (lines.isEmpty()) return null
    if (frame.focusIndex < 0 && frame.activeIndices.isEmpty() && lines[0].startMs >= INTERLUDE_MIN_DURATION_MS) {
        return LyricInterlude(anchorIndex = 0, beforeFirst = true, startMs = 0L, endMs = lines[0].startMs)
    }
    if (frame.focusIndex !in 0 until lines.lastIndex) return null
    val nextStart = lines[frame.focusIndex + 1].startMs
    if (frame.activeIndices.isEmpty()) {
        val gapStart = (maxOf(0, frame.focusIndex - 4)..frame.focusIndex).mapNotNull { lines[it].endMs }.maxOrNull()
        if (gapStart != null && nextStart - gapStart >= INTERLUDE_MIN_DURATION_MS) {
            return LyricInterlude(anchorIndex = frame.focusIndex, beforeFirst = false, startMs = gapStart, endMs = nextStart)
        }
    }
    val current = lines[frame.focusIndex]
    if (current.endMs == null && nextStart - current.startMs >= PLAIN_LRC_INTERLUDE_MIN_SPAN_MS) {
        return LyricInterlude(
            anchorIndex = frame.focusIndex,
            beforeFirst = false,
            startMs = current.startMs + PLAIN_LRC_INTERLUDE_LEAD_MS,
            endMs = nextStart,
        )
    }
    return null
}

internal fun interludeDotVisuals(
    interlude: LyricInterlude,
    positionMs: Long,
    motionEnabled: Boolean,
): List<InterludeDotVisual>? {
    val duration = interlude.endMs - interlude.startMs
    if (duration <= 0L || positionMs !in interlude.startMs until interlude.endMs) return null
    val elapsed = positionMs - interlude.startMs
    val remaining = interlude.endMs - positionMs
    val fadeMs = minOf(360f, duration * 0.25f)
    val envelope = if (motionEnabled) minOf(elapsed / fadeMs, remaining / fadeMs).coerceIn(0f, 1f) else 1f
    val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    val exitScale = if (motionEnabled && remaining < fadeMs) 0.72f + 0.28f * (remaining / fadeMs) else 1f
    return List(3) { index ->
        val segProgress = ((progress - index / 3f) * 3f).coerceIn(0f, 1f)
        val wave = if (motionEnabled) kotlin.math.sin((elapsed / 1_600.0) * 2.0 * Math.PI - index * 0.75).toFloat() else 0f
        val activeBoost = if (segProgress > 0f && segProgress < 1f) 1f else 0.45f
        val alpha = ((0.22f + 0.64f * segProgress + 0.08f * wave * activeBoost) * envelope).coerceIn(0f, 1f)
        val scale = (0.86f + 0.16f * segProgress + 0.10f * wave * activeBoost) * exitScale
        InterludeDotVisual(alpha = alpha, scale = scale)
    }
}

/**
 * 统一歌词视口上下缓冲，主歌词页和沉浸页共用同一离屏 alpha mask。
 * 调用方只需把它放在视口尺寸 modifier 之后；不要再叠加另一层 DstIn mask。
 */
internal fun Modifier.lyricViewportFade(edgeFraction: Float = 0.12f): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val fade = edgeFraction.coerceIn(0f, 0.45f)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    fade to Color.Black,
                    (1f - fade) to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

private fun immersiveLyricsScale(distance: Int): Float = when (distance) {
    0 -> 1f
    1 -> 0.82f
    else -> 0.72f
}

private fun immersiveLyricsAlpha(distance: Int): Float = when (distance) {
    0 -> 1f
    1 -> 0.76f
    else -> 0.50f
}

internal fun ordinaryLyricsAlpha(distance: Int, past: Boolean): Float = when (distance) {
    0 -> 1f
    1 -> if (past) 0.31f else 0.38f
    2 -> if (past) 0.23f else 0.29f
    else -> if (past) 0.18f else 0.23f
}

internal fun lyricsBlurRadiusDp(distance: Int, immersive: Boolean): Float = when (distance) {
    0 -> 0f
    1 -> if (immersive) 1.4f else 1.3f
    else -> if (immersive) 5f else 3.25f
}

/** 全屏与封面 mini 共用同一列表和行渲染。mini 固定视口、不接受拖动，只响应点击进入全屏。 */
@Composable
internal fun LyricsViewport(
    lines: List<LyricLine>,
    position: State<Long>,
    config: LyricsUiConfig,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    centered: Boolean = false,
    onLineClick: (LyricLine) -> Unit,
    frameState: State<LyricFrame> = rememberLyricFrame(lines, position),
    motionEnabled: Boolean = true,
    immersive: Boolean = false,
) {
    val frame by frameState
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    // 歌词与标题、控件使用同一封面墨水；弱化只调透明度，不叠加中性灰。
    val ink = FullPlayerTextPrimary
    val immersiveMode = immersive && !mini
    val forceCenter = immersive || centered || config.isCentered
    val centerFraction = animateFloatAsState(if (forceCenter) 1f else 0f,
        if (mini && motionEnabled) PlayerCoverMorphSpec else snap(), label = "miniLyricAlignment")
    val alignment = if (forceCenter) Alignment.CenterHorizontally else Alignment.Start
    val fontSize = when {
        mini -> config.fontSizeSp.coerceIn(12f, 26f)
        immersive -> IMMERSIVE_LYRICS_FONT_SIZE_SP
        else -> config.fontSizeSp
    }
    val lineHeightMultiplier = if (immersiveMode) IMMERSIVE_LYRICS_LINE_HEIGHT_MULTIPLIER else 1.3f
    val miniLineHeight = maxOf(24f, fontSize * 1.5f).sp
    val style = baseStyle.copy(fontSize = fontSize.sp, lineHeight = if (mini) miniLineHeight else (fontSize * lineHeightMultiplier).sp,
        fontWeight = when {
            immersive && !mini -> FontWeight.Bold
            config.isBold -> FontWeight.Bold
            else -> FontWeight.Medium
        },
        lineBreak = if (mini) LineBreak.Simple else LineBreak.Heading,
        textAlign = if (forceCenter && !mini) TextAlign.Center else TextAlign.Start)
    val subStyle = baseStyle.copy(fontSize = (fontSize * 0.68f).sp, lineHeight = (fontSize * 0.95f).sp, textAlign = style.textAlign)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // 只测量聚焦锚点，不在首次展示时排版整首歌词；可见行由 LazyColumn 自行测量。
        val rowHeight: (Int) -> Int = remember(
            lines, width, style, subStyle, mini, immersiveMode, density.density, density.fontScale,
        ) {
            val cache = mutableMapOf<Int, Int>();
            { index: Int -> cache.getOrPut(index) {
                if (mini) with(density) { miniLineHeight.roundToPx() }
                else {
                    val line = lines[index]
                    measurer.measure(line.text, style, constraints = Constraints(maxWidth = width)).size.height +
                        listOfNotNull(line.translation, line.romanization).sumOf {
                            measurer.measure(it, subStyle, constraints = Constraints(maxWidth = width)).size.height + with(density) { 3.dp.roundToPx() }
                        }
                }
            } }
        }
        val initial = frame.focusIndex.coerceAtLeast(0)
        val list = rememberLazyListState(initial, if (lines.isEmpty()) 0 else rowHeight(initial) / 2)
        var browsing by remember { mutableStateOf(false) }
        var touching by remember { mutableStateOf(false) }
        var tapEligible by remember { mutableStateOf(true) }
        LaunchedEffect(browsing, touching) {
            if (browsing && !touching) snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collectLatest { moving ->
                if (!moving) { delay(3_000); browsing = false }
            }
        }
        LaunchedEffect(frame.focusIndex, browsing, touching, rowHeight, motionEnabled) {
            val index = frame.focusIndex.coerceAtLeast(0)
            if (browsing || touching || index !in lines.indices) return@LaunchedEffect
            val offset = rowHeight(index) / 2
            if (!motionEnabled) {
                // BoxWithConstraints 会在测量中子组合，交由列表下一次测量应用歌词锚点。
                list.requestScrollToItem(index, offset)
                return@LaunchedEffect
            }
            val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (target != null) list.animateScrollBy((target.offset + offset).toFloat(), spring(dampingRatio = 0.86f, stiffness = 160f))
            else list.animateScrollToItem(index, offset)
        }
        val viewConfiguration = LocalViewConfiguration.current
        val tapSlop = viewConfiguration.touchSlop
        val verticalViewConfiguration = rememberPlayerVerticalViewConfiguration()
        val focusFraction = if (!mini && !immersiveMode) ORDINARY_LYRICS_FOCUS_FRACTION else 0.5f
        val rowSpacingDp = when {
            mini -> 3.dp
            immersiveMode -> (fontSize * IMMERSIVE_LYRICS_SPACING_MULTIPLIER).dp
            else -> maxOf(14f, fontSize * 0.66f).dp
        }
        val interlude = remember(lines, frame, mini) { if (mini) null else lyricInterludeForFrame(lines, frame) }
        CompositionLocalProvider(LocalViewConfiguration provides verticalViewConfiguration) {
            LazyColumn(
                state = list,
                userScrollEnabled = !mini,
                contentPadding = PaddingValues(
                    top = with(density) { (height * focusFraction).toDp() },
                    bottom = with(density) { (height * (1f - focusFraction)).toDp() },
                ),
                verticalArrangement = Arrangement.spacedBy(rowSpacingDp),
                modifier = Modifier.fillMaxSize().pointerInput(list, mini, tapSlop) {
                    if (mini) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        tapEligible = !list.isScrollInProgress
                        touching = true
                        browsing = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                // 一旦越过点击容差，本次触摸即使折返也不能跳播；不消费事件，以保留惯性与父层切页。
                                if (event.changes.any { it.id != down.id ||
                                        (it.position - down.position).getDistance() > tapSlop }) {
                                    tapEligible = false
                                }
                                // 行点击在 Main 阶段执行，必须等 Final 后才能释放本次触摸资格。
                                awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touching = false
                            tapEligible = true
                        }
                    }
                },
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                        val active = index in frame.activeIndices
                        // 间奏保留已唱句的阅读焦点，不改变真实演唱区间或逐字时间。
                        val emphasized = active || (frame.activeIndices.isEmpty() && index == frame.focusIndex)
                        val distance = kotlin.math.abs(index - frame.focusIndex)
                        val immersiveRow = immersiveMode
                        // 对唱/背景声部可能同时在演唱，正在唱的行都应清晰，不能只照亮焦点索引。
                        val depthDistance = if (emphasized) 0 else distance
                        val scale = animateFloatAsState(
                            if (immersiveRow) immersiveLyricsScale(depthDistance)
                            else if (mini) when (distance) { 0 -> 1f; 1 -> 0.82f; else -> 0.75f }
                            else if (emphasized) 1f else 0.88f,
                            if (motionEnabled) spring(dampingRatio = 0.9f, stiffness = 300f) else snap(), label = "lyricFocusScale",
                        )
                        val depthEnabled = !mini && (immersiveRow || config.isBlurEnabled)
                        val blurRadius = animateFloatAsState(
                            if (depthEnabled) lyricsBlurRadiusDp(depthDistance, immersiveRow) else 0f,
                            if (motionEnabled) tween(180) else snap(), label = "lyricDepthBlur",
                        )
                        val depthAlpha = animateFloatAsState(
                            when {
                                mini -> 1f
                                immersiveRow -> immersiveLyricsAlpha(depthDistance)
                                else -> ordinaryLyricsAlpha(depthDistance, index < frame.focusIndex)
                            },
                            if (motionEnabled) tween(200) else snap(),
                            label = "lyricDepthAlpha",
                        )
                        val muted = ink.copy(
                            alpha = when {
                                emphasized -> 0.42f
                                immersiveRow -> 0.48f
                                else -> 1f
                            },
                        )
                        val rowInterlude = interlude?.takeIf { it.anchorIndex == index }
                        Column(Modifier.fillMaxWidth().then(
                            if (rowInterlude != null) Modifier.drawWithContent {
                                drawContent()
                                val spacingPx = rowSpacingDp.toPx()
                                val centerY = if (rowInterlude.beforeFirst) -spacingPx * 0.65f else size.height + spacingPx * 0.5f
                                drawInterludeDots(
                                    interlude = rowInterlude,
                                    positionMs = position.value,
                                    motionEnabled = motionEnabled,
                                    ink = ink,
                                    centered = alignment == Alignment.CenterHorizontally,
                                    baseRadiusDp = if (immersiveRow) 3.5f else 3.2f,
                                    centerY = centerY,
                                )
                            } else Modifier
                        ).clickable(
                            interactionSource = null,
                            indication = if (mini) LocalIndication.current else null,
                        ) {
                            if (mini || tapEligible) {
                                onLineClick(line)
                                if (!mini) browsing = false
                            }
                        }, horizontalAlignment = alignment) {
                            Column(Modifier.fillMaxWidth()
                                .graphicsLayer {
                                    alpha = (if (line.isBackground) 0.78f else 1f) * depthAlpha.value
                                    scaleX = scale.value; scaleY = scale.value
                                    transformOrigin = TransformOrigin(if (mini) centerFraction.value * 0.5f else if (alignment == Alignment.CenterHorizontally) 0.5f else 0f, 0.5f)
                                    val radiusPx = blurRadius.value * density.density
                                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radiusPx > 0f)
                                        BlurEffect(radiusX = radiusPx, radiusY = radiusPx) else null
                                }) {
                                TimedLyricText(line, position, emphasized, ink, style,
                                    modifier = if (mini) Modifier.fillMaxWidth().height(with(density) { miniLineHeight.toDp() }) else Modifier.fillMaxWidth(),
                                    maxLines = if (mini) 1 else Int.MAX_VALUE,
                                    mutedColor = if (mini) null else muted, marquee = mini && active && motionEnabled,
                                    centerFraction = if (mini) centerFraction else null,
                                    inactiveAlpha = if (distance == 1) 0.47f else 0.27f,
                                    liftEnabled = !mini && motionEnabled && config.isWordLiftEnabled)
                                if (!mini) for (text in listOfNotNull(line.translation, line.romanization)) Text(
                                    text, style = subStyle,
                                    color = ink.copy(alpha = if (emphasized) 0.72f else if (immersiveRow) 0.48f else 0.80f),
                                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawInterludeDots(
    interlude: LyricInterlude,
    positionMs: Long,
    motionEnabled: Boolean,
    ink: Color,
    centered: Boolean,
    baseRadiusDp: Float,
    centerY: Float,
) {
    val dots = interludeDotVisuals(interlude, positionMs, motionEnabled) ?: return
    val baseRadius = baseRadiusDp.dp.toPx()
    val step = baseRadius * 3.5f
    val startX = if (centered) size.width * 0.5f - step else baseRadius + 1.dp.toPx()
    dots.forEachIndexed { dotIndex, dot ->
        drawCircle(
            color = ink.copy(alpha = dot.alpha),
            radius = baseRadius * dot.scale,
            center = Offset(startX + dotIndex * step, centerY),
        )
    }
}

internal fun stageHeroFontSizeSp(textLength: Int, widthDp: Float): Float = when {
    widthDp >= 960f && textLength <= 10 -> 54f
    widthDp >= 960f && textLength <= 18 -> 46f
    widthDp >= 960f -> 38f
    textLength <= 10 && widthDp >= 700f -> 44f
    textLength <= 18 -> 38f
    else -> 32f
}

internal fun stageLineProgress(lines: List<LyricLine>, index: Int, positionMs: Long, durationMs: Long): Float {
    val line = lines.getOrNull(index) ?: return 0f
    val endMs = line.endMs
        ?: lines.getOrNull(index + 1)?.startMs
        ?: durationMs.takeIf { it > line.startMs }
        ?: (line.startMs + 4_000L)
    val span = (endMs - line.startMs).coerceAtLeast(1L)
    return ((positionMs - line.startMs).toFloat() / span).coerceIn(0f, 1f)
}

private fun Modifier.horizontalWingFade(leftEdge: Boolean): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.horizontalGradient(
                    if (leftEdge) {
                        listOf(Color.Black, Color.Black, Color.Transparent)
                    } else {
                        listOf(Color.Transparent, Color.Black, Color.Black)
                    },
                ),
                blendMode = BlendMode.DstIn,
            )
        }

private fun DrawScope.drawStageStardust(
    progress: Float,
    positionMs: Long,
    ink: Color,
    isDark: Boolean,
    fadeAlpha: Float,
) {
    if (fadeAlpha <= 0f) return
    val count = 15
    for (i in 0 until count) {
        val u = (i + 0.5f) / count
        val x = size.width * (0.18f + 0.64f * u)
        val archY = size.height * (0.86f - 0.64f * kotlin.math.sin(Math.PI * u).toFloat())
        val proximity = (1f - kotlin.math.abs(u - progress) / 0.28f).coerceIn(0f, 1f)
        val twinkle = 0.55f + 0.45f * kotlin.math.sin((positionMs / 520.0) + i * 1.45).toFloat()
        val baseAlpha = if (isDark) (0.06f + 0.58f * proximity) else (0.04f + 0.28f * proximity)
        val alpha = (baseAlpha * twinkle * fadeAlpha).coerceIn(0f, 1f)
        val radius = ((0.9f + 1.3f * proximity) * (0.85f + 0.15f * twinkle)).dp.toPx()
        drawCircle(color = ink.copy(alpha = alpha * 0.28f), radius = radius * 2.4f, center = Offset(x, archY))
        drawCircle(color = ink.copy(alpha = alpha), radius = radius, center = Offset(x, archY))
    }
}

/** 横屏沉浸第3页：纯粹文字剧场（衬线主次焦距 + 单行跨边流字 + 星尘微光弧线）。 */
@Composable
internal fun ImmersiveStageLyrics(
    track: UiTrack?,
    durationMs: Long,
    playing: Boolean,
    isVisible: Boolean,
    lines: List<LyricLine>,
    position: State<Long>,
    frameState: State<LyricFrame>,
    config: LyricsUiConfig,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val frame by frameState
    val colors = LocalPlayerColors.current
    val isDark = colors.isDark
    val ink = FullPlayerTextPrimary
    val baseStyle = LocalTextStyle.current
    val displayIndex = frame.focusIndex.coerceAtLeast(0)
    val heroEmphasized = displayIndex in frame.activeIndices || (frame.activeIndices.isEmpty() && frame.focusIndex == displayIndex)
    val interlude = remember(lines, frame) { lyricInterludeForFrame(lines, frame) }
    val seconds by remember(position) { derivedStateOf { position.value / 1_000L } }
    val prevText = lines.getOrNull(displayIndex - 1)?.text?.takeIf { it.isNotBlank() }
    val nextText = lines.getOrNull(displayIndex + 1)?.text?.takeIf { it.isNotBlank() }
    val stardustAlpha by animateFloatAsState(
        targetValue = if (motionEnabled && heroEmphasized && (playing || isVisible)) 1f else 0f,
        animationSpec = if (motionEnabled) tween(300) else snap(),
        label = "stageStardustFade",
    )
    BoxWithConstraints(modifier.clipToBounds()) {
        val stageWidth = maxWidth
        val largeStage = stageWidth >= 960.dp
        // 1. 柔和剧场聚光暗角：只收束视觉焦点到中央舞台带，不加边框卡片。
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to if (isDark) Color.Black.copy(alpha = 0.34f) else ink.copy(alpha = 0.04f),
                    0.22f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1.00f to if (isDark) Color.Black.copy(alpha = 0.38f) else ink.copy(alpha = 0.045f),
                ),
            ),
        )
        if (isDark) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        0.0f to ink.copy(alpha = 0.06f),
                        0.72f to Color.Transparent,
                    ),
                ),
            )
        }

        // 2. 左右跨边流动单行衬线大字（禁折行 + 向屏幕外自然延伸 + 语义隔离）。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.29f)
                .clipToBounds()
                .horizontalWingFade(leftEdge = true)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedContent(
                targetState = prevText,
                transitionSpec = {
                    if (motionEnabled) {
                        (slideInHorizontally(tween(620, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(500))) togetherWith
                            (slideOutHorizontally(tween(480, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(400)))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "stageLeftWing",
            ) { wing ->
                if (wing != null) {
                    Text(
                        text = wing,
                        style = baseStyle.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = if (largeStage) 108.sp else 86.sp,
                            lineHeight = if (largeStage) 118.sp else 94.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = ink.copy(alpha = if (isDark) 0.19f else 0.14f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier
                            .wrapContentWidth(unbounded = true, align = Alignment.End)
                            .offset(x = (-10).dp)
                            .graphicsLayer {
                                val blurPx = 1.6.dp.toPx()
                                renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    BlurEffect(radiusX = blurPx, radiusY = blurPx)
                                } else null
                            },
                    )
                } else {
                    Spacer(Modifier.fillMaxWidth())
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.30f)
                .clipToBounds()
                .horizontalWingFade(leftEdge = false)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.CenterStart,
        ) {
            AnimatedContent(
                targetState = nextText,
                transitionSpec = {
                    if (motionEnabled) {
                        (slideInHorizontally(tween(620, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(500))) togetherWith
                            (slideOutHorizontally(tween(480, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(400)))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "stageRightWing",
            ) { wing ->
                if (wing != null) {
                    val tail = lines.getOrNull(displayIndex + 2)?.text?.takeIf { it.isNotBlank() }
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = wing,
                            style = baseStyle.copy(
                                fontFamily = FontFamily.Serif,
                                fontSize = if (largeStage) 114.sp else 90.sp,
                                lineHeight = if (largeStage) 124.sp else 98.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = ink.copy(alpha = if (isDark) 0.21f else 0.15f),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier
                                .wrapContentWidth(unbounded = true, align = Alignment.Start)
                                .offset(x = 12.dp)
                                .graphicsLayer {
                                    val blurPx = 1.4.dp.toPx()
                                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        BlurEffect(radiusX = blurPx, radiusY = blurPx)
                                    } else null
                                },
                        )
                        if (tail != null) {
                            Text(
                                text = tail,
                                style = baseStyle.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = FontStyle.Italic,
                                    fontSize = if (largeStage) 72.sp else 60.sp,
                                    lineHeight = if (largeStage) 80.sp else 66.sp,
                                    fontWeight = FontWeight.Normal,
                                ),
                                color = ink.copy(alpha = if (isDark) 0.11f else 0.08f),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                                modifier = Modifier
                                    .wrapContentWidth(unbounded = true, align = Alignment.Start)
                                    .offset(x = 32.dp, y = (-12).dp)
                                    .graphicsLayer {
                                        val blurPx = 2.6.dp.toPx()
                                        renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            BlurEffect(radiusX = blurPx, radiusY = blurPx)
                                        } else null
                                    },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.fillMaxWidth())
                }
            }
        }

        // 3. 顶部与底部极淡画展落款
        val trackLabel = listOfNotNull(
            track?.title?.takeIf { it.isNotBlank() },
            track?.artist?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (trackLabel.isNotEmpty()) {
            Text(
                text = trackLabel,
                style = baseStyle.copy(
                    fontSize = if (largeStage) 12.sp else 11.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                ),
                color = ink.copy(alpha = 0.34f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (largeStage) 24.dp else 18.dp, start = 32.dp, end = 32.dp),
            )
        }
        val timeLabel = if (durationMs > 0L) {
            "${formatClock(seconds * 1_000L)} / ${formatClock(durationMs)}"
        } else {
            formatClock(seconds * 1_000L)
        }
        Text(
            text = timeLabel,
            style = baseStyle.copy(
                fontSize = if (largeStage) 12.sp else 11.sp,
                fontFeatureSettings = "tnum",
                textAlign = TextAlign.Center,
            ),
            color = ink.copy(alpha = 0.32f),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (largeStage) 20.dp else 14.dp),
        )

        // 4. 中央衬线双行剧场聚焦 + 跟随声线的微光星尘弧线
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.76f)
                .widthIn(max = if (largeStage) 820.dp else 680.dp)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                if (interlude != null && interlude.beforeFirst) {
                    drawInterludeDots(
                        interlude = interlude,
                        positionMs = position.value,
                        motionEnabled = motionEnabled,
                        ink = ink,
                        centered = true,
                        baseRadiusDp = 3.5f,
                        centerY = size.height * 0.5f,
                    )
                } else if (stardustAlpha > 0f) {
                    val progress = stageLineProgress(lines, displayIndex, position.value, durationMs)
                    drawStageStardust(
                        progress = progress,
                        positionMs = position.value,
                        ink = ink,
                        isDark = isDark,
                        fadeAlpha = stardustAlpha,
                    )
                }
            }
            AnimatedContent(
                targetState = displayIndex,
                transitionSpec = {
                    if (motionEnabled) {
                        (slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 3 } +
                            scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = 0.88f) +
                            fadeIn(tween(340))) togetherWith
                            (slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { -it / 3 } +
                                scaleOut(tween(280), targetScale = 0.94f) +
                                fadeOut(tween(220)))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "stageHeroLine",
            ) { index ->
                val line = lines.getOrNull(index)
                if (line != null) {
                    val active = index in frame.activeIndices
                    val emphasized = active || (frame.activeIndices.isEmpty() && index == frame.focusIndex)
                    val heroFontSp = stageHeroFontSizeSp(line.text.length, stageWidth.value)
                    val heroStyle = baseStyle.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = heroFontSp.sp,
                        lineHeight = (heroFontSp * 1.26f).sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                        lineBreak = LineBreak.Heading,
                        textAlign = TextAlign.Center,
                        shadow = if (isDark) Shadow(ink.copy(alpha = 0.34f), Offset.Zero, 20f) else null,
                    )
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        TimedLyricText(
                            line = line,
                            position = position,
                            active = emphasized,
                            color = ink,
                            style = heroStyle,
                            modifier = Modifier.fillMaxWidth(),
                            mutedColor = ink.copy(alpha = if (emphasized) 0.38f else 0.32f),
                            liftEnabled = motionEnabled && config.isWordLiftEnabled,
                        )
                        for (subText in listOfNotNull(line.translation, line.romanization)) {
                            Text(
                                text = subText,
                                style = baseStyle.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontSize = if (largeStage) 17.sp else 15.sp,
                                    lineHeight = if (largeStage) 24.sp else 21.sp,
                                    textAlign = TextAlign.Center,
                                ),
                                color = ink.copy(alpha = 0.66f),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(22.dp).drawWithContent {
                    val midInterlude = interlude?.takeIf { !it.beforeFirst && it.anchorIndex == displayIndex }
                    if (midInterlude != null) {
                        drawInterludeDots(
                            interlude = midInterlude,
                            positionMs = position.value,
                            motionEnabled = motionEnabled,
                            ink = ink,
                            centered = true,
                            baseRadiusDp = 3.5f,
                            centerY = size.height * 0.5f,
                        )
                    }
                },
            )
            AnimatedContent(
                targetState = displayIndex + 1,
                transitionSpec = {
                    if (motionEnabled) {
                        (slideInVertically(tween(360, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(300))) togetherWith
                            (slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(200)))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "stageNextLine",
            ) { nextIndex ->
                val preview = lines.getOrNull(nextIndex)?.text
                if (preview != null) {
                    Text(
                        text = preview,
                        style = baseStyle.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = if (largeStage) 19.sp else 16.sp,
                            lineHeight = if (largeStage) 26.sp else 22.sp,
                            letterSpacing = 0.5.sp,
                            textAlign = TextAlign.Center,
                        ),
                        color = ink.copy(alpha = 0.48f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(if (largeStage) 26.dp else 22.dp))
                }
            }
        }
    }
}
