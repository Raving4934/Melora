package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FavoriteSongsTest {
    @Test
    fun batchRemovalIsIdempotentAndNeverAddsBackMissingSongs() {
        val current = listOf(song(1), song(2), song(3))
        val selected = listOf(song(2), song(2), song(4))
        val updated = updatedFavoriteSongs(current, selected, favorite = false)
        assertEquals(listOf("kw_1", "kw_3"), updated.map { it.uid })
        assertEquals(updated, updatedFavoriteSongs(updated, selected, favorite = false))
    }

    @Test
    fun addPreservesExistingOrderAndMatchesPreviousPrependBehavior() {
        val current = listOf(song(1), song(2))
        val selected = listOf(song(2), song(3), song(3), song(4))
        val updated = updatedFavoriteSongs(current, selected, favorite = true)
        assertEquals(listOf("kw_4", "kw_3", "kw_1", "kw_2"), updated.map { it.uid })
        assertSame(current[1], updated.last())
        assertEquals(updated, updatedFavoriteSongs(updated, selected, favorite = true))
    }

    @Test
    fun crossPlatformIdsRemainIndependent() {
        val kuwo = song(1)
        val qq = OnlineSong(JSONObject(kuwo.raw.toString()).put("source", "qq"))
        assertEquals(listOf(qq), updatedFavoriteSongs(listOf(kuwo, qq), listOf(kuwo), favorite = false))
    }

    @Test
    fun bulkRemovePreservesUnselectedMusicAndBookMetadata() {
        val music = (1..1000).map(::song)
        val chapter = OnlineSong(JSONObject().put("source", "kw").put("songmid", "chapter")
            .put("isBookChapter", true).put("albumId", "old-album").put("legacyField", "keep"))
        val remaining = updatedFavoriteSongs(music + chapter, music.filterIndexed { index, _ -> index % 2 == 0 }, favorite = false)
        assertEquals(501, remaining.size)
        assertEquals((2..1000 step 2).map { "kw_$it" }, remaining.dropLast(1).map { it.uid })
        assertSame(chapter, remaining.last())
        assertEquals("keep", remaining.last().raw.getString("legacyField"))
        assertEquals("old-album", remaining.last().raw.getString("albumId"))
    }

    @Test
    fun emptyBatchDoesNotChangeFavorites() {
        val current = listOf(song(1))
        assertEquals(current, updatedFavoriteSongs(current, emptyList(), favorite = true))
        assertEquals(current, updatedFavoriteSongs(current, emptyList(), favorite = false))
    }

    private fun song(id: Int) = OnlineSong(JSONObject().put("source", "kw").put("songmid", "$id").put("name", "歌$id"))
}
