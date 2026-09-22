package com.leyu.melora.playback.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwBookApiAuthorAlbumsTest {
    @Test
    fun authorMatchesWholeNameSegmentsOnly() {
        assertTrue(KwBookApi.authorMatches("单田芳", "鞍山广播评书&单田芳"))
        assertTrue(KwBookApi.authorMatches("单田芳&刘兰芳", "演播团队/刘兰芳"))
        assertTrue(KwBookApi.authorMatches("刘兰芳", "嗨翻屋HIFIVE、刘兰芳"))
        assertFalse(KwBookApi.authorMatches("张三", "大刀张三&有声剧社"))
        assertFalse(KwBookApi.authorMatches("张三", "张三丰"))
        assertFalse(KwBookApi.authorMatches("C", "C++"))
        assertFalse(KwBookApi.authorMatches("单田芳", "鞍山广播评书"))
    }

    @Test
    fun filteredEmptyPageKeepsSearchHasMore() {
        val sourcePage = KwBookApi.BookPage(
            items = listOf(
                album("not-owned", "张三丰"),
                album("missing-author", ""),
            ),
            hasMore = true,
        )

        val filtered = KwBookApi.filterAuthorAlbums("张三", sourcePage)

        assertTrue(filtered.items.isEmpty())
        assertTrue(filtered.hasMore)
    }

    @Test
    fun filteringDoesNotScanPastTheCurrentSearchPage() {
        val sourcePage = KwBookApi.BookPage(
            items = listOf(
                album("owned", "张三&有声剧社"),
                album("not-owned", "张三丰"),
            ),
            hasMore = true,
        )

        val filtered = KwBookApi.filterAuthorAlbums("张三", sourcePage)

        assertEquals(listOf("book_album_owned"), filtered.items.map { it.id })
        assertTrue(filtered.hasMore)
    }

    private fun album(id: String, author: String) = OnlinePlaylist(
        JSONObject()
            .put("id", "book_album_$id")
            .put("name", id)
            .put("author", author),
    )
}
