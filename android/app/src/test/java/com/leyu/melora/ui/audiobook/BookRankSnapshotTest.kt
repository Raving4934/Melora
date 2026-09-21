package com.leyu.melora.ui.audiobook

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BookRankSnapshotTest {
    @After fun cleanup() = OnlineCache.clear("book.rank.")
    private fun album(id: String) = OnlinePlaylist(JSONObject().put("id", id).put("source", "kw"))
    private fun page(vararg ids: String, hasMore: Boolean = true) = KwBookApi.BookPage(ids.map(::album), hasMore)

    @Test fun openingAndUpdatingAllFourRanksDoesNotReplaceTheHomeSnapshot() {
        val home = BookRankSnapshot().withPage(page("home"), 1)
        OnlineCache.put("book.rank.13.27", home)
        for (id in listOf("20", "14", "1", "15")) {
            val rank = BookRankSnapshot().withPage(page(id), 1)
            OnlineCache.put("book.rank.$id.tag", rank.withPage(page("next-$id"), 2))
            assertSame(home, OnlineCache.peek<BookRankSnapshot>("book.rank.13.27"))
            assertEquals(listOf(id), rank.items.map { it.id })
        }
    }

    @Test fun reentryRestoresTheCursorAndRemovesOverlappingAlbums() {
        val snapshot = BookRankSnapshot().withPage(page("a", "b"), 1).withPage(page("b", "c"), 2)
        OnlineCache.put("book.rank.20.128", snapshot)
        val restored = checkNotNull(OnlineCache.get<BookRankSnapshot>("book.rank.20.128", 10000))
        assertEquals(listOf("a", "b", "c"), restored.items.map { it.id })
        assertEquals(3, restored.page + 1)
        assertTrue(restored.hasMore)
    }

    @Test fun refreshReplacesTheOldPagesWithoutMutatingTheDisplayedSnapshot() {
        val old = BookRankSnapshot().withPage(page("old"), 3)
        val refreshed = old.withPage(page("fresh", hasMore = false), 1)
        assertEquals(listOf("old"), old.items.map { it.id })
        assertEquals(listOf("fresh"), refreshed.items.map { it.id })
        assertEquals(1, refreshed.page)
        assertFalse(refreshed.hasMore)
    }

    @Test fun exhaustionComesFromTheResponseNotTheCachedItemCount() {
        val terminal = BookRankSnapshot().withPage(page(*(1..100).map(Int::toString).toTypedArray(), hasMore = false), 2)
        assertFalse(terminal.hasMore)
        val empty = BookRankSnapshot().withPage(page(hasMore = false), 1)
        OnlineCache.put("book.rank.14.28", empty)
        assertSame(empty, OnlineCache.peek<BookRankSnapshot>("book.rank.14.28"))
        assertFalse(empty.hasMore)
        assertEquals(1, empty.page)
    }

    @Test fun categoriesUseIndependentCacheEntries() {
        val first = BookRankSnapshot().withPage(page("first"), 2)
        val second = BookRankSnapshot().withPage(page("second", hasMore = false), 1)
        OnlineCache.put("book.rank.1.30", first)
        OnlineCache.put("book.rank.1.31", second)
        assertSame(first, OnlineCache.peek<BookRankSnapshot>("book.rank.1.30"))
        assertSame(second, OnlineCache.peek<BookRankSnapshot>("book.rank.1.31"))
    }
}
