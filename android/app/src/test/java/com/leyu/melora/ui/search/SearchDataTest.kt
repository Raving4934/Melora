package com.leyu.melora.ui.search

import android.content.ContextWrapper
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun filteredPageStillLoadsMoreWhenCatalogHasAnotherPage() = runBlocking {
        cache("kw", page = 1, count = 2, total = 60, allPage = 2)
        val result = searchSongs(context, PlatformSource.Kuwo, keyword, 1)
        assertEquals(2, result.songs.size)
        assertTrue("过滤后不足30首不能当作目录结束", result.hasMore)
    }

    @Test fun fullLastPageDoesNotRequestAnotherPage() = runBlocking {
        cache("kw", page = 2, count = 30, total = 60, allPage = 2)
        val result = searchSongs(context, PlatformSource.Kuwo, keyword, 2)
        assertEquals(30, result.songs.size)
        assertFalse("目录明确结束时不应多翻一页", result.hasMore)
    }

    @Test fun progressiveSearchUsesTheSameCatalogPagination() = runBlocking {
        platformIds.forEach { cache(it, 1, if (it == "kw") 2 else 0, 60, 2) }
        val batches = mutableListOf<Pair<Int, Boolean>>()
        searchSongs(context, PlatformSource.All, keyword, 1) { outcome ->
            batches += outcome.songs.size to outcome.hasMore
        }
        assertEquals(2 to true, batches.last())
    }

    @Test fun missingResponsePageUsesRequestedCursorAndTotalFallback() = runBlocking {
        cache("kw", page = 2, count = 30, total = 60, allPage = 0)
        val key = songSearchCacheKey("kw", keyword, 2)
        val cached = requireNotNull(OnlineCache.peek<SongPage>(key))
        OnlineCache.put(key, cached.copy(page = 1))
        assertFalse(searchSongs(context, PlatformSource.Kuwo, keyword, 2).hasMore)
    }

    @Test fun unknownTotalsRetainFullPageFallback() = runBlocking {
        cache("kw", page = 1, count = 30, total = 0, allPage = 0)
        assertTrue(searchSongs(context, PlatformSource.Kuwo, keyword, 1).hasMore)
        cache("kw", page = 2, count = 3, total = 0, allPage = 0)
        assertFalse(searchSongs(context, PlatformSource.Kuwo, keyword, 2).hasMore)
    }

    @Test fun progressiveAndBatchSearchReturnTheSameDeduplicatedSongs() = runBlocking {
        platformIds.forEach { cache(it, 1, 2, 2, 1) }
        val shared = OnlineSong(JSONObject().put("source", "kw").put("songmid", "shared")
            .put("name", "same song").put("singer", "same singer"))
        platformIds.forEach { source -> OnlineCache.put(songSearchCacheKey(source, keyword, 1),
            SongPage(listOf(shared), 1, 1, 1)) }
        val batch = searchSongs(context, PlatformSource.All, keyword, 1)
        val progressive = searchSongs(context, PlatformSource.All, keyword, 1) {}
        assertEquals(batch, progressive)
        assertEquals(listOf(shared), progressive.songs)
        assertFalse(progressive.hasMore)
    }

    @Test fun refreshedSourceReplacesOldSongsInsteadOfAppendingThem() = runBlocking {
        val key = songSearchCacheKey("kw", keyword, 1)
        cache("kw", 1, 2, 60, 2)
        val old = requireNotNull(OnlineCache.peek<SongPage>(key))
        val fresh = old.copy(list = old.list.take(1), total = 1, allPage = 1)
        expire(key)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sawSnapshot = CompletableDeferred<Unit>()
        val refresh = async { OnlineCache.refresh(key, -1) { started.complete(Unit); release.await(); fresh } }
        withTimeout(5_000) { started.await() }
        val search = async { searchSongs(context, PlatformSource.Kuwo, keyword, 1) { result ->
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
        val updates = mutableListOf<SongSearchOutcome>()
        val refresh = async { OnlineCache.refresh(key, -1) { started.complete(Unit); release.await(); old.copy(list = emptyList()) } }
        withTimeout(5_000) { started.await() }
        val search = async { searchSongs(context, PlatformSource.Kuwo, keyword, 1) {
            updates += it
            sawSnapshot.complete(Unit)
        } }
        try {
            withTimeout(5_000) { sawSnapshot.await() }
            search.cancelAndJoin()
            val count = updates.size
            OnlineCache.put(songSearchCacheKey("kw", "next keyword", 1), old.copy(list = old.list.take(1), total = 1, allPage = 1))
            val next = searchSongs(context, PlatformSource.Kuwo, "next keyword", 1)
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
