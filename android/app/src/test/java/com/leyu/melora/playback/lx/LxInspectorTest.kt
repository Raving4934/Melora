package com.leyu.melora.playback.lx

import com.leyu.melora.playback.sdk.OnlineSong
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertTrue(probe.failed)
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
        assertFalse(probe.failed)
        assertEquals("FLAC", probe.spec?.codecLabel)
        assertEquals(24, probe.spec?.bitDepth)
        assertEquals(48_000, probe.spec?.sampleRate)
    }

    private fun probe(type: String, bytes: ByteArray, range: AtomicReference<String>? = null): LxInspector.LinkProbe {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                range?.set(exchange.requestHeaders.getFirst("Range"))
                exchange.responseHeaders.set("Content-Type", type)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
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
