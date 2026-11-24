package com.leyu.melora.playback

import android.annotation.SuppressLint
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object RecordSyncMerge {
    private const val VERSION = 1
    private const val MAX_BOOKS = 10_000
    private const val LOCAL_PLAYLIST_PREFIX = "local_pl_"
    private const val HISTORY_CLEARED_AT = "historyClearedAt"
    private const val IMPORT_SOURCE = "importSource"
    private const val IMPORT_BASELINE = "lastSyncedUids"
    private const val IMPORT_EXCLUSIONS = "excludedImportedUids"
    private const val LOCAL_ADDITIONS = "locallyAddedUids"
    private const val PLAYLIST_SONGS = "songs"
    private val fields = listOf(
        "favorites", "recents", "favoritePlaylists", "favoriteAlbums",
        "favoriteArtists", "recentContainers", "playlists",
    )

    /** 从现有用户库格式提取可跨设备使用的记录。 */
    fun project(library: JSONObject, books: JSONArray): JSONObject {
        val shared = JSONObject()
        for (field in fields) {
            val entries = if (field == "playlists") {
                library.entries(field).mapNotNull(::projectPlaylist)
            } else {
                library.entries(field).filterNot(::isLocal).map { portable(it, field) }
            }
            shared.put(field, JSONArray(entries.distinctBy { key(field, it) }))
        }
        copyClearTime(library, shared)
        val sharedBooks = (0 until books.length()).mapNotNull { index ->
            val record = books.getJSONObject(index)
            val song = record.getJSONObject("song")
            if (isLocal(song)) return@mapNotNull null
            JSONObject()
                .put("song", portable(song, "songs"))
                .put("positionMs", record.get("positionMs"))
                .put("durationMs", record.get("durationMs"))
                .put("completed", record.get("completed"))
                .put("updatedAt", record.get("updatedAt"))
        }.distinctBy(::bookKey)
        return document(shared, JSONArray(sharedBooks)).also(::validate)
    }

    fun parse(text: String): JSONObject = validateRemote(backupObject(text))

    fun validateRemote(root: JSONObject): JSONObject {
        validate(root)
        require(contentEquals(root, project(root.getJSONObject("library"), root.getJSONArray("books")))) {
            "同步文件包含设备专属数据、重复条目或未知字段"
        }
        return root
    }

    /**
     * 比较本机及云端各自相对上次同步的修改。删除按条目传播，同一歌单的改名、
     * 曲目增删以及导入设置分别合并。首次同步没有共同基线，保留两端独有的条目。
     */
    fun merge(base: JSONObject?, local: JSONObject, remote: JSONObject?): JSONObject {
        if (remote == null) return local
        val before = base?.getJSONObject("library") ?: JSONObject()
        val ours = local.getJSONObject("library")
        val theirs = remote.getJSONObject("library")
        val clearedAt = maxOf(ours.optLong(HISTORY_CLEARED_AT), theirs.optLong(HISTORY_CLEARED_AT))
        val merged = JSONObject()
        if (clearedAt > 0L) merged.put(HISTORY_CLEARED_AT, clearedAt)
        for (field in fields) {
            val timeKey = when (field) {
                "recents" -> "playedAt"
                "recentContainers" -> "updatedAt"
                else -> null
            }
            fun survivingEntries(library: JSONObject): List<JSONObject> =
                library.entries(field).filter { row ->
                    timeKey == null || clearedAt == 0L || row.optLong(timeKey) > clearedAt
                }
            val rows = mergeRows(
                survivingEntries(before), survivingEntries(ours), survivingEntries(theirs),
                identify = { key(field, it) },
                keepAfterRemoval = { original, remaining ->
                    timeKey != null && remaining.optLong(timeKey) > original.optLong(timeKey)
                },
            ) { original, left, right ->
                when {
                    field == "playlists" -> mergePlaylist(original, left, right)
                    timeKey != null -> newer(left, right, timeKey)
                    else -> right
                }
            }
            val ordered = when (field) {
                "recents" -> rows.sortedByDescending { it.optLong("playedAt") }.take(UserLibrary.MAX_RECENTS)
                "recentContainers" -> rows.sortedByDescending { it.optLong("updatedAt") }.take(UserLibrary.MAX_RECENT_CONTAINERS)
                else -> rows
            }
            merged.put(field, JSONArray(ordered))
        }
        val books = mergeRows(base?.entries("books").orEmpty(), local.entries("books"), remote.entries("books"), ::bookKey) {
            _, left, right -> newer(left, right, "updatedAt")
        }
        return document(merged, JSONArray(books)).also(::validate)
    }

    fun applyLibrary(current: JSONObject, merged: JSONObject): JSONObject {
        val shared = merged.getJSONObject("library")
        return JSONObject(current.toString()).apply {
            for (field in fields) {
                val localEntries = current.entries(field)
                val sharedEntries = shared.entries(field)
                val rows = if (field == "playlists") {
                    applyPlaylists(localEntries, sharedEntries)
                } else {
                    restoreDeviceEntries(field, localEntries, sharedEntries)
                }
                put(field, JSONArray(rows))
            }
            copyClearTime(shared, this)
        }
    }

    /** JSON 对象的键顺序不构成记录变更。数组顺序会影响歌单曲目及列表显示。 */
    fun contentEquals(first: Any?, second: Any?): Boolean = when {
        first === second -> true
        first is JSONObject && second is JSONObject -> first.length() == second.length() &&
            first.keys().asSequence().all { key -> second.has(key) && contentEquals(first.opt(key), second.opt(key)) }
        first is JSONArray && second is JSONArray -> first.length() == second.length() &&
            (0 until first.length()).all { contentEquals(first.get(it), second.get(it)) }
        first is Number && second is Number -> first.toString() == second.toString()
        else -> first == second
    }

    private fun document(library: JSONObject, books: JSONArray): JSONObject =
        JSONObject().put("version", VERSION).put("library", library).put("books", books)

    private fun validate(document: JSONObject) {
        require(document.opt("version") == VERSION) { "不支持的同步文件版本" }
        val library = document.optJSONObject("library") ?: error("同步文件缺少用户库")
        require(fields.all { library.optJSONArray(it) != null }) { "同步文件的用户库不完整" }
        validateBackupLibrary(library)
        require(library.entries("recentContainers").all { row ->
            !row.has("updatedAt") || row.longValue("updatedAt") in 0L..MAX_LIBRARY_TIMESTAMP
        }) { "播放记录时间无效" }
        val books = document.optJSONArray("books") ?: error("同步文件缺少听书进度")
        require(books.length() <= MAX_BOOKS) { "听书进度超过同步条目限制" }
        for (index in 0 until books.length()) {
            val row = books.getJSONObject(index)
            bookIdentity(row)
            require(row.longValue("positionMs") >= 0 && row.longValue("durationMs") >= 0 &&
                row.longValue("updatedAt") in 0..MAX_LIBRARY_TIMESTAMP && row.opt("completed") is Boolean) {
                "听书进度格式无效"
            }
        }
    }

    private fun projectPlaylist(row: JSONObject): JSONObject? {
        val metadata = playlistSyncMetadata(row)
        val songs = row.entries(PLAYLIST_SONGS).filterNot(::isLocal)
            .map { portable(it, PLAYLIST_SONGS) }
            .distinctBy { key(PLAYLIST_SONGS, it) }
        if (row.getString("id").startsWith(LOCAL_PLAYLIST_PREFIX) && songs.isEmpty() && metadata == null) return null
        return JSONObject()
            .put("id", row.getString("id"))
            .put("name", row.getString("name"))
            .put(PLAYLIST_SONGS, JSONArray(songs))
            .apply {
                metadata?.source?.let { source ->
                    put(IMPORT_SOURCE, JSONObject().put("source", source.source).put("value", source.value))
                }
                metadata?.baseline?.let { put(IMPORT_BASELINE, JSONArray(it.filterNot(::isLocalUid))) }
                metadata?.exclusions?.let { put(IMPORT_EXCLUSIONS, JSONArray(it.filterNot(::isLocalUid))) }
                metadata?.localAdditions?.let { put(LOCAL_ADDITIONS, JSONArray(it.filterNot(::isLocalUid))) }
            }
    }

    private fun mergePlaylist(original: JSONObject?, left: JSONObject, right: JSONObject): JSONObject {
        val source = selected(original?.opt(IMPORT_SOURCE), left.opt(IMPORT_SOURCE), right.opt(IMPORT_SOURCE))
        val sameSource = source != null &&
            contentEquals(left.opt(IMPORT_SOURCE), source) && contentEquals(right.opt(IMPORT_SOURCE), source)
        val matchingBase = original?.takeIf { contentEquals(it.opt(IMPORT_SOURCE), source) }
        val selectedBinding = if (contentEquals(right.opt(IMPORT_SOURCE), source)) right else left
        fun mergeBindingField(field: String): JSONArray? = if (sameSource) mergeIds(matchingBase, left, right, field)
            else selectedBinding.optJSONArray(field)
        val baseline = mergeBindingField(IMPORT_BASELINE)
        val exclusions = mergeBindingField(IMPORT_EXCLUSIONS)
        val additions = mergeBindingField(LOCAL_ADDITIONS)
        val excluded = exclusions?.stringSet().orEmpty()
        val added = additions?.stringSet().orEmpty()
        val songs = mergeRows(
            original?.entries(PLAYLIST_SONGS).orEmpty(), left.entries(PLAYLIST_SONGS), right.entries(PLAYLIST_SONGS),
            identify = { key(PLAYLIST_SONGS, it) },
            keepAfterRemoval = { _, remaining -> key(PLAYLIST_SONGS, remaining) in added },
        ).filterNot { key(PLAYLIST_SONGS, it) in excluded }
        return JSONObject()
            .put("id", right.getString("id"))
            .put("name", selected(original?.opt("name"), left.getString("name"), right.getString("name")))
            .put(PLAYLIST_SONGS, JSONArray(songs))
            .apply {
                source?.let { put(IMPORT_SOURCE, it) }
                baseline?.let { put(IMPORT_BASELINE, it) }
                exclusions?.let { put(IMPORT_EXCLUSIONS, it) }
                additions?.let {
                    val present = songs.mapTo(hashSetOf()) { song -> key(PLAYLIST_SONGS, song) }
                    put(LOCAL_ADDITIONS, JSONArray(added.filter { uid -> uid in present }))
                }
            }
    }

    private fun mergeIds(original: JSONObject?, left: JSONObject, right: JSONObject, field: String): JSONArray? {
        val leftIds = left.optJSONArray(field)
        val rightIds = right.optJSONArray(field)
        if (leftIds == null && rightIds == null) return null
        val before = original?.optJSONArray(field)?.stringSet().orEmpty()
        val ours = leftIds?.stringSet().orEmpty()
        val theirs = rightIds?.stringSet().orEmpty()
        return JSONArray((ours + theirs).filter { id ->
            if (id in before) id in ours && id in theirs else true
        })
    }

    private fun applyPlaylists(current: List<JSONObject>, shared: List<JSONObject>): List<JSONObject> {
        val byId = current.associateBy { it.getString("id") }
        val sharedIds = shared.mapTo(hashSetOf()) { it.getString("id") }
        val reservedIds = (byId.keys + sharedIds).toMutableSet()
        val result = shared.map { playlist ->
            val existing = byId[playlist.getString("id")] ?: return@map playlist
            val songs = restoreDeviceEntries(PLAYLIST_SONGS, existing.entries(PLAYLIST_SONGS), playlist.entries(PLAYLIST_SONGS))
            JSONObject(playlist.toString()).put(PLAYLIST_SONGS, JSONArray(songs)).apply {
                if (has(IMPORT_SOURCE)) {
                    val localUids = songs.filter(::isLocal).map { key(PLAYLIST_SONGS, it) }
                    if (localUids.isNotEmpty()) {
                        put(LOCAL_ADDITIONS, JSONArray(optJSONArray(LOCAL_ADDITIONS)?.stringSet().orEmpty() + localUids))
                    }
                }
            }
        }
        return result + current.mapNotNull { playlist ->
            val id = playlist.getString("id")
            if (id in sharedIds) return@mapNotNull null
            // 保留未共享的歌单。远端已删除的混合歌单只保留本机曲目，换 ID 以免下次同步复活旧歌单。
            if (projectPlaylist(playlist) == null) return@mapNotNull playlist
            val songs = playlist.entries(PLAYLIST_SONGS).filter(::isLocal)
            if (songs.isEmpty()) return@mapNotNull null
            val preservedId = generateSequence(0) { it + 1 }
                .map { attempt -> LOCAL_PLAYLIST_PREFIX + UUID.nameUUIDFromBytes("$id:$attempt".toByteArray(Charsets.UTF_8)) }
                .first(reservedIds::add)
            JSONObject().put("id", preservedId).put("name", playlist.getString("name")).put(PLAYLIST_SONGS, JSONArray(songs))
        }
    }

    private fun restoreDeviceEntries(field: String, current: List<JSONObject>, shared: List<JSONObject>): List<JSONObject> {
        val currentById = current.filterNot(::isLocal).associateBy { key(field, it) }
        val online = shared.map { row ->
            currentById[key(field, row)]?.takeIf { contentEquals(portable(it, field), row) } ?: row
        }.iterator()
        val result = buildList {
            for (row in current) {
                if (isLocal(row)) add(row) else if (online.hasNext()) add(online.next())
            }
            online.forEachRemaining { add(it) }
        }
        return when (field) {
            "recents" -> result.sortedByDescending { it.optLong("playedAt") }
            "recentContainers" -> result.sortedByDescending { it.optLong("updatedAt") }
            else -> result
        }
    }

    private fun mergeRows(
        original: List<JSONObject>,
        left: List<JSONObject>,
        right: List<JSONObject>,
        identify: (JSONObject) -> String,
        keepAfterRemoval: (JSONObject, JSONObject) -> Boolean = { _, _ -> false },
        resolve: (JSONObject?, JSONObject, JSONObject) -> JSONObject = { _, _, row -> row },
    ): List<JSONObject> {
        val before = original.associateBy(identify)
        val ours = left.associateBy(identify)
        val theirs = right.associateBy(identify)
        val keys = if (rowsEqual(original, right)) ours.keys + theirs.keys else theirs.keys + ours.keys
        return keys.mapNotNull { key ->
            val initial = before[key]
            val local = ours[key]
            val remote = theirs[key]
            when {
                local == null || remote == null -> (local ?: remote)?.takeIf {
                    initial == null || keepAfterRemoval(initial, it)
                }
                contentEquals(initial, local) -> remote
                contentEquals(initial, remote) || contentEquals(local, remote) -> local
                else -> resolve(initial, local, remote)
            }
        }
    }

    private fun rowsEqual(first: List<JSONObject>, second: List<JSONObject>): Boolean =
        first.size == second.size && first.indices.all { contentEquals(first[it], second[it]) }

    private fun selected(original: Any?, left: Any?, right: Any?): Any? =
        if (contentEquals(original, right)) left else right

    private fun newer(left: JSONObject, right: JSONObject, timeKey: String): JSONObject =
        if (left.optLong(timeKey) > right.optLong(timeKey)) left else right

    private fun key(field: String, row: JSONObject): String {
        val source = row.optString("source")
        val name = row.optString("name")
        val id = row.optString("id")
        return when (field) {
            "favorites", "recents", PLAYLIST_SONGS -> "${source}_${row.optString("songmid")}"
            "favoritePlaylists" -> "${source}_$id"
            "favoriteAlbums" -> "${source}_${name}_${row.optString("artist")}"
            "favoriteArtists" -> "${source}_$name"
            "recentContainers" -> row.optString("kind").let { "${it.length}:$it:${source.length}:$source:$id" }
            "playlists" -> id
            else -> error("无法识别的同步记录")
        }
    }

    private fun bookKey(row: JSONObject): String {
        val (source, id) = bookIdentity(row)
        return "${source.length}:$source:$id"
    }

    private fun bookIdentity(row: JSONObject): Pair<String, String> {
        val song = OnlineSong.from(row.optJSONObject("song")) ?: error("听书进度缺少章节")
        return BookListeningProgress.bookIdentity(song) ?: error("听书进度缺少专辑")
    }

    private fun JSONObject.longValue(key: String): Long {
        val value = opt(key)
        require(value is Int || value is Long) { "同步记录的 $key 格式无效" }
        return (value as Number).toLong()
    }

    private fun JSONObject.entries(field: String): List<JSONObject> =
        optJSONArray(field)?.let { array -> List(array.length(), array::getJSONObject) }.orEmpty()

    private fun JSONArray.stringSet(): Set<String> = (0 until length()).mapTo(linkedSetOf(), ::getString)

    private fun copyClearTime(from: JSONObject, to: JSONObject) {
        if (from.has(HISTORY_CLEARED_AT)) to.put(HISTORY_CLEARED_AT, from.get(HISTORY_CLEARED_AT))
        else to.remove(HISTORY_CLEARED_AT)
    }

    private fun isLocal(row: JSONObject): Boolean = row.optString("source") == LocalSong.SOURCE
    private fun isLocalUid(uid: String): Boolean = uid.startsWith("${LocalSong.SOURCE}_")

    private val localFields = setOf("uri", "localuri", "localpath", "filepath", "relativepath")
    @SuppressLint("SdCardPath") // 识别导入数据里的设备路径以避免上传，不在这些路径读写文件。
    private val localUriPrefixes = listOf("content:", "file:", "android.resource:", "/storage/", "/sdcard/", "/mnt/", "/data/")

    private fun portable(row: JSONObject, field: String): JSONObject = JSONObject().apply {
        val visited = row.keys()
        while (visited.hasNext()) {
            val name = visited.next()
            if (name == "playedAt" && field != "recents") continue
            if (name.lowercase() !in localFields) portableValue(row.get(name), depth = 0)?.let { put(name, it) }
        }
    }

    private fun portableValue(value: Any, depth: Int): Any? {
        require(depth <= 64) { "同步记录嵌套过深" }
        return when (value) {
            is String -> value.takeUnless { text ->
                val trimmed = text.trimStart()
                localUriPrefixes.any { trimmed.startsWith(it, ignoreCase = true) }
            }
            is JSONObject -> JSONObject().apply {
                for (name in value.keys()) {
                    if (name.lowercase() !in localFields) portableValue(value.get(name), depth + 1)?.let { put(name, it) }
                }
            }
            is JSONArray -> JSONArray().apply {
                for (index in 0 until value.length()) portableValue(value.get(index), depth + 1)?.let { put(it) }
            }
            else -> value
        }
    }
}
