package com.leyu.melora.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPlaybackTrackerTest {
    @Test
    fun `music is recorded after five seconds of actual playback`() {
        val tracker = RecentPlaybackTracker()

        assertFalse(tracker.update("song", isPlaying = false, isBookChapter = false, nowMs = 0))
        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 1_000))
        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 5_999))
        assertTrue(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 6_000))
        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 20_000))
    }

    @Test
    fun `paused playback keeps accumulated listening time without counting the pause`() {
        val tracker = RecentPlaybackTracker()

        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 0))
        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 3_000))
        assertFalse(tracker.update("song", isPlaying = false, isBookChapter = false, nowMs = 30_000))
        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 40_000))
        assertFalse(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 41_999))
        assertTrue(tracker.update("song", isPlaying = true, isBookChapter = false, nowMs = 42_000))
    }

    @Test
    fun `book chapters use ten second threshold and changing track resets progress`() {
        val tracker = RecentPlaybackTracker()

        assertFalse(tracker.update("chapter-1", isPlaying = true, isBookChapter = true, nowMs = 0))
        assertFalse(tracker.update("chapter-1", isPlaying = true, isBookChapter = true, nowMs = 9_999))
        assertFalse(tracker.update("chapter-2", isPlaying = true, isBookChapter = true, nowMs = 10_000))
        assertFalse(tracker.update("chapter-2", isPlaying = true, isBookChapter = true, nowMs = 19_999))
        assertTrue(tracker.update("chapter-2", isPlaying = true, isBookChapter = true, nowMs = 20_000))
    }
}
