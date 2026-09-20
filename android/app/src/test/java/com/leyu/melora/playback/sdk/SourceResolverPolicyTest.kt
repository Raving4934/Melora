package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceResolverPolicyTest {
    @Test
    fun downloadsTryEachTierInOrderAndKeepActualQuality() = runBlocking {
        for (preferred in listOf("flac24bit", "hires")) {
            val attempts = mutableListOf<String>()
            val result = SourceResolver.resolveTiers(preferred, SourceResolver.Purpose.DOWNLOAD) { quality ->
                attempts += quality
                if (quality != "320k") error("unsupported $quality")
                SourceResolver.Resolved("https://example.com/audio.mp3", quality, song(), false, "actual-320k")
            }
            assertEquals(listOf(preferred, "flac", "320k"), attempts)
            assertEquals("320k", result.quality)
            assertEquals("actual-320k", result.resourceId)
        }
    }

    @Test
    fun downloadsNeverProbeLowerTiersAfterTheSelectedTierSucceeds() = runBlocking {
        var calls = 0
        val result = SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.DOWNLOAD) { quality ->
            calls++
            assertEquals("flac24bit", quality)
            SourceResolver.Resolved("https://example.com/audio.flac", "hires", song(), false, "actual-hires")
        }
        assertEquals(1, calls)
        assertEquals("hires", result.quality)
    }

    @Test
    fun downloadTimeoutAtHigherTierStillReaches128k() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = SourceResolver.resolveTiers("hires", SourceResolver.Purpose.DOWNLOAD, tierBudgetMs = 30) { quality ->
            attempts += quality
            if (quality != "128k") delay(1000)
            SourceResolver.Resolved("https://example.com/audio.mp3", quality, song(), false, "actual-128k")
        }
        assertEquals(listOf("hires", "flac", "320k", "128k"), attempts)
        assertEquals("128k", result.quality)
    }

    @Test
    fun downloadCancellationDoesNotStartLowerTierAndAllFailuresAreReported() = runBlocking {
        val attempts = mutableListOf<String>()
        try {
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.DOWNLOAD) { quality ->
                attempts += quality
                throw CancellationException("user cancelled")
            }
            error("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("flac24bit"), attempts)
        attempts.clear()
        try {
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.DOWNLOAD) { quality -> attempts += quality; error("offline") }
            error("must report failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("各档下载音质"))
        }
        assertEquals(listOf("flac24bit", "flac", "320k", "128k"), attempts)
    }

    @Test
    fun scriptResultRetainsActualQualityPlatformAndStableIdentity() {
        val original = song("wy")
        val matched = song("kw")
        fun resolved(url: String, identity: String = "file-a", candidate: OnlineSong = matched) = SourceResolver.scriptResolution(original,
            LxScriptPool.ScriptResult("flac24bit", "user-source.js", JSONObject().put("url", url).put("type", "flac")
                .put("source", candidate.source).put("musicInfo", candidate.raw).put("resourceId", identity)))
        val first = requireNotNull(resolved("https://example.com/file?token=one"))
        assertEquals("flac", first.quality)
        assertEquals("kw", first.song.source)
        assertTrue(first.switched)
        assertEquals(first.resourceId, resolved("https://example.com/file?token=two")?.resourceId)
        assertNotEquals(first.resourceId, resolved("https://example.com/file?token=two", "file-b")?.resourceId)
        assertNull(resolved("https://example.com/wrong", candidate = song("kw", singer = "Someone Else")))
    }

    @Test
    fun legacyUrlOnlyScriptIsStillSupportedWithoutInventingActualPlatform() {
        val original = song("wy")
        val result = SourceResolver.scriptResolution(original, LxScriptPool.ScriptResult("320k", "old.js", "https://example.com/a.mp3"))!!
        assertEquals(original.uid, result.song.uid)
        assertFalse(result.switched)
        assertEquals("320k", result.quality)
    }

    @Test
    fun scriptCacheIdentityDoesNotMixProvidersOrDifferentFiles() {
        val first = SourceResolver.scriptResourceId("a", "https://cdn.test/song.mp3?file=1")
        assertEquals(first, SourceResolver.scriptResourceId("a", "https://cdn.test/song.mp3?file=1"))
        assertNotEquals(first, SourceResolver.scriptResourceId("b", "https://cdn.test/song.mp3?file=1"))
        assertNotEquals(first, SourceResolver.scriptResourceId("a", "https://cdn.test/song.mp3?file=2"))
        assertNotEquals(first, SourceResolver.scriptResourceId("a", "https://cdn.test/song.aac?file=1"))
    }

    @Test
    fun allPurposesShareTheFullDescendingQualityLadder() {
        assertEquals(listOf("flac24bit", "flac", "320k", "128k"), SourceResolver.qualityAttempts("flac24bit"))
        assertEquals(listOf("flac", "320k", "128k"), SourceResolver.qualityAttempts("flac"))
        assertEquals(listOf("320k", "128k"), SourceResolver.qualityAttempts("320k"))
        assertEquals(listOf("320k", "128k"), SourceResolver.qualityAttempts("legacy-unknown"))
        assertEquals(listOf("128k"), SourceResolver.qualityAttempts("128k"))
    }

    @Test
    fun highResolutionAliasUsesTheActualNameDeclaredByEachScript() {
        assertEquals(
            "hires",
            SourceResolver.selectQuality("flac24bit", listOf("128k", "hires")),
        )
        assertEquals(
            "master",
            SourceResolver.selectQuality("flac24bit", listOf("master")),
        )
        assertNull(SourceResolver.selectQuality("flac24bit", listOf("flac", "320k")))
    }

    @Test
    fun downloadQualityFloorRejectsSilentFlacFallback() {
        assertEquals(true, SourceResolver.qualitySatisfies("flac24bit", "hires"))
        assertEquals(true, SourceResolver.qualitySatisfies("flac24bit", "master"))
        assertEquals(false, SourceResolver.qualitySatisfies("flac24bit", "flac"))
        assertEquals(true, SourceResolver.qualitySatisfies("flac", "flac24bit"))
        assertEquals(false, SourceResolver.qualitySatisfies("320k", "128k"))
    }

    @Test
    fun extractUrlRejectsEmptyAndBusinessOnlyResults() {
        assertNull(SourceResolver.extractUrl(""))
        assertNull(SourceResolver.extractUrl(JSONObject().put("code", 200)))
        assertNull(SourceResolver.extractUrl(JSONObject().put("data", JSONObject().put("message", "ok"))))
    }

    @Test
    fun extractUrlAcceptsDirectAndNestedPlaybackUrls() {
        assertEquals("https://cdn.test/song.mp3", SourceResolver.extractUrl(" https://cdn.test/song.mp3 "))
        assertEquals(
            "http://cdn.test/song.flac",
            SourceResolver.extractUrl(JSONObject().put("data", JSONObject().put("play_url", "http://cdn.test/song.flac"))),
        )
    }

    @Test
    fun alternativeCacheKeyIsolatesSourceAlbumAndDuration() {
        val base = song(source = "kw", album = "Album A", interval = "03:30")

        assertNotEquals(
            SourceResolver.alternativeCacheKey(base),
            SourceResolver.alternativeCacheKey(song(source = "kg", album = "Album A", interval = "03:30")),
        )
        assertNotEquals(
            SourceResolver.alternativeCacheKey(base),
            SourceResolver.alternativeCacheKey(song(source = "kw", album = "Album B", interval = "03:30")),
        )
        assertNotEquals(
            SourceResolver.alternativeCacheKey(base),
            SourceResolver.alternativeCacheKey(song(source = "kw", album = "Album A", interval = "03:31")),
        )
    }

    @Test
    fun alternativeScoreRequiresEvidenceBeyondSameName() {
        val original = song(singer = "Singer A", album = "", interval = "00:00")
        val missingSinger = song(source = "kg", singer = "", album = "", interval = "00:00")

        assertNull(SourceResolver.alternativeScore(original, missingSinger))
    }

    @Test
    fun alternativeScoreKeepsExistingWeightsAndRejectsDifferentNames() {
        val original = song(name = "Song A", singer = "Singer A", album = "Album A", interval = "03:30")
        val exact = song(source = "kg", name = "Song A", singer = "Singer A", album = "Album A", interval = "03:34")
        val differentName = song(
            source = "kg",
            name = "Song B",
            singer = "Singer A",
            album = "Album A",
            interval = "03:30",
        )

        assertEquals(16, SourceResolver.alternativeScore(original, exact))
        assertNull(SourceResolver.alternativeScore(original, differentName))
    }

    @Test
    fun singleFlightSurvivesFirstWaiterCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val singleFlight = SingleFlight<String, String>(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first = launch {
                singleFlight.run("song") {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    "resolved"
                }
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                singleFlight.run("song") {
                    error("同一 key 不应启动第二个解析任务")
                }
            }

            first.cancelAndJoin()
            release.complete(Unit)

            assertEquals("resolved", second.await())
            assertEquals(1, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun singleFlightEvictsFailedRequestBeforeRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val singleFlight = SingleFlight<String, String>(scope)
            val calls = AtomicInteger()
            try {
                singleFlight.run("song") {
                    calls.incrementAndGet()
                    error("first failure")
                }
            } catch (_: IllegalStateException) {
                // 首次失败应从在途表移除，允许下一次真实重试。
            }

            val result = singleFlight.run("song") {
                calls.incrementAndGet()
                "resolved"
            }

            assertEquals("resolved", result)
            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun singleFlightFenceCancelsRetiredTaskAndRebuildsSameKey() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val singleFlight = SingleFlight<String, String>(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val retired = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    singleFlight.run("song") {
                        calls.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        "retired"
                    }
                    false
                } catch (_: CancellationException) {
                    true
                }
            }

            started.await()
            singleFlight.fence()

            assertEquals("rebuilt", singleFlight.run("song") {
                calls.incrementAndGet()
                "rebuilt"
            })

            assertTrue(retired.await())
            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fencedGenerationCannotRegisterAfterClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<String, String>(scope)
            val generation = flight.currentGeneration()
            flight.fence()
            try {
                flight.run("song", generation) { error("retired generation must not start") }
                error("expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals("new", flight.run("song") { "new" })
        } finally { scope.cancel() }
    }

    @Test
    fun clearedUrlCacheRejectsLateWritesButAcceptsCurrentGeneration() {
        SourceResolver.clearCache()
        try {
            val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "cache-fence"))
            val key = SourceResolver.ResolveKey(song.uid, "320k", true)
            val resolved = SourceResolver.Resolved("https://example.com/audio.mp3", "320k", song, false, "resource")
            val flight = SourceResolver.javaClass.getDeclaredField("inFlight").apply { isAccessible = true }
                .get(SourceResolver) as SingleFlight<*, *>
            val generation = flight.currentGeneration()
            val write = SourceResolver.javaClass.declaredMethods.single { it.name == "cache" }.apply { isAccessible = true }
            SourceResolver.clearCache()
            write.invoke(SourceResolver, key, resolved, generation)
            assertNull(SourceResolver.peek(song, "320k"))
            write.invoke(SourceResolver, key, resolved, flight.currentGeneration())
            assertEquals(resolved, SourceResolver.peek(song, "320k"))
        } finally { SourceResolver.clearCache() }
    }

    @Test
    fun parallelMapRunsConcurrentlyButPreservesSourceOrder() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        val result = parallelMapWithinBudget(listOf("slow", "fast"), budgetMs = 1_000) { source ->
            val current = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(if (source == "slow") 80 else 10)
                source.uppercase()
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(listOf("SLOW", "FAST"), result)
        assertEquals(2, maxActive.get())
    }

    @Test
    fun parallelMapDropsRecoverableFailuresAndOwnTimeouts() = runBlocking {
        val result = parallelMapWithinBudget(listOf("ok", "failed", "timed-out"), budgetMs = 200) { source ->
            when (source) {
                "failed" -> error("offline")
                "timed-out" -> delay(1_000)
            }
            source
        }

        assertEquals(listOf("ok"), result)
    }

    @Test
    fun parallelMapDoesNotSwallowParentCancellation() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(30) {
                    parallelMapWithinBudget(listOf("slow"), budgetMs = 1_000) {
                        delay(1_000)
                        it
                    }
                }
            }
        }
    }
}

private fun song(
    source: String = "kw",
    name: String = "Song A",
    singer: String = "Singer A",
    album: String = "Album A",
    interval: String = "03:30",
    qualities: List<String> = emptyList(),
): OnlineSong = OnlineSong(
    JSONObject()
        .put("source", source)
        .put("songmid", "$source-$name-$singer-$album-$interval")
        .put("name", name)
        .put("singer", singer)
        .put("albumName", album)
        .put("interval", interval)
        .also { raw ->
            if (qualities.isNotEmpty()) {
                raw.put("_types", JSONObject().also { types -> qualities.forEach { types.put(it, JSONObject()) } })
            }
        },
)
