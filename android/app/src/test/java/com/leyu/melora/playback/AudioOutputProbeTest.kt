package com.leyu.melora.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputProbeTest {
    @Test fun resumedOutputInvalidatesAnAlreadyQueuedFailure() {
        val probe = AudioOutputProbe()
        probe.position(0)
        val failure = AudioOutputProbe.Failure(probe.snapshot)
        assertTrue(probe.stillStalled(failure))
        probe.position(1_000)
        assertFalse(probe.stillStalled(failure))
    }

    @Test fun replacementOrSeekCannotReceiveThePreviousStreamsLateFailure() {
        val probe = AudioOutputProbe()
        val failure = AudioOutputProbe.Failure(probe.snapshot)
        probe.reset()
        assertFalse(probe.stillStalled(failure))
    }

    @Test fun pauseOrMonitorReleaseCanCancelTheFailureWithoutTouchingTheSink() {
        val probe = AudioOutputProbe()
        val failure = AudioOutputProbe.Failure(probe.snapshot)
        failure.cancelled = true
        assertFalse(probe.stillStalled(failure))
    }
}
