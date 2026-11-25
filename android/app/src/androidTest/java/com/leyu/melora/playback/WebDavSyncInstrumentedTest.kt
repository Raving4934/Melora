package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavSyncInstrumentedTest {
    @Test
    fun noEtagServerCanEnableUploadMergeAndDisableRecordSync() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = Files.createTempDirectory(app.cacheDir.toPath(), "webdav-sync-").toFile()
        val context = SyncFixtureContext(app, storage)
        val restoreLibrary = bindFixtureLibrary(File(storage, "user-library.json"))
        try {
            LoopbackDav().use { dav ->
                val config = WebDavSync.saveConfig(context, dav.config)
                val client = WebDavClient(config)
                UserLibrary.replaceFromBackup(library("phone-only").toString())
                WebDavSync.setEnabled(context, true)
                assertTrue(WebDavSync.enabled.value)
                val uploaded = WebDavSync.syncNow(context)
                assertTrue(WebDavSync.status.value, uploaded)

                val phoneFile = client.listNames().single()
                val phone = RecordReplica.decode(phoneFile, requireNotNull(client.read(phoneFile, MAX_BACKUP_BYTES)))
                assertTrue(RecordKey("favorites", "kw_phone-only") in phone.edits)

                var tablet = RecordSyncMerge.project(library("tablet-only"), JSONArray())
                WebDavSyncLedger(File(storage, "tablet")).sync(
                    UUID.randomUUID().toString(), client, capture = { tablet },
                    apply = { _, after, prepare, commit ->
                        prepare()
                        tablet = after
                        commit()
                        true
                    },
                )
                val merged = WebDavSync.syncNow(context)
                assertTrue(WebDavSync.status.value, merged)
                assertEquals(setOf("kw_phone-only", "kw_tablet-only"), UserLibrary.favoriteUids.value)
                UserLibrary.reloadAfterRestore()
                assertEquals(setOf("kw_phone-only", "kw_tablet-only"), UserLibrary.favoriteUids.value)

                WebDavSync.setEnabled(context, false)
                assertFalse(WebDavSync.enabled.value)
                val filenames = client.listNames()
                UserLibrary.replaceFromBackup(library("only-after-disable").toString())
                assertTrue(WebDavSync.syncNow(context))
                assertEquals(filenames, client.listNames())
            }
        } finally {
            try {
                if (WebDavSync.enabled.value) WebDavSync.setEnabled(context, false)
            } finally {
                restoreLibrary()
                context.clean()
            }
        }
    }

    private fun library(id: String) = JSONObject().put("favorites", JSONArray().put(
        JSONObject().put("source", "kw").put("songmid", id).put("name", id),
    ))

    private fun bindFixtureLibrary(path: File): () -> Unit = BackupStateLock.write {
        val fields = UserLibrary::class.java.declaredFields.associateBy { it.name }
        val file = fields.getValue("file").apply { isAccessible = true }
        val clearTime = fields.getValue("historyClearedAt").apply { isAccessible = true }
        val previousFile = file.get(UserLibrary)
        val previousClearTime = clearTime.getLong(UserLibrary)
        val previousFlows = fields.values.filter { MutableStateFlow::class.java.isAssignableFrom(it.type) }.map {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val flow = it.get(UserLibrary) as MutableStateFlow<Any?>
            flow to flow.value
        }
        file.set(UserLibrary, path)
        return@write {
            BackupStateLock.write {
                file.set(UserLibrary, previousFile)
                clearTime.setLong(UserLibrary, previousClearTime)
                previousFlows.forEach { (flow, value) -> flow.value = value }
            }
        }
    }

    private class SyncFixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("${root.name}.$name".also(preferenceNames::add), mode)

        fun clean() {
            preferenceNames.forEach(baseContext::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private class LoopbackDav : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val files = linkedMapOf<String, String>()
        val config = WebDavConfig("http://127.0.0.1:${server.localPort}/")
        private val directory = config.validated().folderUrl().encodedPath
        private val worker = thread(name = "webdav-test-loopback", isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use(::serve)
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().source().buffer()
            val (method, path) = input.readUtf8LineStrict().split(' ')
            val headers = generateSequence(input::readUtf8Line).takeWhile { it.isNotEmpty() }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val body = input.readUtf8(headers["content-length"]?.toLong() ?: 0)
            val (status, text) = when {
                headers.keys.any { it == "if-match" || it == "if-none-match" } -> 412 to ""
                path == directory && method == "PROPFIND" -> 207 to listing(headers["depth"] == "1")
                !path.startsWith(directory) -> 404 to ""
                method == "GET" -> files[path]?.let { 200 to it } ?: (404 to "")
                method == "PUT" -> { files[path] = body; 201 to "" }
                method == "DELETE" -> { files.remove(path); 204 to "" }
                else -> 405 to ""
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status WebDAV\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        private fun listing(includeFiles: Boolean) = buildString {
            fun resource(path: String, collection: String) {
                append("<response><href>$path</href><propstat><prop><resourcetype>$collection</resourcetype></prop>")
                append("<status>HTTP/1.1 200 OK</status></propstat></response>")
            }
            append("<multistatus xmlns=\"DAV:\">")
            resource(directory, "<collection/>")
            if (includeFiles) for (path in files.keys) resource(path, "")
            append("</multistatus>")
        }

        override fun close() {
            server.close()
            worker.join(5_000)
        }
    }
}
