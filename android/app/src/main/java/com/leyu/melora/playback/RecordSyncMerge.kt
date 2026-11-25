package com.leyu.melora.playback

import android.annotation.SuppressLint
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportLink
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class RecordConflict(
    val key: String,
    val title: String,
    val localLabel: String,
    val remoteLabel: String,
)

internal data class RecordMergeResult(val records: JSONObject, val conflicts: List<RecordConflict>)

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
    val libraryFields = listOf(
        "favorites", "recents", "favoritePlaylists", "favoriteAlbums",
        "favoriteArtists", "recentContainers", "playlists",
    )

    /** 从现有用户库格式提取可跨设备使用的记录。 */
    fun project(library: JSONObject, books: JSONArray): JSONObject {
        val shared = JSONObject()
        for (field in libraryFields) {
            val entries = if (field == "playlists") {
                library.entries(field).mapNotNull(::projectPlaylist)
            } else {
                library.entries(field).filterNot(::isLocal).map { portable(it, field) }
            }
            shared.put(field, JSONArray(entries.distinctBy { rowKey(field, it) }))
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
        }.distinctBy { rowKey("books", it) }
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

    /** 无法同时应用的修改单独列出，其他条目和字段仍合并。 */
    fun review(
        base: JSONObject?,
        local: JSONObject,
        remote: JSONObject?,
        choose: (RecordConflict) -> Boolean? = { null },
    ): RecordMergeResult {
        if (remote == null) return RecordMergeResult(local, emptyList())
        val choices = MergeChoices(choose)
        val before = base?.getJSONObject("library") ?: JSONObject()
        val ours = local.getJSONObject("library")
        val theirs = remote.getJSONObject("library")
        val clearedAt = maxOf(ours.optLong(HISTORY_CLEARED_AT), theirs.optLong(HISTORY_CLEARED_AT))
        val merged = JSONObject()
        if (clearedAt > 0L) merged.put(HISTORY_CLEARED_AT, clearedAt)
        for (field in libraryFields) {
            val timeKey = historyTimeKey(field)
            fun survivingEntries(library: JSONObject): List<JSONObject> =
                library.entries(field).filter { row ->
                    timeKey == null || clearedAt == 0L || row.optLong(timeKey) > clearedAt
                }
            val rows = mergeRows(
                survivingEntries(before), survivingEntries(ours), survivingEntries(theirs),
                identify = { rowKey(field, it) },
            ) { key, original, left, right ->
                mergeRow(field, key, original, left, right, base != null, choices)
            }
            val ordered = when (field) {
                "recents" -> rows.sortedByDescending { it.optLong("playedAt") }.take(UserLibrary.MAX_RECENTS)
                "recentContainers" -> rows.sortedByDescending { it.optLong("updatedAt") }.take(UserLibrary.MAX_RECENT_CONTAINERS)
                else -> rows
            }
            merged.put(field, JSONArray(ordered))
        }
        val books = mergeRows(
            base?.entries("books").orEmpty(), local.entries("books"), remote.entries("books"),
            identify = { rowKey("books", it) },
        ) { key, original, left, right ->
            mergeRow("books", key, original, left, right, base != null, choices)
        }
        return RecordMergeResult(document(merged, JSONArray(books)).also(::validate), choices.conflicts)
    }

    /** 并发分支的 null 表示明确删除。只有已知共同基线时才能以缺项判断谁修改了这一行。 */
    fun reviewRow(
        key: RecordKey,
        original: JSONObject?,
        left: JSONObject?,
        right: JSONObject?,
        based: Boolean,
        choose: (RecordConflict) -> Boolean?,
    ): Pair<JSONObject?, List<RecordConflict>> {
        val choices = MergeChoices(choose)
        fun merge() = mergeRow(key.field, key.id, original.takeIf { based }, left, right, based, choices)
        val result = if (based) resolveDelta(original, left, right, ::merge)
            else if (contentEquals(left, right)) left else merge()
        return result to choices.conflicts
    }

    fun applyLibrary(current: JSONObject, merged: JSONObject): JSONObject {
        val shared = merged.getJSONObject("library")
        return JSONObject(current.toString()).apply {
            for (field in libraryFields) {
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

    fun document(library: JSONObject, books: JSONArray): JSONObject =
        JSONObject().put("version", VERSION).put("library", library).put("books", books)

    private fun validate(document: JSONObject) {
        require(document.opt("version") == VERSION) { "不支持的同步文件版本" }
        val library = document.optJSONObject("library") ?: error("同步文件缺少用户库")
        require(libraryFields.all { library.optJSONArray(it) != null }) { "同步文件的用户库不完整" }
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
            .distinctBy { rowKey(PLAYLIST_SONGS, it) }
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

    private fun mergeRow(
        field: String,
        id: String,
        original: JSONObject?,
        left: JSONObject?,
        right: JSONObject?,
        based: Boolean,
        choices: MergeChoices,
    ): JSONObject? {
        val timeKey = historyTimeKey(field)
        if (left == null || right == null) {
            val remaining = requireNotNull(left ?: right)
            if (!based) return choices.row(field, id, null, left, right)
            val before = requireNotNull(original)
            if (!sameVersion(field, before, remaining)) return choices.row(field, id, before, left, right)
            return remaining.takeIf { timeKey != null && it.optLong(timeKey) > before.optLong(timeKey) }
        }
        return when {
            !based && !sameVersion(field, left, right) -> choices.row(field, id, null, left, right)
            based && field == "playlists" -> mergePlaylist(original, left, right, choices)
            based && field == "books" -> mergeBook(id, original, left, right, choices)
            field == "books" -> newer(left, right, "updatedAt")
            timeKey != null -> newer(left, right, timeKey)
            else -> right
        }
    }

    private fun mergePlaylist(original: JSONObject?, left: JSONObject, right: JSONObject, choices: MergeChoices): JSONObject {
        val id = right.getString("id")
        val title = original?.getString("name") ?: left.getString("name")
        val source = choices.field(
            "playlists", id, IMPORT_SOURCE, "来源 · $title",
            original?.optJSONObject(IMPORT_SOURCE), left.optJSONObject(IMPORT_SOURCE), right.optJSONObject(IMPORT_SOURCE),
        ) { playlistSourceLabel(it) }
        val sameSource = source != null &&
            contentEquals(left.opt(IMPORT_SOURCE), source) && contentEquals(right.opt(IMPORT_SOURCE), source)
        val matchingBase = original?.takeIf { contentEquals(it.opt(IMPORT_SOURCE), source) }
        val selectedBinding = if (contentEquals(right.opt(IMPORT_SOURCE), source)) right else left
        fun mergeBindingField(field: String): JSONArray? = if (sameSource) mergeIds(matchingBase, left, right, field)
            else selectedBinding.optJSONArray(field)
        val baseline = mergeBindingField(IMPORT_BASELINE)
        val exclusions = mergeBindingField(IMPORT_EXCLUSIONS)
        val additions = mergeBindingField(LOCAL_ADDITIONS)
        val excluded = exclusions?.stringSet().orEmpty().toMutableSet()
        val added = additions?.stringSet().orEmpty().toMutableSet()
        val leftSongs = left.entries(PLAYLIST_SONGS)
        val rightSongs = right.entries(PLAYLIST_SONGS)
        if (sameSource) {
            val contraryIds = added intersect excluded
            if (contraryIds.isNotEmpty()) {
                val leftAdded = left.optJSONArray(LOCAL_ADDITIONS)?.stringSet().orEmpty()
                val rightAdded = right.optJSONArray(LOCAL_ADDITIONS)?.stringSet().orEmpty()
                val names = (leftSongs + rightSongs).associate { rowKey(PLAYLIST_SONGS, it) to it.optString("name") }
                for (uid in contraryIds) {
                    val keep = (uid in leftAdded) != (uid in rightAdded) && choices.pick(
                        "playlists", id, "song:$uid", "$title · ${names[uid].orEmpty().ifBlank { uid }}",
                        uid in leftAdded, uid in rightAdded,
                    ) { if (it) "保留歌曲" else "移除歌曲" }
                    if (keep) excluded.remove(uid) else added.remove(uid)
                }
            }
        }
        val originalSongs = original?.entries(PLAYLIST_SONGS).orEmpty()
        val songs = mergeRows(
            originalSongs, leftSongs, rightSongs, identify = { rowKey(PLAYLIST_SONGS, it) },
        ) { _, _, ours, theirs -> if (ours == null) null else theirs }
        val retained = (leftSongs + rightSongs).filter { rowKey(PLAYLIST_SONGS, it) in added }
        val ordered = mergePlaylistOrder(
            id, title, originalSongs, leftSongs, rightSongs,
            (songs + retained).distinctBy { rowKey(PLAYLIST_SONGS, it) }
                .filterNot { rowKey(PLAYLIST_SONGS, it) in excluded },
            choices,
        )
        return JSONObject()
            .put("id", id)
            .put("name", choices.field(
                "playlists", id, "name", "歌单名称 · $title",
                original?.getString("name"), left.getString("name"), right.getString("name"),
            ) { it.orEmpty() })
            .put(PLAYLIST_SONGS, JSONArray(ordered))
            .apply {
                source?.let { put(IMPORT_SOURCE, it) }
                baseline?.let { put(IMPORT_BASELINE, it) }
                exclusions?.let { put(IMPORT_EXCLUSIONS, JSONArray(excluded)) }
                additions?.let {
                    val present = ordered.mapTo(hashSetOf()) { song -> rowKey(PLAYLIST_SONGS, song) }
                    put(LOCAL_ADDITIONS, JSONArray(added.filter { uid -> uid in present }))
                }
            }
    }

    private fun mergePlaylistOrder(
        id: String,
        title: String,
        original: List<JSONObject>,
        left: List<JSONObject>,
        right: List<JSONObject>,
        merged: List<JSONObject>,
        choices: MergeChoices,
    ): List<JSONObject> {
        fun ids(rows: List<JSONObject>) = rows.map { rowKey(PLAYLIST_SONGS, it) }
        val rows = merged.associateBy { rowKey(PLAYLIST_SONGS, it) }
        val ours = ids(left).filter(rows::containsKey)
        val theirs = ids(right).filter(rows::containsKey)
        val shared = ours intersect theirs.toSet()
        val baseOrder = ids(original).filter { it in shared }
        val inherited = baseOrder.toSet()
        val leftOrder = ours.filter { it in inherited }
        val rightOrder = theirs.filter { it in inherited }
        val preferLeft = when {
            leftOrder == rightOrder || leftOrder == baseOrder -> false
            rightOrder == baseOrder -> true
            else -> {
                val firstDifference = leftOrder.indices.first { leftOrder[it] != rightOrder[it] }
                choices.pick("playlists", id, "order", "曲目顺序 · $title", leftOrder, rightOrder) {
                    val song = rows.getValue(it[firstDifference])
                    "第 ${firstDifference + 1} 首 · ${song.optString("name").ifBlank { song.optString("songmid") }}"
                } == leftOrder
            }
        }
        val primary = if (preferLeft) ours else theirs
        val secondary = if (preferLeft) theirs else ours
        val included = primary.toSet()
        val insertions = mutableMapOf<String, List<String>>()
        var pending = mutableListOf<String>()
        for (uid in secondary) {
            if (uid !in included) pending.add(uid)
            else if (pending.isNotEmpty()) {
                insertions[uid] = pending
                pending = mutableListOf()
            }
        }
        return buildList {
            for (uid in primary) {
                insertions[uid]?.forEach { add(rows.getValue(it)) }
                add(rows.getValue(uid))
            }
            pending.forEach { add(rows.getValue(it)) }
        }
    }

    private fun mergeBook(
        id: String,
        original: JSONObject?,
        left: JSONObject,
        right: JSONObject,
        choices: MergeChoices,
    ): JSONObject {
        if (sameBookPoint(left, right)) return newer(left, right, "updatedAt")
        if (original != null) {
            if (sameBookPoint(original, left)) return right
            if (sameBookPoint(original, right)) return left
            val chapter = bookChapter(original)
            if (bookChapter(left) == chapter && bookChapter(right) == chapter) {
                val before = bookPosition(original)
                val ours = bookPosition(left)
                val theirs = bookPosition(right)
                if (ours >= before && theirs >= before) return if (ours > theirs) left else right
            }
        }
        return requireNotNull(choices.row("books", id, original, left, right))
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
                    val localUids = songs.filter(::isLocal).map { rowKey(PLAYLIST_SONGS, it) }
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
        val currentById = current.filterNot(::isLocal).associateBy { rowKey(field, it) }
        val online = shared.map { row ->
            currentById[rowKey(field, row)]?.takeIf { contentEquals(portable(it, field), row) } ?: row
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
        resolve: (String, JSONObject?, JSONObject?, JSONObject?) -> JSONObject?,
    ): List<JSONObject> {
        val before = original.associateBy(identify)
        val ours = left.associateBy(identify)
        val theirs = right.associateBy(identify)
        val keys = if (rowsEqual(original, right)) ours.keys + theirs.keys else theirs.keys + ours.keys
        return keys.mapNotNull { key ->
            val initial = before[key]
            val local = ours[key]
            val remote = theirs[key]
            resolveDelta(initial, local, remote) { resolve(key, initial, local, remote) }
        }
    }

    private inline fun resolveDelta(before: JSONObject?, left: JSONObject?, right: JSONObject?,
                                    changed: () -> JSONObject?): JSONObject? = when {
        contentEquals(before, left) -> right
        contentEquals(before, right) || contentEquals(left, right) -> left
        else -> changed()
    }

    private fun rowsEqual(first: List<JSONObject>, second: List<JSONObject>): Boolean =
        first.size == second.size && first.indices.all { contentEquals(first[it], second[it]) }

    private fun newer(left: JSONObject, right: JSONObject, timeKey: String): JSONObject =
        if (left.optLong(timeKey) > right.optLong(timeKey)) left else right

    fun rowKey(field: String, row: JSONObject): String {
        if (field == "books") return bookKey(row)
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

    private fun historyTimeKey(field: String): String? = when (field) {
        "recents" -> "playedAt"
        "recentContainers" -> "updatedAt"
        else -> null
    }

    private fun sameVersion(field: String, left: JSONObject, right: JSONObject) = when (field) {
        "playlists" -> playlistEdits(left) == playlistEdits(right)
        "books" -> sameBookPoint(left, right)
        else -> true
    }

    private fun playlistEdits(row: JSONObject): List<Any?> = listOf(
        row.getString("name"),
        row.optJSONObject(IMPORT_SOURCE)?.getString("value"),
        row.entries(PLAYLIST_SONGS).map { rowKey(PLAYLIST_SONGS, it) },
        row.optJSONArray(IMPORT_EXCLUSIONS)?.stringSet().orEmpty().sorted(),
        row.optJSONArray(LOCAL_ADDITIONS)?.stringSet().orEmpty().sorted(),
    )

    fun sameBookPoint(first: JSONObject, second: JSONObject): Boolean =
        bookChapter(first) == bookChapter(second) &&
            first.getBoolean("completed") == second.getBoolean("completed") && bookPosition(first) == bookPosition(second)

    fun bookChapter(row: JSONObject): String = rowKey(PLAYLIST_SONGS, row.getJSONObject("song"))

    fun bookPosition(row: JSONObject): Long =
        if (row.getBoolean("completed")) Long.MAX_VALUE else row.getLong("positionMs")

    private class MergeChoices(private val choose: (RecordConflict) -> Boolean?) {
        val conflicts = mutableListOf<RecordConflict>()

        fun row(field: String, id: String, base: JSONObject?, left: JSONObject?, right: JSONObject?): JSONObject? {
            val present = base ?: requireNotNull(left ?: right)
            val type = when (field) {
                "playlists" -> "歌单"
                "books" -> "听书断点"
                "recents", "recentContainers" -> "最近播放"
                else -> "收藏"
            }
            val title = if (field == "books") {
                val chapter = present.getJSONObject("song")
                chapter.optString("albumName").ifBlank { rowName(chapter, id) }
            } else rowName(present, id)
            fun fingerprint(value: JSONObject?): Any? = value?.let {
                when (field) {
                    "playlists" -> playlistEdits(it)
                    "books" -> JSONArray().put(bookChapter(it)).put(it.getBoolean("completed")).put(bookPosition(it))
                    else -> rowKey(field, it)
                }
            }
            val labels = if (left != null && right != null) {
                when (field) {
                    "playlists" -> playlistLabels(left, right)
                    "books" -> bookLabels(left, right)
                    else -> null
                }
            } else null
            return pick(field, id, "row", "$type · $title", left, right, ::fingerprint) { value ->
                when {
                    value == null -> "删除记录"
                    labels != null -> if (value === left) labels.first else labels.second
                    field == "playlists" -> playlistLabel(value)
                    field == "books" -> bookLabel(value)
                    else -> "保留记录"
                }
            }
        }

        fun <T> field(
            field: String,
            id: String,
            property: String,
            title: String,
            before: T,
            left: T,
            right: T,
            describe: (T) -> String,
        ): T = when {
            contentEquals(before, left) -> right
            contentEquals(before, right) || contentEquals(left, right) -> left
            else -> pick(field, id, property, title, left, right, describe = describe)
        }

        fun <T> pick(
            field: String,
            id: String,
            property: String,
            title: String,
            left: T,
            right: T,
            fingerprint: (T) -> Any? = { it },
            describe: (T) -> String,
        ): T {
            val bytes = buildString {
                appendCanonical(listOf(field, id, property, fingerprint(left), fingerprint(right)))
            }.toByteArray(Charsets.UTF_8)
            val key = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            val conflict = RecordConflict(key, title, describe(left), describe(right))
            val decision = choose(conflict)
            if (decision == null) conflicts.add(conflict)
            return if (decision == false) right else left
        }
    }

    private fun playlistLabel(row: JSONObject): String =
        "${row.getString("name")} · ${row.getJSONArray(PLAYLIST_SONGS).length()} 首"

    private fun playlistSourceLabel(binding: JSONObject?): String {
        if (binding == null) return "未绑定"
        val link = PlaylistImportLink(binding.getString("source"), binding.getString("value"))
        return "${link.platformName} · ${link.value}"
    }

    private fun playlistLabels(left: JSONObject, right: JSONObject): Pair<String, String> {
        val ours = playlistLabel(left)
        val theirs = playlistLabel(right)
        if (ours != theirs) return ours to theirs
        val leftSongs = left.entries(PLAYLIST_SONGS)
        val rightSongs = right.entries(PLAYLIST_SONGS)
        val difference = leftSongs.indices.firstOrNull {
            rowKey(PLAYLIST_SONGS, leftSongs[it]) != rowKey(PLAYLIST_SONGS, rightSongs[it])
        }
        if (difference != null) {
            val leftSong = leftSongs[difference]
            val rightSong = rightSongs[difference]
            val leftName = rowName(leftSong, rowKey(PLAYLIST_SONGS, leftSong))
            val rightName = rowName(rightSong, rowKey(PLAYLIST_SONGS, rightSong))
            val start = "$ours · 第 ${difference + 1} 首"
            return if (leftName != rightName) "$start $leftName" to "$start $rightName"
            else "$start $leftName (${rowKey(PLAYLIST_SONGS, leftSong)})" to "$start $rightName (${rowKey(PLAYLIST_SONGS, rightSong)})"
        }
        fun source(row: JSONObject) = playlistSourceLabel(row.optJSONObject(IMPORT_SOURCE))
        if (source(left) != source(right)) return "$ours · ${source(left)}" to "$theirs · ${source(right)}"
        for ((field, description) in listOf(IMPORT_EXCLUSIONS to "移除", LOCAL_ADDITIONS to "手动添加")) {
            val leftIds = left.optJSONArray(field)?.stringSet().orEmpty()
            val rightIds = right.optJSONArray(field)?.stringSet().orEmpty()
            val id = (leftIds + rightIds).firstOrNull { (it in leftIds) != (it in rightIds) } ?: continue
            val name = leftSongs.firstOrNull { rowKey(PLAYLIST_SONGS, it) == id }?.let { rowName(it, id) } ?: id
            fun describe(hasChange: Boolean) = "$ours · ${if (hasChange) description else "无$description 记录"} $name"
            return describe(id in leftIds) to describe(id in rightIds)
        }
        return ours to theirs
    }

    private fun bookLabel(row: JSONObject): String {
        val chapter = row.getJSONObject("song")
        val position = row.getLong("positionMs")
        val seconds = LocalSong.formatDuration(position / 1_000L)
        val location = when {
            row.getBoolean("completed") -> "本章听完"
            position % 1_000L == 0L -> seconds
            else -> "$seconds.${(position % 1_000L).toString().padStart(3, '0').trimEnd('0')}"
        }
        return "${rowName(chapter, chapter.optString("songmid"))} · $location"
    }

    private fun bookLabels(left: JSONObject, right: JSONObject): Pair<String, String> {
        val ours = bookLabel(left)
        val theirs = bookLabel(right)
        if (ours != theirs) return ours to theirs
        fun chapter(row: JSONObject): String {
            val song = row.getJSONObject("song")
            val ordinal = song.optInt("bookOrdinal")
            return if (ordinal > 0) "第 $ordinal 章" else song.optString("songmid")
        }
        val leftChapter = chapter(left)
        val rightChapter = chapter(right)
        return if (leftChapter != rightChapter) "$ours · $leftChapter" to "$theirs · $rightChapter"
        else "$ours · ${bookChapter(left)}" to "$theirs · ${bookChapter(right)}"
    }

    private fun rowName(row: JSONObject, fallback: String): String = row.optString("name").ifBlank { fallback }

    private fun StringBuilder.appendCanonical(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinTo(this, prefix = "{", postfix = "}") { key ->
                buildString { append(JSONObject.quote(key)).append(':').appendCanonical(value.get(key)) }
            }
            is JSONArray -> appendCanonical(List(value.length(), value::get))
            is List<*> -> value.joinTo(this, prefix = "[", postfix = "]") { item ->
                buildString { appendCanonical(item) }
            }
            is String -> append(JSONObject.quote(value))
            else -> append(value.toString())
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
