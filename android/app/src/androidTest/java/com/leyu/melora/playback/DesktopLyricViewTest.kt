package com.leyu.melora.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopLyricViewTest {
    @Test
    fun repeatedLyricsAndTranslationsKeepTheSameNaturalWidth() = onMain {
        val unique = listOf(
            LyricLine(0, "短句", translation = "a longer translated line"),
            LyricLine(1000, "另一句\n第二行", translation = "短译文"),
        )
        for (limit in listOf(1, 4)) {
            val view = DesktopLyricViewport(testContext())
            view.configure(20f, limit, Gravity.CENTER, Color.WHITE)
            view.submit(unique, 0, "歌曲", animate = false)
            layoutAutomaticViewport(view, 360)
            val width = view.width
            val height = view.height
            view.submit(List(3000) { unique[it % unique.size] }, 0, "歌曲", animate = false)
            layoutAutomaticViewport(view, 360)
            assertEquals(width, view.width)
            assertEquals(height, view.height)
            assertTrue("只保留当前句附近的有界行", view.childCount <= 9)
        }
    }

    @Test
    fun automaticWidthFitsTheSongAndDoesNotResizeOnSentenceChanges() = onMain {
        val viewport = DesktopLyricViewport(testContext())
        val lines = listOf(LyricLine(0, "短句"), LyricLine(1000, "稍微长一点的歌词"))
        viewport.configure(20f, 3, Gravity.CENTER, Color.WHITE)
        viewport.submit(lines, 0, "歌曲", animate = false)
        layoutAutomaticViewport(viewport, 360)
        val width = viewport.width
        val height = viewport.height
        assertTrue(width < dp(testContext(), 360))
        assertTrue(width >= dp(testContext(), 48))
        viewport.submit(lines, 1, "歌曲", animate = true)
        layoutAutomaticViewport(viewport, 360)
        viewport.stopScrolling()
        assertEquals(width, viewport.width)
        assertEquals(height, viewport.height)
        viewport.configure(26f, 3, Gravity.CENTER, Color.WHITE)
        layoutAutomaticViewport(viewport, 360)
        assertTrue(viewport.width > width)
    }

    @Test
    fun automaticWidthCapsLongLyricsInPortraitAndLandscape() = onMain {
        val viewport = DesktopLyricViewport(testContext())
        viewport.configure(24f, 4, Gravity.CENTER, Color.WHITE)
        viewport.submit(listOf(LyricLine(0, "这是一句很长的歌词".repeat(20))), 0, "", animate = false)
        layoutAutomaticViewport(viewport, 240)
        assertEquals(dp(testContext(), 240), viewport.width)
        layoutAutomaticViewport(viewport, 900)
        assertEquals(dp(testContext(), 560), viewport.width)
        layoutAutomaticViewport(viewport, 240)
        assertEquals(dp(testContext(), 240), viewport.width)
    }

    private fun layoutAutomaticViewport(viewport: DesktopLyricViewport, widthDp: Int) {
        viewport.measure(MeasureSpec.makeMeasureSpec(dp(testContext(), widthDp), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(dp(testContext(), 600), MeasureSpec.AT_MOST))
        viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
    }

    @Test
    fun multilineRetainsSentenceViewsAndScrollsWithoutFadingTheWholeWindow() = onMain {
        val viewport = DesktopLyricViewport(testContext())
        val lines = (0..12).map { LyricLine(it * 1000L, "line $it") }
        viewport.configure(20f, 4, Gravity.CENTER, Color.WHITE)
        viewport.submit(lines, 5, "", animate = false)
        layoutViewport(viewport)
        val oldFocus = row(viewport, "line 5")
        val nextFocus = row(viewport, "line 6")
        val stableHeight = viewport.height
        val startingY = nextFocus.translationY
        assertEquals(1f, oldFocus.scaleX, 0.001f)
        assertEquals(0.8f, nextFocus.scaleX, 0.001f)
        assertTrue(nextFocus.alpha < oldFocus.alpha)

        viewport.submit(lines, 6, "", animate = true)
        layoutViewport(viewport)
        assertSame(oldFocus, row(viewport, "line 5"))
        assertSame(nextFocus, row(viewport, "line 6"))
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            assertEquals(startingY, nextFocus.translationY, 0.01f)
        }
        viewport.stopScrolling()
        assertTrue(nextFocus.translationY < startingY)
        assertEquals(1f, nextFocus.scaleX, 0.001f)
        assertEquals(0.8f, oldFocus.scaleX, 0.001f)
        assertEquals(1f, viewport.alpha, 0f)
        assertEquals(stableHeight, viewport.height)
        assertEquals(desktopLyricFocusCenter(viewport.height, viewport.paddingTop, viewport.paddingBottom, 4, nextFocus.height),
            nextFocus.translationY + nextFocus.height / 2f, 1f)
    }

    @Test
    fun viewportStaysStableThroughLongLinesTranslationsSeekAndModeChanges() = onMain {
        val viewport = DesktopLyricViewport(testContext())
        val lines = listOf(LyricLine(0, "short"),
            LyricLine(1000, "a very long lyric sentence ".repeat(8), "translated sentence"),
            LyricLine(2000, "last"))
        viewport.configure(20f, 4, Gravity.START, Color.WHITE)
        viewport.submit(lines, 0, "", animate = false)
        layoutViewport(viewport)
        val stableHeight = viewport.height
        viewport.submit(lines, 1, "", animate = true)
        layoutViewport(viewport)
        viewport.stopScrolling()
        assertEquals(stableHeight, viewport.height)
        viewport.submit(lines, 2, "", animate = false)
        layoutViewport(viewport)
        assertEquals(stableHeight, viewport.height)
        assertEquals(0f, row(viewport, "last").pivotX, 0f)

        viewport.configure(20f, 1, Gravity.CENTER, Color.WHITE)
        layoutViewport(viewport)
        assertEquals(1, viewport.childCount)
        assertEquals("last", (viewport.getChildAt(0) as DesktopLyricView).text.toString())
        assertTrue(viewport.height < stableHeight)
        viewport.configure(26f, 4, Gravity.END, Color.WHITE)
        layoutViewport(viewport)
        assertTrue(viewport.height > stableHeight)
        assertEquals(row(viewport, "last").width.toFloat(), row(viewport, "last").pivotX, 0f)

        viewport.submit(emptyList(), -1, "new song", animate = false)
        layoutViewport(viewport)
        assertEquals(1, viewport.childCount)
        assertEquals("new song", viewport.contentDescription.toString())
    }

    @Test
    fun viewportWordProgressReusesRowsAndTheirTextLayout() = onMain {
        val viewport = DesktopLyricViewport(testContext())
        val lines = listOf(LyricLine(0, "KARAOKE", words = listOf(LyricWord("KARAOKE", 0, 1000))))
        viewport.configure(24f, 3, Gravity.CENTER, Color.WHITE)
        viewport.submit(lines, 0, "", animate = false)
        layoutViewport(viewport)
        val focused = row(viewport, "KARAOKE")
        val originalText = focused.text
        val originalLayout = focused.layout
        val before = drawViewportAt(viewport, 0)
        val after = drawViewportAt(viewport, 700)
        assertTrue(changedPixels(before, after) > 20)
        assertSame(originalText, focused.text)
        assertSame(originalLayout, focused.layout)
        assertSame(focused, row(viewport, "KARAOKE"))
    }

    @Test
    fun movingWordFocusDoesNotReplaceTheUpcomingSentenceOrItsLayout() = onMain {
        val viewport = DesktopLyricViewport(testContext())
        val lines = listOf(
            LyricLine(0, "first", words = listOf(LyricWord("first", 0, 1000))),
            LyricLine(1000, "next", words = listOf(LyricWord("next", 1000, 2000))),
        )
        viewport.configure(24f, 4, Gravity.CENTER, Color.WHITE)
        viewport.submit(lines, 0, "", animate = false)
        layoutViewport(viewport)
        viewport.renderPosition(900)
        val next = row(viewport, "next")
        val text = next.text
        val layout = next.layout
        viewport.submit(lines, 1, "", animate = true)
        layoutViewport(viewport)
        viewport.renderPosition(1000)
        viewport.stopScrolling()
        assertSame(next, row(viewport, "next"))
        assertSame(text, next.text)
        assertSame(layout, next.layout)
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun testContext() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(viewport: DesktopLyricViewport, text: String): DesktopLyricView =
        (0 until viewport.childCount).map { viewport.getChildAt(it) as DesktopLyricView }.first { it.text.toString() == text }

    private fun layoutViewport(viewport: DesktopLyricViewport) {
        viewport.measure(MeasureSpec.makeMeasureSpec(dp(testContext(), 320), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(testContext(), 600), MeasureSpec.AT_MOST))
        viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
    }

    private fun drawViewportAt(viewport: DesktopLyricViewport, position: Long): Bitmap {
        viewport.renderPosition(position)
        val bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
        viewport.draw(Canvas(bitmap))
        return bitmap
    }

    @Test
    fun timedWordFillsProgressivelyWithoutReplacingTextOrLayout() {
        lateinit var before: Bitmap
        lateinit var middle: Bitmap
        lateinit var complete: Bitmap
        lateinit var originalText: CharSequence
        lateinit var originalLayout: android.text.Layout
        lateinit var view: DesktopLyricView

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createView(
                text = "KARAOKE",
                words = listOf(LyricWord("KARAOKE", 0, 1_000)),
                widthDp = 320,
                heightDp = 90,
            )
            originalText = view.text
            originalLayout = requireNotNull(view.layout)
            before = drawAt(view, 0)
            middle = drawAt(view, 500)
            complete = drawAt(view, 1_000)
        }

        assertSame(originalText, view.text)
        assertSame(originalLayout, view.layout)
        assertTrue("halfway progress should visibly fill glyph pixels", changedPixels(before, middle) > 20)
        assertTrue("completion bitmap should visibly differ from the partial frame", changedPixels(middle, complete) > 20)
        assertTrue(changedPixels(before, complete) > 20)
    }

    @Test
    fun ordinaryLyricsAndMismatchedWordsStayUnchangedAcrossPositions() {
        lateinit var ordinaryAtStart: Bitmap
        lateinit var ordinaryLater: Bitmap
        lateinit var mismatchAtStart: Bitmap
        lateinit var mismatchLater: Bitmap

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val ordinary = createView("普通 LRC 歌词", emptyList(), widthDp = 320, heightDp = 90)
            ordinaryAtStart = drawAt(ordinary, 0)
            ordinaryLater = drawAt(ordinary, 8_000)

            val mismatch = createView(
                text = "没有逐词对应",
                words = listOf(LyricWord("另一句", 0, 1_000)),
                widthDp = 320,
                heightDp = 90,
            )
            mismatchAtStart = drawAt(mismatch, 0)
            mismatchLater = drawAt(mismatch, 8_000)
        }

        assertEquals(0, changedPixels(ordinaryAtStart, ordinaryLater))
        assertEquals(0, changedPixels(mismatchAtStart, mismatchLater))
    }

    @Test
    fun translationKeepsItsOwnColorAndIsNeverIncludedInWordFill() {
        lateinit var start: Bitmap
        lateinit var complete: Bitmap
        var translationTop = 0
        var translationBottom = 0

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = createView(
                text = "HELLO\n你好",
                translationRange = 6..7,
                words = listOf(LyricWord("HELLO", 0, 1_000)),
                widthDp = 320,
                heightDp = 140,
            )
            val layout = requireNotNull(view.layout)
            translationTop = view.totalPaddingTop + layout.getLineTop(1)
            translationBottom = view.totalPaddingTop + layout.getLineBottom(1)
            start = drawAt(view, 0)
            complete = drawAt(view, 1_000)
        }

        assertTrue("translation should be visible", countNonBlack(start, translationTop, translationBottom) > 0)
        assertEquals(
            "word-progress overlay must not recolor translation pixels",
            0,
            changedPixels(start, complete, translationTop, translationBottom),
        )
    }

    @Test
    fun wrappedAndRtlWordsKeepTheProgressMaskInsideTheirNativeGlyphs() {
        lateinit var wrappedStart: Bitmap
        lateinit var wrappedPartial: Bitmap
        lateinit var rtlStart: Bitmap
        lateinit var rtlPartial: Bitmap
        var rtlRevealMinX = 0f
        var rtlThresholdX = 0f

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wrappedText = "SUPERCALIFRAGILISTICEXPIALIDOCIOUS"
            val wrapped = createView(
                text = wrappedText,
                words = listOf(LyricWord(wrappedText, 0, 1_000)),
                widthDp = 120,
                heightDp = 260,
                maxLines = 4,
            )
            assertTrue("fixture should wrap", requireNotNull(wrapped.layout).lineCount > 1)
            wrappedStart = drawAt(wrapped, 0)
            wrappedPartial = drawAt(wrapped, 520)

            val rtlText = "مرحبا"
            val rtl = createView(
                text = rtlText,
                words = listOf(LyricWord(rtlText, 0, 1_000)),
                widthDp = 260,
                heightDp = 100,
                layoutDirection = View.LAYOUT_DIRECTION_RTL,
            )
            val selection = Path()
            requireNotNull(rtl.layout).getSelectionPath(0, rtlText.length, selection)
            val bounds = RectF().also { selection.computeBounds(it, true) }
            rtlThresholdX = rtl.totalPaddingLeft + bounds.left + bounds.width() * 0.50f - 4f
            rtlStart = drawAt(rtl, 0)
            rtlPartial = drawAt(rtl, 350)
            rtlRevealMinX = changedBounds(rtlStart, rtlPartial)?.first ?: Float.POSITIVE_INFINITY
        }

        assertTrue("wrapped progress must visibly advance", changedPixels(wrappedStart, wrappedPartial) > 0)
        assertChangedPixelsStayOnExistingGlyphs(wrappedStart, wrappedPartial)
        assertTrue("RTL progress must visibly advance", changedPixels(rtlStart, rtlPartial) > 0)
        assertChangedPixelsStayOnExistingGlyphs(rtlStart, rtlPartial)
        assertTrue("RTL fill should start on the visual right", rtlRevealMinX >= rtlThresholdX)
    }

    private fun createView(
        text: String,
        words: List<LyricWord>,
        widthDp: Int,
        heightDp: Int,
        translationRange: IntRange? = null,
        maxLines: Int = 5,
        layoutDirection: Int = View.LAYOUT_DIRECTION_LTR,
    ): DesktopLyricView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = DesktopLyricView(context)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        view.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        view.gravity = Gravity.TOP or Gravity.START
        view.layoutDirection = layoutDirection
        view.maxLines = maxLines
        view.setBackgroundColor(Color.BLACK)
        view.setLyricContent(text, translationRange, words, Color.WHITE)
        val width = dp(context, widthDp)
        val height = dp(context, heightDp)
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun drawAt(view: DesktopLyricView, positionMs: Long): Bitmap {
        view.renderPosition(positionMs)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun changedPixels(first: Bitmap, second: Bitmap, top: Int = 0, bottom: Int = first.height): Int {
        var changed = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(first.height)) {
            for (x in 0 until first.width) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
            }
        }
        return changed
    }

    private fun countNonBlack(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)) {
            for (x in 0 until bitmap.width) if (bitmap.getPixel(x, y) != Color.BLACK) count++
        }
        return count
    }

    private fun assertChangedPixelsStayOnExistingGlyphs(before: Bitmap, after: Bitmap) {
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) {
                    assertFalse("overlay escaped the original glyph mask at ($x,$y)", before.getPixel(x, y) == Color.BLACK)
                }
            }
        }
    }

    private fun changedBounds(first: Bitmap, second: Bitmap): Pair<Float, Float>? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                }
            }
        }
        return if (minX == Int.MAX_VALUE) null else minX.toFloat() to maxX.toFloat()
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
