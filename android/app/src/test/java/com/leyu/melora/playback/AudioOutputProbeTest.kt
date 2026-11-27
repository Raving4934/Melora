package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputProbeTest {
    private fun AudioOutputProbe.failure() = AudioOutputProbe.Failure(snapshot, "chapter", 0, 0, 0)

    @Test fun resumedOutputInvalidatesAnAlreadyQueuedFailure() {
        val probe = AudioOutputProbe()
        probe.sample(0, 10)
        val failure = probe.failure()
        assertTrue(probe.stillStalled(failure))
        probe.sample(1_000, 10)
        assertFalse(probe.stillStalled(failure))
    }

    @Test fun replacementOrSeekCannotReceiveThePreviousStreamsLateFailure() {
        val probe = AudioOutputProbe()
        val failure = probe.failure()
        probe.reset()
        assertFalse(probe.stillStalled(failure))
    }

    @Test fun pauseOrMonitorReleaseCanCancelTheFailureWithoutTouchingTheSink() {
        val probe = AudioOutputProbe()
        val failure = probe.failure()
        failure.cancelled = true
        assertFalse(probe.stillStalled(failure))
    }

    @Test fun headJitterDoesNotInvalidateFailureButDrainingQueuedFramesDoes() {
        val probe = AudioOutputProbe()
        probe.sample(1_000, 50)
        val failure = probe.failure()
        for (head in listOf(990, 1_000, 1_010, 1_040, 995)) probe.sample(head, 50)
        assertTrue(probe.stillStalled(failure))
        probe.sample(1_055, 50)
        assertEquals(55L, probe.snapshot.playedFrames)
        assertFalse(probe.stillStalled(failure))
    }

    @Test fun writesAndHardwareFramesAreIndependentAndCountersCanWrap() {
        val probe = AudioOutputProbe()
        probe.sample(-10, 1)
        val failure = probe.failure()
        probe.wrote(0)
        probe.sample(-10, 1)
        assertTrue(probe.stillStalled(failure))
        probe.wrote(320)
        probe.sample(-10, 1)
        assertFalse(probe.stillStalled(failure))
        assertEquals(320L, probe.snapshot.writtenBytes)
        assertEquals(0L, probe.snapshot.playedFrames)
        probe.sample(20, 1)
        assertEquals(30L, probe.snapshot.playedFrames)
        probe.reset()
        probe.sample(10_000, 1)
        assertEquals(AudioOutputProbe.Snapshot(generation = 1), probe.snapshot)
    }
}
