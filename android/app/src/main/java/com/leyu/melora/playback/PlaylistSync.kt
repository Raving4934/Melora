package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportLink
import org.json.JSONArray
import org.json.JSONObject
import com.leyu.melora.playback.sdk.PlaylistImportResult

internal class PlaylistSyncPreview(
    val expected: UserLibrary.UserPlaylist,
    candidate: UserLibrary.UserPlaylist,
) {
    // JSONObject is mutable: freeze both the conflict token and the exact user-confirmed result.
    val expectedSnapshot: String = expected.toJson().toString()
    private val updatedSnapshot = candidate.toJson().toString()
    val updated: UserLibrary.UserPlaylist = playlistFromJson(JSONObject(updatedSnapshot))
    private val oldSongs = playlistFromJson(JSONObject(expectedSnapshot)).songs
    private val oldUids = oldSongs.mapTo(hashSetOf()) { it.uid }
    private val newUids = updated.songs.mapTo(hashSetOf()) { it.uid }
    val added: List<OnlineSong> = updated.songs.filterNot { it.uid in oldUids }
    val removed: List<OnlineSong> = oldSongs.filterNot { it.uid in newUids }.distinctBy { it.uid }
    val firstBinding: Boolean = expected.importSource == null || expected.lastSyncedUids == null
    val hasChanges: Boolean = expectedSnapshot != updatedSnapshot
    fun snapshotForCommit(): UserLibrary.UserPlaylist = playlistFromJson(JSONObject(updatedSnapshot))
}

internal fun previewPlaylistSync(before: UserLibrary.UserPlaylist, remote: PlaylistImportResult): PlaylistSyncPreview {
    require(remote.warning == null) { "未获取完整歌单，不能更新；本地歌单未改变。${remote.warning.orEmpty()}" }
    require(remote.songs.isNotEmpty()) { "原歌单为空、私密或不可访问，为避免误删，未更新本地歌单。" }
    val source = requireNotNull(remote.importSource) { "缺少可信歌单来源，请重新读取。" }
    require(PlaylistImportLink.parse(source.value) == source) { "歌单来源无效。" }
    require(before.importSource == null || before.importSource == source) { "读取来源与已绑定歌单不符，不能更新。" }
    val songs = (remote.songs + before.songs.filterNot { it.uid in before.lastSyncedUids.orEmpty() }).distinctBy { it.uid }
    return PlaylistSyncPreview(before, before.copy(songs = songs, importSource = remote.importSource,
        lastSyncedUids = remote.songs.mapTo(linkedSetOf()) { it.uid }))
}

internal fun UserLibrary.UserPlaylist.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name)
    .put("songs", JSONArray().apply { songs.forEach { put(it.raw) } })
    .apply {
        importSource?.let { put("importSource", JSONObject().put("source", it.source).put("value", it.value)) }
        lastSyncedUids?.let { put("lastSyncedUids", JSONArray(it.toList())) }
    }

internal fun playlistSyncMetadata(node: JSONObject): Pair<PlaylistImportLink?, Set<String>?> {
    if (!node.has("importSource")) {
        require(!node.has("lastSyncedUids")) { "同步基线缺少歌单来源" }
        return null to null
    }
    val metadata = node.optJSONObject("importSource") ?: throw IllegalArgumentException("歌单来源格式无效")
    val value = metadata.opt("value") as? String ?: throw IllegalArgumentException("歌单来源链接无效")
    val source = PlaylistImportLink.parse(value)
    require(source.source == metadata.opt("source") && source.value == value) { "歌单来源不匹配" }
    val baseline = if (!node.has("lastSyncedUids")) null else {
        val array = node.optJSONArray("lastSyncedUids") ?: throw IllegalArgumentException("同步基线格式无效")
        require(array.length() <= 10_000) { "同步基线条目过多" }
        (0 until array.length()).map { index ->
            val uid = array.opt(index)
            require(uid is String && uid.isNotBlank()) { "同步基线歌曲标识无效" }
            uid
        }.toSet()
    }
    return source to baseline
}

internal fun playlistFromJson(node: JSONObject): UserLibrary.UserPlaylist {
    // 单份本地旧/损坏元数据降级为未绑定，绝不据此推断远端删除。
    val (source, baseline) = runCatching { playlistSyncMetadata(node) }.getOrDefault(null to null)
    val songs = node.optJSONArray("songs")?.let { array ->
        (0 until array.length()).mapNotNull { OnlineSong.from(array.optJSONObject(it)) }
    }.orEmpty()
    return UserLibrary.UserPlaylist(node.optString("id"), node.optString("name"), songs, source, baseline)
}
