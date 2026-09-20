package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test
    fun supportedSpeedsAlwaysKeepOriginalPitch() {
        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
            val parameters = PlaybackController.pitchCorrectedPlaybackParameters(speed)
            assertEquals(speed, parameters.speed, 0.0001f)
            assertEquals(1f, parameters.pitch, 0.0001f)
        }
    }

    @Test
    fun speedIsClampedToSafeSonicRange() {
        assertEquals(0.5f, PlaybackController.pitchCorrectedPlaybackParameters(0.1f).speed, 0.0001f)
        assertEquals(2f, PlaybackController.pitchCorrectedPlaybackParameters(3f).speed, 0.0001f)
    }
}
