package com.leyu.melora.ui.common

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookChapterPagingTest {
    @Test
    fun ordinalTenHundredRequestsOnlyPageTenWindow() {
        val requestedPage = checkNotNull(BookCatalogPaging.pageForOrdinal(1_000))
        assertEquals(10, requestedPage)

        val initial = BookCatalogWindowState(1, chapters(1, 1..100))
        val (pending, generation) = initial.beginRequest()
        val window = pending.accept(generation, requestedPage, chapters(10, 901..1_000))

        assertEquals(10, window.page)
        assertEquals(100, window.chapters?.items?.size)
        assertEquals("chapter-901", window.chapters?.items?.first()?.songmid)
        assertEquals(901, window.chapters?.items?.first()?.raw?.optInt("bookOrdinal"))
        assertEquals("chapter-1000", window.chapters?.items?.last()?.songmid)
    }

    @Test
    fun descendingMapsTheCompleteKnownBookAndStopsAtBothEdges() {
        assertEquals(10, BookCatalogPaging.pageForDirection(1, total = 1_000, descending = true))
        assertEquals(1, BookCatalogPaging.pageForDirection(10, total = 1_000, descending = true))
    }

    @Test
    fun stalePageResponseCannotReplaceTheNewestWindow() {
        val initial = BookCatalogWindowState(1, chapters(1, 1..100))
        val (requestingTen, oldGeneration) = initial.beginRequest()
        val (requestingFour, newGeneration) = requestingTen.beginRequest()
        val pageFour = requestingFour.accept(newGeneration, 4, chapters(4, 301..400))
        val afterOldResponse = pageFour.accept(oldGeneration, 10, chapters(10, 901..1_000))

        assertEquals(4, afterOldResponse.page)
        assertEquals("chapter-301", afterOldResponse.chapters?.items?.first()?.songmid)
    }

    @Test
    fun emptyResponseKeepsVisibleWindowAndUnknownTotalDoesNotInventRanges() {
        val initial = BookCatalogWindowState(3, chapters(3, 201..250))
        val (pending, generation) = initial.beginRequest()
        val afterEmpty = pending.accept(generation, 10, KwBookApi.BookChapters(emptyList(), false, page = 10))

        assertSame(pending, afterEmpty)
        assertEquals(3, afterEmpty.page)
        assertEquals("chapter-201", afterEmpty.chapters?.items?.first()?.songmid)
        assertNull(BookCatalogPaging.pageCount(null))
        assertNull(BookCatalogPaging.rangeEnd(3, null))
        assertNull(BookCatalogPaging.pageForDirection(1, total = null, descending = true))
    }

    @Test
    fun ordinarySongPagesStillAppendWithoutChangingTheirPagingContract() {
        val first = SongPage(listOf(music("a"), music("b")), total = 4, page = 1, allPage = 2)
        val second = SongPage(listOf(music("c"), music("d")), total = 4, page = 2, allPage = 2)
        val merged = first.append(second, requestedPage = 2)

        assertEquals(listOf("a", "b", "c", "d"), merged.list.map { it.songmid })
        assertEquals(2, merged.page)
        assertFalse(merged.hasMore())
    }

    private fun chapters(page: Int, ordinals: IntRange) = KwBookApi.BookChapters(
        items = ordinals.map { ordinal -> chapter(ordinal, page) },
        hasMore = page < 10,
        metadata = KwBookApi.BookMetadata(total = 1_000),
        page = page,
    )

    private fun chapter(ordinal: Int, page: Int) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", "chapter-$ordinal")
            .put("name", "标题数字不参与序号：第 $ordinal 章")
            .put("albumId", "book")
            .put("isBookChapter", true)
            .put("bookOrdinal", ordinal)
            .put("bookPage", page),
    )

    private fun music(id: String) = OnlineSong(
        JSONObject().put("source", "test").put("songmid", id).put("name", id),
    )
}
