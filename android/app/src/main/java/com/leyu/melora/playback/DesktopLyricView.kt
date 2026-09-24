package com.leyu.melora.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** 原生 TextView 排版上的逐字歌词覆盖绘制；播放位置由调用方逐帧提供。 */
internal class DesktopLyricView(context: Context) : TextView(context) {
    private data class Content(
        val text: String,
        val translationRange: IntRange?,
        val words: List<LyricWord>,
        val color: Int,
    )

    private data class WordRange(val word: LyricWord, val start: Int, val end: Int)

    private class RunGeometry(
        val path: Path,
        val bounds: RectF,
        val rtl: Boolean,
        val revealPath: Path = Path(),
        val visiblePath: Path = Path(),
    ) {
        val width: Float = bounds.width()
    }

    private class WordGeometry(val word: LyricWord, val runs: List<RunGeometry>) {
        val width: Float = runs.sumOf { it.width.toDouble() }.toFloat()
    }

    private inner class CurrentWordStyle : CharacterStyle() {
        var highlightPass = false

        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.color = if (highlightPass) lyricColor else dimmedColor(lyricColor)
        }
    }

    private var lyricColor = Color.WHITE
    private var positionMs = 0L
    private var content: Content? = null
    private var currentWordStyle: CurrentWordStyle? = null
    private var wordRanges: List<WordRange> = emptyList()
    private var geometryLayout: Layout? = null
    private var wordGeometry: List<WordGeometry> = emptyList()
    private val highlightClip = Path()
    private var highlightClipDirty = true

    /** 更新静态歌词/样式。逐帧播放位置不得通过此方法更新。 */
    fun setLyricContent(
        text: String,
        translationRange: IntRange?,
        words: List<LyricWord>,
        color: Int,
    ) {
        val copiedWords = words.toList()
        val translationCandidate = translationRange?.takeIf {
            it.first >= 0 && it.last >= it.first && it.last < text.length
        }
        val wordsText = copiedWords.joinToString(separator = "") { it.text }
        val bodyEnd = wordsText.length
        val matchesBody = wordsText.isNotEmpty() &&
            bodyEnd <= text.length &&
            text.startsWith(wordsText) &&
            (bodyEnd == text.length || text[bodyEnd] == '\n')
        val validTranslation = translationCandidate?.takeIf { !matchesBody || it.first >= bodyEnd }
        val nextContent = Content(text, validTranslation, copiedWords, color)
        if (content == nextContent) return

        content = nextContent
        lyricColor = color
        setTextColor(color)
        invalidateWordGeometry()
        val style = if (matchesBody && bodyEnd > 0) CurrentWordStyle() else null
        currentWordStyle = style

        if (matchesBody) {
            var offset = 0
            wordRanges = copiedWords.mapNotNull { word ->
                val start = offset
                offset += word.text.length
                if (offset <= start) null else WordRange(word, start, offset)
            }
        } else {
            wordRanges = emptyList()
        }

        val styled = SpannableString(text)
        style?.let {
            styled.setSpan(it, 0, bodyEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        validTranslation?.let { range ->
            styled.setSpan(RelativeSizeSpan(0.72f), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(
                ForegroundColorSpan((color and 0x00FFFFFF) or (0x99 shl 24)),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        setText(styled, BufferType.SPANNABLE)
    }

    /** 更新媒体位置；仅逐词进度变化时请求重绘，不创建时钟或动画循环。 */
    fun renderPosition(positionMs: Long) {
        val previousPosition = this.positionMs
        this.positionMs = positionMs
        val progressChanged = wordRanges.any { range ->
            lyricWordProgress(range.word, previousPosition) != lyricWordProgress(range.word, positionMs)
        }
        if (progressChanged) {
            highlightClipDirty = true
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        invalidateWordGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = currentWordStyle ?: return
        val activeLayout = layout ?: return
        ensureWordGeometry(activeLayout)
        if (highlightClipDirty) {
            rebuildHighlightClip()
            highlightClipDirty = false
        }
        if (highlightClip.isEmpty || activeLayout.lineCount == 0) return

        val configuredMaxLines = maxLines
        val visibleLineCount = if (configuredMaxLines > 0) min(activeLayout.lineCount, configuredMaxLines) else activeLayout.lineCount
        if (visibleLineCount == 0) return
        val visibleBottom = min(
            activeLayout.height,
            min(activeLayout.getLineBottom(visibleLineCount - 1), height - paddingBottom - totalPaddingTop + scrollY),
        )
        if (visibleBottom <= 0) return

        val save = canvas.save()
        try {
            canvas.clipRect(
                paddingLeft.toFloat(),
                paddingTop.toFloat(),
                (width - paddingRight).toFloat(),
                (height - paddingBottom).toFloat(),
            )
            canvas.translate(
                (totalPaddingLeft - scrollX).toFloat(),
                (totalPaddingTop - scrollY).toFloat(),
            )
            canvas.clipRect(0f, 0f, activeLayout.width.toFloat(), visibleBottom.toFloat())
            canvas.clipPath(highlightClip)
            style.highlightPass = true
            activeLayout.draw(canvas)
        } finally {
            style.highlightPass = false
            canvas.restoreToCount(save)
        }
    }

    private fun ensureWordGeometry(activeLayout: Layout) {
        if (geometryLayout === activeLayout) return
        geometryLayout = activeLayout
        wordGeometry = wordRanges.mapNotNull { range ->
            val runs = buildRuns(activeLayout, range)
            if (runs.isEmpty()) null else WordGeometry(range.word, runs)
        }
        highlightClipDirty = true
    }

    private fun buildRuns(activeLayout: Layout, range: WordRange): List<RunGeometry> {
        val result = mutableListOf<RunGeometry>()
        var runStart = -1
        var runEnd = -1
        var runLine = -1
        var runRtl = false

        fun flushRun() {
            if (runStart < 0 || runEnd <= runStart) return
            val path = Path()
            activeLayout.getSelectionPath(runStart, runEnd, path)
            val bounds = RectF()
            path.computeBounds(bounds, true)
            if (!path.isEmpty && bounds.width() > 0f && bounds.height() > 0f) {
                result += RunGeometry(path, bounds, runRtl)
            }
            runStart = -1
            runEnd = -1
        }

        var offset = range.start
        while (offset < range.end) {
            val char = text[offset]
            val charCount = if (char.isHighSurrogate() && offset + 1 < range.end && text[offset + 1].isLowSurrogate()) 2 else 1
            if (char.isLowSurrogate()) {
                offset++
                continue
            }

            val line = activeLayout.getLineForOffset(offset)
            val isVisible = line < activeLayout.lineCount && offset < activeLayout.getLineVisibleEnd(line)
            if (!isVisible) {
                flushRun()
            } else {
                val rtl = activeLayout.isRtlCharAt(offset)
                if (runStart >= 0 && (line != runLine || rtl != runRtl)) flushRun()
                if (runStart < 0) {
                    runStart = offset
                    runLine = line
                    runRtl = rtl
                }
                runEnd = offset + charCount
            }
            offset += charCount
        }
        flushRun()
        return result
    }

    private fun rebuildHighlightClip() {
        highlightClip.reset()
        for (word in wordGeometry) {
            if (word.width <= 0f) continue
            var remaining = word.width * lyricWordProgress(word.word, positionMs)
            if (remaining <= 0f) continue

            for (run in word.runs) {
                val amount = min(run.width, remaining)
                if (amount > 0f) {
                    val left = if (run.rtl) run.bounds.right - amount else run.bounds.left
                    val right = if (run.rtl) run.bounds.right else run.bounds.left + amount
                    run.revealPath.reset()
                    run.revealPath.addRect(left, run.bounds.top, right, run.bounds.bottom, Path.Direction.CW)
                    run.visiblePath.reset()
                    if (run.visiblePath.op(run.path, run.revealPath, Path.Op.INTERSECT)) {
                        highlightClip.addPath(run.visiblePath)
                    }
                    remaining -= amount
                }
                if (remaining <= 0f) break
            }
        }
    }

    private fun invalidateWordGeometry() {
        geometryLayout = null
        wordGeometry = emptyList()
        highlightClip.reset()
        highlightClipDirty = true
    }

    private fun dimmedColor(color: Int): Int {
        val alpha = (Color.alpha(color) * 0.42f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
