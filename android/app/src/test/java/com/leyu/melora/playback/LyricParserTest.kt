package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {
    @Test
    fun ordinaryLrcKeepsOffsetTranslationRomanizationAndNoWords() {
        val lines = LyricParser.parse(
            raw = "[offset:100]\n[00:01.00][00:02.000]Line\n[00:01.00]Line 2",
            translation = "[00:01.00]译文\n[00:03.00]孤立译文",
            romanization = "[00:01.00]Line yi",
        )

        assertEquals(listOf(900L, 1_900L, 2_900L), lines.map { it.startMs })
        assertEquals("Line\nLine 2", lines[0].text)
        assertEquals("译文", lines[0].translation)
        assertEquals("Line yi", lines[0].romanization)
        assertTrue(lines[0].words.isEmpty())
        assertEquals("", lines.last().text)
        assertEquals("孤立译文", lines.last().translation)
    }

    @Test
    fun enhancedLrcOnlyCreatesWordsWhenAllTextAndEndTimesAreKnown() {
        val complete = LyricParser.parse(
            "[00:01.000]<00:01.100>Hel<00:01.500>lo\n[00:02.000]Next",
        )
        assertEquals("Hello", complete.first().text)
        assertEquals(2_000L, complete.first().endMs)
        assertEquals(
            listOf(
                LyricWord("Hel", 1_100L, 1_500L),
                LyricWord("lo", 1_500L, 2_000L),
            ),
            complete.first().words,
        )

        val incomplete = LyricParser.parse("[00:01.000]<00:01.100>Hel<00:01.500>lo")
        assertTrue(incomplete.single().words.isEmpty())

        val misaligned = LyricParser.parse("[00:01.000]prefix<00:01.100>Hel<00:01.500>lo ")
        assertTrue(misaligned.single().words.isEmpty())
    }

    @Test
    fun ttmlKeepsNamespacesNestedTextWordsRolesBackgroundAndMetadataAssociation() {
        val raw = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:tts="http://www.w3.org/ns/ttml#styling"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <head>
                <metadata>
                  <iTunesMetadata>
                    <translations>
                      <translation><text for="line-1">世界</text></translation>
                    </translations>
                    <transliterations>
                      <transliteration><text for="line-1">Shi Jie</text></transliteration>
                    </transliterations>
                  </iTunesMetadata>
                </metadata>
              </head>
              <body><div>
                <p begin="00:00:01.000" end="00:00:03.000" itunes:key="line-1" ttm:agent="v1" tts:textAlign="end"><span begin="00:00:01.000" end="00:00:01.500">Hello</span><span begin="00:00:01.500" end="00:00:02.000"> world</span><span ttm:role="x-translation">你好</span><span ttm:role="x-roman">Ni Hao</span></p>
                <p begin="00:00:01.000" end="00:00:02.000" x-bg="true">echo</p>
              </div></body>
            </tt>
        """.trimIndent()

        val lines = LyricParser.parse(raw)

        assertEquals(2, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals("你好\n世界", lines[0].translation)
        assertEquals("Ni Hao\nShi Jie", lines[0].romanization)
        assertEquals(LyricAlignment.End, lines[0].alignment)
        assertEquals(
            listOf(
                LyricWord("Hello", 1_000L, 1_500L),
                LyricWord(" world", 1_500L, 2_000L),
            ),
            lines[0].words,
        )
        assertEquals("echo", lines[1].text)
        assertTrue(lines[1].isBackground)
    }

    @Test
    fun ttmlUsesAbsoluteClockBeginAndRelativeDurWithoutParentDrift() {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="00:00:01.000" dur="2s"><span begin="00:00:01.000" dur="500ms">词</span></p></body></tt>"""

        val line = LyricParser.parse(raw).single()

        assertEquals(1_000L, line.startMs)
        assertEquals(3_000L, line.endMs)
        assertEquals(listOf(LyricWord("词", 1_000L, 1_500L)), line.words)
    }

    @Test
    fun overlappingTtmlLinesAreNotMergedAndExternalEntitiesAreRejected() {
        val overlapping = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><p begin="00:00:01.000">first</p><p begin="00:00:01.000">second</p></body>
            </tt>
        """.trimIndent()
        assertEquals(listOf("first", "second"), LyricParser.parse(overlapping).map { it.text })

        val unsafe = """
            <!DOCTYPE tt [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="00:00:01.000">&secret;</p></body></tt>
        """.trimIndent()
        assertTrue(LyricParser.parse(unsafe).isEmpty())
    }
    @Test fun enhancedTerminalTagBoundsLastWordAndRepeatedOrdinaryTextIsDeduplicated() {
        val line = LyricParser.parse("[00:01.000]<00:01.000>你<00:02.000>好<00:03.000>").single()
        assertEquals(listOf(LyricWord("你", 1000, 2000), LyricWord("好", 2000, 3000)), line.words)
        assertEquals(3000L, line.endMs)
        assertEquals("A\nB", LyricParser.parse("[00:01]A\n[00:01]B\n[00:01]A").single().text)
        assertEquals(99_000L, LyricParser.parse("[00:99]legacy").single().startMs)
    }

    @Test fun nestedBackgroundKeepsItsOwnTimingTextTranslationAndDoesNotMarkLeadAsBackground() {
        val raw = """<tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><p begin="1s" end="5s" ttm:agent="v1"><span begin="1s" end="2s">Hello</span> <span begin="2s" end="3s">world</span><span ttm:role="x-translation">主译</span><span ttm:role="x-bg" begin="3s" end="4s"><span begin="3s" end="4s">echo</span><span ttm:role="x-translation">背景译</span></span></p><p begin="3s" end="5s" ttm:agent="v2">Other</p></body></tt>"""
        val lines = LyricParser.parse(raw)
        assertEquals(3, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals("主译", lines[0].translation)
        assertTrue(!lines[0].isBackground)
        assertEquals("Hello world", lines[0].words.joinToString("") { it.text })
        val background = lines.single { it.isBackground }
        assertEquals("echo", background.text)
        assertEquals("背景译", background.translation)
        assertEquals(3000L, background.startMs)
        assertEquals(4000L, background.endMs)
        assertEquals(LyricAlignment.End, lines.single { it.text == "Other" }.alignment)
    }

    @Test fun unmarkedTextNeverGetsPartiallyTimedOrMisalignedGlyphs() {
        val raw = """<tt><body><p begin="1s" end="3s">untimed<span begin="1s" end="2s">timed</span></p></body></tt>"""
        val line = LyricParser.parse(raw).single()
        assertEquals("untimedtimed", line.text)
        assertTrue(line.words.isEmpty())
    }

    @Test fun declaredLeadSingerAndGroupRemainStableWhenDuetSingsFirst() {
        val raw = """<tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><head><metadata><ttm:agent xml:id="lead" type="person"/><ttm:agent xml:id="duet" type="person"/><ttm:agent xml:id="all" type="group"/></metadata></head><body><p begin="1s" ttm:agent="duet">B</p><p begin="2s" ttm:agent="lead">A</p><p begin="3s" ttm:agent="all">Both</p></body></tt>"""
        assertEquals(listOf(LyricAlignment.End, LyricAlignment.Start, LyricAlignment.Start), LyricParser.parse(raw).map { it.alignment })
    }

}
