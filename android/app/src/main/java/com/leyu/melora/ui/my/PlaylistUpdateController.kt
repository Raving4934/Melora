package com.leyu.melora.ui.my

import com.leyu.melora.playback.PlaylistSyncPreview
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.previewPlaylistSync
import com.leyu.melora.playback.sdk.PlaylistImportProgress
import com.leyu.melora.playback.sdk.PlaylistImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlaylistUpdateState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val progress: PlaylistImportProgress? = null,
    val preview: PlaylistSyncPreview? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = loading || saving
}

/** 只编排元数据读取、预览和显式提交；不读取播放链接。 */
internal class PlaylistUpdateController(
    private val currentPlaylist: () -> UserLibrary.UserPlaylist?,
    private val readPlaylist: suspend (String, (PlaylistImportProgress) -> Unit) -> PlaylistImportResult,
    private val commit: suspend (PlaylistSyncPreview) -> UserLibrary.UserPlaylist = UserLibrary::commitPlaylistSync,
) {
    private val mutableState = MutableStateFlow(PlaylistUpdateState())
    val state = mutableState.asStateFlow()

    suspend fun read() {
        if (state.value.busy) return
        mutableState.value = PlaylistUpdateState(loading = true)
        try {
            currentCoroutineContext().ensureActive()
            val before = checkNotNull(currentPlaylist()) { "歌单已被删除。" }
            val requested = requireNotNull(before.importSource) { "此歌单没有导入来源，不能从原歌单更新。" }
            val result = readPlaylist(requested.value) {
                mutableState.value = mutableState.value.copy(progress = it)
            }
            currentCoroutineContext().ensureActive()
            require(result.importSource == requested) { "读取来源与请求不符，请重新读取。" }
            mutableState.value = PlaylistUpdateState(preview = previewPlaylistSync(before, result))
        } catch (cancelled: CancellationException) {
            mutableState.value = PlaylistUpdateState()
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutableState.value = PlaylistUpdateState(error = "本地歌单未改变。${failure.message.orEmpty().take(250)}")
        } finally {
            mutableState.value = state.value.copy(loading = false)
        }
    }

    suspend fun confirm(): UserLibrary.UserPlaylist? {
        if (state.value.busy) return null
        val preview = state.value.preview?.takeIf { it.hasChanges } ?: return null
        mutableState.value = state.value.copy(saving = true, error = null)
        return try {
            currentCoroutineContext().ensureActive()
            val saved = commit(preview)
            mutableState.value = PlaylistUpdateState()
            saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(
                preview = preview.takeIf { failure is java.io.IOException },
                error = "未更新本地歌单。${failure.message.orEmpty().take(250)}",
            )
            null
        } finally {
            mutableState.value = state.value.copy(saving = false)
        }
    }
}
