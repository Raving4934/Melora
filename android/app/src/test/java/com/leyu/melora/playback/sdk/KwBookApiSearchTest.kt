package com.leyu.melora.playback.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwBookApiSearchTest {
    @Test fun malformedResponseIsNotPresentedAsAnEmptyAuthor() {
        assertThrows(IllegalStateException::class.java) {
            KwBookApi.searchPageFromResponse(JSONObject().put("total", "0"))
        }
        assertThrows(IllegalStateException::class.java) {
            KwBookApi.searchPageFromResponse(JSONObject().put("albumlist", "not-an-array"))
        }
    }

    @Test fun albumResponseKeepsIdentityMetadataAndPaging() {
        val rows = JSONArray()
        repeat(20) { index -> rows.put(JSONObject().put("albumid", "$index")
            .put("name", "测试作品$index").put("artist", "测试作者&测试主播").put("musiccnt", "120")) }
        val page = KwBookApi.searchPageFromResponse(JSONObject().put("albumlist", rows))
        assertEquals(20, page.items.size)
        assertTrue(page.hasMore)
        assertTrue(page.items.all { it.isBookAlbum })
        assertEquals("book_album_0", page.items.first().id)
        assertEquals("测试作者&测试主播", page.items.first().author)
        assertEquals(120, page.items.first().total)
    }

    @Test
    fun emptyAlbumlistIsAValidEmptyPage() {
        val page = KwBookApi.searchPageFromResponse(
            JSONObject().put("total", "0").put("albumlist", JSONArray()),
        )

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }
}
