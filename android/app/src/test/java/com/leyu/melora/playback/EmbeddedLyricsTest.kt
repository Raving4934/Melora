package com.leyu.melora.playback

import com.leyu.melora.playback.local.LocalTagReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyricsTest {
    @Test
    fun ordinaryLinesUseOnlyTheLrcCompatibilityRepresentation() {
        val lines = listOf(
            LyricLine(1_000, "第一句"),
            LyricLine(2_500, "第二句"),
        )

        val embedded = requireNotNull(EmbeddedLyrics.fromLines(lines))

        assertEquals("[00:01.000]第一句\n[00:02.500]第二句", embedded.plain)
        assertEquals("", embedded.ttml)
        assertEquals(lines, embedded.parse())
        assertFalse(embedded.isBlank)
        assertNull(EmbeddedLyrics.fromLines(emptyList()))
    }

    @Test
    fun plainTextThatLooksLikeLrcMarkupIsProtectedByTtml() {
        val line = LyricLine(1_000, "literal [00:02.000] marker")

        val embedded = requireNotNull(EmbeddedLyrics.fromLines(listOf(line)))

        assertTrue(embedded.ttml.isNotBlank())
        assertEquals(listOf(line), embedded.parse())
    }

    @Test
    fun richTtmlRoundTripsWordsPausesLineEndSidecarsAlignmentAndEscaping() {
        val lines = listOf(
            LyricLine(
                startMs = 1_000,
                text = "A&B",
                translation = "译文 <&>",
                endMs = 5_000,
                words = listOf(LyricWord("A&", 1_000, 1_700), LyricWord("B", 2_200, 2_200)),
                romanization = "A and B",
                alignment = LyricAlignment.End,
            ),
            LyricLine(
                startMs = 6_000,
                text = "回声",
                endMs = 7_000,
                isBackground = true,
            ),
        )

        val embedded = requireNotNull(EmbeddedLyrics.fromLines(lines))
        val restored = embedded.parse()

        assertEquals("[00:01.000]A&B\n[00:06.000]回声", embedded.plain)
        assertTrue(embedded.ttml.isNotBlank())
        assertTrue(embedded.ttml.contains("&amp;"))
        assertTrue(embedded.ttml.contains("&lt;"))
        assertEquals(lines, restored)
        assertEquals(listOf(700L, 0L), restored.first().words.map { it.endMs - it.startMs })
        assertEquals(5_000L, restored.first().endMs)
        assertEquals("译文 <&>", restored.first().translation)
        assertEquals("A and B", restored.first().romanization)
        assertEquals(LyricAlignment.End, restored.first().alignment)
        assertTrue(restored.last().isBackground)
    }

    @Test
    fun unrepresentableTtmlKeepsThePlainCompatibilityDraft() {
        val source = LyricLine(
            startMs = 1_000,
            text = "visible text",
            endMs = 2_000,
            words = listOf(LyricWord("non-matching word", 1_000, 2_000)),
        )

        val embedded = requireNotNull(EmbeddedLyrics.fromLines(listOf(source)))

        assertEquals("[00:01.000]visible text", embedded.plain)
        assertTrue(embedded.ttml.isEmpty())
        assertEquals("visible text", embedded.parse().single().text)
    }

    @Test
    fun id3WriterReaderParseRoundTripRunsOnTheJvm() {
        val file = File.createTempFile("embedded-lyrics-", ".mp3")
        val sourceLines = listOf(
            LyricLine(
                startMs = 1_000,
                text = "A&B",
                translation = "译文 <&>",
                endMs = 5_000,
                words = listOf(LyricWord("A&", 1_000, 1_700), LyricWord("B", 2_200, 2_200)),
                romanization = "A and B",
                alignment = LyricAlignment.End,
            ),
        )
        try {
            val mp3Frame = ByteArray(417).apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xFB.toByte()
                this[2] = 0x90.toByte()
                this[3] = 0x64.toByte()
            }
            file.writeBytes(mp3Frame)
            val expected = requireNotNull(EmbeddedLyrics.fromLines(sourceLines))
            DownloadMetadataWriter.write(file, ".mp3", "Title", "Artist", "Album", null, expected)

            val actual = file.inputStream().use { input ->
                LocalTagReader.readEmbeddedLyrics(input, "audio/mpeg")
            }

            assertEquals(expected.plain, actual?.plain)
            assertEquals(expected.ttml, actual?.ttml)
            assertEquals(sourceLines, actual?.parse())
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedOrEmptyTtmlFallsBackToPlainIncludingEnhancedLrc() {
        val enhanced = "[00:01.000]<00:01.000>你<00:02.000>好<00:03.000>"
        val expected = LyricParser.parse(enhanced)

        assertEquals(expected, EmbeddedLyrics(plain = enhanced, ttml = "<tt><body>").parse())
        assertEquals(expected, EmbeddedLyrics(plain = enhanced, ttml = "<tt><body/></tt>").parse())
        assertEquals(expected, EmbeddedLyrics(plain = enhanced, ttml = "[00:09.000]not TTML").parse())
        assertTrue(EmbeddedLyrics().isBlank)
    }
}
