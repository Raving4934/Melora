package com.leyu.melora.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import com.leyu.melora.playback.sdk.OnlineSong
import java.util.Locale
import org.json.JSONObject

enum class PlayMode { List, Single, Shuffle }

/** 播放层统一曲目模型：在线与本地索引歌曲都可携带归一化 raw；raw=null 仅用于外部直链兜底。 */
data class UiTrack(
    val uid: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String = "",
    val artwork: String? = null,
    val raw: JSONObject? = null,
) {
    val isOnline: Boolean get() = raw != null

    companion object {
        fun fromOnline(song: OnlineSong): UiTrack = UiTrack(
            uid = song.uid,
            title = song.name,
            artist = song.singer,
            album = song.albumName,
            source = song.source,
            artwork = song.img,
            raw = song.raw,
        )

        fun fromRaw(raw: JSONObject?): UiTrack? {
            val song = OnlineSong.from(raw) ?: return null
            return fromOnline(song)
        }
    }
}

/** Media3/Coil 可直接读取的封面地址；本地 file/content 与网络封面走同一元数据链路。 */
internal fun playableArtworkUri(value: String?, enabled: Boolean = true): String? = value
    ?.takeIf { enabled }
    ?.takeIf { artwork -> artwork.substringBefore(':', missingDelimiterValue = "").lowercase() in ARTWORK_SCHEMES }

private val ARTWORK_SCHEMES = setOf("http", "https", "file", "content")

data class PlayerUiState(
    val ready: Boolean = false,
    val current: UiTrack? = null,
    val queue: List<UiTrack> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val positionSampleRealtimeMs: Long = 0,
    val positionAdvancing: Boolean = false,
    val durationMs: Long = 0,
    val mode: PlayMode = PlayMode.List,
    val speed: Float = 1.0f,
    val resolving: Boolean = false,
    val message: String? = null,
    val quality: String? = null,
    val resolvedPlatform: String? = null,
    // 实际播放来源（resourceId）：lx:<scriptId>:<hash>（脚本）或内置
    val resolvedBy: String? = null,
    // 实际选中输入音轨的规格，用于展示徽标和校验缓存音质，不取请求档位。
    val audioSpec: AudioSpecification? = null,
    // 当前播放队列的来源标识（如 board.kw.93 / playlist.kw.xxx），供卡片播放按钮跟随状态
    val queueId: String? = null,
    val pendingQueueId: String? = null,
)

/** 全屏歌词外观；由设置中心持久化，mini与桌面悬浮歌词保持各自字号。 */
data class LyricsUiConfig(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val isCentered: Boolean = false,
    val isBold: Boolean = false,
    val isBlurEnabled: Boolean = false,
) {
    fun resizeBy(steps: Int): LyricsUiConfig =
        copy(fontSizeSp = (fontSizeSp + steps * 2f).coerceIn(FONT_SIZE_MIN_SP, FONT_SIZE_MAX_SP))

    fun resetFontSize(): LyricsUiConfig = copy(fontSizeSp = DEFAULT_FONT_SIZE_SP)

    internal fun normalized(): LyricsUiConfig = copy(
        fontSizeSp = if (fontSizeSp.isFinite()) fontSizeSp.coerceIn(FONT_SIZE_MIN_SP, FONT_SIZE_MAX_SP) else DEFAULT_FONT_SIZE_SP,
    )

    internal fun toJson(): JSONObject = JSONObject()
        .put("fontSizeSp", fontSizeSp).put("isCentered", isCentered)
        .put("isBold", isBold).put("isBlurEnabled", isBlurEnabled)

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 22f
        const val FONT_SIZE_MIN_SP = 16f
        const val FONT_SIZE_MAX_SP = 30f

        internal fun fromJson(value: JSONObject): LyricsUiConfig = LyricsUiConfig(
            fontSizeSp = value.optDouble("fontSizeSp", DEFAULT_FONT_SIZE_SP.toDouble()).toFloat(),
            isCentered = value.optBoolean("isCentered", false),
            isBold = value.optBoolean("isBold", false),
            isBlurEnabled = value.optBoolean("isBlurEnabled", false),
        ).normalized()
    }
}

/** 横向保存对齐锚点、纵向保存窗口顶部相对屏幕的坐标（可为负）；空轴使用对齐默认位置。 */
internal data class DesktopLyricPosition(
    val xFraction: Float? = null,
    val yFraction: Float? = null,
) {
    internal fun normalized() = copy(
        xFraction = xFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        yFraction = yFraction?.takeIf { it.isFinite() }?.coerceIn(-1f, 1f),
    )

    internal fun toJson(): JSONObject {
        val position = normalized()
        return JSONObject().apply {
            position.xFraction?.let { put("xFraction", it) }
            position.yFraction?.let { put("yFraction", it) }
        }
    }

    companion object {
        internal fun fromJson(value: JSONObject): DesktopLyricPosition {
            fun fraction(key: String): Float? {
                val number = (value.opt(key) as? Number)?.toDouble() ?: return null
                return number.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0)?.toFloat()
            }
            return DesktopLyricPosition(fraction("xFraction"), fraction("yFraction")).normalized()
        }
    }
}

/** 所有格式共用一份时序模型；words 为空意味着只有行级时间，不能假定逐词进度。 */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

enum class LyricAlignment { Start, End }

data class LyricLine(
    val startMs: Long,
    val text: String,
    val translation: String? = null,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
    val romanization: String? = null,
    val alignment: LyricAlignment = LyricAlignment.Start,
    val isBackground: Boolean = false,
)

data class PlayerLyric(
    val uid: String,
    val title: String,
    val artist: String,
    val lines: List<LyricLine>,
    val source: String,
)

/** 来自Media3选中输入音轨，而不是用户设置、目录能力或音源请求参数；bitrate是平均码率，未知为-1。 */
data class AudioSpecification(val mimeType: String?, val sampleRate: Int, val bitrate: Int, val bitDepth: Int = -1) {
    private val normalizedMimeType: String? get() = mimeType?.trim()?.lowercase(Locale.ROOT)

    /** 只有真实 DSD 容器才是母带级；高采样率 PCM/FLAC 仍按位深归入 HR/SQ。 */
    val isDsd: Boolean get() = normalizedMimeType in DSD_MIME_TYPES

    val isLossless: Boolean get() = normalizedMimeType in LOSSLESS_MIME_TYPES

    /** 未知位深的无损文件不能冒充16bit；有足够证据时才纠正缓存的音质身份。 */
    val verifiedQuality: String? get() = when {
        isDsd -> "master"
        isLossless -> if (bitDepth >= 24) "flac24bit" else if (bitDepth > 0) "flac" else null
        normalizedMimeType == "audio/mpeg" -> bitrate.takeIf { it > 0 }?.let { "${it / 1000}k" }
        normalizedMimeType == "audio/mp4a-latm" -> bitrate.takeIf { it > 0 }?.let { "aac${it / 1000}k" }
        normalizedMimeType == "audio/vorbis" -> bitrate.takeIf { it > 0 }?.let { "ogg${it / 1000}k" }
        else -> null
    }

    val qualityBadge: String? get() = when {
        isDsd -> "MASTER"
        isLossless -> when {
            bitDepth >= 24 -> "HR"
            bitDepth > 0 -> "SQ"
            else -> null // 未识别位深不冒充SQ，也不用请求HR替代实测。
        }
        bitrate >= 320_000 -> "HQ"
        bitrate > 0 -> "${bitrate / 1000}K"
        else -> null
    }

    companion object {
        /** 将当前选中音轨的Media3格式映射为实测规格；不使用Format.bitrate的峰值优先语义。 */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun fromFormat(format: Format): AudioSpecification {
            val bitDepth = when (format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
                else -> -1
            }
            return AudioSpecification(format.sampleMimeType, format.sampleRate, format.averageBitrate, bitDepth)
        }

        /**
         * 将本地索引的容器/扩展名映射为与播放页相同的实测规格模型。
         * 扩展名只用于识别本地索引中 MediaStore 未提供的容器，不用于猜测采样率或位深。
         */
        fun fromLocal(
            mimeType: String?,
            uri: String,
            sampleRate: Int,
            bitrate: Int,
            bitDepth: Int = -1,
        ): AudioSpecification {
            val mime = mimeType?.trim()?.lowercase(Locale.ROOT)
            val extension = uri
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
            val normalized = when {
                mime in DSD_MIME_TYPES || extension in DSD_EXTENSIONS -> "audio/dsd"
                mime in LOSSLESS_MIME_TYPES -> mime
                extension == "flac" -> "audio/flac"
                extension == "ape" -> "audio/ape"
                extension == "wav" || extension == "wave" -> "audio/wav"
                extension == "mp3" -> "audio/mpeg"
                else -> mime
            }
            return AudioSpecification(normalized, sampleRate, bitrate, bitDepth)
        }

        private val DSD_MIME_TYPES = setOf("audio/dsd", "audio/x-dsd", "audio/vnd.dsd", "audio/dsf", "audio/dff")
        private val DSD_EXTENSIONS = setOf("dsd", "dsf", "dff")
        private val LOSSLESS_MIME_TYPES = setOf(
            "audio/flac", "audio/x-flac", "audio/alac", "audio/ape", "audio/x-ape", "audio/raw", "audio/wav", "audio/x-wav",
            *DSD_MIME_TYPES.toTypedArray(),
        )
    }
}
