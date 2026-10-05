package com.leyu.melora.ui.common

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookChapterSearchTest {
    @Test
    fun blankQueryParsesToEmptyNameQuery() {
        assertEquals(BookChapterQuery("", isEpisode = false, episode = null), parseBookChapterQuery(" \n\t "))
    }

    @Test
    fun trimsAsciiDigitsAndParsesEpisode() {
        assertEquals(BookChapterQuery("1000", isEpisode = true, episode = 1000), parseBookChapterQuery("  1000  "))
    }

    @Test
    fun parsesFullWidthDigitsAsEpisode() {
        assertEquals(BookChapterQuery("１０００", isEpisode = true, episode = 1000), parseBookChapterQuery("１０００"))
    }

    @Test
    fun parsesArabicChapterMarkerAsEpisode() {
        assertEquals(BookChapterQuery("第1000章", isEpisode = true, episode = 1000), parseBookChapterQuery("第1000章"))
    }

    @Test
    fun parsesEpisodeSuffixAsEpisode() {
        assertEquals(BookChapterQuery("1000集", isEpisode = true, episode = 1000), parseBookChapterQuery("1000集"))
    }

    @Test
    fun parsesChineseNumeralChapterMarkerAsEpisode() {
        assertEquals(BookChapterQuery("第一千章", isEpisode = true, episode = 1000), parseBookChapterQuery("第一千章"))
    }

    @Test
    fun parsesSpacedFullWidthEpisodeMarker() {
        assertEquals(BookChapterQuery("第 １０００ 集", isEpisode = true, episode = 1000), parseBookChapterQuery("第 １０００ 集"))
    }

    @Test
    fun zeroIsEpisodeQueryWithoutValidOrdinal() {
        assertEquals(BookChapterQuery("0", isEpisode = true, episode = null), parseBookChapterQuery("0"))
    }

    @Test
    fun overflowingEpisodeIsClassifiedButHasNoOrdinal() {
        assertEquals(
            BookChapterQuery("2147483648", isEpisode = true, episode = null),
            parseBookChapterQuery("2147483648"),
        )
    }

    @Test
    fun mixedTitleContainingEpisodeMarkerRemainsNameQuery() {
        val raw = " 仙逆 第1000集 炼制仙卫 "
        assertEquals(BookChapterQuery(raw.trim(), isEpisode = false, episode = null), parseBookChapterQuery(raw))
    }

    @Test
    fun yearContainingDigitsRemainsNameQuery() {
        assertEquals(BookChapterQuery("1984年", isEpisode = false, episode = null), parseBookChapterQuery("1984年"))
    }

    @Test
    fun chineseNumberWithoutChapterMarkerRemainsNameQuery() {
        assertEquals(BookChapterQuery("一千", isEpisode = false, episode = null), parseBookChapterQuery("一千"))
    }

    @Test
    fun blankQueryDoesNotLoadAnyPage() = runBlocking {
        var loadCount = 0

        searchBookChapters(
            query = " \n\t ",
            pages = mutableMapOf(),
            load = {
                loadCount++
                error("blank queries must not load pages")
            },
            onUpdate = {},
        )

        assertEquals(0, loadCount)
    }

    @Test
    fun cachedHighPageMatchesAreEmittedBeforeMissingPagesLoad() = runBlocking {
        val cachedMatch = song("cached", "远处的目标章节")
        val pages = mutableMapOf(4 to bookPage(4, listOf(cachedMatch), hasMore = false))
        val loadedPages = mutableListOf<Int>()
        val updates = mutableListOf<BookChapterSearchProgress>()
        val events = mutableListOf<String>()

        searchBookChapters(
            query = "目标",
            pages = pages,
            load = { pageNumber ->
                loadedPages += pageNumber
                events += "load:$pageNumber"
                bookPage(pageNumber, listOf(song("page-$pageNumber", "普通章节$pageNumber")), hasMore = true)
            },
            onUpdate = {
                updates += it
                events += "update"
            },
        )

        assertEquals("update", events.first())
        assertEquals(listOf(cachedMatch.uid), updates.first().matches.map { it.song.uid })
        assertEquals(listOf(1, 2, 3), loadedPages)
        assertEquals(listOf(1, 2, 3, 4), pages.keys.sorted())
        assertEquals(listOf(cachedMatch.uid), updates.last().matches.map { it.song.uid })
        assertTrue(updates.last().complete)
    }

    @Test
    fun unknownTotalScansToFarPageAndOnlyCompletesOnHasMoreFalse() = runBlocking {
        val requests = mutableListOf<Int>()
        val updates = mutableListOf<BookChapterSearchProgress>()
        val target = song("far-target", "终于找到目标章节")

        searchBookChapters(
            query = "目标",
            pages = mutableMapOf(),
            load = { pageNumber ->
                requests += pageNumber
                bookPage(
                    pageNumber,
                    listOf(if (pageNumber == 4) target else song("page-$pageNumber", "无关章节$pageNumber")),
                    hasMore = pageNumber < 4,
                )
            },
            onUpdate = { updates += it },
        )

        assertEquals(listOf(1, 2, 3, 4), requests)
        assertEquals(null, updates.last().totalPages)
        assertEquals(listOf(target.uid), updates.last().matches.map { it.song.uid })
        assertTrue(updates.last().complete)
        assertTrue(updates.dropLast(1).none { it.complete })

        val knownTotalRequests = mutableListOf<Int>()
        val knownTotalUpdates = mutableListOf<BookChapterSearchProgress>()
        searchBookChapters(
            query = "章节",
            pages = mutableMapOf(),
            load = { pageNumber ->
                knownTotalRequests += pageNumber
                bookPage(
                    pageNumber,
                    listOf(song("known-$pageNumber", "章节$pageNumber")),
                    hasMore = pageNumber == 1,
                    total = 1,
                )
            },
            onUpdate = { knownTotalUpdates += it },
        )

        assertEquals(listOf(1, 2), knownTotalRequests)
        assertTrue(knownTotalUpdates.dropLast(1).none { it.complete })
        assertTrue(knownTotalUpdates.last().complete)
    }

    @Test
    fun resultsStayInPageOrderAndDeduplicateUidNotChapterName() = runBlocking {
        val repeatedOnPageOne = song("same-uid", "相同章节名")
        val firstDistinct = song("first", "相同章节名")
        val repeatedOnPageTwo = song("same-uid", "相同章节名")
        val secondDistinct = song("second", "相同章节名")
        val firstPage = bookPage(1, listOf(repeatedOnPageOne, firstDistinct), hasMore = true)
        val secondPage = bookPage(2, listOf(repeatedOnPageTwo, secondDistinct), hasMore = false)
        val updates = mutableListOf<BookChapterSearchProgress>()

        searchBookChapters(
            query = "相同章节名",
            pages = mutableMapOf(),
            load = { if (it == 1) firstPage else secondPage },
            onUpdate = { updates += it },
        )

        val matches = updates.last().matches
        assertEquals(listOf(repeatedOnPageOne.uid, firstDistinct.uid, secondDistinct.uid), matches.map { it.song.uid })
        assertEquals(1, matches[0].page.page)
        assertEquals(1, matches[1].page.page)
        assertEquals(2, matches[2].page.page)
        assertSame(repeatedOnPageOne, matches[0].song)
    }

    @Test
    fun queryTrimsWhitespaceAndMatchesCaseInsensitivelyAsSubstring() = runBlocking {
        val match = song("mixed", "前缀 MiXeD 目标章节 后缀")
        val updates = mutableListOf<BookChapterSearchProgress>()

        searchBookChapters(
            query = "  mIxEd  ",
            pages = mutableMapOf(),
            load = { bookPage(it, listOf(match), hasMore = false) },
            onUpdate = { updates += it },
        )

        assertEquals(listOf(match.uid), updates.last().matches.map { it.song.uid })
    }

    @Test
    fun cachedPagesAreReusedAcrossQueries() = runBlocking {
        val pages = mutableMapOf<Int, KwBookApi.BookChapters>()
        val loadedPages = mutableListOf<Int>()
        val alpha = song("alpha", "Alpha章节")
        val beta = song("beta", "Beta章节")

        searchBookChapters(
            query = "alpha",
            pages = pages,
            load = { pageNumber ->
                loadedPages += pageNumber
                if (pageNumber == 1) {
                    bookPage(1, listOf(alpha), hasMore = true)
                } else {
                    bookPage(2, listOf(beta), hasMore = false)
                }
            },
            onUpdate = {},
        )
        val updates = mutableListOf<BookChapterSearchProgress>()
        searchBookChapters(
            query = "beta",
            pages = pages,
            load = {
                loadedPages += it
                error("cached pages should be reused for another query")
            },
            onUpdate = { updates += it },
        )

        assertEquals(listOf(1, 2), loadedPages)
        assertEquals(listOf(beta.uid), updates.last().matches.map { it.song.uid })
        assertTrue(updates.last().complete)
    }

    @Test
    fun failedLoadKeepsPartialResultsAndRetryLoadsOnlyMissingPage() = runBlocking {
        val pages = mutableMapOf<Int, KwBookApi.BookChapters>()
        val requested = mutableListOf<Int>()
        val partialUpdates = mutableListOf<BookChapterSearchProgress>()
        val firstMatch = song("first", "目标章节一")
        val secondMatch = song("second", "目标章节二")
        val failure = IllegalStateException("temporary load failure")

        val actualFailure = runCatching {
            searchBookChapters(
                query = "目标",
                pages = pages,
                load = { pageNumber ->
                    requested += pageNumber
                    if (pageNumber == 1) bookPage(1, listOf(firstMatch), hasMore = true) else throw failure
                },
                onUpdate = { partialUpdates += it },
            )
        }.exceptionOrNull()

        assertSame(failure, actualFailure)
        assertTrue(pages.containsKey(1))
        assertFalse(pages.containsKey(2))
        assertTrue(partialUpdates.any { progress ->
            progress.matches.map { it.song.uid } == listOf(firstMatch.uid) && !progress.complete
        })

        val retryUpdates = mutableListOf<BookChapterSearchProgress>()
        searchBookChapters(
            query = "目标",
            pages = pages,
            load = { pageNumber ->
                requested += pageNumber
                assertEquals(2, pageNumber)
                bookPage(2, listOf(secondMatch), hasMore = false)
            },
            onUpdate = { retryUpdates += it },
        )

        assertEquals(listOf(1, 2, 2), requested)
        assertEquals(listOf(firstMatch.uid, secondMatch.uid), retryUpdates.last().matches.map { it.song.uid })
        assertTrue(retryUpdates.last().complete)
    }

    @Test
    fun cancellationPropagatesWithoutCompletionOrFurtherRequests() = runBlocking {
        val pages = mutableMapOf<Int, KwBookApi.BookChapters>()
        val requested = mutableListOf<Int>()
        val updates = mutableListOf<BookChapterSearchProgress>()
        val secondPageStarted = CompletableDeferred<Unit>()
        val firstMatch = song("first", "目标章节")

        val job = launch {
            searchBookChapters(
                query = "目标",
                pages = pages,
                load = { pageNumber ->
                    requested += pageNumber
                    if (pageNumber == 1) {
                        bookPage(1, listOf(firstMatch), hasMore = true)
                    } else {
                        secondPageStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        error("cancelled load must not continue")
                    }
                },
                onUpdate = { updates += it },
            )
        }

        secondPageStarted.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(listOf(1, 2), requested)
        assertTrue(pages.containsKey(1))
        assertFalse(pages.containsKey(2))
        assertTrue(updates.isNotEmpty())
        assertTrue(updates.none { it.complete })
    }

    @Test
    fun repeatedWholePageThrowsAndDoesNotCacheDuplicatePage() = runBlocking {
        val pages = mutableMapOf<Int, KwBookApi.BookChapters>()
        val first = song("repeated", "目标章节")
        val firstPage = bookPage(1, listOf(first), hasMore = true)
        val updates = mutableListOf<BookChapterSearchProgress>()

        val failure = runCatching {
            searchBookChapters(
                query = "目标",
                pages = pages,
                load = { pageNumber ->
                    if (pageNumber == 1) firstPage else bookPage(2, listOf(song("repeated", "目标章节")), hasMore = true)
                },
                onUpdate = { updates += it },
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(firstPage, pages[1])
        assertFalse(pages.containsKey(2))
        assertTrue(updates.none { it.complete })
    }

    @Test
    fun emptyPageIsFailureRatherThanSuccessfulNoMatchOrCompletion() = runBlocking {
        val pages = mutableMapOf<Int, KwBookApi.BookChapters>()
        val updates = mutableListOf<BookChapterSearchProgress>()

        val failure = runCatching {
            searchBookChapters(
                query = "missing",
                pages = pages,
                load = { pageNumber -> bookPage(pageNumber, emptyList(), hasMore = false) },
                onUpdate = { updates += it },
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(pages.isEmpty())
        assertTrue(updates.none { it.complete })
    }

    private fun song(id: String, name: String) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", name),
    )

    private fun bookPage(
        page: Int,
        items: List<OnlineSong>,
        hasMore: Boolean,
        total: Int = 0,
    ) = KwBookApi.BookChapters(
        items = items,
        hasMore = hasMore,
        metadata = KwBookApi.BookMetadata(total = total),
        page = page,
    )
}
