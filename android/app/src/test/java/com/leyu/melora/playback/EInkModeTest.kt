package com.leyu.melora.playback

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.leyu.melora.ui.player.EInkPlayerColors
import com.leyu.melora.ui.player.eInkStageHeroFontSizeSp
import com.leyu.melora.ui.player.playerColorsFor
import com.leyu.melora.ui.player.playerMotionEnabled
import com.leyu.melora.ui.theme.MeloraAppearance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EInkModeTest {
    @Test
    fun eInkSettingRoundTripsThroughBackupWithoutClobberingLegacyBackups() {
        val originalEInk = MeloraSettings.eInkMode.value
        val originalTheme = MeloraSettings.themeMode.value
        val originalBlur = MeloraSettings.componentBlurMode.value
        try {
            MeloraSettings.eInkMode.value = false
            MeloraSettings.themeMode.value = ThemeMode.Dark
            MeloraSettings.componentBlurMode.value = ComponentBlurMode.All

            val legacyPrepared = BackupSettings.prepare(JSONObject(), emptySet(), emptySet()).first
            BackupSettings.apply(legacyPrepared)
            assertFalse(MeloraSettings.eInkMode.value)
            assertEquals(ThemeMode.Dark, MeloraSettings.themeMode.value)
            assertEquals(ComponentBlurMode.All, MeloraSettings.componentBlurMode.value)

            MeloraSettings.eInkMode.value = true
            val exported = BackupSettings.collect()
            assertTrue(exported.getBoolean("eInkMode"))

            MeloraSettings.eInkMode.value = false
            BackupSettings.apply(BackupSettings.prepare(exported, emptySet(), emptySet()).first)
            assertTrue(MeloraSettings.eInkMode.value)
            assertEquals(ThemeMode.Dark, MeloraSettings.themeMode.value)
            assertEquals(ComponentBlurMode.All, MeloraSettings.componentBlurMode.value)
        } finally {
            MeloraSettings.eInkMode.value = originalEInk
            MeloraSettings.themeMode.value = originalTheme
            MeloraSettings.componentBlurMode.value = originalBlur
        }
    }

    @Test
    fun eInkPaletteOverridesAppTokensAndRestoresCleanlyWhenTurnedOff() {
        val originalEInk = MeloraAppearance.isEInk
        val originalDark = MeloraAppearance.isDark
        try {
            MeloraAppearance.isDark = true
            MeloraAppearance.isEInk = true

            val inkPalette = MeloraAppearance.palette(dark = true)
            assertTrue(inkPalette.isEInk)
            assertEquals(Color.White, inkPalette.canvas)
            assertEquals(Color.White, inkPalette.card)
            assertEquals(Color.Black, inkPalette.textMain)
            assertEquals(Color.Black, inkPalette.brand)
            assertEquals(1.dp, inkPalette.cardBorder.width)

            MeloraAppearance.isEInk = false
            val restored = MeloraAppearance.palette(dark = true)
            assertFalse(restored.isEInk)
            assertTrue(restored.isDark)
        } finally {
            MeloraAppearance.isEInk = originalEInk
            MeloraAppearance.isDark = originalDark
        }
    }

    @Test
    fun playerColorsAndMotionRespectEInkMode() {
        val eInkFromDark = playerColorsFor(dark = true, artworkColor = Color.Red, eInk = true)
        assertEquals(EInkPlayerColors, eInkFromDark)
        assertFalse(eInkFromDark.isDark)
        assertEquals(Color.White, eInkFromDark.background)
        assertEquals(Color.Black, eInkFromDark.textPrimary)
        assertEquals(0f, eInkFromDark.backdrop.artworkAlpha)

        assertFalse(
            playerMotionEnabled(
                isVisible = true,
                lifecycleResumed = true,
                powerSaveMode = false,
                animationsEnabled = true,
                eInkMode = true,
            ),
        )
        assertTrue(
            playerMotionEnabled(
                isVisible = true,
                lifecycleResumed = true,
                powerSaveMode = false,
                animationsEnabled = true,
                eInkMode = false,
            ),
        )
    }

    @Test
    fun eInkStageHeroFontSizeSpCoversCompactLandscapeAndLargeDisplays() {
        assertEquals(36f, eInkStageHeroFontSizeSp(textLength = 8, widthDp = 720f, heightDp = 360f), 0f)
        assertEquals(30f, eInkStageHeroFontSizeSp(textLength = 14, widthDp = 720f, heightDp = 360f), 0f)
        assertEquals(25f, eInkStageHeroFontSizeSp(textLength = 24, widthDp = 720f, heightDp = 360f), 0f)

        assertEquals(48f, eInkStageHeroFontSizeSp(textLength = 8, widthDp = 1080f, heightDp = 720f), 0f)
        assertEquals(40f, eInkStageHeroFontSizeSp(textLength = 14, widthDp = 1080f, heightDp = 720f), 0f)
        assertEquals(34f, eInkStageHeroFontSizeSp(textLength = 24, widthDp = 1080f, heightDp = 720f), 0f)
    }
}
