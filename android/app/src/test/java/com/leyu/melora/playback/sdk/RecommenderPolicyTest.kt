package com.leyu.melora.playback.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecommenderPolicyTest {
    @Test
    fun `song diversification excludes chapters known tracks and duplicate ids`() {
        val candidates = listOf(
            song("1", "A"),
            song("2", "A"),
            song("3", "B", isBook = true),
            song("4", "C"),
            song("4", "C"),
            song("5", "D"),
        )

        val result = Recommender.diversifySongs(candidates, excluded = setOf("kw_5"), limit = 6, perArtist = 1)

        assertEquals(listOf("kw_1", "kw_4", "kw_2"), result.map { it.uid })
        assertFalse(result.any { it.isBookChapter })
    }

    @Test
    fun `round robin aggregation preserves platform diversity and removes duplicates`() {
        val result = roundRobinDistinct(
            groups = listOf(listOf("a", "b", "c"), listOf("x", "b", "z"), listOf("m")),
            limit = 6,
            key = { it },
        )

        assertEquals(listOf("a", "x", "m", "b", "c", "z"), result)
    }

    private fun song(id: String, artist: String, isBook: Boolean = false) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", "song-$id")
            .put("singer", artist)
            .put("isBookChapter", isBook),
    )
}
