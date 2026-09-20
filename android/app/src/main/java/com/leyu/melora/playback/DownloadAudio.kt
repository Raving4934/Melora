package com.leyu.melora.playback

import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.extractor.FlacStreamMetadata
import com.leyu.melora.playback.local.LocalMediaIoCoordinator
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import org.json.JSONObject

/** 仅在下载任务IO线程检查单个候选/临时文件，不参与首播、列表或全库扫描。 */
internal data class DownloadAudio(val spec: AudioSpecification, val durationMs: Long, val tag: LocalTagReader.Tag?) {
    fun matches(song: OnlineSong, trustedIdentity: Boolean): Boolean {
        val metadata = tag
        val candidate = OnlineSong(JSONObject().apply {
            put("source", "local"); put("songmid", "download-candidate")
            put("name", metadata?.title?.ifBlank { null } ?: if (trustedIdentity) song.name else "")
            put("singer", metadata?.artist?.ifBlank { null } ?: if (trustedIdentity) song.singer else "")
            put("albumName", metadata?.album?.ifBlank { null } ?: if (trustedIdentity) song.albumName else "")
            put("interval", "${durationMs / 60_000}:${durationMs / 1000 % 60}")
        })
        if (song.isBookChapter) return trustedIdentity
        return SourceResolver.alternativeScore(song, candidate) != null ||
            (trustedIdentity && metadata?.title.isNullOrBlank() && metadata?.artist.isNullOrBlank() &&
                (song.intervalSeconds <= 0 || kotlin.math.abs(durationMs - song.intervalSeconds * 1000L) <= 5000))
    }
}

internal fun downloadQualityMatches(spec: AudioSpecification?, requested: String): Boolean =
    spec?.verifiedQuality?.let { SourceResolver.sameQualityTier(requested, it) } == true

internal fun sameDownloadedQuality(a: AudioSpecification, b: AudioSpecification): Boolean =
    a.verifiedQuality != null && b.verifiedQuality != null &&
        SourceResolver.sameQualityTier(a.verifiedQuality!!, b.verifiedQuality!!)

/** 后缀只在名称冲突时使用；以实际规格命名，不能拿请求HR给16bit文件贴标。 */
internal fun downloadVariantName(baseName: String, extension: String, spec: AudioSpecification, ordinal: Int): String {
    if (ordinal == 0) return baseName + extension
    val label = spec.qualityBadge ?: "音质待识别"
    val serial = if (ordinal == 1) "" else " ($ordinal)"
    return "${baseName.take(70)} [$label]$serial$extension"
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun inspectDownloadAudio(context: Context, uri: Uri): DownloadAudio? = LocalMediaIoCoordinator.withRead(context, uri) {
    val tag = LocalTagReader.read(context, uri.toString())
    val flac = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(42)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            flacDownloadAudio(header.copyOf(count))
        }
    }.getOrNull()
    // M4A容器名不能证明AAC/ALAC；MP3也不能仅凭文件体积估码率，读取实际音频轨格式。
    val extracted = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@runCatching null
            extractor.selectTrack(track)
            // 声明了完整时长的头不代表存在音频帧；不能跳过只剩STREAMINFO的损坏FLAC。
            if (extractor.sampleTime < 0) return@runCatching null
            val format = extractor.getTrackFormat(track)
            fun number(key: String, fallback: Int) = if (format.containsKey(key)) format.getInteger(key) else fallback
            val depth = when (number(MediaFormat.KEY_PCM_ENCODING, C.ENCODING_INVALID)) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
                else -> tag?.bitDepth ?: -1
            }
            AudioSpecification(format.getString(MediaFormat.KEY_MIME), number(MediaFormat.KEY_SAMPLE_RATE, tag?.sampleRate ?: -1),
                number(MediaFormat.KEY_BIT_RATE, tag?.bitrate ?: -1), depth)
        } finally { extractor.release() }
    }.getOrNull()
    if (flac != null && extracted == null) return@withRead null
    val spec = flac?.spec ?: extracted ?: tag?.let { AudioSpecification(it.mimeType, it.sampleRate, it.bitrate, it.bitDepth) }
    if (spec?.mimeType?.startsWith("audio/") != true) return@withRead null
    val duration = flac?.durationMs?.takeIf { it > 0 } ?: tag?.durationMs ?: 0L
    DownloadAudio(spec, duration, tag)
}

/** 从真实STREAMINFO读位深/采样率，兼容无法回传位深的旧系统，不从文件名或请求推断。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun flacDownloadAudio(header: ByteArray): DownloadAudio? {
    if (header.size < 42 || !header.copyOfRange(0, 4).contentEquals(byteArrayOf(102, 76, 97, 67)) ||
        header[4].toInt() and 0x7f != 0 || header[5] != 0.toByte() || header[6] != 0.toByte() || header[7] != 34.toByte()) return null
    val metadata = FlacStreamMetadata(header, 8)
    if (metadata.sampleRate <= 0 || metadata.bitsPerSample !in 4..32) return null
    return DownloadAudio(AudioSpecification("audio/flac", metadata.sampleRate, -1, metadata.bitsPerSample),
        metadata.totalSamples * 1000 / metadata.sampleRate, null)
}
