package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportLink
import com.leyu.melora.playback.sdk.PlaylistImportResult
import com.leyu.melora.playback.sdk.PlaylistImporter
import org.json.JSONArray
import org.json.JSONObject

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
    val remoteSongs = remote.songs.filterNot { it.uid in before.excludedImportedUids }
    val baseline = before.lastSyncedUids.orEmpty()
    val localSongs = before.songs.filter {
        it.uid !in before.excludedImportedUids && (it.uid !in baseline || it.uid in before.locallyAddedUids)
    }
    val songs = (remoteSongs + localSongs).distinctBy { it.uid }
    val remoteUids = remote.songs.mapTo(linkedSetOf()) { it.uid }
    val localAdditions = localSongs.mapTo(linkedSetOf()) { it.uid }
    // 首次绑定时将重合曲目交给原歌单管理，已有绑定则记住用户添加的曲目。
    if (before.lastSyncedUids == null) localAdditions.removeAll(remoteUids)
    return PlaylistSyncPreview(before, before.copy(songs = songs, importSource = source,
        lastSyncedUids = remoteUids, locallyAddedUids = localAdditions))
}

internal fun UserLibrary.UserPlaylist.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name)
    .put("songs", JSONArray().apply { songs.forEach { put(it.raw) } })
    .apply {
        importSource?.let {
            put("importSource", JSONObject().put("source", it.source).put("value", it.value))
            put("excludedImportedUids", JSONArray(excludedImportedUids.toList()))
            put("locallyAddedUids", JSONArray(locallyAddedUids.toList()))
        }
        lastSyncedUids?.let { put("lastSyncedUids", JSONArray(it.toList())) }
    }

internal data class PlaylistSyncMetadata(
    val source: PlaylistImportLink,
    val baseline: Set<String>?,
    val exclusions: Set<String>?,
    val localAdditions: Set<String>?,
)

internal fun playlistSyncMetadata(node: JSONObject): PlaylistSyncMetadata? {
    if (!node.has("importSource")) {
        require(listOf("lastSyncedUids", "excludedImportedUids", "locallyAddedUids").none(node::has)) {
            "更新记录缺少歌单来源"
        }
        return null
    }
    val metadata = node.optJSONObject("importSource") ?: throw IllegalArgumentException("歌单来源格式无效")
    val value = metadata.opt("value") as? String ?: throw IllegalArgumentException("歌单来源链接无效")
    val source = PlaylistImportLink.parse(value)
    require(source.source == metadata.opt("source") && source.value == value) { "歌单来源不匹配" }
    val baseline = node.uidSet("lastSyncedUids", "更新基线", PlaylistImporter.MAX_SONGS)
    return PlaylistSyncMetadata(source, baseline, node.uidSet("excludedImportedUids", "手动移除记录"),
        node.uidSet("locallyAddedUids", "手动添加记录"))
}

private fun JSONObject.uidSet(key: String, label: String, limit: Int = Int.MAX_VALUE): Set<String>? {
    if (!has(key)) return null
    val array = optJSONArray(key) ?: throw IllegalArgumentException("${label}格式无效")
    require(array.length() <= limit) { "${label}条目过多" }
    return buildSet {
        repeat(array.length()) { index ->
            val uid = array.opt(index)
            require(uid is String && uid.isNotBlank()) { "${label}歌曲标识无效" }
            add(uid)
        }
    }
}

internal fun playlistFromJson(node: JSONObject): UserLibrary.UserPlaylist {
    // 单份本地旧/损坏元数据降级为未绑定，绝不据此推断远端删除。
    val metadata = runCatching { playlistSyncMetadata(node) }.getOrNull()
    val songs = node.optJSONArray("songs")?.let { array ->
        (0 until array.length()).mapNotNull { OnlineSong.from(array.optJSONObject(it)) }
    }.orEmpty()
    if (metadata == null) return UserLibrary.UserPlaylist(node.optString("id"), node.optString("name"), songs)
    // 旧版已绑定歌单按歌曲差集迁移手动增删记录。
    val songUids = songs.mapTo(linkedSetOf()) { it.uid }
    val exclusions = metadata.exclusions ?: metadata.baseline.orEmpty() - songUids
    val localAdditions = metadata.localAdditions ?: songUids - metadata.baseline.orEmpty()
    return UserLibrary.UserPlaylist(node.optString("id"), node.optString("name"), songs,
        metadata.source, metadata.baseline, exclusions, localAdditions)
}
