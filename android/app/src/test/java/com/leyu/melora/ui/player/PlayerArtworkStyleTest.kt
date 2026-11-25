package com.leyu.melora.ui.player

import com.leyu.melora.playback.PlayerCoverStyle
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtworkStyleTest {
    @Test
    fun defaultKeepsRoundedArtworkWhileCircleAndVinylShareOneOuterShape() {
        assertEquals(NowPlayingArtworkShape.Rounded, nowPlayingArtworkShape(PlayerCoverStyle.Default))
        assertEquals(NowPlayingArtworkShape.Circle, nowPlayingArtworkShape(PlayerCoverStyle.Circle))
        assertEquals(NowPlayingArtworkShape.Circle, nowPlayingArtworkShape(PlayerCoverStyle.Vinyl))
    }
    @Test
    fun vinylResumesFromCurrentAngleAtTheSameSpeed() {
        assertEquals(24_000, vinylRotationRemainingMillis(0f))
        assertEquals(18_000, vinylRotationRemainingMillis(90f))
        assertEquals(12_000, vinylRotationRemainingMillis(180f))
        assertEquals(6_000, vinylRotationRemainingMillis(270f))
        assertEquals(1, vinylRotationRemainingMillis(360f))
    }

    @Test
    fun vinylRotationDurationAlwaysStaysBoundedAndNonzero() {
        assertEquals(24_000, vinylRotationRemainingMillis(-1f))
        assertEquals(1, vinylRotationRemainingMillis(361f))
        for (angle in 0..360) assertTrue(vinylRotationRemainingMillis(angle.toFloat()) in 1..24_000)
    }

    @Test fun leavingVinylRestoresUprightAlongTheShortArc() {
        assertEquals(-90f, artworkRestingRotation(90f), 0f)
        assertEquals(90f, artworkRestingRotation(270f), 0f)
        assertEquals(10f, artworkRestingRotation(350f), 0f)
        for (angle in 0..360) {
            val correction = artworkRestingRotation(angle.toFloat())
            assertTrue(kotlin.math.abs(correction) <= 180f)
            assertEquals(0f, (angle + correction) % 360f, 0.0001f)
        }
    }

}
