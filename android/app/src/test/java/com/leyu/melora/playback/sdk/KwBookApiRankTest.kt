package com.leyu.melora.playback.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KwBookApiRankTest {
    @Test fun transportOrDecodeFailureCannotBeSuccessfulEmptyPage() {
        assertThrows(IllegalStateException::class.java) { KwBookApi.rankPageFromResponse(null) }
    }

    @Test fun businessFailureAndMissingRequiredStructureAreErrors() {
        for (response in listOf(
            JSONObject("""{"code":500,"data":{"list":[]}}"""),
            JSONObject("""{"code":200}"""),
            JSONObject("""{"code":200,"data":{}}"""),
            JSONObject("""{"code":200,"data":{"list":null}}"""),
        )) {
            assertThrows(IllegalStateException::class.java) { KwBookApi.rankPageFromResponse(response) }
        }
    }

    @Test fun successfulEmptyArrayIsALegitimateEmptyRank() {
        val result = KwBookApi.rankPageFromResponse(response(JSONArray()))
        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test fun filteringInvalidRowsDoesNotChangeTheRawPaginationBoundary() {
        val result = KwBookApi.rankPageFromResponse(response(JSONArray().apply {
            repeat(50) { put(JSONObject()) }
        }))
        assertTrue(result.items.isEmpty())
        assertTrue(result.hasMore)
    }

    @Test fun validRowsKeepTheirIdentityAndDeduplication() {
        val row = JSONObject().put("id", "42").put("name", "节目")
        val result = KwBookApi.rankPageFromResponse(response(JSONArray().put(row).put(row)))
        assertEquals(1, result.items.size)
        assertEquals("book_album_42", result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun response(rows: JSONArray) = JSONObject().put("code", 200)
        .put("data", JSONObject().put("list", rows))
}
