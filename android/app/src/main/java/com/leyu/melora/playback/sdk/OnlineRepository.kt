package com.leyu.melora.playback.sdk

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 在线目录仓库：把 MusicSdkEngine 的 JSON 结果转为强类型模型。 */
object OnlineRepository {
    suspend fun search(
        context: Context,
        source: String,
        text: String,
        page: Int = 1,
        limit: Int = 30,
        background: Boolean = false,
        timeoutMs: Long = 25_000,
    ): SongPage {
        val data = MusicSdkEngine.call(
            context = context,
            action = "search",
            source = source,
            params = JSONObject().put("text", text).put("page", page).put("limit", limit),
            timeoutMs = timeoutMs,
            background = background,
        )
        return songPage(data)
    }

    suspend fun songlistSearch(
        context: Context,
        source: String,
        text: String,
        page: Int = 1,
        background: Boolean = false,
    ): PlaylistPage {
        val data = MusicSdkEngine.call(context, "songlistSearch", source, JSONObject()
            .put("text", text).put("page", page), background = background)
        return playlistPage(data)
    }

    suspend fun hotSearch(context: Context, source: String, background: Boolean = false): List<String> {
        val data = MusicSdkEngine.call(context, "hotSearch", source, background = background)
        return data.optJSONArray("list")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { word -> word.isNotBlank() } }
        }.orEmpty()
    }

    suspend fun tipSearch(context: Context, source: String, text: String): List<String> {
        val data = MusicSdkEngine.call(context, "tipSearch", source, JSONObject().put("text", text))
        return data.optJSONArray("list")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { word -> word.isNotBlank() } }
        }.orEmpty()
    }

    suspend fun boards(context: Context, source: String, background: Boolean = false): List<BoardItem> {
        val data = MusicSdkEngine.call(context, "boards", source, background = background)
        return data.optJSONArray("list")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    BoardItem(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        bangid = item.optString("bangid"),
                        img = item.optString("img").takeIf { it.isNotBlank() && it != "null" },
                    )
                }?.takeIf { it.name.isNotBlank() }
            }
        }.orEmpty()
    }

    suspend fun boardSongs(
        context: Context,
        source: String,
        bangId: String,
        page: Int = 1,
        background: Boolean = false,
        limit: Int? = null,
    ): SongPage {
        val params = JSONObject()
            .put("bangId", bangId)
            .put("page", page)
        limit?.let { params.put("limit", it) }
        val data = MusicSdkEngine.call(context, "boardSongs", source, params, background = background)
        return songPage(data)
    }

    suspend fun playlistTags(context: Context, source: String, background: Boolean = false): TagInfo {
        val data = MusicSdkEngine.call(context, "playlistTags", source, background = background)
        val hot = data.optJSONArray("hotTag")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Tag(it.optString("id"), it.optString("name")) }
            }
        }.orEmpty()
        val groups = data.optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val group = array.optJSONObject(index) ?: return@mapNotNull null
                val items = group.optJSONArray("list")?.let { list ->
                    (0 until list.length()).mapNotNull { itemIndex ->
                        list.optJSONObject(itemIndex)?.let { Tag(it.optString("id"), it.optString("name")) }
                    }
                }.orEmpty()
                TagGroup(group.optString("name"), items)
            }
        }.orEmpty()
        return TagInfo(hot, groups)
    }

    /** 各平台排序参数不同：kg 最热6/最新7，tx 最热5/最新2，其余沿用 hot/new。 */
    private fun platformSortId(source: String, sortId: String): String = when (source) {
        "kg" -> if (sortId == "new") "7" else "6"
        "tx" -> if (sortId == "new") "2" else "5"
        else -> sortId
    }

    suspend fun playlists(
        context: Context,
        source: String,
        sortId: String,
        tagId: String,
        page: Int = 1,
        background: Boolean = false,
    ): PlaylistPage {
        val data = MusicSdkEngine.call(context, "playlists", source, JSONObject()
            .put("sortId", platformSortId(source, sortId)).put("tagId", tagId).put("page", page), background = background)
        return playlistPage(data)
    }

    suspend fun playlistSongs(context: Context, source: String, id: String, page: Int = 1): SongPage {
        val data = MusicSdkEngine.call(context, "playlistSongs", source, JSONObject()
            .put("id", id).put("page", page))
        return songPage(data)
    }

    suspend fun lyric(context: Context, source: String, song: OnlineSong, background: Boolean = false): OnlineLyric {
        val data = MusicSdkEngine.call(context, "lyric", source, JSONObject().put("song", song.raw), background = background)
        return OnlineLyric.from(data, source, song)
    }

    suspend fun pic(
        context: Context,
        source: String,
        song: OnlineSong,
        timeoutMs: Long = 12_000,
        background: Boolean = false,
    ): String? = try {
        val data = MusicSdkEngine.call(
            context,
            "pic",
            source,
            JSONObject().put("song", song.raw),
            timeoutMs,
            background,
        )
        data.optString("url").takeIf { it.isNotBlank() && it != "null" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun songPage(data: JSONObject): SongPage {
        val list = data.optJSONArray("list")?.let { array ->
            (0 until array.length())
                .mapNotNull { index -> OnlineSong.from(array.optJSONObject(index)) }
                .musicOnly()
        }.orEmpty()
        return SongPage(
            list = list,
            total = data.optInt("total"),
            page = data.optInt("page", 1),
            allPage = data.optInt("allPage"),
        )
    }

    private fun playlistPage(data: JSONObject): PlaylistPage {
        val list = data.optJSONArray("list")?.let { array ->
            (0 until array.length()).mapNotNull { index -> OnlinePlaylist.from(array.optJSONObject(index)) }
        }.orEmpty()
        return PlaylistPage(list, data.optInt("total"), data.optInt("page", 1))
    }
}
