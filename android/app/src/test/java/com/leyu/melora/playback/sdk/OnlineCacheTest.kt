package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
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
    private lateinit var snapshotRoot: File
    private lateinit var snapshotContext: Context

    @Before fun reset() {
        OnlineCache.clear()
        snapshotRoot = Files.createTempDirectory("melora-online-cache-").toFile()
        snapshotContext = SnapshotContext(snapshotRoot)
    }

    @After fun cleanup() {
        OnlineCache.clear()
        runBlocking { OnlineCache.clearDisk(snapshotContext) }
        snapshotRoot.deleteRecursively()
    }

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

    @Test fun boardListDiskSnapshotRestoresWithoutRefreshingItsSavedAt() = runBlocking {
        val key = "boards.kw.disk"
        val boards = listOf(BoardItem("1", "热歌榜", "bang-1", "https://img/1"))
        val savedAtMs = System.currentTimeMillis() - OnlineCache.CATALOG_TTL_MS * 2

        assertTrue(
            OnlineCache.persistBoardList(
                snapshotContext,
                key,
                boards,
                snapshotToken(key),
                savedAtMs,
            ),
        )
        OnlineCache.clear(key)

        assertEquals(boards, OnlineCache.hydrateBoardList(snapshotContext, key))
        assertEquals(boards, OnlineCache.peek<List<BoardItem>>(key))
        assertNull(OnlineCache.get<List<BoardItem>>(key, OnlineCache.CATALOG_TTL_MS))
    }

    @Test fun playlistDiskSnapshotRestoresOnlyFirstPageMetadata() = runBlocking {
        val key = "playlists.kw.hot.all"
        val firstPage = CachedPlaylistPage(
            list = listOf(playlist("p1"), playlist("p2")),
            page = 1,
            hasMore = true,
            total = 60,
        )

        assertTrue(
            OnlineCache.persistPlaylistFirstPage(
                snapshotContext,
                key,
                firstPage,
                snapshotToken(key),
            ),
        )
        OnlineCache.clear(key)

        val restored = checkNotNull(OnlineCache.hydratePlaylistFirstPage(snapshotContext, key))
        assertEquals(1, restored.page)
        assertTrue(restored.hasMore)
        assertEquals(60, restored.total)
        assertEquals(listOf("p1", "p2"), restored.list.map(OnlinePlaylist::id))
    }

    @Test fun multiPagePlaylistSnapshotIsRejectedInsteadOfBecomingFirstPage() = runBlocking {
        val key = "playlists.kw.hot.multi"
        val merged = CachedPlaylistPage(
            list = listOf(playlist("p1"), playlist("p2")),
            page = 2,
            hasMore = true,
            total = 60,
        )

        assertFalse(
            OnlineCache.persistPlaylistFirstPage(
                snapshotContext,
                key,
                merged,
                snapshotToken(key),
            ),
        )
        assertNull(OnlineCache.hydratePlaylistFirstPage(snapshotContext, key))
    }

    @Test fun corruptedDiskSnapshotFallsBackToNetwork() = runBlocking {
        val key = "boards.kg.disk"
        assertTrue(
            OnlineCache.persistBoardList(
                snapshotContext,
                key,
                listOf(BoardItem("1", "榜单", "bang-1")),
                snapshotToken(key),
            ),
        )
        OnlineCache.clear(key)
        val snapshotFile = checkNotNull(snapshotRoot.resolve(SNAPSHOT_DIRECTORY).listFiles()?.single())
        snapshotFile.writeText("not-json")

        assertNull(OnlineCache.hydrateBoardList(snapshotContext, key))
        assertFalse(snapshotFile.exists())
    }

    @Test fun clearBlocksLatePageResponseFromMemoryAndDisk() = runBlocking {
        val key = "boards.kw.clear-race"
        val token = checkNotNull(OnlineCache.capturePageSnapshot(key))
        val response = CompletableDeferred<List<BoardItem>>()
        val oldRequest = async {
            val boards = response.await()
            val writtenAtMs = OnlineCache.putPageIfCurrent(token, key, boards)
            val saved = OnlineCache.persistBoardList(
                snapshotContext,
                key,
                boards,
                token,
                System.currentTimeMillis(),
            )
            writtenAtMs to saved
        }

        OnlineCache.clearDisk(snapshotContext)
        response.complete(listOf(BoardItem("old", "旧榜单", "old-bangid")))

        val (writtenAtMs, saved) = oldRequest.await()
        assertNull(writtenAtMs)
        assertFalse(saved)
        assertNull(OnlineCache.peek<List<BoardItem>>(key))
        assertFalse(snapshotRoot.resolve(SNAPSHOT_DIRECTORY).exists())
    }

    @Test fun clearDiskRemovesSnapshotsWithoutLeavingOldFiles() = runBlocking {
        val key = "boards.wy.disk"
        assertTrue(
            OnlineCache.persistBoardList(
                snapshotContext,
                key,
                listOf(BoardItem("1", "榜单", "bang-1")),
                snapshotToken(key),
            ),
        )

        OnlineCache.clear()
        OnlineCache.clearDisk(snapshotContext)

        assertFalse(snapshotRoot.resolve("online-cache-v1").exists())
        assertNull(OnlineCache.hydrateBoardList(snapshotContext, key))
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


private fun snapshotToken(key: String): PageSnapshotToken =
    requireNotNull(OnlineCache.capturePageSnapshot(key))


private fun playlist(id: String): OnlinePlaylist = OnlinePlaylist(
    org.json.JSONObject()
        .put("id", id)
        .put("name", "歌单 $id")
        .put("source", "kw")
        .put("author", "作者")
        .put("play_count", "100")
)

private class SnapshotContext(private val root: File) : ContextWrapper(null) {
    override fun getCacheDir(): File = root
    override fun getApplicationContext(): Context = this
}
