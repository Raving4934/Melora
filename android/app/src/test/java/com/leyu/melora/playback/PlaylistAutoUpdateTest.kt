package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportLink
import com.leyu.melora.playback.sdk.PlaylistImportResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAutoUpdateTest {
    @Test fun aCheckRemainsDueAfterOneDayOrAfterClockCorrection() {
        val firstAttempt = 1_700_000_000_000L
        val day = 86_400_000L
        assertTrue(isPlaylistUpdateDue(0L, firstAttempt))
        assertFalse(isPlaylistUpdateDue(firstAttempt, firstAttempt))
        assertFalse(isPlaylistUpdateDue(firstAttempt, firstAttempt + day - 1))
        assertTrue(isPlaylistUpdateDue(firstAttempt, firstAttempt + day))
        assertTrue(isPlaylistUpdateDue(firstAttempt, firstAttempt - 1))
    }

    @Test fun automaticChecksSkipUnboundAndRecentlyCheckedPlaylistsAndKeepIncompleteOnes() = runBlocking {
        val notBound = playlist(1).copy(importSource = null, lastSyncedUids = null)
        val alreadyChecked = playlist(2)
        val incomplete = playlist(3)
        val unchanged = playlist(4)
        val changed = playlist(5)
        val playlists = listOf(notBound, alreadyChecked, incomplete, unchanged, changed)
        val attempts = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val commits = mutableListOf<PlaylistSyncPreview>()
        val failures = mutableListOf<Exception>()

        updateImportedPlaylists(
            playlistIds = playlists.map { it.id },
            currentPlaylist = { id -> playlists.firstOrNull { it.id == id } },
            claimAttempt = { id ->
                attempts += id
                id != alreadyChecked.id
            },
            read = { link ->
                reads += link
                when (link) {
                    incomplete.importSource!!.value -> remote(incomplete, song("partial")).copy(warning = "读取不完整")
                    unchanged.importSource!!.value -> remote(unchanged, song("old"))
                    changed.importSource!!.value -> remote(changed, song("new"))
                    else -> error("Unexpected playlist URL")
                }
            },
            commit = { preview ->
                commits += preview
                preview.updated
            },
            onFailure = { failure -> failures += failure },
        )

        assertEquals(listOf(alreadyChecked.id, incomplete.id, unchanged.id, changed.id), attempts)
        assertEquals(listOf(incomplete, unchanged, changed).map { it.importSource!!.value }, reads)
        assertEquals(1, failures.size)
        assertEquals(changed.id, commits.single().expected.id)
        assertEquals(listOf("wy_new"), commits.single().updated.songs.map { it.uid })
    }

    @Test fun cancelledReadCannotCommitOrContinueToNextPlaylist() = runBlocking {
        val first = playlist(1)
        val playlists = listOf(first, playlist(2))
        var readCount = 0
        var commitCount = 0
        val refresh = launch {
            updateImportedPlaylists(
                playlistIds = playlists.map { it.id },
                currentPlaylist = { id -> playlists.firstOrNull { it.id == id } },
                claimAttempt = { true },
                read = {
                    readCount++
                    coroutineContext.cancel()
                    remote(first, song("new"))
                },
                commit = { preview ->
                    commitCount++
                    preview.updated
                },
                onFailure = { error("Cancellation was reported as an update failure") },
            )
        }
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertEquals(1, readCount)
        assertEquals(0, commitCount)
    }

    private fun playlist(id: Int) = UserLibrary.UserPlaylist(
        id.toString(), "歌单 $id", listOf(song("old")),
        PlaylistImportLink.parse("https://music.163.com/#/playlist?id=$id"), setOf("wy_old"),
    )

    private fun remote(playlist: UserLibrary.UserPlaylist, song: OnlineSong) = PlaylistImportResult(
        "网易云音乐", playlist.name, null, listOf(song), 1, 0, null, playlist.importSource,
    )

    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))
}
