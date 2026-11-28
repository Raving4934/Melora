package com.leyu.melora.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.util.Locale
import org.json.JSONObject

enum class PlayMode(val storageValue: String, @get:Player.RepeatMode internal val repeat: Int, internal val shuffled: Boolean) {
    List("list", Player.REPEAT_MODE_ALL, false),
    Single("single", Player.REPEAT_MODE_ONE, false),
    Shuffle("shuffle", Player.REPEAT_MODE_ALL, true);

    internal fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        internal fun restore(value: String?, fallback: PlayMode = List): PlayMode =
            entries.firstOrNull { it.storageValue == value } ?: fallback
    }
}

/** 初始化、切换及备份恢复共用模式映射，不触碰倍速、进度或队列。 */
internal fun Player.applyPlayMode(mode: PlayMode) {
    repeatMode = mode.repeat
    shuffleModeEnabled = mode.shuffled
}

/** 从播放器导出的时间线读取当前引擎顺序；固定关闭重复，避免单曲循环造成遍历不终止。 */
internal fun Player.playbackQueueOrder(): List<Int> {
    val timeline = currentTimeline
    val windowCount = timeline.windowCount
    if (windowCount == 0) return emptyList()

    val order = ArrayList<Int>(windowCount)
    var index = timeline.getFirstWindowIndex(shuffleModeEnabled)
    while (index != C.INDEX_UNSET && order.size < windowCount) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    return order
}

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
    /** 展示播放顺序对应的原始 mediaItem 索引；queue 本身始终按原始顺序保存。 */
    val queueOrder: List<Int> = queue.indices.toList(),
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
    // 当前解析确实命中完整音频缓存；边播边写入缓存不算命中。
    val fromCompleteCache: Boolean = false,
    // 实际选中输入音轨的规格，用于展示徽标和校验缓存音质，不取请求档位。
    val audioSpec: AudioSpecification? = null,
    // 当前播放队列的来源标识（如 board.kw.93 / playlist.kw.xxx），供卡片播放按钮跟随状态
    val queueId: String? = null,
    val pendingQueueId: String? = null,
    /** 连续采样校正时的显示下界；实际positionMs和保存断点始终保留播放器原值。 */
    val lyricPositionFloorMs: Long = 0,
)

/** 全屏歌词外观；由设置中心持久化，mini与桌面悬浮歌词保持各自字号。 */
data class LyricsUiConfig(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val isCentered: Boolean = false,
    val isBold: Boolean = false,
    val isBlurEnabled: Boolean = false,
    val isWordLiftEnabled: Boolean = true,
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
        .put("isWordLiftEnabled", isWordLiftEnabled)

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 22f
        const val FONT_SIZE_MIN_SP = 16f
        const val FONT_SIZE_MAX_SP = 30f

        internal fun fromJson(value: JSONObject): LyricsUiConfig = LyricsUiConfig(
            fontSizeSp = value.optDouble("fontSizeSp", DEFAULT_FONT_SIZE_SP.toDouble()).toFloat(),
            isCentered = value.optBoolean("isCentered", false),
            isBold = value.optBoolean("isBold", false),
            isBlurEnabled = value.optBoolean("isBlurEnabled", false),
            isWordLiftEnabled = value.optBoolean("isWordLiftEnabled", true),
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
data class AudioSpecification(
    val mimeType: String?,
    val sampleRate: Int,
    val bitrate: Int,
    val bitDepth: Int = -1,
    val channelCount: Int = -1,
) {
    private val normalizedMimeType: String? get() = mimeType?.trim()?.lowercase(Locale.ROOT)

    /** 只有真实 DSD 容器才是母带级；高采样率 PCM/FLAC 仍按位深归入 HR/SQ。 */
    val isDsd: Boolean get() = normalizedMimeType in DSD_MIME_TYPES

    val isLossless: Boolean get() = normalizedMimeType in LOSSLESS_MIME_TYPES

    /** 未知位深的无损文件不能冒充16bit；有足够证据时才纠正缓存的音质身份。 */
    val verifiedQuality: String? get() = when {
        isDsd -> "master"
        isLossless -> if (bitDepth >= 24) "flac24bit" else if (bitDepth > 0) "flac" else null
        normalizedMimeType == "audio/mpeg" -> bitrate.takeIf { it > 0 }?.let { "${(it.toLong() + 500) / 1000}k" }
        normalizedMimeType == "audio/mp4a-latm" -> bitrate.takeIf { it > 0 }?.let { "aac${it / 1000}k" }
        normalizedMimeType == "audio/vorbis" -> bitrate.takeIf { it > 0 }?.let { "ogg${it / 1000}k" }
        else -> null
    }

    /** 即使流没有平均码率，已识别的有损编码也足以否决无损声明。 */
    internal fun verifiedStreamQuality(claimedQuality: String?): String? = verifiedQuality
        ?: if (claimedQuality != null && SourceResolver.qualityRank(claimedQuality) >= SourceResolver.qualityRank("flac")) when (codecLabel) {
            "MP3" -> "mp3"
            "AAC" -> "aac"
            "Vorbis" -> "ogg"
            "Opus" -> "opus"
            else -> null
        } else null

    val codecLabel: String? get() = when (normalizedMimeType) {
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/ape", "audio/x-ape" -> "APE"
        "audio/raw" -> "PCM"
        "audio/wav", "audio/x-wav" -> "WAV"
        in DSD_MIME_TYPES -> "DSD"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l1" -> "MP1"
        "audio/mpeg-l2" -> "MP2"
        "audio/mp4a-latm", "audio/aac", "audio/aac-adts" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "AC-3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
        "audio/ac4" -> "AC-4"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/true-hd" -> "TrueHD"
        "audio/iamf" -> "IAMF"
        "audio/mha1", "audio/mhm1" -> "MPEG-H"
        "audio/amr-wb" -> "AMR-WB"
        "audio/3gpp", "audio/amr" -> "AMR"
        else -> null
    }

    internal val displayBitrateKbps: Int? get() =
        bitrate.takeIf { it >= 1_000 }?.let { ((it + 500L) / 1_000).toInt() }

    val sampleRateLabel: String? get() = if (sampleRate <= 0) null else {
        val mhz = isDsd && sampleRate >= 1_000_000
        val rate = java.math.BigDecimal.valueOf(sampleRate.toLong(), if (mhz) 6 else 3).stripTrailingZeros()
        "${rate.toPlainString()} ${if (mhz) "MHz" else "kHz"}"
    }

    val qualityBadge: AudioQualityBadge? get() = when {
        isDsd -> {
            val multiplier = (sampleRate / 44_100).takeIf {
                sampleRate % 44_100 == 0 && it in DSD_MULTIPLIERS
            }
            AudioQualityBadge(AudioQualityTier.Dsd, shortSpec = multiplier?.toString() ?: sampleRateLabel)
        }
        isLossless && bitDepth >= 24 ->
            AudioQualityBadge(AudioQualityTier.HiRes, shortSpec = sampleRateLabel ?: "${bitDepth}-bit")
        isLossless && bitDepth > 0 -> AudioQualityBadge(
            AudioQualityTier.Lossless,
            shortSpec = sampleRateLabel?.removeSuffix(" kHz")?.let { "$bitDepth/$it" } ?: "${bitDepth}-bit",
            splitLabel = codecLabel ?: AudioQualityTier.Lossless.label,
        )
        isLossless -> null
        normalizedMimeType == "audio/eac3-joc" -> AudioQualityBadge(AudioQualityTier.Atmos, shortSpec = "DOLBY")
        else -> compressedAudioBadge(displayBitrateKbps ?: 0, codecLabel, channelCount)
    }

    /** 只用同一文件的已知字段补齐所选轨道。规格冲突时可能是另一条音轨。 */
    internal fun withKnownFileFields(file: AudioSpecification?): AudioSpecification {
        if (file == null || codecLabel == null || codecLabel != file.codecLabel ||
            (normalizedMimeType != file.normalizedMimeType && codecLabel == "E-AC-3")) return this
        fun conflicts(a: Int, b: Int) = a > 0 && b > 0 && a != b
        if (conflicts(sampleRate, file.sampleRate) || conflicts(bitDepth, file.bitDepth) ||
            conflicts(channelCount, file.channelCount) || conflicts(bitrate, file.bitrate)) return this
        return copy(
            sampleRate = sampleRate.takeIf { it > 0 } ?: file.sampleRate,
            bitrate = bitrate.takeIf { it > 0 } ?: file.bitrate,
            bitDepth = bitDepth.takeIf { it > 0 } ?: file.bitDepth,
            channelCount = channelCount.takeIf { it > 0 } ?: file.channelCount,
        )
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
            return AudioSpecification(format.sampleMimeType, format.sampleRate, format.averageBitrate, bitDepth, format.channelCount)
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
            channelCount: Int = -1,
        ): AudioSpecification {
            val mime = mimeType?.trim()?.lowercase(Locale.ROOT)
            val declared = AudioSpecification(mime, sampleRate, bitrate, bitDepth, channelCount)
            if (declared.codecLabel != null) return declared
            val extension = uri.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)
            return FILE_AUDIO_MIME_TYPES[extension]?.let { declared.copy(mimeType = it) } ?: declared
        }

        private val DSD_MIME_TYPES = setOf("audio/dsd", "audio/x-dsd", "audio/vnd.dsd", "audio/dsf", "audio/dff")
        private val DSD_MULTIPLIERS = setOf(64, 128, 256, 512, 1024)
        private val FILE_AUDIO_MIME_TYPES = mapOf(
            "dsd" to "audio/dsd", "dsf" to "audio/dsd", "dff" to "audio/dsd",
            "flac" to "audio/flac", "alac" to "audio/alac", "ape" to "audio/ape",
            "wav" to "audio/wav", "wave" to "audio/wav", "mp3" to "audio/mpeg",
            "aac" to "audio/aac", "opus" to "audio/opus",
        )
        private val LOSSLESS_MIME_TYPES = setOf(
            "audio/flac", "audio/x-flac", "audio/alac", "audio/ape", "audio/x-ape", "audio/raw", "audio/wav", "audio/x-wav",
            *DSD_MIME_TYPES.toTypedArray(),
        )
    }
}
