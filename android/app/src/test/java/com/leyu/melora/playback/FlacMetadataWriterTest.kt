package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineLyric
import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FlacMetadataWriterTest {
    private val audioFrames = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 1, 2, 3, 4, 5)
    private val pngCover = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4,
    )

    @Test
    fun downloadLyricsRequireExactAudioSongIdentity() {
        val audioSong = onlineSong("kw", "audio")
        val otherSong = onlineSong("kg", "other")
        val lyric = OnlineLyric(
            lyric = "[00:01.00]<+0,500>Hel<+500,500>lo",
            tlyric = "[00:01.00]你好",
            rlyric = "[00:01.00]ni hao",
            lxlyric = "[00:01.00]<+0,500>Hel<+500,500>lo",
            song = audioSong,
        )

        val exact = embeddedLyricsForDownload(lyric, audioSong.uid)!!
        val exactLine = exact.parse().single()
        assertFalse(exact.ttml.isBlank())
        assertEquals("Hello", exactLine.text)
        assertEquals("你好", exactLine.translation)
        assertEquals("ni hao", exactLine.romanization)
        assertEquals(2, exactLine.words.size)

        assertNull(embeddedLyricsForDownload(lyric.copy(song = otherSong), audioSong.uid))
        assertNull(embeddedLyricsForDownload(lyric.copy(song = null), audioSong.uid))
    }

    @Test
    fun ordinaryDownloadLyricsAlsoRequireExactIdentityAndKeepCompatibilityText() {
        val audioSong = onlineSong("kw", "audio")
        val lyric = OnlineLyric(lyric = "[00:01.00]Hello", song = audioSong)
        val exact = embeddedLyricsForDownload(lyric, audioSong.uid)!!
        assertEquals("Hello", exact.parse().single().text)
        assertTrue(exact.ttml.isBlank())
        assertNull(embeddedLyricsForDownload(lyric.copy(song = onlineSong("kg", "other")), audioSong.uid))
        assertNull(embeddedLyricsForDownload(lyric.copy(song = null), audioSong.uid))
        assertNull(embeddedLyricsForDownload(lyric, "legacy"))
    }

    private fun onlineSong(source: String, songmid: String) = OnlineSong(
        JSONObject().put("source", source).put("songmid", songmid).put("name", "歌名").put("singer", "歌手"),
    )

    @Test
    fun id3WriterPreservesAudioBytesWhenInputHasNoOldTag() = withMp3(
        audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7F),
    ) { file ->
        val original = file.readBytes()
        Id3Writer.write(file, "标题", "歌手", "专辑", null, null)
        assertArrayEquals(original, id3Audio(file.readBytes()))
    }

    @Test
    fun id3WriterRemovesOldTagWithoutDroppingAudioHeader() = withMp3(
        audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
        oldTagBody = byteArrayOf(0x41, 0x42, 0x43, 0x44),
    ) { file ->
        val originalAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        Id3Writer.write(file, "标题", "歌手", "专辑", null, null)
        assertArrayEquals(originalAudio, id3Audio(file.readBytes()))
    }

    @Test
    fun id3WriterPreservesUnmanagedFramesAndReplacesManagedFields() {
        val genre = id3Frame("TCON", byteArrayOf(0) + "Rock".toByteArray(Charsets.ISO_8859_1))
        val track = id3Frame("TRCK", byteArrayOf(0) + "7/12".toByteArray(Charsets.ISO_8859_1))
        val oldTitle = id3Frame("TIT2", byteArrayOf(0) + "旧标题".toByteArray(Charsets.ISO_8859_1))
        withMp3(
            audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3, 4),
            oldTagBody = genre + track + oldTitle,
        ) { file ->
            val originalAudio = id3Audio(file.readBytes())
            Id3Writer.write(file, "新标题", "歌手", "专辑", null, null, year = 2024)

            val written = file.readBytes()
            val tagBody = id3Body(written)
            assertEquals(1, id3FrameIds(tagBody).count { it == "TIT2" })
            assertTrue(id3FrameIds(tagBody).containsAll(listOf("TCON", "TRCK", "TYER")))
            assertTrue(tagBody.toString(Charsets.ISO_8859_1).contains("Rock"))
            assertTrue(tagBody.toString(Charsets.ISO_8859_1).contains("7/12"))
            assertArrayEquals(originalAudio, id3Audio(written))
        }
    }

    @Test
    fun id3WriterKeepsOptionalAndBlankFieldsWhenNoReplacementWasRequested() {
        val oldArtist = id3Frame("TPE1", byteArrayOf(0) + "Original Artist".toByteArray(Charsets.ISO_8859_1))
        val oldAlbum = id3Frame("TALB", byteArrayOf(0) + "Original Album".toByteArray(Charsets.ISO_8859_1))
        val oldYear = id3Frame("TYER", byteArrayOf(0) + "2020".toByteArray(Charsets.ISO_8859_1))
        val oldCoverPayload = byteArrayOf(0, 1, 2, 3)
        val oldCover = id3Frame("APIC", oldCoverPayload)
        val oldLyricPayload = byteArrayOf(0, 4, 5, 6)
        val oldLyric = id3Frame("USLT", oldLyricPayload)
        val oldRichPayload = txxxPayload("LYRICS_TTML", "<ttml>原时间轴</ttml>")
        val oldRich = id3Frame("TXXX", oldRichPayload)
        val customTxxxPayload = txxxPayload("CUSTOM", "保留值")
        val customTxxx = id3Frame("TXXX", customTxxxPayload)
        withMp3(
            audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 7, 8, 9),
            oldTagBody = oldArtist + oldAlbum + oldYear + oldCover + oldLyric + oldRich + customTxxx,
        ) { file ->
            val originalAudio = id3Audio(file.readBytes())
            Id3Writer.write(file, "新标题", "", "", null, null, year = null)

            val written = file.readBytes()
            val ids = id3FrameIds(id3Body(written))
            assertTrue(ids.containsAll(listOf("TIT2", "TPE1", "TALB", "TYER", "APIC", "USLT", "TXXX")))
            val frames = id3FramePayloads(id3Body(written))
            assertEquals(listOf("CUSTOM", "LYRICS_TTML"), frames.filter { it.first == "TXXX" }.map { txxxDescription(it.second) }.sorted())
            assertArrayEquals(oldLyricPayload, frames.single { it.first == "USLT" }.second)
            assertArrayEquals(oldCoverPayload, frames.single { it.first == "APIC" }.second)
            assertArrayEquals(customTxxxPayload, frames.single { it.first == "TXXX" && txxxDescription(it.second) == "CUSTOM" }.second)
            assertArrayEquals(oldRichPayload, frames.single { it.first == "TXXX" && txxxDescription(it.second) == "LYRICS_TTML" }.second)
            assertArrayEquals(originalAudio, id3Audio(written))
        }
    }

    @Test
    fun id3WriterUpdatesOrdinaryAndRichLyricsIdempotentlyAndClearsOldTimeline() = withMp3(
        audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 9, 8, 7, 6),
        oldTagBody = id3Frame("USLT", usltPayloadForTest("旧普通歌词")) +
            id3Frame("TXXX", txxxLatin1Payload("lyrics_ttml", "<ttml>小写旧字段</ttml>")) +
            id3Frame("TXXX", txxxLatin1Payload("LYRICS_TTML", "<ttml>old</ttml>")) +
            id3Frame("TXXX", txxxPayload("LYRICS_TTML", "<ttml>重复旧</ttml>")) +
            id3Frame("TXXX", txxxPayload("OTHER", "不要改")) +
            id3Frame("TCON", byteArrayOf(0) + "Rock".toByteArray(Charsets.ISO_8859_1)) +
            id3Frame("APIC", byteArrayOf(0, 1, 2, 3, 4)),
    ) { file ->
        val originalAudio = id3Audio(file.readBytes())
        val rich = EmbeddedLyrics(plain = "[00:01.00]新普通歌词", ttml = "<ttml>新时间轴</ttml>")
        Id3Writer.write(file, "标题", "歌手", "专辑", null, rich)
        Id3Writer.write(file, "标题", "歌手", "专辑", null, rich)

        var frames = id3FramePayloads(id3Body(file.readBytes()))
        assertEquals(1, frames.count { it.first == "USLT" })
        assertEquals("[00:01.00]新普通歌词", usltText(frames.single { it.first == "USLT" }.second))
        assertEquals(1, frames.count { it.first == "TXXX" && txxxDescription(it.second).equals("LYRICS_TTML", ignoreCase = true) })
        assertEquals("<ttml>新时间轴</ttml>", txxxValue(frames.single { it.first == "TXXX" && txxxDescription(it.second) == "LYRICS_TTML" }.second))
        assertEquals("不要改", txxxValue(frames.single { it.first == "TXXX" && txxxDescription(it.second) == "OTHER" }.second))
        assertArrayEquals(byteArrayOf(0) + "Rock".toByteArray(Charsets.ISO_8859_1), frames.single { it.first == "TCON" }.second)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), frames.single { it.first == "APIC" }.second)

        Id3Writer.write(file, "标题", "歌手", "专辑", null, EmbeddedLyrics(plain = "[00:02.00]仅普通歌词"))
        frames = id3FramePayloads(id3Body(file.readBytes()))
        assertEquals(1, frames.count { it.first == "USLT" })
        assertEquals("[00:02.00]仅普通歌词", usltText(frames.single { it.first == "USLT" }.second))
        assertFalse(frames.any { it.first == "TXXX" && txxxDescription(it.second).equals("LYRICS_TTML", ignoreCase = true) })
        assertEquals("不要改", txxxValue(frames.single { it.first == "TXXX" && txxxDescription(it.second) == "OTHER" }.second))
        assertArrayEquals(originalAudio, id3Audio(file.readBytes()))
    }

    @Test
    fun id3WriterRejectsInvalidFrameIdWithoutChangingOriginal() = withMp3(byteArrayOf(1, 2, 3)) { file ->
        val invalidFrame = "bad!".toByteArray(Charsets.US_ASCII) + be32(1) + byteArrayOf(0, 0, 1)
        val original = id3Header(invalidFrame.size) + invalidFrame + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 7)
        file.writeBytes(original)

        assertTrue(runCatching { Id3Writer.write(file, "title", "artist", "album", null, null) }.isFailure)
        assertArrayEquals(original, file.readBytes())
        assertFalse(File(file.parentFile, file.name + ".tag").exists())
    }

    @Test
    fun id3WriterRejectsTruncatedTagWithoutChangingOriginal() = withMp3(byteArrayOf(1, 2, 3)) { file ->
        val original = id3Header(999) + byteArrayOf(1, 2, 3)
        file.writeBytes(original)
        assertTrue(runCatching { Id3Writer.write(file, "title", "artist", "album", null, null) }.isFailure)
        assertArrayEquals(original, file.readBytes())
        assertFalse(File(file.parentFile, file.name + ".tag").exists())
    }

    @Test
    fun id3WriterSkipsV24FooterAndPreservesAudio() = withMp3(byteArrayOf(1, 2, 3)) { file ->
        val header = id3Header(4).apply { this[3] = 4; this[5] = 0x10 }
        val audio = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 7, 8, 9)
        file.writeBytes(header + ByteArray(4) + ByteArray(10) + audio)
        Id3Writer.write(file, "title", "artist", "album", null, null)
        assertArrayEquals(audio, id3Audio(file.readBytes()))
    }

    @Test
    fun flacAnd24BitFlacUseTheSameMetadataContainer() {
        assertTrue(DownloadMetadataWriter.supports(".flac"))
        assertTrue(DownloadMetadataWriter.supports(".FLAC"))
        assertFalse(DownloadMetadataWriter.supports(".m4a"))
    }

    @Test
    fun embedsFlacCoverLyricsAndTextWithoutChangingAudioFrames() = withFlac(
        comments = listOf("TITLE=旧标题", "GENRE=流行"),
    ) { file ->
        DownloadMetadataWriter.write(
            file, ".flac", "新标题", "歌手", "专辑", pngCover,
            EmbeddedLyrics(plain = "[00:01.00]歌词", ttml = "<ttml>逐字</ttml>"), year = 2024,
        )

        val parsed = parseFlac(file)
        val comments = parsed.comments()
        assertEquals(1, parsed.blocks.count { it.type == 4 })
        assertEquals(1, parsed.blocks.count { it.type == 6 && it.pictureType() == 3 })
        assertEquals(listOf("TITLE=新标题"), comments.filter { it.startsWith("TITLE=") })
        assertTrue(comments.contains("ARTIST=歌手"))
        assertTrue(comments.contains("ALBUM=专辑"))
        assertTrue(comments.contains("DATE=2024"))
        assertTrue(comments.contains("LYRICS=[00:01.00]歌词"))
        assertTrue(comments.contains("LYRICS_TTML=<ttml>逐字</ttml>"))
        assertTrue(comments.contains("GENRE=流行"))
        assertArrayEquals(pngCover, parsed.frontCover())
        assertArrayEquals(audioFrames, parsed.audio)
    }

    @Test
    fun repeatedWriteReplacesManagedFieldsInsteadOfDuplicatingThem() = withFlac(
        comments = listOf("GENRE=摇滚", "UNSYNCED LYRICS=旧别名", "UNSYNCEDLYRICS=旧别名2"),
    ) { file ->
        DownloadMetadataWriter.write(
            file, ".flac", "第一次", "歌手", "专辑", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1),
            EmbeddedLyrics(plain = "旧歌词", ttml = "<ttml>旧</ttml>"),
        )
        val update = EmbeddedLyrics(plain = "新歌词", ttml = "<ttml>新</ttml>")
        DownloadMetadataWriter.write(file, ".flac", "第二次", "歌手", "专辑", pngCover, update)
        DownloadMetadataWriter.write(file, ".flac", "第二次", "歌手", "专辑", pngCover, update)

        val parsed = parseFlac(file)
        val comments = parsed.comments()
        assertEquals(1, parsed.blocks.count { it.type == 4 })
        assertEquals(1, parsed.blocks.count { it.type == 6 && it.pictureType() == 3 })
        assertEquals(listOf("TITLE=第二次"), comments.filter { it.startsWith("TITLE=") })
        assertEquals(listOf("LYRICS=新歌词"), comments.filter { it.startsWith("LYRICS=") })
        assertEquals(listOf("LYRICS_TTML=<ttml>新</ttml>"), comments.filter { it.startsWith("LYRICS_TTML=") })
        assertFalse(comments.any { it.substringBefore('=') in setOf("UNSYNCED LYRICS", "UNSYNCEDLYRICS") })
        assertTrue(comments.contains("GENRE=摇滚"))
        assertArrayEquals(pngCover, parsed.frontCover())
        assertArrayEquals(audioFrames, parsed.audio)
    }

    @Test
    fun disabledOrBlankFieldsDoNotDeleteExistingMetadata() = withFlac(
        comments = listOf("ARTIST=原歌手", "ALBUM=原专辑", "DATE=2020", "LYRICS=原歌词", "LYRICS_TTML=<ttml>原时间轴</ttml>"),
        cover = pngCover,
    ) { file ->
        DownloadMetadataWriter.write(file, ".flac", "标题", "", "", null, null)

        val parsed = parseFlac(file)
        val comments = parsed.comments()
        assertTrue(comments.contains("ARTIST=原歌手"))
        assertTrue(comments.contains("ALBUM=原专辑"))
        assertTrue(comments.contains("DATE=2020"))
        assertTrue(comments.contains("LYRICS=原歌词"))
        assertTrue(comments.contains("LYRICS_TTML=<ttml>原时间轴</ttml>"))
        assertArrayEquals(pngCover, parsed.frontCover())
    }

    @Test
    fun flacOrdinaryUpdateClearsStaleRichLyricsButNullPreservesBoth() = withFlac(
        comments = listOf(
            "LYRICS=旧正文",
            "LYRICS_TTML=<ttml>旧时间轴</ttml>",
            "UNSYNCED LYRICS=旧别名",
            "GENRE=保留",
        ),
    ) { file ->
        DownloadMetadataWriter.write(
            file, ".flac", "标题", "歌手", "专辑", null, EmbeddedLyrics(plain = "新正文"),
        )
        var comments = parseFlac(file).comments()
        assertEquals(listOf("LYRICS=新正文"), comments.filter { it.startsWith("LYRICS=") })
        assertFalse(comments.any { it.startsWith("LYRICS_TTML=") || it.startsWith("UNSYNCED LYRICS=") })
        assertTrue(comments.contains("GENRE=保留"))

        DownloadMetadataWriter.write(file, ".flac", "标题", "歌手", "专辑", null, null)
        comments = parseFlac(file).comments()
        assertEquals(listOf("LYRICS=新正文"), comments.filter { it.startsWith("LYRICS=") })
        assertFalse(comments.any { it.startsWith("LYRICS_TTML=") })
        assertTrue(comments.contains("GENRE=保留"))
        assertArrayEquals(audioFrames, parseFlac(file).audio)
    }

    @Test
    fun malformedInputIsRejectedWithoutReplacingTheOriginalFile() {
        val file = File.createTempFile("melora-invalid-", ".flac")
        val original = "not-a-flac".toByteArray()
        try {
            file.writeBytes(original)
            val failed = runCatching {
                DownloadMetadataWriter.write(file, ".flac", "标题", "歌手", "专辑", pngCover, EmbeddedLyrics(plain = "歌词"))
            }.isFailure
            assertTrue(failed)
            assertArrayEquals(original, file.readBytes())
        } finally {
            file.delete()
        }
    }

    private fun withMp3(
        audio: ByteArray,
        oldTagBody: ByteArray? = null,
        verify: (File) -> Unit,
    ) {
        val file = File.createTempFile("melora-metadata-", ".mp3")
        try {
            val oldTag = oldTagBody?.let { id3Header(it.size) + it } ?: ByteArray(0)
            file.writeBytes(oldTag + audio)
            verify(file)
        } finally {
            file.delete()
        }
    }

    private fun id3Header(size: Int): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
        3, 0, 0,
        ((size ushr 21) and 0x7F).toByte(),
        ((size ushr 14) and 0x7F).toByte(),
        ((size ushr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    private fun id3Frame(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + be32(payload.size) + byteArrayOf(0, 0) + payload

    private fun id3Body(bytes: ByteArray): ByteArray {
        val size = id3TagSize(bytes)
        return bytes.copyOfRange(10, 10 + size)
    }

    private fun id3FramePayloads(body: ByteArray): List<Pair<String, ByteArray>> = buildList {
        var offset = 0
        while (offset + 10 <= body.size && body.copyOfRange(offset, offset + 4).any { it != 0.toByte() }) {
            val id = String(body, offset, 4, Charsets.US_ASCII)
            val size = readBe32(body, offset + 4)
            add(id to body.copyOfRange(offset + 10, offset + 10 + size))
            offset += 10 + size
        }
    }

    private fun id3FrameIds(body: ByteArray): List<String> = id3FramePayloads(body).map { it.first }

    private fun txxxLatin1Payload(description: String, value: String): ByteArray = ByteArrayOutputStream().apply {
        write(0)
        write(description.toByteArray(Charsets.ISO_8859_1))
        write(0)
        write(value.toByteArray(Charsets.ISO_8859_1))
    }.toByteArray()

    private fun txxxPayload(description: String, value: String): ByteArray = ByteArrayOutputStream().apply {
        write(1)
        write(0xFF); write(0xFE)
        write(description.toByteArray(Charsets.UTF_16LE))
        write(0); write(0)
        write(0xFF); write(0xFE)
        write(value.toByteArray(Charsets.UTF_16LE))
    }.toByteArray()

    private fun txxxDescription(payload: ByteArray): String {
        val end = (3 until payload.size - 1 step 2).first { payload[it] == 0.toByte() && payload[it + 1] == 0.toByte() }
        return payload.copyOfRange(3, end).toString(Charsets.UTF_16LE)
    }

    private fun txxxValue(payload: ByteArray): String {
        val end = (3 until payload.size - 1 step 2).first { payload[it] == 0.toByte() && payload[it + 1] == 0.toByte() }
        return payload.copyOfRange(end + 4, payload.size).toString(Charsets.UTF_16LE)
    }

    private fun usltPayloadForTest(value: String): ByteArray = ByteArrayOutputStream().apply {
        write(1)
        write("xxx".toByteArray(Charsets.US_ASCII))
        write(0xFF); write(0xFE)
        write(0); write(0)
        write(0xFF); write(0xFE)
        write(value.toByteArray(Charsets.UTF_16LE))
    }.toByteArray()

    private fun usltText(payload: ByteArray): String = payload.copyOfRange(10, payload.size).toString(Charsets.UTF_16LE)

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun id3TagSize(bytes: ByteArray): Int =
        ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)

    private fun id3Audio(bytes: ByteArray): ByteArray {
        assertEquals("ID3", bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII))
        val size = id3TagSize(bytes)
        return bytes.copyOfRange(10 + size, bytes.size)
    }

    private fun withFlac(
        comments: List<String> = emptyList(),
        cover: ByteArray? = null,
        verify: (File) -> Unit,
    ) {
        val file = File.createTempFile("melora-metadata-", ".flac")
        try {
            file.writeBytes(buildFlac(comments, cover))
            verify(file)
        } finally {
            file.delete()
        }
    }

    private fun buildFlac(comments: List<String>, cover: ByteArray?): ByteArray {
        val blocks = mutableListOf(Block(0, ByteArray(34)))
        if (comments.isNotEmpty()) blocks += Block(4, buildComments(comments))
        if (cover != null) blocks += Block(6, buildPicture(cover))
        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            blocks.forEachIndexed { index, block -> writeBlock(block, index == blocks.lastIndex) }
            write(audioFrames)
        }.toByteArray()
    }

    private fun parseFlac(file: File): ParsedFlac {
        file.inputStream().buffered().use { input ->
            assertEquals("fLaC", input.readN(4).toString(Charsets.US_ASCII))
            val blocks = mutableListOf<Block>()
            var last: Boolean
            do {
                val header = input.readN(4)
                last = header[0].toInt() and 0x80 != 0
                val size = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                blocks += Block(header[0].toInt() and 0x7F, input.readN(size))
            } while (!last)
            return ParsedFlac(blocks, input.readBytes())
        }
    }

    private data class ParsedFlac(val blocks: List<Block>, val audio: ByteArray) {
        fun comments(): List<String> {
            val data = blocks.single { it.type == 4 }.data
            var offset = 0
            fun readLe32(): Int = readLe32(data, offset).also { offset += 4 }
            val vendorSize = readLe32()
            offset += vendorSize
            val count = readLe32()
            require(count in 0..100)
            return List(count) {
                val size = readLe32()
                data.copyOfRange(offset, offset + size).toString(Charsets.UTF_8).also { offset += size }
            }
        }

        fun frontCover(): ByteArray {
            val data = blocks.single { it.type == 6 && it.pictureType() == 3 }.data
            var offset = 4
            val mimeSize = readBe32(data, offset); offset += 4 + mimeSize
            val descriptionSize = readBe32(data, offset); offset += 4 + descriptionSize
            offset += 16
            val coverSize = readBe32(data, offset); offset += 4
            return data.copyOfRange(offset, offset + coverSize)
        }
    }

    private data class Block(val type: Int, val data: ByteArray) {
        fun pictureType(): Int? = if (data.size < 4) null else readBe32(data, 0)
    }

    private fun buildComments(comments: List<String>): ByteArray = ByteArrayOutputStream().apply {
        writeLeUtf8("fixture")
        writeLe32(comments.size)
        comments.forEach { writeLeUtf8(it) }
    }.toByteArray()

    private fun buildPicture(cover: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeBe32(3)
        writeBeUtf8("image/png")
        writeBeUtf8("")
        repeat(4) { writeBe32(0) }
        writeBe32(cover.size)
        write(cover)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeBlock(block: Block, last: Boolean) {
        write(block.type or if (last) 0x80 else 0)
        write((block.data.size ushr 16) and 0xFF)
        write((block.data.size ushr 8) and 0xFF)
        write(block.data.size and 0xFF)
        write(block.data)
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
        write(value and 0xFF); write((value ushr 8) and 0xFF); write((value ushr 16) and 0xFF); write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBe32(value: Int) {
        write((value ushr 24) and 0xFF); write((value ushr 16) and 0xFF); write((value ushr 8) and 0xFF); write(value and 0xFF)
    }

    private fun java.io.InputStream.readN(size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            require(read > 0)
            offset += read
        }
    }

    companion object {
        private fun readLe32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun readBe32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
