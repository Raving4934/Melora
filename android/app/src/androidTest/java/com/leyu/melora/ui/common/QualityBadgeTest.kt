package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.AudioQualityBadge
import com.leyu.melora.playback.AudioQualityTier
import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.QualityBadgeMode
import com.leyu.melora.ui.player.AudioInfoCard
import com.leyu.melora.ui.player.PlayerAppearanceProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyTierRendersAnIconAndReadableLabel() {
        val badges = listOf(
            AudioQualityBadge(AudioQualityTier.HiRes, "HI-RES"),
            AudioQualityBadge(AudioQualityTier.Lossless, "LOSSLESS"),
            AudioQualityBadge(AudioQualityTier.Master, "MASTER"),
            AudioQualityBadge(AudioQualityTier.Atmos, "ATMOS"),
            AudioQualityBadge(AudioQualityTier.Spatial, "3D SPATIAL"),
            AudioQualityBadge(AudioQualityTier.Dsd, "DSD"),
            AudioQualityBadge(AudioQualityTier.High, "HQ"),
            AudioQualityBadge(AudioQualityTier.Standard, "SD 128K"),
            AudioQualityBadge(AudioQualityTier.Economy, "ECO 96K"),
            AudioQualityBadge(AudioQualityTier.Low, "LQ 64K"),
            AudioQualityBadge(AudioQualityTier.LoFi, "LO-FI 32K"),
            AudioQualityBadge(AudioQualityTier.Mono, "MONO 24K"),
            AudioQualityBadge(AudioQualityTier.Opus, "OPUS 96K"),
        )
        assertEquals(AudioQualityTier.entries.toSet(), badges.map { it.tier }.toSet())
        compose.setContent {
            CompositionLocalProvider(LocalBadgeThemeDark provides false) {
                Column { badges.forEach { BadgePill(it, mode = QualityBadgeMode.IconText) } }
            }
        }
        badges.forEach {
            compose.onNodeWithContentDescription(it.label).assertIsDisplayed().assertHeightIsEqualTo(18.dp)
            assertVisibleGlyph(it.label, audioBadgeAppearance(it.tier).light)
        }
    }

    @Test fun everyModeDrawsTheSameSoftFillAtTheEdgeAndInside() {
        val badge = AudioQualityBadge(AudioQualityTier.Lossless, "LOSSLESS", "16/44.1", "FLAC")
        val palette = audioBadgeAppearance(badge.tier)
        val surfaces = listOf(false to Color(0xFFF3EBDF), true to Color(0xFF232C3A))
        compose.setContent {
            Column {
                surfaces.forEach { (dark, surface) ->
                    CompositionLocalProvider(LocalBadgeThemeDark provides dark) {
                        QualityBadgeMode.entries.forEach { mode ->
                            Box(Modifier.background(surface).testTag("${mode.name}-$dark")) {
                                BadgePill(badge, mode = mode)
                            }
                        }
                    }
                }
            }
        }

        surfaces.forEach { (dark, surface) ->
            QualityBadgeMode.entries.forEach { mode ->
                val pixels = compose.onNodeWithTag("${mode.name}-$dark").assertIsDisplayed()
                    .captureToImage().toPixelMap()
                val fillAlpha = if (mode == QualityBadgeMode.Compact) {
                    if (dark) 0.15f else 0.11f
                } else {
                    if (dark) 0.145f else 0.115f
                }
                val ink = if (dark) palette.dark else palette.light
                val expectedFill = ink.copy(alpha = fillAlpha).compositeOver(surface)
                val inset = maxOf(1, pixels.height / 8)
                val sampleRows = listOf(0, inset, pixels.height - 1 - inset, pixels.height - 1)
                // 从胶囊直边到内部留白采样，避开文字与两侧圆弧。
                assertTrue("${mode.name}, dark=$dark: edge and interior should share the soft fill", sampleRows.all { y ->
                    (pixels.width * 3 / 8 until pixels.width * 5 / 8).all { x ->
                        pixels[x, y].resembles(expectedFill, tolerance = 0.022f)
                    }
                })
                val textStart = if (mode == QualityBadgeMode.Split) pixels.width * 2 / 3 else pixels.width / 3
                assertTrue("${mode.name}, dark=$dark: visible ink expected", (pixels.height / 4 until pixels.height * 3 / 4).any { y ->
                    (textStart until pixels.width * 7 / 8).any { x ->
                        !pixels[x, y].resembles(expectedFill, tolerance = 0.10f)
                    }
                })
            }
        }
    }

    @Test fun opusRenderUsesTheBitratePaletteInAllModesWhenKnown() {
        val economy = requireNotNull(AudioSpecification("audio/opus", 48_000, 96_000).qualityBadge)
        val unknown = requireNotNull(AudioSpecification("audio/opus", 48_000, -1).qualityBadge)
        val dark = mutableStateOf(false)
        val badges = listOf(economy, unknown)
        compose.setContent {
            val surface = if (dark.value) Color(0xFF191C23) else Color(0xFFF4F5F7)
            CompositionLocalProvider(LocalBadgeThemeDark provides dark.value) {
                Column(Modifier.background(surface)) {
                    QualityBadgeMode.entries.forEach { mode ->
                        Row {
                            badges.forEach { badge ->
                                Box(Modifier.testTag("${mode.name}-${badge.label}")) {
                                    BadgePill(badge, mode = mode)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            val economyInk = if (night) Color(0xFF9CB6A7) else Color(0xFF50665B)
            val opusInk = if (night) Color(0xFFA0B4BD) else Color(0xFF4E6570)
            for (mode in QualityBadgeMode.entries) {
                for ((badge, ink) in listOf(economy to economyInk, unknown to opusInk)) {
                    val pixels = compose.onNodeWithTag("${mode.name}-${badge.label}").captureToImage().toPixelMap()
                    val hasGlyph = (pixels.height / 4 until pixels.height * 3 / 4).any { y ->
                        (pixels.width / 3 until pixels.width * 7 / 8).any { x -> pixels[x, y].resembles(ink, 0.016f) }
                    }
                    assertTrue("$mode, ${badge.label}, dark=$night: missing foreground $ink", hasGlyph)
                }
            }
        }
    }

    @Test fun preferenceSwitchesBetweenTheThreeBadgeModes() {
        val badge = AudioQualityBadge(AudioQualityTier.HiRes, "HI-RES", "192 kHz")
        val original = MeloraSettings.qualityBadgeMode.value
        try {
            MeloraSettings.qualityBadgeMode.value = QualityBadgeMode.IconText
            compose.setContent { BadgePill(badge) }
            fun width(description: String): Float {
                val pill = compose.onNodeWithContentDescription(description).assertIsDisplayed()
                    .assertHeightIsEqualTo(18.dp)
                return pill.fetchSemanticsNode().boundsInRoot.width
            }
            val iconWidth = width("HI-RES")
            compose.runOnIdle { MeloraSettings.qualityBadgeMode.value = QualityBadgeMode.Split }
            val specWidth = width("HI-RES · 192 kHz")
            compose.runOnIdle { MeloraSettings.qualityBadgeMode.value = QualityBadgeMode.Compact }
            val compact = compose.onNodeWithContentDescription("HI-RES · 192 kHz").assertIsDisplayed()
                .assertHeightIsEqualTo(17.dp).assertWidthIsEqualTo(32.dp)
            val compactWidth = compact.fetchSemanticsNode().boundsInRoot.width
            assertTrue("expected compact < icon < split, was $compactWidth, $iconWidth, $specWidth",
                compactWidth < iconWidth && iconWidth < specWidth)
        } finally {
            MeloraSettings.qualityBadgeMode.value = original
        }
    }

    @Test fun playerAudioInfoCardShowsAFixedOutlinedSpecification() {
        val specification = mutableStateOf(AudioSpecification("audio/flac", 192_000, 4_812_000, bitDepth = 24))
        val dark = mutableStateOf(false)
        val original = MeloraSettings.qualityBadgeMode.value
        val badgeDescription = "HI-RES · 192 kHz"
        val fullSpecification = "24-bit · 192 kHz · FLAC · 4,812 kbps"
        try {
            compose.setContent {
                val background = if (dark.value) Color(0xFF18222C) else Color(0xFFF0E9DE)
                Box(Modifier.background(background).testTag("audio-info-card")) {
                    PlayerAppearanceProvider(dark = dark.value) {
                        AudioInfoCard("本地音频", specification.value, "播放源：本地媒体")
                    }
                }
            }

            val card = compose.onNodeWithTag("audio-info-card")
            for (night in listOf(false, true)) {
                compose.runOnIdle { dark.value = night }
                val badgeWidths = QualityBadgeMode.entries.map { mode ->
                    compose.runOnIdle { MeloraSettings.qualityBadgeMode.value = mode }
                    val badge = compose.onNodeWithContentDescription(badgeDescription).assertIsDisplayed()
                        .assertHeightIsEqualTo(18.dp)
                    compose.onNodeWithText(fullSpecification).assertIsDisplayed()

                    val bounds = badge.fetchSemanticsNode().boundsInRoot
                    val cardTop = card.fetchSemanticsNode().boundsInRoot.top
                    val cardPixels = card.captureToImage().toPixelMap()
                    // 用同一行远离内容的卡片留白采到实际底色，计入播放器卡片自己的半透明层。
                    val surface = cardPixels[cardPixels.width / 50, (bounds.center.y - cardTop).roundToInt()]
                    val pixels = badge.captureToImage().toPixelMap()
                    val inset = maxOf(1, pixels.height / 8)
                    val straightEdge = pixels.width * 3 / 8 until pixels.width * 5 / 8
                    assertTrue("$mode, dark=$night: the player badge should have a visible outline", straightEdge.all { x ->
                        (0 until inset).any { y -> !pixels[x, y].resembles(surface, tolerance = 0.07f) } &&
                            (0 until inset).any { y ->
                                !pixels[x, pixels.height - 1 - y].resembles(surface, tolerance = 0.07f)
                            }
                    })
                    assertTrue("$mode, dark=$night: the card surface should show inside the outline", straightEdge.all { x ->
                        pixels[x, inset].resembles(surface, tolerance = 0.022f) &&
                            pixels[x, pixels.height - 1 - inset].resembles(surface, tolerance = 0.022f)
                    })
                    assertTrue("$mode, dark=$night: the measured short specification should be visible",
                        (pixels.height / 4 until pixels.height * 3 / 4).any { y ->
                            (pixels.width * 2 / 3 until pixels.width * 7 / 8).any { x ->
                                !pixels[x, y].resembles(surface, tolerance = 0.10f)
                            }
                        })
                    bounds.width
                }
                assertEquals("the global badge mode should leave the player badge size stable", 1, badgeWidths.distinct().size)
            }

            compose.runOnIdle { specification.value = AudioSpecification("audio/flac", 96_000, -1) }
            compose.onNodeWithText("96 kHz · FLAC").assertIsDisplayed()
            compose.onNodeWithText(fullSpecification).assertDoesNotExist()
            compose.onNodeWithContentDescription(badgeDescription).assertDoesNotExist()
            compose.onNodeWithContentDescription("LOSSLESS", substring = true).assertDoesNotExist()
        } finally {
            MeloraSettings.qualityBadgeMode.value = original
        }
    }

    @Test fun compactBadgePaintsOnlyCenteredShortTextAndRetainsFullDescription() {
        val badge = AudioQualityBadge(AudioQualityTier.Opus, "OPUS 96K", "96K", "OPUS", AudioQualityTier.Economy)
        compose.setContent {
            CompositionLocalProvider(LocalBadgeThemeDark provides false) {
                BadgePill(badge, mode = QualityBadgeMode.Compact)
            }
        }
        val node = compose.onNodeWithContentDescription("OPUS 96K").assertIsDisplayed()
            .assertHeightIsEqualTo(17.dp).assertWidthIsEqualTo(32.dp)
        val pixels = node.captureToImage().toPixelMap()
        val ink = audioBadgeAppearance(AudioQualityTier.Economy).light
        val inkColumns = (pixels.width / 8 until pixels.width * 7 / 8).filter { x ->
            (pixels.height / 4 until pixels.height * 3 / 4).any { y -> pixels[x, y].resembles(ink) }
        }
        assertTrue("expected visible short text", inkColumns.isNotEmpty())
        assertTrue("expected only centered short text, edge columns were ${inkColumns.first()} and ${inkColumns.last()}",
            inkColumns.first() >= pixels.width / 4 && inkColumns.last() < pixels.width * 3 / 4)
    }

    @Test fun splitModeShowsOnlyKnownSpecifications() {
        compose.setContent {
            BadgePill(AudioQualityBadge(AudioQualityTier.HiRes, "HI-RES"), mode = QualityBadgeMode.Split)
        }
        compose.onNodeWithContentDescription("HI-RES").assertIsDisplayed()
    }

    @Test fun sameWidthBitratesAndAThemeChangeKeepBadgeGeometry() {
        val badge = mutableStateOf(AudioQualityBadge(AudioQualityTier.Standard, "SD 165K"))
        val dark = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalBadgeThemeDark provides dark.value) {
                Row { BadgePill(badge.value, mode = QualityBadgeMode.IconText) }
            }
        }
        val before = compose.onNodeWithContentDescription("SD 165K").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            badge.value = AudioQualityBadge(AudioQualityTier.Standard, "SD 192K")
            dark.value = true
        }
        val node = compose.onNodeWithContentDescription("SD 192K").assertIsDisplayed()
        assertEquals(before, node.fetchSemanticsNode().boundsInRoot)
        assertVisibleGlyph("SD 192K", audioBadgeAppearance(AudioQualityTier.Standard).dark)
    }

    @Test fun largeSystemFontKeepsBadgesLegibleWithoutIncreasingRowHeight() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalBadgeThemeDark provides true,
            ) {
                Row {
                    BadgePill(AudioQualityBadge(AudioQualityTier.Economy, "ECO 96K"), mode = QualityBadgeMode.IconText)
                    BadgePill(AudioQualityBadge(AudioQualityTier.HiRes, "HI-RES"), mode = QualityBadgeMode.IconText)
                }
            }
        }
        compose.onNodeWithContentDescription("ECO 96K").assertIsDisplayed().assertHeightIsEqualTo(18.dp)
        compose.onNodeWithContentDescription("HI-RES").assertIsDisplayed().assertHeightIsEqualTo(18.dp)
        assertVisibleGlyph("ECO 96K", audioBadgeAppearance(AudioQualityTier.Economy).dark)
    }

    private fun assertVisibleGlyph(label: String, ink: Color) {
        val pixels = compose.onNodeWithContentDescription(label).captureToImage().toPixelMap()
        // 在组件中部检查实际字形。
        assertTrue("missing badge glyph: $label", (pixels.height / 4 until pixels.height * 3 / 4).any { y ->
            (pixels.width / 3 until pixels.width * 3 / 4).any { x ->
                pixels[x, y].resembles(ink)
            }
        })
    }

    private fun Color.resembles(color: Color, tolerance: Float = 0.13f): Boolean =
        abs(red - color.red) < tolerance && abs(green - color.green) < tolerance && abs(blue - color.blue) < tolerance
}
