package com.leyu.melora.playback

import org.junit.Assert.*
import org.junit.Test

class LyricTimelineTest {
    @Test fun clockInterpolatesSpeedAndFreezesDuringPauseBufferOrResolve() {
        val anchor = PlayerUiState(positionMs = 1000, positionSampleRealtimeMs = 2000,
            positionAdvancing = true, durationMs = 10_000, speed = 2f)
        assertEquals(1500L, lyricPositionAt(anchor, 2250))
        assertEquals(1000L, lyricPositionAt(anchor.copy(positionAdvancing = false), 2250))
        assertEquals(1000L, lyricPositionAt(anchor.copy(buffering = true), 2250))
        assertEquals(1000L, lyricPositionAt(anchor.copy(resolving = true), 2250))
        assertEquals(10_000L, lyricPositionAt(anchor, 100_000))
        assertEquals(1000L, lyricPositionAt(anchor, 1000))
        assertEquals(0L, lyricPositionAt(anchor.copy(positionMs = 0, positionSampleRealtimeMs = 2250), 2250))
    }

    @Test fun delayedPositionCorrectionHoldsBrieflyThenCatchesUpWithoutRewinding() {
        val before = PlayerUiState(positionMs = 1000, positionSampleRealtimeMs = 1000,
            positionAdvancing = true, durationMs = 10_000)
        val corrected = before.samplePosition(1420, 1500, true)
        assertEquals("真实播放进度不得被显示校正覆盖", 1420L, corrected.positionMs)
        assertEquals(1500L, lyricPositionAt(corrected, 1500))
        assertEquals(1500L, lyricPositionAt(corrected, 1540))
        assertEquals(1520L, lyricPositionAt(corrected, 1600))
        val next = corrected.samplePosition(1920, 2000, true)
        assertEquals("不能永久累积超前量", 1920L, lyricPositionAt(next, 2000))
        assertEquals(2020L, lyricPositionAt(next, 2100))
    }

    @Test fun stalledAudioDoesNotAccumulateAnEverAdvancingLyricFloor() {
        var state = PlayerUiState(positionMs = 1000, positionSampleRealtimeMs = 1000, positionAdvancing = true)
        for (time in 1500L..5000L step 500) {
            state = state.samplePosition(1000, time, true)
            assertEquals(1500L, lyricPositionAt(state, time))
        }
    }

    @Test fun explicitSeekPauseAndBufferingResetThePresentationFloorImmediately() {
        val corrected = PlayerUiState(positionMs = 1000, positionSampleRealtimeMs = 1000, positionAdvancing = true)
            .samplePosition(1420, 1500, true)
        val seek = corrected.samplePosition(500, 1600, true, reset = true)
        assertEquals(500L, lyricPositionAt(seek, 1600))
        assertEquals(600L, lyricPositionAt(seek, 1700))
        val pause = corrected.samplePosition(1420, 1600, false)
        assertEquals(1420L, lyricPositionAt(pause, 5000))
        val resume = corrected.copy(buffering = true).samplePosition(1400, 1700, true).copy(buffering = false)
        assertEquals(1400L, lyricPositionAt(resume, 1700))
    }

    @Test fun overlapsBackgroundAndSeekHaveIndependentActiveIntervals() {
        val lines = listOf(
            LyricLine(1000, "A", endMs = 4000),
            LyricLine(1500, "background", endMs = 2500, isBackground = true),
            LyricLine(2000, "B", endMs = 3500, alignment = LyricAlignment.End),
            LyricLine(5000, "C", endMs = 6000),
        )
        val timeline = LyricTimeline(lines)
        assertEquals(LyricFrame(), timeline.at(0))
        assertEquals(setOf(0), timeline.at(1000).activeIndices)
        assertEquals(0, timeline.at(1500).focusIndex)
        assertEquals(setOf(0, 1, 2), timeline.at(2300).activeIndices)
        assertEquals(setOf(0, 2), timeline.at(2500).activeIndices)
        assertEquals(setOf(0), timeline.at(3500).activeIndices)
        assertTrue(timeline.at(4000).activeIndices.isEmpty())
        assertEquals(setOf(3), timeline.at(5000).activeIndices)
        assertEquals(setOf(0, 1), timeline.at(1600).activeIndices)
        assertEquals(LyricFrame(), timeline.at(0))
        assertTrue(timeline.at(9000).activeIndices.isEmpty())
    }

    @Test fun repeatedCallsKeepSnapshotIdentityAndPlainLrcNeverGetsWords() {
        val lines = listOf(LyricLine(1000, "one"), LyricLine(2000, "two"))
        val timeline = LyricTimeline(lines)
        val frame = timeline.at(1100)
        assertSame(frame, timeline.at(1100))
        assertSame(frame, timeline.at(1500))
        assertEquals(setOf(1), timeline.at(2000).activeIndices)
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test fun largeTimelineMatchesReferenceDuringForwardPlaybackAndRandomSeeks() {
        val lines = (0 until 10_000).map { LyricLine(it * 100L, "$it", endMs = it * 100L + 350) }
        val timeline = LyricTimeline(lines)
        for (time in (0L..20_000L step 17) + listOf(900_005L, 800L, 10_000L, 0L, 990_000L)) {
            val frame = timeline.at(time)
            assertEquals(lines.indices.filter { lines[it].startMs <= time && time < lines[it].endMs!! }.toSet(), frame.activeIndices)
            assertEquals(lines.indexOfLast { it.startMs <= time }, frame.focusIndex)
        }
    }

    @Test fun wordProgressHandlesLeadInEndAndZeroDuration() {
        val word = LyricWord("词", 1000, 2000)
        assertEquals(0f, lyricWordProgress(word, 0), 0f)
        assertEquals(0.5f, lyricWordProgress(word, 1500), 0f)
        assertEquals(1f, lyricWordProgress(word, 3000), 0f)
        assertEquals(1f, lyricWordProgress(word.copy(endMs = 1000), 1000), 0f)
        assertEquals(0f, lyricWordProgress(word.copy(endMs = 1000), 999), 0f)
    }

    @Test fun knownLineEndsLeaveAQuietGapWithoutAdvancingTheFocusEarly() {
        val timeline = LyricTimeline(listOf(
            LyricLine(1000, "上一句", endMs = 2000),
            LyricLine(8000, "下一句", endMs = 9000),
        ))
        assertEquals(setOf(0), timeline.at(1999).activeIndices)
        for (time in listOf(2000L, 3000L, 7999L)) {
            val frame = timeline.at(time)
            assertEquals("间奏不能提前滚到下一句", 0, frame.focusIndex)
            assertTrue("唱完后不能保持演唱高亮", frame.activeIndices.isEmpty())
        }
        assertEquals(setOf(1), timeline.at(8000).activeIndices)
        assertTrue(timeline.at(9000).activeIndices.isEmpty())
        assertEquals(setOf(0), timeline.at(1500).activeIndices)
    }

    @Test fun unknownLrcEndDoesNotInventASilentInterval() {
        val timeline = LyricTimeline(listOf(LyricLine(0, "普通LRC"), LyricLine(10_000, "下一句")))
        assertEquals(setOf(0), timeline.at(9_999).activeIndices)
        assertEquals(setOf(1), timeline.at(10_000).activeIndices)
    }

    @Test fun emptyAndSimultaneousLinesUseStableBoundaries() {
        assertEquals(LyricFrame(), LyricTimeline(emptyList()).at(5000))
        val lines = listOf(LyricLine(1000, "a"), LyricLine(1000, "b"), LyricLine(2000, "c"))
        assertEquals(-1, lyricIndexAt(lines, 999))
        assertEquals(1, lyricIndexAt(lines, 1000))
        val timeline = LyricTimeline(lines)
        assertEquals(setOf(0, 1), timeline.at(1000).activeIndices)
        assertEquals(setOf(2), timeline.at(2000).activeIndices)
    }
}
