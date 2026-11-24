package com.leyu.melora.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.EOFException
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {
    @Test
    fun configurationUsesFolderAsOneEncodedPathSegment() {
        val config = WebDavConfig(" https://dav.example.test:443/base ", " 用户 ", "secret", "乐屿 +%").validated()
        assertEquals("https://dav.example.test/base/", config.server)
        assertEquals("/base/%E4%B9%90%E5%B1%BF%20+%25/", config.folderUrl().encodedPath)
        assertEquals(config.scopeKey, config.copy(password = "another password").scopeKey)
        assertFalse(config.scopeKey == config.copy(folder = "Other").scopeKey)
        assertFalse(config.toString().contains("secret"))
        assertEquals("Melora", config.copy(folder = " ").validated().folder)
        listOf("../elsewhere", "nested/dir", "..", "nested\\dir").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { config.copy(folder = name).validated() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.copy(server = "https://old:secret@dav.example.test/base").validated()
        }
    }

    @Test
    fun probeCreatesFolderTestsBothConditionsAndRemovesItsFile() = runBlocking {
        DavFixture().use { dav ->
            assertTrue(WebDavBackup.test(dav.config).canSync)
            assertTrue(WebDavClient(dav.config).listNames().isEmpty())
            assertEquals(
                listOf("PROPFIND", "MKCOL", "PROPFIND", "PUT", "GET", "PROPFIND", "PUT", "PUT", "PUT", "GET", "DELETE", "PROPFIND"),
                dav.methods,
            )
        }
    }

    @Test
    fun folderCreationRejectedWhenDirectoryIsStillMissing() {
        DavFixture(mkcolStatus = 405, folderExistsAfterMkcol = false).use { dav ->
            val error = assertThrows(WebDavException::class.java) {
                runBlocking { WebDavClient(dav.config).ensureFolder() }
            }
            assertEquals(405, error.status)
            assertTrue(error.message.orEmpty().contains("创建"))
            assertEquals(listOf("PROPFIND", "MKCOL", "PROPFIND"), dav.methods)
        }
    }

    @Test
    fun folderCreationConflictSucceedsWhenDirectoryNowExists() = runBlocking {
        DavFixture(mkcolStatus = 405).use { dav ->
            WebDavClient(dav.config).ensureFolder()
            assertEquals(listOf("PROPFIND", "MKCOL", "PROPFIND"), dav.methods)
        }
    }

    @Test
    fun incompleteTlsHandshakeHasItsOwnDiagnostic() {
        val error = SSLHandshakeException("TLS handshake failed").apply { initCause(EOFException()) }
        val message = webDavTlsError(error).message.orEmpty()
        assertTrue(message.contains("握手"))
        assertFalse(message.contains("证书"))
    }

    @Test
    fun certificateFailuresReportCertificateValidation() {
        val handshake = SSLHandshakeException("TLS handshake failed").apply { initCause(CertificateException()) }
        for (error in listOf(handshake, SSLPeerUnverifiedException("Peer name did not match"))) {
            assertTrue(webDavTlsError(error).message.orEmpty().contains("证书"))
        }
    }

    @Test
    fun missingStrongTagOrIgnoredPreconditionsAllowBackupOnly() = runBlocking {
        DavFixture(weakEtags = true).use { dav ->
            assertFalse(WebDavBackup.test(dav.config).canSync)
            assertTrue(WebDavClient(dav.config).listNames().isEmpty())
        }
        DavFixture(ignoreIfMatch = true).use { dav ->
            assertFalse(WebDavBackup.test(dav.config).canSync)
            assertTrue(WebDavClient(dav.config).listNames().isEmpty())
        }
    }

    @Test
    fun concurrentWriterCannotOverwriteRemoteRevisionWithStaleTag() = runBlocking {
        DavFixture().use { dav ->
            val phone = WebDavClient(dav.config)
            val tablet = WebDavClient(dav.config)
            phone.ensureFolder()
            val name = "records.json"
            assertNull(phone.read(name, 128))
            assertTrue(phone.put(name, "first", expectedEtag = null))
            val phoneTag = checkNotNull(phone.read(name, 128)?.etag)
            val tabletTag = checkNotNull(tablet.read(name, 128)?.etag)
            assertEquals(phoneTag, tabletTag)
            assertTrue(tablet.put(name, "new from tablet", tabletTag))
            assertFalse(phone.put(name, "stale from phone", phoneTag))
            assertFalse(phone.put(name, "cannot create again", expectedEtag = null))
            assertEquals("new from tablet", phone.read(name, 128)?.text)
        }
    }

    @Test
    fun backupListUsesSameOriginChildrenAndRecognizedNamesOnly() = runBlocking {
        DavFixture().use { dav ->
            val client = WebDavClient(dav.config)
            client.ensureFolder()
            val first = "melora-backup-1680049900000-0123abcd-1c2bed1a-0ef5-41af-b62e-ec6cd66f1409.json"
            val latest = "melora-backup-1680049900001-bcde1234-ccdf9dbf-2c5a-4f4c-84eb-3ff7a687df50.json"
            val unrelated = "melora-backup-1680049900002-feed1234-383bfbcb-0953-4851-ab08-3847f0b99823.json"
            client.put(first, "old", expectedEtag = null)
            client.put(latest, "new", expectedEtag = null)
            client.put("records.json", "{}", expectedEtag = null)
            dav.additionalResponses = davResource("https://foreign.example.test/dav/Melora/$unrelated") +
                davResource("/dav/Melora-other/$unrelated") +
                davResource("/dav/Melora/nested/$unrelated") +
                davResource("/dav/Melora/$unrelated", collection = true) +
                davResource("/dav/Melora/$unrelated", status = 404)
            val entries = WebDavBackup.list(dav.config)
            assertEquals(listOf(latest, first), entries.map { it.name })
            assertEquals(listOf("bcde1234", "0123abcd"), entries.map { it.device })
        }
    }

    @Test
    fun backupPreviewReadsOnlyTheRequestedPrefixEvenWhenRangeIsIgnored() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val ranges = CopyOnWriteArrayList<String>()
        server.createContext("/") { exchange -> exchange.use {
            ranges += it.requestHeaders.getFirst("Range").orEmpty()
            when (it.requestURI.path.substringAfterLast('/')) {
                "partial" -> {
                    it.responseHeaders.add("Content-Range", "bytes 0-3/10")
                    it.reply(206, "0123")
                }
                "full" -> it.reply(200, "0123456789", chunked = true)
                "wrong-offset" -> {
                    it.responseHeaders.add("Content-Range", "bytes 4-7/10")
                    it.reply(206, "4567")
                }
                else -> it.reply(404)
            }
        } }
        server.start()
        try {
            val client = WebDavClient(WebDavConfig("http://127.0.0.1:${server.address.port}"))
            assertEquals("0123", client.readPrefix("partial", 4))
            assertEquals("0123", client.readPrefix("full", 4))
            assertNull(client.readPrefix("wrong-offset", 4))
            assertNull(client.readPrefix("missing", 4))
            assertEquals(List(4) { "bytes=0-3" }, ranges)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun responseBodyLimitAppliesToChunkedReads() {
        DavFixture(chunkedReads = true).use { dav ->
            val error = assertThrows(WebDavException::class.java) {
                runBlocking {
                    val client = WebDavClient(dav.config)
                    client.ensureFolder()
                    client.put("records.json", "x".repeat(129), expectedEtag = null)
                    client.read("records.json", 128)
                }
            }
            assertTrue(error.message.orEmpty().contains("大小限制"))
        }
    }

    @Test
    fun redirectIsReportedWithoutFollowingTheTarget() {
        DavFixture(redirectReads = true).use { dav ->
            val error = assertThrows(WebDavException::class.java) {
                runBlocking { WebDavClient(dav.config).read("records.json", 128) }
            }
            assertEquals(307, error.status)
            assertEquals(listOf("GET"), dav.methods)
        }
    }

    private class DavFixture(
        private val weakEtags: Boolean = false,
        private val ignoreIfMatch: Boolean = false,
        private val chunkedReads: Boolean = false,
        private val redirectReads: Boolean = false,
        private val mkcolStatus: Int = 201,
        private val folderExistsAfterMkcol: Boolean = true,
    ) : AutoCloseable {
        private data class File(val text: String, val etag: String)

        private val files = mutableMapOf<String, File>()
        private var revision = 0
        private var folderExists = false
        val methods = CopyOnWriteArrayList<String>()
        @Volatile var additionalResponses = ""
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> exchange.use(::respond) }
            start()
        }
        val config = WebDavConfig("http://127.0.0.1:${server.address.port}/dav", "test", "pass")

        private fun respond(exchange: HttpExchange) {
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            methods += method
            if (!path.startsWith("/dav/Melora/") ||
                exchange.requestHeaders.getFirst("Authorization") != Credentials.basic("test", "pass")) {
                exchange.reply(401)
                return
            }
            val name = path.removePrefix("/dav/Melora/")
            when (method) {
                "PROPFIND" -> {
                    if (!folderExists) {
                        exchange.reply(404)
                        return
                    }
                    val children = if (exchange.requestHeaders.getFirst("Depth") == "1") {
                        files.keys.joinToString("") { davResource("/dav/Melora/$it") } + additionalResponses
                    } else ""
                    exchange.reply(
                        207,
                        """<d:multistatus xmlns:d="DAV:">${davResource("/dav/Melora/", collection = true)}$children</d:multistatus>""",
                    )
                }
                "MKCOL" -> {
                    folderExists = folderExistsAfterMkcol
                    exchange.reply(mkcolStatus)
                }
                "GET" -> {
                    if (redirectReads) {
                        exchange.responseHeaders.add("Location", "/dav/Melora/other")
                        exchange.reply(307)
                        return
                    }
                    val file = files[name]
                    if (file == null) exchange.reply(404) else {
                        exchange.responseHeaders.add("ETag", if (weakEtags) "W/${file.etag}" else file.etag)
                        exchange.reply(200, file.text, chunkedReads)
                    }
                }
                "PUT" -> {
                    val previous = files[name]
                    val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                    val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                    when {
                        !folderExists -> exchange.reply(409)
                        ifNoneMatch == "*" && previous != null -> exchange.reply(412)
                        ifMatch != null && !ignoreIfMatch && ifMatch != previous?.etag -> exchange.reply(412)
                        else -> {
                            val text = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                            files[name] = File(text, "\"r${++revision}\"")
                            exchange.reply(if (previous == null) 201 else 204)
                        }
                    }
                }
                "DELETE" -> exchange.reply(if (files.remove(name) == null) 404 else 204)
                else -> exchange.reply(405)
            }
        }

        override fun close() = server.stop(0)
    }
}

private fun HttpExchange.reply(code: Int, text: String? = null, chunked: Boolean = false) {
    val bytes = text?.toByteArray(Charsets.UTF_8)
    sendResponseHeaders(code, if (chunked) 0L else bytes?.size?.toLong() ?: -1L)
    if (bytes != null) responseBody.use { it.write(bytes) }
}

private fun davResource(href: String, collection: Boolean = false, status: Int = 200): String {
    val resourceType = if (collection) "<d:collection/>" else ""
    return """
        <d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>$resourceType</d:resourcetype></d:prop>
        <d:status>HTTP/1.1 $status Test</d:status></d:propstat></d:response>
    """.trimIndent()
}
