package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.PlaylistImportLink
import com.leyu.melora.playback.sdk.identity

internal class PlaylistAlreadyImportedException(val matches: List<UserLibrary.UserPlaylist>) :
    IllegalStateException("该来源已导入，请选择已有歌单更新，或明确另存为副本。")

internal fun matchingImportedPlaylists(
    playlists: List<UserLibrary.UserPlaylist>,
    source: PlaylistImportLink,
): List<UserLibrary.UserPlaylist> {
    val identity = source.identity()
    return playlists.filter { it.importSource?.identity() == identity }
}
