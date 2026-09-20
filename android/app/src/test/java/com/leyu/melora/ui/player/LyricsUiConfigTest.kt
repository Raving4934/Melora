package com.leyu.melora.ui.player

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
}
