package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.AudioCacheStore
import com.leyu.melora.playback.AudioCacheIndex
import com.leyu.melora.playback.MeloraDataSourceFactory
import com.leyu.melora.playback.TrackRegistry
import com.leyu.melora.playback.UiTrack
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.leyu.melora.playback.lx.LxScriptStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test fun playbackKeepsFastPrimaryHqWithoutRequestingSlowerHrBackup() = runBlocking<Unit> {
        val chapter = chapterSong("playback-primary-first")
        store.import("primary.js", prioritySource("primary", "320k"))
        val backup = store.import("backup.js", prioritySource("backup", "flac24bit", responseDelay = 1200))
        store.setEnabled("primary.js", true)

        val result = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.PLAYBACK)

        assertEquals("320k", result.quality)
        assertTrue(result.resourceId.startsWith("lx:primary.js:"))
        delay(700) // 覆盖600ms宽限窗口，确认没有延迟启动备用请求。
        // probe_count is a synthetic script query, explicitly excluded from musicUrl call accounting.
        val backupCalls = LxScriptPool.resolve(context, backup.id, "kw", "musicUrl",
            JSONObject().put("type", "probe_count"), 1_000)
        assertEquals("calls:0", backupCalls)
    }

    @Test fun playbackFallsBackWhenPrimaryHasNoCandidateWithinHedgeWindow() = runBlocking<Unit> {
        val chapter = chapterSong("playback-primary-slow")
        store.import("primary.js", prioritySource("primary", "320k", responseDelay = 3000, unavailable = true))
        store.import("backup.js", prioritySource("backup", "flac24bit"))
        store.setEnabled("primary.js", true)
        val started = android.os.SystemClock.elapsedRealtime()

        val result = withTimeout(4_000) {
            SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
                purpose = SourceResolver.Purpose.PLAYBACK)
        }

        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue(result.resourceId.startsWith("lx:backup.js:"))
        assertEquals("flac24bit", result.quality)
        assertTrue("备用接管不应等待主源的长响应窗口（${elapsed}ms）", elapsed < 2500)
    }

    @Test fun playbackPrefersLatePrimaryCandidateWhileBackupIsStillResolving() = runBlocking<Unit> {
        val chapter = chapterSong("playback-primary-late-candidate")
        store.import("primary.js", prioritySource("primary", "320k", responseDelay = 1200))
        store.import("backup.js", prioritySource("backup", "flac24bit", responseDelay = 1000))
        store.setEnabled("primary.js", true)
        val started = android.os.SystemClock.elapsedRealtime()

        val result = withTimeout(4_000) {
            SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
                purpose = SourceResolver.Purpose.PLAYBACK)
        }

        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertTrue("候选到达前应经过600ms宽限", elapsed >= 600)
        assertTrue(result.resourceId.startsWith("lx:primary.js:"))
        assertEquals("320k", result.quality)
        assertTrue("主源晚到候选不应等待其完整长超时（${elapsed}ms）", elapsed < 2500)
    }

    @Test fun rejectedPlayableUrlIsNotSelectedAgainAndBackupStaysSameQuality() = runBlocking<Unit> {
        store.import("primary.js", source("primary"))
        store.import("backup.js", source("backup"))
        store.setEnabled("primary.js", true)
        val first = SourceResolver.resolve(context, song, "320k", allowSwitch = true)
        assertTrue(first.resourceId.startsWith("lx:primary.js:"))
        SourceResolver.rejectResource(first.resourceId)
        val second = SourceResolver.resolve(context, song, "320k", allowSwitch = true)
        assertEquals("320k", second.quality)
        assertTrue(second.resourceId.startsWith("lx:backup.js:"))
        assertNotEquals(first.resourceId, second.resourceId)
    }

    @Test fun rejectedSharedScriptUrlIsSkippedForAnotherUidWhileBackupRemainsUsable() = runBlocking<Unit> {
        val suffix = System.nanoTime().toString()
        val primary = store.import("shared-primary-$suffix", source("shared-$suffix"))
        val backup = store.import("shared-backup-$suffix", source("good-$suffix"))
        store.setEnabled(primary.id, true)
        LxScriptPool.reload(context)
        val firstSong = OnlineSong(JSONObject(song.raw.toString())
            .put("songmid", "shared-owner-$suffix").put("isBookChapter", true))
        val nextSong = OnlineSong(JSONObject(song.raw.toString())
            .put("songmid", "shared-next-$suffix").put("isBookChapter", true))
        try {
            val failed = SourceResolver.resolve(context, firstSong, "320k", allowSwitch = true)
            assertTrue(failed.resourceId.startsWith("lx:${primary.id}:"))
            assertEquals("同一脚本对不同 UID 返回相同 URL，应具有相同物理资源身份",
                SourceResolver.scriptResourceId(primary.id, failed.url), failed.resourceId)
            assertTrue(failed.url.startsWith("https://example.test/shared-$suffix-"))

            SourceResolver.rejectResource(failed.resourceId)
            assertTrue(SourceResolver.isRejected(failed.resourceId))
            val recovered = SourceResolver.resolve(context, nextSong, "320k", allowSwitch = true)
            assertTrue("另一 UID 不得再次解析命中已拒绝 URL", recovered.resourceId.startsWith("lx:${backup.id}:"))
            assertNotEquals(failed.resourceId, recovered.resourceId)
            assertTrue("未被拒绝的备用 URL 仍可解析", recovered.url.startsWith("https://example.test/good-$suffix-"))
            assertFalse(SourceResolver.isRejected(recovered.resourceId))
        } finally {
            store.remove(primary.id)
            store.remove(backup.id)
            LxScriptPool.reload(context)
            SourceResolver.clearCache()
        }
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
        assertFalse(SourceResolver.isRejected(first.resourceId))
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
        assertFalse(SourceResolver.isRejected(first.resourceId))
        assertEquals(first, SourceResolver.peek(chapter, "flac24bit", allowSwitch = true))
    }

    @Test fun downloadReportedPrimaryDowngradeDoesNotBeatHighQualityBackup() = runBlocking<Unit> {
        val chapter = chapterSong("download-reported-downgrade")
        store.import("primary.js", source("primary", reportedQuality = "128k"))
        store.import("backup.js", source("backup"))
        store.setEnabled("primary.js", true)
        val result = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.DOWNLOAD)
        assertEquals("flac24bit", result.quality)
        assertTrue(result.resourceId.startsWith("lx:backup.js:"))
    }

    @Test fun downloadQualityChoiceDoesNotPollutePlaybackUrlResolution() = runBlocking<Unit> {
        val chapter = chapterSong("download-then-playback")
        store.import("primary.js", prioritySource("primary", "320k"))
        store.import("backup.js", prioritySource("backup", "flac24bit", responseDelay = 120))
        store.setEnabled("primary.js", true)

        val downloaded = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.DOWNLOAD)
        assertEquals("flac24bit", downloaded.quality)
        assertTrue(downloaded.resourceId.startsWith("lx:backup.js:"))

        // Intentionally keep resolver/script caches intact between purposes.
        assertNull("DOWNLOAD结果不能进入默认PLAYBACK peek缓存",
            SourceResolver.peek(chapter, "flac24bit", allowSwitch = true))
        val playback = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.PLAYBACK)
        assertEquals("320k", playback.quality)
        assertTrue(playback.resourceId.startsWith("lx:primary.js:"))
        assertNotEquals(downloaded.url, playback.url)
    }

    @Test fun playbackPrimaryHrResolutionIsSharedWithDownloadWithoutRefetch() = runBlocking<Unit> {
        val chapter = chapterSong("playback-hr-then-download")
        val primary = store.import("primary.js", prioritySource("primary", "flac24bit"))
        store.setEnabled(primary.id, true)

        val playback = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.PLAYBACK)
        assertEquals("flac24bit", playback.quality)
        assertTrue(playback.resourceId.startsWith("lx:${primary.id}:"))
        val callsAfterPlayback = LxScriptPool.resolve(context, primary.id, "kw", "musicUrl",
            JSONObject().put("type", "probe_count"), 1_000)

        val downloaded = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.DOWNLOAD)

        assertEquals(playback.url, downloaded.url)
        assertEquals(playback.resourceId, downloaded.resourceId)
        val callsAfterDownload = LxScriptPool.resolve(context, primary.id, "kw", "musicUrl",
            JSONObject().put("type", "probe_count"), 1_000)
        assertEquals("满足请求档位的主源缓存应跨purpose共享，不重复请求", callsAfterPlayback, callsAfterDownload)
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
        AudioCacheStore.recordObservedQuality(context, original.resourceId, "128k")
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
        SourceResolver.rejectResource(original.resourceId)
        assertNull(AudioCacheIndex(cache).find(song, "128k", online = true))
    }

    @Test fun cacheMissClearsOnlyItsStaleResolutionBeforeSlowFactoryResolve() = runBlocking<Unit> {
        val suffix = System.nanoTime().toString()
        val target = OnlineSong(JSONObject(song.raw.toString())
            .put("songmid", "factory-$suffix").put("name", "Factory Fixture").put("isBookChapter", true))
        val untouched = OnlineSong(JSONObject(song.raw.toString())
            .put("source", "tx").put("songmid", "untouched-$suffix"))
        val script = store.import("factory-slow-$suffix", source("factory-slow", responseDelay = 1800))
        val scriptId = script.id
        store.setEnabled(scriptId, true)
        LxScriptPool.reload(context)
        SourceResolver.clearCache()

        val cacheField = AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }
        assertNull("factory fixture must start with an uninitialized AudioCacheStore cache", cacheField.get(AudioCacheStore))
        ownsCache = true
        TrackRegistry.register(UiTrack.fromOnline(target))
        TrackRegistry.register(UiTrack.fromOnline(untouched))
        TrackRegistry.notifyResolved(target.uid, "320k", "fixture:old-cache", "320k", "fixture", fromCompleteCache = true)
        TrackRegistry.notifyResolved(untouched.uid, "128k", "fixture:untouched", "128k", "fixture", fromCompleteCache = true)
        val untouchedResolution = checkNotNull(TrackRegistry.resolved(untouched.uid))
        val targetEvents = CopyOnWriteArrayList<TrackRegistry.Resolution?>()
        val targetCleared = CompletableDeferred<Unit>()
        val listener: (String) -> Unit = { uid ->
            if (uid == target.uid) {
                val resolution = TrackRegistry.resolved(uid)
                targetEvents += resolution
                if (resolution == null) targetCleared.complete(Unit)
            }
        }
        TrackRegistry.onResolved(listener)
        val dataSource = MeloraDataSourceFactory(context,
            DataSource.Factory { ByteArrayDataSource("fixture stream".toByteArray()) }).createDataSource()
        val opening = async(Dispatchers.IO) {
            dataSource.open(DataSpec(TrackRegistry.songUri(target.uid)))
        }
        try {
            withTimeout(1_000) { targetCleared.await() }
            delay(100)
            assertTrue("慢源解析仍在进行", opening.isActive)
            assertNull("缓存未命中后应先撤销旧来源", TrackRegistry.resolved(target.uid))
            assertSame("清理目标 UID 不得影响其它 UID 的预加载来源", untouchedResolution,
                TrackRegistry.resolved(untouched.uid))

            val openedLength = withTimeout(8_000) { opening.await() }
            assertTrue(openedLength > 0)
            val resolved = checkNotNull(TrackRegistry.resolved(target.uid))
            assertFalse(resolved.fromCompleteCache)
            assertTrue(resolved.resourceId.orEmpty().startsWith("lx:$scriptId:"))
            assertEquals(listOf(null, resolved), targetEvents.toList())
        } finally {
            opening.cancelAndJoin()
            dataSource.close()
            TrackRegistry.removeResolvedListener(listener)
            TrackRegistry.clearResolved(target.uid)
            TrackRegistry.clearResolved(untouched.uid)
            TrackRegistry.clear()
            store.remove(scriptId)
            LxScriptPool.reload(context)
            SourceResolver.clearCache()
        }
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

    private fun prioritySource(
        name: String,
        reportedQuality: String,
        responseDelay: Int = 0,
        unavailable: Boolean = false,
    ): String = """
        // @name $name
        // @version 1
        lx.send('inited', {sources: {kw: {
            name: 'fixture', actions: ['musicUrl'], qualitys: ['flac24bit', 'flac', '320k', '128k']
        }}});
        let musicUrlCalls = 0;
        lx.on('request', ({info}) => {
            if (info.type === 'probe_count') return 'calls:' + musicUrlCalls;
            musicUrlCalls++;
            const result = ${if (unavailable) "null" else "{url: 'https://example.test/$name-' + info.type + '.flac', type: ${JSONObject.quote(reportedQuality)}}"};
            if ($responseDelay > 0) return new Promise(resolve => setTimeout(() => resolve(result), $responseDelay));
            return Promise.resolve(result);
        });
    """.trimIndent()

    private fun chapterSong(songmid: String) = OnlineSong(JSONObject(song.raw.toString())
        .put("songmid", songmid).put("isBookChapter", true))

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
