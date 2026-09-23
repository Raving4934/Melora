package com.leyu.melora.playback

import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DownloadAudioTest {
    @Test fun flacUsesStreamInfoNotFileNameOrSourceClaim() {
        for (depth in listOf(16, 24, 32)) {
            val header = flacHeader(depth)
            val actual = requireNotNull(flacDownloadAudio(header))
            assertEquals(depth, actual.spec.bitDepth)
            assertEquals(1000L, actual.durationMs)
            assertEquals(if (depth == 16) "SQ" else "HR", actual.spec.qualityBadge)
        }
        assertNull(flacDownloadAudio("{\"url\":\"not an audio file\"}".toByteArray()))
        assertNull(flacDownloadAudio(ByteArray(42)))
    }

    @Test
    fun flacProbeOnlyReadsStreamInfoAndLeavesTheUnreadTailForDownload() {
        val header = flacHeader(24)
        val payload = ByteArray(128 * 1024 + 17) { index -> (index * 31).toByte() }
        header.copyInto(payload)
        val input = ByteArrayInputStream(payload)

        val probe = probeDownloadUpgrade(input)

        assertEquals(42, probe.prefix.size)
        assertArrayEquals(payload.copyOf(42), probe.prefix)
        assertArrayEquals(payload.copyOfRange(42, payload.size), input.readBytes())
        assertEquals("audio/flac", probe.spec?.mimeType)
        assertEquals(24, probe.spec?.bitDepth)
    }

    @Test fun unknownPrefixIsBoundedAndReplayedWithoutLosingBytes() {
        val payload = ByteArray(128 * 1024 + 123) { 0 }
        val input = ByteArrayInputStream(payload)
        val prefix = readDownloadUpgradePrefix(input)
        assertEquals(128 * 1024, prefix.size)
        assertArrayEquals(payload, prefix + input.readBytes())
    }

    @Test
    fun probePropagatesInputIOException() {
        val expected = IOException("read failed")
        val input = object : InputStream() {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw expected
            override fun read(): Int = throw expected
        }

        val actual = assertThrows(IOException::class.java) {
            probeDownloadUpgrade(input)
        }

        assertSame(expected, actual)
    }

    @Test
    fun betterDownloadQualityUsesStrictSourceResolverRanks() {
        assertTrue(
            isBetterDownloadQuality(
                AudioSpecification("audio/mpeg", 44_100, 192_000),
                AudioSpecification("audio/mp4a-latm", 44_100, 160_000),
            ) == true,
        )
        assertFalse(
            isBetterDownloadQuality(
                AudioSpecification("audio/mpeg", 44_100, 320_000),
                AudioSpecification("audio/mpeg", 44_100, 320_000),
            ) == true,
        )
        assertFalse(
            isBetterDownloadQuality(
                AudioSpecification("audio/wav", 48_000, -1, 16),
                AudioSpecification("audio/flac", 48_000, -1, 16),
            ) == true,
        )
        assertFalse(
            isBetterDownloadQuality(
                AudioSpecification("audio/dsd", 2_822_400, -1),
                AudioSpecification("audio/flac", 96_000, -1, 24),
            ) == true,
        )
    }

    @Test
    fun betterDownloadQualityReturnsUnknownWhenEitherMeasuredQualityIsUnknown() {
        assertNull(
            isBetterDownloadQuality(
                AudioSpecification("audio/mpeg", 44_100, -1),
                AudioSpecification("audio/mpeg", 44_100, 128_000),
            ),
        )
        assertNull(
            isBetterDownloadQuality(
                AudioSpecification("audio/unknown", 44_100, 999_000),
                AudioSpecification("audio/mpeg", 44_100, 128_000),
            ),
        )
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

    private fun flacHeader(depth: Int): ByteArray {
        val header = ByteArray(42)
        byteArrayOf(102, 76, 97, 67, 0x80.toByte(), 0, 0, 34).copyInto(header)
        val packed = (48000L shl 44) or (1L shl 41) or ((depth - 1L) shl 36) or 48000L
        ByteBuffer.wrap(header).putLong(18, packed)
        return header
    }

}
