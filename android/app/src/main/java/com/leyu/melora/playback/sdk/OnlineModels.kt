package com.leyu.melora.playback.sdk

import com.leyu.melora.playback.AudioQualityBadge
import com.leyu.melora.playback.AudioQualityTier
import com.leyu.melora.playback.compressedAudioBadge
import java.util.Calendar
import java.util.Locale
import java.util.RandomAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 在线歌曲：包装 musicSdk 归一化后的 old-format JSON（与音源脚本互通）。 */
data class OnlineSong(val raw: JSONObject) {
    val source: String get() = raw.optString("source")
    val songmid: String get() = raw.optString("songmid")
    val name: String get() = raw.optString("name")
    val singer: String get() = raw.optString("singer")
    val albumName: String get() = raw.optString("albumName")
    val albumId: String get() = raw.optString("albumId")
    val year: Int? get() = onlineSongYear(raw)
    val interval: String get() = raw.optString("interval").takeIf { it.isNotBlank() } ?: "00:00"
    val img: String? get() = raw.optString("img").takeIf { it.isNotBlank() && it != "null" }
    val isBookChapter: Boolean get() = raw.optBoolean("isBookChapter")
    val uid: String get() = "${source}_$songmid"

    val qualitys: List<String>
        get() {
            val fromMap = raw.optJSONObject("_types")?.keys()?.asSequence()?.toList().orEmpty()
            if (fromMap.isNotEmpty()) return fromMap
            val arr = raw.optJSONArray("types") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("type")?.takeIf { it.isNotBlank() }
            }
        }

    /** 列表响应已声明的最高音质；只读目录元数据，不触发播放地址解析。 */
    val bestQualityBadge: AudioQualityBadge?
        get() = qualityBadgeForCapabilities(qualitys)

    val intervalSeconds: Int
        get() {
            val parts = interval.split(":")
            return when (parts.size) {
                2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
                else -> 0
            }
        }

    companion object {
        fun from(raw: JSONObject?): OnlineSong? =
            raw?.takeIf { it.optString("source").isNotBlank() && it.optString("songmid").isNotBlank() }?.let(::OnlineSong)
    }
}

internal fun onlineSongYear(raw: JSONObject): Int? {
    val keys = arrayOf("year", "releaseDate", "publishTime", "date", "time_public", "publish_time", "release_date")
    keys.forEach { key ->
        if (!raw.has(key) || raw.isNull(key)) return@forEach
        parseOnlineYear(raw.opt(key))?.let { return it }
    }
    return null
}

private fun parseOnlineYear(value: Any?): Int? {
    val number = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
    if (number != null) {
        if (number in 1900L..2100L) return number.toInt()
        if (number in 19_000_000L..21_001_231L) return (number / 10_000L).toInt()
        val epochMs = when {
            number >= 100_000_000_000L -> number
            number >= 1_000_000_000L -> number * 1_000L
            else -> null
        }
        if (epochMs != null) {
            val year = Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.YEAR)
            if (year in 1900..2100) return year
        }
    }
    return YEAR_PATTERN.find(value?.toString().orEmpty())?.value?.toIntOrNull()
}

private val YEAR_PATTERN = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")


/** 顺序与目录优先级一致。目录声明不能提供采样率或实测码率。 */
private val catalogBadges = mapOf(
    "master" to AudioQualityBadge(AudioQualityTier.Master, shortSpec = "母带"),
    "dsd" to AudioQualityBadge(AudioQualityTier.Dsd),
    "atmos_plus" to AudioQualityBadge(AudioQualityTier.Atmos, shortSpec = "DOLBY"),
    "atmos" to AudioQualityBadge(AudioQualityTier.Atmos, shortSpec = "DOLBY"),
    "spatial" to AudioQualityBadge(AudioQualityTier.Spatial),
    "flac32bit" to AudioQualityBadge(AudioQualityTier.HiRes, shortSpec = "32-bit"),
    "flac24bit" to AudioQualityBadge(AudioQualityTier.HiRes, shortSpec = "24-bit"),
    "hires" to AudioQualityBadge(AudioQualityTier.HiRes),
    "flac" to AudioQualityBadge(AudioQualityTier.Lossless, shortSpec = "FLAC"),
    "ape" to AudioQualityBadge(AudioQualityTier.Lossless, shortSpec = "APE"),
    "wav" to AudioQualityBadge(AudioQualityTier.Lossless, shortSpec = "WAV"),
)

private val catalogBitrate = Regex("""(aac|ogg|opus)?([1-9][0-9]{0,3})k""")

internal fun qualityBadgeForCapabilities(qualities: Iterable<String>): AudioQualityBadge? {
    val keys = qualities.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    catalogBadges.entries.firstOrNull { it.key in keys }?.let { return it.value }
    val best = keys.mapNotNull(catalogBitrate::matchEntire)
        .maxWithOrNull(compareBy({ it.groupValues[2].toInt() }, { it.value }))
        ?: return when {
            "opus" in keys -> AudioQualityBadge(AudioQualityTier.Opus)
            "standard" in keys -> AudioQualityBadge(AudioQualityTier.Standard)
            else -> null
        }
    return compressedAudioBadge(best.groupValues[2].toInt(), if (best.groupValues[1] == "opus") "Opus" else null)
}

internal fun Iterable<OnlineSong>.musicOnly(): List<OnlineSong> = filterNot(OnlineSong::isBookChapter)

private fun inferredSongPageHasMore(
    rawCount: Int,
    pageSize: Int,
    total: Int,
    page: Int,
    allPage: Int,
    loadedCount: Int,
): Boolean = rawCount > 0 && when {
    allPage > 0 -> page < allPage
    total > 0 -> loadedCount < total
    else -> rawCount >= pageSize
}

/** 保留展示顺序和有序 UID。追加时只读新页的 UID，旧快照可继续扩展为不同分支。 */
private class IndexedSongs(
    private val rows: List<OnlineSong>,
    private val sortedUids: Array<String>,
) : AbstractList<OnlineSong>(), RandomAccess {
    override val size: Int get() = rows.size
    override fun get(index: Int): OnlineSong = rows[index]

    private data class Insertion(val song: OnlineSong, val uid: String, val index: Int)

    fun append(next: IndexedSongs): IndexedSongs {
        val additions = next.mapNotNull { song ->
            val uid = song.uid
            val position = sortedUids.binarySearch(uid)
            if (position < 0) Insertion(song, uid, -position - 1) else null
        }
        if (additions.isEmpty()) return this

        val ordered = ArrayList<OnlineSong>(size + additions.size)
        ordered.addAll(rows)
        additions.forEach { ordered.add(it.song) }

        val uids = Array(ordered.size) { "" }
        var previous = 0
        var written = 0
        for ((_, uid, index) in additions.sortedBy(Insertion::uid)) {
            sortedUids.copyInto(uids, written, previous, index)
            written += index - previous
            uids[written++] = uid
            previous = index
        }
        sortedUids.copyInto(uids, written, previous)
        return IndexedSongs(ordered, uids)
    }

    companion object {
        fun from(entries: List<OnlineSong>): IndexedSongs {
            if (entries is IndexedSongs) return entries
            val unique = LinkedHashMap<String, OnlineSong>()
            entries.forEach { unique.putIfAbsent(it.uid, it) }
            val uids = unique.keys.toTypedArray().apply { sort() }
            return IndexedSongs(unique.values.toList(), uids)
        }
    }
}

data class SongPage(
    /** 音源返回的有效记录，供导入计数与版本匹配使用。 */
    internal val entries: List<OnlineSong>,
    val total: Int,
    val page: Int,
    val allPage: Int,
    /** 合并快照的终态；单页网络响应为 null，按平台元数据推导。 */
    val snapshotHasMore: Boolean? = null,
    /** 歌单导入保留目录信息与原始页计数，避免过滤不可用歌曲后误报完整。 */
    val playlistName: String? = null,
    val playlistCover: String? = null,
    val pageSize: Int = 30,
    val rawCount: Int = entries.size,
    /** 实际收到的原始条目累计；不能用去重/过滤后的展示数量代替。 */
    val receivedCount: Int = rawCount,
) {
    /** 列表行和播放队列使用同一个 UID，按来源顺序保留首次出现的版本。 */
    private val indexed = IndexedSongs.from(entries)
    val list: List<OnlineSong> get() = indexed

    /** 优先使用已保存的合并快照状态；未知总数时才以当前页满页推断。 */
    fun hasMore(loadedCount: Int = receivedCount): Boolean = snapshotHasMore ?: inferredSongPageHasMore(
        rawCount = rawCount,
        pageSize = pageSize,
        total = total,
        page = page,
        allPage = allPage,
        loadedCount = loadedCount,
    )

    /** 把网络结果标记为实际请求页，避免服务端漏回 page 时游标回退。 */
    internal fun forRequestedPage(requestedPage: Int): SongPage {
        val cursor = requestedPage.coerceAtLeast(1)
        return copy(
            page = cursor,
            snapshotHasMore = inferredSongPageHasMore(
                rawCount = rawCount,
                pageSize = pageSize,
                total = total,
                page = cursor,
                allPage = allPage,
                loadedCount = receivedCount,
            ),
        )
    }

    /** 生成完整合并快照：保存去重列表、总数、实际请求游标和 hasMore 终态。 */
    internal fun append(next: SongPage, requestedPage: Int): SongPage {
        val cursor = requestedPage.coerceAtLeast(1)
        val resolvedTotal = next.total.takeIf { it > 0 } ?: total
        val resolvedAllPage = next.allPage.takeIf { it > 0 } ?: allPage
        val received = receivedCount + next.rawCount
        val nextHasMore = next.snapshotHasMore ?: inferredSongPageHasMore(
            rawCount = next.rawCount,
            pageSize = next.pageSize,
            total = resolvedTotal,
            page = cursor,
            allPage = resolvedAllPage,
            loadedCount = received,
        )
        val merged = indexed.append(next.indexed)
        return SongPage(
            entries = merged,
            total = resolvedTotal,
            page = cursor,
            allPage = resolvedAllPage,
            snapshotHasMore = next.rawCount > 0 && nextHasMore && (next.list.isEmpty() || merged !== indexed),
            playlistName = next.playlistName ?: playlistName,
            playlistCover = next.playlistCover ?: playlistCover,
            pageSize = next.pageSize,
            rawCount = next.rawCount,
            receivedCount = received,
        )
    }
}

/** 首屏没有可滚动行时跨过全过滤页；未知总数的异常响应也不能无界读取。 */
internal suspend fun SongPage.loadUntilPlayable(fetch: suspend (Int) -> SongPage): SongPage {
    var snapshot = this
    var skipped = 0
    while (snapshot.list.isEmpty() && snapshot.hasMore()) {
        currentCoroutineContext().ensureActive()
        check(skipped++ < 500) { "目录连续返回不可用条目，请重试" }
        val nextPage = snapshot.page + 1
        val next = fetch(nextPage)
        currentCoroutineContext().ensureActive()
        snapshot = snapshot.append(next, nextPage)
    }
    return snapshot
}

data class OnlinePlaylist(val raw: JSONObject) {
    val id: String get() = raw.optString("id")
    val name: String get() = raw.optString("name")
    val img: String? get() = raw.optString("img").takeIf { it.isNotBlank() && it != "null" }
    val author: String get() = raw.optString("author")
    val description: String get() = raw.optString("description")
    val playCount: String get() = raw.optString("play_count")

    /** 播放量展示文案：兼容「纯数字 / 1.2万 / 123456.7万 / 1.2亿」等来源格式，统一换算成 万/亿。 */
    val playCountLabel: String get() = formatPlayCountLabel(playCount)
    val playNum: Long get() = raw.optLong("play_num")
    val total: Int get() = raw.optInt("total")
    val source: String get() = raw.optString("source")
    /** 有声专辑：听书目录统一用 book_album_ 前缀，兼容旧数据的 kind 标记。 */
    val isBookAlbum: Boolean get() = id.startsWith("book_album_") || raw.optString("kind") == "book"

    companion object {
        fun from(raw: JSONObject?): OnlinePlaylist? =
            raw?.takeIf { it.optString("id").isNotBlank() && it.optString("name").isNotBlank() }?.let(::OnlinePlaylist)
    }
}

/**
 * 播放量归一化：有些源直接返回「123456.7万」这类未换算的字符串，
 * 统一按数量级换算成 亿 / 万，无法解析时原样返回。
 */
internal fun formatPlayCountLabel(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    val locale = java.util.Locale.US
    if (text.endsWith("亿")) {
        val value = text.dropLast(1).toDoubleOrNull() ?: return text
        return if (value >= 1) "%.1f亿".format(locale, value) else text
    }
    if (text.endsWith("万")) {
        val value = text.dropLast(1).toDoubleOrNull() ?: return text
        return if (value >= 10_000) "%.1f亿".format(locale, value / 10_000) else text
    }
    val value = text.toDoubleOrNull() ?: return text
    return when {
        value >= 100_000_000 -> "%.1f亿".format(locale, value / 100_000_000)
        value >= 10_000 -> "%.1f万".format(locale, value / 10_000)
        else -> text
    }
}

data class PlaylistPage(
    val list: List<OnlinePlaylist>,
    val total: Int,
    val page: Int,
    val pageSize: Int = 0,
    val rawCount: Int = list.size,
    val hasMore: Boolean? = null,
)

data class BoardItem(
    val id: String,
    val name: String,
    val bangid: String,
    val img: String? = null,
)

data class Tag(val id: String, val name: String)

data class TagGroup(val name: String, val list: List<Tag>)

data class TagInfo(val hotTag: List<Tag>, val tags: List<TagGroup>)

data class OnlineLyric(
    val lyric: String,
    val tlyric: String = "",
    val rlyric: String = "",
    val source: String = "",
    val lxlyric: String = "",
    val song: OnlineSong? = null,
) {
    val hasLyrics: Boolean get() = lyric.isNotBlank() || lxlyric.isNotBlank()

    companion object {
        fun from(raw: JSONObject, source: String, song: OnlineSong? = null): OnlineLyric = OnlineLyric(
            lyric = raw.optString("lyric"),
            tlyric = raw.optString("tlyric"),
            rlyric = raw.optString("rlyric"),
            source = source,
            lxlyric = raw.optString("lxlyric"),
            song = song,
        )
    }
}
