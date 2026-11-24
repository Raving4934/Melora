package com.leyu.melora.playback

import java.io.IOException
import java.io.StringReader
import java.net.SocketTimeoutException
import java.nio.charset.CharacterCodingException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.CacheControl
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal class WebDavException(message: String, val status: Int? = null) : IOException(message)

internal class WebDavClient(config: WebDavConfig) {
    private val configuration = config.validated()
    private val folder = configuration.folderUrl()
    private val folderSegments = folder.pathSegments.dropLast(1)
    private val authorization = configuration.account.takeIf(String::isNotEmpty)?.let {
        Credentials.basic(it, configuration.password, Charsets.UTF_8)
    }

    suspend fun ensureFolder() {
        val resources = properties(depth = 0) ?: run {
            val createStatus = request("MKCOL", folder).execute { response ->
                // 另一台设备可能刚创建了同一个目录，随后仍须确认目录存在。
                if (response.code != 201 && response.code != 405) response.fail()
                response.code
            }
            properties(depth = 0) ?: throw if (createStatus == 405) {
                WebDavException("服务器拒绝创建子目录（HTTP 405），复查仍为 404。请选择可写的父目录或在服务器上创建该子目录。", 405)
            } else {
                WebDavException("服务器已返回创建成功（HTTP 201），但查询子目录仍返回 404。请核对 WebDAV 路径。", 404)
            }
        }
        checkFolder(resources)
    }

    suspend fun listNames(): List<String> {
        val resources = properties(depth = 1)
            ?: throw WebDavException("WebDAV 目标目录已不存在", 404)
        checkFolder(resources)
        return resources.asSequence()
            .filter { !it.isFolder && it.segments.size == folderSegments.size + 1 && it.segments.dropLast(1) == folderSegments }
            .map { it.segments.last() }
            .distinct()
            .toList()
    }

    suspend fun read(name: String, maxBytes: Int): String? {
        require(maxBytes in 1..MAX_BACKUP_BYTES)
        return request("GET", fileUrl(name)).execute { response ->
            when (response.code) {
                404 -> null
                200 -> response.readText(maxBytes)
                else -> response.fail()
            }
        }
    }

    suspend fun readPrefix(name: String, maxBytes: Int): String? {
        require(maxBytes in 1..MAX_BACKUP_BYTES)
        return request("GET", fileUrl(name)).header("Range", "bytes=0-${maxBytes - 1}").execute { response ->
            when (response.code) {
                404, 416 -> return@execute null
                206 -> if (response.header("Content-Range")?.startsWith("bytes 0-", ignoreCase = true) != true) return@execute null
                200 -> Unit // 部分服务忽略 Range，仍只取开头。
                else -> response.fail()
            }
            response.peekBody(maxBytes.toLong()).bytes().toString(Charsets.UTF_8)
        }
    }

    /** 文件名由调用方分配。重试应核对已有内容并使用相同的文件名。 */
    suspend fun writeFile(name: String, text: String) {
        require(text.length <= MAX_BACKUP_BYTES) { "WebDAV 文件超过大小限制" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "WebDAV 文件超过大小限制" }
        request("PUT", fileUrl(name), bytes.toRequestBody(jsonType)).execute { response ->
            if (response.code != 200 && response.code != 201 && response.code != 204) response.fail()
        }
    }

    suspend fun deleteFile(name: String) {
        request("DELETE", fileUrl(name)).execute { response ->
            if (response.code != 200 && response.code != 204 && response.code != 404) response.fail()
        }
    }

    private fun checkFolder(resources: List<WebDavResource>) {
        if (resources.none { it.isFolder && it.segments == folderSegments }) {
            throw WebDavException("服务器已响应目录查询（HTTP 207），但未返回与目标路径匹配的目录信息。")
        }
    }

    private suspend fun properties(depth: Int): List<WebDavResource>? = request(
        "PROPFIND", folder, propertiesRequest,
    ).header("Depth", depth.toString()).execute { response ->
        when (response.code) {
            404 -> null
            207 -> parseProperties(response.readText(MAX_LIST_BYTES), folder)
            else -> response.fail()
        }
    }

    private fun fileUrl(name: String): HttpUrl {
        require(name.isNotBlank() && name.length <= 200 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "WebDAV 文件名无效" }
        return folder.newBuilder().addPathSegment(name).build()
    }

    private fun request(method: String, url: HttpUrl, body: RequestBody? = null): Request.Builder =
        Request.Builder().url(url).method(method, body).cacheControl(CacheControl.FORCE_NETWORK).apply {
            header("Accept-Encoding", "identity")
            authorization?.let { header("Authorization", it) }
        }

    private suspend fun <T> Request.Builder.execute(read: (Response) -> T): T {
        val request = build()
        return try {
            httpClient.newCall(request).readResponse(read)
        } catch (error: WebDavException) {
            throw error
        } catch (error: SSLException) {
            throw webDavTlsError(error)
        } catch (_: SocketTimeoutException) {
            throw WebDavException("WebDAV ${request.method} 请求超时，请检查网络和服务器地址")
        } catch (_: IOException) {
            throw WebDavException("WebDAV ${request.method} 请求中断，请检查网络和服务器地址")
        }
    }

    private companion object {
        const val MAX_LIST_BYTES = 4 * 1024 * 1024

        val httpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonType = "application/json; charset=utf-8".toMediaType()
        val propertiesRequest =
            """<propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
    }
}

internal fun webDavTlsError(error: SSLException): WebDavException {
    val certificateFailure = generateSequence<Throwable>(error) { it.cause }.any {
        it is SSLPeerUnverifiedException || it is CertificateException || it is CertPathValidatorException
    }
    val message = if (certificateFailure) "WebDAV 证书验证失败，请检查域名、证书和手机时间"
        else "WebDAV HTTPS 握手或连接中断，请检查网络、代理和服务器的 HTTPS 端口"
    return WebDavException(message).also { it.initCause(error) }
}

private fun Response.fail(): Nothing {
    val detail = when (code) {
        301, 302, 303, 307, 308 -> "地址发生重定向，请填写最终的服务器地址"
        401 -> "登录失败，请检查账号和密码"
        403 -> "账号没有此操作的权限"
        404 -> "文件或目录不存在"
        405, 501 -> "服务器不支持此操作"
        409 -> "父目录不存在，请检查服务器地址和子文件夹"
        423 -> "文件已锁定，请稍后重试"
        507 -> "服务器存储空间不足"
        else -> "请求失败"
    }
    throw WebDavException("WebDAV ${request.method}（HTTP $code）· $detail", code)
}

private fun Response.readText(limit: Int): String {
    if (body.contentLength() > limit) throw WebDavException("WebDAV 文件超过大小限制")
    return try {
        body.byteStream().use { it.readBackupText(limit) }
    } catch (_: CharacterCodingException) {
        throw WebDavException("WebDAV 文件须采用 UTF-8 编码")
    } catch (_: IllegalArgumentException) {
        throw WebDavException("WebDAV 文件超过大小限制")
    }
}

private data class WebDavResource(val segments: List<String>, val isFolder: Boolean)

private fun parseProperties(text: String, folder: HttpUrl): List<WebDavResource> {
    // DAV 目录列表不需要 DTD。限制外部实体，避免 XML 解析引入额外的文件或网络读取。
    require("<!DOCTYPE" !in text) { "WebDAV 目录列表包含无效的 XML 声明" }
    val root = try {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("外部实体") }
        builder.setErrorHandler(DefaultHandler())
        builder.parse(InputSource(StringReader(text.removePrefix("\uFEFF")))).documentElement
    } catch (_: Exception) {
        throw WebDavException("无法读取 WebDAV 目录列表")
    }
    require(root.namespaceURI == "DAV:" && root.localName == "multistatus") { "服务器返回的 WebDAV 目录列表无效" }
    return root.davElements("response").mapNotNull { response ->
        val href = response.davElements("href").singleOrNull()?.textContent?.trim() ?: return@mapNotNull null
        val address = folder.resolve(href)?.takeIf {
            it.scheme == folder.scheme && it.host == folder.host && it.port == folder.port &&
                it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        } ?: return@mapNotNull null
        val resourceType = response.davElements("propstat").asSequence()
            .filter { it.davElements("status").singleOrNull()?.textContent?.let(::successfulPropStatus) == true }
            .flatMap { it.davElements("prop") }
            .flatMap { it.davElements("resourcetype") }
            .firstOrNull() ?: return@mapNotNull null
        val isFolder = resourceType.davElements("collection").any()
        val segments = if (isFolder) address.pathSegments.dropLastWhile(String::isEmpty) else address.pathSegments
        WebDavResource(segments, isFolder)
    }.toList()
}

private fun Element.davElements(name: String): Sequence<Element> = generateSequence(firstChild) { it.nextSibling }
    .filterIsInstance<Element>()
    .filter { it.namespaceURI == "DAV:" && it.localName == name }

private fun successfulPropStatus(status: String): Boolean =
    status.trim().split(' ', limit = 3).getOrNull(1)?.toIntOrNull()?.let { it in 200..299 } == true
