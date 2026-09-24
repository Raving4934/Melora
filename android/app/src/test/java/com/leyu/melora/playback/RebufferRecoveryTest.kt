package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    @Test fun pendingAttemptNeverStartsAParallelRecovery() {
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

    @Test fun pausedRecoveryDoesNotPermanentlySpendTheTracksOpportunity() {
        played(); tick(1000)
        assertEquals(FIND_ALTERNATIVE, tick(9000))
        policy.attemptStarted()
        policy.interrupted()
        played(10_000); tick(11_000)
        assertEquals(NONE, tick(20_000)) // 取消也保留冷却，不因反复暂停触发请求风暴。
        assertEquals(FIND_ALTERNATIVE, tick(39_000))
    }

    @Test fun finishedRecoveriesHaveCooldownAndTwoAttemptCap() {
        played(); tick(1000); tick(9000); policy.attemptStarted()
        policy.attemptFinished(completed = true, nowMs = 9_500)
        assertEquals(NONE, tick(39_499))
        assertEquals(FIND_ALTERNATIVE, tick(39_500))
        policy.attemptStarted()
        policy.attemptFinished(completed = true, nowMs = 40_000)
        for (time in 70_000L..120_000L step 1000) assertEquals(NONE, tick(time))
    }

    @Test fun cancelledSearchRetainsBudgetButCannotRetryImmediately() {
        played(); tick(1000); tick(9000); policy.attemptStarted()
        policy.attemptFinished(completed = false, nowMs = 10_000)
        assertEquals(NONE, tick(39_999))
        assertEquals(FIND_ALTERNATIVE, tick(40_000))
        policy.attemptStarted()
        policy.attemptFinished(completed = true, nowMs = 41_000)
        assertEquals(FIND_ALTERNATIVE, tick(71_000))
    }

    @Test fun nextSongGetsNewRecoveryBudgetAfterPreviousWasExhausted() {
        played(); tick(1000); tick(9000); policy.attemptStarted()
        policy.attemptFinished(true, 10_000)
        tick(40_000); policy.attemptStarted(); policy.attemptFinished(true, 41_000)
        tick(42_000, uid = "next", playing = true, buffering = false)
        tick(43_000, uid = "next")
        assertEquals(FIND_ALTERNATIVE, tick(51_000, uid = "next"))
    }

    @Test fun hardFailureRetriesDifferentResourcesAndNeverLoopsOneUrl() {
        val errors = PlaybackErrorRecovery()
        assertTrue(errors.allowRetry("A", 0, true))
        assertFalse(errors.allowRetry("A", 100, true))
        assertTrue(errors.allowRetry("B", 200, true))
        assertFalse(errors.allowRetry("C", 300, true))
    }

    @Test fun hardFailureWindowAndDisabledSwitchStayBounded() {
        val errors = PlaybackErrorRecovery()
        assertTrue(errors.allowRetry("A", 0, true))
        assertFalse(errors.allowRetry("B", 30_001, true))
        errors.reset()
        assertTrue(errors.allowRetry("A", 40_000, false))
        assertFalse(errors.allowRetry("B", 40_001, false))
        errors.reset()
        assertTrue(errors.allowRetry("B", 40_002, true))
    }
    @Test fun slowResourcesStayExcludedForThisTrackOnly() {
        played(); tick(1000); tick(9000)
        policy.attemptStarted("resource-A")
        policy.attemptFinished(true, 10_000)
        assertEquals(setOf("resource-A"), policy.excludedResources)
        tick(40_000); policy.attemptStarted("resource-B")
        assertEquals(setOf("resource-A", "resource-B"), policy.excludedResources)
        tick(41_000, uid = "other", playing = true, buffering = false)
        assertTrue(policy.excludedResources.isEmpty())
    }
}
