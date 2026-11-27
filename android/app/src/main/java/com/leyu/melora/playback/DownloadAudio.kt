package com.leyu.melora.playback

import android.content.Context
import android.net.Uri
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import androidx.media3.extractor.FlacStreamMetadata
import com.leyu.melora.playback.local.LocalMediaIoCoordinator
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.InputStream
import org.json.JSONObject

/** 仅在下载任务IO线程检查单个候选/临时文件，不参与首播、列表或全库扫描。 */
internal data class DownloadAudio(
    val spec: AudioSpecification,
    val durationMs: Long,
    val tag: LocalTagReader.Tag?,
    val flacPcmChecksum: String? = null,
) {
    fun sameFlacPcm(other: DownloadAudio): Boolean =
        flacPcmChecksum != null && flacPcmChecksum == other.flacPcmChecksum && sameFileFormat(other)

    fun sameFileFormat(other: DownloadAudio): Boolean =
        spec.mimeType == other.spec.mimeType && spec.sampleRate == other.spec.sampleRate &&
            spec.bitDepth == other.spec.bitDepth && spec.channelCount == other.spec.channelCount &&
            durationMs == other.durationMs

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

internal fun downloadQualityMatches(
    spec: AudioSpecification?,
    requested: String,
    sourceQuality: String? = null,
): Boolean = spec?.verifiedQuality?.let {
    SourceResolver.sameQualityTier(requested, SourceResolver.effectiveQuality(it, sourceQuality))
} == true

internal fun sameDownloadedQuality(a: AudioSpecification, b: AudioSpecification): Boolean {
    val first = a.verifiedQuality ?: return false
    val second = b.verifiedQuality ?: return false
    return SourceResolver.sameQualityTier(first, second) || SourceResolver.sameQualityTier(second, first)
}

/** 校验没有 PCM 摘要的文件时，流式比较音频，避免标签变化制造重复文件。 */
internal fun sameDownloadedBytes(first: InputStream, second: InputStream, flac: Boolean): Boolean {
    val a = DataInputStream(first)
    val b = DataInputStream(second)
    try {
        if (flac && (!a.skipFlacMetadata() || !b.skipFlacMetadata())) return false

        val left = ByteArray(AUDIO_TRANSFER_BUFFER_BYTES)
        val right = ByteArray(AUDIO_TRANSFER_BUFFER_BYTES)
        var hasAudio = false
        while (true) {
            val count = a.read(left)
            if (count < 0) return hasAudio && b.read() < 0
            b.readFully(right, 0, count)
            for (index in 0 until count) {
                if (left[index] != right[index]) return false
            }
            hasAudio = true
        }
    } catch (_: EOFException) {
        return false
    }
}

private fun DataInputStream.skipFlacMetadata(): Boolean {
    if (readInt() != 0x664C6143) return false
    do {
        val header = readInt()
        val length = header and 0x00FFFFFF
        if (skipBytes(length) != length) return false
    } while (header >= 0)
    return true
}

/**
 * 母带优先时可按无损文件的实测位深与采样率比较同档规格。
 * qualityRank 由SourceResolver统一维护，避免本地预检复制或误归一化质量表。
 */
internal fun isBetterDownloadQuality(
    candidate: AudioSpecification,
    local: AudioSpecification,
    preferMaster: Boolean = false,
): Boolean? {
    val candidateRank = candidate.verifiedQuality?.let { SourceResolver.qualityRank(it) } ?: return null
    val localRank = local.verifiedQuality?.let { SourceResolver.qualityRank(it) } ?: return null
    if (candidateRank < 0 || localRank < 0) return null
    if (candidateRank != localRank) return candidateRank > localRank
    if (!preferMaster) return false
    if (candidate.isDsd) return !local.isDsd
    return candidate.isLossless && local.isLossless && local.sampleRate > 0 &&
        candidate.bitDepth >= local.bitDepth && candidate.sampleRate >= local.sampleRate &&
        (candidate.bitDepth > local.bitDepth || candidate.sampleRate > local.sampleRate)
}

private const val DOWNLOAD_UPGRADE_PREFIX_LIMIT = 128 * 1024

internal data class DownloadUpgradeProbe(
    val prefix: ByteArray,
    val spec: AudioSpecification?,
    val flac: DownloadAudio? = null,
)

/**
 * 从下载器已经打开的同一个流读取有限前缀；前缀必须原样回写，不能让预检吞掉正文。
 * 输入读取故意不包runCatching，网络/文件IOException必须回到下载生命周期。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun probeDownloadUpgrade(input: InputStream): DownloadUpgradeProbe {
    var prefix = readDownloadUpgradePrefix(input, limit = 8 * 1024)
    if (hasFlacSignature(prefix)) {
        val flac = flacDownloadAudio(prefix)
        return DownloadUpgradeProbe(prefix, flac?.spec, flac)
    }
    var spec = mp3PrefixAudioSpec(prefix) ?: mediaPrefixAudioSpec(prefix)
    if (spec?.verifiedQuality == null && prefix.size == 8 * 1024) {
        prefix = readDownloadUpgradePrefix(input, prefix)
        spec = mp3PrefixAudioSpec(prefix) ?: mediaPrefixAudioSpec(prefix)
    }
    return DownloadUpgradeProbe(prefix, spec)
}

internal fun readDownloadUpgradePrefix(input: InputStream, initial: ByteArray = byteArrayOf(), limit: Int = DOWNLOAD_UPGRADE_PREFIX_LIMIT): ByteArray {
    val buffer = ByteArray(limit)
    initial.copyInto(buffer)
    var count = initial.size
    while (count < buffer.size) {
        // FLAC只需42字节；其它容器先探测8KiB，信息不足才扩至上限，且不重复读取网络。
        val target = if (count < 42) minOf(42, limit) else limit
        val read = input.read(buffer, count, target - count)
        when {
            read < 0 -> break
            read > 0 -> count += read
            else -> {
                val one = input.read()
                if (one < 0) break
                buffer[count++] = one.toByte()
            }
        }
        if (count >= 42 && hasFlacSignature(buffer)) break
    }
    return buffer.copyOf(count)
}

private fun hasFlacSignature(prefix: ByteArray): Boolean =
    prefix.size >= 4 && prefix[0] == 102.toByte() && prefix[1] == 76.toByte() &&
        prefix[2] == 97.toByte() && prefix[3] == 67.toByte()

/** 复用Media3的CBR/Xing/VBRI识别和平均码率，不用MP3首帧码率代替VBR全曲规格。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun mp3PrefixAudioSpec(prefix: ByteArray): AudioSpecification? {
    var format: Format? = null
    val extractor = Mp3Extractor(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
    val reader = ByteArrayInputStream(prefix)
    val input = DefaultExtractorInput(DataReader(reader::read), 0, C.LENGTH_UNSET.toLong())
    extractor.init(object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(value: Format) { format = value }
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    })
    try {
        if (!extractor.sniff(input)) return null
        input.resetPeekPosition()
        val position = PositionHolder()
        while (format == null && extractor.read(input, position) == Extractor.RESULT_CONTINUE) {}
    } catch (_: java.io.IOException) {
        // 有界前缀耗尽不等于网络读取失败；真正的输入异常已在读取前缀时原样抛出。
    } finally { extractor.release() }
    return format?.takeIf { it.averageBitrate > 0 }?.let(AudioSpecification::fromFormat)
}

/** MediaExtractor只接触已读入内存的前缀，绝不会因预检再次访问网络或原始输入流。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun mediaPrefixAudioSpec(prefix: ByteArray): AudioSpecification? {
    if (prefix.isEmpty()) return null
    return runCatching {
        val source = PrefixMediaDataSource(prefix)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            extractor.downloadAudioSpec()?.let { spec ->
                // MP3只接受上面的Media3 CBR/Xing/VBRI结果，不使用平台前缀估码率。
                if (spec.mimeType == "audio/mpeg") spec.copy(bitrate = -1) else spec
            }
        } finally { extractor.release(); source.close() }
    }.getOrNull()
}

/** 前缀预检与完整文件校验使用同一份音轨字段映射，未知位深不能推断为16bit。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun MediaExtractor.downloadAudioSpec(tag: LocalTagReader.Tag? = null): AudioSpecification? {
    val track = (0 until trackCount).firstOrNull {
        getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: return null
    selectTrack(track)
    if (sampleTime < 0) return null
    val format = getTrackFormat(track)
    fun number(key: String, fallback: Int = -1): Int =
        if (format.containsKey(key)) format.getInteger(key).takeIf { it > 0 } ?: fallback else fallback
    val depth = when (number(MediaFormat.KEY_PCM_ENCODING, C.ENCODING_INVALID)) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        else -> tag?.bitDepth ?: -1
    }
    return AudioSpecification(
        mimeType = format.getString(MediaFormat.KEY_MIME),
        sampleRate = number(MediaFormat.KEY_SAMPLE_RATE, tag?.sampleRate ?: -1),
        bitrate = number(MediaFormat.KEY_BIT_RATE, tag?.bitrate ?: -1),
        bitDepth = depth,
        channelCount = number(MediaFormat.KEY_CHANNEL_COUNT),
    )
}

private class PrefixMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size < 0 || position < 0 || offset < 0 || offset > buffer.size - size) return -1
        if (size == 0) return 0
        if (position >= data.size.toLong()) return -1
        val start = position.toInt()
        val count = minOf(size, data.size - start)
        data.copyInto(buffer, offset, start, start + count)
        return count
    }

    override fun close() = Unit
}

/** 名称冲突沿用下载文件后缀，以实际音频规格命名。 */
internal fun downloadVariantName(baseName: String, extension: String, spec: AudioSpecification, ordinal: Int): String {
    if (ordinal == 0) return baseName + extension
    val label = when {
        spec.isDsd -> "MASTER"
        spec.isLossless -> when {
            spec.bitDepth >= 24 -> "HR"
            spec.bitDepth > 0 -> "SQ"
            else -> "音质待识别"
        }
        spec.bitrate >= 320_000 -> "HQ"
        spec.bitrate > 0 -> "${spec.bitrate / 1000}K"
        else -> "音质待识别"
    }
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
            // 声明了时长不代表存在音频帧；共享校验会拒绝只剩STREAMINFO的损坏FLAC。
            extractor.downloadAudioSpec(tag)
        } finally { extractor.release() }
    }.getOrNull()
    if (flac != null && extracted == null) return@withRead null
    val spec = flac?.spec?.withKnownFileFields(extracted)
        ?: extracted ?: tag?.let { AudioSpecification(it.mimeType, it.sampleRate, it.bitrate, it.bitDepth) }
    if (spec?.mimeType?.startsWith("audio/") != true) return@withRead null
    val duration = flac?.durationMs?.takeIf { it > 0 } ?: tag?.durationMs ?: 0L
    DownloadAudio(spec, duration, tag, flac?.flacPcmChecksum)
}

/** 从真实STREAMINFO读位深/采样率，兼容无法回传位深的旧系统，不从文件名或请求推断。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun flacDownloadAudio(header: ByteArray): DownloadAudio? {
    if (header.size < 42 || !header.copyOfRange(0, 4).contentEquals(byteArrayOf(102, 76, 97, 67)) ||
        header[4].toInt() and 0x7f != 0 || header[5] != 0.toByte() || header[6] != 0.toByte() || header[7] != 34.toByte()) return null
    val metadata = FlacStreamMetadata(header, 8)
    if (metadata.sampleRate <= 0 || metadata.bitsPerSample !in 4..32) return null
    val checksum = header.copyOfRange(26, 42).takeIf { bytes -> bytes.any { it != 0.toByte() } }?.toHexString()
    return DownloadAudio(
        AudioSpecification("audio/flac", metadata.sampleRate, -1, metadata.bitsPerSample, metadata.channels),
        metadata.totalSamples * 1000 / metadata.sampleRate,
        null,
        checksum,
    )
}
