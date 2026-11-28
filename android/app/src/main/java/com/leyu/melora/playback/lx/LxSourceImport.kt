package com.leyu.melora.playback.lx

import com.leyu.melora.playback.BACKUP_ORIGIN_KEY
import com.leyu.melora.playback.readResponse
import com.leyu.melora.playback.sha256Hex
import com.leyu.melora.playback.sharedNetworkClient
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 新导入的单个 LX 脚本上限；备份恢复仍使用历史 4 MiB 限制。 */
internal const val MAX_IMPORTED_SCRIPT_BYTES = 512 * 1024

/** 本地 JSON 合集允许由多个 512 KiB 脚本组成，但仍必须有界读取。 */
internal const val MAX_LOCAL_SOURCE_DOCUMENT_BYTES = 4 * 1024 * 1024

internal const val MAX_IMPORTED_SOURCE_COUNT = 128

internal const val MAX_SOURCE_REDIRECTS = 5
private const val SOURCE_EXPORT_FOOTER = "// melora:lx-source "
private const val MAX_SOURCE_EXPORT_METADATA_BYTES = 16 * 1024
private const val MAX_DOWNLOADED_SOURCE_DOCUMENT_BYTES = MAX_IMPORTED_SCRIPT_BYTES + MAX_SOURCE_EXPORT_METADATA_BYTES

internal data class LxSourcePayload(
    val fileName: String,
    val code: String,
    val originUrl: String? = null,
)

/** 附注放在结尾，保留脚本原有头部及其他 LX 客户端可执行性。 */
internal fun LxSourcePayload.scriptExport(): String {
    val origin = originUrl ?: return code
    val footer = "\n$SOURCE_EXPORT_FOOTER" + JSONObject().put("name", fileName).put("originUrl", origin)
    require(footer.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_EXPORT_METADATA_BYTES) { "音源来源链接过长" }
    return code + footer
}

internal fun sourceBundleExport(sources: List<LxSourcePayload>): String {
    val entries = JSONArray()
    sources.forEach { script ->
        entries.put(JSONObject().put("name", script.fileName).put("code", script.code).put("originUrl", script.originUrl))
    }
    val document = JSONObject().put("app", "melora").put("type", "lx-sources").put("scripts", entries).toString(2)
    requireLxSourceContentLength(document.toByteArray(Charsets.UTF_8).size.toLong(), MAX_LOCAL_SOURCE_DOCUMENT_BYTES)
    return document
}

internal fun validateSourceBundleCount(count: Int) {
    require(count in 1..MAX_IMPORTED_SOURCE_COUNT) { "音源合集须包含 1 至 $MAX_IMPORTED_SOURCE_COUNT 个脚本" }
}

internal class LxSourceUpdate(
    val scriptId: String,
    val currentVersion: String,
    val remoteVersion: String,
    internal val originUrl: String,
    internal val expectedRevision: Long,
    internal val code: String,
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
    suspend fun download(rawUrl: String): LxSourcePayload {
        val sourceUrl = LxSourceUrlPolicy.validate(rawUrl)
        var current = sourceUrl
        var redirects = 0

        while (true) {
            val request = Request.Builder()
                .url(current)
                .header("Accept", "application/javascript, text/javascript, application/json, text/plain;q=0.9, */*;q=0.1")
                .header("User-Agent", "Melora-Android")
                .build()
            val code = client.newCall(request).readResponse { response ->
                if (response.code in 300..399) {
                    require(redirects < MAX_SOURCE_REDIRECTS) { "音源链接重定向次数过多" }
                    val location = response.header("Location")?.takeIf { it.isNotBlank() }
                        ?: error("音源重定向缺少目标地址")
                    current = LxSourceUrlPolicy.resolveRedirect(current, location)
                    redirects++
                    return@readResponse null
                }

                require(response.isSuccessful) { "读取音源脚本失败（HTTP ${response.code}）" }
                rejectHtmlContentType(response.header("Content-Type"))
                val body = response.body
                requireLxSourceContentLength(body.contentLength(), MAX_DOWNLOADED_SOURCE_DOCUMENT_BYTES)
                body.byteStream().use { input ->
                    input.readBoundedLxSourceText(MAX_DOWNLOADED_SOURCE_DOCUMENT_BYTES)
                }
            } ?: continue
            return LxSourcePayload(
                fileName = onlineSourceFileName(sourceUrl),
                code = code,
                originUrl = sourceUrl.toString(),
            )
        }
    }

    private companion object {
        fun defaultLxSourceHttpClient(): OkHttpClient = sharedNetworkClient.newBuilder()
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

    suspend fun checkUpdate(script: LxScript): LxSourceUpdate? = withContext(Dispatchers.IO) {
        val originUrl = script.originUrl ?: error("此音源不是通过链接导入的")
        val (currentCode, revision) = store.content(script.id) ?: error("本地音源已删除")
        store.requireUpdateBase(script.id, originUrl, revision)
        val payload = downloader.download(originUrl)
        val entries = parseLxSourceDocument(payload.code, script.id, MAX_IMPORTED_SCRIPT_BYTES, originUrl)
        val remoteCode = entries.firstOrNull { it.fileName == script.id }?.code
            ?: error("远端音源包中未找到「${script.name}」")
        currentCoroutineContext().ensureActive()
        store.requireUpdateBase(script.id, originUrl, revision)
        if (currentCode == remoteCode) return@withContext null
        LxSourceUpdate(
            scriptId = script.id,
            currentVersion = parseLxScriptMetadata(currentCode)["version"].orEmpty(),
            remoteVersion = parseLxScriptMetadata(remoteCode)["version"].orEmpty(),
            originUrl = originUrl,
            expectedRevision = revision,
            code = remoteCode,
        )
    }

    suspend fun applyUpdate(update: LxSourceUpdate): LxScript = withContext(Dispatchers.IO) {
        store.import(update.scriptId, update.code, update.originUrl, expectedRevision = update.expectedRevision)
    }

    private suspend fun importDocument(
        text: String,
        fallbackName: String?,
        maxDocumentBytes: Int,
        originUrl: String?,
    ): List<LxScript> {
        val entries = parseLxSourceDocument(text, fallbackName, maxDocumentBytes, originUrl)
        // 整包轻量校验通过才开始写盘，避免无效条目导致半导入状态。
        currentCoroutineContext().ensureActive()
        return entries.map { store.import(it.fileName, it.code, it.originUrl) }
    }
}

/** 先完整校验合集再写盘，杜绝后续条目失败时留下半导入状态。 */
internal fun parseLxSourceDocument(
    text: String,
    fallbackName: String?,
    maxDocumentBytes: Int,
    downloadedFrom: String? = null,
): List<LxSourcePayload> {
    val footerEnd = text.indexOfLast { it != '\n' && it != '\r' } + 1
    val footerStart = text.lastIndexOf('\n', footerEnd - 1)
    if (footerStart >= 0 && text.startsWith(SOURCE_EXPORT_FOOTER, footerStart + 1)) {
        val limit = if (downloadedFrom == null) maxDocumentBytes else MAX_DOWNLOADED_SOURCE_DOCUMENT_BYTES
        validateLxSourceDocument(text, limit)
        val metadata = text.substring(footerStart, footerEnd)
        require(metadata.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_EXPORT_METADATA_BYTES) { "音源来源链接过长" }
        val trailer = JSONObject(metadata.removePrefix("\n$SOURCE_EXPORT_FOOTER"))
        val code = validateImportedScriptCode(text.substring(0, footerStart))
        val name = if (downloadedFrom != null) fallbackName else {
            requireNotNull((trailer.opt("name") as? String)?.takeIf(String::isNotBlank)) { "音源导出文件缺少脚本名称" }
        }
        val origin = downloadedFrom ?: requireNotNull(trailer.sourceOriginUrl()) { "音源导出文件缺少来源链接" }
        return listOf(LxSourcePayload(backupScriptId(name ?: "imported.js"), code, origin))
    }
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
        validateSourceBundleCount(array.length())
        val isMeloraBundle = json.optString("app") == "melora" && json.optString("type") == "lx-sources"
        val isAppBackup = downloadedFrom == null && json.optInt("version") == 1 &&
            (json.has(BACKUP_ORIGIN_KEY) || json.has("settings") || json.has("library"))
        val ids = hashSetOf<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val node = requireNotNull(array.optJSONObject(index)) { "音源合集第 ${index + 1} 项格式无效" }
                val code = requireNotNull(node.opt("code") as? String) { "音源合集第 ${index + 1} 项缺少脚本内容" }
                val nameKey = if (isAppBackup && node.has("fileName")) "fileName" else "name"
                require(!node.has(nameKey) || node.opt(nameKey) is String) { "音源合集第 ${index + 1} 项名称格式无效" }
                val name = node.optString(nameKey)
                val id = backupScriptId(name.ifBlank { "source-$index.js" })
                validateImportedScriptCode(code)
                require(ids.add(id)) { "音源合集包含重名脚本: $id" }
                val origin = downloadedFrom ?: if (isMeloraBundle || isAppBackup) node.sourceOriginUrl() else null
                add(LxSourcePayload(id, code, origin))
            }
        }
    }
    validateImportedScriptCode(text)
    return listOf(LxSourcePayload(backupScriptId(fallbackName ?: "imported.js"), text, downloadedFrom))
}

private fun JSONObject.sourceOriginUrl(): String? = when (val origin = opt("originUrl")) {
    null, JSONObject.NULL -> null
    is String -> LxSourceUrlPolicy.validate(origin).toString()
    else -> error("音源来源链接格式无效")
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
    val fingerprint = sourceUrl.toString().sha256Hex(byteCount = 5)
    return "$stem-$fingerprint.js"
}

private fun looksLikeHtml(text: String): Boolean = Regex(
    "(?is)^\\s*<(?:!doctype\\s+html\\b|html\\b|head\\b|body\\b|meta\\b|title\\b|script\\b)",
).containsMatchIn(text)
