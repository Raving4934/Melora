package com.leyu.melora.playback

import android.content.Context
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 用户库（收藏 / 最近播放 / 自建歌单）：所有状态变更与 JSON 快照写入共用同一临界区。 */
object UserLibrary {
    data class UserPlaylist(val id: String, val name: String, val songs: List<OnlineSong>)

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
            playlists.value.sumOf { it.songs.size.toLong() + 1 }
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
        favorites.value = parseSongs(root.optJSONArray("favorites"))
        recents.value = parseSongs(root.optJSONArray("recents"))
        favoriteUids.value = favorites.value.mapTo(linkedSetOf()) { it.uid }
        favoritePlaylists.value = root.optJSONArray("favoritePlaylists")?.let { array ->
            (0 until array.length()).mapNotNull { OnlinePlaylist.from(array.optJSONObject(it)) }
        }.orEmpty()
        favoriteAlbums.value = root.optJSONArray("favoriteAlbums")?.let { array ->
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
        favoriteArtists.value = root.optJSONArray("favoriteArtists")?.let { array ->
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
        recentContainers.value = root.optJSONArray("recentContainers")?.let { array ->
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
        searchHistory.value = root.optJSONArray("searchHistory")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        playlists.value = root.optJSONArray("playlists")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                UserPlaylist(node.optString("id"), node.optString("name"), parseSongs(node.optJSONArray("songs")))
            }
        }.orEmpty()
    }

    private fun buildRoot(): JSONObject = JSONObject()
        .put("favorites", songsToJson(favorites.value))
        .put("recents", songsToJson(recents.value))
        .put("searchHistory", JSONArray().apply { searchHistory.value.forEach(::put) })
        .put("favoritePlaylists", JSONArray().apply { favoritePlaylists.value.forEach { put(it.raw) } })
        .put("favoriteAlbums", JSONArray().apply {
            favoriteAlbums.value.forEach { album ->
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
            favoriteArtists.value.forEach { artist ->
                put(
                    JSONObject()
                        .put("name", artist.name)
                        .put("source", artist.source)
                        .put("img", artist.img ?: ""),
                )
            }
        })
        .put("recentContainers", JSONArray().apply {
            recentContainers.value.forEach { container ->
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
            playlists.value.forEach { playlist ->
                put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("songs", songsToJson(playlist.songs)))
            }
        })

    private fun parseSongs(array: JSONArray?): List<OnlineSong> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { OnlineSong.from(array.optJSONObject(it)) }

    private fun songsToJson(songs: List<OnlineSong>) = JSONArray().apply { songs.forEach { put(it.raw) } }

    private inline fun <T> mutate(
        shouldPersist: (T) -> Boolean = { true },
        change: () -> T,
    ): T = synchronized(lock) {
        val result = change()
        if (shouldPersist(result)) writeTextAtomically(file, buildRoot().toString())
        result
    }

    fun isFavorite(uid: String): Boolean = uid in favoriteUids.value

    fun toggleFavorite(song: OnlineSong) {
        synchronized(lock) { setFavorites(listOf(song), favorite = !isFavorite(song.uid)) }
    }

    /** 批量设置而非逐首反转，一次持久化；重复取消不会意外重新收藏。 */
    fun setFavorites(songs: List<OnlineSong>, favorite: Boolean): Int = mutate {
        val before = favorites.value
        val after = updatedFavoriteSongs(before, songs, favorite)
        favorites.value = after
        favoriteUids.value = after.mapTo(linkedSetOf()) { it.uid }
        kotlin.math.abs(after.size - before.size)
    }

    fun markPlayed(song: OnlineSong) = mutate {
        recents.value = (listOf(song) + recents.value.filterNot { it.uid == song.uid }).take(MAX_RECENTS)
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
                val newFavorites = refreshed(favorites.value)
                val newRecents = refreshed(recents.value)
                val newPlaylists = playlists.value.map { it.copy(songs = refreshed(it.songs)) }
                val newFavoriteUids = newFavorites.mapTo(linkedSetOf()) { it.uid }
                if (
                    newFavorites == favorites.value &&
                    newRecents == recents.value &&
                    newPlaylists == playlists.value &&
                    newFavoriteUids == favoriteUids.value
                ) {
                    return@mutate false
                }
                favorites.value = newFavorites
                favoriteUids.value = newFavoriteUids
                recents.value = newRecents
                playlists.value = newPlaylists
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
        recents.value = emptyList()
        recentContainers.value = emptyList()
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
        recentContainers.value = (listOf(entry) + recentContainers.value.filterNot { it.key == entry.key }).take(30)
    }

    /** 容器封面缺失/不满意时补齐（不改变排序）。 */
    fun updateContainerCover(key: String, img: String) = mutate {
        if (img.isBlank()) return@mutate
        recentContainers.value = recentContainers.value.map {
            if (it.key == key && it.img != img) it.copy(img = img) else it
        }
    }

    fun isFavoritePlaylist(id: String): Boolean = favoritePlaylists.value.any { "${it.source}_${it.id}" == id }

    fun isFavoriteAlbum(key: String): Boolean = favoriteAlbums.value.any { it.key == key }

    fun toggleFavoriteAlbum(album: FavoriteAlbum) = mutate {
        favoriteAlbums.value = if (isFavoriteAlbum(album.key)) {
            favoriteAlbums.value.filterNot { it.key == album.key }
        } else {
            listOf(album) + favoriteAlbums.value.filterNot { it.key == album.key }
        }
    }

    fun isFavoriteArtist(key: String): Boolean = favoriteArtists.value.any { it.key == key }

    fun toggleFavoriteArtist(artist: FavoriteArtist) = mutate {
        favoriteArtists.value = if (isFavoriteArtist(artist.key)) {
            favoriteArtists.value.filterNot { it.key == artist.key }
        } else {
            listOf(artist) + favoriteArtists.value.filterNot { it.key == artist.key }
        }
    }

    fun toggleFavoritePlaylist(playlist: OnlinePlaylist) = mutate {
        val key = "${playlist.source}_${playlist.id}"
        favoritePlaylists.value = if (isFavoritePlaylist(key)) {
            favoritePlaylists.value.filterNot { "${it.source}_${it.id}" == key }
        } else {
            listOf(playlist) + favoritePlaylists.value
        }
    }

    fun addSearchKeyword(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) mutate {
            searchHistory.value = (listOf(trimmed) + searchHistory.value.filterNot { it == trimmed }).take(12)
        }
    }

    fun clearSearchHistory() = mutate { searchHistory.value = emptyList() }

    fun createPlaylist(name: String): UserPlaylist = synchronized(lock) {
        UserPlaylist("pl_${System.currentTimeMillis()}", name.ifBlank { "新建歌单" }, emptyList()).also {
            playlists.value += it
            writeTextAtomically(file, buildRoot().toString())
        }
    }

    fun deletePlaylist(id: String) = mutate { playlists.value = playlists.value.filterNot { it.id == id } }

    fun renamePlaylist(id: String, name: String) = mutate {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
    }

    fun addToPlaylist(id: String, song: OnlineSong) = mutate {
        playlists.value = playlists.value.map {
            if (it.id == id && it.songs.none { item -> item.uid == song.uid }) it.copy(songs = it.songs + song) else it
        }
    }

    fun removeFromPlaylist(id: String, uid: String) = mutate {
        playlists.value = playlists.value.map {
            if (it.id == id) it.copy(songs = it.songs.filterNot { item -> item.uid == uid }) else it
        }
    }
}

internal fun writeTextAtomically(target: File, text: String) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    val backup = File(target.parentFile, "${target.name}.bak")
    try {
        temp.writeText(text)
        if (target.isFile) target.copyTo(backup, overwrite = true)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (error: Throwable) {
        if (backup.isFile) runCatching { backup.copyTo(target, overwrite = true) }
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
