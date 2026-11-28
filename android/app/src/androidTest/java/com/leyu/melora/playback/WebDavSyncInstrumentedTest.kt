package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavSyncInstrumentedTest {
    @Test
    fun interruptedTargetSwitchCannotResumeThePreviouslyEnabledDestination() = runBlocking<Unit> {
        withSyncFixture { context ->
            LoopbackDav().use { dav ->
                val original = WebDavSync.saveConfig(context, dav.config)
                val replacement = original.copy(folder = "Other folder")
                // 模拟旧版应用保存新连接后，在撤销旧同步授权之前退出。
                WebDavConfigStore.save(context, replacement)
                val legacy = context.useLegacyConfig()
                assertTrue(context.syncPrefs.edit().putString("enabled-scope", original.scopeKey).commit())

                assertTrue(WebDavSync.status.value, WebDavSync.syncNow(context))
                assertFalse(WebDavSync.enabled.value)
                assertNull(context.syncPrefs.getString("enabled-scope", null))
                assertFalse(legacy.exists())
                WebDavSync.saveConfig(context, original)
                assertFalse(WebDavSync.enabled.value)
                assertTrue(WebDavSync.syncNow(context))
            }
        }
    }

    @Test
    fun failedSwitchCommitLeavesTheOriginalConfigurationAndConsent() = runBlocking<Unit> {
        withSyncFixture { context ->
            LoopbackDav().use { dav ->
                val original = WebDavSync.saveConfig(context, dav.config)
                assertTrue(context.syncPrefs.edit().putString("enabled-scope", original.scopeKey).commit())
                assertTrue(WebDavSync.syncNow(context))
                val previous = context.syncPrefs.all
                val replacement = original.copy(folder = "Other folder")
                for (failures in 1..2) {
                    context.rejectedCommitsRemaining = failures
                    val error = requireNotNull(runCatching { WebDavSync.saveConfig(context, replacement) }.exceptionOrNull())
                    assertEquals("无法在本机保存 WebDAV 配置", error.message)
                    assertEquals("无法保存 WebDAV 设置", error.cause?.message)
                    assertEquals(failures == 2, error.cause?.cause != null)
                    assertEquals(0, context.rejectedCommitsRemaining)
                    assertEquals(previous, context.syncPrefs.all)
                    assertEquals(original, WebDavConfigStore.load(context))
                    assertTrue(WebDavSync.enabled.value)
                }
            }
        }
    }

    @Test
    fun legacyConsentSurvivesMigrationAndChangingOnlyThePassword() = runBlocking<Unit> {
        withSyncFixture { context ->
            LoopbackDav().use { dav ->
                val config = WebDavSync.saveConfig(context, dav.config.copy(account = "listener", password = "first"))
                assertTrue(context.syncPrefs.edit().putString("enabled-scope", config.scopeKey).commit())
                val legacy = context.useLegacyConfig()

                assertTrue(WebDavSync.syncNow(context))
                assertFalse(legacy.exists())
                val rotated = WebDavSync.saveConfig(context, config.copy(password = "second"))
                assertEquals(rotated, WebDavConfigStore.load(context))
                assertEquals(config.scopeKey, context.syncPrefs.getString("enabled-scope", null))
                assertTrue(WebDavSync.enabled.value)
                assertTrue(WebDavSync.syncNow(context))
            }
        }
    }

    @Test
    fun unreadableCredentialsPreserveRetryAndAllowExplicitDisable() = runBlocking<Unit> {
        withSyncFixture { context ->
            LoopbackDav().use { dav ->
                val config = WebDavSync.saveConfig(context, dav.config)
                assertTrue(context.syncPrefs.edit().putString("enabled-scope", config.scopeKey).commit())
                assertTrue(WebDavSync.syncNow(context))
                val encrypted = requireNotNull(context.syncPrefs.getString("encrypted-config", null))
                context.replaceEncryptedConfig("unreadable")

                assertFalse(WebDavSync.syncNow(context))
                assertTrue(WebDavSync.enabled.value)
                assertEquals(config.scopeKey, context.syncPrefs.getString("enabled-scope", null))
                context.replaceEncryptedConfig(encrypted)
                assertTrue(WebDavSync.syncNow(context))

                context.replaceEncryptedConfig("unreadable")
                WebDavSync.setEnabled(context, false)
                assertFalse(WebDavSync.enabled.value)
                assertEquals("unreadable", context.syncPrefs.getString("encrypted-config", null))
                context.replaceEncryptedConfig(encrypted)
                assertTrue(WebDavSync.syncNow(context))
                assertFalse(WebDavSync.enabled.value)
            }
        }
    }

    @Test
    fun noEtagServerCanEnableUploadMergeAndDisableRecordSync() = runBlocking {
        withSyncFixture { context ->
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
                WebDavSyncLedger(File(context.noBackupFilesDir, "tablet")).sync(
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
        }
    }

    private fun library(id: String) = JSONObject().put("favorites", JSONArray().put(
        JSONObject().put("source", "kw").put("songmid", id).put("name", id),
    ))

    private suspend fun withSyncFixture(block: suspend (SyncFixtureContext) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = Files.createTempDirectory(app.cacheDir.toPath(), "webdav-sync-").toFile()
        val context = SyncFixtureContext(app, storage)
        val restoreLibrary = bindFixtureLibrary(File(storage, "user-library.json"))
        try {
            block(context)
        } finally {
            context.rejectedCommitsRemaining = 0
            try {
                WebDavSync.setEnabled(context, false)
            } finally {
                restoreLibrary()
                context.clean()
            }
        }
    }

    private fun bindFixtureLibrary(path: File): () -> Unit = BackupStateLock.write {
        val file = UserLibrary::class.java.getDeclaredField("file").apply { isAccessible = true }
        val previousFile = file.get(UserLibrary)
        val snapshot = UserLibrary.exportSnapshot()
        file.set(UserLibrary, path)
        return@write {
            BackupStateLock.write {
                try { UserLibrary.replaceFromBackup(snapshot) } finally { file.set(UserLibrary, previousFile) }
            }
        }
    }

    private class SyncFixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
        var rejectedCommitsRemaining = 0
        val syncPrefs get() = getSharedPreferences("webdav-sync", Context.MODE_PRIVATE)
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences("${root.name}.$name".also(preferenceNames::add), mode)
            if (name != "webdav-sync") return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun commit(): Boolean {
                            if (rejectedCommitsRemaining == 0) return editor.commit()
                            rejectedCommitsRemaining--
                            // Android 写盘失败时也可能已更新进程内可读的偏好值。
                            editor.apply()
                            return false
                        }
                    }
                }
            }
        }

        fun useLegacyConfig(): File = File(root, "webdav-config.json").apply {
            writeText(requireNotNull(syncPrefs.getString("encrypted-config", null)))
            assertTrue(syncPrefs.edit().remove("encrypted-config").commit())
        }

        fun replaceEncryptedConfig(value: String) {
            assertTrue(syncPrefs.edit().putString("encrypted-config", value).commit())
        }

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
