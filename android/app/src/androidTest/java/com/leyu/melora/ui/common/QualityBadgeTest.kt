package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arbitraryMeasuredBitratesAndExistingQualityLevelsAreVisible() {
        val labels = listOf("96K", "128K", "165K", "192K", "256K", "320K", "HQ", "SQ", "HR", "MASTER")
        compose.setContent {
            CompositionLocalProvider(LocalBadgeThemeDark provides false) {
                Column { labels.forEach { BadgePill(it) } }
            }
        }
        labels.forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHeightIsEqualTo(13.dp)
            assertVisibleGlyph(it, dark = false)
        }
    }

    @Test fun numericBadgeDoesNotShiftWhenBitrateOrThemeChanges() {
        val label = mutableStateOf("165K")
        val dark = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalBadgeThemeDark provides dark.value) {
                Row { BadgePill(label.value) }
            }
        }
        val before = compose.onNodeWithContentDescription("165K").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { label.value = "96K"; dark.value = true }
        val node = compose.onNodeWithContentDescription("96K").assertIsDisplayed()
        assertEquals(before, node.fetchSemanticsNode().boundsInRoot)
        node.assertWidthIsEqualTo(26.dp).assertHeightIsEqualTo(13.dp)
        assertVisibleGlyph("96K", dark = true)
    }

    @Test fun largeSystemFontKeepsTheSameBadgeGeometryAsVectors() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalBadgeThemeDark provides true,
            ) { Row { BadgePill("96K"); BadgePill("HR") } }
        }
        compose.onNodeWithContentDescription("96K").assertIsDisplayed()
            .assertWidthIsEqualTo(26.dp).assertHeightIsEqualTo(13.dp)
        compose.onNodeWithContentDescription("HR").assertIsDisplayed().assertHeightIsEqualTo(13.dp)
        assertVisibleGlyph("96K", dark = true)
    }

    private fun assertVisibleGlyph(label: String, dark: Boolean) {
        val pixels = compose.onNodeWithContentDescription(label).captureToImage().toPixelMap()
        val ink = badgeThemeColor(label, dark)
        // 排除边框，只检查中部确有文字像素，避免“语义节点存在、实际只画空框”漏检。
        assertTrue("missing badge glyph: $label", (pixels.height / 4 until pixels.height * 3 / 4).any { y ->
            (pixels.width / 4 until pixels.width * 3 / 4).any { x ->
                val color = pixels[x, y]
                abs(color.red - ink.red) < 0.08f && abs(color.green - ink.green) < 0.08f && abs(color.blue - ink.blue) < 0.08f
            }
        })
    }
}
