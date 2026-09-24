package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.AudioCacheStore
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.leyu.melora.playback.lx.LxScriptStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 只解析合成脚本的example.test字符串，不联网、不播放、不读写用户源/媒体/设置。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackResolutionInstrumentedTest {
    private val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private var ownsCache = false
    private val store get() = LxScriptStore(context)
    private val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "fixture")
        .put("name", "Fixture").put("singer", "Test Artist").put("_types", JSONObject().put("128k", JSONObject())))

    @Before fun setup() = runBlocking<Unit> {
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        context.getSharedPreferences("lx-sources", 0).edit().clear().commit()
        context.getSharedPreferences(MeloraSettings.PREFS, 0).edit().clear().commit()
        MeloraSettings.init(context)
        LxScriptPool.reload(context)
        SourceResolver.clearCache()
    }

    @After fun cleanup() = runBlocking<Unit> {
        SourceResolver.clearCache()
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        LxScriptPool.reload(context)
        context.getSharedPreferences("lx-sources", 0).edit().clear().commit()
        context.getSharedPreferences(MeloraSettings.PREFS, 0).edit().clear().commit()
        val cacheField = AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }
        if (ownsCache) {
            (cacheField.get(AudioCacheStore) as? SimpleCache)?.release()
            cacheField.set(AudioCacheStore, null)
        }
        context.root.deleteRecursively()
    }

    @Test fun readySourceDoesNotWaitForSlowSiblingInitialization() = runBlocking<Unit> {
        store.import("slow.js", source("slow", initDelay = 2000))
        store.import("fast.js", source("fast"))
        val started = android.os.SystemClock.elapsedRealtime()
        val result = LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ALL, 900,
            request = { script, _ -> LxScriptPool.ScriptRequest("320k", script.id, "kw", "musicUrl",
                JSONObject().put("type", "320k"), 900) },
            accept = { SourceResolver.extractUrl(it.data) != null })
        assertNotNull("冷源已经就绪时不应被慢源初始化阻挡", result)
        assertEquals("fast.js", result!!.scriptId)
        assertTrue("快源胜出后不能等待慢源初始化窗口", android.os.SystemClock.elapsedRealtime() - started < 800)
    }

    @Test fun foregroundReceivesReadySourceWhileBackgroundReloadStillInitializesSlowSibling() = runBlocking<Unit> {
        store.import("slow.js", source("slow", initDelay = 2000))
        store.import("fast.js", source("fast", initDelay = 200))
        store.setEnabled("fast.js", true)
        val reload = async { LxScriptPool.reload(context) }
        try {
            delay(50)
            val result = LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ENABLED, 900,
                request = { script, _ -> LxScriptPool.ScriptRequest("320k", script.id, "kw", "musicUrl",
                    JSONObject().put("type", "320k"), 900) },
                accept = { SourceResolver.extractUrl(it.data) != null })
            assertEquals("fast.js", result?.scriptId)
        } finally { reload.cancelAndJoin() }
    }

    @Test fun usableLowerTierDoesNotTripProviderCircuitBreaker() = runBlocking<Unit> {
        store.import("primary.js", source("primary", reportedQuality = "128k"))
        store.setEnabled("primary.js", true)
        repeat(3) {
            LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ENABLED, 900,
                request = { script, _ -> LxScriptPool.ScriptRequest("flac24bit", script.id, "kw", "musicUrl",
                    JSONObject().put("type", "flac24bit"), 900) }, accept = { false })
        }
        val health = LxScriptPool.javaClass.getDeclaredField("scriptHealth").apply { isAccessible = true }
            .get(LxScriptPool) as Map<*, *>
        assertFalse(health.containsKey("primary.js"))
    }

    @Test fun cancellingColdSourceInitializationAfterGcCanReleaseAndReloadRuntimes() = runBlocking<Unit> {
        repeat(3) {
            store.import("slow.js", source("slow", initDelay = 1000))
            val resolving = async {
                LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ALL, 1500,
                    request = { script, _ -> LxScriptPool.ScriptRequest("320k", script.id, "kw", "musicUrl",
                        JSONObject().put("type", "320k"), 1500) }, accept = { true })
            }
            delay(100)
            System.gc()
            val cancelledAt = android.os.SystemClock.elapsedRealtime()
            resolving.cancelAndJoin()
            assertTrue("初始化取消应立即退出定时器等待", android.os.SystemClock.elapsedRealtime() - cancelledAt < 700)
            store.remove("slow.js")
            LxScriptPool.reload(context)
        }
    }

    @Test fun highTierCanFinishAfterFormer128kHedgeWouldHaveWon() = runBlocking<Unit> {
        store.import("primary.js", source("primary", responseDelay = 1800))
        store.setEnabled("primary.js", true)
        val result = SourceResolver.resolve(context, song, "flac24bit", allowSwitch = false)
        assertEquals("flac24bit", result.quality)
        assertTrue(result.resourceId.startsWith("lx:primary.js:"))
    }

    @Test fun rejectedPlayableUrlIsNotSelectedAgainAndBackupStaysSameQuality() = runBlocking<Unit> {
        store.import("primary.js", source("primary"))
        store.import("backup.js", source("backup"))
        store.setEnabled("primary.js", true)
        val first = SourceResolver.resolve(context, song, "320k", allowSwitch = true)
        assertTrue(first.resourceId.startsWith("lx:primary.js:"))
        SourceResolver.rejectResource(song.uid, first.resourceId)
        val second = SourceResolver.resolve(context, song, "320k", allowSwitch = true)
        assertEquals("320k", second.quality)
        assertTrue(second.resourceId.startsWith("lx:backup.js:"))
        assertNotEquals(first.resourceId, second.resourceId)
    }

    @Test fun slowSourceCandidateDoesNotPoisonActiveUrlCacheOrRejectCurrentResource() = runBlocking<Unit> {
        // 标记章节避免测试任何跨平台公网搜索；脚本只返回合成URL。
        val chapter = OnlineSong(JSONObject(song.raw.toString()).put("isBookChapter", true))
        store.import("primary.js", source("primary"))
        store.import("backup.js", source("backup"))
        store.setEnabled("primary.js", true)
        val first = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true)
        val candidate = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.REBUFFER, excludedResources = setOf(first.resourceId))
        assertEquals("flac24bit", candidate.quality)
        assertTrue(candidate.resourceId.startsWith("lx:backup.js:"))
        assertFalse(SourceResolver.isRejected(chapter.uid, first.resourceId))
        assertEquals(first, SourceResolver.peek(chapter, "flac24bit", allowSwitch = true))
        assertEquals(first, SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true))
        MeloraSettings.autoSwitchSource.value = true
        SourceResolver.selectForPlayback(chapter, "flac24bit", candidate)
        assertEquals(candidate, SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true))
    }

    @Test fun failedSlowSourceRecoveryDoesNotDowngradeOrDisableOriginal() = runBlocking<Unit> {
        val chapter = OnlineSong(JSONObject(song.raw.toString()).put("isBookChapter", true))
        store.import("primary.js", source("primary"))
        store.import("backup.js", source("backup", reportedQuality = "flac"))
        store.setEnabled("primary.js", true)
        val first = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true)
        val result = runCatching {
            SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
                purpose = SourceResolver.Purpose.REBUFFER, excludedResources = setOf(first.resourceId))
        }
        assertTrue(result.isFailure)
        assertFalse(SourceResolver.isRejected(chapter.uid, first.resourceId))
        assertEquals(first, SourceResolver.peek(chapter, "flac24bit", allowSwitch = true))
    }

    @Test fun sourceReportedDowngradeDoesNotBeatHighQualityBackup() = runBlocking<Unit> {
        store.import("primary.js", source("primary", reportedQuality = "128k"))
        store.import("backup.js", source("backup"))
        store.setEnabled("primary.js", true)
        val result = SourceResolver.resolve(context, song, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.DOWNLOAD)
        assertEquals("flac24bit", result.quality)
        assertTrue(result.resourceId.startsWith("lx:backup.js:"))
    }

    @Test fun verifiedQualitySurvivesCacheLookupAndDownloadExtractionWithoutRelabellingAnotherFile() = runBlocking<Unit> {
        val original = SourceResolver.Resolved("https://example.test/file-a.flac", "flac24bit", song, false, "lx:fixture:file-a")
        assertNull(AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(AudioCacheStore))
        val resource = AudioCacheStore.registerResolved(context, song.uid, "flac24bit", original)
        ownsCache = true
        val cache = AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            .get(AudioCacheStore) as SimpleCache
        val bytes = "synthetic cached audio bytes for metadata test".toByteArray()
        val hole = cache.startReadWrite(resource.key, 0, bytes.size.toLong())
        try {
            val file = cache.startFile(resource.key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            cache.applyContentMetadataMutations(resource.key, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, bytes.size.toLong())
            })
        } finally { cache.releaseHoleSpan(hole) }
        SourceResolver.confirmQuality(original.resourceId, "128k")
        AudioCacheStore.recordObservedQuality(context, song.uid, original.resourceId, "128k")
        SourceResolver.clearCache() // 模拟解析层没有内存音质观察记录。
        val downloaded = AudioCacheStore.openForDownload(context, song, "128k", allowSwitch = false)
        downloaded.stream.use { assertArrayEquals(bytes, it.readBytes()) }
        assertTrue(downloaded.completeCacheHit)
        assertEquals("128k", downloaded.actualQuality)
        assertEquals(resource.key, downloaded.resourceKey)
        assertEquals("128k", SourceResolver.observedQuality(original.resourceId))

        val offline = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val offlineExport = AudioCacheStore.openForDownload(offline, song, "flac24bit", allowSwitch = false)
        offlineExport.stream.use { assertArrayEquals(bytes, it.readBytes()) }
        assertTrue(offlineExport.completeCacheHit)
        assertEquals("128k", offlineExport.actualQuality)

        val other = original.copy(url = "https://example.test/file-b.flac", resourceId = "lx:fixture:file-b")
        val next = AudioCacheStore.registerResolved(context, song.uid, "flac24bit", other)
        assertNotEquals(resource.key, next.key)
        assertEquals("flac24bit", next.actualQuality)
        assertTrue(cache.getCachedSpans(next.key).isEmpty())
        SourceResolver.rejectResource(song.uid, original.resourceId)
        val lookup = AudioCacheStore.javaClass.declaredMethods.single { it.name == "preferredResource" }.apply { isAccessible = true }
        assertNull(lookup.invoke(AudioCacheStore, context, song.uid, "128k", true, emptySet<String>()))
    }

    @Test fun lateScriptPromiseCannotMasqueradeAsNextQualityResponse() {
        com.leyu.melora.playback.lx.LxScriptEngine(context).use { engine ->
            engine.load("""
                let count = 0;
                lx.on('request', ({info}) => new Promise(resolve => {
                    const wait = ++count === 1 ? 80 : 160;
                    setTimeout(() => resolve('https://example.test/' + info.type), wait);
                }));
            """.trimIndent(), "late-result.js")
            try {
                engine.request("kw", "musicUrl", JSONObject().put("type", "flac24bit"), 30)
                fail("first request must time out")
            } catch (_: IllegalStateException) { }
            assertEquals("https://example.test/flac",
                engine.request("kw", "musicUrl", JSONObject().put("type", "flac"), 800))
        }
    }

    private fun source(name: String, initDelay: Int = 0, responseDelay: Int = 0, reportedQuality: String? = null): String = """
        // @name $name
        // @version 1
        const initialize = () => lx.send('inited', {sources: {kw: {
            name: 'fixture', actions: ['musicUrl'], qualitys: ['flac24bit', 'flac', '320k', '128k']
        }}});
        if ($initDelay > 0) setTimeout(initialize, $initDelay); else initialize();
        lx.on('request', ({info}) => new Promise(resolve => {
            const result = {url: 'https://example.test/$name-' + info.type + '.flac',
                type: ${reportedQuality?.let(JSONObject::quote) ?: "info.type"}};
            if ($responseDelay > 0) setTimeout(() => resolve(result), $responseDelay); else resolve(result);
        }));
    """.trimIndent()

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "isolated-resolution-tests").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int) =
            baseContext.getSharedPreferences("isolated-resolution-tests.$name", mode)
    }
}
