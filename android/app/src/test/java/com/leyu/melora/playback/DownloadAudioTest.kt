package com.leyu.melora.playback

import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DownloadAudioTest {
    @Test fun flacUsesStreamInfoNotFileNameOrSourceClaim() {
        for (depth in listOf(16, 24, 32)) {
            val header = ByteArray(42)
            byteArrayOf(102, 76, 97, 67, 0x80.toByte(), 0, 0, 34).copyInto(header)
            val packed = (48000L shl 44) or (1L shl 41) or ((depth - 1L) shl 36) or 48000L
            ByteBuffer.wrap(header).putLong(18, packed)
            val actual = requireNotNull(flacDownloadAudio(header))
            assertEquals(depth, actual.spec.bitDepth)
            assertEquals(1000L, actual.durationMs)
            assertEquals(if (depth == 16) "SQ" else "HR", actual.spec.qualityBadge)
        }
        assertNull(flacDownloadAudio("{\"url\":\"not an audio file\"}".toByteArray()))
        assertNull(flacDownloadAudio(ByteArray(42)))
    }

    @Test fun sameNameIsNotEnoughToSkipAnotherArtistOrVersion() {
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "1")
            .put("name", "测试歌曲").put("singer", "歌手A").put("interval", "03:00"))
        val spec = AudioSpecification("audio/flac", 48000, -1, 24)
        fun file(title: String, artist: String, duration: Long = 180000) = DownloadAudio(spec, duration,
            LocalTagReader.Tag(title, artist, "专辑", duration, 48000, -1, 2020, 24))
        assertTrue(file("测试歌曲", "歌手A").matches(song, false))
        assertFalse(file("测试歌曲", "歌手B").matches(song, false))
        assertFalse(file("测试歌曲 (Live)", "歌手A").matches(song, false))
        assertFalse(file("测试歌曲", "歌手A", 30000).matches(song, true))
        assertFalse(file("", "").matches(song, false))
        assertTrue(file("", "").matches(song, true))
    }
}
