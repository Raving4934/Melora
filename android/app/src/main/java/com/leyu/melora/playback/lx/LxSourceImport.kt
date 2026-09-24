package com.leyu.melora.playback.lx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 新导入的单个 LX 脚本上限；备份恢复仍使用历史 4 MiB 限制。 */
internal const val MAX_IMPORTED_SCRIPT_BYTES = 512 * 1024

/** 本地 JSON 合集允许由多个 512 KiB 脚本组成，但仍必须有界读取。 */
internal const val MAX_LOCAL_SOURCE_DOCUMENT_BYTES = 4 * 1024 * 1024

internal const val MAX_IMPORTED_SOURCE_COUNT = 128

internal const val MAX_SOURCE_REDIRECTS = 5

internal data class LxSourcePayload(
    val fileName: String,
    val code: String,
    val originUrl: String,
)

internal data class LxSourceUpdate(
    val script: LxScript,
    val updated: Boolean,
)

/**
 * 在线 URL 只允许 HTTP/HTTPS、无凭据、无 fragment。
 * 重定向不会交给 OkHttp 自动跟随；HTTPS 起点禁止降级到明文 HTTP。
 */
internal object LxSourceUrlPolicy {
    fun validate(rawUrl: String): HttpUrl {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "请输入音源脚本链接" }
        val url = trimmed.toHttpUrlOrNull() ?: error("音源脚本链接格式无效")
        require(url.scheme == "http" || url.scheme == "https") { "在线音源只支持 HTTP/HTTPS 链接" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "音源链接不能包含用户名或密码" }
        require(url.fragment == null) { "音源链接不能包含 fragment" }
        return url
    }

    fun resolveRedirect(current: HttpUrl, location: String): HttpUrl {
        val next = current.resolve(location.trim()) ?: error("音源重定向地址无效")
        val validated = validate(next.toString())
        require(!current.isHttps || validated.isHttps) { "HTTPS 音源链接不能重定向到 HTTP" }
        return validated
    }
}

internal class LxSourceDownloader(
    private val client: OkHttpClient = defaultLxSourceHttpClient(),
) {
    fun download(rawUrl: String): LxSourcePayload {
        val sourceUrl = LxSourceUrlPolicy.validate(rawUrl)
        var current = sourceUrl
        var redirects = 0

        while (true) {
            val request = Request.Builder()
                .url(current)
                .header("Accept", "application/javascript, text/javascript, application/json, text/plain;q=0.9, */*;q=0.1")
                .header("User-Agent", "Melora-Android")
                .build()
            val response = client.newCall(request).execute()
            try {
                if (response.code in 300..399) {
                    require(redirects < MAX_SOURCE_REDIRECTS) { "音源链接重定向次数过多" }
                    val location = response.header("Location")?.takeIf { it.isNotBlank() }
                        ?: error("音源重定向缺少目标地址")
                    current = LxSourceUrlPolicy.resolveRedirect(current, location)
                    redirects++
                    continue
                }

                require(response.isSuccessful) { "读取音源脚本失败（HTTP ${response.code}）" }
                rejectHtmlContentType(response.header("Content-Type"))
                val body = response.body
                requireLxSourceContentLength(body.contentLength(), MAX_IMPORTED_SCRIPT_BYTES)
                val code = body.byteStream().use { input ->
                    input.readBoundedLxSourceText(MAX_IMPORTED_SCRIPT_BYTES)
                }
                validateImportedScriptCode(code)
                return LxSourcePayload(
                    fileName = onlineSourceFileName(sourceUrl),
                    code = code,
                    originUrl = sourceUrl.toString(),
                )
            } finally {
                response.close()
            }
        }
    }

    private companion object {
        fun defaultLxSourceHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Android 的唯一脚本导入入口：本地文件和在线 URL 最终都进入同一个文本解析、校验和 Store 写入流程。
 */
internal class LxSourceImporter(
    private val store: LxScriptStore,
    private val downloader: LxSourceDownloader = LxSourceDownloader(),
) {
    suspend fun importLocal(input: InputStream, displayName: String?): List<LxScript> = withContext(Dispatchers.IO) {
        val text = input.use { it.readBoundedLxSourceText(MAX_LOCAL_SOURCE_DOCUMENT_BYTES) }
        importDocument(text, displayName, MAX_LOCAL_SOURCE_DOCUMENT_BYTES, originUrl = null)
    }

    suspend fun importUrl(url: String): List<LxScript> = withContext(Dispatchers.IO) {
        val payload = downloader.download(url)
        importDocument(payload.code, payload.fileName, MAX_IMPORTED_SCRIPT_BYTES, payload.originUrl)
    }

    suspend fun updateFromOrigin(script: LxScript): LxSourceUpdate = withContext(Dispatchers.IO) {
        val originUrl = script.originUrl ?: error("此音源不是通过链接导入的")
        val payload = downloader.download(originUrl)
        val entries = parseLxSourceDocument(payload.code, payload.fileName, MAX_IMPORTED_SCRIPT_BYTES)
        val remote = entries.firstOrNull { (name, _) -> backupScriptId(name) == script.id }
            ?: entries.singleOrNull()
            ?: error("远端音源包中未找到「${script.name}」")
        val currentCode = store.code(script.id) ?: error("本地音源脚本不存在")
        if (currentCode == remote.second) return@withContext LxSourceUpdate(script, updated = false)
        currentCoroutineContext().ensureActive()
        LxSourceUpdate(store.import(script.id, remote.second, payload.originUrl), updated = true)
    }

    private suspend fun importDocument(
        text: String,
        fallbackName: String?,
        maxDocumentBytes: Int,
        originUrl: String?,
    ): List<LxScript> {
        val entries = parseLxSourceDocument(text, fallbackName, maxDocumentBytes)
        // 整包轻量校验通过才开始写盘，避免无效条目导致半导入状态。
        currentCoroutineContext().ensureActive()
        return entries.map { (name, code) -> store.import(name, code, originUrl) }
    }
}

/** 先完整校验合集再写盘，杜绝后续条目失败时留下半导入状态。 */
internal fun parseLxSourceDocument(
    text: String,
    fallbackName: String?,
    maxDocumentBytes: Int,
): List<Pair<String, String>> {
    validateLxSourceDocument(text, maxDocumentBytes)
    // 仅把完整JSON当文档；以对象/数组表达式开头的JavaScript不能被截断误判。
    val json = runCatching {
        val reader = JSONTokener(text)
        reader.nextValue().takeIf { value ->
            reader.nextClean() == '\u0000' && (value !is String || text.trimStart().startsWith('"'))
        }
    }.getOrNull()
    if (json != null) {
        require(json is JSONObject && json.opt("scripts") is JSONArray) { "所选JSON不是音源合集" }
        val array = json.getJSONArray("scripts")
        require(array.length() in 1..MAX_IMPORTED_SOURCE_COUNT) {
            "音源合集须包含 1 至 $MAX_IMPORTED_SOURCE_COUNT 个脚本"
        }
        val ids = hashSetOf<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val node = requireNotNull(array.optJSONObject(index)) { "音源合集第 ${index + 1} 项格式无效" }
                val code = requireNotNull(node.opt("code") as? String) { "音源合集第 ${index + 1} 项缺少脚本内容" }
                require(!node.has("name") || node.opt("name") is String) { "音源合集第 ${index + 1} 项名称格式无效" }
                val id = backupScriptId(node.optString("name").ifBlank { "source-$index.js" })
                validateImportedScriptCode(code)
                require(ids.add(id)) { "音源合集包含重名脚本: $id" }
                add(id to code)
            }
        }
    }
    validateImportedScriptCode(text)
    return listOf(backupScriptId(fallbackName ?: "imported.js") to text)
}

internal fun validateImportedScriptCode(code: String): String {
    validateLxSourceDocument(code, MAX_IMPORTED_SCRIPT_BYTES)
    return code
}

internal fun validateLxSourceDocument(text: String, maxBytes: Int): String {
    require(maxBytes > 0) { "音源脚本大小限制无效" }
    require(text.isNotBlank()) { "音源脚本为空" }
    require(!text.any { it == '\u0000' }) { "音源脚本包含无效的 NUL 字符" }
    require(text.toByteArray(Charsets.UTF_8).size <= maxBytes) {
        "音源脚本超过 ${maxBytes / 1024} KiB 限制"
    }
    require(!looksLikeHtml(text)) { "链接返回的是 HTML 页面，请使用 JS 原始文件链接" }
    return text
}

internal fun InputStream.readBoundedLxSourceText(limit: Int): String {
    val bytes = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val remaining = limit - bytes.size()
        require(remaining >= 0) { "音源脚本超过 ${limit / 1024} KiB 限制" }
        val count = read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count < 0) break
        if (count == 0) continue
        require(count <= remaining) { "音源脚本超过 ${limit / 1024} KiB 限制" }
        bytes.write(buffer, 0, count)
    }
    return try {
        decodeLxSourceUtf8(bytes.toByteArray())
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("文件不是 UTF-8 文本，请选择 JS 音源脚本或 JSON 音源合集", failure)
    }
}

internal fun decodeLxSourceUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()
    .removePrefix("\uFEFF")
    .also { text ->
        require(!text.any { it == '\u0000' }) { "音源脚本包含无效的 NUL 字符" }
        require(!looksLikeHtml(text)) { "链接返回的是 HTML 页面，请使用 JS 原始文件链接" }
    }

internal fun requireLxSourceContentLength(length: Long, limit: Int) {
    require(length < 0L || length <= limit.toLong()) {
        "音源脚本超过 ${limit / 1024} KiB 限制"
    }
}

internal fun rejectHtmlContentType(contentType: String?) {
    val mediaType = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    require(mediaType != "text/html" && mediaType != "application/xhtml+xml") {
        "链接返回的是 HTML 页面，请使用 JS 原始文件链接"
    }
}

internal fun onlineSourceFileName(sourceUrl: HttpUrl): String {
    val candidate = sourceUrl.pathSegments.lastOrNull { it.isNotBlank() } ?: "online-source.js"
    val stem = backupScriptId(candidate)
        .removeSuffix(".js")
        .take(44)
        .ifBlank { "online-source" }
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(sourceUrl.toString().toByteArray(Charsets.UTF_8))
        .take(5)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$stem-$fingerprint.js"
}

private fun looksLikeHtml(text: String): Boolean = Regex(
    "(?is)^\\s*<(?:!doctype\\s+html\\b|html\\b|head\\b|body\\b|meta\\b|title\\b|script\\b)",
).containsMatchIn(text)
