package com.leyu.melora.playback

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.text.Layout
import android.text.TextPaint
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** 有界行视口：句子保留身份，只移动/缩放已有字形；单行与多行共用逐字绘制。 */
internal class DesktopLyricViewport(context: Context) : ViewGroup(context) {
    private val rows = linkedMapOf<Int, DesktopLyricView>()
    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var fallback = ""
    private var fontSize = 18f
    private var lineLimit = 1
    private var alignment = Gravity.CENTER
    private var color = Color.WHITE
    private var positionMs = 0L
    private var scrollAnimator: ValueAnimator? = null
    private var animateLayout = false
    private val freshRows = mutableSetOf<Int>()
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val widthPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private var widthLimit = 0
    private var preferredWidth = 0
    private val density = resources.displayMetrics.density
    private val scrollInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    init {
        setPadding((12 * density).roundToInt(), (12 * density).roundToInt(),
            (12 * density).roundToInt(), (12 * density).roundToInt())
        clipChildren = true
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun configure(fontSize: Float, lineLimit: Int, alignment: Int, color: Int) {
        if (this.fontSize == fontSize && this.lineLimit == lineLimit && this.alignment == alignment && this.color == color) return
        if (this.fontSize != fontSize || this.lineLimit != lineLimit) preferredWidth = 0
        this.fontSize = fontSize
        this.lineLimit = lineLimit
        this.alignment = alignment
        this.color = color
        stopScrolling()
        bindRows()
    }

    fun submit(lines: List<LyricLine>, index: Int, fallback: String, animate: Boolean) {
        if (this.lines === lines && currentIndex == index && this.fallback == fallback) return
        val sameSong = this.lines === lines
        animateLayout = animate && sameSong && currentIndex >= 0 && index >= 0 &&
            index != currentIndex && index in rows && lineLimit > 1 && isLaidOut
        scrollAnimator?.cancel()
        scrollAnimator = null
        if (!sameSong || this.fallback != fallback) preferredWidth = 0
        if (!sameSong) {
            removeAllViews()
            rows.clear()
            freshRows.clear()
        }
        this.lines = lines
        currentIndex = index
        this.fallback = fallback
        bindRows()
    }

    private fun bindRows() {
        val indices = desktopLyricRowIndices(lines.size, currentIndex, lineLimit)
        val wanted = if (indices.isEmpty()) listOf(-1) else indices.toList()
        for (index in rows.keys.toList()) if (index !in wanted) {
            removeView(rows.remove(index))
            freshRows.remove(index)
        }
        for (index in wanted) {
            val row = rows.getOrPut(index) {
                DesktopLyricView(context).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    freshRows += index
                }
            }
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            if (row.maxLines != lineLimit) row.maxLines = lineLimit
            row.gravity = alignment
            val line = lines.getOrNull(index)
            val text = line?.let { desktopLyricRowText(it, lineLimit) } ?: fallback
            val translationStart = line?.takeIf { lineLimit > 1 && !it.translation.isNullOrBlank() }
                ?.let { if (it.text.isBlank()) 0 else it.text.length + 1 }
            row.setLyricContent(text, translationStart?.let { it until text.length },
                line?.words.orEmpty(), color)
            row.renderPosition(positionMs)
        }
        contentDescription = lines.getOrNull(currentIndex)?.let { desktopLyricRowText(it, lineLimit) } ?: fallback
        requestLayout()
    }

    fun renderPosition(positionMs: Long) {
        this.positionMs = positionMs
        // 保留相邻句的逐字底色，焦点转移时不替换整行样式；无进度变化的行不会重绘。
        for (row in rows.values) row.renderPosition(positionMs)
    }

    /** 息屏、尺寸改变、移除窗口时结束一次滚动，不留下后台动画。 */
    fun stopScrolling() {
        animateLayout = false
        scrollAnimator?.end()
        scrollAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopScrolling()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)
            else preferredWidth(MeasureSpec.getSize(widthMeasureSpec))
        val textWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        // 视口高度由设置决定，不因歌词换行、首尾句、翻译出现而跳动。
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, resources.displayMetrics)
        val desiredHeight = (textSize * 1.4f * lineLimit).roundToInt() + paddingTop + paddingBottom
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        for (row in rows.values) row.measure(
            MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((height - paddingTop - paddingBottom).coerceAtLeast(1), MeasureSpec.AT_MOST),
        )
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height)
    }

    private fun preferredWidth(availableWidth: Int): Int {
        val limit = minOf(availableWidth, (560 * density).roundToInt()).coerceAtLeast(1)
        if (preferredWidth == 0 || widthLimit != limit) {
            widthLimit = limit
            val fontPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, resources.displayMetrics)
            widthPaint.textSize = fontPixels
            val horizontalPadding = paddingLeft + paddingRight
            var naturalWidth = Layout.getDesiredWidth(fallback, widthPaint)
            for (line in lines) {
                naturalWidth = maxOf(naturalWidth, Layout.getDesiredWidth(line.text, widthPaint))
                if (lineLimit > 1 && !line.translation.isNullOrBlank()) {
                    widthPaint.textSize = fontPixels * 0.72f
                    naturalWidth = maxOf(naturalWidth, Layout.getDesiredWidth(line.translation, widthPaint))
                    widthPaint.textSize = fontPixels
                }
                if (naturalWidth + horizontalPadding >= limit) break
            }
            // 一首歌共用稳定宽度；逐句换词/展开控制条不会重排文字。
            preferredWidth = (kotlin.math.ceil(naturalWidth).toInt() + horizontalPadding)
                .coerceAtLeast((48 * density).roundToInt()).coerceAtMost(limit)
        }
        return preferredWidth
    }

    /** 拖动围绕当前句的目标位置，滚动过程不会改变边界。 */
    val focusAnchorY: Float
        get() = desktopLyricFocusCenter(measuredHeight, paddingTop, paddingBottom, lineLimit,
            (rows[currentIndex] ?: rows[-1])?.measuredHeight ?: 0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val edge = (paddingTop + resources.displayMetrics.density * 5f).coerceAtMost(h * 0.2f)
        fadePaint.shader = if (h > 0) LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, edge / h, 1f - edge / h, 1f), Shader.TileMode.CLAMP) else null
    }

    @SuppressLint("DrawAllocation") // 仅换句/尺寸变化时创建有界过渡快照；逐帧只更新属性，不触发布局。
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 布局中断时从已显示的位置继续，不回到上一句起点。
        scrollAnimator?.cancel()
        scrollAnimator = null
        val focus = rows[currentIndex] ?: rows[-1] ?: return
        val center = desktopLyricFocusCenter(height, paddingTop, paddingBottom, lineLimit, focus.measuredHeight)
        val targets = mutableMapOf<Int, Float>()
        targets[if (currentIndex in rows) currentIndex else -1] = center
        val gap = 7f * density
        var top = center - focus.measuredHeight / 2f
        for (index in rows.keys.filter { it < currentIndex }.sortedDescending()) {
            val halfHeight = rows.getValue(index).measuredHeight * 0.4f
            targets[index] = top - gap - halfHeight
            top -= gap + halfHeight * 2f
        }
        var bottom = center + focus.measuredHeight / 2f
        for (index in rows.keys.filter { it > currentIndex }.sorted()) {
            val halfHeight = rows.getValue(index).measuredHeight * 0.4f
            targets[index] = bottom + gap + halfHeight
            bottom += gap + halfHeight * 2f
        }
        val travel = if (currentIndex !in freshRows) focus.translationY + focus.measuredHeight / 2f - center else 0f
        val animate = animateLayout && !changed && ValueAnimator.areAnimatorsEnabled()
        val transitions = rows.map { (index, row) ->
            row.layout(paddingLeft, 0, width - paddingRight, row.measuredHeight)
            row.pivotY = row.height / 2f
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            row.pivotX = row.width * when (alignment and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
                Gravity.START -> if (rtl) 1f else 0f
                Gravity.END -> if (rtl) 0f else 1f
                else -> 0.5f
            }
            val targetY = targets.getValue(index) - row.height / 2f
            val focused = index == currentIndex || index == -1
            val targetScale = if (focused) 1f else 0.8f
            val targetAlpha = if (focused) 1f else if (abs(index - currentIndex) == 1) 0.52f else 0.3f
            if (index in freshRows || !animate) {
                row.translationY = targetY + if (animate) travel else 0f
                row.scaleX = targetScale; row.scaleY = targetScale
                row.alpha = if (animate) 0f else targetAlpha
            }
            RowTransition(row, row.translationY, targetY, row.scaleX, targetScale, row.alpha, targetAlpha)
        }
        freshRows.clear()
        animateLayout = false
        if (animate) {
            scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380L
                interpolator = scrollInterpolator
                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    for (row in transitions) row.apply(progress)
                }
                start()
            }
        } else transitions.forEach { it.apply(1f) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (lineLimit == 1) { super.dispatchDraw(canvas); return }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        canvas.restoreToCount(save)
    }

    private data class RowTransition(val view: DesktopLyricView, val fromY: Float, val toY: Float,
                                     val fromScale: Float, val toScale: Float, val fromAlpha: Float, val toAlpha: Float) {
        fun apply(progress: Float) {
            view.translationY = fromY + (toY - fromY) * progress
            view.scaleX = fromScale + (toScale - fromScale) * progress
            view.scaleY = view.scaleX
            view.alpha = fromAlpha + (toAlpha - fromAlpha) * progress
        }
    }
}

/** 当前句前后各留一个屏外缓冲，视图数量与整首歌词长度无关。 */
internal fun desktopLyricRowIndices(count: Int, current: Int, lineLimit: Int): IntRange {
    if (current !in 0 until count) return IntRange.EMPTY
    val radius = if (lineLimit <= 1) 0 else (lineLimit + 1) / 2 + 1
    return (current - radius).coerceAtLeast(0)..(current + radius).coerceAtMost(count - 1)
}

internal fun desktopLyricRowText(line: LyricLine, lineLimit: Int): String =
    if (lineLimit > 1 && !line.translation.isNullOrBlank()) {
        if (line.text.isBlank()) line.translation else "${line.text}\n${line.translation}"
    } else line.text

/** 偶数行数给下句留完整一行；长句占满视口时仍完整居中，不被边缘遮罩裁掉。 */
internal fun desktopLyricFocusCenter(height: Int, paddingTop: Int, paddingBottom: Int, lineLimit: Int, focusHeight: Int): Float {
    val available = (height - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
    val halfFocus = focusHeight.coerceAtMost(available.toInt()) / 2f
    val slot = (lineLimit - 1) / 2
    return paddingTop + (available * (slot + 0.5f) / lineLimit).coerceIn(halfFocus, available - halfFocus)
}
