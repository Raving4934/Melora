package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationLyricPolicyTest {
    private fun lyric(uid: String = "a", text: String = "第一句") = PlayerLyric(
        uid = uid,
        title = "歌名",
        artist = "歌手",
        source = "test",
        lines = listOf(LyricLine(100, text), LyricLine(1000, "第二句"), LyricLine(2000, "  ")),
    )

    @Test
    fun currentLineUsesPlaybackPositionIncludingSeekBack() {
        val lyric = lyric()
        assertNull(notificationLyricLine(true, "a", 99, lyric))
        assertEquals("第一句", notificationLyricLine(true, "a", 100, lyric))
        assertEquals("第二句", notificationLyricLine(true, "a", 1500, lyric))
        assertEquals("第一句", notificationLyricLine(true, "a", 500, lyric))
        assertNull(notificationLyricLine(true, "a", 2000, lyric))
    }

    @Test
    fun disabledStoppedAndMissingLyricsRestoreArtist() {
        assertNull(notificationLyricLine(false, "a", 500, lyric()))
        assertNull(notificationLyricLine(true, null, 500, lyric()))
        assertNull(notificationLyricLine(true, "a", 500, null))
        assertNull(notificationLyricLine(true, "a", 500, lyric().copy(lines = emptyList())))
    }

    @Test
    fun staleLyricsCannotCrossTrackBoundary() {
        assertNull(notificationLyricLine(true, "b", 500, lyric("a")))
        assertEquals("第一句", notificationLyricLine(true, "b", 500, lyric("b")))
    }

    @Test
    fun identicalCachedLyricsRemainValidForEachTrack() {
        for (uid in listOf("a", "b", "a")) {
            assertEquals("纯音乐，请欣赏", notificationLyricLine(true, uid, 500, lyric(uid, "纯音乐，请欣赏")))
        }
    }
}
