package com.leyu.melora.ui.my

import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.PlaylistAlreadyImportedException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.leyu.melora.playback.matchingImportedPlaylists
import com.leyu.melora.playback.sdk.PlaylistImportResult

internal data class PlaylistImportChoices(
    val matches: List<UserLibrary.UserPlaylist>,
    val target: UserLibrary.UserPlaylist?,
)

internal sealed interface PlaylistImportSave {
    data class Created(val playlist: UserLibrary.UserPlaylist) : PlaylistImportSave
    data class Duplicate(val matches: List<UserLibrary.UserPlaylist>) : PlaylistImportSave
}

/** UI decisions are advisory. The saver must still atomically check under the library lock. */
internal class PlaylistImportCoordinator(
    private val currentPlaylists: () -> List<UserLibrary.UserPlaylist>,
    private val savePlaylist: suspend (String, PlaylistImportResult, Boolean) -> UserLibrary.UserPlaylist,
) {
    fun choices(loaded: PlaylistImportResult, selectedId: String?): PlaylistImportChoices {
        val matches = loaded.importSource?.let { matchingImportedPlaylists(currentPlaylists(), it) }.orEmpty()
        val target = if (selectedId != null) matches.firstOrNull { it.id == selectedId } else matches.singleOrNull()
        return PlaylistImportChoices(matches, target)
    }

    suspend fun save(name: String, loaded: PlaylistImportResult, allowCopy: Boolean = false): PlaylistImportSave {
        currentCoroutineContext().ensureActive()
        return try {
            PlaylistImportSave.Created(savePlaylist(name, loaded, allowCopy))
        } catch (duplicate: PlaylistAlreadyImportedException) {
            PlaylistImportSave.Duplicate(duplicate.matches)
        }
    }

    fun updateTarget(loaded: PlaylistImportResult, selectedId: String): UserLibrary.UserPlaylist =
        checkNotNull(choices(loaded, selectedId).target) { "目标歌单已被删除或来源已改变，请重新选择。" }
}
