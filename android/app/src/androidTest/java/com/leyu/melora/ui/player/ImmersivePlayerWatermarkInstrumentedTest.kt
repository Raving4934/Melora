package com.leyu.melora.ui.player

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersivePlayerWatermarkInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun commonLongTitlesUseMeasuredWrappingWithoutEllipsisOrRotatedOverflow() {
        val titles = listOf(
            "夜航星河与海风之间的漫长回声·".repeat(2),
            "夜航星河与海风之间的漫长回声·The Long Way Home".repeat(2),
            "夜航星河与海风之间的漫长回声·Long Way Home Under A Quiet Moon".repeat(2),
        )
        titles.forEach { title ->
            assertTrue("test title should cover the 30~100 character range", title.length in 30..100)
        }

        val measuredRef = AtomicReference<List<MeasuredWatermark>>(emptyList())
        composeRule.setContent {
            val measurer = rememberTextMeasurer(cacheSize = 8)
            val density = LocalDensity.current
            SideEffect {
                measuredRef.set(
                    titles.map { title ->
                        val widthPx = with(density) { 320.dp.roundToPx() }
                        val heightPx = with(density) { 260.dp.roundToPx() }
                        val marginPx = with(density) { 12.dp.roundToPx() }
                        MeasuredWatermark(
                            title = title,
                            layout = measureImmersiveWatermark(
                                text = title,
                                measurer = measurer,
                                baseStyle = TextStyle.Default,
                                widthPx = widthPx,
                                heightPx = heightPx,
                                density = density,
                            ),
                            widthPx = widthPx,
                            heightPx = heightPx,
                            marginPx = marginPx,
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val measured = measuredRef.get()
        assertEquals("all long-title cases must be measured", titles.size, measured.size)
        measured.forEach { result ->
            val layout = result.layout
            assertNotNull("watermark measurement did not complete", layout)
            assertEquals("measured text must remain complete", result.title, layout.layoutInput.text.text)
            assertTrue("long title should wrap inside the finite canvas", layout.lineCount > 1)
            assertTrue(
                "long title should retain an adaptive size instead of collapsing to the minimum",
                layout.layoutInput.style.fontSize.value > 14f,
            )
            for (line in 0 until layout.lineCount) {
                assertFalse("watermark text must not ellipsize", layout.isLineEllipsized(line))
            }
            assertFalse("watermark layout overflowed horizontally", layout.didOverflowWidth)
            assertFalse("watermark layout overflowed vertically", layout.didOverflowHeight)
            assertFalse("watermark glyphs were clipped by the measured layout", layout.hasVisualOverflow)

            val angle = Math.toRadians(8.0)
            val rotatedWidth = layout.size.width * cos(angle) + layout.size.height * sin(angle)
            val rotatedHeight = layout.size.width * sin(angle) + layout.size.height * cos(angle)
            val widthLimit = result.widthPx - result.marginPx * 2f
            val heightLimit = result.heightPx - result.marginPx * 2f
            assertTrue(
                "-8° watermark rotation exceeds the safe horizontal bounds",
                rotatedWidth <= widthLimit + 1f,
            )
            assertTrue(
                "-8° watermark rotation exceeds the safe vertical bounds",
                rotatedHeight <= heightLimit + 1f,
            )
        }
    }

    private data class MeasuredWatermark(
        val title: String,
        val layout: TextLayoutResult,
        val widthPx: Int,
        val heightPx: Int,
        val marginPx: Int,
    )
}
