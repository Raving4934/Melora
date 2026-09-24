package com.leyu.melora.playback.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.leyu.melora.playback.EmbeddedLyrics
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 本地音频文件标签读取：基础元数据、内嵌封面（缓存为本地文件）与内嵌歌词。 */
internal const val LOCAL_COVER_CACHE_DIR = "local_covers"

object LocalTagReader {

    data class Tag(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val sampleRate: Int,
        val bitrate: Int,
        val year: Int,
        val bitDepth: Int = -1,
        val mimeType: String? = null,
    )

    private const val MAX_MEMORY_COVERS = 256
    private const val MAX_DISK_COVERS = 512
    private const val MAX_DISK_COVER_BYTES = 64L * 1024L * 1024L
    private val UNSAFE_ID_REGEX = Regex("[^A-Za-z0-9_.-]")
    private val coverLock = ReentrantLock()
    private val coverCache = object : LinkedHashMap<String, String>(MAX_MEMORY_COVERS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_MEMORY_COVERS
    }
    private var coverGeneration = 0L

    private data class DiskCoverEntry(
        val file: File,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    fun read(context: Context, uri: String): Tag? = runCatching {
        LocalMediaIoCoordinator.withRead(context, uri.toUri()) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri.toUri())
                Tag(
                    mimeType = retriever.string(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).ifBlank { null },
                    title = retriever.string(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.string(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.string(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = retriever.string(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L,
                    sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        retriever.string(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: 0
                    } else 0,
                    bitrate = retriever.string(MediaMetadataRetriever.METADATA_KEY_BITRATE).toIntOrNull() ?: 0,
                    year = retriever.string(MediaMetadataRetriever.METADATA_KEY_YEAR).toIntOrNull() ?: 0,
                    bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        retriever.string(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                            .toIntOrNull()
                            ?.takeIf { it > 0 }
                            ?: -1
                    } else {
                        -1
                    },
                )
            } finally {
                runCatching { retriever.release() }
            }
        }
    }.getOrNull()

    fun embeddedPicture(context: Context, uri: String): ByteArray? = runCatching {
        LocalMediaIoCoordinator.withRead(context, uri.toUri()) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri.toUri())
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 内嵌封面：解出后落到 cacheDir/local_covers，返回 file:// 地址供 Coil 渲染。 */
    fun coverUri(context: Context, song: LocalSong): String? {
        val key = coverCacheKey(song)
        val cached = File(coverDir(context), "$key.img")
        val generation = coverLock.withLock {
            coverCache[key]?.let { value ->
                if (isUsableCoverUri(value)) return value
                coverCache.remove(key)
            }
            if (cached.isFile && cached.length() > 0L) {
                return Uri.fromFile(cached).toString().also { coverCache[key] = it }
            }
            coverGeneration
        }

        // 读取媒体可能较慢，不能阻塞清理与其它缓存提交。
        val bytes = embeddedPicture(context, song.uri) ?: return null
        return commitCover(context, song, key, generation, bytes)
    }

    private fun commitCover(
        context: Context,
        song: LocalSong,
        key: String,
        generation: Long,
        bytes: ByteArray,
    ): String? = coverLock.withLock {
        if (generation != coverGeneration) return@withLock null
        writeCoverLocked(context, song, key, bytes, removePreviousVersions = false)
    }


    /** 索引封面、已缓存内嵌封面和文件内嵌封面的统一入口；失效的 file:// 不会继续占位。 */
    fun bestCoverUri(context: Context, song: LocalSong): String? =
        song.coverUri?.takeIf(::isUsableCoverUri) ?: coverUri(context, song)

    internal fun isUsableCoverUri(value: String): Boolean {
        if (value.isBlank()) return false
        val uri = value.toUri()
        return uri.scheme != "file" || uri.path?.let(::File)?.let { it.isFile && it.length() > 0L } == true
    }

    fun cacheCover(context: Context, song: LocalSong, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val key = coverCacheKey(song)
        return coverLock.withLock {
            writeCoverLocked(context, song, key, bytes, removePreviousVersions = true)
        }
    }

    private fun coverCacheKey(song: LocalSong): String = "${safeId(song.id)}-${song.modifiedAt}"

    private fun safeId(value: String): String = value.replace(UNSAFE_ID_REGEX, "_")

    fun clearCoverCache(context: Context) {
        val directory = coverDir(context)
        coverLock.withLock {
            coverGeneration++
            coverCache.clear()
            var failure: Throwable? = null
            try {
                if (directory.exists() && (!directory.deleteRecursively() || directory.exists())) {
                    throw IOException("本地封面缓存目录无法删除：${directory.absolutePath}")
                }
            } catch (error: Throwable) {
                failure = error
            }

            // 删除失败也必须清除索引里的失效 file://，否则下次仍会持久化旧地址。
            try {
                LocalMediaStore.invalidateCachedCovers(directory)
            } catch (error: Throwable) {
                failure = failure?.also { it.addSuppressed(error) } ?: error
            }
            failure?.let { throw it }
        }
    }

    private fun writeCoverLocked(
        context: Context,
        song: LocalSong,
        key: String,
        bytes: ByteArray,
        removePreviousVersions: Boolean,
    ): String? = runCatching {
        val dir = coverDir(context)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建本地封面缓存目录：${dir.absolutePath}")
        }
        if (removePreviousVersions) {
            val prefix = "${safeId(song.id)}-"
            dir.list { _, name -> name.startsWith(prefix) && name != "$key.img" }
                ?.forEach { name ->
                    val file = File(dir, name)
                    if (file.delete()) coverCache.remove(name.removeSuffix(".img"))
                }
        }
        val cached = File(dir, "$key.img")
        cached.writeBytes(bytes)
        val uri = Uri.fromFile(cached).toString()
        coverCache[key] = uri
        trimDiskCacheLocked(dir, cached)
        uri
    }.getOrNull()

    private fun trimDiskCacheLocked(dir: File, keep: File) {
        // 只枚举封面缓存目录，不扫描本地音频索引或媒体库。
        // 不传 NOFOLLOW_LINKS，保持与 File.isFile 一样默认跟随符号链接；单条属性读取失败只跳过该条目。
        val entries = dir.listFiles { _, name -> name.endsWith(".img") }
            ?.mapNotNull { file ->
                try {
                    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    if (attributes.isRegularFile) DiskCoverEntry(file, attributes.size(), attributes.lastModifiedTime().toMillis()) else null
                } catch (_: IOException) {
                    null
                }
            }
            .orEmpty()
        var totalBytes = entries.sumOf { it.sizeBytes }
        var count = entries.size
        if (count <= MAX_DISK_COVERS && totalBytes <= MAX_DISK_COVER_BYTES) return

        // sortedBy 是稳定排序，相同 mtime 继续保持 listFiles 的原有顺序。
        for (entry in entries.sortedBy { it.lastModified }) {
            if (count <= MAX_DISK_COVERS && totalBytes <= MAX_DISK_COVER_BYTES) break
            val file = entry.file
            if (file == keep) continue
            if (file.delete()) {
                count--
                totalBytes -= entry.sizeBytes
                coverCache.remove(file.name.removeSuffix(".img"))
            }
        }
    }

    private fun coverDir(context: Context): File =
        File(context.cacheDir, LOCAL_COVER_CACHE_DIR)

    private fun MediaMetadataRetriever.string(key: Int): String =
        extractMetadata(key)?.trim().orEmpty()

    /** 内嵌歌词：只读取容器元数据块，不把整首音频载入内存。 */
    fun embeddedLyrics(context: Context, uri: String, mimeType: String): EmbeddedLyrics? {
        val mime = mimeType.lowercase()
        return runCatching {
            LocalMediaIoCoordinator.withRead(context, uri.toUri()) {
                context.contentResolver.openInputStream(uri.toUri())?.buffered()?.use { input ->
                    readEmbeddedLyrics(input, mime)
                }
            }
        }.getOrNull()?.takeUnless { it.isBlank }
    }

    /** 共用生产解析链，仅供容器帧/块的 instrumented fixture 注入字节。 */
    internal fun embeddedLyricsFromBytes(bytes: ByteArray, mimeType: String): EmbeddedLyrics? = runCatching {
        ByteArrayInputStream(bytes).use { input -> readEmbeddedLyrics(input, mimeType) }
    }.getOrNull()?.takeUnless { it.isBlank }

    /** 生产与 JVM 测试共用的流入口；调用方负责关闭输入流。 */
    internal fun readEmbeddedLyrics(input: InputStream, mimeType: String): EmbeddedLyrics? {
        val stream = input as? BufferedInputStream ?: input.buffered()
        val mime = mimeType.lowercase()
        return when {
            mime.contains("mpeg") || mime.contains("mp3") -> readId3Lyrics(stream)
            mime.contains("flac") -> readFlacLyrics(stream)
            else -> detectAndReadLyrics(stream)
        }
    }

    private fun detectAndReadLyrics(input: BufferedInputStream): EmbeddedLyrics? {
        input.mark(10)
        val header = input.readExact(4) ?: return null
        input.reset()
        return when {
            header.contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())) -> readFlacLyrics(input)
            header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> readId3Lyrics(input)
            else -> null
        }
    }

    // --- MP3: ID3v2 USLT + TXXX[LYRICS_TTML] ---

    private fun readId3Lyrics(input: InputStream): EmbeddedLyrics? {
        val header = input.readExact(10) ?: return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        val body = input.readExact(tagSize) ?: return null
        return parseId3Lyrics(header + body)
    }

    private fun parseId3Lyrics(bytes: ByteArray): EmbeddedLyrics? {
        val major = bytes[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val flags = bytes[5].toInt() and 0xFF
        val end = minOf(bytes.size, 10 + synchsafe(bytes, 6))
        var offset = 10
        if (flags and 0x40 != 0) {
            if (offset + 4 > end) return null
            val declared = if (major >= 4) synchsafe(bytes, offset) else beInt(bytes, offset, 4)
            val total = if (major >= 4) declared else 4 + declared
            if (total < 4 || offset.toLong() + total > end.toLong()) return null
            offset += total
        }

        var plain = ""
        var ttml = ""
        while (offset + 10 <= end) {
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (id.isBlank() || id[0] == '\u0000') break
            val size = if (major >= 4) synchsafe(bytes, offset + 4) else beInt(bytes, offset + 4, 4)
            val body = offset + 10
            if (size < 0 || size > end - body) break
            val frameFlags = bytes[offset + 9].toInt() and 0xFF
            if (frameFlags == 0 && (id == "USLT" || id == "TXXX")) {
                val fields = decodeId3Fields(bytes, body, size, if (id == "USLT") 4 else 1)
                if (fields != null && fields.second.isNotBlank()) {
                    if (fields.first.equals(EmbeddedLyrics.TTML_FIELD, ignoreCase = true)) {
                        if (ttml.isBlank()) ttml = fields.second
                    } else if (id == "USLT" && plain.isBlank()) {
                        plain = fields.second
                    }
                }
            }
            offset = body + size
        }
        return EmbeddedLyrics(plain = plain, ttml = ttml).takeUnless { it.isBlank }
    }

    /** 返回 (description, value)；USLT 的前缀含 encoding+language，TXXX 仅含 encoding。 */
    private fun decodeId3Fields(bytes: ByteArray, start: Int, size: Int, prefixLength: Int): Pair<String, String>? {
        if (size <= prefixLength || start < 0 || size > bytes.size - start) return null
        val encoding = bytes[start].toInt() and 0xFF
        val end = start + size
        val descriptionStart = start + prefixLength
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (descriptionStart + 1 < end && bytes[descriptionStart] == 0xFF.toByte() && bytes[descriptionStart + 1] == 0xFE.toByte()) {
                Charsets.UTF_16LE
            } else {
                Charsets.UTF_16BE
            }
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val wide = encoding == 1 || encoding == 2
        var cursor = descriptionStart
        if (wide && encoding == 1 && cursor + 1 < end &&
            ((bytes[cursor] == 0xFF.toByte() && bytes[cursor + 1] == 0xFE.toByte()) ||
                (bytes[cursor] == 0xFE.toByte() && bytes[cursor + 1] == 0xFF.toByte()))
        ) cursor += 2

        val delimiter = if (wide) {
            var index = cursor
            while (index + 1 < end && !(bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte())) index += 2
            if (index + 1 >= end) return null
            index
        } else {
            while (cursor < end && bytes[cursor] != 0.toByte()) cursor++
            if (cursor >= end) return null
            cursor
        }
        val descriptionEnd = delimiter
        val valueStart = delimiter + if (wide) 2 else 1
        if (valueStart > end) return null
        val description = runCatching {
            String(bytes, descriptionStart, descriptionEnd - descriptionStart, charset).trim('\uFEFF', '\u0000')
        }.getOrNull() ?: return null
        val value = runCatching {
            String(bytes, valueStart, end - valueStart, charset).trimStart('\uFEFF').trimEnd('\u0000')
        }.getOrNull() ?: return null
        return description to value
    }

    // --- FLAC: VORBIS_COMMENT ---

    private fun readFlacLyrics(input: InputStream): EmbeddedLyrics? {
        val magic = input.readExact(4) ?: return null
        if (String(magic, Charsets.US_ASCII) != "fLaC") return null
        var plain = ""
        var ttml = ""
        var blockCount = 0
        while (blockCount++ < MAX_FLAC_BLOCKS) {
            val header = input.readExact(4) ?: break
            val first = header[0].toInt() and 0xFF
            val isLast = first and 0x80 != 0
            val type = first and 0x7F
            val length = beInt(header, 1, 3)
            if (type == 4) {
                if (length > MAX_TAG_BYTES) {
                    if (!input.skipExact(length.toLong())) break
                } else {
                    val body = input.readExact(length) ?: break
                    val found = readVorbisComment(body)
                    if (plain.isBlank() && found.plain.isNotBlank()) plain = found.plain
                    if (ttml.isBlank() && found.ttml.isNotBlank()) ttml = found.ttml
                }
            } else if (!input.skipExact(length.toLong())) {
                break
            }
            if (isLast) break
        }
        return EmbeddedLyrics(plain = plain, ttml = ttml).takeUnless { it.isBlank }
    }

    private fun readVorbisComment(bytes: ByteArray): EmbeddedLyrics {
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 4) return EmbeddedLyrics()
        val vendorLength = buffer.int
        if (vendorLength < 0 || vendorLength > buffer.remaining()) return EmbeddedLyrics()
        buffer.position(buffer.position() + vendorLength)
        if (buffer.remaining() < 4) return EmbeddedLyrics()
        val count = buffer.int
        if (count < 0) return EmbeddedLyrics()
        var plain = ""
        var ttml = ""
        repeat(minOf(count, buffer.remaining() / 4)) {
            if (buffer.remaining() < 4) return EmbeddedLyrics(plain, ttml)
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) return EmbeddedLyrics(plain, ttml)
            val text = String(bytes, buffer.position(), size, Charsets.UTF_8)
            buffer.position(buffer.position() + size)
            val key = text.substringBefore('=').trim()
            val value = text.substringAfter('=', "")
            when {
                key.equals(EmbeddedLyrics.TTML_FIELD, ignoreCase = true) && ttml.isBlank() -> ttml = value
                isPlainLyricsField(key) && plain.isBlank() -> plain = value
            }
        }
        return EmbeddedLyrics(plain, ttml)
    }

    private fun isPlainLyricsField(key: String): Boolean =
        key.equals("LYRICS", true) || key.equals("UNSYNCEDLYRICS", true) || key.equals("UNSYNCED LYRICS", true)

    private fun InputStream.readExact(size: Int): ByteArray? {
        if (size < 0) return null
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(buffer, offset, size - offset)
            if (count <= 0) return null
            offset += count
        }
        return buffer
    }

    private fun InputStream.skipExact(size: Long): Boolean {
        var remaining = size
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                return false
            }
        }
        return true
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun beInt(bytes: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (index in 0 until length) value = (value shl 8) or (bytes[offset + index].toInt() and 0xFF)
        return value
    }

    private const val MAX_TAG_BYTES = 8 * 1024 * 1024
    private const val MAX_FLAC_BLOCKS = 128

}
