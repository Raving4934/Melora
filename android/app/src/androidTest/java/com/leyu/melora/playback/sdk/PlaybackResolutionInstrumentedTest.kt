package com.leyu.melora.playback.sdk

import com.leyu.melora.releaseAudioCacheFixture
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.AudioCacheStore
import com.leyu.melora.playback.AudioCacheIndex
import com.leyu.melora.playback.AudioOutputMonitor
import com.leyu.melora.playback.AudioOutputProbe
import com.leyu.melora.playback.MeloraDataSourceFactory
import com.leyu.melora.playback.PcmEffectsAudioProcessor
import com.leyu.melora.playback.PlaybackRenderersFactory
import com.leyu.melora.playback.TrackRegistry
import com.leyu.melora.playback.UiTrack
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool.CandidateVerdict
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

/** 合成脚本和回环媒体服务，不访问公网或用户的音源、媒体与设置。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackResolutionInstrumentedTest {
    private val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var media: ResolutionMediaServer
    private val store get() = LxScriptStore(context)
    private val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "fixture")
        .put("name", "Fixture").put("singer", "Test Artist").put("_types", JSONObject().put("128k", JSONObject())))

    @Before fun setup() = runBlocking<Unit> {
        media = ResolutionMediaServer()
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        context.getSharedPreferences("lx-sources", 0).edit().clear().commit()
        context.getSharedPreferences(MeloraSettings.PREFS, 0).edit().clear().commit()
        MeloraSettings.init(context)
        LxScriptPool.reload(context)
        SourceResolver.clearCache()
    }

    @After fun cleanup() = runBlocking<Unit> {
        media.close()
        SourceResolver.clearCache()
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        LxScriptPool.reload(context)
        context.getSharedPreferences("lx-sources", 0).edit().clear().commit()
        context.getSharedPreferences(MeloraSettings.PREFS, 0).edit().clear().commit()
        releaseAudioCacheFixture()
        context.root.deleteRecursively()
    }

    @Test fun readySourceDoesNotWaitForSlowSiblingInitialization() = runBlocking<Unit> {
        store.import("slow.js", source("slow", initDelay = 2000))
        store.import("fast.js", source("fast"))
        val started = android.os.SystemClock.elapsedRealtime()
        val result = LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ALL, 900,
            request = { script, _ -> LxScriptPool.ScriptRequest("320k", script.id, "kw", "musicUrl",
                JSONObject().put("type", "320k"), 900) },
            accept = { CandidateVerdict.Accepted(it) })
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
                accept = { CandidateVerdict.Accepted(it) })
            assertEquals("fast.js", result?.scriptId)
        } finally { reload.cancelAndJoin() }
    }

    @Test fun repeatedlyInvalidPrimaryIsSkippedForHealthyBackupOnTheFourthTrack() = runBlocking<Unit> {
        prepareCandidateHealthSources()
        repeat(4) { index ->
            media.requests.clear()
            val songmid = "page-${index + 1}"
            resolveWithHealthyBackup(songmid)
            val expected = if (index < 3) listOf("GET /$songmid-candidate.flac") else emptyList()
            assertEquals(expected + "GET /verified-flac24bit.flac", media.requests.toList())
        }
        media.requests.clear()
        val singleSource = SourceResolver.resolve(context, candidateHealthSong("verified-single-source"),
            "flac24bit", allowSwitch = false)
        assertEquals("${media.url}/verified-single-source-candidate.flac", singleSource.url)
        assertTrue("关闭换源时单源解析仍交给播放器", media.requests.isEmpty())

        for ((songmid, backupRequests) in listOf(
            "verified-recovery" to listOf("GET /page-backup-flac24bit.flac"),
            "verified-primary-again" to emptyList(),
        )) {
            media.requests.clear()
            val result = SourceResolver.resolve(context, candidateHealthSong(songmid), "flac24bit", allowSwitch = true)
            val path = "/$songmid-candidate.flac"
            assertEquals("${media.url}$path", result.url)
            assertEquals(SourceResolver.scriptResourceId("candidate.js", result.url), result.resourceId)
            assertEquals("flac24bit", result.effectiveQuality)
            assertEquals(backupRequests + "GET $path", media.requests.toList())
        }
    }

    @Test fun recoveredPrimaryCanWinWhileThePreferredBackupStalls() = runBlocking<Unit> {
        prepareCandidateHealthSources()
        repeat(3) { resolveWithHealthyBackup("page-${it + 1}") }

        media.requests.clear()
        val result = SourceResolver.resolve(context, candidateHealthSong("verified-stalled-backup"),
            "flac24bit", allowSwitch = true)
        assertEquals("${media.url}/verified-stalled-backup-candidate.flac", result.url)
        assertEquals(SourceResolver.scriptResourceId("candidate.js", result.url), result.resourceId)
        assertEquals("flac24bit", result.effectiveQuality)
        assertEquals(listOf("GET /verified-stalled-backup-candidate.flac"), media.requests.toList())
        assertEquals("stalls:1", LxScriptPool.resolve(context, "backup.js", "kw", "musicUrl",
            JSONObject().put("type", "stall_count"), 1_000))
    }

    @Test fun singleSourcePlaybackClearsFailuresAndCacheReplaysDoNotEraseNewFailures() = runBlocking<Unit> {
        preparePlaybackHealthSources(withBackup = false)
        recordPlaybackHealthFailure(1)
        recordPlaybackHealthFailure(2)
        val chapter = chapterSong("verified-unprobed")
        withHealthPlaybackSettings(allowSwitch = false) {
            playAndObserveOutput(chapter) { resolution ->
                assertFalse(resolution.fromCompleteCache)
                assertTrue(resolution.resourceId.orEmpty().startsWith("lx:feedback-primary.js:"))
                assertTrue("单源播放无需前缀探测", media.prefixRequests.isEmpty())
            }
            val resolved = checkNotNull(SourceResolver.peek(chapter, "flac24bit", allowSwitch = false))
            installPlaybackHealthBackup()
            recordPlaybackHealthFailure(3)
            assertFalse("实际播放后的一次失败不能触发冷却", prefersHealthBackup())

            recordPlaybackHealthFailure(4)
            recordPlaybackHealthFailure(5)
            assertTrue("之后连续三次失败应开始冷却", prefersHealthBackup())

            media.requests.clear()
            playAndObserveOutput(chapter) { resolution ->
                assertTrue("回放应直接命中完整磁盘缓存", resolution.fromCompleteCache)
                assertEquals(resolved.resourceId, resolution.resourceId)
            }
            assertTrue("完整缓存播放无需联网", media.requests.isEmpty())
            assertTrue("旧磁盘缓存播放不能清除近期失败", prefersHealthBackup())

            AudioCacheStore.clearAll(context)
            assertEquals(resolved, SourceResolver.peek(chapter, "flac24bit", allowSwitch = false))
            playAndObserveOutput(chapter) { resolution ->
                assertFalse(resolution.fromCompleteCache)
                assertEquals(resolved.resourceId, resolution.resourceId)
            }
            assertTrue("URL 缓存回放也实际请求了原媒体", "GET /verified-unprobed.flac" in media.requests)
            assertTrue("已经确认的旧解析再次播放不能清除近期失败", prefersHealthBackup())
        }
    }

    @Test fun actualPlaybackRestoresCoolingPrimaryWhenMediaPrefixTimesOut() = runBlocking<Unit> {
        preparePlaybackHealthSources(withBackup = true)
        repeat(3) { recordPlaybackHealthFailure(it + 1) }
        assertTrue(prefersHealthBackup())
        val chapter = chapterSong("verified-slow-probe-recovery")
        withHealthPlaybackSettings(allowSwitch = true) {
            playAndObserveOutput(chapter) { resolution ->
                assertFalse(resolution.fromCompleteCache)
                assertTrue(resolution.resourceId.orEmpty().startsWith("lx:feedback-primary.js:"))
                assertEquals(listOf("/verified-slow-probe-recovery.flac"), media.prefixRequests.toList())
                assertTrue("探测超时后仅准备音轨不能解除冷却", prefersHealthBackup())
            }
        }
        assertFalse("同一主源实际输出后应恢复优先级", prefersHealthBackup())
        val next = SourceResolver.resolve(context, chapterSong("verified-after-recovery"), "flac24bit", allowSwitch = true)
        assertTrue(next.resourceId.startsWith("lx:feedback-primary.js:"))
    }

    @Test fun preloadedOccurrenceWithSameSongIdNeedsItsOwnAudioOutput() = runBlocking<Unit> {
        preparePlaybackHealthSources(withBackup = true)
        val chapter = chapterSong("verified-duplicate-occurrence")
        // 两个媒体项实际加载不同的音频地址，避免完整磁盘缓存短路第二次解析。
        val factory = MeloraDataSourceFactory(context, DefaultHttpDataSource.Factory())
        withHealthPlaybackSettings(allowSwitch = false) {
            withPreparedHealthPlayer(chapter, factory) { first ->
                val originalResource = checkNotNull(TrackRegistry.resolved(chapter.uid)?.resourceId)
                assertEquals("${media.url}/verified-duplicate-occurrence-1.flac",
                    TrackRegistry.cacheResourceFor(chapter.uid, first.uri)?.sourceUrl)
                withContext(Dispatchers.Main) { first.player.play() }
                repeat(3) { recordPlaybackHealthFailure(it + 1) }
                assertTrue(prefersHealthBackup())
                SourceResolver.clearCache()

                withPreparedHealthPlayer(chapter, factory) { second ->
                    assertNotEquals(first.uri, second.uri)
                    val preloadedResource = checkNotNull(TrackRegistry.resolved(chapter.uid)?.resourceId)
                    assertNotEquals("预读要更新同 UID 的解析快照", originalResource, preloadedResource)
                    assertNull("首项不能为同 UID 的预读项补齐缓存", TrackRegistry.cacheResourceFor(chapter.uid, first.uri))
                    assertEquals("${media.url}/verified-duplicate-occurrence-2.flac",
                        TrackRegistry.cacheResourceFor(chapter.uid, second.uri)?.sourceUrl)
                    assertNull(TrackRegistry.cacheResourceFor(chapter.uid, null))
                    assertTrue("两项都应已打开独立的媒体地址",
                        listOf("GET /verified-duplicate-occurrence-1.flac", "GET /verified-duplicate-occurrence-2.flac")
                            .all { it in media.requests })

                    first.observeAudioOutput()
                    assertTrue("首项的音频输出不能替尚未播放的同 UID 项解除冷却", prefersHealthBackup())

                    withContext(Dispatchers.Main) { first.player.pause() }
                    second.observeAudioOutput()
                    assertFalse("预读项真正持续输出后才应解除冷却", prefersHealthBackup())
                }
            }
        }
    }

    @Test fun verifiedLowerTierClearsFailuresWithoutWinningTheRequestedTier() = runBlocking<Unit> {
        prepareCandidateHealthSources()
        resolveWithHealthyBackup("page-first")
        resolveWithHealthyBackup("page-second")
        resolveWithHealthyBackup("lower-full-length")
        assertTrue("完整低档应经过实际音频探测", "GET /lower-full-length-candidate.flac" in media.requests)
        resolveWithHealthyBackup("page-third")
        assertEquals("candidate.js", requestHealthCandidate()?.scriptId)
    }

    @Test fun previewAndExcludedResourcesKeepProviderHealthUnchanged() = runBlocking<Unit> {
        prepareCandidateHealthSources()
        resolveWithHealthyBackup("page-first")
        resolveWithHealthyBackup("page-second")
        resolveWithHealthyBackup("preview-first")
        val excluded = SourceResolver.scriptResourceId("candidate.js", "${media.url}/verified-excluded-candidate.flac")
        resolveWithHealthyBackup("verified-excluded", setOf(excluded))
        assertTrue("试听应经过实际音频探测", "GET /preview-first-candidate.flac" in media.requests)
        assertFalse("本轮排除的资源无需发出探测", "GET /verified-excluded-candidate.flac" in media.requests)
        assertEquals("试听和排除不能触发熔断", "candidate.js", requestHealthCandidate()?.scriptId)
        resolveWithHealthyBackup("page-third")
        assertNull("试听和排除不能清除已有失败", requestHealthCandidate())
    }

    @Test fun cancellingColdSourceInitializationAfterGcCanReleaseAndReloadRuntimes() = runBlocking<Unit> {
        repeat(3) {
            store.import("slow.js", source("slow", initDelay = 1000))
            val resolving = async {
                LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ALL, 1500,
                    request = { script, _ -> LxScriptPool.ScriptRequest("320k", script.id, "kw", "musicUrl",
                        JSONObject().put("type", "320k"), 1500) }, accept = { CandidateVerdict.Accepted(it) })
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

    @Test fun quickHtmlResultCannotWinOrSeedTheLowerQualityCache() = runBlocking<Unit> {
        for (reported in listOf("320k", "128k")) {
            media.requests.clear()
            val page = if (reported == "320k") "page" else "opaque-page"
            store.import("page.js", source(page, reportedQuality = reported))
            store.import("music.js", source("music", responseDelay = 800))
            store.setEnabled("page.js", true)
            val song = chapterSong("page-before-media-$reported")
            val result = SourceResolver.resolve(context, song, "320k", allowSwitch = true)
            assertEquals("${media.url}/music-320k.flac", result.url)
            assertEquals(result, SourceResolver.peek(song, "320k", allowSwitch = true))
            assertEquals(1, media.requests.count { it == "GET /$page-320k.flac" })
            assertEquals(1, media.requests.count { it == "GET /music-320k.flac" })
        }
    }

    @Test fun failedOrUnsupportedMediaProbeLeavesPlaybackToThePlayer() = runBlocking<Unit> {
        for (name in listOf("head-page", "get-only", "range-unavailable")) {
            store.import("music.js", source(name))
            store.import("slow.js", source("slow", responseDelay = 1500))
            store.setEnabled("music.js", true)
            val result = SourceResolver.resolve(context, chapterSong(name), "320k", allowSwitch = true)
            assertEquals("${media.url}/$name-320k.flac", result.url)
            assertEquals(1, media.requests.count { it == "GET /$name-320k.flac" })
        }
        assertTrue(media.requests.none { it.startsWith("HEAD ") })
    }

    @Test fun coldSinglePlatformSourceResolvesWithoutMediaProbeEvenWithAutoSwitch() = runBlocking<Unit> {
        store.import("primary.js", source("verified", initDelay = 900))
        store.setEnabled("primary.js", true)
        val track = candidateHealthSong("cold-single-source")
        // 冷启动可能先检查其它平台。预填目录缓存，确保它只访问回环媒体服务。
        for (platform in listOf("kg", "wy", "tx", "mg")) {
            OnlineCache.put(SourceResolver.matchingCacheKey(track, platform), emptyList<OnlineSong>())
        }

        val result = SourceResolver.resolve(context, track, "flac24bit", allowSwitch = true)
        assertEquals("${media.url}/verified-flac24bit.flac", result.url)
        assertEquals("flac24bit", result.effectiveQuality)
        assertTrue("单源直出无需 Range 探测：${media.requests}", media.requests.isEmpty())
    }

    @Test fun candidateProbeMeasuresFlacAndMp3AndLeavesUnverifiedDurationUnknown() = runBlocking<Unit> {
        val flac = checkNotNull(AudioCacheStore.probePlaybackCandidate("${media.url}/verified-audio"))
        assertEquals(24, flac.spec?.bitDepth)
        assertEquals(12_000L, flac.durationMs)

        val short = checkNotNull(AudioCacheStore.probePlaybackCandidate("${media.url}/preview-audio"))
        assertEquals("audio/mpeg", short.spec?.mimeType)
        // Media3 用含编码填充的音频字节数和剪除填充后的可听时长计算平均码率。
        val shortSpec = checkNotNull(short.spec) { "MP3 试听规格：$short" }
        assertTrue("MP3 试听规格：$short", shortSpec.bitrate in 120_000..140_000)
        assertEquals("MP3 试听档位：$short", SourceResolver.qualityRank("128k"),
            SourceResolver.qualityRank(shortSpec.verifiedQuality.orEmpty()))
        assertTrue("MP3 试听时长：$short", short.durationMs?.let { it in 990L..1_100L } == true)

        val unindexed = checkNotNull(AudioCacheStore.probePlaybackCandidate("${media.url}/unindexed-audio"))
        assertEquals("audio/mpeg", unindexed.spec?.mimeType)
        assertEquals("无索引 MP3 规格：$unindexed", -1, unindexed.spec?.bitrate)
        assertNull("无索引 MP3 时长：$unindexed", unindexed.durationMs)
        assertEquals(listOf("GET /verified-audio", "GET /preview-audio", "GET /unindexed-audio"), media.requests)
    }

    @Test fun highTierCanFinishAfterFormer128kHedgeWouldHaveWon() = runBlocking<Unit> {
        store.import("primary.js", source("primary", responseDelay = 1800))
        store.setEnabled("primary.js", true)
        val result = SourceResolver.resolve(context, song, "flac24bit", allowSwitch = false)
        assertEquals("flac24bit", result.quality)
        assertTrue("单源直出不应预检", media.requests.isEmpty())
        assertTrue(result.resourceId.startsWith("lx:primary.js:"))
    }

    @Test fun playbackWaitsForTheRequestedTierWhenThePrimaryReportsADowngrade() = runBlocking<Unit> {
        val chapter = chapterSong("playback-highest-tier")
        store.import("primary.js", prioritySource("primary", "320k"))
        store.import("backup.js", prioritySource("backup", "flac24bit", responseDelay = 1200))
        store.setEnabled("primary.js", true)

        val result = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true)
        assertEquals("flac24bit", result.quality)
        assertTrue(result.resourceId.startsWith("lx:backup.js:"))
    }

    @Test fun neteasePlaylistUrlOnlyDowngradeAndPreviewCannotBeatFullLengthHiRes() = runBlocking<Unit> {
        val playlistSong = OnlineSong(JSONObject(song.raw.toString()).put("source", "wy")
            .put("songmid", "123456").put("interval", "00:12").put("albumName", "Fixture Album"))
        val track = requireNotNull(OnlineSong.from(UiTrack.fromOnline(playlistSong).raw))
        assertEquals(12, track.intervalSeconds)
        for (name in listOf("preview", "lower")) {
            SourceResolver.clearCache()
            store.import("fast.js", source(name, urlOnly = true, platform = "wy"))
            store.import("full.js", source("verified", responseDelay = 900, platform = "wy"))
            store.setEnabled("fast.js", true)
            val resolved = SourceResolver.resolve(context, track, "flac24bit", allowSwitch = true)
            assertTrue("先返回的 $name 不能抢占完整 24-bit 文件", resolved.resourceId.startsWith("lx:full.js:"))
            assertEquals("flac24bit", resolved.quality)
            assertEquals(resolved, SourceResolver.peek(track, "flac24bit"))
        }
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

    @Test fun latePrimaryDowngradeCannotCancelAnInFlightHigherTierBackup() = runBlocking<Unit> {
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
        assertTrue(result.resourceId.startsWith("lx:backup.js:"))
        assertEquals("flac24bit", result.quality)
        assertTrue("同档备源应在预算内接管（${elapsed}ms）", elapsed < 2500)
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
            assertTrue(failed.url.startsWith("${media.url}/shared-$suffix-"))

            SourceResolver.rejectResource(failed.resourceId)
            assertTrue(SourceResolver.isRejected(failed.resourceId))
            val recovered = SourceResolver.resolve(context, nextSong, "320k", allowSwitch = true)
            assertTrue("另一 UID 不得再次解析命中已拒绝 URL", recovered.resourceId.startsWith("lx:${backup.id}:"))
            assertNotEquals(failed.resourceId, recovered.resourceId)
            assertTrue("未被拒绝的备用 URL 仍可解析", recovered.url.startsWith("${media.url}/good-$suffix-"))
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
        val generation = SourceResolver.cacheGeneration
        val candidate = SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true,
            purpose = SourceResolver.Purpose.REBUFFER, excludedResources = setOf(first.resourceId))
        assertEquals("flac24bit", candidate.quality)
        assertTrue(candidate.resourceId.startsWith("lx:backup.js:"))
        assertFalse(SourceResolver.isRejected(first.resourceId))
        assertEquals(first, SourceResolver.peek(chapter, "flac24bit", allowSwitch = true))
        assertEquals(first, SourceResolver.resolve(context, chapter, "flac24bit", allowSwitch = true))
        assertTrue(SourceResolver.selectForRebuffer(chapter, "flac24bit", candidate, generation))
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
        assertEquals("flac24bit", playback.quality)
        assertEquals(downloaded.resourceId, playback.resourceId)
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

    private suspend fun prepareCandidateHealthSources() {
        store.import("candidate.js", """
            lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['flac24bit']}}});
            lx.on('request', ({info}) => '${media.url}/' + info.musicInfo.songmid + '-candidate.flac');
        """.trimIndent())
        store.import("backup.js", """
            lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['flac24bit']}}});
            let stalls = 0;
            lx.on('request', ({info}) => {
                if (info.type === 'stall_count') return 'stalls:' + stalls;
                const songmid = info.musicInfo.songmid;
                const path = songmid === 'verified-recovery' ? 'page-backup' : 'verified';
                const url = '${media.url}/' + path + '-' + info.type + '.flac';
                if (songmid === 'verified-stalled-backup') {
                    stalls++;
                    return new Promise(resolve => setTimeout(() => resolve(url), 10_000));
                }
                return url;
            });
        """.trimIndent())
        store.setEnabled("candidate.js", true)
        assertEquals(2, LxScriptPool.reload(context))
    }

    private suspend fun preparePlaybackHealthSources(withBackup: Boolean) {
        store.import("feedback-primary.js", """
            lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['flac24bit']}}});
            let duplicateCount = 0;
            lx.on('request', ({info}) => {
                const songmid = info.musicInfo.songmid;
                if (songmid.startsWith('feedback-failure-')) throw new Error('temporary network failure');
                const suffix = songmid === 'verified-duplicate-occurrence' ? '-' + (++duplicateCount) : '';
                return '${media.url}/' + songmid + suffix + '.flac';
            });
        """.trimIndent())
        store.setEnabled("feedback-primary.js", true)
        if (withBackup) installPlaybackHealthBackup() else LxScriptPool.reload(context)
    }

    private suspend fun installPlaybackHealthBackup() {
        store.import("feedback-backup.js", """
            lx.send('inited', {sources: {kw: {actions: ['musicUrl'], qualitys: ['flac24bit']}}});
            lx.on('request', ({info}) => {
                if (info.musicInfo.songmid.startsWith('verified-slow-probe-')) {
                    throw Object.assign(new Error('no matching track'), {code: 'MUSIC_UNAVAILABLE'});
                }
                return '${media.url}/verified-backup.flac';
            });
        """.trimIndent())
        assertEquals(listOf("feedback-backup.js"), LxScriptPool.scriptsFor(context, "kw",
            scope = LxScriptPool.ScriptScope.DISABLED).map { it.first.id })
    }

    private suspend fun recordPlaybackHealthFailure(index: Int) {
        val result = runCatching {
            SourceResolver.resolve(context, chapterSong("feedback-failure-$index"), "flac24bit", allowSwitch = false)
        }
        assertTrue("主源应发生第 $index 次解析失败：$result", result.exceptionOrNull() is IllegalStateException)
    }

    private suspend fun prefersHealthBackup(): Boolean =
        LxScriptPool.shouldPreferBackup(context, "kw") { "flac24bit" in it.qualitys }

    private suspend fun withHealthPlaybackSettings(allowSwitch: Boolean, test: suspend () -> Unit) {
        val settings = mapOf(
            MeloraSettings.playQualityWifi to MeloraSettings.playQualityWifi.value,
            MeloraSettings.playQualityMobile to MeloraSettings.playQualityMobile.value,
        )
        val originalSwitch = MeloraSettings.autoSwitchSource.value
        settings.keys.forEach { it.value = "flac24bit" }
        MeloraSettings.autoSwitchSource.value = allowSwitch
        try {
            test()
        } finally {
            settings.forEach { (setting, value) -> setting.value = value }
            MeloraSettings.autoSwitchSource.value = originalSwitch
        }
    }

    private suspend fun playAndObserveOutput(
        song: OnlineSong,
        onPrepared: suspend (TrackRegistry.Resolution) -> Unit,
    ) = withPreparedHealthPlayer(song, AudioCacheStore.playbackDataSourceFactory(context)) { fixture ->
        onPrepared(checkNotNull(TrackRegistry.resolved(song.uid)))
        fixture.observeAudioOutput()
    }

    private suspend fun withPreparedHealthPlayer(
        song: OnlineSong,
        dataSource: DataSource.Factory,
        test: suspend (HealthPlayer) -> Unit,
    ) {
        val uri = TrackRegistry.songUri(song.uid)
        val renderers = PlaybackRenderersFactory(context, PcmEffectsAudioProcessor())
        val fixture = withContext(Dispatchers.Main) {
            val player = ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build()
            player.setAudioAttributes(AudioAttributes.DEFAULT, false)
            player.volume = 0f
            HealthPlayer(player, AudioOutputMonitor(player, renderers.outputProbe), renderers.outputProbe, uri)
        }
        try {
            TrackRegistry.register(UiTrack.fromOnline(song))
            withContext(Dispatchers.Main) {
                val player = fixture.player
                player.setMediaItem(MediaItem.Builder().setMediaId(song.uid).setUri(uri).build())
                player.prepare()
                withTimeout(7_000L) {
                    while (player.playbackState != Player.STATE_READY) {
                        assertNull(player.playerError)
                        delay(50L)
                    }
                }
                assertFalse(player.isPlaying)
            }
            test(fixture)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                fixture.monitor.close()
                fixture.player.release()
            }
            TrackRegistry.clearResolved(song.uid)
        }
    }

    private class HealthPlayer(
        val player: ExoPlayer,
        val monitor: AudioOutputMonitor,
        private val outputProbe: AudioOutputProbe,
        val uri: android.net.Uri,
    ) {
        suspend fun observeAudioOutput() = withContext(Dispatchers.Main) {
            player.play()
            withTimeout(8_000L) {
                var previous = outputProbe.snapshot
                var progressing = 0
                while (progressing < 5) {
                    // 先读上一轮已完成的采样，避免本轮异步消息抢先更新后下轮重复计数。
                    val output = outputProbe.snapshot
                    monitor.check()
                    assertNull(player.playerError)
                    progressing = if (player.isPlaying && output.generation == previous.generation &&
                        output.playedFrames > previous.playedFrames) progressing + 1 else 0
                    previous = output
                    if (progressing < 5) delay(500L)
                }
            }
        }
    }

    private fun candidateHealthSong(songmid: String) = OnlineSong(JSONObject(song.raw.toString())
        .put("songmid", songmid).put("interval", "00:12"))

    private suspend fun resolveWithHealthyBackup(songmid: String, excludedResources: Set<String> = emptySet()) {
        val result = SourceResolver.resolve(context, candidateHealthSong(songmid), "flac24bit",
            allowSwitch = true, excludedResources = excludedResources)
        assertEquals("${media.url}/verified-flac24bit.flac", result.url)
        assertEquals("flac24bit", result.effectiveQuality)
    }

    private suspend fun requestHealthCandidate() = LxScriptPool.race(
        context, "kw", LxScriptPool.ScriptScope.ALL, 2_000,
        request = { script, _ ->
            val info = JSONObject().put("type", "flac24bit")
                .put("musicInfo", candidateHealthSong("verified-health-check").raw)
            LxScriptPool.ScriptRequest("flac24bit", script.id, "kw", "musicUrl", info, 1_000)
        },
        // 同档备用源可调度。只观察候选是否获准请求，避免在检查时更新健康状态。
        accept = { if (it.scriptId == "candidate.js") CandidateVerdict.Accepted(it) else CandidateVerdict.Skipped },
    )

    private fun source(name: String, initDelay: Int = 0, responseDelay: Int = 0, reportedQuality: String? = null, urlOnly: Boolean = false, platform: String = "kw"): String = """
        // @name $name
        // @version 1
        const initialize = () => lx.send('inited', {sources: {$platform: {
            name: 'fixture', actions: ['musicUrl'], qualitys: ['flac24bit', 'flac', '320k', '128k']
        }}});
        if ($initDelay > 0) setTimeout(initialize, $initDelay); else initialize();
        lx.on('request', ({info}) => new Promise(resolve => {
            const result = {url: '${media.url}/$name-' + info.type + '.flac',
                type: ${reportedQuality?.let(JSONObject::quote) ?: "info.type"}};
            const payload = $urlOnly ? result.url : result;
            if ($responseDelay > 0) setTimeout(() => resolve(payload), $responseDelay); else resolve(payload);
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
            const result = ${if (unavailable) "null" else "{url: '${media.url}/$name-' + info.type + '.flac', type: ${JSONObject.quote(reportedQuality)}}"};
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
