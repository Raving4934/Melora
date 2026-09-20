package com.leyu.melora.playback.lx

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DataFormatException
import java.util.zip.Deflater

class LxHostTest {
    @Test
    fun outerRequestDeadlineCapsEveryHostHttpCall() {
        assertEquals(15_000, effectiveHttpTimeoutMs(15_000, Long.MAX_VALUE, nowMs = 10_000))
        assertEquals(700, effectiveHttpTimeoutMs(15_000, deadlineMs = 10_700, nowMs = 10_000))
        assertEquals(400, effectiveHttpTimeoutMs(15_000, deadlineMs = 10_400, nowMs = 10_000))
        assertEquals(0, effectiveHttpTimeoutMs(15_000, deadlineMs = 10_000, nowMs = 10_001))
        assertEquals(100, effectiveHttpTimeoutMs(1, Long.MAX_VALUE, nowMs = 10_000))
    }

    @Test
    fun cancellationBeforeCallCreationSkipsOkHttp() {
        val calls = AtomicInteger()
        val host = LxHost(client {
            calls.incrementAndGet()
            error("OkHttp must not be entered")
        })
        val token = host.newRequestToken()
        host.cancelRequest(token)

        val result = JSONObject(host.withinRequestTimeout(token, 5_000) { host.http(requestPayload()) })

        assertEquals(0, calls.get())
        assertTrue(result.getString("error").contains("取消"))
    }

    @Test(timeout = 2_000)
    fun cancellationCancelsOnlyTheMatchingActiveCall() {
        val entered = CountDownLatch(1)
        val observedCancellation = CountDownLatch(1)
        val calls = AtomicInteger()
        val host = LxHost(client { chain ->
            calls.incrementAndGet()
            entered.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(1)
            observedCancellation.countDown()
            throw IOException("cancelled")
        })
        val token = host.newRequestToken()
        val otherToken = host.newRequestToken()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                host.withinRequestTimeout(token, 5_000) { host.http(requestPayload()) }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            host.cancelRequest(otherToken)
            assertFalse(observedCancellation.await(100, TimeUnit.MILLISECONDS))

            host.cancelRequest(token)
            assertTrue(observedCancellation.await(1, TimeUnit.SECONDS))
            val result = JSONObject(future.get(1, TimeUnit.SECONDS))
            assertTrue(result.getString("error").contains("cancelled"))
            assertEquals(1, calls.get())
        } finally {
            host.cancelRequest(token)
            executor.shutdownNow()
        }
    }

    @Test
    fun boundedHeaderReadDoesNotDownloadAnIgnoredRangeResponse() {
        val body = generatedBody(HTTP_LIMIT + 1)
        val result = JSONObject(LxHost(clientReturning(body)).http(JSONObject(requestPayload()).put("maxResponseBytes", 64).toString()))
        assertFalse(result.has("error"))
        assertEquals(64, Base64.getDecoder().decode(result.getString("raw")).size)
        assertEquals(800, effectiveHttpTimeoutMs(800, Long.MAX_VALUE, 0))
    }

    @Test
    fun httpMakesOneCallAndPreservesFinalUrl() {
        val calls = AtomicInteger()
        val client = client { chain ->
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request().newBuilder().url("https://cdn.example.test/final").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("hello".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val result = JSONObject(LxHost(client).http(requestPayload()))

        assertEquals(1, calls.get())
        assertEquals(200, result.getInt("statusCode"))
        assertEquals("https://cdn.example.test/final", result.getString("finalUrl"))
        assertEquals("hello", String(Base64.getDecoder().decode(result.getString("raw"))))
    }

    @Test
    fun httpDoesNotRepeatHostFailures() {
        val calls = AtomicInteger()
        val client = client {
            calls.incrementAndGet()
            throw IOException("offline")
        }

        val result = JSONObject(LxHost(client).http(requestPayload()))

        assertEquals(1, calls.get())
        assertEquals("offline", result.getString("error"))
    }

    @Test
    fun httpRejectsOversizedDeclaredBodyBeforeReading() {
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = HTTP_LIMIT + 1
            override fun source(): BufferedSource = error("body must not be read")
        }
        val result = JSONObject(LxHost(clientReturning(body)).http(requestPayload()))

        assertTrue(result.getString("error").contains("8 MiB"))
    }

    @Test
    fun httpRejectsOversizedChunkedBodyWhileStreaming() {
        val result = JSONObject(LxHost(clientReturning(generatedBody(HTTP_LIMIT + 1))).http(requestPayload()))

        assertTrue(result.getString("error").contains("8 MiB"))
    }

    @Test
    fun inflateRestoresCompleteStream() {
        val original = "Melora-歌词-123".repeat(100).toByteArray()
        assertArrayEquals(original, inflateZlib(deflate(original)))
    }

    @Test
    fun inflateRejectsTruncatedStream() {
        val compressed = deflate("truncated".repeat(100).toByteArray())
        val error = assertThrows(DataFormatException::class.java) {
            inflateZlib(compressed.copyOf(compressed.size - 2))
        }
        assertTrue(error.message.orEmpty().contains("truncated"))
    }

    @Test(timeout = 1_000)
    fun inflateRejectsPresetDictionaryWithoutLooping() {
        val dictionary = "melora-dictionary".toByteArray()
        val error = assertThrows(DataFormatException::class.java) {
            inflateZlib(deflate("melora-dictionary-payload".toByteArray(), dictionary))
        }
        assertTrue(error.message.orEmpty().contains("dictionary"))
    }

    @Test
    fun inflateAllowsOutputExactlyAtLimit() {
        val original = "bounded-output".repeat(100).toByteArray()

        assertArrayEquals(original, inflateZlib(deflate(original), original.size))
    }

    @Test
    fun inflateRejectsOutputBeyondLimit() {
        val original = "inflate-bomb".repeat(1_000).toByteArray()
        val error = assertThrows(DataFormatException::class.java) {
            inflateZlib(deflate(original), original.size - 1)
        }

        assertTrue(error.message.orEmpty().contains("${original.size - 1} bytes"))
    }

    @Test
    fun deflateUsesTheSameBoundedCodecPath() {
        val original = "Melora-zlib".repeat(200).toByteArray()

        assertArrayEquals(original, inflateZlib(deflateZlib(original)))
        assertThrows(DataFormatException::class.java) {
            deflateZlib(ByteArray(1_024) { it.toByte() }, maxOutputBytes = 16)
        }
    }

    @Test
    fun base64DecoderRejectsInputBeyondConfiguredLimit() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            decodeBase64Limited("A".repeat(9), maxBytes = 4)
        }

        assertTrue(error.message.orEmpty().contains("4 bytes"))
    }

    private fun client(interceptor: Interceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(interceptor)
        .retryOnConnectionFailure(true)
        .build()

    private fun clientReturning(body: ResponseBody): OkHttpClient = client { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private fun generatedBody(size: Long): ResponseBody = object : ResponseBody() {
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            private var remaining = size

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1L
                val count = minOf(remaining, byteCount, 16 * 1024L).toInt()
                sink.write(ByteArray(count))
                remaining -= count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    private fun deflate(input: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val deflater = Deflater()
        return try {
            dictionary?.let(deflater::setDictionary)
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                assertFalse(count == 0 && !deflater.finished())
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun requestPayload(): String = JSONObject()
        .put("url", "https://origin.example.test/start")
        .put("method", "GET")
        .toString()

    private companion object {
        const val HTTP_LIMIT = 8L * 1024 * 1024
    }
}
