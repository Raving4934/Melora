package com.leyu.melora.playback.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 酷我听书目录（与 Web 版 /api/v1/audiobooks 完全同源）：
 * - 专区橱窗 mobileinterfaces.kuwo.cn/er.s (digest=13 即有声专辑)
 * - 榜单 wapi.kuwo.cn/openapi/v1/album/bang（tagList / dataList）
 * - 有声专辑章节 wapi.kuwo.cn/api/www/album/albumInfo（每页 100 章）
 * - 听书搜索 search.kuwo.cn/r.s + show_series_listen=1（只出长音频/有声专辑）
 * 章节归一化成 OnlineSong（source=kw）；播放由 SourceResolver 统一交给用户音源。
 */
object KwBookApi {
    private const val TAG = "KwBookApi"
    private const val PAGE_SIZE_ALBUM = 100
    private const val PAGE_SIZE_RANK = 50
    private const val PAGE_SIZE_SEARCH = 20
    private const val CHANNEL_SECTIONS_ID = "18"
    private val RANK_WHITELIST = listOf("13", "20", "1", "14", "15")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class BookTag(val id: String, val name: String)
    data class BookRankTab(val id: String, val name: String, val tags: List<BookTag>)
    data class BookSection(val id: String, val title: String, val items: List<OnlinePlaylist>)
    data class BookPage(val items: List<OnlinePlaylist>, val hasMore: Boolean)
    data class BookMetadata(
        val description: String = "",
        val playCount: Long = 0,
        val total: Int = 0,
        val author: String = "",
        val artwork: String? = null,
        val releaseDate: String = "",
        val language: String = "",
    )
    data class BookChapters(
        val items: List<OnlineSong>,
        val hasMore: Boolean,
        val metadata: BookMetadata = BookMetadata(),
        /** 已请求并合并进 items 的最后一页游标。 */
        val page: Int = 1,
    ) {
        val total: Int get() = metadata.total

        /** 生成完整合并快照，重复页或已知尾页不会继续触发分页。 */
        internal fun append(next: BookChapters, requestedPage: Int): BookChapters {
            val cursor = requestedPage.coerceAtLeast(1)
            val merged = (items + next.items).distinctBy(OnlineSong::uid)
            val resolvedMetadata = BookMetadata(
                description = next.metadata.description.ifBlank { metadata.description },
                playCount = next.metadata.playCount.takeIf { it > 0 } ?: metadata.playCount,
                total = next.metadata.total.takeIf { it > 0 } ?: metadata.total,
                author = next.metadata.author.ifBlank { metadata.author },
                artwork = next.metadata.artwork ?: metadata.artwork,
                releaseDate = next.metadata.releaseDate.ifBlank { metadata.releaseDate },
                language = next.metadata.language.ifBlank { metadata.language },
            )
            val nextHasMore = if (resolvedMetadata.total > 0) {
                bookPageHasMore(cursor, resolvedMetadata.total, next.items.size)
            } else {
                next.hasMore
            }
            return BookChapters(
                items = merged,
                hasMore = next.items.isNotEmpty() && merged.size > items.size && nextHasMore,
                metadata = resolvedMetadata,
                page = cursor,
            )
        }
    }

    /** 已知总数时按请求页判断，避免总数恰好为整页时把尾页误判为可继续。 */
    internal fun bookPageHasMore(page: Int, total: Int, itemCount: Int): Boolean = itemCount > 0 && when {
        total > 0 -> page.coerceAtLeast(1) * PAGE_SIZE_ALBUM < total
        else -> itemCount >= PAGE_SIZE_ALBUM
    }

    // ---------- 网络 ----------

    private suspend fun getObject(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Referer", "https://www.kuwo.cn/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body.string())
            }
        }.getOrNull()
    }

    private suspend fun getArray(url: String): JSONArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Referer", "https://www.kuwo.cn/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONArray(response.body.string())
            }
        }.getOrNull()
    }

    private fun urlencode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // ---------- 字段清洗 ----------

    private val tagRegex = Regex("<[^>]*>")

    private fun clean(raw: String): String {
        if (raw.isEmpty() || raw == "null") return ""
        return raw.replace("<br>", "\n", ignoreCase = true)
            .replace(tagRegex, "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .trim()
    }

    private val imageHosts = listOf(
        "img1.kuwo.cn", "img2.kuwo.cn", "img3.kuwo.cn", "img4.kuwo.cn",
        "kwimg1.kuwo.cn", "kwimg2.kuwo.cn", "kwimg3.kuwo.cn", "kwimg4.kuwo.cn",
        "kwcdn.kuwo.cn", "sycdn.kuwo.cn", "h5s.kuwo.cn",
    )

    private fun normalizeImage(raw: String, base: String = "https://img1.kuwo.cn/star/albumcover/"): String {
        var value = raw.trim()
        if (value.isEmpty() || value == "null") return ""
        if (value.startsWith("//")) value = "https:$value"
        if (!value.startsWith("http")) {
            if (value.startsWith("/") || value.contains('?') || value.contains('#') || value.contains('\\')) return ""
            value = base + value
        }
        if (value.startsWith("http://")) {
            val host = value.removePrefix("http://").substringBefore('/')
            if (imageHosts.any { host == it || host.endsWith(".$it") }) {
                value = "https://" + value.removePrefix("http://")
            }
        }
        return value
    }

    private fun formatCount(count: Long): String = when {
        count <= 0 -> ""
        count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
        count >= 10_000 -> "%.1f万".format(count / 10_000.0)
        else -> count.toString()
    }

    private fun optText(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun optLong(json: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = json.opt(key) ?: continue
            val parsed = when (value) {
                is Number -> value.toLong()
                else -> value.toString().replace(",", "").toDoubleOrNull()?.toLong()
            }
            if (parsed != null && parsed > 0) return parsed
        }
        return 0
    }

    private fun albumRaw(
        rid: String,
        name: String,
        cover: String,
        author: String,
        total: Int,
        playCount: Long,
        description: String = "",
    ): JSONObject = JSONObject()
        .put("source", "kw")
        .put("kind", "book")
        .put("id", "book_album_$rid")
        .put("name", name)
        .put("img", cover)
        .put("author", author)
        .put("total", total)
        .put("play_count", if (playCount > 0) formatCount(playCount) else "")
        .put("description", description)

    // ---------- 榜单 ----------

    suspend fun ranks(): List<BookRankTab> {
        val response = getObject("https://wapi.kuwo.cn/openapi/v1/album/bang/tagList") ?: return emptyList()
        if (response.optInt("code", -1) != 200) return emptyList()
        val data = response.optJSONObject("data") ?: return emptyList()
        val rows = data.optJSONArray("list") ?: return emptyList()
        val index = mutableMapOf<String, JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id")
            if (id.isNotBlank()) index[id] = row
        }
        val out = mutableListOf<BookRankTab>()
        for (id in RANK_WHITELIST) {
            val row = index[id] ?: continue
            val name = clean(row.optString("name"))
            if (name.isBlank()) continue
            val tags = mutableListOf<BookTag>()
            val seen = mutableSetOf<String>()
            val tagRows = row.optJSONArray("tagList") ?: continue
            for (j in 0 until tagRows.length()) {
                val tag = tagRows.optJSONObject(j) ?: continue
                val tagId = tag.optString("id")
                val tagName = clean(tag.optString("name"))
                if (tagId.isBlank() || tagName.isBlank() || tagId in seen) continue
                if (tag.has("isShow") && tag.optInt("isShow") == 0) continue
                seen += tagId
                tags += BookTag(tagId, tagName)
            }
            if (tags.isEmpty()) continue
            out += BookRankTab(id, name, tags)
        }
        return out
    }

    suspend fun rank(tabId: String, tagId: String, page: Int): BookPage {
        val url = "https://wapi.kuwo.cn/openapi/v1/album/bang/dataList" +
            "?tabId=${urlencode(tabId)}&tagId=${urlencode(tagId)}&pn=$page&rn=$PAGE_SIZE_RANK"
        val response = getObject(url) ?: return BookPage(emptyList(), false)
        if (response.optInt("code", -1) != 200) return BookPage(emptyList(), false)
        val rows = response.optJSONObject("data")?.optJSONArray("list") ?: return BookPage(emptyList(), false)
        val items = mutableListOf<OnlinePlaylist>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val item = rankItem(rows.optJSONObject(i) ?: continue) ?: continue
            if (!seen.add(item.id)) continue
            items += item
        }
        return BookPage(items, rows.length() >= PAGE_SIZE_RANK)
    }

    private fun rankItem(row: JSONObject): OnlinePlaylist? {
        val rid = row.optString("id").ifBlank { row.optString("albumid") }
        val title = clean(row.optString("name"))
        if (rid.isBlank() || title.isBlank()) return null
        val total = row.optInt("musicnum", 0)
        val playCount = row.optLong("listencnt", 0)
        return OnlinePlaylist.from(
            albumRaw(
                rid = rid,
                name = title,
                cover = normalizeImage(row.optString("pic")),
                author = clean(row.optString("artist")),
                total = total,
                playCount = playCount,
                description = clean(optText(row, "desc", "description", "subtitle"))
                    .ifBlank { clean(row.optJSONObject("data")?.let { optText(it, "desc", "description") }.orEmpty()) },
            ),
        )
    }

    // ---------- 专区橱窗 ----------

    suspend fun homeSections(): List<BookSection> {
        val url = "https://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web" +
            "&id=$CHANNEL_SECTIONS_ID&prod=pc&ver=1"
        val rows = getArray(url) ?: return emptyList()
        val out = mutableListOf<BookSection>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val title = clean(row.optString("label"))
            val items = row.optJSONArray("list") ?: continue
            if (title.isBlank()) continue
            val sectionItems = mutableListOf<OnlinePlaylist>()
            for (j in 0 until items.length()) {
                val item = sectionItem(items.optJSONObject(j) ?: continue) ?: continue
                if (!seen.add(item.id)) continue
                sectionItems += item
                if (sectionItems.size >= 30) break
            }
            if (sectionItems.isEmpty()) continue
            out += BookSection("book_${CHANNEL_SECTIONS_ID}_${out.size + 1}", title, sectionItems)
            if (out.size >= 30) break
        }
        return out
    }

    private fun sectionItem(row: JSONObject): OnlinePlaylist? {
        val rid = row.optString("id").ifBlank { row.optString("albumid") }
        // digest=13 才是有声专辑，其它类型没有稳定详情接口，直接跳过
        if (row.optString("digest") != "13") return null
        val title = clean(row.optString("name"))
        if (rid.isBlank() || title.isBlank()) return null
        return OnlinePlaylist.from(
            albumRaw(
                rid = rid,
                name = title,
                cover = normalizeImage(row.optString("img")),
                author = clean(row.optString("artist")),
                total = row.optInt("musicnum", 0),
                playCount = row.optLong("listencnt", 0),
                description = clean(optText(row, "desc", "description")),
            ),
        )
    }

    // ---------- 听书搜索 ----------

    suspend fun search(keyword: String, page: Int): BookPage {
        val url = "https://search.kuwo.cn/r.s?all=${urlencode(keyword)}&ft=album" +
            "&pn=${page - 1}&rn=$PAGE_SIZE_SEARCH&rformat=json&encoding=utf8&client=kt&mobi=1&newver=1" +
            "&show_series_listen=1"
        return searchPageFromResponse(checkNotNull(getObject(url)) { "听书作品加载失败，请检查网络后重试" })
    }

    /** 解析公开专辑搜索响应；空 albumlist 是合法零结果，缺失/错误结构则显式失败。 */
    internal fun searchPageFromResponse(response: JSONObject): BookPage {
        val rows = response.optJSONArray("albumlist")
            ?: throw IllegalStateException("酷我听书搜索响应缺少 albumlist 数组")
        val items = mutableListOf<OnlinePlaylist>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val rid = optText(row, "albumid", "id")
            val title = clean(optText(row, "name", "title"))
            if (rid.isBlank() || title.isBlank() || !seen.add(rid)) continue
            val cover = normalizeImage(
                optText(row, "hts_img", "img", "pic"),
                base = "https://img1.kuwo.cn/star/albumcover/",
            )
            items += OnlinePlaylist.from(
                albumRaw(
                    rid = rid,
                    name = title,
                    cover = cover,
                    author = clean(optText(row, "artist")),
                    total = optText(row, "musiccnt", "songnum").toIntOrNull() ?: 0,
                    playCount = optLong(row, "listencnt", "playCnt", "play_count"),
                    description = clean(optText(row, "albuminfo", "info", "desc", "description")),
                ),
            )!!
        }
        return BookPage(items, rows.length() >= PAGE_SIZE_SEARCH)
    }

    /**
     * 按作者/主播入口查听书专辑：只复用专辑搜索，不把单曲或章节搜索结果混入专辑卡片。
     * 搜索页是本方法的游标；过滤后即使当前页为空，也必须保留原搜索页的 hasMore。
     */
    suspend fun authorAlbums(author: String, page: Int): BookPage {
        val requestedAuthor = author.trim()
        if (requestedAuthor.isBlank() || page < 1) return BookPage(emptyList(), false)
        return filterAuthorAlbums(requestedAuthor, search(requestedAuthor, page))
    }

    /** 仅过滤当前搜索页，不为凑满一页继续扫描，保持 search 的分页游标语义。 */
    internal fun filterAuthorAlbums(author: String, page: BookPage): BookPage {
        if (author.isBlank()) return BookPage(emptyList(), false)
        return page.copy(items = page.items.filter { authorMatches(author, it.author) })
    }

    /**
     * 作者字段是多人列表时按完整姓名段匹配，避免 contains 把“张三”误匹配到“张三丰”。
     * 分隔规则与公开目录元数据链路保持一致。
     */
    internal fun authorMatches(requested: String, listed: String): Boolean {
        val requestedNames = splitAuthorNames(requested)
            .map(::authorNameKey)
            .filter(String::isNotBlank)
        if (requestedNames.isEmpty()) return false
        return splitAuthorNames(listed)
            .map(::authorNameKey)
            .any { it in requestedNames }
    }

    private fun splitAuthorNames(value: String): List<String> =
        value.split('、', '/', ',', '，', '&').map(String::trim)

    private fun authorNameKey(value: String): String = value.trim().lowercase(Locale.ROOT)

    // ---------- 有声专辑章节 ----------

    suspend fun album(albumRef: String, page: Int): BookChapters {
        val rid = albumRef.removePrefix("book_album_").removePrefix("kw:book_album_")
        if (rid.isBlank()) return BookChapters(emptyList(), false, page = page)
        val url = "https://wapi.kuwo.cn/api/www/album/albumInfo" +
            "?albumId=${urlencode(rid)}&pn=$page&rn=$PAGE_SIZE_ALBUM&httpsStatus=1"
        val response = getObject(url) ?: return BookChapters(emptyList(), false, page = page)
        if (response.optInt("code", -1) != 200) return BookChapters(emptyList(), false, page = page)
        val data = response.optJSONObject("data") ?: return BookChapters(emptyList(), false, page = page)
        val actualId = optText(data, "albumid", "albumId").substringBefore(".")
        if (actualId.isNotBlank() && actualId != rid) return BookChapters(emptyList(), false, page = page)
        val albumTitle = clean(optText(data, "album"))
        val metadata = bookMetadataFromDetail(data)
        val rows = data.optJSONArray("musicList") ?: return BookChapters(emptyList(), false, metadata, page)
        if (rows.length() > PAGE_SIZE_ALBUM) return BookChapters(emptyList(), false, metadata, page)
        // 章节接口的行有时不带 albumid：用已校验的专辑 id 兜底，保证播放页“出自专辑”能复用章节顺序
        val albumId = actualId.ifBlank { rid }
        val items = mutableListOf<OnlineSong>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val song = chapter(rows.optJSONObject(i) ?: continue, albumTitle, albumId, metadata) ?: continue
            if (!seen.add(song.uid)) continue
            items += song
        }
        val hasMore = bookPageHasMore(page, metadata.total, rows.length())
        items.forEachIndexed { index, song ->
            song.raw.put("bookPage", page)
                .put("bookPageEnd", index == items.lastIndex)
                .put("bookHasMore", hasMore)
        }
        return BookChapters(items, hasMore, metadata, page)
    }

    internal fun bookMetadataFromDetail(data: JSONObject): BookMetadata = BookMetadata(
        description = clean(optText(data, "albuminfo", "info", "desc", "description")),
        playCount = optLong(data, "playCnt", "listencnt", "play_count", "playCount"),
        total = optText(data, "total", "musicnum", "songnum").toIntOrNull()?.coerceAtLeast(0) ?: 0,
        author = clean(optText(data, "artist", "author", "singer")),
        artwork = normalizeImage(optText(data, "pic", "img", "albumpic")).takeIf { it.isNotBlank() },
        releaseDate = clean(optText(data, "releaseDate", "publish", "publishTime")),
        language = clean(optText(data, "lang", "language")),
    )

    private fun chapter(row: JSONObject, albumTitle: String, albumId: String, metadata: BookMetadata): OnlineSong? {
        var rid = row.optString("rid")
        if (rid.isBlank()) {
            rid = optText(row, "musicrid", "musicrId", "MUSICRID").removePrefix("MUSIC_")
        }
        if (rid.isBlank()) return null
        val name = clean(optText(row, "name", "songName"))
        if (name.isBlank()) return null
        val duration = row.optInt("duration", 0).coerceIn(0, 24 * 60 * 60)
        val interval = "%02d:%02d".format(duration / 60, duration % 60)
        val raw = JSONObject()
            .put("source", "kw")
            .put("songmid", rid)
            .put("rid", rid)
            .put("musicId", rid)
            .put("MUSICRID", "MUSIC_$rid")
            .put("musicrid", "MUSIC_$rid")
            .put("name", name)
            .put("singer", clean(optText(row, "artist")).ifBlank { metadata.author })
            .put("albumName", albumTitle.ifBlank { clean(optText(row, "album")) })
            .put("albumId", optText(row, "albumid", "albumId").ifBlank { albumId })
            .put("duration", duration)
            .put("interval", interval)
            .put("isBookChapter", true)
            .put("description", metadata.description)
            .put("play_count", if (metadata.playCount > 0) formatCount(metadata.playCount) else "")
            // 目录未提供可靠音质清单；音源脚本回传实际格式与档位
            .put("types", org.json.JSONArray().put(JSONObject().put("type", "128k")))
            .put("_types", JSONObject().put("128k", JSONObject()))
            .put("img", normalizeImage(optText(row, "pic", "albumpic")))
        return OnlineSong.from(raw)
    }
}
