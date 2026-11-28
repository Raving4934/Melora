package com.leyu.melora.playback

import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
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
            assertEquals(2, actual.spec.channelCount)
            assertEquals(-1, actual.spec.bitrate)
            assertEquals(1000L, actual.durationMs)
            assertEquals(if (depth == 16) "LOSSLESS" else "HI-RES", actual.spec.qualityBadge?.label)
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
        assertEquals(2, probe.spec?.channelCount)
        assertEquals(1000L, probe.durationMs)
    }

    @Test
    fun prefixedFlacProbeHandlesShortReadsAndReplaysEveryByte() {
        // 首段只包含 ID3 标签以及不完整的 STREAMINFO；扩充到探测上限后才能识别格式。
        val tagSize = 8_180
        val id3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, (tagSize ushr 7).toByte(), (tagSize and 0x7F).toByte())
        val payload = id3 + ByteArray(tagSize) + flacHeader(24) + ByteArray(140_000) { it.toByte() }
        val input = object : ByteArrayInputStream(payload) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 31))
        }
        val probe = probeDownloadUpgrade(input)

        assertEquals("audio/flac", probe.spec?.mimeType)
        assertEquals(24, probe.spec?.bitDepth)
        assertEquals(1_000L, probe.durationMs)
        assertArrayEquals(payload, probe.prefix + input.readBytes())
        assertTrue(probe.prefix.size <= AUDIO_PREFIX_PROBE_BYTES)
    }

    @Test fun rangeProbeUsesPayloadForQualityAndDurationEvenWhenUrlPromisesHiRes() = runBlocking {
        val header = flacHeader(16)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/master") { exchange ->
            exchange.use {
                if (it.requestHeaders.getFirst("Range") != "bytes=0-${AUDIO_PREFIX_PROBE_BYTES - 1}") {
                    it.sendResponseHeaders(416, -1)
                } else {
                    it.responseHeaders.add("Content-Range", "bytes 0-41/42")
                    it.sendResponseHeaders(206, header.size.toLong())
                    it.responseBody.write(header)
                }
            }
        }
        server.start()
        try {
            val result = requireNotNull(AudioCacheStore.probePlaybackCandidate("http://127.0.0.1:${server.address.port}/master"))
            assertEquals("flac", result.spec?.verifiedQuality)
            assertEquals(1_000L, result.durationMs)
            assertTrue(isPreviewDuration(12, requireNotNull(result.durationMs)))
        } finally {
            server.stop(0)
        }
    }

    @Test fun previewRequiresAConfirmedDurationShorterThanTheCatalogTolerance() {
        assertTrue(isPreviewDuration(180, 30_000))
        assertTrue(isPreviewDuration(180, 116_999))
        assertFalse(isPreviewDuration(180, 117_000))
        assertFalse(isPreviewDuration(1, 1_000))
        assertFalse(isPreviewDuration(0, 30_000))
        assertFalse(isPreviewDuration(180, 0))
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

    @Test
    fun masterUpgradeComparesMeasuredLosslessSampleRateWithoutSacrificingBitDepth() {
        val ordinary = AudioSpecification("audio/flac", 48_000, -1, 24)
        val master = ordinary.copy(sampleRate = 192_000)
        assertEquals(false, isBetterDownloadQuality(master, ordinary))
        assertEquals(true, isBetterDownloadQuality(master, ordinary, preferMaster = true))
        assertEquals(false, isBetterDownloadQuality(ordinary, master, preferMaster = true))
        assertEquals(false, isBetterDownloadQuality(master.copy(bitDepth = 16), ordinary, preferMaster = true))
    }

    @Test
    fun flacWithoutPcmChecksumCanCompareFramesWithoutComparingTags() {
        val info = flacHeader(24)
        assertNull(flacDownloadAudio(info)?.flacPcmChecksum)
        val withPadding = info.copyOf().apply { this[4] = 0 }
            .plus(byteArrayOf(0x81.toByte(), 0, 0, 2, 0, 0))
        val frames = byteArrayOf(1, 2, 3)
        assertTrue(sameDownloadedBytes(
            ByteArrayInputStream(info + frames),
            ByteArrayInputStream(withPadding + frames),
            flac = true,
        ))
        assertFalse(sameDownloadedBytes(
            ByteArrayInputStream(info + frames),
            ByteArrayInputStream(withPadding + byteArrayOf(1, 2, 4)),
            flac = true,
        ))
        assertFalse(sameDownloadedBytes(
            ByteArrayInputStream(info + frames),
            ByteArrayInputStream(withPadding + frames.copyOf(2)),
            flac = true,
        ))
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
