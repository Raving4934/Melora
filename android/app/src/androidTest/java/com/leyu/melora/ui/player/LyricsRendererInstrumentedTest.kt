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
import android.os.SystemClock
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
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
    fun delayedPlaybackSamplesAreProjectedBeforeFirstDrawAndEachRefresh() {
        composeRule.mainClock.autoAdvance = false
        val state = mutableStateOf(PlayerUiState(current = UiTrack("clock", "时钟测试", "", ""),
            positionMs = 1_000L, positionSampleRealtimeMs = SystemClock.elapsedRealtime() - 250L,
            positionAdvancing = true, durationMs = 100_000L))
        lateinit var position: State<Long>
        composeRule.setContent { position = rememberLyricPosition(state.value, visible = true) }
        try {
            composeRule.runOnIdle {
                assertTrue("初次展示不能先画过期采样值: ${position.value}", position.value >= 1_250L)
            }
            repeat(3) { index ->
                val sample = 2_000L + index * 1_000L
                composeRule.runOnIdle {
                    state.value = state.value.copy(positionMs = sample,
                        positionSampleRealtimeMs = SystemClock.elapsedRealtime() - 250L)
                }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle {
                    assertTrue("刷新锚点不能让已填充文字退回旧采样: ${position.value}", position.value >= sample + 250L)
                }
            }
        } finally { composeRule.mainClock.autoAdvance = true }
    }

    @Test
    fun lyricClockStillReanchorsForPausedSeekBufferingSpeedAndTrackChange() {
        composeRule.mainClock.autoAdvance = false
        val state = mutableStateOf(PlayerUiState(current = UiTrack("clock", "时钟测试", "", ""),
            positionMs = 4_000L, positionSampleRealtimeMs = SystemClock.elapsedRealtime(),
            positionAdvancing = false, durationMs = 100_000L))
        lateinit var position: State<Long>
        composeRule.setContent { position = rememberLyricPosition(state.value, visible = true) }
        fun publish(next: PlayerUiState, expected: Long, advancing: Boolean = false) {
            composeRule.runOnIdle { state.value = next }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                if (advancing) assertTrue(position.value >= expected) else assertEquals(expected, position.value)
            }
        }
        try {
            publish(state.value.copy(positionMs = 500L, positionSampleRealtimeMs = SystemClock.elapsedRealtime()), 500L)
            publish(state.value.copy(positionMs = 900L, positionAdvancing = true, buffering = true,
                positionSampleRealtimeMs = SystemClock.elapsedRealtime() - 250L), 900L)
            publish(state.value.copy(buffering = false, resolving = true,
                positionSampleRealtimeMs = SystemClock.elapsedRealtime() - 500L), 900L)
            publish(state.value.copy(resolving = false, speed = 2f,
                positionSampleRealtimeMs = SystemClock.elapsedRealtime() - 250L), 1_400L, advancing = true)
            publish(state.value.copy(current = UiTrack("next", "下一首", "", ""), positionMs = 0L,
                positionAdvancing = false, positionSampleRealtimeMs = SystemClock.elapsedRealtime()), 0L)
        } finally { composeRule.mainClock.autoAdvance = true }
    }

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
        val image = composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage()
        val bitmap = image.asAndroidBitmap()
        val background = bitmap.getPixel(0, 0)
        for (label in listOf("主唱", "对唱")) {
            val bounds = textBounds(label)
            var left = bitmap.width
            var right = -1
            val top = (bounds.top - viewportBounds().top).toInt().coerceAtLeast(0)
            val bottom = (bounds.bottom - viewportBounds().top).toInt().coerceAtMost(bitmap.height)
            for (y in top until bottom) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(background)) > 20) {
                    left = minOf(left, x); right = maxOf(right, x)
                }
            }
            assertTrue("$label must render visible glyphs", right >= left)
            assertEquals("marquee-enabled active and inactive glyphs must both be centered: $label",
                bitmap.width / 2f, (left + right) / 2f, 8f)
        }
        saveProof("mini-aligned", image)
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
    fun lightWordFillUsesTheSameCoverInkAsPlayerControls() {
        val line = LyricLine(0, "Hello world", words = listOf(LyricWord("Hello", 0, 1000), LyricWord(" world", 1000, 2000)))
        setViewport(listOf(line), mutableLongStateOf(1500L), height = 180.dp, playerDark = false)
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage()
        val bitmap = image.asAndroidBitmap()
        var dark = 0
        var matchedInk = 0
        val ink = playerColorsFor(false, Color(0xFF3A78FF)).textPrimary.toArgb()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            if (minOf(red, green, blue) < 70) {
                dark++
                if (abs(red - android.graphics.Color.red(ink)) <= 8 &&
                    abs(green - android.graphics.Color.green(ink)) <= 8 &&
                    abs(blue - android.graphics.Color.blue(ink)) <= 8) matchedInk++
            }
        }
        assertTrue("no readable filled glyphs", dark > 50)
        assertTrue("逐字填充必须跟随播放页墨水", matchedInk > 50)
        saveProof("themed-fill-light", image)
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

    @Test fun interludeKeepsFinishedLineAsReadingFocusWithoutEarlyHighlightOrScroll() {
        val lines = listOf(
            LyricLine(0L, "上一句", endMs = 1_000L, words = listOf(LyricWord("上一句", 0L, 1_000L))),
            LyricLine(7_000L, "下一句", endMs = 8_000L),
        )
        val position = mutableLongStateOf(500L)
        setViewport(lines, position, height = 320.dp, motionEnabled = false)
        composeRule.waitForIdle()
        val viewport = viewportBounds()
        val nextBounds = textBounds("下一句")
        fun brightPixels(text: String): Int {
            val image = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            var count = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (android.graphics.Color.blue(image.getPixel(x, y)) > 210) count++
            }
            return count
        }
        assertTrue(brightPixels("上一句") > 10)
        composeRule.runOnIdle { position.longValue = 2_000L }
        composeRule.onNodeWithTag("lyric-gap-indicator").assertDoesNotExist()
        assertTrue("间奏必须保留一行清晰的阅读焦点", brightPixels("上一句") > 10)
        saveProof("reading-focus", composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
        assertEquals(viewport, viewportBounds())
        assertEquals("间奏期间歌词不得被占位内容顶动", nextBounds, textBounds("下一句"))
        composeRule.runOnIdle { position.longValue = 6_999L }
        assertEquals("不能提前点亮下一句", 0, brightPixels("下一句"))
        composeRule.runOnIdle { position.longValue = 7_000L }
        assertTrue(brightPixels("下一句") > 10)
        assertEquals("焦点交接后上一句恢复弱化", 0, brightPixels("上一句"))
        composeRule.runOnIdle { position.longValue = 9_000L }
        composeRule.onNodeWithTag("lyric-gap-indicator").assertDoesNotExist()
        assertTrue("尾奏保留最后一句阅读焦点而非全屏同灰", brightPixels("下一句") > 10)
    }

    @Test fun miniRetainsReadingFocusDuringSilenceAndHandsOffAtTheNextStart() {
        val lines = listOf(LyricLine(0L, "mini上一句", endMs = 1_000L,
            words = listOf(LyricWord("mini上一句", 0L, 1_000L))), LyricLine(7_000L, "mini下一句", endMs = 8_000L))
        val position = mutableLongStateOf(500L)
        setViewport(lines, position, height = 160.dp, mini = true, motionEnabled = false)
        composeRule.waitForIdle()
        val viewport = viewportBounds()
        val previousBounds = textBounds("mini上一句")
        val nextBounds = textBounds("mini下一句")
        fun brightPixels(text: String): Int {
            val image = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            var count = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                // mini沿用封面蓝色墨水，检查蓝通道而非全屏的中性白色。
                if (android.graphics.Color.blue(image.getPixel(x, y)) > 210) count++
            }
            return count
        }
        composeRule.runOnIdle { position.longValue = 2_000L }
        assertTrue("mini间奏保留已唱句而非全灰", brightPixels("mini上一句") > 10)
        assertEquals(viewport, viewportBounds())
        assertEquals(previousBounds, textBounds("mini上一句"))
        assertEquals(nextBounds, textBounds("mini下一句"))
        saveProof("mini-reading-focus", composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
        composeRule.runOnIdle { position.longValue = 6_999L }
        assertEquals("mini不能提前点亮下一句", 0, brightPixels("mini下一句"))
        composeRule.runOnIdle { position.longValue = 7_000L }
        assertTrue(brightPixels("mini下一句") > 10)
        assertEquals("交接后上一句恢复弱化", 0, brightPixels("mini上一句"))
        composeRule.runOnIdle { position.longValue = 9_000L }
        assertTrue("mini尾奏保留最后一句", brightPixels("mini下一句") > 10)
        composeRule.runOnIdle { position.longValue = 2_000L }
        assertTrue("回拖后恢复正确阅读焦点", brightPixels("mini上一句") > 10)
        assertEquals(0, brightPixels("mini下一句"))
        composeRule.onNodeWithTag("lyric-gap-indicator").assertDoesNotExist()
    }

    @Test fun lightThemeInterludeKeepsOneDarkReadingAnchorInsteadOfUniformGray() {
        setViewport(listOf(LyricLine(0L, "已唱完的焦点句", endMs = 1_000L,
            words = listOf(LyricWord("已唱完的焦点句", 0L, 1_000L))), LyricLine(7_000L, "尚未开唱的下一句", endMs = 8_000L)),
            mutableLongStateOf(2_000L), height = 320.dp, motionEnabled = false, playerDark = false)
        fun darkPixels(text: String): Int {
            val image = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            var count = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (android.graphics.Color.red(image.getPixel(x, y)) < 70) count++
            }
            return count
        }
        assertTrue("浅色模式的阅读焦点必须清晰", darkPixels("已唱完的焦点句") > 30)
        assertEquals("下一句必须维持非焦点灰色", 0, darkPixels("尚未开唱的下一句"))
        saveProof("reading-focus-light", composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
    }

    @Test fun darkLyricsUsePlayerInkAndBlurOnlyNonFocusRows() = verifyThemedDepth(dark = true)

    @Test fun lightLyricsUsePlayerInkAndBlurOnlyNonFocusRows() = verifyThemedDepth(dark = false)

    @Test fun miniKeepsItsSizeAndRenderingWhenFullPageBlurIsToggled() = verifyThemedDepth(dark = true, mini = true)

    private fun verifyThemedDepth(dark: Boolean, mini: Boolean = false) {
        val artwork = Color(0xFFB34B20)
        val colors = playerColorsFor(dark, artwork)
        val config = mutableStateOf(LyricsUiConfig(fontSizeSp = 24f))
        val lines = listOf("远处已唱歌词", "相邻已唱歌词", "当前焦点歌词", "相邻等待歌词", "远处等待歌词")
            .mapIndexed { index, text -> LyricLine(index * 1_000L, text) }
        val position = mutableLongStateOf(2_500L)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                PlayerAppearanceProvider(dark = dark, artworkColor = artwork) {
                    LyricsViewport(lines, position, config.value,
                        modifier = Modifier.fillMaxWidth().height(480.dp).background(colors.background).testTag(VIEWPORT_TAG),
                        mini = mini, motionEnabled = false, onLineClick = {})
                }
            }
        }
        composeRule.waitForIdle()
        fun image(text: String) = composeRule.onNodeWithText(text, useUnmergedTree = true).captureToImage()
        val focusBefore = image("当前焦点歌词")
        val nextBefore = image("相邻等待歌词")
        val farBefore = image("远处等待歌词")
        val focusBounds = textBounds("当前焦点歌词")
        val nextBounds = textBounds("相邻等待歌词")
        val ink = colors.textPrimary.toArgb()
        val pixels = focusBefore.asAndroidBitmap()
        var inkPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels.getPixel(x, y)
            if (abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(ink)) <= 8 &&
                abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(ink)) <= 8 &&
                abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(ink)) <= 8) inkPixels++
        }
        assertTrue("焦点必须使用播放页封面墨水而非固定黑白", inkPixels > 30)
        if (!mini) assertEquals("焦点只做约6%视觉放大，不重排字号", 1f / 0.94f,
            focusBounds.width / nextBounds.width, 0.01f)
        composeRule.runOnIdle { config.value = config.value.copy(isBlurEnabled = true) }
        assertEquals("虚化不能影响焦点清晰度", 0, countDifferentPixels(focusBefore, image("当前焦点歌词")))
        assertEquals("开关虚化不能挤动歌词", focusBounds, textBounds("当前焦点歌词"))
        assertEquals(nextBounds, textBounds("相邻等待歌词"))
        if (mini) {
            assertEquals(0, countDifferentPixels(nextBefore, image("相邻等待歌词")))
            assertEquals(0, countDifferentPixels(farBefore, image("远处等待歌词")))
        } else {
            assertTrue("邻句应有实际虚化而非仅改灰色", countDifferentPixels(nextBefore, image("相邻等待歌词")) > 30)
            assertTrue("远句也必须进入景深", countDifferentPixels(farBefore, image("远处等待歌词")) > 30)
        }
        saveProof("depth-${if (dark) "dark" else "light"}${if (mini) "-mini" else ""}",
            composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
        composeRule.runOnIdle { config.value = config.value.copy(isBlurEnabled = false) }
        assertEquals("关闭后恢复原清晰文字", 0, countDifferentPixels(nextBefore, image("相邻等待歌词")))
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
