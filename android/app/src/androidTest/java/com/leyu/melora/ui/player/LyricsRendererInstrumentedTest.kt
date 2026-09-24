package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricsUiConfig

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.LyricAlignment
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.LyricWord
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsRendererInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun miniViewportKeepsFixedHeightAndCentersFirstMiddleAndLastLine() {
        val lines = listOf(
            LyricLine(startMs = 0L, text = "首句"),
            LyricLine(startMs = 2_000L, text = "中句"),
            LyricLine(startMs = 4_000L, text = "尾句"),
        )
        val position = mutableLongStateOf(0L)
        setViewport(
            lines = lines,
            position = position,
            mini = true,
            height = 96.dp,
        )
        composeRule.waitForIdle()

        val firstViewport = viewportBounds()
        val firstLine = textBounds("首句")
        assertEquals("mini focus must be at viewport center", firstViewport.center.y, firstLine.center.y, 2f)
        assertIsDisplayed("首句")

        composeRule.runOnIdle { position.longValue = 2_000L }
        composeRule.waitForIdle()
        val middleViewport = viewportBounds()
        val middleLine = textBounds("中句")
        assertIsDisplayed("中句")

        composeRule.runOnIdle { position.longValue = 4_000L }
        composeRule.waitForIdle()
        val lastViewport = viewportBounds()
        val lastLine = textBounds("尾句")
        assertIsDisplayed("尾句")

        assertEquals("mini viewport top changed while switching lines", firstViewport.top, middleViewport.top, 0.5f)
        assertEquals("mini viewport top changed at the tail", firstViewport.top, lastViewport.top, 0.5f)
        assertEquals("mini viewport height changed while switching lines", firstViewport.height, middleViewport.height, 0.5f)
        assertEquals("mini viewport height changed at the tail", firstViewport.height, lastViewport.height, 0.5f)
        assertEquals("first line was not kept at the mini focus position", firstLine.center.y, middleLine.center.y, 2f)
        assertEquals("last line was not kept at the mini focus position", firstLine.center.y, lastLine.center.y, 2f)
    }

    @Test
    fun fullViewportRendersTranslationAndRomanizationInOrder() {
        val line = LyricLine(
            startMs = 0L,
            text = "主歌词",
            translation = "translated lyric",
            romanization = "zhǔ gē cí",
        )
        setViewport(
            lines = listOf(line),
            position = mutableLongStateOf(0L),
            height = 300.dp,
        )
        composeRule.waitForIdle()

        val lyricBounds = textBounds("主歌词")
        val translationBounds = textBounds("translated lyric")
        val romanizationBounds = textBounds("zhǔ gē cí")

        assertEquals("translated row must be centered as a whole", viewportBounds().center.y, (lyricBounds.top + romanizationBounds.bottom) / 2f, 2f)
        assertIsDisplayed("主歌词")
        assertIsDisplayed("translated lyric")
        assertIsDisplayed("zhǔ gē cí")
        assertTrue("translation must be laid out below the source lyric", translationBounds.top > lyricBounds.bottom)
        assertTrue("romanization must be laid out below the translation", romanizationBounds.top > translationBounds.bottom)
    }

    @Test
    fun singerMetadataDoesNotMoveFullLyricsBetweenSides() {
        val lines = listOf(
            LyricLine(startMs = 0L, text = "左声部", alignment = LyricAlignment.Start),
            LyricLine(startMs = 1_000L, text = "右声部", alignment = LyricAlignment.End),
        )
        setViewport(
            lines = lines,
            position = mutableLongStateOf(0L),
            height = 300.dp,
        )
        composeRule.waitForIdle()

        val viewport = viewportBounds()
        val left = textBounds("左声部")
        val right = textBounds("右声部")

        assertIsDisplayed("左声部")
        assertIsDisplayed("右声部")
        assertEquals("singer metadata must not override page alignment", left.left, right.left, 0.5f)
        assertTrue("duet lines should remain inside the viewport", viewport.contains(left.center) && viewport.contains(right.center))
        saveProof("aligned", composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
    }

    @Test
    fun miniKeepsBothSingersAtTheCoverCenter() {
        val lines = listOf(LyricLine(0, "主唱", alignment = LyricAlignment.Start),
            LyricLine(1000, "对唱", alignment = LyricAlignment.End))
        setViewport(lines, mutableLongStateOf(0L), height = 120.dp, mini = true, centered = true)
        composeRule.waitForIdle()
        assertEquals(viewportBounds().center.x, textBounds("主唱").center.x, 0.5f)
        assertEquals(viewportBounds().center.x, textBounds("对唱").center.x, 0.5f)
        saveProof("mini-aligned", composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
    }

    @Test
    fun fullLyricsCenterSettingOverridesSingerMetadata() {
        val lines = listOf(LyricLine(0, "主唱", alignment = LyricAlignment.Start),
            LyricLine(1000, "对唱", alignment = LyricAlignment.End))
        setViewport(lines, mutableLongStateOf(0L), height = 220.dp, config = LyricsUiConfig(isCentered = true))
        composeRule.waitForIdle()
        assertEquals(viewportBounds().center.x, textBounds("主唱").center.x, 0.5f)
        assertEquals(viewportBounds().center.x, textBounds("对唱").center.x, 0.5f)
    }

    @Test
    fun lightFullLyricsUseNeutralInkInsteadOfTheCoverTint() {
        val line = LyricLine(0, "Hello world", words = listOf(LyricWord("Hello", 0, 1000), LyricWord(" world", 1000, 2000)))
        setViewport(listOf(line), mutableLongStateOf(1500L), height = 180.dp, playerDark = false)
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage()
        val bitmap = image.asAndroidBitmap()
        var dark = 0
        var tinted = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            if (minOf(red, green, blue) < 70) {
                dark++
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 16) tinted++
            }
        }
        assertTrue("no readable filled glyphs", dark > 50)
        assertEquals("cover tint leaked into lyric ink", 0, tinted)
        saveProof("monochrome-light", image)
    }

    @Test
    fun clickingLyricLineReportsTheExactSeekTarget() {
        val target = LyricLine(startMs = 12_345L, text = "seek target")
        var clicked: LyricLine? = null
        setViewport(
            lines = listOf(target),
            position = mutableLongStateOf(0L),
            height = 180.dp,
            onLineClick = { clicked = it },
        )
        composeRule.waitForIdle()

        composeRule.onNode(hasText(target.text) and hasClickAction()).performClick()
        composeRule.runOnIdle { assertSame("line click must pass the original lyric model", target, clicked) }
    }

    @Test
    fun browsingSuppressesClockFollowUntilThreeSecondsThenRecentersCurrentLine() {
        val lines = (0 until 12).map { index ->
            LyricLine(startMs = index * 1_000L, text = "line-$index")
        }
        val position = mutableLongStateOf(0L)
        setViewport(
            lines = lines,
            position = position,
            height = 320.dp,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(VIEWPORT_TAG).performTouchInput {
            swipe(Offset(centerX, height * 0.85f), Offset(centerX, height * 0.20f), durationMillis = 1_200)
        }
        composeRule.waitForIdle()
        // 屏幕密度和惯性会影响具体可见行，检查真实浏览锚点，不硬编码第7行。
        val browsedLine = requireNotNull(lines.drop(2).firstOrNull { isDisplayed(it.text) }).text
        val targetLine = requireNotNull(lines.drop(1).lastOrNull { !isDisplayed(it.text) })
        assertTrue("drag did not move away from the original focus", !isDisplayed("line-0") ||
            abs(textBounds("line-0").center.y - viewportBounds().center.y) > 20f)
        val beforeTimeAdvance = textBounds(browsedLine)

        composeRule.runOnIdle { position.longValue = targetLine.startMs }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        val duringBrowse = textBounds(browsedLine)

        assertTrue(
            "advancing the lyric clock must not steal the user's browse position",
            abs(beforeTimeAdvance.top - duringBrowse.top) <= 2f &&
                abs(beforeTimeAdvance.bottom - duringBrowse.bottom) <= 2f,
        )
        composeRule.onNodeWithText(browsedLine, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "current line must not steal the browse position before the grace period",
            !isDisplayed(targetLine.text),
        )

        // LaunchedEffect 的 delay 使用 Compose 测试时钟，不以 Thread.sleep 伪造时间推进。
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.waitForIdle()
        assertTrue("returned before the three-second grace period", !isDisplayed(targetLine.text))
        composeRule.mainClock.advanceTimeBy(1_100L)
        composeRule.waitForIdle()
        assertEquals("did not return to the current line", viewportBounds().center.y, textBounds(targetLine.text).center.y, 2f)
    }

    @Test
    fun longChineseEmojiRtlLineIsMeasuredAndDisplayed() {
        val text = "中文歌词 🎵✨ 这是一个需要换行的很长句子，保留表情并混排 שלום עולם مرحبا بالعالم，再继续补充一段没有空格的长文本 1234567890 ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        setViewport(
            lines = listOf(LyricLine(startMs = 0L, text = text)),
            position = mutableLongStateOf(0L),
            height = 320.dp,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        assertTrue("long mixed-script lyric should wrap to more than one visual line", textBounds(text).height > 44f)
    }

    @Test
    fun timedWordHighlightChangesCapturedPixelsWhenPositionAdvances() {
        val line = LyricLine(
            startMs = 0L,
            text = "hello world",
            words = listOf(
                LyricWord(text = "hello", startMs = 0L, endMs = 1_000L),
                LyricWord(text = " world", startMs = 1_000L, endMs = 2_000L),
            ),
        )
        val position = mutableLongStateOf(0L)
        val compositions = AtomicInteger()
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SideEffect { compositions.incrementAndGet() }
                Box(
                    modifier = Modifier
                        .size(width = 280.dp, height = 72.dp)
                        .background(Color.Black),
                ) {
                    TimedLyricText(
                        line = line,
                        position = position,
                        active = true,
                        color = Color.White,
                        style = TextStyle(
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(HIGHLIGHT_TAG)
                            .padding(8.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val before = composeRule.onNodeWithTag(HIGHLIGHT_TAG).captureToImage()

        val beforeCompositions = compositions.get()
        composeRule.runOnIdle { position.longValue = 1_500L }
        composeRule.waitForIdle()
        val after = composeRule.onNodeWithTag(HIGHLIGHT_TAG).captureToImage()
        assertEquals("word progress recomposed the host", beforeCompositions, compositions.get())
        val pixels = after.asAndroidBitmap()
        var brightPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width / 2) {
            val pixel = pixels.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 240 && android.graphics.Color.green(pixel) > 240 &&
                android.graphics.Color.blue(pixel) > 240) brightPixels++
        }
        assertTrue("completed words inherited the dim base text alpha", brightPixels > 50)
        saveProof("fill", after)

        assertTrue(
            "word highlight must change rendered pixels as the manual position advances",
            countDifferentPixels(before, after) > 24,
        )
    }

    @Test
    fun largeDocumentKeepsOnlyVisibleRowsComposed() {
        val lines = (0 until 10_000).map { LyricLine(it * 1_000L, "bulk-$it") }
        setViewport(lines, mutableLongStateOf(9_990_000L), height = 320.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("bulk-9990", useUnmergedTree = true).assertIsDisplayed()
        val composed = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue("virtualization composed $composed lyric rows", composed in 1..40)
    }

    @Test
    fun reducedMotionStillFollowsTheCurrentLine() {
        val lines = (0 until 20).map { LyricLine(it * 1_000L, "reduced-$it") }
        val position = mutableLongStateOf(0L)
        setViewport(lines, position, height = 320.dp, motionEnabled = false)
        composeRule.runOnIdle { position.longValue = 18_000L }
        composeRule.waitForIdle()
        assertEquals(viewportBounds().center.y, textBounds("reduced-18").center.y, 2f)
    }

    private fun saveProof(name: String, image: ImageBitmap) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lyrics-proof-$name.png")
        file.outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun setViewport(
        lines: List<LyricLine>,
        position: State<Long>,
        height: Dp,
        mini: Boolean = false,
        centered: Boolean = false,
        config: LyricsUiConfig = LyricsUiConfig(),
        motionEnabled: Boolean = true,
        playerDark: Boolean = true,
        onLineClick: (LyricLine) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme(colorScheme = if (playerDark) darkColorScheme() else lightColorScheme()) {
                PlayerAppearanceProvider(dark = playerDark, artworkColor = Color(0xFF3A78FF)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag(VIEWPORT_TAG),
                ) {
                    LyricsViewport(
                        lines = lines,
                        position = position,
                        config = config,
                        modifier = Modifier.fillMaxSize(),
                        mini = mini,
                        centered = centered,
                        motionEnabled = motionEnabled,
                        onLineClick = onLineClick,
                    )
                }
                }
            }
        }
    }

    private fun viewportBounds(): Rect = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    private fun textBounds(text: String): Rect = composeRule
        .onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    private fun assertIsDisplayed(text: String) {
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun isDisplayed(text: String): Boolean = runCatching {
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        true
    }.getOrDefault(false)

    private fun countDifferentPixels(before: ImageBitmap, after: ImageBitmap): Int {
        val beforeBitmap = before.asAndroidBitmap()
        val afterBitmap = after.asAndroidBitmap()
        val width = min(beforeBitmap.width, afterBitmap.width)
        val height = min(beforeBitmap.height, afterBitmap.height)
        var different = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (beforeBitmap.getPixel(x, y) != afterBitmap.getPixel(x, y)) different++
            }
        }
        return different
    }

    private companion object {
        const val VIEWPORT_TAG = "lyrics-viewport"
        const val HIGHLIGHT_TAG = "timed-lyric-highlight"
    }
}
