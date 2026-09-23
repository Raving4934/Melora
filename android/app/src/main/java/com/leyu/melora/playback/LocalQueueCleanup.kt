package com.leyu.melora.playback

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.Player
import com.leyu.melora.playback.local.LocalSong
import org.json.JSONArray

/** 按文件身份清理纯本地条目，绝不按歌名删除在线歌曲或另一份音质文件。 */
internal data class DeletedLocalFiles(val ids: Set<String>, val uris: Set<String>) {
    fun matches(source: String, uid: String, localUri: String?): Boolean = when {
        source == LocalSong.SOURCE -> uid.removePrefix("${LocalSong.SOURCE}_") in ids || localUri in uris
        source.isBlank() -> uid in uris
        else -> false
    }

    fun matches(track: UiTrack): Boolean = matches(track.source, track.uid, track.raw?.optString("localUri"))
}

/** 倒序合并相邻区间；批量删除当前曲之前先去掉后方无效条目，避免短暂跳到另一首待删歌曲。 */
internal fun Player.removeDeletedLocalItems(deleted: DeletedLocalFiles, trackFor: (String) -> UiTrack? = TrackRegistry::get) {
    val restartSingle = repeatMode == Player.REPEAT_MODE_ONE &&
        currentMediaItem?.mediaId?.let(trackFor)?.let(deleted::matches) == true
    var end = mediaItemCount
    while (end > 0) {
        val track = trackFor(getMediaItemAt(end - 1).mediaId)
        if (track == null || !deleted.matches(track)) { end--; continue }
        var start = end - 1
        while (start > 0 && trackFor(getMediaItemAt(start - 1).mediaId)?.let(deleted::matches) == true) start--
        removeMediaItems(start, end)
        end = start
    }
    if (mediaItemCount == 0) pause() else if (restartSingle) {
        // Media3在单曲循环中移除当前项会将幸存项置为ENDED；显式定位解除终止态，不改播放意图/循环模式。
        seekToDefaultPosition(currentMediaItemIndex)
    }
}

/** 服务未连接时也清理落盘窗口；当前项未删除保持原项，删除则选择之后的第一首幸存项。 */
internal fun pruneSavedLocalQueue(prefs: SharedPreferences, deleted: DeletedLocalFiles): Boolean {
    val array = runCatching { JSONArray(prefs.getString("queue", null) ?: return false) }.getOrNull() ?: return false
    val oldIndex = prefs.getInt("index", 0).coerceIn(0, (array.length() - 1).coerceAtLeast(0))
    val kept = (0 until array.length()).filter { index ->
        val item = array.optJSONObject(index) ?: return@filter true
        !deleted.matches(item.optString("source"), item.optString("uid"), item.optJSONObject("raw")?.optString("localUri"))
    }
    if (kept.size == array.length()) return false
    val newIndex = kept.indexOfFirst { it >= oldIndex }.takeIf { it >= 0 } ?: 0
    prefs.edit {
        if (kept.isEmpty()) clear() else {
            putString("queue", JSONArray().apply { kept.forEach { put(array.get(it)) } }.toString())
            putInt("index", newIndex)
        }
    }
    return true
}

/** 未连接服务的播放意图保存为数据，删除可直接剪掉其中的条目，不能留不可检查的闭包。 */
internal data class PendingPlaybackSelection(
    val tracks: List<UiTrack>,
    val index: Int = 0,
    val queueId: String? = null,
    val insertSingle: Boolean = false,
) {
    fun without(deleted: DeletedLocalFiles): PendingPlaybackSelection {
        val kept = tracks.indices.filterNot { deleted.matches(tracks[it]) }
        return copy(tracks = kept.map(tracks::get), index = kept.indexOfFirst { it >= index }.takeIf { it >= 0 } ?: 0)
    }
}
