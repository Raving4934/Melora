package com.leyu.melora.playback.sdk

import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.AUDIO_PREFIX_PROBE_BYTES
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** 给真实解析和探测链路提供离线可重放的媒体、错误页与 Range 特例。 */
internal class ResolutionMediaServer : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val shortMp3 by lazy { fixture("fixture-128.mp3") }
    private val unindexedMp3 by lazy {
        shortMp3.copyOf().apply {
            val info = toString(Charsets.ISO_8859_1).indexOf("Info")
            check(info >= 0)
            fill(0, info, info + 4)
        }
    }
    private val fullMp3 by lazy { fixture("scheduling-12s.mp3") }
    private val fullFlac by lazy { fixture("scheduling-12s-24.flac") }
    private val html = "<html>unavailable</html>".toByteArray()
    private val unknown = "unidentified media fixture".toByteArray()
    val url = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    val prefixRequests = CopyOnWriteArrayList<String>()
    private val worker = thread(isDaemon = true, name = "resolution-media-fixture") {
        while (!server.isClosed) {
            try {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    val reader = peer.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    val headers = generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
                    val (method, path) = line.split(' ', limit = 3)
                    requests += "$method $path"
                    val range = headers.any {
                        it.equals("Range: bytes=0-${AUDIO_PREFIX_PROBE_BYTES - 1}", ignoreCase = true)
                    }
                    if (range) {
                        prefixRequests += path
                        if (path.startsWith("/verified-slow-probe-")) Thread.sleep(1_000)
                    }
                    val (mime, body) = when {
                        path.startsWith("/page-") || method == "HEAD" && path.startsWith("/head-page-") -> "text/html" to html
                        path.startsWith("/opaque-page-") -> "application/octet-stream" to html
                        path.startsWith("/preview-") -> "audio/mpeg" to shortMp3
                        path.startsWith("/unindexed-") -> "audio/mpeg" to unindexedMp3
                        path.startsWith("/lower-") || path.startsWith("/range-unavailable-") -> "audio/mpeg" to fullMp3
                        path.startsWith("/verified-") -> "audio/flac" to fullFlac
                        else -> "application/octet-stream" to unknown
                    }
                    val status = when {
                        method == "HEAD" && path.startsWith("/get-only-") -> "405 Method Not Allowed"
                        range && path.startsWith("/range-unavailable-") -> "416 Range Not Satisfiable"
                        range && mime.startsWith("audio/") -> "206 Partial Content"
                        else -> "200 OK"
                    }
                    val length = when {
                        status.startsWith("416") || status.startsWith("405") -> 0
                        status.startsWith("206") -> minOf(body.size, AUDIO_PREFIX_PROBE_BYTES)
                        else -> body.size
                    }
                    val contentRange = if (status.startsWith("206")) "Content-Range: bytes 0-${length - 1}/${body.size}\r\n" else ""
                    val output = peer.getOutputStream()
                    output.write(("HTTP/1.1 $status\r\nContent-Type: $mime\r\n" +
                        "${contentRange}Content-Length: $length\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") output.write(body, 0, length)
                }
            } catch (_: IOException) {
                // 竞速结束可能先取消客户端；下一条连接仍要正常服务。
            }
        }
    }

    private fun fixture(name: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/$name").use { it.readBytes() }

    override fun close() {
        server.close()
        worker.join(2_500)
    }
}
