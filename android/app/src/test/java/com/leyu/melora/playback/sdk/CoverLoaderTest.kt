package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CoverLoaderTest {
    @Test
    fun boundedCacheEvictsOldInactiveEntries() {
        val cache = CoverAddressCache(maxEntries = 3, valueTtlMs = 1_000, nowMs = { 0L })

        repeat(3) { index -> cache.put("song-$index", "https://example.com/$index") }
        cache.put("song-new", "https://example.com/new")

        assertEquals(3, cache.size)
        assertNull(cache.peek("song-0"))
        assertEquals("https://example.com/new", cache.peek("song-new"))
    }

    @Test
    fun activeSubscriptionSurvivesEvictionAndContinuesReceivingUpdates() = runBlocking {
        val cache = CoverAddressCache(maxEntries = 2, valueTtlMs = 1_000, nowMs = { 0L })
        cache.put("active", "https://example.com/old")
        val values = mutableListOf<String?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observe("active").collect { values += it }
        }
        try {
            cache.put("other-1", "https://example.com/one")
            cache.put("other-2", "https://example.com/two")
            cache.put("active", "https://example.com/new")
            yield()

            assertEquals(2, cache.size)
            assertEquals("https://example.com/new", cache.peek("active"))
            assertTrue(values.contains("https://example.com/new"))
        } finally {
            collector.cancel()
            collector.join()
        }
    }

    @Test
    fun clearKeepsOldAndNewCollectorsUntilSameUrlIsResolvedAgain() = runBlocking {
        val cache = CoverAddressCache(maxEntries = 2, valueTtlMs = 1_000, nowMs = { 0L })
        val url = "https://example.com/same-url"
        cache.put("song", url)
        val oldValues = mutableListOf<String?>()
        val oldCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observe("song").collect { oldValues += it }
        }
        val newValues = mutableListOf<String?>()
        var newCollector: kotlinx.coroutines.Job? = null
        try {
            cache.clear()
            assertEquals(url, oldValues.last())
            assertNull(cache.peek("song"))
            assertTrue(cache.needsRefresh("song"))

            newCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                cache.observe("song").collect { newValues += it }
            }
            assertNull(newValues.first())

            // clear 后即使解析重新得到完全相同的 URL，也必须从 null -> url 发射给新旧 collector。
            cache.put("song", url)
            yield()
            assertEquals(url, oldValues.last())
            assertEquals(url, newValues.last())
            assertFalse(cache.needsRefresh("song"))
        } finally {
            newCollector?.cancel()
            newCollector?.join()
            oldCollector.cancel()
            oldCollector.join()
        }
    }

    @Test
    fun flowCreatedBeforeClearDoesNotReRegisterClearedSnapshotWhenCollectedLater() = runBlocking {
        val cache = CoverAddressCache(maxEntries = 2, valueTtlMs = 1_000, nowMs = { 0L })
        cache.put("song", "https://example.com/old")
        val flow = cache.observe("song")
        cache.clear()

        val values = mutableListOf<String?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect { values += it }
        }
        try {
            assertEquals(listOf(null), values)
            assertNull(cache.peek("song"))
        } finally {
            collector.cancel()
            collector.join()
        }
    }

    @Test
    fun releasingLastCollectorRemovesClearedSnapshot() = runBlocking {
        val cache = CoverAddressCache(maxEntries = 2, valueTtlMs = 1_000, nowMs = { 0L })
        cache.put("song", "https://example.com/old")
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observe("song").collect { }
        }

        cache.clear()
        collector.cancel()
        collector.join()

        assertEquals(0, cache.size)
        assertNull(cache.peek("song"))
    }

    @Test
    fun expiredAddressRemainsReadableAndFailedRefreshDoesNotMakePermanentMiss() {
        var now = 0L
        val cache = CoverAddressCache(maxEntries = 2, valueTtlMs = 100, nowMs = { now })
        cache.put("song", "https://example.com/old")

        assertFalse(cache.needsRefresh("song"))
        now = 101
        assertEquals("https://example.com/old", cache.peek("song"))
        assertTrue(cache.needsRefresh("song"))

        // 失败不写入空值/失败标记，下一次请求仍可重新尝试。
        assertTrue(cache.needsRefresh("song"))
        cache.put("song", "https://example.com/fresh")
        assertFalse(cache.needsRefresh("song"))
    }

    @Test
    fun failedSingleFlightCanRetryBecauseFailureIsNotCached() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<String, String>(scope)
            var calls = 0
            val failure = runCatching {
                flight.run("song") {
                    calls++
                    error("temporary")
                }
            }
            assertTrue(failure.isFailure)
            assertEquals("recovered", flight.run("song") {
                calls++
                "recovered"
            })
            assertEquals(2, calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clearFencesLateResolutionSoItCannotRepopulateAddressCache() = runBlocking {
        val lock = Any()
        val cache = CoverAddressCache(maxEntries = 2, valueTtlMs = 1_000, nowMs = { 0L }, lock = lock)
        cache.put("song", "https://example.com/before-clear")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<String, String>(scope, lock)
            val generation = flight.currentGeneration()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val retired = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    flight.run("song", generation) {
                        started.complete(Unit)
                        try {
                            release.await()
                        } finally {
                            // 模拟解析协程在取消竞态中仍走到回写点；代次检查必须拒绝它。
                            withContext(NonCancellable) {
                                synchronized(lock) {
                                    if (flight.currentGeneration() == generation) {
                                        cache.put("song", "https://example.com/late")
                                    }
                                }
                            }
                        }
                        "late"
                    }
                } catch (_: CancellationException) {
                    // fence 会取消清理前的 waiter。
                }
            }

            started.await()
            synchronized(lock) {
                flight.fence()
                cache.clear()
            }
            release.complete(Unit)
            retired.await()

            assertNull(cache.peek("song"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retriesTransientNullAndReturnsSecondResult() = runBlocking {
        val attempts = mutableListOf<Int>()

        val result = retryNullable(maxAttempts = 2, retryDelayMs = 0) { attempt ->
            attempts += attempt
            if (attempt == 2) "https://example.com/cover.jpg" else null
        }

        assertEquals("https://example.com/cover.jpg", result)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test
    fun stopsAfterFirstSuccessfulResult() = runBlocking {
        var calls = 0

        val result = retryNullable(maxAttempts = 3, retryDelayMs = 0) {
            calls++
            "cover"
        }

        assertEquals("cover", result)
        assertEquals(1, calls)
    }

    @Test
    fun returnsNullAfterBoundedFailures() = runBlocking {
        var calls = 0

        val result = retryNullable<String>(maxAttempts = 2, retryDelayMs = 0) {
            calls++
            error("temporary failure")
        }

        assertNull(result)
        assertEquals(2, calls)
    }

    @Test
    fun propagatesCancellationWithoutRetrying() {
        var calls = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retryNullable<String>(maxAttempts = 2, retryDelayMs = 0) {
                    calls++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, calls)
    }
}
