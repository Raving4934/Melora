package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import com.leyu.melora.playback.RebufferRecovery.Action.*

class RebufferRecoveryTest {
    private val policy = RebufferRecovery()
    private fun tick(time: Long, uid: String? = "song", playing: Boolean = false,
        intent: Boolean = true, buffering: Boolean = true, online: Boolean = true, switch: Boolean = true) =
        policy.update(uid, playing, intent, buffering, online, switch, time)
    private fun played(time: Long = 0) = tick(time, playing = true, buffering = false)

    @Test fun initialResolutionOrBufferingNeverStartsExtraRequests() {
        for (time in 0L..60_000L step 500) assertEquals(NONE, tick(time))
    }
    @Test fun offlineOrMissingPrerequisitesDoNotSpendAttemptBeforeDispatch() {
        played(); tick(1000)
        assertEquals(FIND_ALTERNATIVE, tick(9000))
        assertEquals(FIND_ALTERNATIVE, tick(10_000))
        policy.attemptStarted()
        assertEquals(NONE, tick(11_000))
    }
    @Test fun briefStallOnlyYieldsBackgroundCacheAndNeverSwitches() {
        played()
        assertEquals(YIELD_PREFETCH, tick(1000))
        assertEquals(NONE, tick(3000))
        assertEquals(NONE, played(3500))
        assertEquals(NONE, played(60_000))
    }
    @Test fun sustainedStallStartsOneBoundedAttemptPerTrack() {
        played()
        assertEquals(YIELD_PREFETCH, tick(1000))
        assertEquals(NONE, tick(8999))
        assertEquals(FIND_ALTERNATIVE, tick(9000))
        policy.attemptStarted()
        for (time in 9500L..50_000L step 500) assertEquals(NONE, tick(time))
        played(51_000)
        assertEquals(YIELD_PREFETCH, tick(52_000))
        assertEquals(NONE, tick(70_000))
    }
    @Test fun frequentStuttersAccumulateOnlyInsideWindow() {
        played()
        tick(1000); played(3000)
        tick(5000); played(7000)
        tick(9000)
        assertEquals(NONE, tick(10_999))
        assertEquals(FIND_ALTERNATIVE, tick(11_000))
    }
    @Test fun oldStuttersDoNotAccumulateForever() {
        played()
        tick(1000); played(3000)
        tick(5000); played(7000)
        tick(40_000)
        assertEquals(NONE, tick(42_000))
    }
    @Test fun pauseAndSeekMustNotBecomeSlowSourceEvidence() {
        played(); tick(1000)
        assertEquals(NONE, tick(9000, intent = false))
        assertEquals(NONE, tick(100_000))
        played(101_000); tick(102_000)
        policy.interrupted()
        assertEquals(NONE, tick(200_000))
    }
    @Test fun localTracksNeverStartNetworkRecovery() {
        played()
        for (time in 1000L..60_000L step 500) assertEquals(NONE, tick(time, online = false))
    }
    @Test fun disabledAutomaticSwitchStillYieldsCompetingPrefetchOnly() {
        played()
        assertEquals(YIELD_PREFETCH, tick(1000, switch = false))
        for (time in 1500L..60_000L step 500) assertEquals(NONE, tick(time, switch = false))
    }
    @Test fun nextSongDoesNotInheritPreviousStalls() {
        played(); tick(1000)
        assertEquals(NONE, tick(10_000, uid = "next"))
        assertEquals(NONE, tick(100_000, uid = "next"))
    }
}
