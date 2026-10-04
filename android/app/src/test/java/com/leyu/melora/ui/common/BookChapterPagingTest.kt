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

        val initial = BookCatalogState(mapOf(1 to chapters(1, 1..100)))
        val (pending, generation) = initial.beginRequest()
        val window = pending.accept(generation, requestedPage, chapters(10, 901..1_000))

        assertEquals(setOf(10), window.pages.keys)
        assertEquals(100, window.items?.size)
        assertEquals("chapter-901", window.items?.first()?.songmid)
        assertEquals(901, window.items?.first()?.raw?.optInt("bookOrdinal"))
        assertEquals("chapter-1000", window.items?.last()?.songmid)
    }

    @Test
    fun descendingMapsTheCompleteKnownBookAndStopsAtBothEdges() {
        assertEquals(10, BookCatalogPaging.pageForDirection(1, total = 1_000, descending = true))
        assertEquals(1, BookCatalogPaging.pageForDirection(10, total = 1_000, descending = true))
    }

    @Test
    fun stalePageResponseCannotReplaceTheNewestWindow() {
        val initial = BookCatalogState(mapOf(1 to chapters(1, 1..100)))
        val (requestingTen, oldGeneration) = initial.beginRequest()
        val (requestingFour, newGeneration) = requestingTen.beginRequest()
        val pageFour = requestingFour.accept(newGeneration, 4, chapters(4, 301..400))
        val afterOldResponse = pageFour.accept(oldGeneration, 10, chapters(10, 901..1_000))

        assertEquals(setOf(4), afterOldResponse.pages.keys)
        assertEquals("chapter-301", afterOldResponse.items?.first()?.songmid)
    }

    @Test
    fun emptyResponseKeepsVisibleWindowAndUnknownTotalDoesNotInventRanges() {
        val initial = BookCatalogState(mapOf(3 to chapters(3, 201..250)))
        val (pending, generation) = initial.beginRequest()
        val afterEmpty = pending.accept(generation, 10, KwBookApi.BookChapters(emptyList(), false, page = 10))

        assertSame(pending, afterEmpty)
        assertEquals(setOf(3), afterEmpty.pages.keys)
        assertEquals("chapter-201", afterEmpty.items?.first()?.songmid)
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

    @Test fun scrollingAppendsBothDirectionsWithoutLosingExistingChapters() {
        var state = BookCatalogState(mapOf(10 to chapters(10, 901..1000)))
        val (pending, generation) = state.beginRequest()
        state = pending.accept(generation, 9, chapters(9, 801..900), append = true)
        assertEquals(200, state.items.size)
        assertEquals(8, state.nextPage(true))
        assertNull(state.nextPage(false))
        assertEquals("chapter-801", state.items.first().songmid)
        assertEquals("chapter-1000", state.items.last().songmid)
        val first = BookCatalogState(mapOf(1 to chapters(1, 1..100)))
        val second = first.accept(0, 2, chapters(2, 101..200), append = true)
        assertEquals(200, second.items.size)
        assertEquals(3, second.nextPage(false))
        assertNull(second.nextPage(true))
        assertSame(second, second.accept(0, 4, chapters(4, 301..400), append = true))
    }

    @Test fun duplicateAndFailedPagesKeepExistingRowsAndCannotRunAway() {
        val first = BookCatalogState(mapOf(1 to chapters(1, 1..100)))
        assertSame(first, first.accept(0, 2, chapters(2, 1..100), append = true))
        assertSame(first, first.accept(0, 2, KwBookApi.BookChapters(emptyList(), false), append = true))
        val second = first.accept(0, 2, chapters(2, 100..199), append = true)
        assertEquals(199, second.items.size)
        val refreshed = second.accept(0, 2, chapters(2, 100..199), append = true)
        assertEquals(second.items.map { it.uid }, refreshed.items.map { it.uid })
    }

    @Test fun staleOrdinalIsOnlyAHintAndExactUidIsFoundInAnotherPage() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<Int>()
        val target = chapter(203, 3)
        val found = findBookChapterPage(2, { it.uid == target.uid }) { page ->
            requests += page
            chapters(page, ((page - 1) * 100 + 1)..(page * 100))
        }
        assertEquals(3, found?.page)
        assertEquals(listOf(2, 1, 3), requests)
        assertTrue(found!!.items.any { it.uid == target.uid })
    }

    @Test fun missingUidNeverFallsBackToAnotherChapterAndRepeatedPagesStopSearch() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<Int>()
        assertNull(findBookChapterPage(1, { it.uid == "missing" }) { page ->
            requests += page
            chapters(page, 1..100)
        })
        assertEquals(listOf(1, 2), requests)
        assertNull(findBookChapterPage(5, { it.uid == "missing" }) { page ->
            chapters(page, 1..100).copy(hasMore = false)
        })
    }

    @Test fun endRangeDoesNotWrapBackwardsWhenSavedPageExceedsNewTotal() {
        assertNull(BookCatalogPaging.rangeEnd(3, 150))
        assertEquals(150, BookCatalogPaging.rangeEnd(2, 150))
    }

    @Test fun titleEpisodeIsNotAnAlbumNumberDirectoryPositionOrTrack() {
        assertEquals(100, bookEpisodeNumber("第100集 龙岭迷窟 41 藏宝洞"))
        assertEquals(101, bookEpisodeNumber("第101集 龙岭迷窟 42 流沙门"))
        assertEquals(1, bookEpisodeNumber("第001集 穿越的唐家三少"))
        assertEquals(100, bookEpisodeNumber("第一百章 风起"))
        assertEquals(1002, bookEpisodeNumber("第一千零二回"))
        assertEquals(12, bookEpisodeNumber("第０１２集"))
        assertEquals(23, bookEpisodeNumber("0023 风起"))
        assertNull(bookEpisodeNumber("鬼吹灯1 主题曲"))
        assertNull(bookEpisodeNumber("全新《鬼吹灯》有声剧-剧情预告，精彩抢先听"))
        assertNull(bookEpisodeNumber("【花絮】胡八一&王胖子唱红歌"))
        assertNull(bookEpisodeNumber("2026年大结局预告"))
    }

    @Test fun episodeLookupCrossesIntroAndMidBookBonusWithoutAFixedOffset() = kotlinx.coroutines.runBlocking {
        val sourcePages = mapOf(
            1 to KwBookApi.BookChapters(listOf(named("主题曲"), named("第97集 龙岭迷窟 38 黑水城")), true, page = 1),
            2 to KwBookApi.BookChapters(listOf(named("第98集"), named("第99集"), named("第100集 龙岭迷窟 41 藏宝洞"),
                named("【花絮】胡八一&王胖子唱红歌"), named("第101集 龙岭迷窟 42 流沙门")), false, page = 2),
        )
        for (episode in listOf(100, 101)) {
            val matches: (OnlineSong) -> Boolean = { bookEpisodeNumber(it.name) == episode }
            val result = findBookChapterPage(BookCatalogPaging.pageForOrdinal(episode)!!, matches) { sourcePages.getValue(it) }
            assertEquals(2, result?.page)
            assertEquals(episode, bookEpisodeNumber(result!!.items.first(matches).name))
        }
        assertNull(findBookChapterPage(1, { bookEpisodeNumber(it.name) == 96 }) { sourcePages.getValue(it) })
    }

    private fun named(name: String) = OnlineSong(JSONObject().put("source", "kw").put("songmid", name).put("name", name))

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
