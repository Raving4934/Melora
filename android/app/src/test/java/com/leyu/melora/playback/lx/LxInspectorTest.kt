package com.leyu.melora.playback.lx

import com.leyu.melora.playback.lx.LxInspector.LinkStatus
import com.leyu.melora.playback.sdk.OnlineSong
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class LxInspectorTest {
    @Test
    fun samplePrefersOriginalAlbumOverCompilationAndSkipsLiveVersion() {
        fun song(id: String, album: String, title: String, interval: String) =
            OnlineSong(JSONObject().put("source", "wy").put("songmid", id)
                .put("name", title).put("singer", "孙燕姿")
                .put("albumName", album).put("interval", interval))
        val compilation = song("454828887", "白羽毛之恋 华语典藏情歌 Vol.2", "遇见", "03:29")
        val live = song("34200629", "2010垦丁半岛音乐嘉年华", "遇见 (Live)", "03:59")
        val original = song("287319", "The Moment", "遇见", "03:29")

        assertEquals(original, LxInspector.pickCatalogSample("wy", listOf(compilation, live, original)))
        assertNull(LxInspector.pickCatalogSample("wy", listOf(live)))
    }

    @Test
    fun successfulHttpResponseWithDisguisedHtmlIsRejected() {
        val probe = probe("audio/mpeg", "\uFEFF\n<!DOCTYPE html><html>Unavailable</html>".toByteArray())

        assertEquals(LinkStatus.INVALID, probe.status)
        assertNull(probe.spec)
        assertEquals("链接返回网页", probe.message)
    }

    @Test
    fun rangeProbeRecognizesFlacFromBytesWhenServerDoesNotProvideAnAudioMime() {
        val stream = ByteArray(256 * 1024)
        byteArrayOf(102, 76, 97, 67, 0x80.toByte(), 0, 0, 34).copyInto(stream)
        val streamInfo = (48_000L shl 44) or (1L shl 41) or (23L shl 36) or 48_000L
        ByteBuffer.wrap(stream).putLong(18, streamInfo)
        val range = AtomicReference<String>()

        val probe = probe("application/octet-stream", stream, range)

        assertEquals("bytes=0-131071", range.get())
        assertEquals(LinkStatus.VALID, probe.status)
        assertEquals("FLAC", probe.spec?.codecLabel)
        assertEquals(24, probe.spec?.bitDepth)
        assertEquals(48_000, probe.spec?.sampleRate)
    }

    @Test
    fun temporaryHttpFailureIsInconclusiveButExpiredLinkIsInvalid() {
        val unavailable = probe("text/html", byteArrayOf(), responseCode = 503)
        val expired = probe("text/html", byteArrayOf(), responseCode = 410)

        assertEquals(LinkStatus.INCONCLUSIVE, unavailable.status)
        assertEquals(LinkStatus.INVALID, expired.status)
    }

    @Test
    fun playlistWithoutVerifiedAudioIsInconclusive() {
        val playlist = probe("application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:5,\nsegment.aac\n".toByteArray())

        assertEquals(LinkStatus.INCONCLUSIVE, playlist.status)
        assertNull(playlist.spec)
    }

    private fun probe(
        type: String,
        bytes: ByteArray,
        range: AtomicReference<String>? = null,
        responseCode: Int = 200,
    ): LxInspector.LinkProbe {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                range?.set(exchange.requestHeaders.getFirst("Range"))
                exchange.responseHeaders.set("Content-Type", type)
                exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        return try {
            runBlocking { LxInspector.probeMedia("http://127.0.0.1:${server.address.port}/") }
        } finally {
            server.stop(0)
        }
    }
}
