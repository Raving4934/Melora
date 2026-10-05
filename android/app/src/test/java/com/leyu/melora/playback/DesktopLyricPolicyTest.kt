package com.leyu.melora.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricPolicyTest {
    @Test
    fun singleLineOverridesConfiguredLineCountAndTranslation() {
        assertEquals(1, desktopLyricMaxLines(singleLine = true, configured = 4f))
        assertEquals(2..2, desktopLyricRowIndices(10, 2, 1))
        assertEquals("原词", desktopLyricRowText(LyricLine(0, "原词", "译文"), 1))
    }

    @Test
    fun translationStaysWithItsSentenceAndNeverBecomesTheFocusedRow() {
        assertEquals("原词\n译文", desktopLyricRowText(LyricLine(0, "原词", "译文"), 3))
        assertEquals("原词", desktopLyricRowText(LyricLine(0, "原词", "  "), 3))
        assertEquals("译文", desktopLyricRowText(LyricLine(0, "", "译文"), 3))
    }

    @Test
    fun defaultPlacementKeepsLogicalAlignmentAndHistoricalTopCoordinates() {
        fun geometry(align: Int, rtl: Boolean = false) = DesktopLyricGeometry(360, 800, 264, 80, align, rtl, 40f, 48)
        assertEquals(0 to 360, geometry(0).offset(DesktopLyricPosition(), 1))
        assertEquals(48 to 360, geometry(1).offset(DesktopLyricPosition(), 1))
        assertEquals(96 to 360, geometry(2).offset(DesktopLyricPosition(), 1))
        assertEquals(geometry(0).offset(DesktopLyricPosition(), 1), geometry(2, true).offset(DesktopLyricPosition(), 1))
        assertEquals(0 to 96, geometry(0).offset(DesktopLyricPosition(), 0))
        assertEquals(0 to 608, geometry(0).offset(DesktopLyricPosition(), 2))
        assertEquals(0 to 300, geometry(0).offset(DesktopLyricPosition(0f, 0.375f), 1))
    }

    @Test
    fun dragCanMoveBlankWindowOutsideScreenWhileKeepingTheLyricHandleReachable() {
        val geometry = DesktopLyricGeometry(400, 800, 340, 120, 1, false, 60f, 48)
        assertEquals(-146 to -36, geometry.clamp(-999, -999))
        assertEquals(206 to 716, geometry.clamp(999, 999))
        val saved = geometry.position(-146, -36)
        assertEquals(0.06f, saved.xFraction!!, 0.00001f)
        assertEquals(-0.045f, saved.yFraction!!, 0.00001f)
        assertEquals(-146 to -36, geometry.offset(saved, 1))
        assertEquals(saved, DesktopLyricPosition.fromJson(saved.toJson()))
    }

    @Test
    fun landscapeAndFullWidthLyricsRemainDraggable() {
        val landscape = DesktopLyricGeometry(800, 400, 560, 180, 1, false, 80f, 48)
        assertEquals(-256 to -56, landscape.clamp(-999, -999))
        assertEquals(496 to 296, landscape.clamp(999, 999))
        val fullWidth = landscape.copy(screenWidth = 400, width = 400)
        assertEquals(-176, fullWidth.clamp(-999, 0).first)
        assertEquals(176, fullWidth.clamp(999, 0).first)
        val position = fullWidth.position(-100, 200)
        assertEquals(-100 to 200, fullWidth.offset(position, 1))
    }

    @Test
    fun geometryChangesDoNotOverwriteSavedPreferences() {
        val saved = DesktopLyricPosition(0.25f, -0.02f)
        val portrait = DesktopLyricGeometry(400, 800, 300, 160, 1, false, 60f, 48)
        assertEquals(-50 to -16, portrait.offset(saved, 1))
        val landscape = portrait.copy(screenWidth = 800, screenHeight = 400, width = 500)
        assertEquals(-50 to -8, landscape.offset(saved, 1))
        assertEquals(-50 to -16, portrait.offset(saved, 1))
    }

    @Test
    fun controlPanelClampsIndependentlyWithoutMovingLyricsAtAnyEdgeOrAlignment() {
        for (alignment in 0..2) for (rtl in listOf(false, true)) {
            val geometry = DesktopLyricGeometry(400, 800, 120, 90, alignment, rtl, 45f, 48)
            for ((rawX, rawY) in listOf(-999 to -999, 999 to 999, 120 to 300)) {
                val original = geometry.clamp(rawX, rawY)
                val saved = geometry.position(original.first, original.second)
                val panel = geometry.panelOffset(original.first, original.second, 300, 60, 8)
                assertTrue(panel.first in 0..100)
                assertTrue(panel.second in 0..740)
                assertEquals(original, geometry.offset(saved, 1))
                assertEquals(original, geometry.clamp(original.first, original.second))
            }
        }
        val centered = DesktopLyricGeometry(400, 800, 120, 90, 1, false, 45f, 48)
        assertEquals(50 to 642, centered.panelOffset(140, 710, 300, 60, 8))
        assertEquals(50 to 198, centered.panelOffset(140, 100, 300, 60, 8))
    }

    @Test
    fun framesRunOnlyForRealTimingWhileVisibleAndActuallyAdvancing() {
        val playing = PlayerUiState(positionAdvancing = true)
        assertTrue(desktopLyricNeedsFrames(playing, true, true, true))
        assertFalse(desktopLyricNeedsFrames(playing, false, true, true))
        assertFalse(desktopLyricNeedsFrames(playing, true, false, true))
        assertFalse(desktopLyricNeedsFrames(playing, true, true, false))
        assertFalse(desktopLyricNeedsFrames(playing.copy(positionAdvancing = false), true, true, true))
        assertFalse(desktopLyricNeedsFrames(playing.copy(buffering = true), true, true, true))
        assertFalse(desktopLyricNeedsFrames(playing.copy(resolving = true), true, true, true))
        assertFalse(desktopLyricNeedsFrames(playing.copy(speed = 0f), true, true, true))
    }

    @Test
    fun multilineKeepsBothPastAndUpcomingSentencesWithOffscreenBuffers() {
        assertEquals(2..8, desktopLyricRowIndices(20, 5, 4))
        assertEquals(3..9, desktopLyricRowIndices(20, 6, 4))
        assertEquals(0..3, desktopLyricRowIndices(20, 0, 4))
        assertEquals(16..19, desktopLyricRowIndices(20, 19, 4))
    }

    @Test
    fun ordinaryLyricsWakeAtSentenceBoundariesInsteadOfPollingEveryFrame() {
        val lines = listOf(LyricLine(0, "first"), LyricLine(120, "short"), LyricLine(240, "next"))
        val state = PlayerUiState(positionMs = 0, positionAdvancing = true, positionSampleRealtimeMs = 1000)
        assertEquals(120L, desktopLyricNextLineDelay(lines, state, 1000))
        assertEquals(1L, desktopLyricNextLineDelay(lines, state, 1119))
        assertEquals(120L, desktopLyricNextLineDelay(lines, state, 1120))
        assertEquals(60L, desktopLyricNextLineDelay(lines, state.copy(speed = 2f), 1000))
        assertNull(desktopLyricNextLineDelay(lines, state, 1240))
        assertNull(desktopLyricNextLineDelay(lines, state.copy(positionAdvancing = false), 1000))
        assertNull(desktopLyricNextLineDelay(lines, state.copy(buffering = true), 1000))
        assertNull(desktopLyricNextLineDelay(lines, state.copy(resolving = true), 1000))
        assertNull(desktopLyricNextLineDelay(lines, state.copy(durationMs = 100), 1000))
        assertNull(desktopLyricNextLineDelay(emptyList(), state, 1000))
    }

    @Test
    fun timedLyricsWakeAtTheNearestVisibleWordOrSentenceBoundary() {
        val lines = listOf(LyricLine(0, "first"), LyricLine(120, "short"), LyricLine(240, "next"))
        val state = PlayerUiState(positionMs = 0, positionAdvancing = true, positionSampleRealtimeMs = 1000)

        assertEquals(40L, desktopLyricNextLineDelay(lines, state, 1000, nextWordBoundaryMs = 40))
        assertEquals("句界仍早于后续词界", 120L,
            desktopLyricNextLineDelay(lines, state, 1000, nextWordBoundaryMs = 180))
        assertEquals("词间隙按下一个词的起点唤醒", 300L,
            desktopLyricNextLineDelay(listOf(lines.first()), state, 1000, nextWordBoundaryMs = 300))
        assertEquals("快放按倍速换算", 150L,
            desktopLyricNextLineDelay(listOf(lines.first()), state.copy(speed = 2f), 1000, nextWordBoundaryMs = 300))
    }

    @Test
    fun timedWakeUsesPositionFloorAndDiscardsBoundariesPassedBySeekOrBeyondDuration() {
        val lines = listOf(LyricLine(0, "first"), LyricLine(8_000, "next"))
        val floored = PlayerUiState(
            positionMs = 500,
            positionSampleRealtimeMs = 1000,
            positionAdvancing = true,
            durationMs = 10_000,
            speed = 2f,
            lyricPositionFloorMs = 750,
        )
        assertEquals(250L,
            desktopLyricNextLineDelay(lines, floored, 1000, nextWordBoundaryMs = 1000))
        assertEquals("floor领先期间显示位置不提前越界", 750L, lyricPositionAt(floored, 1125))
        assertEquals("采样投影追上后才到达目标边界", 1000L, lyricPositionAt(floored, 1250))
        assertEquals("seek 后忽略已经经过的旧词界", 2_000L,
            desktopLyricNextLineDelay(lines, floored.copy(positionMs = 4_000, lyricPositionFloorMs = 4_000),
                1000, nextWordBoundaryMs = 1000))
        assertNull("尾奏没有超出歌曲时长的唤醒", desktopLyricNextLineDelay(
            lines,
            floored.copy(positionMs = 9_500, lyricPositionFloorMs = 9_500, durationMs = 10_000, speed = 1f),
            1000,
            nextWordBoundaryMs = 10_500,
        ))
        assertNull("暂停时取消所有定时唤醒", desktopLyricNextLineDelay(
            lines, floored.copy(positionAdvancing = false), 1000, nextWordBoundaryMs = 1000,
        ))
        assertNull("缓冲时取消所有定时唤醒", desktopLyricNextLineDelay(
            lines, floored.copy(buffering = true), 1000, nextWordBoundaryMs = 1000,
        ))
    }

    @Test
    fun focusPlacementLeavesReadableContextAndKeepsLongSentencesInsideTheViewport() {
        assertEquals(26f, desktopLyricFocusCenter(80, 12, 12, 2, 24), 0.001f)
        assertEquals(54f, desktopLyricFocusCenter(108, 12, 12, 3, 24), 0.001f)
        assertEquals(40f, desktopLyricFocusCenter(80, 12, 12, 2, 56), 0.001f)
        assertEquals(26f, desktopLyricFocusCenter(52, 12, 12, 1, 24), 0.001f)
    }

    @Test
    fun rowPoolIsBoundedEvenForVeryLongLyrics() {
        assertEquals(11, desktopLyricRowIndices(100_000, 50_000, 8).count())
        assertTrue(desktopLyricRowIndices(0, -1, 4).isEmpty())
        assertTrue(desktopLyricRowIndices(10, -1, 4).isEmpty())
        assertTrue(desktopLyricRowIndices(10, 10, 4).isEmpty())
        assertEquals(0..0, desktopLyricRowIndices(1, 0, 8))
    }
}
