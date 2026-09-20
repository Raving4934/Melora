package com.leyu.melora.playback.lx

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64 as JavaBase64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val MAX_ZLIB_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_ZLIB_OUTPUT_BYTES = 16 * 1024 * 1024

internal class LxRequestToken {
    internal val cancelled = AtomicBoolean(false)
    internal val currentCall = AtomicReference<Call?>(null)
}

private data class LxRequestContext(
    val token: LxRequestToken,
    val deadlineMs: Long,
)

// 提供给音源脚本的同步宿主能力；运行在脚本引擎线程，网络调用串行执行。
class LxHost internal constructor(
    // 部分音源 CDN 对 HTTP/2 支持不稳定（unexpected end of stream），强制 HTTP/1.1 更可靠。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val random = SecureRandom()
    private val requestContext = ThreadLocal<LxRequestContext?>()

    internal fun newRequestToken(): LxRequestToken = LxRequestToken()

    /**
     * 取消指定脚本调用当前正在执行的 OkHttp Call。
     * token 自带调用状态，不能误伤同一引擎外的其它请求。
     */
    internal fun cancelRequest(token: LxRequestToken) {
        token.cancelled.set(true)
        token.currentCall.get()?.cancel()
    }

    /** 让脚本内部每次同步 HTTP 都共享外层解析预算，避免单次网络阻塞穿透播放器超时。 */
    internal fun <T> withinRequestTimeout(
        token: LxRequestToken,
        timeoutMs: Long,
        block: () -> T,
    ): T {
        val previous = requestContext.get()
        requestContext.set(
            LxRequestContext(
                token = token,
                deadlineMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1L),
            ),
        )
        return try {
            block()
        } finally {
            requestContext.set(previous)
        }
    }

    /** 兼容非池化调用；正式池化调用会传入可取消 token。 */
    internal fun <T> withinRequestTimeout(timeoutMs: Long, block: () -> T): T =
        withinRequestTimeout(newRequestToken(), timeoutMs, block)

    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, message)
    }

    @JavascriptInterface
    fun http(payloadJson: String): String = try {
        val context = requestContext.get()
        val token = context?.token
        if (token?.cancelled?.get() == true) throw IOException("脚本请求已取消")
        val payload = JSONObject(payloadJson)
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
        val timeout = effectiveHttpTimeoutMs(
            requestedMs = payload.optInt("timeout", 15000),
            deadlineMs = context?.deadlineMs ?: Long.MAX_VALUE,
            nowMs = System.currentTimeMillis(),
        )
        if (timeout <= 0) throw IOException("http 请求超时")
        if (token?.cancelled?.get() == true) throw IOException("脚本请求已取消")
        val callClient = client.newBuilder().callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS).build()
        val call = callClient.newCall(builder.build())
        if (token != null) {
            token.currentCall.set(call)
            // cancel() 可能正好发生在 newCall 与 execute 之间；二次检查覆盖该窗口。
            if (token.cancelled.get()) {
                token.currentCall.compareAndSet(call, null)
                call.cancel()
                throw IOException("脚本请求已取消")
            }
        }
        try {
            call.execute().use { response ->
                return JSONObject().apply {
                    put("statusCode", response.code)
                    put("statusMessage", response.message)
                    put("finalUrl", response.request.url.toString())
                    put("headers", JSONObject().apply {
                        response.headers.forEach { (name, value) -> put(name, value) }
                    })
                    put("raw", readBodyBase64(response.body, payload.optInt("maxResponseBytes", 0)))
                }.toString()
            }
        } finally {
            token?.currentCall?.compareAndSet(call, null)
        }
    } catch (error: Exception) {
        JSONObject().put("error", error.message ?: "http 请求失败").toString()
    }

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

    private fun readBodyBase64(body: ResponseBody, requestedLimit: Int): String {
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

internal fun effectiveHttpTimeoutMs(requestedMs: Int, deadlineMs: Long, nowMs: Long): Int {
    val configured = requestedMs.coerceIn(100, 60_000)
    if (deadlineMs == Long.MAX_VALUE) return configured
    val remaining = deadlineMs - nowMs
    if (remaining <= 0L) return 0
    return minOf(configured.toLong(), remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
