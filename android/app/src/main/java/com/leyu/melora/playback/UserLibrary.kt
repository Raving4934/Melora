package com.leyu.melora.playback

import android.content.Context
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportLink
import com.leyu.melora.playback.sdk.PlaylistImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 用户库（收藏 / 最近播放 / 自建歌单）：所有状态变更与 JSON 快照写入共用同一临界区。 */
object UserLibrary {
    data class UserPlaylist(
        val id: String,
        val name: String,
        val songs: List<OnlineSong>,
        val importSource: PlaylistImportLink? = null,
        /** null means no known remote baseline, not an empty remote playlist. */
        val lastSyncedUids: Set<String>? = null,
    )

    /** 收藏的音乐专辑（聚合页实体）：无平台专辑 id，按 名称+歌手+平台 作为唯一键。 */
    data class FavoriteAlbum(
        val name: String,
        val artist: String,
        val source: String,
        val img: String?,
    ) {
        val key: String get() = "${source}_${name}_${artist}"
    }

    /** 收藏的歌手（聚合页实体）：按平台+姓名作为唯一键。 */
    data class FavoriteArtist(
        val name: String,
        val source: String,
        val img: String?,
    ) {
        val key: String get() = "${source}_$name"
    }

    /** 最近收听的容器（歌单/专辑/榜单/听书专辑/推荐页），用于「我的 → 最近播放」容器视图。 */
    data class RecentContainer(
        val kind: String,
        val id: String,
        val name: String,
        val img: String?,
        val source: String,
        val queueId: String,
        val updatedAt: Long,
        val artist: String = "",
    ) {
        val key: String get() = "${kind}_$id"
    }

    /** 播放容器时登记的信息（与 RecentContainer 相同，但不含时间戳）。 */
    data class PlayContainer(
        val kind: String,
        val id: String,
        val name: String,
        val img: String?,
        val source: String,
        val queueId: String,
        val artist: String = "",
    )

    private const val MAX_RECENTS = 100
    private lateinit var file: File
    private val lock = BackupStateLock.monitor

    val favorites = MutableStateFlow<List<OnlineSong>>(emptyList())
    val recents = MutableStateFlow<List<OnlineSong>>(emptyList())
    val playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val favoriteUids = MutableStateFlow<Set<String>>(emptySet())
    val favoritePlaylists = MutableStateFlow<List<OnlinePlaylist>>(emptyList())
    val favoriteAlbums = MutableStateFlow<List<FavoriteAlbum>>(emptyList())
    val favoriteArtists = MutableStateFlow<List<FavoriteArtist>>(emptyList())
    val recentContainers = MutableStateFlow<List<RecentContainer>>(emptyList())
    val searchHistory = MutableStateFlow<List<String>>(emptyList())

    /** 事务内的候选值：变换只改这个快照，写盘成功后才向观察者发布。 */
    private class Snapshot {
        var favoriteUids = UserLibrary.favoriteUids.value
            private set
        var favorites: List<OnlineSong> = UserLibrary.favorites.value
            set(value) {
                field = value
                favoriteUids = value.mapTo(linkedSetOf()) { it.uid }
            }
        var recents: List<OnlineSong> = UserLibrary.recents.value
        var playlists: List<UserPlaylist> = UserLibrary.playlists.value
        var favoritePlaylists: List<OnlinePlaylist> = UserLibrary.favoritePlaylists.value
        var favoriteAlbums: List<FavoriteAlbum> = UserLibrary.favoriteAlbums.value
        var favoriteArtists: List<FavoriteArtist> = UserLibrary.favoriteArtists.value
        var recentContainers: List<RecentContainer> = UserLibrary.recentContainers.value
        var searchHistory: List<String> = UserLibrary.searchHistory.value
    }

    private fun publish(snapshot: Snapshot) {
        favorites.value = snapshot.favorites
        recents.value = snapshot.recents
        favoriteUids.value = snapshot.favoriteUids
        favoritePlaylists.value = snapshot.favoritePlaylists
        favoriteAlbums.value = snapshot.favoriteAlbums
        favoriteArtists.value = snapshot.favoriteArtists
        recentContainers.value = snapshot.recentContainers
        searchHistory.value = snapshot.searchHistory
        playlists.value = snapshot.playlists
    }

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "user-library.json")
        synchronized(lock) { applyRoot(readRoot()) }
        // 本地索引可能先于用户库初始化，也可能反过来；两端就绪后由本地索引协调一次刷新。
        refreshLocalSongs()
    }

    fun exportSnapshot(): String = synchronized(lock) { buildRoot().toString() }

    internal fun backupSnapshot(): String = synchronized(lock) {
        val entries = favorites.value.size.toLong() + recents.value.size + favoritePlaylists.value.size +
            favoriteAlbums.value.size + favoriteArtists.value.size + recentContainers.value.size + searchHistory.value.size +
            playlists.value.sumOf { it.songs.size.toLong() + 1 + (it.lastSyncedUids?.size ?: 0) }
        require(entries <= 50_000) { "用户库超过50000条备份限制" }
        boundedBackupJson(buildRoot())
    }

    fun replaceFromBackup(json: String) {
        val root = JSONObject(json)
        synchronized(lock) {
            writeTextAtomically(file, root.toString())
            applyRoot(root)
        }
    }

    internal fun reloadAfterRestore() = synchronized(lock) {
        if (::file.isInitialized) applyRoot(readRoot())
    }

    private fun readRoot(): JSONObject {
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrElse {
            runCatching { file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")) }
            runCatching { JSONObject(File(file.parentFile, "${file.name}.bak").readText()) }.getOrDefault(JSONObject())
        }
    }

    private fun applyRoot(root: JSONObject) {
        val snapshot = Snapshot()
        snapshot.favorites = parseSongs(root.optJSONArray("favorites"))
        snapshot.recents = parseSongs(root.optJSONArray("recents"))
        snapshot.favoritePlaylists = root.optJSONArray("favoritePlaylists")?.let { array ->
            (0 until array.length()).mapNotNull { OnlinePlaylist.from(array.optJSONObject(it)) }
        }.orEmpty()
        snapshot.favoriteAlbums = root.optJSONArray("favoriteAlbums")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val name = node.optString("name")
                if (name.isBlank()) null else FavoriteAlbum(
                    name = name,
                    artist = node.optString("artist"),
                    source = node.optString("source"),
                    img = node.optString("img").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.orEmpty()
        snapshot.favoriteArtists = root.optJSONArray("favoriteArtists")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val name = node.optString("name")
                if (name.isBlank()) null else FavoriteArtist(
                    name = name,
                    source = node.optString("source"),
                    img = node.optString("img").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.orEmpty().distinctBy { it.key }
        snapshot.recentContainers = root.optJSONArray("recentContainers")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val id = node.optString("id")
                val name = node.optString("name")
                if (id.isBlank() || name.isBlank()) null else RecentContainer(
                    kind = node.optString("kind"),
                    id = id,
                    name = name,
                    img = node.optString("img").takeIf { it.isNotBlank() && it != "null" },
                    source = node.optString("source"),
                    queueId = node.optString("queueId"),
                    updatedAt = node.optLong("updatedAt"),
                    artist = node.optString("artist"),
                )
            }
        }.orEmpty()
        snapshot.searchHistory = root.optJSONArray("searchHistory")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        snapshot.playlists = root.optJSONArray("playlists")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                playlistFromJson(node)
            }
        }.orEmpty()
        publish(snapshot)
    }

    private fun buildRoot(snapshot: Snapshot = Snapshot()): JSONObject = JSONObject()
        .put("favorites", songsToJson(snapshot.favorites))
        .put("recents", songsToJson(snapshot.recents))
        .put("searchHistory", JSONArray().apply { snapshot.searchHistory.forEach(::put) })
        .put("favoritePlaylists", JSONArray().apply { snapshot.favoritePlaylists.forEach { put(it.raw) } })
        .put("favoriteAlbums", JSONArray().apply {
            snapshot.favoriteAlbums.forEach { album ->
                put(
                    JSONObject()
                        .put("name", album.name)
                        .put("artist", album.artist)
                        .put("source", album.source)
                        .put("img", album.img ?: ""),
                )
            }
        })
        .put("favoriteArtists", JSONArray().apply {
            snapshot.favoriteArtists.forEach { artist ->
                put(
                    JSONObject()
                        .put("name", artist.name)
                        .put("source", artist.source)
                        .put("img", artist.img ?: ""),
                )
            }
        })
        .put("recentContainers", JSONArray().apply {
            snapshot.recentContainers.forEach { container ->
                put(
                    JSONObject()
                        .put("kind", container.kind)
                        .put("id", container.id)
                        .put("name", container.name)
                        .put("img", container.img ?: "")
                        .put("source", container.source)
                        .put("queueId", container.queueId)
                        .put("updatedAt", container.updatedAt)
                        .put("artist", container.artist),
                )
            }
        })
        .put("playlists", JSONArray().apply {
            snapshot.playlists.forEach { playlist ->
                put(playlist.toJson())
            }
        })

    private fun parseSongs(array: JSONArray?): List<OnlineSong> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { OnlineSong.from(array.optJSONObject(it)) }

    private fun songsToJson(songs: List<OnlineSong>) = JSONArray().apply { songs.forEach { put(it.raw) } }

    private inline fun <T> mutate(
        shouldPersist: (T) -> Boolean = { true },
        change: Snapshot.() -> T,
    ): T = synchronized(lock) {
        val snapshot = Snapshot()
        val result = snapshot.change()
        if (shouldPersist(result)) {
            writeTextAtomically(file, buildRoot(snapshot).toString())
            publish(snapshot)
        }
        result
    }

    fun isFavorite(uid: String): Boolean = uid in favoriteUids.value

    fun toggleFavorite(song: OnlineSong) {
        synchronized(lock) { setFavorites(listOf(song), favorite = !isFavorite(song.uid)) }
    }

    /** 批量设置而非逐首反转，一次持久化；重复取消不会意外重新收藏。 */
    fun setFavorites(songs: List<OnlineSong>, favorite: Boolean): Int = mutate {
        val before = favorites
        val after = updatedFavoriteSongs(before, songs, favorite)
        favorites = after
        kotlin.math.abs(after.size - before.size)
    }

    fun markPlayed(song: OnlineSong) = mutate {
        recents = (listOf(song) + recents.filterNot { it.uid == song.uid }).take(MAX_RECENTS)
    }

    /**
     * 本地索引提交后刷新收藏/最近/自建歌单里的 source=local 快照；
     * 索引快照在 LocalMediaStore 锁内读取，避免调用方把旧 map 写回来。
     */
    internal fun refreshLocalSongs(pruneMissing: Boolean = false) {
        if (!::file.isInitialized) return
        LocalMediaStore.withIndexLock { songs ->
            val currentIndex = songs.associateBy(LocalSong::id)
            if (currentIndex.isEmpty() && !pruneMissing) return@withIndexLock
            mutate(shouldPersist = { it }) {
                fun refreshed(list: List<OnlineSong>) = list.mapNotNull { song ->
                    if (song.source != LocalSong.SOURCE) return@mapNotNull song
                    currentIndex[song.songmid]?.let { song.withLocalSong(it) }
                        ?: song.takeUnless { pruneMissing }
                }
                val newFavorites = refreshed(favorites)
                val newRecents = refreshed(recents)
                val newPlaylists = playlists.map { it.copy(songs = refreshed(it.songs)) }
                val newFavoriteUids = newFavorites.mapTo(linkedSetOf()) { it.uid }
                if (
                    newFavorites == favorites &&
                    newRecents == recents &&
                    newPlaylists == playlists &&
                    newFavoriteUids == favoriteUids
                ) {
                    return@mutate false
                }
                favorites = newFavorites
                recents = newRecents
                playlists = newPlaylists
                true
            }
        }
    }

    private fun OnlineSong.withLocalSong(local: LocalSong): OnlineSong {
        val name = local.title
        val singer = local.artist
        val albumName = local.album
        val interval = LocalSong.formatDuration(local.durationMs / 1000)
        val image = local.coverUri?.takeIf { it.isNotBlank() }
        val imageUnchanged = img == image && (image != null || !raw.has("img"))
        if (this.name == name && singer == this.singer && albumName == this.albumName && interval == this.interval && imageUnchanged) {
            return this
        }
        return OnlineSong(JSONObject(raw.toString()).apply {
            put("name", name)
            put("singer", singer)
            put("albumName", albumName)
            put("interval", interval)
            if (image == null) remove("img") else put("img", image)
        })
    }

    fun clearRecents() = mutate {
        recents = emptyList()
        recentContainers = emptyList()
    }

    /** 记录最近播放的容器（歌单/专辑/榜单/听书专辑/推荐），按容器去重后置顶。 */
    fun markContainerPlayed(container: PlayContainer) = mutate {
        if (container.id.isBlank() || container.name.isBlank()) return@mutate
        val entry = RecentContainer(
            kind = container.kind,
            id = container.id,
            name = container.name,
            img = container.img,
            source = container.source,
            queueId = container.queueId,
            updatedAt = System.currentTimeMillis(),
            artist = container.artist,
        )
        recentContainers = (listOf(entry) + recentContainers.filterNot { it.key == entry.key }).take(30)
    }

    /** 容器封面缺失/不满意时补齐（不改变排序）。 */
    fun updateContainerCover(key: String, img: String) = mutate {
        if (img.isBlank()) return@mutate
        recentContainers = recentContainers.map {
            if (it.key == key && it.img != img) it.copy(img = img) else it
        }
    }

    fun isFavoritePlaylist(id: String): Boolean = favoritePlaylists.value.any { "${it.source}_${it.id}" == id }

    fun isFavoriteAlbum(key: String): Boolean = favoriteAlbums.value.any { it.key == key }

    fun toggleFavoriteAlbum(album: FavoriteAlbum) = mutate {
        favoriteAlbums = if (isFavoriteAlbum(album.key)) {
            favoriteAlbums.filterNot { it.key == album.key }
        } else {
            listOf(album) + favoriteAlbums.filterNot { it.key == album.key }
        }
    }

    fun isFavoriteArtist(key: String): Boolean = favoriteArtists.value.any { it.key == key }

    fun toggleFavoriteArtist(artist: FavoriteArtist) = mutate {
        favoriteArtists = if (isFavoriteArtist(artist.key)) {
            favoriteArtists.filterNot { it.key == artist.key }
        } else {
            listOf(artist) + favoriteArtists.filterNot { it.key == artist.key }
        }
    }

    fun toggleFavoritePlaylist(playlist: OnlinePlaylist) = mutate {
        val key = "${playlist.source}_${playlist.id}"
        favoritePlaylists = if (isFavoritePlaylist(key)) {
            favoritePlaylists.filterNot { "${it.source}_${it.id}" == key }
        } else {
            listOf(playlist) + favoritePlaylists
        }
    }

    fun addSearchKeyword(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) mutate {
            searchHistory = (listOf(trimmed) + searchHistory.filterNot { it == trimmed }).take(12)
        }
    }

    fun clearSearchHistory() = mutate { searchHistory = emptyList() }

    fun createPlaylist(name: String, songs: List<OnlineSong> = emptyList()): UserPlaylist =
        createPlaylistSnapshot(UserPlaylist("", name, songs))

    /** Default import is atomic check-and-create; only an explicit UI copy action may opt out. */
    internal fun createImportedPlaylist(
        name: String,
        result: PlaylistImportResult,
        allowCopy: Boolean = false,
    ): UserPlaylist = synchronized(lock) {
        val source = requireNotNull(result.importSource) { "缺少可信歌单来源，请重新读取" }
        require(PlaylistImportLink.parse(source.value) == source) { "歌单来源无效" }
        require(result.songs.isNotEmpty()) { "未获取到歌曲" }
        val matches = matchingImportedPlaylists(playlists.value, source)
        if (!allowCopy && matches.isNotEmpty()) throw PlaylistAlreadyImportedException(matches)
        createPlaylistSnapshot(UserPlaylist("", name, result.songs, source, result.songs.mapTo(linkedSetOf()) { it.uid }))
    }

    /** Cancel before entering the commit boundary, never misreport a persisted creation as cancelled. */
    internal suspend fun saveImportedPlaylist(
        name: String,
        result: PlaylistImportResult,
        allowCopy: Boolean = false,
    ): UserPlaylist {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    caller.ensureActive()
                    createImportedPlaylist(name, result, allowCopy)
                }
            }
        }
    }

    private fun createPlaylistSnapshot(draft: UserPlaylist): UserPlaylist = mutate {
        val currentPlaylists = playlists
        val existingIds = currentPlaylists.mapTo(hashSetOf()) { it.id }
        var timestamp = System.currentTimeMillis()
        while ("pl_$timestamp" in existingIds) timestamp++

        val playlist = draft.copy(
            id = "pl_$timestamp",
            name = draft.name.ifBlank { "新建歌单" },
            songs = draft.songs.distinctBy { it.uid },
        )
        val candidatePlaylists = currentPlaylists + playlist
        playlists = candidatePlaylists
        playlist
    }

    /** 检查取消/并发修改后，提交阶段不可取消：不会已落盘却向调用者报告取消或失败。 */
    internal suspend fun commitPlaylistSync(preview: PlaylistSyncPreview): UserPlaylist {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                mutate {
                    caller.ensureActive()
                    val current = playlists.firstOrNull { it.id == preview.expected.id }
                    check(current === preview.expected && current.toJson().toString() == preview.expectedSnapshot) {
                        "歌单已被修改或删除，请重新读取后确认。"
                    }
                    val updated = preview.snapshotForCommit()
                    playlists = playlists.map { if (it.id == current.id) updated else it }
                    updated
                }
            }
        }
    }

    fun deletePlaylist(id: String) = mutate { playlists = playlists.filterNot { it.id == id } }

    fun renamePlaylist(id: String, name: String) = mutate {
        playlists = playlists.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
    }

    /** 返回实际新增数量；歌单已删除时返回null，无变化不写盘。 */
    fun addToPlaylist(id: String, songs: List<OnlineSong>): Int? = mutate(
        shouldPersist = { it != null && it > 0 },
    ) {
        val playlist = playlists.firstOrNull { it.id == id } ?: return@mutate null
        val knownUids = playlist.songs.mapTo(hashSetOf()) { it.uid }
        val additions = songs.filter { knownUids.add(it.uid) }
        if (additions.isEmpty()) return@mutate 0

        playlists = playlists.map {
            if (it.id == id) it.copy(songs = it.songs + additions) else it
        }
        additions.size
    }

    fun removeFromPlaylist(id: String, uid: String) = mutate {
        playlists = playlists.map {
            if (it.id == id) it.copy(songs = it.songs.filterNot { item -> item.uid == uid }) else it
        }
    }
}

internal fun writeTextAtomically(target: File, text: String) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    val backup = File(target.parentFile, "${target.name}.bak")
    var backupCreated = false
    try {
        temp.writeText(text)
        if (target.isFile) {
            target.copyTo(backup, overwrite = true)
            backupCreated = true
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (error: Throwable) {
        if (backupCreated && backup.isFile) runCatching { backup.copyTo(target, overwrite = true) }
        throw error
    } finally {
        temp.delete()
    }
}

/** 保留原有逐首收藏的置顶顺序；已有收藏不移动，取消收藏保持剩余顺序。 */
internal fun updatedFavoriteSongs(
    current: List<OnlineSong>,
    selected: List<OnlineSong>,
    favorite: Boolean,
): List<OnlineSong> {
    if (!favorite) {
        val removed = selected.mapTo(hashSetOf()) { it.uid }
        return current.filterNot { it.uid in removed }
    }
    val existing = current.mapTo(hashSetOf()) { it.uid }
    return selected.filter { existing.add(it.uid) }.asReversed() + current
}
