package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OnlineCacheTest {
    @Before fun reset() = OnlineCache.clear()
    @After fun cleanup() = OnlineCache.clear()

    @Test fun ttlMissRetainsSnapshotForImmediatePlayback() {
        val queue = listOf("old")
        OnlineCache.put("songs", queue)
        assertSame(queue, OnlineCache.get<List<String>>("songs", Long.MAX_VALUE))
        assertNull(OnlineCache.get<List<String>>("songs", -1))
        assertSame(queue, OnlineCache.peek<List<String>>("songs"))
    }

    @Test fun leastRecentlyUsedEntryEvictedAndReplacementDoesNotGrowCache() {
        repeat(OnlineCache.MAX_ENTRIES) { OnlineCache.put("$it", it) }
        assertEquals(0, OnlineCache.peek<Int>("0"))
        OnlineCache.put("0", 42)
        OnlineCache.put("new", 999)
        assertNull(OnlineCache.peek<Int>("1"))
        assertEquals(42, OnlineCache.peek<Int>("0"))
        assertEquals(999, OnlineCache.peek<Int>("new"))
    }

    @Test fun freshHitDoesNotInvokeLoader() = runBlocking {
        OnlineCache.put("songs", listOf("cached"))
        assertEquals(listOf("cached"), OnlineCache.refresh<List<String>>("songs", Long.MAX_VALUE) { error("unexpected fetch") })
    }

    @Test fun pageSnapshotArrivingAfterInitialPeekIsStillAppliedByRefresh() = runBlocking {
        val key = "boardSongsPage.kw.test"
        assertNull(OnlineCache.peek<SongPage>(key))
        val song = OnlineSong(org.json.JSONObject().put("source", "kw").put("songmid", "one"))
        val page = SongPage(listOf(song), 2, 1, 2)
        OnlineCache.put(key, page)
        val loaded = OnlineCache.refresh<SongPage>(key, Long.MAX_VALUE) { error("unexpected fetch") }
        assertSame(page, loaded)
        assertTrue(loaded.hasMore())
        assertEquals(listOf(song), loaded.list)
    }

    @Test fun detailSnapshotsRestoreMergedCursorAndTerminalStateOnReentry() = runBlocking {
        val song = OnlineSong(org.json.JSONObject().put("source", "kw").put("songmid", "song"))
        val songSnapshot = SongPage(
            list = listOf(song),
            total = 1,
            page = 2,
            allPage = 2,
            snapshotHasMore = false,
        )
        val bookSnapshot = KwBookApi.BookChapters(
            items = listOf(song),
            hasMore = false,
            metadata = KwBookApi.BookMetadata(total = 1),
            page = 2,
        )

        OnlineCache.put("playlistDetail.kw.song", songSnapshot)
        OnlineCache.put("playlistDetail.book.book", bookSnapshot)

        val restoredSongs = OnlineCache.refresh<SongPage>("playlistDetail.kw.song", Long.MAX_VALUE) {
            error("unexpected song reload")
        }
        val restoredBook = OnlineCache.refresh<KwBookApi.BookChapters>("playlistDetail.book.book", Long.MAX_VALUE) {
            error("unexpected book reload")
        }

        assertEquals(2, restoredSongs.page)
        assertFalse(restoredSongs.hasMore())
        assertEquals(2, restoredBook.page)
        assertEquals(1, restoredBook.total)
        assertFalse(restoredBook.hasMore)
    }

    @Test fun staleRefreshIsSingleFlightAndDoesNotMutatePlayingSnapshot() = runBlocking {
        val snapshot = listOf("old")
        OnlineCache.put("songs", snapshot)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { OnlineCache.refresh("songs", -1) { started.complete(Unit); release.await(); listOf("new") } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            OnlineCache.refresh<List<String>>("songs", -1) { error("duplicate request") }
        }
        assertSame(snapshot, OnlineCache.peek<List<String>>("songs"))
        release.complete(Unit)
        assertEquals(listOf("new"), first.await())
        assertEquals(listOf("new"), second.await())
        assertEquals(listOf("old"), snapshot)
        assertEquals(listOf("new"), OnlineCache.get<List<String>>("songs", Long.MAX_VALUE))
    }

    @Test fun cancellingOneWaiterDoesNotCancelSharedRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { OnlineCache.refresh("songs", 1000) { started.complete(Unit); release.await(); listOf("new") } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            OnlineCache.refresh<List<String>>("songs", 1000) { error("duplicate request") }
        }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals(listOf("new"), second.await())
    }

    @Test fun clearCancelsFetchAndPreventsLateRepopulation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async { OnlineCache.refresh("songs", 1000) { started.complete(Unit); release.await(); listOf("old") } }
        started.await()
        OnlineCache.clear()
        OnlineCache.put("songs", listOf("after-clear"))
        release.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(listOf("after-clear"), OnlineCache.peek<List<String>>("songs"))
    }

    @Test fun pageWriteDuringRefreshWinsOverLateResponse() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async { OnlineCache.refresh("songs", -1) { started.complete(Unit); release.await(); listOf("late") } }
        started.await()
        OnlineCache.put("songs", listOf("page-refresh"))
        release.complete(Unit)
        result.await()
        assertEquals(listOf("page-refresh"), OnlineCache.peek<List<String>>("songs"))
    }

    @Test fun failedAndEmptyRefreshKeepOldValueAndAllowRetry() = runBlocking {
        OnlineCache.put("songs", listOf("old"))
        val failure = runCatching { OnlineCache.refresh<List<String>>("songs", -1) { error("offline") } }
        assertTrue(failure.isFailure)
        assertEquals(listOf("old"), OnlineCache.peek<List<String>>("songs"))
        assertTrue(OnlineCache.refresh("songs", -1) { emptyList<String>() }.isEmpty())
        assertEquals(listOf("old"), OnlineCache.peek<List<String>>("songs"))
        assertEquals(listOf("recovered"), OnlineCache.refresh("songs", -1) { listOf("recovered") })
    }
    @Test fun prefixClearOnlyCancelsMatchingRequestsAndKeepsPageCaches() = runBlocking {
        OnlineCache.put("resolver:matches:old", listOf("old"))
        OnlineCache.put("catalog:board", listOf("visible"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val matching = async {
            OnlineCache.refresh("resolver:matches:pending", 1000) {
                started.complete(Unit)
                release.await()
                listOf("late")
            }
        }
        started.await()
        OnlineCache.clear("resolver:matches:")
        release.complete(Unit)
        matching.join()
        assertTrue(matching.isCancelled)
        assertNull(OnlineCache.peek<List<String>>("resolver:matches:old"))
        assertNull(OnlineCache.peek<List<String>>("resolver:matches:pending"))
        assertEquals(listOf("visible"), OnlineCache.get<List<String>>("catalog:board", Long.MAX_VALUE))
        assertEquals(listOf("fresh"), OnlineCache.refresh("resolver:matches:pending", 1000) { listOf("fresh") })
    }

}
