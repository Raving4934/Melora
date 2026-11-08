package com.leyu.melora.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 以连续播放观察验证超时；不把用户等待时间或旧输出证据计入当前故障。 */
class AudioOutputWatchdogTest {
    private val watchdog = AudioOutputWatchdog()

    private fun tick(
        time: Long,
        position: Long = time,
        uid: String? = "chapter",
        playing: Boolean = true,
        duration: Long = 600_000L,
        generation: Long = 0L,
        progress: Long = 0L,
    ): Boolean = watchdog.update(
        AudioOutputWatchdog.Observation(uid, position, duration, playing, generation, progress),
        time,
    )

    @Test fun noOutputWhilePlaybackAdvancesRaisesOneFailureAfterGracePeriod() {
        for (time in 0L..4_500L step 500) assertFalse(tick(time))
        assertTrue(tick(5_000))
        for (time in 5_500L..20_000L step 500) assertFalse(tick(time))
    }

    @Test fun outputCanStallAfterSuccessfulPlaybackHasAlreadyStarted() {
        for (time in 0L..3_000L step 500) assertFalse(tick(time, progress = time / 500))
        for (time in 3_500L..7_500L step 500) assertFalse(tick(time, progress = 6))
        assertTrue(tick(8_000, progress = 6))
    }

    @Test fun normalOutputRemainsHealthyWithoutRepeatedStartCallbacks() {
        for (time in 0L..60_000L step 500) {
            assertFalse(tick(time, progress = time / 500))
        }
    }

    @Test fun seamlessTransitionMayKeepTheSameSinkAndProgressCounter() {
        for (time in 0L..8_000L step 500) assertFalse(tick(time, progress = time / 500))
        // 切项时不清零输出序号，也不要求再次收到首次出声事件。
        for (time in 8_500L..20_000L step 500) {
            assertFalse(tick(time, position = time - 8_500, uid = "next", progress = time / 500))
        }
    }

    @Test fun nextChapterDoesNotInheritThePreviousChaptersMissingOutputTime() {
        for (time in 0L..4_000L step 500) assertFalse(tick(time))
        for (time in 4_500L..9_000L step 500) {
            assertFalse(tick(time, position = time - 4_500, uid = "next"))
        }
        assertTrue(tick(9_500, position = 5_000, uid = "next"))
    }

    @Test fun pauseBufferingAndFocusSuppressionDoNotAccumulateTimeout() {
        // 三类等待在策略边界均表现为 playing=false，时间不能跨越等待继续累计。
        for (cycle in 0L..2L) {
            val start = cycle * 100_000
            for (offset in 0L..3_000L step 500) {
                assertFalse(tick(start + offset, position = offset))
            }
            for (offset in 3_500L..60_000L step 500) {
                assertFalse(tick(start + offset, position = 3_000, playing = false))
            }
        }
        for (offset in 0L..4_500L step 500) {
            assertFalse(tick(300_000 + offset, position = 3_000 + offset))
        }
        assertTrue(tick(305_000, position = 8_000))
    }

    @Test fun stationaryMediaClockCannotCountAsSilentPlayback() {
        for (time in 0L..10_000L step 500) assertFalse(tick(time, position = 0))
        for (time in 10_500L..14_500L step 500) {
            assertFalse(tick(time, position = time - 10_000))
        }
        assertTrue(tick(15_000, position = 5_000))
    }

    @Test fun backwardPositionJumpStartsANewObservationWindow() {
        for (time in 0L..4_000L step 500) assertFalse(tick(time, position = 10_000 + time))
        for (time in 4_500L..9_000L step 500) {
            assertFalse(tick(time, position = 2_000 + time - 4_500))
        }
        assertTrue(tick(9_500, position = 7_000))
    }

    @Test fun explicitSeekResetDoesNotCarryTheOldTimeoutIntoTheSeekTarget() {
        for (time in 0L..4_000L step 500) assertFalse(tick(time))
        watchdog.reset()
        for (time in 4_500L..9_000L step 500) {
            assertFalse(tick(time, position = 120_000 + time - 4_500))
        }
        assertTrue(tick(9_500, position = 125_000))
    }

    @Test fun newOutputGenerationGetsAFullGracePeriod() {
        for (time in 0L..4_000L step 500) assertFalse(tick(time))
        for (time in 4_500L..9_000L step 500) assertFalse(tick(time, generation = 1))
        assertTrue(tick(9_500, generation = 1))
    }

    @Test fun resumedOutputClearsAnEarlierFailureAndCanBeObservedAgain() {
        for (time in 0L..4_500L step 500) assertFalse(tick(time))
        assertTrue(tick(5_000))
        for (time in 5_500L..10_000L step 500) assertFalse(tick(time, progress = 1))
        assertTrue(tick(10_500, progress = 1))
    }

    @Test fun replacingTheOutputAfterFailureCanReportANewFailure() {
        for (time in 0L..4_500L step 500) assertFalse(tick(time))
        assertTrue(tick(5_000))
        for (time in 5_500L..10_000L step 500) assertFalse(tick(time, generation = 1))
        assertTrue(tick(10_500, generation = 1))
    }

    @Test fun normalEndOfTrackDoesNotTriggerRecoveryWhileOutputDrains() {
        for (time in 0L..7_000L step 500) assertFalse(tick(time, duration = 5_000))
    }

    @Test fun unknownDurationStillDetectsMissingOutput() {
        for (duration in listOf(0L, -1L)) {
            watchdog.reset()
            for (time in 0L..4_500L step 500) assertFalse(tick(time, duration = duration))
            assertTrue(tick(5_000, duration = duration))
        }
    }

    @Test fun slowPlaybackRequiresEnoughMediaProgressBeforeFailure() {
        for (time in 0L..9_500L step 500) assertFalse(tick(time, position = time / 10))
        assertTrue(tick(10_000, position = 1_000))
    }

    @Test fun missingCurrentItemNeverStartsRecovery() {
        for (time in 0L..30_000L step 500) assertFalse(tick(time, uid = null))
        for (time in 30_500L..35_000L step 500) {
            assertFalse(tick(time, position = time - 30_500))
        }
        assertTrue(tick(35_500, position = 5_000))
    }
}
