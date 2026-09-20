package com.leyu.melora.ui.player

import com.leyu.melora.playback.PlayerCoverStyle
import org.junit.Assert.assertFalse
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
    fun incomingImageNeverExposesTheColorBackdropDuringHandoff() {
        for (step in 0..100) {
            val alpha = step / 100f
            val baseAlpha = if (artworkNeedsOpaqueBase(true, alpha)) 1f else 0f
            val coverage = alpha + baseAlpha * (1f - alpha)
            assertEquals("frame $step must stay opaque", 1f, coverage, 0.0001f)
        }
        // 旧双向Crossfade中点的合成覆盖率只有75%，并不是不透明的封面。
        assertEquals(0.75f, 0.5f + 0.5f * (1f - 0.5f), 0f)
    }

    @Test
    fun loadingAndFailedTargetsKeepAnOpaqueBaseAndFinishedImagesDropIt() {
        assertTrue(artworkNeedsOpaqueBase(false, 0f))
        assertTrue(artworkNeedsOpaqueBase(false, 1f))
        assertTrue(artworkNeedsOpaqueBase(true, 0.999f))
        assertFalse(artworkNeedsOpaqueBase(true, 1f))
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

}
