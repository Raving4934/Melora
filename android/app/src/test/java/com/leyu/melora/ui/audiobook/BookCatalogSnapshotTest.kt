package com.leyu.melora.ui.audiobook

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BookCatalogSnapshotTest {
    @After fun cleanup() = OnlineCache.clear("book.")
    private fun album(id: String) = OnlinePlaylist(JSONObject().put("id", id).put("source", "kw"))
    private fun page(vararg ids: String, hasMore: Boolean = true) = KwBookApi.BookPage(ids.map(::album), hasMore)

    @Test fun openingAndUpdatingAllFourRanksDoesNotReplaceTheHomeSnapshot() {
        val home = BookCatalogSnapshot().withPage(page("home"), 1)
        OnlineCache.put("book.rank.13.27", home)
        for (id in listOf("20", "14", "1", "15")) {
            val rank = BookCatalogSnapshot().withPage(page(id), 1)
            OnlineCache.put("book.rank.$id.tag", rank.withPage(page("next-$id"), 2))
            assertSame(home, OnlineCache.peek<BookCatalogSnapshot>("book.rank.13.27"))
            assertEquals(listOf(id), rank.items.map { it.id })
        }
    }

    @Test fun reentryRestoresTheCursorAndRemovesOverlappingAlbums() {
        val snapshot = BookCatalogSnapshot().withPage(page("a", "b"), 1).withPage(page("b", "c"), 2)
        OnlineCache.put("book.rank.20.128", snapshot)
        val restored = checkNotNull(OnlineCache.get<BookCatalogSnapshot>("book.rank.20.128", 10000))
        assertEquals(listOf("a", "b", "c"), restored.items.map { it.id })
        assertEquals(3, restored.page + 1)
        assertTrue(restored.hasMore)
    }

    @Test fun refreshReplacesTheOldPagesWithoutMutatingTheDisplayedSnapshot() {
        val old = BookCatalogSnapshot().withPage(page("old"), 3)
        val refreshed = old.withPage(page("fresh", hasMore = false), 1)
        assertEquals(listOf("old"), old.items.map { it.id })
        assertEquals(listOf("fresh"), refreshed.items.map { it.id })
        assertEquals(1, refreshed.page)
        assertFalse(refreshed.hasMore)
    }

    @Test fun exhaustionComesFromTheResponseNotTheCachedItemCount() {
        val terminal = BookCatalogSnapshot().withPage(page(*(1..100).map(Int::toString).toTypedArray(), hasMore = false), 2)
        assertFalse(terminal.hasMore)
        val empty = BookCatalogSnapshot().withPage(page(hasMore = false), 1)
        OnlineCache.put("book.rank.14.28", empty)
        assertSame(empty, OnlineCache.peek<BookCatalogSnapshot>("book.rank.14.28"))
        assertFalse(empty.hasMore)
        assertEquals(1, empty.page)
    }

    @Test fun authorWorksAreDeduplicatedAcrossPagesWithoutResettingCursor() {
        val snapshot = BookCatalogSnapshot().withPage(page("book-a", "book-b"), 1)
            .withPage(page("book-b", "book-c"), 2)
        OnlineCache.put("book.author.fixture", snapshot)
        val restored = checkNotNull(OnlineCache.get<BookCatalogSnapshot>("book.author.fixture", 10000))
        assertEquals(listOf("book-a", "book-b", "book-c"), restored.items.map { it.id })
        assertEquals(3, restored.page + 1)
    }

    @Test fun filteredEmptyPageCanContinueWithoutDiscardingEarlierWorks() {
        val first = BookCatalogSnapshot().withPage(page("book-a"), 1)
        val emptyPage = first.withPage(page(hasMore = true), 2)
        assertEquals(listOf("book-a"), emptyPage.items.map { it.id })
        assertEquals(2, emptyPage.page)
        assertTrue(emptyPage.hasMore)
    }

    @Test fun authorsAndRanksNeverShareTheirCache() {
        val first = BookCatalogSnapshot().withPage(page("a"), 1)
        val second = BookCatalogSnapshot().withPage(page("b"), 2)
        OnlineCache.put("book.author.fixture-a", first)
        OnlineCache.put("book.author.fixture-b", second)
        OnlineCache.put("book.rank.1.30", BookCatalogSnapshot())
        assertSame(first, OnlineCache.peek<BookCatalogSnapshot>("book.author.fixture-a"))
        assertSame(second, OnlineCache.peek<BookCatalogSnapshot>("book.author.fixture-b"))
    }

    @Test fun categoriesUseIndependentCacheEntries() {
        val first = BookCatalogSnapshot().withPage(page("first"), 2)
        val second = BookCatalogSnapshot().withPage(page("second", hasMore = false), 1)
        OnlineCache.put("book.rank.1.30", first)
        OnlineCache.put("book.rank.1.31", second)
        assertSame(first, OnlineCache.peek<BookCatalogSnapshot>("book.rank.1.30"))
        assertSame(second, OnlineCache.peek<BookCatalogSnapshot>("book.rank.1.31"))
    }
}
