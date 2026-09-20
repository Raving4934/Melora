package com.leyu.melora.playback.local

import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.sdk.OnlineSong
import java.util.Locale
import org.json.JSONObject

/** 本地媒体歌曲：索引自 MediaStore 或自定义文件夹，播放时由本地文件直接接管。 */
data class LocalSong(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val sampleRate: Int,
    val bitrate: Int,
    val modifiedAt: Long,
    val addedAt: Long,
    val year: Int = 0,
    val folder: String,
    val coverUri: String? = null,
    val infoFilled: Boolean = false,
    /** MediaMetadataRetriever/Media3 未识别时为 -1；旧索引读取同样默认为未知。 */
    val bitDepth: Int = -1,
) {
    /** 本地索引与播放页共用同一实际音频规格映射。 */
    val audioSpecification: AudioSpecification
        get() = AudioSpecification.fromLocal(mimeType, uri, sampleRate, bitrate, bitDepth)

    val isLossless: Boolean
        get() = audioSpecification.isLossless

    /** 列表徽标：MASTER / HR / SQ / HQ / 实际压缩码率。 */
    val qualityBadge: String?
        get() = audioSpecification.qualityBadge

    /** 只有真实 DSD 文件是母带级；高采样率本身不能推出 MASTER。 */
    val isMaster: Boolean
        get() = audioSpecification.isDsd

    /** 本地资源通知播放注册表时使用的实际质量标识，不用目录能力猜测未知位深。 */
    val playbackQuality: String
        get() = audioSpecification.verifiedQuality ?: if (isLossless) "flac" else "local"

    fun matchKey(): String = matchKeyOf(title, artist)

    /** 本地搜索：标题/歌手/专辑/文件名包含即命中（忽略大小写）。 */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return title.lowercase(Locale.ROOT).contains(q) ||
            artist.lowercase(Locale.ROOT).contains(q) ||
            album.lowercase(Locale.ROOT).contains(q) ||
            uri.lowercase(Locale.ROOT).substringAfterLast('/').contains(q)
    }

    /** 映射为在线歌曲壳（source=local）：收藏、入队、队列持久化统一走既有链路。 */
    fun toOnlineSong(): OnlineSong = OnlineSong(JSONObject().apply {
        put("source", SOURCE)
        put("songmid", id)
        put("name", title)
        put("singer", artist)
        put("albumName", album)
        put("interval", formatDuration(durationMs / 1000))
        coverUri?.let { put("img", it) }
    })

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("mimeType", mimeType)
        put("sampleRate", sampleRate)
        put("bitrate", bitrate)
        put("modifiedAt", modifiedAt)
        put("addedAt", addedAt)
        put("year", year)
        put("folder", folder)
        put("bitDepth", bitDepth)
        coverUri?.let { put("coverUri", it) }
        if (infoFilled) put("infoFilled", true)
    }

    companion object {
        const val SOURCE = "local"
        fun fromJson(json: JSONObject): LocalSong? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val uri = json.optString("uri").takeIf { it.isNotBlank() } ?: return null
            return LocalSong(
                id = id,
                uri = uri,
                title = json.optString("title").ifBlank { "未知歌曲" },
                artist = json.optString("artist"),
                album = json.optString("album"),
                durationMs = json.optLong("durationMs"),
                sizeBytes = json.optLong("sizeBytes"),
                mimeType = json.optString("mimeType"),
                sampleRate = json.optInt("sampleRate"),
                bitrate = json.optInt("bitrate"),
                modifiedAt = json.optLong("modifiedAt"),
                addedAt = json.optLong("addedAt"),
                year = json.optInt("year"),
                folder = json.optString("folder"),
                coverUri = json.optString("coverUri").takeIf { it.isNotBlank() },
                infoFilled = json.optBoolean("infoFilled"),
                bitDepth = json.optInt("bitDepth", -1).takeIf { it > 0 } ?: -1,
            )
        }

        /** 网络歌曲与本地文件的匹配键：忽略大小写、空白、标点与常见装饰后缀。 */
        fun matchKeyOf(title: String, artist: String): String =
            titleKeyOf(title) + "|" + artistKeyOf(artist)

        fun titleKeyOf(title: String): String = normalize(title)

        fun artistKeyOf(artist: String): String = normalize(artist)

        private fun normalize(raw: String): String =
            raw.lowercase(Locale.ROOT)
                .replace(Regex("(?i)\\((feat|ft|live|remix|伴奏|和声)[^)]*\\)"), "")
                .replace(Regex("[\\s·・、,，.。!！?？'\"“”‘’\\-—_()（）\\[\\]【】《》<>]+"), "")

        fun formatDuration(seconds: Long): String {
            val safe = seconds.coerceAtLeast(0)
            return "%02d:%02d".format(safe / 60, safe % 60)
        }
    }
}
