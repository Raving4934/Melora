package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.leyu.melora.playback.sdk.PlaylistImportResult
import com.leyu.melora.playback.sdk.PlaylistImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PLAYLIST_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** 前台触发的自动更新与手动更新使用同一读取、预览和保存流程。 */
internal object PlaylistAutoUpdate {
    private const val PREFS = "melora-playlist-auto-update"
    private const val ENABLED = "enabled"
    private const val ATTEMPT_PREFIX = "attempt:"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferences: SharedPreferences
    private var running: Job? = null
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutableEnabled.value = preferences.getBoolean(ENABLED, false)
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        preferences.edit { putBoolean(ENABLED, value) }
        mutableEnabled.value = value
        if (value) onForeground(context) else running?.cancel()
    }

    @Synchronized
    fun onForeground(context: Context) {
        init(context)
        if (!enabled.value || running?.isActive == true) return
        val app = context.applicationContext
        running = scope.launch {
            if (!NetworkState.isConnected(app) || BackupRestoreTransaction.failure.value != null) return@launch
            val playlistIds = UserLibrary.playlists.value.filter { it.importSource != null }.map { it.id }
            pruneAttempts(playlistIds)
            updateImportedPlaylists(
                playlistIds = playlistIds,
                currentPlaylist = { id -> UserLibrary.playlists.value.firstOrNull { it.id == id } },
                claimAttempt = ::claimAttempt,
                read = { PlaylistImporter.load(app, it) {} },
                commit = UserLibrary::commitPlaylistSync,
                onFailure = { Log.d("PlaylistAutoUpdate", "自动更新歌单未完成（${it.javaClass.simpleName}）") },
            )
        }
    }

    @Synchronized
    private fun pruneAttempts(playlistIds: List<String>) {
        val current = playlistIds.mapTo(hashSetOf()) { ATTEMPT_PREFIX + it }
        val stale = preferences.all.keys.filter { it.startsWith(ATTEMPT_PREFIX) && it !in current }
        if (stale.isNotEmpty()) preferences.edit { stale.forEach { remove(it) } }
    }

    @Synchronized
    private fun claimAttempt(id: String): Boolean {
        if (!enabled.value || BackupRestoreTransaction.failure.value != null) return false
        val key = ATTEMPT_PREFIX + id
        val now = System.currentTimeMillis()
        if (!isPlaylistUpdateDue(preferences.getLong(key, 0L), now)) return false
        preferences.edit { putLong(key, now) }
        return true
    }
}

internal fun isPlaylistUpdateDue(lastAttemptAt: Long, now: Long): Boolean =
    lastAttemptAt <= 0 || now < lastAttemptAt || now - lastAttemptAt >= PLAYLIST_UPDATE_INTERVAL_MS

internal suspend fun updateImportedPlaylists(
    playlistIds: List<String>,
    currentPlaylist: (String) -> UserLibrary.UserPlaylist?,
    claimAttempt: (String) -> Boolean,
    read: suspend (String) -> PlaylistImportResult,
    commit: suspend (PlaylistSyncPreview) -> UserLibrary.UserPlaylist,
    onFailure: (Exception) -> Unit,
) {
    for (id in playlistIds) {
        currentCoroutineContext().ensureActive()
        val source = currentPlaylist(id)?.importSource ?: continue
        try {
            if (!claimAttempt(id)) continue
            val result = read(source.value)
            currentCoroutineContext().ensureActive()
            // WebDAV 同步或手动编辑可能在读取时已经发布了更新后的用户库。
            val current = currentPlaylist(id) ?: continue
            if (current.importSource != source) continue
            val preview = previewPlaylistSync(current, result)
            if (preview.hasChanges) commit(preview)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            onFailure(failure)
        }
    }
}
