package com.leyu.melora.playback.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineContentBoundaryTest {
    @Test
    fun identifiesAudiobookChapterFromCanonicalMarker() {
        val music = song("music")
        val chapter = song("chapter", isBookChapter = true)

        assertFalse(music.isBookChapter)
        assertTrue(chapter.isBookChapter)
    }

    @Test
    fun musicCatalogExcludesAudiobookChapters() {
        val music = song("music")
        val chapter = song("chapter", isBookChapter = true)

        assertEquals(listOf(music), listOf(music, chapter).musicOnly())
    }

    @Test
    fun paginationUsesMetadataRatherThanAssumingThirtySongsPerPage() {
        val shortPage = SongPage(listOf(song("one")), total = 2, page = 1, allPage = 2)
        assertTrue(shortPage.hasMore())
        assertFalse(shortPage.copy(page = 2).hasMore(2))
        val fullLastPage = shortPage.copy(list = List(30) { song("$it") }, total = 30, allPage = 1)
        assertFalse(fullLastPage.hasMore())
        assertTrue(fullLastPage.copy(total = 0, allPage = 0).hasMore())
        assertFalse(fullLastPage.copy(list = emptyList(), allPage = 2).hasMore())
        assertTrue(shortPage.copy(allPage = 0).hasMore(1))
        assertFalse(shortPage.copy(allPage = 0).hasMore(2))
    }

    @Test
    fun mergedSongSnapshotKeepsRequestedCursorAndStopsDuplicatePages() {
        val firstPage = SongPage(
            list = List(30) { song("first-$it") },
            total = 60,
            page = 1,
            allPage = 2,
        )
        val secondPage = SongPage(
            list = List(30) { song("second-$it") },
            total = 60,
            page = 1,
            allPage = 2,
        )

        assertEquals(2, secondPage.forRequestedPage(2).page)
        val merged = firstPage.append(secondPage, requestedPage = 2)
        assertEquals(2, merged.page)
        assertEquals(60, merged.list.size)
        assertFalse(merged.hasMore())

        val duplicate = merged.append(secondPage, requestedPage = 3)
        assertEquals(3, duplicate.page)
        assertEquals(60, duplicate.list.size)
        assertFalse(duplicate.hasMore())
    }

    @Test
    fun unknownTotalKeepsPagingOnlyWhenTheNextPageAddsSongs() {
        val firstPage = SongPage(List(30) { song("unknown-$it") }, 0, 1, 0)
        val nextPage = SongPage(List(30) { song("unknown-${it + 30}") }, 0, 1, 0)

        val merged = firstPage.append(nextPage, requestedPage = 2)
        assertTrue(merged.hasMore())

        val duplicate = merged.append(nextPage, requestedPage = 3)
        assertFalse(duplicate.hasMore())
    }

    private fun song(id: String, isBookChapter: Boolean = false): OnlineSong = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", id)
            .put("singer", "artist")
            .put("isBookChapter", isBookChapter),
    )
}
