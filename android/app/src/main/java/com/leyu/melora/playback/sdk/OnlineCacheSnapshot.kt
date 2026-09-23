package com.leyu.melora.playback.sdk

import org.json.JSONArray
import org.json.JSONObject

internal const val ONLINE_CACHE_SNAPSHOT_VERSION = 1
internal const val ONLINE_CACHE_SNAPSHOT_MAX_ENTRIES = 32
internal const val ONLINE_CACHE_SNAPSHOT_MAX_BYTES = 8L * 1024L * 1024L
internal const val ONLINE_CACHE_SNAPSHOT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

internal const val ONLINE_CACHE_BOARD_LIST_KIND = "board_list"
internal const val ONLINE_CACHE_PLAYLIST_FIRST_PAGE_KIND = "playlist_first_page"

/** 歌单广场的内存页快照；磁盘只接受 page=1，后续页仅存在于进程内。 */
data class CachedPlaylistPage(
    val list: List<OnlinePlaylist>,
    val page: Int,
    val hasMore: Boolean,
    val total: Int = 0,
)

internal data class OnlineCacheSnapshotMetadata(
    val key: String,
    val kind: String,
    val writtenAtMs: Long,
)

internal data class OnlineCacheDecodedSnapshot<T>(
    val value: T,
    val writtenAtMs: Long,
)

/**
 * 磁盘快照的显式 JSON 白名单编解码器。
 * 不序列化 raw/Any，版本或字段不完整时整条快照作废，回退到网络加载。
 */
internal object OnlineCacheSnapshotCodec {
    fun encodeBoardList(key: String, value: List<BoardItem>, writtenAtMs: Long): ByteArray =
        JSONObject()
            .put("version", ONLINE_CACHE_SNAPSHOT_VERSION)
            .put("kind", ONLINE_CACHE_BOARD_LIST_KIND)
            .put("key", key)
            .put("writtenAt", writtenAtMs)
            .put("items", JSONArray().apply {
                value.forEach { board ->
                    put(
                        JSONObject()
                            .put("id", board.id)
                            .put("name", board.name)
                            .put("bangid", board.bangid)
                            .put("img", board.img ?: JSONObject.NULL),
                    )
                }
            })
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun encodePlaylistFirstPage(
        key: String,
        value: CachedPlaylistPage,
        writtenAtMs: Long,
    ): ByteArray = JSONObject()
        .put("version", ONLINE_CACHE_SNAPSHOT_VERSION)
        .put("kind", ONLINE_CACHE_PLAYLIST_FIRST_PAGE_KIND)
        .put("key", key)
        .put("writtenAt", writtenAtMs)
        .put("page", value.page)
        .put("hasMore", value.hasMore)
        .put("total", value.total)
        .put("items", JSONArray().apply {
            value.list.forEach { playlist -> put(playlistToJson(playlist)) }
        })
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun readMetadata(bytes: ByteArray): OnlineCacheSnapshotMetadata? {
        val root = parseRoot(bytes) ?: return null
        val version = root.opt("version") as? Number ?: return null
        if (version.toInt() != ONLINE_CACHE_SNAPSHOT_VERSION) return null
        val key = root.optString("key").takeIf { it.isNotBlank() } ?: return null
        val kind = root.optString("kind").takeIf { it.isNotBlank() } ?: return null
        val writtenAtMs = (root.opt("writtenAt") as? Number)?.toLong() ?: return null
        if (writtenAtMs <= 0L) return null
        return OnlineCacheSnapshotMetadata(key, kind, writtenAtMs)
    }

    fun decodeBoardList(
        expectedKey: String,
        bytes: ByteArray,
        nowMs: Long,
    ): OnlineCacheDecodedSnapshot<List<BoardItem>>? {
        val root = parseAndValidate(
            expectedKey = expectedKey,
            expectedKind = ONLINE_CACHE_BOARD_LIST_KIND,
            bytes = bytes,
            nowMs = nowMs,
        ) ?: return null
        val array = root.optJSONArray("items") ?: return null
        if (array.length() == 0) return null

        val boards = ArrayList<BoardItem>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return null
            val bangid = item.optString("bangid").takeIf { it.isNotBlank() } ?: return null
            boards += BoardItem(
                id = item.optString("id"),
                name = name,
                bangid = bangid,
                img = item.optString("img").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return OnlineCacheDecodedSnapshot(boards, root.writtenAtMs)
    }

    fun decodePlaylistFirstPage(
        expectedKey: String,
        bytes: ByteArray,
        nowMs: Long,
    ): OnlineCacheDecodedSnapshot<CachedPlaylistPage>? {
        val root = parseAndValidate(
            expectedKey = expectedKey,
            expectedKind = ONLINE_CACHE_PLAYLIST_FIRST_PAGE_KIND,
            bytes = bytes,
            nowMs = nowMs,
        ) ?: return null
        val page = root.opt("page") as? Number ?: return null
        if (page.toInt() != 1) return null
        val hasMore = root.opt("hasMore") as? Boolean ?: return null
        val total = (root.opt("total") as? Number)?.toInt() ?: return null
        val array = root.optJSONArray("items") ?: return null
        if (array.length() == 0) return null

        val playlists = ArrayList<OnlinePlaylist>(array.length())
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: return null
            playlists += OnlinePlaylist.from(raw) ?: return null
        }
        return OnlineCacheDecodedSnapshot(
            CachedPlaylistPage(
                list = playlists,
                page = 1,
                hasMore = hasMore,
                total = total,
            ),
            root.writtenAtMs,
        )
    }

    private fun playlistToJson(playlist: OnlinePlaylist): JSONObject = JSONObject()
        .put("id", playlist.id)
        .put("name", playlist.name)
        .put("img", playlist.img ?: JSONObject.NULL)
        .put("author", playlist.author)
        .put("description", playlist.description)
        .put("play_count", playlist.playCount)
        .put("play_num", playlist.playNum)
        .put("total", playlist.total)
        .put("source", playlist.source)
        .apply {
            if (playlist.isBookAlbum) put("kind", "book")
        }

    private fun parseAndValidate(
        expectedKey: String,
        expectedKind: String,
        bytes: ByteArray,
        nowMs: Long,
    ): JSONObjectWithTimestamp? {
        val root = parseRoot(bytes) ?: return null
        val metadata = readMetadata(bytes) ?: return null
        if (metadata.key != expectedKey || metadata.kind != expectedKind) return null
        if (nowMs >= metadata.writtenAtMs &&
            nowMs - metadata.writtenAtMs > ONLINE_CACHE_SNAPSHOT_MAX_AGE_MS
        ) return null
        return JSONObjectWithTimestamp(root, metadata.writtenAtMs)
    }

    private fun parseRoot(bytes: ByteArray): JSONObject? =
        runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()

    private data class JSONObjectWithTimestamp(
        val root: JSONObject,
        val writtenAtMs: Long,
    ) {
        fun optJSONArray(name: String): JSONArray? = root.optJSONArray(name)
        fun opt(name: String): Any? = root.opt(name)
        fun optString(name: String): String = root.optString(name)
        fun optJSONObject(name: String): JSONObject? = root.optJSONObject(name)
    }
}
