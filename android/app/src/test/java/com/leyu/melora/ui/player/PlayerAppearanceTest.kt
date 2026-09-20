package com.leyu.melora.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAppearanceTest {
    @Test
    fun collectionDetailsUseAppThemeAndReturningRestoresPlayerTheme() {
        for (appDark in listOf(false, true)) for (playerDark in listOf(false, true)) {
            assertEquals(!appDark, playerPageIsLight(true, playerDark, appDark))
            assertEquals(!playerDark, playerPageIsLight(false, playerDark, appDark))
        }
    }

    @Test
    fun paletteRetainsTheDisplayedToneUntilTheNextResultCompletes() {
        val request = Any()
        val previousTone = Color(0xFF176BA3)
        val nextTone = Color(0xFF8A5B37)
        for (dark in listOf(false, true)) {
            val loadingTone = playerBackdropAfterLoad(previousTone, request, request, null)
            assertEquals(playerColorsFor(dark, previousTone), playerColorsFor(dark, loadingTone))
            val readyTone = playerBackdropAfterLoad(previousTone, request, request, Result.success(nextTone))
            assertEquals(playerColorsFor(dark, nextTone), playerColorsFor(dark, readyTone))
            val failedTone = playerBackdropAfterLoad(previousTone, request, request, Result.success(null))
            assertEquals(playerColorsFor(dark), playerColorsFor(dark, failedTone))
        }
        assertTrue(PlayerBackdropTransitionMillis in 220..260)
    }

    @Test
    fun neutralLightAndDarkKeepSeparateSoftBackdropPlans() {
        val dark = playerColorsFor(dark = true)
        val light = playerColorsFor(dark = false)

        assertNotEquals(dark.backdrop.baseColor, light.backdrop.baseColor)
        assertNotEquals(dark.backdrop.scrimStops, light.backdrop.scrimStops)
        assertTrue(dark.backdrop.artworkAlpha > light.backdrop.artworkAlpha)
        assertTrue(light.background.luminance() > 0.70f)
        assertTrue(light.textPrimary.luminance() < 0.5f)
        assertEquals(4, dark.backdrop.scrimStops.size)
        assertEquals(4, light.backdrop.scrimStops.size)
    }

    @Test
    fun lightArtworkToneDrivesDarkInkAndLowAlphaColoredCards() {
        val colors = playerColorsFor(dark = false, artworkColor = Color(0xFF2B6A9A))

        assertTrue(colors.textPrimary.blue > colors.textPrimary.red)
        assertTrue(colors.progressActive.blue > colors.progressActive.red)
        assertTrue(colors.cardSurface.alpha in 0.05f..0.20f)
        assertTrue(colors.cardSelected.alpha in 0.05f..0.25f)
        assertTrue(colors.cardSurface != Color.White.copy(alpha = 0.56f))
        assertEquals(colors.cardSurface.red, colors.queueSelected.red, 0f)
        assertEquals(colors.cardSurface.green, colors.queueSelected.green, 0f)
        assertEquals(colors.cardSurface.blue, colors.queueSelected.blue, 0f)
        assertNotEquals(colors.cardSurface.alpha, colors.queueSelected.alpha)
    }

    @Test
    fun darkArtworkToneDrivesLightInkWhileKeepingTheBackdropDark() {
        val colors = playerColorsFor(dark = true, artworkColor = Color(0xFF8A5B37))

        assertTrue(colors.background.luminance() < 0.35f)
        assertTrue(colors.textPrimary.luminance() > 0.50f)
        assertTrue(colors.textPrimary.red > colors.textPrimary.blue)
        assertTrue(colors.progressActive.red > colors.progressActive.blue)
        assertTrue(colors.cardSurface.alpha in 0.05f..0.25f)
        assertEquals(colors.cardSurface.red, colors.queueSelected.red, 0f)
        assertEquals(colors.cardSurface.green, colors.queueSelected.green, 0f)
        assertEquals(colors.cardSurface.blue, colors.queueSelected.blue, 0f)
    }

    @Test
    fun lowSaturationAndTransparentArtworkFallBackToNeutralPalette() {
        val neutral = playerColorsFor(dark = false)
        val lowSaturation = Color(0xFF808080)
        val transparent = Color(0x001E88E5)

        assertEquals(null, normalizePlayerArtworkColor(null))
        assertEquals(null, normalizePlayerArtworkColor(lowSaturation))
        assertEquals(null, normalizePlayerArtworkColor(transparent))
        assertEquals(neutral.textPrimary, playerColorsFor(false, lowSaturation).textPrimary)
        assertEquals(neutral.cardSurface, playerColorsFor(false, transparent).cardSurface)
    }

    @Test
    fun sheetTokensKeepTheirOriginalThemeValuesWhenArtworkChanges() {
        val dark = playerColorsFor(dark = true)
        val darkArtwork = playerColorsFor(dark = true, artworkColor = Color(0xFF8A5B37))
        val light = playerColorsFor(dark = false)
        val lightArtwork = playerColorsFor(dark = false, artworkColor = Color(0xFF2B6A9A))

        assertEquals(dark.sheetBackground, darkArtwork.sheetBackground)
        assertEquals(dark.sheetText, darkArtwork.sheetText)
        assertEquals(dark.sheetTextMuted, darkArtwork.sheetTextMuted)
        assertEquals(dark.segmentTrack, darkArtwork.segmentTrack)
        assertEquals(dark.accent, darkArtwork.accent)
        assertEquals(light.sheetBackground, lightArtwork.sheetBackground)
        assertEquals(light.sheetText, lightArtwork.sheetText)
        assertEquals(light.sheetTextMuted, lightArtwork.sheetTextMuted)
        assertEquals(light.segmentTrack, lightArtwork.segmentTrack)
        assertEquals(light.accent, lightArtwork.accent)
    }

    @Test
    fun queueSelectionKeepsTheAcceptedDarkHighlightSeparateFromCardSelection() {
        val dark = playerColorsFor(dark = true)

        assertEquals(Color.White.copy(alpha = 0.12f), dark.queueSelected)
        assertNotEquals(dark.cardSelected, dark.queueSelected)
    }
}
