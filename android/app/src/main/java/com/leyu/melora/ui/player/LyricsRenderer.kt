package com.leyu.melora.ui.player

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlinx.coroutines.launch

/** 同一时钟供三个展示入口读取；时间只在 draw 或时间线边界派生中消费。 */
@Composable
internal fun rememberLyricPosition(state: PlayerUiState, visible: Boolean): State<Long> {
    val latest by rememberUpdatedState(state)
    val position = remember(state.current?.uid) { mutableLongStateOf(state.positionMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.current?.uid, state.positionSampleRealtimeMs, state.positionAdvancing, visible) {
        position.longValue = state.positionMs
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
    glow: Boolean = true,
    inactiveAlpha: Float = 0.36f,
    marquee: Boolean = false,
) {
    var layout by remember(line.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    val runs = remember(line, layout) { layout?.let { wordRuns(line, it) }.orEmpty() }
    // 先对已填充的字形做整层柔化，再绘制清晰文本；不能在字框内裁剪阴影，否则会出现硬矩形。
    Box(modifier.then(if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
        initialDelayMillis = 1000, repeatDelayMillis = 1200, velocity = 32.dp) else Modifier)) {
        if (active && glow && runs.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Canvas(Modifier.matchParentSize().blur(4.dp, BlurredEdgeTreatment.Unbounded)) {
                layout?.let { drawTimedWords(it, runs, position.value, color, 0.28f) }
            }
        }
        Text(
            text = line.text,
            style = style,
            color = color.copy(alpha = if (active && line.words.isEmpty()) 1f else if (active) 0.4f else inactiveAlpha),
            maxLines = maxLines,
            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().drawWithCache {
                onDrawWithContent {
                    drawContent()
                    if (active && runs.isNotEmpty()) layout?.let { drawTimedWords(it, runs, position.value, color) }
                }
            },
        )
    }
}

/** 两个绘制层共享相同的字形遮罩；显式 alpha，避免继承 Text 基础暗色的透明度。 */
private fun DrawScope.drawTimedWords(
    text: TextLayoutResult,
    runs: List<Pair<LyricWord, List<GlyphRun>>>,
    time: Long,
    color: Color,
    alpha: Float = 1f,
) {
    for ((word, segments) in runs) {
        var remaining = segments.sumOf { it.bounds.width.toDouble() }.toFloat() * lyricWordProgress(word, time)
        for (segment in segments) {
            val rect = segment.bounds
            val filled = remaining.coerceIn(0f, rect.width)
            remaining -= rect.width
            if (filled <= 0f) continue
            val edge = if (segment.rtl) rect.right - filled else rect.left + filled
            val feather = minOf(8.dp.toPx(), rect.width * 0.2f)
            val brush = if (segment.rtl) Brush.horizontalGradient(listOf(Color.Transparent, color), edge - feather, edge)
                else Brush.horizontalGradient(listOf(color, Color.Transparent), edge, edge + feather)
            clipRect(rect.left, rect.top, rect.right, rect.bottom) { drawText(text, brush = brush, alpha = alpha) }
        }
    }
}

private data class GlyphRun(val bounds: Rect, val rtl: Boolean)

/** 按布局中的视觉行及方向合并字形框，保持换行、连字和 RTL 方向；仅布局变化时计算。 */
private fun wordRuns(line: LyricLine, layout: TextLayoutResult): List<Pair<LyricWord, List<GlyphRun>>> {
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
) {
    val frame by frameState
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    val fontSize = if (mini) config.fontSizeSp.coerceIn(12f, 16f) else config.fontSizeSp
    val style = baseStyle.copy(fontSize = fontSize.sp, lineHeight = (if (mini) 24f else fontSize * 1.3f).sp,
        fontWeight = if (config.isBold) FontWeight.ExtraBold else FontWeight.SemiBold,
        textAlign = if (centered || config.isCentered) TextAlign.Center else TextAlign.Start)
    val subStyle = baseStyle.copy(fontSize = (fontSize * 0.68f).sp, lineHeight = (fontSize * 0.95f).sp)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val duet = remember(lines) { lines.any { it.alignment == LyricAlignment.End } }
        // 只测量聚焦锚点，不在首次展示时排版整首歌词；可见行由 LazyColumn 自行测量。
        val rowHeight: (Int) -> Int = remember(lines, width, style, subStyle, mini, density.density, density.fontScale) {
            val cache = mutableMapOf<Int, Int>();
            { index: Int -> cache.getOrPut(index) {
                if (mini) with(density) { 24.sp.roundToPx() }
                else {
                    val line = lines[index]
                    val measureWidth = if (duet) (width * 0.88f).toInt() else width
                    measurer.measure(line.text, style, constraints = Constraints(maxWidth = measureWidth)).size.height +
                        listOfNotNull(line.translation, line.romanization).sumOf {
                            measurer.measure(it, subStyle, constraints = Constraints(maxWidth = measureWidth)).size.height + with(density) { 3.dp.roundToPx() }
                        }
                }
            } }
        }
        val initial = frame.focusIndex.coerceAtLeast(0)
        val list = rememberLazyListState(initial, if (lines.isEmpty()) 0 else rowHeight(initial) / 2)
        var browsing by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        var interaction by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(list, mini) {
            if (!mini) list.interactionSource.interactions.collect { event ->
                when (event) {
                    is DragInteraction.Start -> { browsing = true; dragging = true; interaction++ }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> { dragging = false; interaction++ }
                }
            }
        }
        LaunchedEffect(browsing, dragging, interaction) {
            if (browsing && !dragging) snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collectLatest { moving ->
                if (!moving) { delay(3_000); browsing = false }
            }
        }
        suspend fun follow(index: Int) {
            if (index !in lines.indices) return
            val offset = rowHeight(index) / 2
            if (!motionEnabled) { list.scrollToItem(index, offset); return }
            val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (target != null) list.animateScrollBy((target.offset + offset).toFloat(), spring(dampingRatio = 0.86f, stiffness = 160f))
            else list.animateScrollToItem(index, offset)
        }
        LaunchedEffect(frame.focusIndex, browsing, rowHeight, motionEnabled) {
            if (!browsing) follow(frame.focusIndex.coerceAtLeast(0))
        }
        LazyColumn(
            state = list,
            userScrollEnabled = !mini,
            contentPadding = PaddingValues(vertical = with(density) { (height / 2f).toDp() }),
            verticalArrangement = Arrangement.spacedBy(if (mini) 3.dp else (fontSize * 0.66f).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val active = index in frame.activeIndices
                val distance = kotlin.math.abs(index - frame.focusIndex)
                val scale = animateFloatAsState(
                    if (mini) when (distance) { 0 -> 1f; 1 -> 0.82f; else -> 0.75f }
                    else if (active) 1f else 0.97f,
                    if (motionEnabled) spring(dampingRatio = 0.9f, stiffness = 300f) else snap(), label = "lyricFocusScale",
                )
                val align = when {
                    duet && line.alignment == LyricAlignment.End -> Alignment.End
                    duet -> Alignment.Start
                    centered || config.isCentered -> Alignment.CenterHorizontally
                    else -> Alignment.Start
                }
                val textAlign = when (align) { Alignment.End -> TextAlign.End; Alignment.CenterHorizontally -> TextAlign.Center; else -> TextAlign.Start }
                Column(Modifier.fillMaxWidth().clickable {
                    onLineClick(line)
                    if (!mini) { browsing = false; scope.launch { follow(index) } }
                }, horizontalAlignment = align) {
                    Column(Modifier.fillMaxWidth(if (duet && !mini) 0.88f else 1f)
                        .graphicsLayer {
                            alpha = if (line.isBackground) 0.78f else 1f
                            scaleX = scale.value; scaleY = scale.value
                            transformOrigin = TransformOrigin(when (align) { Alignment.End -> 1f; Alignment.CenterHorizontally -> 0.5f; else -> 0f }, 0.5f)
                        }) {
                        TimedLyricText(line, position, active, FullPlayerTextPrimary,
                            style.copy(textAlign = textAlign),
                            modifier = if (mini) Modifier.fillMaxWidth().height(with(density) { 24.sp.toDp() }) else Modifier.fillMaxWidth(),
                            maxLines = if (mini) 1 else Int.MAX_VALUE,
                            glow = !mini && motionEnabled, marquee = mini && active && motionEnabled,
                            inactiveAlpha = if (mini) { if (distance == 1) 0.47f else 0.27f } else if (config.isBlurEnabled) 0.24f else 0.36f)
                        if (!mini) for (text in listOfNotNull(line.translation, line.romanization)) Text(
                            text, style = subStyle.copy(textAlign = textAlign),
                            color = FullPlayerTextPrimary.copy(alpha = if (active) 0.78f else 0.32f),
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
