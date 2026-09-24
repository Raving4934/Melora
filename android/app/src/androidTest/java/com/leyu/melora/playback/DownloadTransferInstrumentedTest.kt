package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 loopback HTTP 下载回归；只用测试 APK 脚本、测试 SAF provider 与 androidTest 音频资产。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class DownloadTransferInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = TransferFixtureContext(instrumentation.targetContext)
    private val scriptStore by lazy { LxScriptStore(context) }
    private val tree get() = DocumentsContract.buildTreeDocumentUri("${instrumentation.context.packageName}.download-fixture", "root")
    private val songs = mutableSetOf<String>()

    @Before fun setup() = runBlocking<Unit> {
        context.root.deleteRecursively()
        context.root.mkdirs()
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        instrumentation.context.startActivity(
            android.content.Intent().setClassName(
                instrumentation.context.packageName,
                "com.leyu.melora.playback.DownloadFixtureProvider\$GrantActivity",
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < deadline
        ) android.os.SystemClock.sleep(25)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags))
        context.contentResolver.call(tree, "reset", null, null)
        MeloraSettings.init(context)
        MeloraSettings.downloadPath.value = tree.toString()
        MeloraSettings.downloadQuality.value = "320k"
        MeloraSettings.downloadAutoSwitchSource.value = true
        MeloraSettings.downloadSkipSameName.value = false
        MeloraSettings.downloadEmbedCover.value = false
        MeloraSettings.downloadEmbedLyric.value = false
        MeloraSettings.downloadFileNameFormat.value = "song-artist"
        DownloadCenter.init(context)
        LocalMediaStore.init(context)
        LocalMediaStore.clear()
        SourceResolver.clearCache()
        LxScriptPool.reload(context)
    }

    @After fun cleanup() = runBlocking<Unit> {
        songs.forEach(DownloadCenter::remove)
        songs.clear()
        LocalMediaStore.clear()
        SourceResolver.clearCache()
        scriptStore.list().forEach { scriptStore.remove(it.id) }
        LxScriptPool.reload(context)
        run {
            val field = AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            (field.get(AudioCacheStore) as? SimpleCache)?.release()
            field.set(AudioCacheStore, null)
        }
        context.contentResolver.call(tree, "reset", null, null)
        context.getSharedPreferences(MeloraSettings.PREFS, 0).edit().clear().commit()
        // DownloadCenter.init 是进程级单次初始化，不能让下一组测试继续写已删除的夹具目录。
        DownloadCenter.javaClass.getDeclaredField("file").apply { isAccessible = true }.set(DownloadCenter, null)
        context.root.deleteRecursively()
    }

    @Test fun interruptedReadExcludesFirstResourceAndUsesOnlyTheNewTransfer() = runBlocking<Unit> {
        requireValidatedNetworkForRetry()
        val firstBytes = asset("audio/fixture-128.mp3")
        val fallbackBytes = asset("audio/fixture-320.mp3")
        LoopbackAudioServer(mapOf(
            "/interrupted" to HttpResponse(200, firstBytes, bytesToSend = 512),
            "/fallback" to HttpResponse(200, fallbackBytes),
        )).use { server ->
            installSources(server, Source("primary", "/interrupted"), Source("backup", "/fallback"))
            val song = song("transfer-read-interrupted")

            val result = withTimeout(25_000) { download(song) }

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals(1, server.count("/interrupted"))
            assertEquals(1, server.count("/fallback"))
            val record = requireNotNull(DownloadCenter.saved(song.uid))
            val savedAudio = requireNotNull(inspectDownloadAudio(context, Uri.parse(requireNotNull(record.savedUri))))
            assertTrue("must contain the fallback's measured quality", SourceResolver.qualityRank(savedAudio.spec.verifiedQuality.orEmpty()) >=
                SourceResolver.qualityRank("320k"))
            assertTemporaryFilesClean()
        }
    }

    @Test fun forbiddenHttpOpenCanSwitchToAnotherResource() = runBlocking<Unit> {
        requireValidatedNetworkForRetry()
        val payload = asset("audio/fixture-320.mp3")
        LoopbackAudioServer(mapOf(
            "/forbidden" to HttpResponse(403, byteArrayOf()),
            "/available" to HttpResponse(200, payload),
        )).use { server ->
            installSources(server, Source("primary", "/forbidden"), Source("backup", "/available"))
            val song = song("transfer-open-403")

            val result = withTimeout(25_000) { download(song) }

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals(1, server.count("/forbidden"))
            assertEquals(1, server.count("/available"))
            assertNotNull(DownloadCenter.saved(song.uid)?.savedUri)
        }
    }

    @Test fun successfulFirstResourceMakesExactlyOneHttpRequest() = runBlocking<Unit> {
        val payload = asset("audio/fixture-320.mp3")
        LoopbackAudioServer(mapOf(
            "/success" to HttpResponse(200, payload),
            "/unused" to HttpResponse(200, payload),
        )).use { server ->
            installSources(server, Source("primary", "/success"), Source("backup", "/unused"))
            val song = song("transfer-first-path-success")

            val result = withTimeout(25_000) { download(song) }

            assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
            assertEquals("normal success must not probe another URL", 1, server.count("/success"))
            assertEquals(0, server.count("/unused"))
        }
    }

    @Test fun cancellingDuringReadDoesNotResolveAnotherResource() = runBlocking<Unit> {
        requireValidatedNetworkForRetry()
        val payload = asset("audio/fixture-320.mp3")
        val headersSent = CountDownLatch(1)
        val resumeBody = CountDownLatch(1)
        LoopbackAudioServer(mapOf(
            "/blocked" to HttpResponse(200, payload, headersSent = headersSent, waitBeforeBody = resumeBody),
            "/unused" to HttpResponse(200, payload),
        )).use { server ->
            installSources(server, Source("primary", "/blocked"), Source("backup", "/unused"))
            val song = song("transfer-cancel")
            val task = launch(Dispatchers.IO) { runCatching { download(song) } }
            try {
                assertTrue("download did not open the loopback response", headersSent.await(8, TimeUnit.SECONDS))
                Downloader.pause(context, song.uid)
            } finally {
                resumeBody.countDown()
            }
            withTimeout(8_000) { task.join() }
            assertEquals(1, server.count("/blocked"))
            assertEquals("cancellation must not switch resources", 0, server.count("/unused"))
            assertTemporaryFilesClean()
        }
    }

    @Test fun safWriteFailureDoesNotRetryAnotherResource() = runBlocking<Unit> {
        val payload = asset("audio/fixture-320.mp3")
        LoopbackAudioServer(mapOf(
            "/download" to HttpResponse(200, payload),
            "/unused" to HttpResponse(200, payload),
        )).use { server ->
            installSources(server, Source("primary", "/download"), Source("backup", "/unused"))
            val song = song("transfer-saf-write-failure")
            context.contentResolver.call(tree, "failNextWrite", null, null)

            val result = withTimeout(25_000) { download(song) }

            assertTrue("fixture SAF write failure must fail the task", result.isFailure)
            assertEquals(1, server.count("/download"))
            assertEquals("destination write failure is not a network retry", 0, server.count("/unused"))
            assertTrue(listFiles().isEmpty())
            assertTemporaryFilesClean()
        }
    }

    @Test fun disabledAutoSwitchMakesOnlyTheInitialRequest() = runBlocking<Unit> {
        val payload = asset("audio/fixture-320.mp3")
        LoopbackAudioServer(mapOf(
            "/forbidden" to HttpResponse(403, byteArrayOf()),
            "/unused" to HttpResponse(200, payload),
        )).use { server ->
            installSources(server, Source("primary", "/forbidden"), Source("backup", "/unused"))
            MeloraSettings.downloadAutoSwitchSource.value = false
            val song = song("transfer-switch-disabled")

            val result = withTimeout(15_000) { download(song) }

            assertTrue(result.isFailure)
            assertEquals(1, server.count("/forbidden"))
            assertEquals(0, server.count("/unused"))
        }
    }

    @Test fun failedResourcesAreDistinctAndCappedAtThree() = runBlocking<Unit> {
        requireValidatedNetworkForRetry()
        val payload = asset("audio/fixture-320.mp3")
        LoopbackAudioServer(mapOf(
            "/one" to HttpResponse(403, byteArrayOf()),
            "/two" to HttpResponse(403, byteArrayOf()),
            "/three" to HttpResponse(403, byteArrayOf()),
            "/four" to HttpResponse(200, payload),
        )).use { server ->
            installSources(server,
                Source("primary", "/one"), Source("backup-two", "/two"),
                Source("backup-three", "/three", responseDelayMs = 250),
                Source("backup-four", "/four", responseDelayMs = 750),
            )
            val song = song("transfer-resource-cap")

            val result = withTimeout(25_000) { download(song) }

            assertTrue(result.isFailure)
            assertEquals(1, server.count("/one"))
            assertEquals(1, server.count("/two"))
            assertEquals(1, server.count("/three"))
            assertEquals("the fourth resource is outside the cap", 0, server.count("/four"))
            assertTemporaryFilesClean()
        }
    }

    @Test fun upgradeRetryDoesNotResetThePreviouslyCompletedRecordDuringProbe() = runBlocking<Unit> {
        requireValidatedNetworkForRetry()
        LoopbackAudioServer(mapOf(
            "/old" to HttpResponse(200, asset("audio/fixture-128.mp3")),
            "/forbidden" to HttpResponse(403, byteArrayOf()),
            "/upgrade" to HttpResponse(200, asset("audio/fixture-24.flac")),
        )).use { server ->
            val song = song("transfer-upgrade-history")
            installSources(server, Source("initial", "/old"))
            download(song).getOrThrow()
            val old = requireNotNull(DownloadCenter.saved(song.uid))
            val local = requireNotNull(LocalMediaStore.findByUri(requireNotNull(old.savedUri)))
            scriptStore.list().forEach { scriptStore.remove(it.id) }
            installSources(server, Source("primary", "/forbidden"), Source("backup", "/upgrade"))
            SourceResolver.clearCache()
            MeloraSettings.downloadQuality.value = "flac24bit"
            val snapshots = mutableListOf<DownloadCenter.Record>()
            val observer = launch(Dispatchers.Unconfined) {
                DownloadCenter.records.collect { list -> list.firstOrNull { it.id == song.uid }?.let(snapshots::add) }
            }
            try {
                withTimeout(25_000) { Downloader.upgrade(context, song, local).await() }.getOrThrow()
                assertTrue(snapshots.filter { it.status == DownloadCenter.Status.Done && it.savedUri == old.savedUri }
                    .all { it.percent == old.percent && it.detail == old.detail })
                assertEquals(24, DownloadCenter.saved(song.uid)?.audioSpec?.bitDepth)
                assertEquals(1, server.count("/forbidden"))
                assertEquals(1, server.count("/upgrade"))
            } finally { observer.cancel() }
        }
    }

    private suspend fun installSources(server: LoopbackAudioServer, vararg sources: Source) {
        val imported = sources.map { source ->
            scriptStore.import(source.name, script(source, server.port))
        }
        scriptStore.setEnabled(imported.first().id, true)
        LxScriptPool.reload(context)
    }

    private fun script(source: Source, port: Int): String = """
        // @name ${source.name}
        // @version 1
        const initialize = () => lx.send('inited', {sources: {kw: {
            name: 'download fixture', actions: ['musicUrl'], qualitys: ['flac24bit', 'flac', '320k', '128k']
        }}});
        initialize();
        lx.on('request', ({info}) => new Promise(resolve => {
            const result = {url: 'http://127.0.0.1:$port${source.path}',
                type: info.type, resourceId: '${source.name}-resource'};
            if (${source.responseDelayMs} > 0) setTimeout(() => resolve(result), ${source.responseDelayMs});
            else resolve(result);
        }));
    """.trimIndent()

    private fun song(uid: String): OnlineSong {
        return OnlineSong(JSONObject().put("source", "kw").put("songmid", uid)
            .put("name", "Fixture").put("singer", "Test Artist").put("albumName", "Fixture Album").put("interval", "00:01"))
            .also { songs += it.uid }
    }

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    private suspend fun download(song: OnlineSong): Result<String> {
        return Downloader.download(context, song)
    }

    private fun requireValidatedNetworkForRetry() = assumeTrue("retry policy requires a validated active network", NetworkState.isConnected(context))

    private fun listFiles(): List<String> = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root"),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
    )!!.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun assertTemporaryFilesClean() {
        assertTrue(File(context.cacheDir, Downloader.TEMP_DIRECTORY).listFiles().orEmpty().isEmpty())
    }

    private data class Source(val name: String, val path: String, val responseDelayMs: Int = 0)

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val bytesToSend: Int = body.size,
        val headersSent: CountDownLatch? = null,
        val waitBeforeBody: CountDownLatch? = null,
    )

    private class LoopbackAudioServer(private val responses: Map<String, HttpResponse>) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val running = AtomicInteger(1)
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        val port: Int get() = server.localPort
        private val acceptThread = Thread({
            while (running.get() == 1) {
                try {
                    val socket = server.accept()
                    executor.execute { serve(socket) }
                } catch (_: Exception) { if (running.get() != 1) break }
            }
        }, "melora-download-loopback").apply { isDaemon = true; start() }

        fun count(path: String): Int = counts[path]?.get() ?: 0

        private fun serve(socket: Socket) {
            socket.use { client ->
                client.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return
                while (!reader.readLine().isNullOrEmpty()) Unit
                counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val response = responses[path] ?: HttpResponse(404, byteArrayOf())
                val reason = if (response.status == 200) "OK" else if (response.status == 403) "Forbidden" else "Not Found"
                val output = client.getOutputStream()
                output.write(("HTTP/1.1 ${response.status} $reason\r\n" +
                    "Content-Type: audio/mpeg\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                response.headersSent?.countDown()
                response.waitBeforeBody?.await(10, TimeUnit.SECONDS)
                if (response.bytesToSend > 0) {
                    output.write(response.body, 0, response.bytesToSend.coerceAtMost(response.body.size))
                    output.flush()
                }
            }
        }

        override fun close() {
            running.set(0)
            server.close()
            executor.shutdownNow()
            acceptThread.join(1_000)
        }
    }

    private class TransferFixtureContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "isolated-download-transfer-tests")
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
            errorHandler: android.database.DatabaseErrorHandler?,
        ) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int) =
            baseContext.getSharedPreferences("isolated-download-transfer-tests.$name", mode)
    }
}
