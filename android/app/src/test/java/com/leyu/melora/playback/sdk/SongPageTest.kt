package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SongPageTest {
    @Test fun filteredMiddlePageKeepsCursorAndLaterPlayableSongs() {
        val first = page(1, 1..30, total = 75, all = 3)
        val second = first.append(page(2, IntRange.EMPTY, total = 75, all = 3, raw = 30), 2)
        assertEquals(2, second.page)
        assertEquals(60, second.receivedCount)
        assertEquals(30, second.list.size)
        assertTrue(second.hasMore())
        val last = second.append(page(3, 61..75, total = 75, all = 3), 3)
        assertEquals((1..30).toList() + (61..75).toList(), last.list.map { it.songmid.toInt() })
        assertEquals(75, last.receivedCount)
        assertEquals(15, last.rawCount)
        assertFalse(last.hasMore())
    }

    @Test fun totalUsesReceivedEntriesNotFilteredOrDeduplicatedRows() {
        val first = page(1, 1..2, total = 8, raw = 4, size = 4)
        val last = first.append(page(2, 3..4, total = 8, raw = 4, size = 4), 2)
        assertEquals(4, last.list.size)
        assertEquals(8, last.receivedCount)
        assertFalse(last.hasMore())
        assertEquals(4, last.pageSize)
    }

    @Test fun unknownTotalUsesRawPageSizeIncludingFullyFilteredPages() {
        val first = page(1, 1..2, raw = 4, size = 4)
        val filtered = first.append(page(2, IntRange.EMPTY, raw = 4, size = 4), 2)
        assertTrue(filtered.hasMore())
        val partial = filtered.append(page(3, 9..9, raw = 1, size = 4), 3)
        assertFalse(partial.hasMore())
        assertEquals(9, partial.receivedCount)
    }

    @Test fun repeatedNonEmptyPageStillStopsWithAndWithoutTotals() {
        for (all in listOf(0, 10)) {
            val first = page(1, 1..30, all = all)
            val repeated = first.append(page(2, 1..30, all = all), 2)
            assertEquals(30, repeated.list.size)
            assertFalse(repeated.hasMore())
        }
    }

    @Test fun rawEmptyPageStopsEvenWhenMetadataClaimsMore() {
        val first = page(1, 1..30, total = 90, all = 3)
        val stopped = first.append(page(2, IntRange.EMPTY, total = 90, all = 3), 2)
        assertFalse(stopped.hasMore())
        assertEquals(first.list, stopped.list)
    }

    @Test fun firstFilteredPagesAreReadBeforeTheEmptyViewCanTerminatePaging() = runBlocking {
        val calls = mutableListOf<Int>()
        val first = page(1, IntRange.EMPTY, total = 9, all = 3, raw = 3, size = 3)
            .forRequestedPage(1)
        val playable = first.loadUntilPlayable { requested ->
            calls += requested
            if (requested == 2) page(1, IntRange.EMPTY, total = 9, all = 3, raw = 3, size = 3)
            else page(1, 7..9, total = 9, all = 3, size = 3)
        }
        assertEquals(listOf(2, 3), calls)
        assertEquals(3, playable.page)
        assertEquals(9, playable.receivedCount)
        assertEquals(listOf("7", "8", "9"), playable.list.map { it.songmid })
        assertFalse(playable.hasMore())
    }

    @Test fun fullyUnavailableFiniteCatalogEndsWithoutCallingPastItsLastPage() = runBlocking {
        var calls = 0
        val result = page(1, IntRange.EMPTY, total = 6, all = 2, raw = 3, size = 3)
            .loadUntilPlayable { calls++; page(2, IntRange.EMPTY, total = 6, all = 2, raw = 3, size = 3) }
        assertEquals(1, calls)
        assertTrue(result.list.isEmpty())
        assertFalse(result.hasMore())
    }

    @Test fun missingTotalsCannotCauseUnboundedFilteredRequests() = runBlocking {
        var calls = 0
        val failure = runCatching {
            page(1, IntRange.EMPTY, raw = 3, size = 3).loadUntilPlayable { requested ->
                calls++
                page(requested, IntRange.EMPTY, raw = 3, size = 3)
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(500, calls)
    }

    @Test fun filteredFirstPageReadHonorsCancellationBeforeAnotherFetch() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                page(1, IntRange.EMPTY, raw = 3, size = 3).loadUntilPlayable { requested ->
                    calls++
                    currentCoroutineContext().cancel()
                    page(requested, IntRange.EMPTY, raw = 3, size = 3)
                }
            }
        }
        assertEquals(1, calls)
    }

    private fun page(number: Int, ids: IntRange, total: Int = 0, all: Int = 0,
        raw: Int = ids.count(), size: Int = 30) = SongPage(
        list = ids.map { OnlineSong(JSONObject().put("source", "kg").put("songmid", "$it").put("name", "$it")) },
        total = total, page = number, allPage = all, rawCount = raw, pageSize = size,
    )
}
