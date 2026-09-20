package com.leyu.melora.ui.common

import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SongSelectionTest {
    @Test
    fun enterAndExitClearSelection() {
        val selection = SongSelectionState()
        assertFalse(selection.active)
        selection.start()
        selection.toggle("kw_1")
        assertTrue(selection.active)
        assertEquals(setOf("kw_1"), selection.selectedUids)
        selection.finish()
        assertFalse(selection.active)
        assertTrue(selection.selectedUids.isEmpty())
        selection.start()
        assertTrue(selection.selectedUids.isEmpty())
    }

    @Test
    fun toggleTwiceDoesNotKeepSong() {
        val selection = SongSelectionState()
        selection.start()
        selection.toggle("kw_1")
        selection.toggle("kw_1")
        assertTrue(selection.selectedUids.isEmpty())
    }

    @Test
    fun selectAllDeduplicatesAndSecondClickClears() {
        val songs = listOf(song(1), song(1), song(2))
        val selection = SongSelectionState()
        selection.start()
        selection.toggleAll(songs)
        assertTrue(selection.allSelected(songs))
        assertEquals(listOf("kw_1", "kw_2"), selection.selectedSongs(songs).map { it.uid })
        selection.toggleAll(songs)
        assertFalse(selection.allSelected(songs))
        assertTrue(selection.selectedUids.isEmpty())
    }

    @Test
    fun changedListDropsMissingSelectionsAndPreservesVisibleOrder() {
        val selection = SongSelectionState()
        selection.start()
        selection.toggleAll(listOf(song(1), song(2), song(3)))
        val current = listOf(song(3), song(1), song(4))
        assertEquals(listOf("kw_3", "kw_1"), selection.selectedSongs(current).map { it.uid })
        selection.toggleAll(current)
        assertEquals(listOf("kw_3", "kw_1", "kw_4"), selection.selectedSongs(current).map { it.uid })
        assertFalse("kw_2" in selection.selectedUids)
    }

    @Test
    fun emptyListCannotProduceActions() {
        val selection = SongSelectionState()
        selection.start()
        selection.toggle("kw_1")
        assertTrue(selection.selectedSongs(emptyList()).isEmpty())
        assertFalse(selection.allSelected(emptyList()))
        selection.toggleAll(emptyList())
        assertTrue(selection.selectedUids.isEmpty())
    }

    private fun song(id: Int) = OnlineSong(JSONObject().put("source", "kw").put("songmid", "$id").put("name", "歌$id"))
}
