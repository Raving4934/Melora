package com.leyu.melora.ui.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.local.LocalSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongListStateTest {
    @Test
    fun sharedStatesUpdateWithoutReplacingTheHolder() {
        val songs = mutableStateOf<List<LocalSong>>(emptyList())
        val covers = mutableStateOf(true)
        val current = mutableStateOf<UiTrack?>(track("local_one"))
        val state = SongListState(songs, covers, current)
        assertEquals("local_one", state.currentTrack.value?.uid)

        covers.value = false
        current.value = track("kw_two")
        assertFalse(state.showCovers.value)
        assertEquals("kw_two", state.currentTrack.value?.uid)

        current.value = null
        assertEquals(null, state.currentTrack.value?.uid)
    }

    @Test
    fun onlyConsumersThatReadAChangedStateAreInvalidated() {
        val songs = mutableStateOf<List<LocalSong>>(emptyList())
        val covers = mutableStateOf(true)
        val current = mutableStateOf<UiTrack?>(track("local_one"))
        val state = SongListState(songs, covers, current)
        val invalidated = mutableListOf<String>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            val onChanged: (String) -> Unit = { invalidated += it }
            observer.observeReads("provider", onChanged) { state }
            observer.observeReads("cover", onChanged) { state.showCovers.value }
            observer.observeReads("current", onChanged) { state.currentTrack.value?.uid }
            Snapshot.sendApplyNotifications()
            invalidated.clear()

            current.value = track("kw_two")
            Snapshot.sendApplyNotifications()
            assertEquals(listOf("current"), invalidated)
            invalidated.clear()

            observer.observeReads("current", onChanged) { state.currentTrack.value }
            current.value = track("kw_two", artwork = "https://cover/two.jpg")
            Snapshot.sendApplyNotifications()
            assertEquals(listOf("current"), invalidated)
            invalidated.clear()

            covers.value = false
            Snapshot.sendApplyNotifications()
            assertEquals(listOf("cover"), invalidated)
            assertFalse("provider" in invalidated)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun localIndexChangesNotifyEveryConsumerButNotTheProvider() {
        val songs = mutableStateOf<List<LocalSong>>(emptyList())
        val state = SongListState(songs, mutableStateOf(true), mutableStateOf<UiTrack?>(null))
        val invalidated = mutableSetOf<String>()
        val observer = SnapshotStateObserver { it() }
        val song = LocalSong(
            id = "one", uri = "file:///music/one.mp3", title = "曲目", artist = "歌手", album = "专辑",
            durationMs = 180_000, sizeBytes = 1_000, mimeType = "audio/mpeg", sampleRate = 44_100,
            bitrate = 320_000, modifiedAt = 1, addedAt = 1, folder = "music",
        )
        observer.start()
        try {
            val onChanged: (String) -> Unit = { invalidated += it }
            observer.observeReads("provider", onChanged) { state }
            observer.observeReads("rowA", onChanged) { state.localSongs.value }
            observer.observeReads("rowB", onChanged) { state.localSongs.value }
            Snapshot.sendApplyNotifications()
            invalidated.clear()

            songs.value = listOf(song)
            Snapshot.sendApplyNotifications()
            assertEquals(setOf("rowA", "rowB"), invalidated)
            assertEquals("one", state.localSongs.value.single().id)
            invalidated.clear()

            // 重组后再次订阅，删除与扫描新增必须遵循同一份索引状态。
            observer.observeReads("rowA", onChanged) { state.localSongs.value }
            observer.observeReads("rowB", onChanged) { state.localSongs.value }
            songs.value = emptyList()
            Snapshot.sendApplyNotifications()
            assertEquals(setOf("rowA", "rowB"), invalidated)
            assertTrue(state.localSongs.value.isEmpty())
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun unchangedTrackAndCoverSettingsDoNotInvalidateConsumers() {
        val songs = mutableStateOf<List<LocalSong>>(emptyList())
        val covers = mutableStateOf(true)
        val current = mutableStateOf<UiTrack?>(track("kw_one"))
        val state = SongListState(songs, covers, current)
        val invalidated = mutableListOf<String>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("row", { key: String -> invalidated += key }) {
                state.showCovers.value
                state.currentTrack.value?.uid
                state.localSongs.value
            }
            Snapshot.sendApplyNotifications()
            invalidated.clear()
            current.value = track("kw_one")
            covers.value = true
            songs.value = emptyList()
            Snapshot.sendApplyNotifications()
            assertTrue(invalidated.isEmpty())
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    private fun track(uid: String, artwork: String? = null) = UiTrack(
        uid = uid,
        title = "曲目",
        artist = "歌手",
        album = "专辑",
        artwork = artwork,
    )
}
