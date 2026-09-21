package com.leyu.melora.ui.player

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val ImmersiveWatermarkMinAlpha = 0.032f
private const val ImmersiveWatermarkPulseAlpha = 0.006f
private const val ImmersiveWatermarkPulseScale = 0.004f
private const val ImmersiveWatermarkAnimationMillis = 4_800
private const val ImmersiveWatermarkRotationDegrees = -8f
private const val ImmersiveWatermarkMarginDp = 12f
private const val ImmersiveWatermarkTextWidthFraction = 0.82f
private const val ImmersiveWatermarkMinFontSizeSp = 14f
private const val ImmersiveWatermarkMaxFontSizeSp = 220f
private const val ImmersiveProgressTrackAlpha = 0.07f
private const val ImmersiveProgressAlpha = 0.65f
private const val ImmersiveProgressInsetDp = 2f
private const val ImmersiveProgressCornerRadiusDp = 22f

/** 进度环只接受有效时长；所有播放位置都被限制在 [0, 1]。 */
internal fun immersiveProgressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * 沉浸标题是纯装饰层：不新增封面、不参与触摸，也不向无障碍树暴露重复歌名。
 * State 只在 graphicsLayer 中读取，播放进度不会把宿主页面带入逐帧重组。
 */
@Composable
internal fun ImmersiveTitleWatermark(
    title: String,
    immersion: State<Float>,
    playing: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPlayerColors.current
    // 设置按钮的 accent 是固定品牌蓝；沉浸纹理只用封面派生的背景与文字色。
    val watermarkColor = remember(colors.textPrimary, colors.background) {
        lerp(colors.textPrimary, colors.background, 0.2f).copy(alpha = 1f)
    }
    val breathing = if (playing && motionEnabled) {
        rememberInfiniteTransition(label = "immersive-title-watermark").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = ImmersiveWatermarkAnimationMillis,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "immersive-title-breathing",
        )
    } else {
        null
    }

    BoxWithConstraints(
        modifier = modifier
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val textMeasurer = rememberTextMeasurer(cacheSize = 8)
        val baseTextStyle = LocalTextStyle.current
        val widthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            with(density) { 360.dp.roundToPx() }
        }
        val heightPx = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            with(density) { 360.dp.roundToPx() }
        }
        val textLayout = remember(
            title,
            widthPx,
            heightPx,
            baseTextStyle,
            density.density,
            density.fontScale,
        ) {
            measureImmersiveWatermark(
                text = title,
                measurer = textMeasurer,
                baseStyle = baseTextStyle,
                widthPx = widthPx.coerceAtLeast(1),
                heightPx = heightPx.coerceAtLeast(1),
                density = density,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val normalizedImmersion = immersion.value.coerceIn(0f, 1f)
                    val pulse = breathing?.value ?: 0.5f
                    alpha = normalizedImmersion *
                        (ImmersiveWatermarkMinAlpha + ImmersiveWatermarkPulseAlpha * pulse)
                    val scale = 1f + ImmersiveWatermarkPulseScale * (pulse - 0.5f)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = ImmersiveWatermarkRotationDegrees
                },
        ) {
            drawText(
                textLayout,
                color = watermarkColor,
                topLeft = Offset(
                    x = (size.width - textLayout.size.width) / 2f,
                    y = (size.height - textLayout.size.height) / 2f,
                ),
            )
        }
    }
}

/**
 * 用真实 TextMeasurer 选择能在有限画布内完整容纳的字号；排版只在标题/尺寸/字体变化时发生。
 * 旋转后的外接矩形也参与 fit 判断，避免 -8° 后字形贴边或被父层裁掉。
 */
internal fun measureImmersiveWatermark(
    text: String,
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    widthPx: Int,
    heightPx: Int,
    density: Density,
): TextLayoutResult {
    val marginPx = with(density) { ImmersiveWatermarkMarginDp.dp.toPx() }
    val safeWidthPx = widthPx - marginPx * 2f
    // 给旋转后的多行块留横向余量；fit 仍以完整安全画布判断，避免 TextLayout.size.width 占满后误判。
    val maxTextWidth = (safeWidthPx * ImmersiveWatermarkTextWidthFraction)
        .roundToInt()
        .coerceAtLeast(1)
    val maxTextHeight = (heightPx - marginPx * 2f).roundToInt().coerceAtLeast(1)
    val widthDp = with(density) { maxTextWidth.toDp().value }
    val maxFontSizeSp = min(ImmersiveWatermarkMaxFontSizeSp, widthDp * 0.34f)
        .coerceAtLeast(ImmersiveWatermarkMinFontSizeSp)
    val minFontSizeSp = ImmersiveWatermarkMinFontSizeSp
    val radians = Math.toRadians(abs(ImmersiveWatermarkRotationDegrees).toDouble())
    val cosAngle = cos(radians).toFloat()
    val sinAngle = sin(radians).toFloat()
    val rotatedWidthLimit = widthPx - marginPx * 2f
    val rotatedHeightLimit = heightPx - marginPx * 2f

    fun styleFor(fontSizeSp: Float): TextStyle = baseStyle.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 0.96f).sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (fontSizeSp * -0.016f).sp,
        textAlign = TextAlign.Center,
        lineBreak = LineBreak.Simple,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    fun measureAt(fontSizeSp: Float): TextLayoutResult = measurer.measure(
        text = text,
        style = styleFor(fontSizeSp),
        maxLines = Int.MAX_VALUE,
        softWrap = true,
        overflow = TextOverflow.Visible,
        constraints = Constraints(maxWidth = maxTextWidth, maxHeight = maxTextHeight),
    )

    fun fits(layout: TextLayoutResult): Boolean {
        val layoutWidth = layout.size.width.toFloat()
        val layoutHeight = layout.size.height.toFloat()
        val rotatedWidth = layoutWidth * cosAngle + layoutHeight * sinAngle
        val rotatedHeight = layoutWidth * sinAngle + layoutHeight * cosAngle
        return !layout.didOverflowWidth &&
            !layout.didOverflowHeight &&
            !layout.hasVisualOverflow &&
            rotatedWidth <= rotatedWidthLimit &&
            rotatedHeight <= rotatedHeightLimit
    }

    val largest = measureAt(maxFontSizeSp)
    if (fits(largest)) return largest

    var lower = minFontSizeSp
    var upper = maxFontSizeSp
    var best = measureAt(minFontSizeSp)
    repeat(10) {
        val candidateSize = (lower + upper) / 2f
        val candidate = measureAt(candidateSize)
        if (fits(candidate)) {
            best = candidate
            lower = candidateSize
        } else {
            upper = candidateSize
        }
    }
    if (fits(best)) return best

    // 常见标题在 14sp 已足够；仅当短屏/超长标题仍溢出时，继续用有限步数降低字号，绝不返回已裁切布局。
    var fallbackSize = minFontSizeSp
    repeat(6) {
        if (fits(best)) return best
        fallbackSize *= 0.82f
        best = measureAt(fallbackSize)
    }
    return best
}

/**
 * 进度线贴合封面轮廓，不再把方形封面包进大圆环。位置只在 draw 阶段读取，路径与 PathMeasure 只在尺寸/形状变化时构建。
 * durationMs 无效时保留淡轨道，但不绘制任何虚假进度。
 */
@Composable
internal fun ImmersiveCoverProgress(
    position: State<Long>,
    durationMs: Long,
    immersion: State<Float>,
    circular: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPlayerColors.current
    val trackColor = colors.textPrimary
    val activeColor = colors.progressActive

    Box(
        modifier = modifier
            .clearAndSetSemantics {}
            .drawWithCache {
                val strokeWidth = 1.dp.toPx()
                val pathInset = ImmersiveProgressInsetDp.dp.toPx()
                val minDimension = min(size.width, size.height)
                val path = buildImmersiveCoverPath(
                    width = size.width,
                    height = size.height,
                    circular = circular,
                    inset = pathInset,
                    cornerRadius = ImmersiveProgressCornerRadiusDp.dp.toPx(),
                )
                val pathMeasure = PathMeasure(path, false)
                val pathLength = pathMeasure.length
                val progressPath = Path()
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                onDrawBehind {
                    val normalizedImmersion = immersion.value.coerceIn(0f, 1f)
                    if (normalizedImmersion <= 0f || pathLength <= 0f || minDimension <= 0f) {
                        return@onDrawBehind
                    }

                    trackPaint.color = trackColor.copy(
                        alpha = ImmersiveProgressTrackAlpha * normalizedImmersion,
                    ).toArgb()
                    progressPaint.color = activeColor.copy(
                        alpha = ImmersiveProgressAlpha * normalizedImmersion,
                    ).toArgb()

                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawPath(path, trackPaint)

                        if (durationMs <= 0L) return@drawIntoCanvas
                        val progress = immersiveProgressFraction(position.value, durationMs)
                        val stopDistance = pathLength * progress
                        if (stopDistance <= 0f) return@drawIntoCanvas

                        progressPath.rewind()
                        if (pathMeasure.getSegment(0f, stopDistance, progressPath, true)) {
                            canvas.nativeCanvas.drawPath(progressPath, progressPaint)
                        }
                    }
                }
            },
    )
}

private fun buildImmersiveCoverPath(
    width: Float,
    height: Float,
    circular: Boolean,
    inset: Float,
    cornerRadius: Float,
): Path {
    val path = Path()
    if (width <= inset * 2f || height <= inset * 2f) return path

    if (circular) {
        val diameter = min(width, height) - inset * 2f
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        path.addArc(RectF(left, top, left + diameter, top + diameter), -90f, 360f)
        return path
    }

    val left = inset
    val top = inset
    val right = width - inset
    val bottom = height - inset
    val radius = min(cornerRadius, min(right - left, bottom - top) / 2f)
    val centerX = (left + right) / 2f
    path.moveTo(centerX, top)
    path.lineTo(right - radius, top)
    path.arcTo(RectF(right - radius * 2f, top, right, top + radius * 2f), -90f, 90f)
    path.lineTo(right, bottom - radius)
    path.arcTo(RectF(right - radius * 2f, bottom - radius * 2f, right, bottom), 0f, 90f)
    path.lineTo(left + radius, bottom)
    path.arcTo(RectF(left, bottom - radius * 2f, left + radius * 2f, bottom), 90f, 90f)
    path.lineTo(left, top + radius)
    path.arcTo(RectF(left, top, left + radius * 2f, top + radius * 2f), 180f, 90f)
    path.lineTo(centerX, top)
    return path
}
