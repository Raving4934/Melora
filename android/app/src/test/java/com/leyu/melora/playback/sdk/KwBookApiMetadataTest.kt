package com.leyu.melora.playback.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwBookApiMetadataTest {
    @Test
    fun detailMetadataReadsIntroHeatAndCatalogFacts() {
        val metadata = KwBookApi.bookMetadataFromDetail(JSONObject("""
            {
              "albuminfo": "<b>真实节目简介</b>",
              "playCnt": "309997779",
              "total": "1250",
              "artist": "演播者",
              "pic": "https://img1.kuwo.cn/cover.jpg",
              "releaseDate": "2024-03-05",
              "lang": "国语"
            }
        """))

        assertEquals("真实节目简介", metadata.description)
        assertEquals(309_997_779L, metadata.playCount)
        assertEquals(1250, metadata.total)
        assertEquals("演播者", metadata.author)
        assertEquals("https://img1.kuwo.cn/cover.jpg", metadata.artwork)
        assertEquals("2024-03-05", metadata.releaseDate)
        assertEquals("国语", metadata.language)
    }

    @Test
    fun bookPageHasMoreUsesTotalToStopOnAnExactLastPage() {
        assertFalse(KwBookApi.bookPageHasMore(page = 1, total = 100, itemCount = 100))
        assertTrue(KwBookApi.bookPageHasMore(page = 1, total = 101, itemCount = 100))
        assertTrue(KwBookApi.bookPageHasMore(page = 2, total = 201, itemCount = 100))
        assertFalse(KwBookApi.bookPageHasMore(page = 3, total = 201, itemCount = 1))
        assertFalse(KwBookApi.bookPageHasMore(page = 2, total = 0, itemCount = 0))
    }

    @Test
    fun bookChapterSnapshotKeepsCursorTotalAndStopsDuplicatePages() {
        val metadata = KwBookApi.BookMetadata(total = 200, author = "演播者")
        val firstPage = KwBookApi.BookChapters(
            items = List(100) { chapter("first-$it") },
            hasMore = true,
            metadata = metadata,
            page = 1,
        )
        val secondPage = KwBookApi.BookChapters(
            items = List(100) { chapter("second-$it") },
            hasMore = false,
            metadata = metadata,
            page = 2,
        )

        val merged = firstPage.append(secondPage, requestedPage = 2)
        assertEquals(2, merged.page)
        assertEquals(200, merged.total)
        assertEquals(200, merged.items.size)
        assertFalse(merged.hasMore)

        val duplicate = merged.append(secondPage, requestedPage = 3)
        assertEquals(3, duplicate.page)
        assertEquals(200, duplicate.items.size)
        assertFalse(duplicate.hasMore)
    }

    private fun chapter(id: String) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", id)
            .put("isBookChapter", true),
    )
}
