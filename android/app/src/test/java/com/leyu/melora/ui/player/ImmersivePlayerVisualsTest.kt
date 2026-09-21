package com.leyu.melora.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersivePlayerVisualsTest {
    @Test
    fun progressFractionRejectsInvalidDurationAndDoesNotInventProgress() {
        assertEquals(0f, immersiveProgressFraction(positionMs = 500L, durationMs = 0L), 0f)
        assertEquals(0f, immersiveProgressFraction(positionMs = 500L, durationMs = -1L), 0f)
    }

    @Test
    fun progressFractionClampsBeforeStartAndAfterEnd() {
        assertEquals(0f, immersiveProgressFraction(positionMs = Long.MIN_VALUE, durationMs = 1_000L), 0f)
        assertEquals(0f, immersiveProgressFraction(positionMs = -1L, durationMs = 1_000L), 0f)
        assertEquals(0f, immersiveProgressFraction(positionMs = 0L, durationMs = 1_000L), 0f)
        assertEquals(1f, immersiveProgressFraction(positionMs = 1_000L, durationMs = 1_000L), 0f)
        assertEquals(1f, immersiveProgressFraction(positionMs = 1_001L, durationMs = 1_000L), 0f)
    }

    @Test
    fun progressFractionKeepsTheClockwiseSweepRatio() {
        assertEquals(0.25f, immersiveProgressFraction(positionMs = 25L, durationMs = 100L), 0.0001f)
        assertEquals(0.5f, immersiveProgressFraction(positionMs = 50L, durationMs = 100L), 0.0001f)
        assertEquals(0.5f, immersiveProgressFraction(positionMs = Long.MAX_VALUE / 2L, durationMs = Long.MAX_VALUE), 0.0001f)
        assertEquals(1f, immersiveProgressFraction(positionMs = Long.MAX_VALUE, durationMs = Long.MAX_VALUE), 0f)
    }

    @Test
    fun progressFractionAlwaysStaysWithinTheRingDomain() {
        val durations = listOf(1L, 100L, Long.MAX_VALUE)
        val positions = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 50L, 100L, Long.MAX_VALUE)

        for (duration in durations) for (position in positions) {
            assertTrue(immersiveProgressFraction(position, duration) in 0f..1f)
        }
    }
}
