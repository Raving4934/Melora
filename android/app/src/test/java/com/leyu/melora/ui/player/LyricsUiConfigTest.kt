package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricsUiConfig

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsUiConfigTest {
    @Test
    fun defaultKeepsTheExistingSizeAlignmentAndBoldAppearance() {
        val config = LyricsUiConfig()
        assertEquals(22f, config.fontSizeSp, 0f)
        assertFalse(config.isCentered)
        assertFalse(config.isBold)
        assertFalse(config.isBlurEnabled)
    }

    @Test
    fun fontButtonsMoveTwoSpAtATimeAndStopAtTheOriginalBounds() {
        assertEquals(20f, LyricsUiConfig().resizeBy(-1).fontSizeSp, 0f)
        assertEquals(24f, LyricsUiConfig().resizeBy(1).fontSizeSp, 0f)
        var config = LyricsUiConfig()
        repeat(30) { config = config.resizeBy(-1) }
        assertEquals(16f, config.fontSizeSp, 0f)
        repeat(30) { config = config.resizeBy(1) }
        assertEquals(30f, config.fontSizeSp, 0f)
    }

    @Test
    fun resizingDoesNotChangeAlignmentBoldOrBlur() {
        val config = LyricsUiConfig(isCentered = true, isBold = false, isBlurEnabled = true)
        assertEquals(config.copy(fontSizeSp = 24f), config.resizeBy(1))
        assertEquals(config, config.resizeBy(1).resizeBy(-1))
    }

    @Test
    fun switchesRemainIndependentAndCanBeToggledBack() {
        val config = LyricsUiConfig()
        val changed = config.copy(isCentered = true, isBold = true, isBlurEnabled = true)
        assertEquals(config.fontSizeSp, changed.fontSizeSp, 0f)
        assertEquals(config, changed.copy(isCentered = false, isBold = false, isBlurEnabled = false))
    }
    @Test
    fun allOptionsSurviveSerializedStorageRoundTrip() {
        for (size in listOf(16f, 22f, 30f)) for (flags in 0..7) {
            val config = LyricsUiConfig(size, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
            assertEquals(config, LyricsUiConfig.fromJson(JSONObject(config.toJson().toString())))
        }
    }

    @Test
    fun missingAndInvalidStoredSizesPreserveDefaultsAndBounds() {
        assertEquals(LyricsUiConfig(), LyricsUiConfig.fromJson(JSONObject()))
        for ((stored, expected) in listOf("NaN" to 22f, "Infinity" to 22f, "wrong" to 22f, "8" to 16f, "99" to 30f)) {
            val config = LyricsUiConfig.fromJson(JSONObject().put("fontSizeSp", stored).put("isBold", true))
            assertEquals(expected, config.fontSizeSp, 0f)
            assertTrue(config.isBold)
        }
    }

    @Test
    fun resetOnlyChangesFontSizeAndTheResetValueCanBeRestored() {
        val config = LyricsUiConfig(30f, true, true, true).resetFontSize()
        assertEquals(LyricsUiConfig(22f, true, true, true), config)
        assertEquals(config, LyricsUiConfig.fromJson(JSONObject(config.toJson().toString())))
    }

}
