package com.leyu.melora.playback.lx

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.Base64 as JavaBase64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

private const val MAX_ZLIB_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_ZLIB_OUTPUT_BYTES = 16 * 1024 * 1024
private const val MAX_OUTSTANDING_HTTP = 32
private const val MAX_BUFFERED_HTTP_BODY_BYTES = 16L * 1024 * 1024
private const val HTTP_TIMEOUT = "http 请求超时"

private val bufferedHttpBodyBytes = AtomicLong()

/** 异步读入与排队交付共用预算，按原始响应体字节计量。 */
internal class LxHttpBodyReservation : Closeable {
    private var reservedBytes = 0L

    fun reserve(bytes: Int) {
        while (true) {
            val buffered = bufferedHttpBodyBytes.get()
            if (bytes > MAX_BUFFERED_HTTP_BODY_BYTES - buffered) {
                throw IOException("并行 HTTP 响应体超过 16 MiB 缓冲上限")
            }
            if (bufferedHttpBodyBytes.compareAndSet(buffered, buffered + bytes)) {
                reservedBytes += bytes
                return
            }
        }
    }

    override fun close() {
        bufferedHttpBodyBytes.addAndGet(-reservedBytes)
        reservedBytes = 0L
    }
}

internal class LxHttpResponse(
    val json: String,
    private val body: LxHttpBodyReservation? = null,
) : Closeable {
    override fun close() {
        body?.close()
    }
}

// 部分音源 CDN 的 HTTP/2 会断流。各引擎统一使用 HTTP/1.1 和同一连接池。
private val sharedHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()
}

internal class LxRequestToken(internal val scopeId: String) {
    internal val cancelled = AtomicBoolean(false)
    internal val runtimeTicket = AtomicLong(0)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val calls = mutableMapOf<Call, PendingHttp?>()
    private val responses = ArrayDeque<LxHttpResponse>()

    internal fun register(call: Call, pending: PendingHttp? = null) = lock.withLock {
        if (cancelled.get()) throw IOException("脚本请求已取消")
        if (calls.size + responses.size >= MAX_OUTSTANDING_HTTP) throw IOException("并行 HTTP 请求过多")
        calls[call] = pending
    }

    internal fun finish(call: Call, response: LxHttpResponse? = null) {
        lock.withLock {
            if (calls.remove(call) != null && response != null) {
                responses.addLast(response)
                changed.signal()
                return
            }
        }
        response?.close()
    }

    internal fun takeHttpResponse(): String? {
        val (call, pending) = lock.withLock {
            responses.removeFirstOrNull()?.let { return it.use { response -> response.json } }
            val now = System.nanoTime()
            val expired = calls.entries.firstOrNull { (_, pending) ->
                pending != null && pending.deadlineNanos <= now
            } ?: return null
            val call = expired.key
            call to checkNotNull(calls.remove(call))
        }
        call.cancel()
        return pending.result(JSONObject().put("error", HTTP_TIMEOUT))
    }

    internal fun awaitHttpResponse(wakeupNanos: Long) = lock.withLock {
        while (!cancelled.get() && responses.isEmpty()) {
            var wakeup = wakeupNanos
            calls.values.forEach { if (it != null) wakeup = minOf(wakeup, it.deadlineNanos) }
            val remaining = wakeup - System.nanoTime()
            if (remaining <= 0L) break
            changed.awaitNanos(remaining)
        }
    }

    internal fun cancel() {
        val cancelledCalls = lock.withLock {
            cancelled.set(true)
            val snapshot = calls.keys.toList()
            calls.clear()
            responses.forEach(LxHttpResponse::close)
            responses.clear()
            changed.signal()
            snapshot
        }
        cancelledCalls.forEach(Call::cancel)
    }

    internal class PendingHttp(val id: String, val deadlineNanos: Long) {
        fun result(response: JSONObject): String = response.put("requestId", id).toString()
    }
}

private data class LxRequestContext(
    val token: LxRequestToken,
    val deadlineNanos: Long,
    val asyncDeadlineNanos: Long,
)

// LX 的 HTTP 回调由引擎线程交付。内置目录 SDK 同步调用相同的 HTTP 传输。
class LxHost internal constructor(
    private val client: OkHttpClient = sharedHttpClient,
) {
    private val random = SecureRandom()
    private val requestSequence = AtomicLong(0)
    // JS JavaCallbacks run on QuickJS's event thread, not the IO caller that starts the request.
    // One engine serializes executions, so this per-host context is shared across that queue hop.
    @Volatile private var requestContext: LxRequestContext? = null

    internal fun newRequestToken(): LxRequestToken = LxRequestToken(requestSequence.incrementAndGet().toString())

    /** 媒体脚本的JS执行与宿主HTTP共享调用方创建的同一单调时钟deadline。 */
    internal fun <T> withinRequestDeadline(
        token: LxRequestToken,
        deadlineNanos: Long,
        asyncDeadlineNanos: Long = deadlineNanos,
        block: () -> T,
    ): T {
        val previous = requestContext
        requestContext = LxRequestContext(token, deadlineNanos, asyncDeadlineNanos)
        return try {
            block()
        } finally {
            // 成功/异常/超时和外部取消走同一条退役路径，不能遗留宿主 Call。
            token.cancel()
            requestContext = previous
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, message)
    }

    @JavascriptInterface
    fun http(payloadJson: String, scopeId: String = ""): String = try {
        val context = activeRequest(scopeId)
        val token = context?.token
        val payload = JSONObject(payloadJson)
        val call = createHttpCall(payload, httpDeadline(payload, context?.deadlineNanos))
        token?.register(call)
        try {
            httpResponse(call.execute(), payload.optInt("maxResponseBytes", 0)).toString()
        } finally {
            token?.finish(call)
        }
    } catch (error: Exception) {
        httpFailure(error).toString()
    }

    internal fun startHttp(payloadJson: String, scopeId: String, id: String): String = try {
        val context = activeRequest(scopeId) ?: throw IOException("脚本请求已结束")
        val payload = JSONObject(payloadJson)
        val deadline = httpDeadline(payload, context.asyncDeadlineNanos)
        val token = context.token
        val call = createHttpCall(payload, deadline)
        val limit = payload.optInt("maxResponseBytes", 0)
        val pending = LxRequestToken.PendingHttp(id, deadline)
        token.register(call, pending)
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    token.finish(call, LxHttpResponse(pending.result(httpFailure(e))))
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = LxHttpBodyReservation()
                    val result = try {
                        LxHttpResponse(pending.result(httpResponse(response, limit, body)), body)
                    } catch (error: Exception) {
                        body.close()
                        LxHttpResponse(pending.result(httpFailure(error)))
                    }
                    token.finish(call, result)
                }
            })
        } catch (error: Exception) {
            token.finish(call)
            call.cancel()
            throw error
        }
        ""
    } catch (error: Exception) {
        httpFailure(error).toString()
    }

    internal fun takeHttpResponse(): String = requestContext?.token?.takeHttpResponse().orEmpty()

    private fun activeRequest(scopeId: String): LxRequestContext? {
        val context = requestContext
        if (scopeId.isNotEmpty() && scopeId != context?.token?.scopeId) throw IOException("脚本请求已结束")
        if (context?.token?.cancelled?.get() == true) throw IOException("脚本请求已取消")
        return context
    }

    private fun httpDeadline(payload: JSONObject, deadlineNanos: Long?): Long {
        val remainingMs = deadlineNanos?.let {
            TimeUnit.NANOSECONDS.toMillis(it - System.nanoTime())
        } ?: Long.MAX_VALUE
        val timeout = effectiveHttpTimeoutMs(payload.optInt("timeout", 15_000), remainingMs)
        if (timeout <= 0) throw IOException(HTTP_TIMEOUT)
        return minOf(monotonicDeadlineAfter(timeout.toLong()), deadlineNanos ?: Long.MAX_VALUE)
    }

    private fun createHttpCall(payload: JSONObject, deadlineNanos: Long): Call {
        val url = payload.getString("url")
        val method = payload.optString("method", "GET").uppercase()
        val builder = Request.Builder().url(url)
        payload.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { key ->
                headers.optString(key).takeIf { it.isNotEmpty() }?.let { builder.header(key, it) }
            }
        }
        val bodyBase64 = payload.optString("body", "")
        val form = payload.optJSONObject("form")
        val body = when {
            bodyBase64.isNotEmpty() -> Base64.decode(bodyBase64, Base64.DEFAULT).toRequestBody(null)
            form != null -> FormBody.Builder()
                .apply { form.keys().forEach { key -> add(key, form.optString(key)) } }
                .build()
            method != "GET" && method != "HEAD" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, body)
        return client.newCall(builder.build()).also { it.timeout().deadlineNanoTime(deadlineNanos) }
    }

    private fun httpResponse(
        response: Response,
        limit: Int,
        reservation: LxHttpBodyReservation? = null,
    ): JSONObject = response.use {
        JSONObject().apply {
            put("statusCode", response.code)
            put("statusMessage", response.message)
            put("finalUrl", response.request.url.toString())
            put("headers", JSONObject().apply {
                response.headers.forEach { (name, value) -> put(name, value) }
            })
            put("raw", readBodyBase64(response.body, limit, reservation))
        }
    }

    private fun httpFailure(error: Exception): JSONObject =
        JSONObject().put("error", error.message ?: "http 请求失败")

    @JavascriptInterface
    fun hash(algo: String, dataBase64: String): String {
        val algorithm = when (algo.lowercase()) {
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            "sha256" -> "SHA-256"
            else -> throw IllegalArgumentException("不支持的哈希算法: $algo")
        }
        val digest = MessageDigest.getInstance(algorithm).digest(Base64.decode(dataBase64, Base64.DEFAULT))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @JavascriptInterface
    fun zlibInflate(dataBase64: String): String = compressResult {
        inflateZlib(decodeBase64Limited(dataBase64, MAX_ZLIB_INPUT_BYTES))
    }

    @JavascriptInterface
    fun zlibDeflate(dataBase64: String): String = compressResult {
        deflateZlib(decodeBase64Limited(dataBase64, MAX_ZLIB_INPUT_BYTES))
    }

    @JavascriptInterface
    fun randomBase64(length: Int): String {
        val bytes = ByteArray(length.coerceIn(1, 4096))
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun aes(action: String, mode: String, keyBase64: String, ivBase64: String, dataBase64: String): String {
        val normalized = mode.lowercase()
        val parts = normalized.split('-')
        val blockMode = if (parts.any { it.contains("ecb") }) "ECB" else "CBC"
        val noPadding = normalized.contains("nopadding") || normalized.contains("no padding")
        val transformation = if (noPadding) "AES/$blockMode/NoPadding" else "AES/$blockMode/PKCS5Padding"
        val cipher = Cipher.getInstance(transformation)
        val key = SecretKeySpec(Base64.decode(keyBase64, Base64.DEFAULT), "AES")
        val cipherMode = if (action == "encrypt") Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        if (blockMode == "ECB" || iv.isEmpty()) {
            cipher.init(cipherMode, key)
        } else {
            cipher.init(cipherMode, key, IvParameterSpec(iv))
        }
        val result = cipher.doFinal(Base64.decode(dataBase64, Base64.DEFAULT))
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    // PEM 公钥 + NoPadding/OAEP 加密，返回 Base64。
    @JavascriptInterface
    fun rsaEncrypt(dataBase64: String, keyPem: String, padding: String? = null): String {
        val der = keyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(der, Base64.DEFAULT)))
        val transformation = padding?.takeIf { it.isNotBlank() } ?: "RSA/ECB/NoPadding"
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(Base64.decode(dataBase64, Base64.DEFAULT)), Base64.NO_WRAP)
    }

    private fun readBodyBase64(body: ResponseBody, requestedLimit: Int, reservation: LxHttpBodyReservation?): String {
        val limited = requestedLimit in 1..MAX_HTTP_BODY_BYTES.toInt()
        val limit = if (limited) requestedLimit.toLong() else MAX_HTTP_BODY_BYTES
        if (!limited && body.contentLength() > limit) throw IOException(BODY_TOO_LARGE)
        val encoded = ByteArrayOutputStream()
        JavaBase64.getEncoder().wrap(encoded).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    if (limited && total >= limit) break
                    val count = input.read(buffer, 0, if (limited) minOf(buffer.size.toLong(), limit - total).toInt() else buffer.size)
                    if (count < 0) break
                    total += count
                    if (total > MAX_HTTP_BODY_BYTES) throw IOException(BODY_TOO_LARGE)
                    reservation?.reserve(count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return encoded.toString(StandardCharsets.US_ASCII.name())
    }

    private inline fun compressResult(block: () -> ByteArray): String =
        Base64.encodeToString(block(), Base64.NO_WRAP)

    private companion object {
        const val TAG = "LxHost"
        const val MAX_HTTP_BODY_BYTES = 8L * 1024 * 1024
        const val BODY_TOO_LARGE = "HTTP 响应体超过 8 MiB"
    }
}

internal fun effectiveHttpTimeoutMs(requestedMs: Int, remainingMs: Long): Int {
    val configured = requestedMs.coerceIn(100, 60_000)
    if (remainingMs <= 0L) return 0
    return minOf(configured.toLong(), remainingMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun monotonicDeadlineAfter(timeoutMs: Long): Long {
    val now = System.nanoTime()
    val duration = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
    return if (duration > 0L && now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
}

internal fun decodeBase64Limited(dataBase64: String, maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val maxEncodedChars = ((maxBytes.toLong() + 2) / 3 * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    require(dataBase64.length <= maxEncodedChars) { "base64 input exceeds $maxBytes bytes" }
    return Base64.decode(dataBase64, Base64.DEFAULT).also { decoded ->
        require(decoded.size <= maxBytes) { "base64 input exceeds $maxBytes bytes" }
    }
}

internal fun inflateZlib(input: ByteArray, maxOutputBytes: Int = MAX_ZLIB_OUTPUT_BYTES): ByteArray {
    require(maxOutputBytes >= 0) { "maxOutputBytes must not be negative" }
    val inflater = Inflater()
    return try {
        inflater.setInput(input)
        val initialCapacity = minOf(input.size.toLong() * 2, maxOutputBytes.toLong()).toInt()
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val written = inflater.inflate(buffer)
            if (written > 0) {
                writeZlibChunk(output, buffer, written, maxOutputBytes)
                continue
            }
            val reason = when {
                inflater.needsDictionary() -> "zlib dictionary required"
                inflater.needsInput() -> "truncated zlib stream"
                else -> "zlib inflate made no progress"
            }
            throw DataFormatException(reason)
        }
        output.toByteArray()
    } finally {
        inflater.end()
    }
}

internal fun deflateZlib(input: ByteArray, maxOutputBytes: Int = MAX_ZLIB_OUTPUT_BYTES): ByteArray {
    require(maxOutputBytes >= 0) { "maxOutputBytes must not be negative" }
    val deflater = Deflater()
    return try {
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream(minOf(input.size, maxOutputBytes))
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            if (written == 0) throw DataFormatException("zlib deflate made no progress")
            writeZlibChunk(output, buffer, written, maxOutputBytes)
        }
        output.toByteArray()
    } finally {
        deflater.end()
    }
}

private fun writeZlibChunk(
    output: ByteArrayOutputStream,
    buffer: ByteArray,
    written: Int,
    maxOutputBytes: Int,
) {
    if (written > maxOutputBytes - output.size()) {
        throw DataFormatException("zlib output exceeds $maxOutputBytes bytes")
    }
    output.write(buffer, 0, written)
}
