package com.leyu.melora.playback.sdk

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwBookApiSearchTest {
    @Test(timeout = 10_000)
    fun sameRequestPathDecodesObjectAndArray() = runBlocking {
        withResponse("{\"total\":125}") { url ->
            assertEquals(125, KwBookApi.get(url, ::JSONObject)?.getInt("total"))
        }
        withResponse("[1,2,3]") { url ->
            assertEquals(3, KwBookApi.get(url, ::JSONArray)?.length())
        }
    }

    @Test(timeout = 10_000)
    fun httpFailureAndMalformedJsonRetainNullFallback() = runBlocking {
        withResponse("{}", status = "503 Unavailable") { url ->
            assertNull(KwBookApi.get(url, ::JSONObject))
        }
        withResponse("not json") { url -> assertNull(KwBookApi.get(url, ::JSONObject)) }
    }

    private suspend fun withResponse(body: String, status: String = "200 OK", check: suspend (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* 消费请求头 */ }
                    val bytes = body.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            try { check("http://127.0.0.1:${server.localPort}/catalog") } finally { worker.join(1_000) }
        }
    }

    @Test(timeout = 10_000)
    fun cancellationClosesConnectionWaitingForHeaders() = assertRequestCancelled(sendPartialBody = false)

    @Test(timeout = 10_000)
    fun cancellationClosesConnectionWhileReadingBody() = assertRequestCancelled(sendPartialBody = true)

    private fun assertRequestCancelled(sendPartialBody: Boolean) = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val socket = AtomicReference<Socket?>()
        val received = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val worker = thread(isDaemon = true, name = "book-slow-http") {
            try {
                server.accept().use { connection ->
                    socket.set(connection)
                    connection.soTimeout = 5_000
                    val reader = connection.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* 消费请求头 */ }
                    if (sendPartialBody) {
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{".toByteArray())
                            flush()
                        }
                    }
                    received.countDown()
                    if (reader.read() == -1) disconnected.countDown()
                }
            } catch (_: java.io.IOException) {
                // 失败用例的 finally 主动关闭测试 socket，不能将其算作客户端取消成功。
            }
        }
        val request = launch(Dispatchers.IO) {
            KwBookApi.get("http://127.0.0.1:${server.localPort}/catalog", ::JSONObject)
        }
        try {
            assertTrue("请求应已到达服务器", withContext(Dispatchers.IO) { received.await(3, TimeUnit.SECONDS) })
            val cancelled = withTimeoutOrNull(1_000) { request.cancelAndJoin(); true } ?: false
            val closed = withContext(Dispatchers.IO) { disconnected.await(1, TimeUnit.SECONDS) }
            assertTrue("取消目录请求不能等待读取超时", cancelled)
            assertTrue("取消应同时关闭底层 HTTP 连接", closed)
        } finally {
            request.cancel()
            socket.get()?.close()
            server.close()
            worker.join(1_000)
        }
    }

    @Test fun malformedResponseIsNotPresentedAsAnEmptyAuthor() {
        assertThrows(IllegalStateException::class.java) {
            KwBookApi.searchPageFromResponse(JSONObject().put("total", "0"))
        }
        assertThrows(IllegalStateException::class.java) {
            KwBookApi.searchPageFromResponse(JSONObject().put("albumlist", "not-an-array"))
        }
    }

    @Test fun albumResponseKeepsIdentityMetadataAndPaging() {
        val rows = JSONArray()
        repeat(20) { index -> rows.put(JSONObject().put("albumid", "$index")
            .put("name", "测试作品$index").put("artist", "测试作者&测试主播").put("musiccnt", "120")) }
        val page = KwBookApi.searchPageFromResponse(JSONObject().put("albumlist", rows))
        assertEquals(20, page.items.size)
        assertTrue(page.hasMore)
        assertTrue(page.items.all { it.isBookAlbum })
        assertEquals("book_album_0", page.items.first().id)
        assertEquals("测试作者&测试主播", page.items.first().author)
        assertEquals(120, page.items.first().total)
    }

    @Test
    fun emptyAlbumlistIsAValidEmptyPage() {
        val page = KwBookApi.searchPageFromResponse(
            JSONObject().put("total", "0").put("albumlist", JSONArray()),
        )

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }
}
