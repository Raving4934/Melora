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
