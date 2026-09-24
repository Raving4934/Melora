package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricsUiConfig

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersiveLyricsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultViewportRemainsStableWhenImmersiveIsFalse() {
        val lines = listOf(
            LyricLine(0L, "普通一"),
            LyricLine(1_000L, "普通二"),
            LyricLine(2_000L, "普通三"),
        )
        val position = mutableLongStateOf(0L)
        setViewport(lines, position, height = 280.dp, motionEnabled = false)
        composeRule.waitForIdle()

        val firstViewport = viewportBounds()
        val firstLine = textBounds("普通一")
        composeRule.runOnIdle { position.longValue = 1_000L }
        composeRule.waitForIdle()

        val secondViewport = viewportBounds()
        val secondLine = textBounds("普通二")
        assertEquals("ordinary viewport top changed", firstViewport.top, secondViewport.top, 0.5f)
        assertEquals("ordinary viewport height changed", firstViewport.height, secondViewport.height, 0.5f)
        assertEquals("ordinary focus lost its center anchor", firstLine.center.y, secondLine.center.y, 2f)
    }

    @Test
    fun immersiveViewportCentersFocusAndAppliesThreeDepthScales() {
        val lines = listOf(
            LyricLine(0L, "远处上"),
            LyricLine(1_000L, "近处上"),
            LyricLine(2_000L, "沉浸焦点"),
            LyricLine(3_000L, "近处下"),
            LyricLine(4_000L, "远处下"),
        )
        setViewport(
            lines = lines,
            position = mutableLongStateOf(2_000L),
            height = 600.dp,
            motionEnabled = false,
            immersive = mutableStateOf(true),
        )
        composeRule.waitForIdle()

        val viewport = viewportBounds()
        val focus = textBounds("沉浸焦点")
        val near = textBounds("近处上")
        val far = textBounds("远处上")
        assertEquals("immersive focus must be vertically centered", viewport.center.y, focus.center.y, 2f)
        assertEquals("immersive rows must be horizontally centered", viewport.center.x, focus.center.x, 2f)
        assertEquals("near rows must share the centered axis", viewport.center.x, near.center.x, 2f)
        assertEquals("far rows must share the centered axis", viewport.center.x, far.center.x, 2f)
        assertTrue("near row should be smaller than focus", near.width < focus.width)
        assertTrue("far row should be smaller than near row", far.width < near.width)
    }

    @Test
    fun immersiveTypographyUses34spAndKeepsTranslationStack() {
        val line = LyricLine(
            startMs = 0L,
            text = "Immersive focus",
            translation = "沉浸翻译",
            romanization = "chén jìn fān yì",
        )
        val immersion = mutableStateOf(false)
        setViewport(
            lines = listOf(line),
            position = mutableLongStateOf(0L),
            height = 320.dp,
            config = LyricsUiConfig(fontSizeSp = 20f),
            motionEnabled = false,
            immersive = immersion,
        )
        composeRule.waitForIdle()
        val ordinaryInk = foregroundBounds(composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())

        composeRule.runOnIdle { immersion.value = true }
        composeRule.waitForIdle()

        val viewport = viewportBounds()
        val lyric = textBounds("Immersive focus")
        val translation = textBounds("沉浸翻译")
        val romanization = textBounds("chén jìn fān yì")
        val immersiveInk = foregroundBounds(composeRule.onNodeWithTag(VIEWPORT_TAG).captureToImage())
        assertTrue("immersive 34sp baseline should render larger than ordinary text", immersiveInk.height > ordinaryInk.height)
        assertEquals("translated immersive row must stay centered as a whole", viewport.center.y, (lyric.top + romanization.bottom) / 2f, 2f)
        assertEquals("immersive translation must remain centered", viewport.center.x, translation.center.x, 2f)
        assertTrue("translation must remain below the main lyric", translation.top > lyric.bottom)
        assertTrue("romanization must remain below the translation", romanization.top > translation.bottom)
        composeRule.onNodeWithText("Immersive focus").assertIsDisplayed()
    }

    @Test
    fun immersiveTypographyUsesReducedLineHeightAndRowSpacingWhileCenteringTheFullStack() {
        val lines = listOf(
            LyricLine(0L, "沉浸一", translation = "译一", romanization = "yi", endMs = 10_000L),
            LyricLine(2_000L, "沉浸二", endMs = 10_000L),
            LyricLine(3_000L, "沉浸三", endMs = 10_000L),
        )
        setViewport(
            lines = lines,
            position = mutableLongStateOf(1_000L),
            height = 520.dp,
            motionEnabled = false,
            immersive = mutableStateOf(true),
            density = Density(1f),
        )
        composeRule.waitForIdle()

        val viewport = viewportBounds()
        val first = textBounds("沉浸一")
        val second = textBounds("沉浸二")
        val translation = textBounds("译一")
        val romanization = textBounds("yi")
        val expectedMainLineHeight = 34f * 1.22f
        val firstRow = composeRule.onNode(hasText("沉浸一") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val secondRow = composeRule.onNode(hasText("沉浸二") and hasClickAction()).fetchSemanticsNode().boundsInRoot

        val renderedStyle = textLayout("沉浸一").layoutInput.style
        assertEquals(34f, renderedStyle.fontSize.value, 0.01f)
        assertEquals("沉浸行高规格必须应用到实际文字布局", expectedMainLineHeight, renderedStyle.lineHeight.value, 0.01f)
        assertEquals("副文本算在上一行内部，行间距应单独保持0.44倍字号", 34f * 0.44f,
            secondRow.top - firstRow.bottom, 2f)
        assertEquals("translation/romanization stack must stay centered as one row", viewport.center.y,
            (first.top + romanization.bottom) / 2f, 3f)
        assertTrue("translation must remain below the main lyric", translation.top > first.bottom)
        assertTrue("romanization must remain below the translation", romanization.top > translation.bottom)
    }

    @Test
    fun ordinaryTypographyKeepsTheOriginalLineHeightAndRowSpacing() {
        val lines = listOf(
            LyricLine(0L, "普通一", endMs = 10_000L),
            LyricLine(0L, "普通二", endMs = 10_000L),
        )
        setViewport(
            lines = lines,
            position = mutableLongStateOf(1_000L),
            height = 300.dp,
            config = LyricsUiConfig(fontSizeSp = 20f),
            motionEnabled = false,
            density = Density(1f),
        )
        composeRule.waitForIdle()

        val first = textBounds("普通一")
        val second = textBounds("普通二")
        val renderedStyle = textLayout("普通一").layoutInput.style
        assertEquals(20f, renderedStyle.fontSize.value, 0.01f)
        assertEquals("普通行高规格保持1.3倍，不假设系统中文字体的字形框高度", 26f, renderedStyle.lineHeight.value, 0.01f)
        assertEquals("ordinary row spacing must remain 0.66x", 20f * 0.66f,
            second.top - first.bottom, 2f)
    }

    @Test
    fun lyricViewportFadeBuffersBothEdgesInTheRenderedAlpha() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(120.dp).background(Color.Black)) {
                    Box(Modifier.fillMaxSize().lyricViewportFade().testTag(FADE_TAG)) {
                        Box(Modifier.fillMaxSize().background(Color.White))
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onNodeWithTag(FADE_TAG).captureToImage().asAndroidBitmap()
        val topAlpha = fadeLevelAt(bitmap, 0f)
        val upperBufferAlpha = fadeLevelAt(bitmap, 0.06f)
        val centerAlpha = fadeLevelAt(bitmap, 0.5f)
        val lowerBufferAlpha = fadeLevelAt(bitmap, 0.94f)
        val bottomAlpha = fadeLevelAt(bitmap, 1f)

        assertTrue("top edge must be transparent", topAlpha < 32)
        assertTrue("upper fade must retain a measurable buffer", upperBufferAlpha in 64..192)
        assertTrue("center must remain opaque", centerAlpha > 240)
        assertTrue("lower fade must retain a measurable buffer", lowerBufferAlpha in 64..192)
        assertTrue("bottom edge must be transparent", bottomAlpha < 32)
    }

    @Test
    fun immersiveClickStillReturnsTheOriginalLineForSeek() {
        val target = LyricLine(12_345L, "seek in immersive")
        var clicked: LyricLine? = null
        setViewport(
            lines = listOf(target),
            position = mutableLongStateOf(0L),
            height = 220.dp,
            motionEnabled = false,
            immersive = mutableStateOf(true),
            onLineClick = { clicked = it },
        )
        composeRule.waitForIdle()

        composeRule.onNode(hasText(target.text) and hasClickAction()).performClick()
        composeRule.runOnIdle {
            assertSame("immersive line click must keep the seek model", target, clicked)
        }
    }

    @Test
    fun overlappingActiveVoicesAreBothFullSizeInsteadOfBlurringOneSinger() {
        val lines = listOf(
            LyricLine(0L, "正在唱甲", endMs = 5_000L),
            LyricLine(1_000L, "正在唱乙", endMs = 5_000L),
        )
        setViewport(lines, mutableLongStateOf(2_000L), 340.dp,
            motionEnabled = false, immersive = mutableStateOf(true))
        composeRule.waitForIdle()
        assertEquals("同时演唱的声部不能按非焦点缩小", textBounds("正在唱甲").width, textBounds("正在唱乙").width, 1f)
    }

    private fun setViewport(
        lines: List<LyricLine>,
        position: State<Long>,
        height: Dp,
        config: LyricsUiConfig = LyricsUiConfig(),
        motionEnabled: Boolean = true,
        immersive: State<Boolean> = mutableStateOf(false),
        onLineClick: (LyricLine) -> Unit = {},
        density: Density? = null,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides (density ?: LocalDensity.current)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PlayerAppearanceProvider(dark = true, artworkColor = Color(0xFF3A78FF)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(height)
                                .background(Color.Black)
                                .testTag(VIEWPORT_TAG),
                        ) {
                            LyricsViewport(
                                lines = lines,
                                position = position,
                                config = config,
                                modifier = Modifier.fillMaxSize(),
                                motionEnabled = motionEnabled,
                                immersive = immersive.value,
                                onLineClick = onLineClick,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun viewportBounds() = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun textBounds(text: String) = composeRule
        .onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    private fun fadeLevelAt(bitmap: android.graphics.Bitmap, fraction: Float): Int {
        val y = ((bitmap.height - 1) * fraction).roundToInt().coerceIn(0, bitmap.height - 1)
        return AndroidColor.red(bitmap.getPixel(bitmap.width / 2, y))
    }

    private fun foregroundBounds(image: ImageBitmap): PixelBounds {
        val bitmap = image.asAndroidBitmap()
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel)) > 96) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        check(right >= left && bottom >= top) { "lyrics screenshot contains no readable foreground" }
        return PixelBounds(left, top, right + 1, bottom + 1)
    }

    private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private companion object {
        const val VIEWPORT_TAG = "immersive-lyrics-viewport"
        const val FADE_TAG = "immersive-lyrics-fade"
    }
}
