package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSchedulingPolicyTest {
    @Test fun playingDoesNotSleepForHalfAnAudioTrackBuffer() {
        assertEquals(10_000L, audioProgressIntervalUs(250_000L, started = true, ended = false))
    }

    @Test fun preservesImmediateAndEarlierWork() {
        for (suggested in listOf(0L, 1L, 3_000L, 10_000L)) {
            assertEquals(suggested, audioProgressIntervalUs(suggested, started = true, ended = false))
        }
    }

    @Test fun pausedRendererKeepsSdkIdleInterval() {
        assertEquals(1_000_000L, audioProgressIntervalUs(1_000_000L, started = false, ended = false))
    }

    @Test fun endedRendererDoesNotKeepPollingAtPlaybackRate() {
        for (started in listOf(false, true)) {
            assertEquals(1_000_000L, audioProgressIntervalUs(1_000_000L, started = started, ended = true))
        }
    }
}
