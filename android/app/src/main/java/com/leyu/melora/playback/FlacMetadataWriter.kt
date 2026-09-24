package com.leyu.melora.playback

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** 下载文件的唯一元数据写入口；不同音频容器只在这里分派。 */
internal object DownloadMetadataWriter {
    fun supports(extension: String): Boolean = when (extension.lowercase(Locale.ROOT)) {
        ".mp3", ".flac" -> true
        else -> false
    }

    fun write(
        file: File,
        extension: String,
        title: String,
        artist: String,
        album: String,
        cover: ByteArray?,
        lyric: EmbeddedLyrics?,
        year: Int? = null,
    ) {
        when (extension.lowercase(Locale.ROOT)) {
            ".mp3" -> Id3Writer.write(file, title, artist, album, cover, lyric, year)
            ".flac" -> FlacMetadataWriter.write(file, title, artist, album, cover, lyric, year)
        }
    }
}

/** 标准 FLAC 元数据写入：VORBIS_COMMENT 保存文本/歌词，PICTURE 保存封面，音频帧不重编码。 */
internal object FlacMetadataWriter {
    private const val STREAM_INFO = 0
    private const val PADDING = 1
    private const val VORBIS_COMMENT = 4
    private const val PICTURE = 6
    private const val FRONT_COVER = 3
    private const val MAX_BLOCK_SIZE = 0xFF_FF_FF
    private const val MAX_TOTAL_METADATA = 32 * 1024 * 1024
    private const val MAX_COMMENT_COUNT = 10_000
    private const val MAX_COVER_SIZE = 8 * 1024 * 1024
    private val marker = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    private data class Block(val type: Int, val data: ByteArray)
    private data class Comments(val vendor: String, val entries: List<String>)

    fun write(
        file: File,
        title: String,
        artist: String,
        album: String,
        cover: ByteArray?,
        lyric: EmbeddedLyrics?,
        year: Int? = null,
    ) {
        val rewritten = File(file.parentFile, "${file.name}.tag")
        try {
            file.inputStream().buffered(AUDIO_TRANSFER_BUFFER_BYTES).use { input ->
                require(input.readExact(marker.size).contentEquals(marker)) { "不是有效的 FLAC 文件" }
                val original = readBlocks(input)
                require(original.firstOrNull()?.let { it.type == STREAM_INFO && it.data.size == 34 } == true) {
                    "FLAC STREAMINFO 缺失或损坏"
                }
                val merged = mergeBlocks(original, title, artist, album, cover, lyric, year)
                rewritten.outputStream().buffered(AUDIO_TRANSFER_BUFFER_BYTES).use { output ->
                    output.write(marker)
                    merged.forEachIndexed { index, block -> writeBlock(output, block, index == merged.lastIndex) }
                    input.copyTo(output, AUDIO_TRANSFER_BUFFER_BYTES)
                }
            }
            require(rewritten.length() > 4L) { "FLAC 标签写入结果为空" }
            replaceFile(rewritten, file)
        } finally {
            if (rewritten.exists()) rewritten.delete()
        }
    }

    private fun readBlocks(input: InputStream): List<Block> {
        val blocks = mutableListOf<Block>()
        var total = 0L
        var last: Boolean
        do {
            val header = input.readExact(4)
            last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            total += length.toLong()
            require(total <= MAX_TOTAL_METADATA.toLong()) { "FLAC 元数据过大" }
            blocks += Block(type, input.readExact(length))
        } while (!last)
        return blocks
    }

    private fun mergeBlocks(
        blocks: List<Block>,
        title: String,
        artist: String,
        album: String,
        cover: ByteArray?,
        lyric: EmbeddedLyrics?,
        year: Int?,
    ): List<Block> {
        var vendor = "Melora"
        val existingComments = mutableListOf<String>()
        blocks.filter { it.type == VORBIS_COMMENT }.forEach { block ->
            val parsed = parseComments(block.data)
            if (vendor == "Melora" && parsed.vendor.isNotBlank()) vendor = parsed.vendor
            existingComments += parsed.entries
        }
        val commentBlock = Block(
            VORBIS_COMMENT,
            buildComments(vendor, existingComments, title, artist, album, lyric, year),
        )
        val usableCover = cover?.takeIf { it.isNotEmpty() && it.size <= MAX_COVER_SIZE }
        val result = mutableListOf<Block>()
        var commentAdded = false
        blocks.forEach { block ->
            when {
                block.type == VORBIS_COMMENT -> if (!commentAdded) {
                    result += commentBlock
                    commentAdded = true
                }
                block.type == PICTURE && usableCover != null && pictureType(block.data) == FRONT_COVER -> Unit
                else -> result += block
            }
        }
        if (!commentAdded) {
            result.add(1.coerceAtMost(result.size), commentBlock)
        }
        if (usableCover != null) {
            val insertion = result.indexOfFirst { it.type == PADDING }.takeIf { it >= 0 } ?: result.size
            result.add(insertion, Block(PICTURE, buildPicture(usableCover)))
        }
        return result
    }

    private fun parseComments(data: ByteArray): Comments {
        val cursor = ByteCursor(data)
        val vendor = cursor.readUtf8(cursor.readLeSize())
        val count = cursor.readLeSize()
        require(count <= MAX_COMMENT_COUNT) { "FLAC 评论数量异常" }
        return Comments(vendor, List(count) { cursor.readUtf8(cursor.readLeSize()) })
    }

    private fun buildComments(
        vendor: String,
        existing: List<String>,
        title: String,
        artist: String,
        album: String,
        lyric: EmbeddedLyrics?,
        year: Int?,
    ): ByteArray {
        val replacedKeys = mutableSetOf<String>()
        if (title.isNotBlank()) replacedKeys += "TITLE"
        if (artist.isNotBlank()) replacedKeys += "ARTIST"
        if (album.isNotBlank()) replacedKeys += "ALBUM"
        if (lyric != null) {
            replacedKeys += setOf("LYRICS", EmbeddedLyrics.TTML_FIELD, "UNSYNCED LYRICS", "UNSYNCEDLYRICS")
        }
        if (year?.let { it in 1900..2100 } == true) replacedKeys += setOf("DATE", "YEAR")
        val entries = existing.filterNot { entry ->
            entry.substringBefore('=', "").trim().uppercase(Locale.ROOT) in replacedKeys
        }.toMutableList()
        fun add(key: String, value: String?) {
            value?.takeIf { it.isNotBlank() }?.let { entries += "$key=$it" }
        }
        add("TITLE", title)
        add("ARTIST", artist)
        add("ALBUM", album)
        add("DATE", year?.takeIf { it in 1900..2100 }?.toString())
        if (lyric != null) {
            add("LYRICS", lyric.plain)
            add(EmbeddedLyrics.TTML_FIELD, lyric.ttml)
        }

        val output = ByteArrayOutputStream()
        output.writeLeUtf8(vendor.ifBlank { "Melora" })
        output.writeLe32(entries.size)
        entries.forEach { output.writeLeUtf8(it) }
        return output.toByteArray().also { require(it.size <= MAX_BLOCK_SIZE) { "FLAC 歌词或标签过大" } }
    }

    private fun buildPicture(cover: ByteArray): ByteArray {
        val mime = when {
            cover.size >= 8 && cover.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A),
            ) -> "image/png"
            cover.size >= 12 && cover.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                cover.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
        val output = ByteArrayOutputStream(cover.size + 64)
        output.writeBe32(FRONT_COVER)
        output.writeBeUtf8(mime)
        output.writeBeUtf8("")
        repeat(4) { output.writeBe32(0) } // 宽、高、色深、调色板颜色数未知时写 0
        output.writeBe32(cover.size)
        output.write(cover)
        return output.toByteArray().also { require(it.size <= MAX_BLOCK_SIZE) { "FLAC 封面过大" } }
    }

    private fun pictureType(data: ByteArray): Int? =
        data.takeIf { it.size >= 4 }?.let { readBe32(it, 0) }

    private fun writeBlock(output: OutputStream, block: Block, last: Boolean) {
        require(block.type in 0..126 && block.data.size <= MAX_BLOCK_SIZE) { "无效的 FLAC 元数据块" }
        output.write(block.type or if (last) 0x80 else 0)
        output.write((block.data.size ushr 16) and 0xFF)
        output.write((block.data.size ushr 8) and 0xFF)
        output.write(block.data.size and 0xFF)
        output.write(block.data)
    }

    private fun replaceFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class ByteCursor(private val data: ByteArray) {
        private var position = 0

        fun readLeSize(): Int {
            require(data.size - position >= 4) { "FLAC 评论块损坏" }
            val value = (data[position].toLong() and 0xFF) or
                ((data[position + 1].toLong() and 0xFF) shl 8) or
                ((data[position + 2].toLong() and 0xFF) shl 16) or
                ((data[position + 3].toLong() and 0xFF) shl 24)
            position += 4
            require(value <= Int.MAX_VALUE && value <= data.size - position) { "FLAC 评论长度异常" }
            return value.toInt()
        }

        fun readUtf8(length: Int): String {
            require(length <= data.size - position) { "FLAC 评论内容不完整" }
            return data.copyOfRange(position, position + length).toString(Charsets.UTF_8).also { position += length }
        }
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            require(count >= 0) { "FLAC 文件意外结束" }
            if (count == 0) continue
            offset += count
        }
        return result
    }

    private fun ByteArrayOutputStream.writeLeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeLe32(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeBeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeBe32(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBe32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun readBe32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
