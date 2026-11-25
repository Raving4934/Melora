package com.leyu.melora.ui.search

import android.content.ContextWrapper
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistPage
import com.leyu.melora.playback.sdk.SongPage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDataTest {
    private val context = ContextWrapper(null)
    private val keyword = "search-pagination-regression"

    @After fun cleanup() = OnlineCache.clear("search:songs:")

    @Test fun playlistSearchStopsAtTheReportedTotalAndSupportsSourcesWithoutTotals() {
        val list = listOf(OnlinePlaylist(JSONObject().put("source", "tx").put("id", "42").put("name", "测试")))
        val response = PlaylistPage(list, total = 21, page = 1)
        assertTrue(response.hasMoreSearchResults(1))
        assertFalse(response.hasMoreSearchResults(2))
        assertFalse(response.copy(total = 20).hasMoreSearchResults(1))
        assertTrue(response.copy(total = 0).hasMoreSearchResults(2))
        assertFalse(response.copy(list = emptyList(), total = 0).hasMoreSearchResults(2))
    }

    @Test fun filteredPageStillLoadsMoreWhenCatalogHasAnotherPage() = runBlocking {
        cache("kw", page = 1, count = 2, total = 60, allPage = 2)
        val result = loadSongs(PlatformSource.Kuwo, keyword, 1)
        assertEquals(2, result.songs.size)
        assertTrue("过滤后不足30首不能当作目录结束", result.hasMore)
    }

    @Test fun fullLastPageDoesNotRequestAnotherPage() = runBlocking {
        cache("kw", page = 2, count = 30, total = 60, allPage = 2)
        val result = loadSongs(PlatformSource.Kuwo, keyword, 2)
        assertEquals(30, result.songs.size)
        assertFalse("目录明确结束时不应多翻一页", result.hasMore)
    }

    @Test fun progressiveSearchUsesTheSameCatalogPagination() = runBlocking {
        platformIds.forEach { cache(it, 1, if (it == "kw") 2 else 0, 60, 2) }
        val batches = mutableListOf<Pair<Int, Boolean>>()
        loadSongs(PlatformSource.All, keyword, 1) { outcome ->
            batches += outcome.songs.size to outcome.hasMore
        }
        assertEquals(2 to true, batches.last())
    }

    @Test fun missingResponsePageUsesRequestedCursorAndTotalFallback() = runBlocking {
        cache("kw", page = 2, count = 30, total = 60, allPage = 0)
        val key = songSearchCacheKey("kw", keyword, 2)
        val cached = requireNotNull(OnlineCache.peek<SongPage>(key))
        OnlineCache.put(key, cached.copy(page = 1))
        assertFalse(loadSongs(PlatformSource.Kuwo, keyword, 2).hasMore)
    }

    @Test fun unknownTotalsRetainFullPageFallback() = runBlocking {
        cache("kw", page = 1, count = 30, total = 0, allPage = 0)
        assertTrue(loadSongs(PlatformSource.Kuwo, keyword, 1).hasMore)
        cache("kw", page = 2, count = 3, total = 0, allPage = 0)
        assertFalse(loadSongs(PlatformSource.Kuwo, keyword, 2).hasMore)
    }

    @Test fun progressiveAndBatchSearchReturnTheSameDeduplicatedSongs() = runBlocking {
        platformIds.forEach { cache(it, 1, 2, 2, 1) }
        val shared = OnlineSong(JSONObject().put("source", "kw").put("songmid", "shared")
            .put("name", "same song").put("singer", "same singer"))
        platformIds.forEach { source -> OnlineCache.put(songSearchCacheKey(source, keyword, 1),
            SongPage(listOf(shared), 1, 1, 1)) }
        val batch = loadSongs(PlatformSource.All, keyword, 1)
        val progressive = loadSongs(PlatformSource.All, keyword, 1) {}
        assertEquals(batch, progressive)
        assertEquals(listOf(shared), progressive.songs)
        assertFalse(progressive.hasMore)
    }

    @Test fun aggregatedSearchDoesNotPublishTwoTitlesWithTheSameSongId() = runBlocking {
        platformIds.forEach { cache(it, 1, 0, 0, 1) }
        fun cacheTitle(bucket: String, title: String) {
            val song = OnlineSong(JSONObject().put("source", "kg").put("songmid", "395119045")
                .put("name", title).put("singer", "同一位歌手"))
            OnlineCache.put(songSearchCacheKey(bucket, keyword, 1), SongPage(listOf(song), 1, 1, 1))
        }
        cacheTitle("kw", "第一个标题")
        cacheTitle("kg", "另一个标题")

        val expected = listOf("kg_395119045")
        val batch = loadSongs(PlatformSource.All, keyword, 1)
        assertEquals(expected, batch.songs.map(OnlineSong::uid))
        val updates = mutableListOf<List<String>>()
        val progressive = loadSongs(PlatformSource.All, keyword, 1) { update ->
            updates += update.songs.map(OnlineSong::uid)
        }
        assertEquals(listOf(expected), updates.filter { it.isNotEmpty() }.distinct())
        assertEquals(expected, progressive.songs.map(OnlineSong::uid))
    }

    @Test fun aSkippedSearchRowDoesNotHideAnotherSongOrItsAlias() = runBlocking {
        fun song(id: String, title: String) = OnlineSong(JSONObject()
            .put("source", "kg").put("songmid", id).put("name", title).put("singer", "同一位歌手"))
        val entries = listOf(song("1", "甲"), song("2", "甲"), song("2", "乙"), song("2", "丙"), song("3", "丙"))
        OnlineCache.put(songSearchCacheKey("kg", keyword, 1), SongPage(entries, entries.size, 1, 1))

        val result = loadSongs(PlatformSource.Kugou, keyword, 1)
        assertEquals(listOf(entries[0], entries[2], entries[4]), result.songs)
    }

    @Test fun refreshedSourceReplacesOldSongsInsteadOfAppendingThem() = runBlocking {
        val key = songSearchCacheKey("kw", keyword, 1)
        cache("kw", 1, 2, 60, 2)
        val old = requireNotNull(OnlineCache.peek<SongPage>(key))
        val fresh = old.copy(entries = old.list.take(1), total = 1, allPage = 1)
        expire(key)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sawSnapshot = CompletableDeferred<Unit>()
        val refresh = async { OnlineCache.refresh(key, -1) { started.complete(Unit); release.await(); fresh } }
        withTimeout(5_000) { started.await() }
        val search = async { loadSongs(PlatformSource.Kuwo, keyword, 1) { result ->
            if (result.songs.size == 2) sawSnapshot.complete(Unit)
        } }
        try {
            withTimeout(5_000) { sawSnapshot.await() }
            release.complete(Unit)
            val result = withTimeout(5_000) { search.await() }
            assertEquals(fresh.list, result.songs)
            assertFalse(result.hasMore)
        } finally {
            release.complete(Unit)
            search.cancelAndJoin()
            refresh.cancelAndJoin()
        }
    }

    @Test fun cancelledKeywordCannotPublishLateResultsIntoNextSearch() = runBlocking {
        val key = songSearchCacheKey("kw", keyword, 1)
        cache("kw", 1, 2, 60, 2)
        val old = requireNotNull(OnlineCache.peek<SongPage>(key))
        expire(key)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sawSnapshot = CompletableDeferred<Unit>()
        val updates = mutableListOf<SearchResults>()
        val refresh = async { OnlineCache.refresh(key, -1) { started.complete(Unit); release.await(); old.copy(entries = emptyList()) } }
        withTimeout(5_000) { started.await() }
        val search = async { loadSongs(PlatformSource.Kuwo, keyword, 1) {
            updates += it
            sawSnapshot.complete(Unit)
        } }
        try {
            withTimeout(5_000) { sawSnapshot.await() }
            search.cancelAndJoin()
            val count = updates.size
            OnlineCache.put(songSearchCacheKey("kw", "next keyword", 1), old.copy(entries = old.list.take(1), total = 1, allPage = 1))
            val next = loadSongs(PlatformSource.Kuwo, "next keyword", 1)
            release.complete(Unit)
            withTimeout(5_000) { refresh.await() }
            assertEquals(count, updates.size)
            assertEquals(1, next.songs.size)
            assertFalse(next.hasMore)
        } finally {
            release.complete(Unit)
            search.cancelAndJoin()
            refresh.cancelAndJoin()
        }
    }

    @Test fun failedPageKeepsItsCursorAndCanBeRetried() = runBlocking {
        cache("kw", page = 1, count = 1, total = 2, allPage = 2)
        val first = loadSongs(PlatformSource.Kuwo, keyword, 1)
        val key = songSearchCacheKey("kw", keyword, 2)
        val release = CompletableDeferred<Unit>()
        val failedRefresh = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                OnlineCache.refresh<SongPage>(key, -1) {
                    release.await()
                    throw IOException("第二页读取中断")
                }
            }
        }
        try {
            val failed = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { loadSongs(PlatformSource.Kuwo, keyword, 2) }
            }
            yield() // 同一运行队列中分页请求已加入这条失败的在途读取。
            release.complete(Unit)
            assertEquals("第二页读取中断", withTimeout(5_000) { failed.await() }.exceptionOrNull()?.message)
            failedRefresh.await()
            cache("kw", page = 2, count = 1, total = 2, allPage = 2)
            val retried = first.withPage(loadSongs(PlatformSource.Kuwo, keyword, 2))
            assertEquals(2, retried.page)
            assertEquals(listOf("kw_1-0", "kw_2-0"), retried.songs.map(OnlineSong::uid))
            assertFalse(retried.hasMore)
        } finally {
            release.complete(Unit)
            failedRefresh.cancelAndJoin()
        }
    }

    private suspend fun loadSongs(
        platform: PlatformSource,
        term: String,
        page: Int,
        onUpdate: ((SearchResults) -> Unit)? = null,
    ) = searchResultPage(context, SearchCategory.Song, platform, term, page, onUpdate)

    /** 仅测试调整缓存写入时间，不等待真实10分钟，也不引入生产时钟/网络测试分支。 */
    private fun expire(key: String) {
        val field = OnlineCache.javaClass.getDeclaredField("store").apply { isAccessible = true }
        val entry = requireNotNull((field.get(OnlineCache) as Map<*, *>)[key])
        entry.javaClass.getDeclaredField("time").apply { isAccessible = true }.setLong(entry, 0L)
    }

    private fun cache(source: String, page: Int, count: Int, total: Int, allPage: Int) {
        val songs = List(count) { index -> OnlineSong(JSONObject()
            .put("source", source).put("songmid", "$page-$index")
            .put("name", "$source-$page-$index").put("singer", "fixture")) }
        OnlineCache.put(songSearchCacheKey(source, keyword, page), SongPage(songs, total, page, allPage))
    }
}
