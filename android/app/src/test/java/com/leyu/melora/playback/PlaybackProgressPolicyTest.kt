package com.leyu.melora.playback

import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressPolicyTest {
    @After
    fun tearDown() {
        LocalMediaStore.clear()
    }

    @Test
    fun mediaLibraryCommandsRequireOwnUidOrTrustedController() {
        assertTrue(canUsePrivateMediaCommands(1000, 1000, false))
        assertTrue(canUsePrivateMediaCommands(2000, 1000, true))
        assertFalse(canUsePrivateMediaCommands(2000, 1000, false))
    }

    @Test
    fun regularSongDoesNotResumeFromNearEndOrMiddle() {
        assertFalse(shouldRestoreProgress(isBookChapter = false, durationMs = 180_000, savedMs = 175_000))
        assertFalse(shouldRestoreProgress(isBookChapter = false, durationMs = 180_000, savedMs = 60_000))
        assertFalse(shouldRestoreProgress(isBookChapter = false, durationMs = 0, savedMs = 60_000))
        assertFalse(shouldRestoreProgress(isBookChapter = false, durationMs = 180_000, savedMs = 4_000))
    }

    @Test
    fun bookAndLongTrackResumeUnlessFinished() {
        assertTrue(shouldRestoreProgress(isBookChapter = true, durationMs = 180_000, savedMs = 60_000))
        assertFalse(shouldRestoreProgress(isBookChapter = true, durationMs = 180_000, savedMs = 175_000))
        assertTrue(shouldRestoreProgress(isBookChapter = false, durationMs = 12 * 60 * 1000L, savedMs = 60_000))
        assertTrue(shouldRestoreProgress(isBookChapter = true, durationMs = 0, savedMs = 60_000))
    }

    @Test
    fun finishedTrackClearsPersistedProgress() {
        assertEquals(0L, persistedProgressMs(179_000, 180_000))
        assertEquals(90_000L, persistedProgressMs(90_000, 180_000))
        assertEquals(90_000L, persistedProgressMs(90_000, 0))
    }

    @Test
    fun localAndDownloadedTracksSkipNetworkPrefetch() {
        LocalMediaStore.replaceAll(
            listOf(
                LocalSong(
                    id = "ms_1",
                    uri = "file:///sdcard/Music/a.mp3",
                    title = "夜曲",
                    artist = "周杰伦",
                    album = "",
                    durationMs = 180_000,
                    sizeBytes = 4_000_000,
                    mimeType = "audio/mpeg",
                    sampleRate = 44_100,
                    bitrate = 320_000,
                    modifiedAt = 1,
                    addedAt = 1,
                    folder = "Music",
                ),
            ),
        )
        assertFalse(needsNetworkPrefetch(track("local", "local_ms_1", "夜曲", "周杰伦")))
        assertFalse(needsNetworkPrefetch(track("kw", "kw_1", "夜曲", "周杰伦")))
        assertTrue(needsNetworkPrefetch(track("kw", "kw_2", "稻香", "周杰伦")))
        assertFalse(needsNetworkPrefetch(UiTrack(uid = "file://a", title = "a", artist = "b", album = "")))
    }

    private fun track(source: String, uid: String, title: String, artist: String) = UiTrack(
        uid = uid,
        title = title,
        artist = artist,
        album = "",
        source = source,
        raw = JSONObject()
            .put("source", source)
            .put("songmid", uid.substringAfter('_'))
            .put("name", title)
            .put("singer", artist),
    )
}
