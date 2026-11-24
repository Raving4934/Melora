package com.leyu.melora.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavClientTest {
    @get:Rule val temporary = TemporaryFolder()

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
    fun probeCreatesFolderVerifiesReadAndListingAndRemovesItsFile() = runBlocking {
        DavFixture().use { dav ->
            assertNull(WebDavBackup.test(dav.config))
            assertTrue(WebDavClient(dav.config).listNames().isEmpty())
            assertEquals(
                listOf("PROPFIND", "MKCOL", "PROPFIND", "PUT", "GET", "PROPFIND", "DELETE", "PROPFIND"),
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
    fun serversWithoutStrongEtagOrConditionalWritesPassProbe() = runBlocking {
        for (etag in listOf(null, "W/\"weak\"")) {
            DavFixture(etag = etag).use { dav ->
                assertNull(WebDavBackup.test(dav.config))
                assertTrue(WebDavClient(dav.config).listNames().isEmpty())
            }
        }
    }

    @Test
    fun probeCanSucceedWhenServerDoesNotGrantDeletePermission() = runBlocking {
        DavFixture(deleteStatus = 403).use { dav ->
            val warning = WebDavBackup.test(dav.config)
            assertTrue(warning.orEmpty().contains("DELETE（HTTP 403）"))
            assertTrue(WebDavClient(dav.config).listNames().single().startsWith("melora-test-"))
        }
    }

    @Test
    fun probeReportsFailureWhenServerOmitsTheFileFromDirectoryListing() {
        DavFixture(hideProbeFiles = true).use { dav ->
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { WebDavBackup.test(dav.config) }
            }
            assertTrue(error.message.orEmpty().contains("目录列表缺少"))
            assertEquals("DELETE", dav.methods.last())
        }
    }

    @Test
    fun directoryMissingIsAnErrorForSyncButAnEmptyBackupList() = runBlocking {
        DavFixture().use { dav ->
            val error = assertThrows(WebDavException::class.java) {
                runBlocking { WebDavClient(dav.config).listNames() }
            }
            assertEquals(404, error.status)
            assertTrue(WebDavBackup.list(dav.config).isEmpty())
        }
    }

    @Test
    fun concurrentDevicesCanPublishDifferentFilesWithoutConditionalWrites() = runBlocking {
        DavFixture().use { dav ->
            val phone = WebDavClient(dav.config)
            val tablet = WebDavClient(dav.config)
            phone.ensureFolder()
            val phoneWrite = async { phone.writeFile("phone.json", "new from phone") }
            val tabletWrite = async { tablet.writeFile("tablet.json", "new from tablet") }
            phoneWrite.await()
            tabletWrite.await()
            assertEquals(setOf("phone.json", "tablet.json"), phone.listNames().toSet())
            assertEquals("new from phone", tablet.read("phone.json", 128))
            assertEquals("new from tablet", phone.read("tablet.json", 128))
        }
    }

    @Test
    fun twoDevicesConvergeAndResolveOnlyTheConflictingPlaylistFieldOverPlainWebDav() = runBlocking {
        DavFixture(deleteStatus = 403).use { dav ->
            val client = WebDavClient(dav.config)
            val backup = BackupSnapshot(BackupFileId(1680049900000, "0123abcd", UUID.randomUUID()), "backup contents")
            WebDavBackup.upload(dav.config, backup)
            val phone = SyncDevice(temporary.newFolder(), client)
            val tablet = SyncDevice(temporary.newFolder(), WebDavClient(dav.config))
            phone.data = records("原名", listOf("旧收藏"), "原有歌曲")
            assertTrue(phone.sync().isEmpty())
            assertTrue(tablet.sync().isEmpty())

            phone.data = records("通勤", listOf("旧收藏", "手机收藏"), "原有歌曲", "手机新曲")
            tablet.data = records("轻音乐", listOf("平板收藏"), "原有歌曲", "平板新曲")
            val phoneWrite = async { phone.sync() }
            val tabletWrite = async { tablet.sync() }
            phoneWrite.await()
            tabletWrite.await()

            val conflict = tablet.sync().single()
            assertEquals(setOf("通勤", "轻音乐"), setOf(conflict.localLabel, conflict.remoteLabel))
            assertTrue(tablet.sync(mapOf(conflict.key to (conflict.localLabel == "轻音乐"))).isEmpty())
            assertTrue(phone.sync().isEmpty())
            for (device in listOf(phone, tablet)) {
                val library = device.data.getJSONObject("library")
                val playlist = library.getJSONArray("playlists").getJSONObject(0)
                assertEquals("轻音乐", playlist.getString("name"))
                assertEquals(setOf("原有歌曲", "手机新曲", "平板新曲"), trackIds(playlist.getJSONArray("songs")))
                assertEquals(setOf("手机收藏", "平板收藏"), trackIds(library.getJSONArray("favorites")))
            }
            assertTrue(client.listNames().count { it.startsWith("melora-records-v2-") } >= 3)
            assertEquals(backup.id.networkName, WebDavBackup.list(dav.config).single().name)
            assertEquals(backup.text, client.read(backup.id.networkName, 128))
        }
    }

    @Test
    fun backupUploadCanRetryTheSameIdWithoutOverwritingOtherContent() = runBlocking {
        DavFixture().use { dav ->
            val id = BackupFileId(1680049900000, "0123abcd", UUID.randomUUID())
            val backup = BackupSnapshot(id, "first backup")
            dav.dropResponseAfterNextPut = true
            val dropped = runCatching { WebDavBackup.upload(dav.config, backup) }.exceptionOrNull()
            assertTrue(dropped is WebDavException)
            assertTrue(dropped?.message.orEmpty().contains("PUT"))
            WebDavBackup.upload(dav.config, backup)
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { WebDavBackup.upload(dav.config, BackupSnapshot(id, "other backup")) }
            }
            assertTrue(error.message.orEmpty().contains("同名备份"))
            assertEquals(1, dav.methods.count { it == "PUT" })
            assertEquals(backup.text, WebDavClient(dav.config).read(id.networkName, 128))
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
            client.writeFile(first, "old")
            client.writeFile(latest, "new")
            client.writeFile("records.json", "{}")
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
                    client.writeFile("records.json", "x".repeat(129))
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

    private class SyncDevice(private val directory: File, client: WebDavClient) {
        private val id = UUID.randomUUID().toString()
        private val remote = client.recordFiles()
        var data = RecordSyncMerge.project(JSONObject(), JSONArray())

        suspend fun sync(choices: Map<String, Boolean> = emptyMap()): List<RecordConflict> =
            WebDavSyncLedger(directory).sync(id, remote, { data }, { expected, target, prepare, commit ->
                check(RecordSyncMerge.contentEquals(data, expected))
                prepare()
                data = target
                commit()
                true
            }, choices)
    }

    private fun records(name: String, favorites: List<String>, vararg songs: String): JSONObject {
        fun song(id: String) = JSONObject().put("source", "wy").put("songmid", id).put("name", id)
        val playlist = JSONObject().put("id", "weekly").put("name", name).put("songs", JSONArray(songs.map(::song)))
        return RecordSyncMerge.project(JSONObject().put("playlists", JSONArray(listOf(playlist)))
            .put("favorites", JSONArray(favorites.map(::song))), JSONArray())
    }

    private fun trackIds(rows: JSONArray): Set<String> =
        (0 until rows.length()).mapTo(hashSetOf()) { rows.getJSONObject(it).getString("songmid") }

    private class DavFixture(
        private val etag: String? = null,
        private val chunkedReads: Boolean = false,
        private val redirectReads: Boolean = false,
        private val mkcolStatus: Int = 201,
        private val folderExistsAfterMkcol: Boolean = true,
        private val deleteStatus: Int? = null,
        private val hideProbeFiles: Boolean = false,
    ) : AutoCloseable {
        private val files = mutableMapOf<String, String>()
        private var folderExists = false
        val methods = CopyOnWriteArrayList<String>()
        @Volatile var additionalResponses = ""
        @Volatile var dropResponseAfterNextPut = false
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
                        files.keys.filterNot { hideProbeFiles && it.startsWith("melora-test-") }
                            .joinToString("") { davResource("/dav/Melora/$it") } + additionalResponses
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
                        etag?.let { exchange.responseHeaders.add("ETag", it) }
                        exchange.reply(200, file, chunkedReads)
                    }
                }
                "PUT" -> {
                    val previous = files[name]
                    val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                    val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                    when {
                        !folderExists -> exchange.reply(409)
                        ifNoneMatch != null || ifMatch != null -> exchange.reply(400)
                        else -> {
                            val text = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                            files[name] = text
                            if (dropResponseAfterNextPut) {
                                dropResponseAfterNextPut = false
                                return
                            }
                            exchange.reply(if (previous == null) 201 else 204)
                        }
                    }
                }
                "DELETE" -> exchange.reply(deleteStatus ?: if (files.remove(name) == null) 404 else 204)
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
