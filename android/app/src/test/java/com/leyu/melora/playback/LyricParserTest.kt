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


    @Test fun lxWordsKeepExplicitDurationsGapsAndTheLastWordWithoutAnotherLine() {
        val line = LyricParser.parse("[00:10.000]<0,350>风<600,600>从<1550,1450>海").single()
        assertEquals("风从海", line.text)
        assertEquals(listOf(LyricWord("风", 10_000, 10_350), LyricWord("从", 10_600, 11_200),
            LyricWord("海", 11_550, 13_000)), line.words)
        assertEquals(13_000L, line.endMs)
    }

    @Test fun lxRepeatedLineTagsUseEachLineStartAndOffsetsApplyToBothWordEdges() {
        val lines = LyricParser.parse("[offset:100]\n[00:01.000][00:05.000]<200,500>你<1000,400>好")
        assertEquals(listOf(900L, 4900L), lines.map { it.startMs })
        assertEquals(listOf(LyricWord("你", 1100, 1600), LyricWord("好", 1900, 2300)), lines.first().words)
        assertEquals(listOf(LyricWord("你", 5100, 5600), LyricWord("好", 5900, 6300)), lines.last().words)
        val clipped = LyricParser.parse("[offset:1500]\n[00:01.000]<0,800>你<800,400>好").single()
        assertEquals(0L, clipped.startMs)
        assertEquals(listOf(LyricWord("你", 0, 300), LyricWord("好", 300, 700)), clipped.words)
        val delayed = LyricParser.parse("[offset:-100]\n[00:01.000]<0,500>你").single()
        assertEquals(listOf(LyricWord("你", 1100, 1600)), delayed.words)
    }

    @Test fun lxWordsPreserveInternalSpacesPunctuationAndZeroDuration() {
        val line = LyricParser.parse("[00:01.000]<0,200>  Hello <400,500>world<900,0>!  ").single()
        assertEquals("Hello world!", line.text)
        assertEquals(listOf(LyricWord("Hello ", 1000, 1200), LyricWord("world", 1400, 1900),
            LyricWord("!", 1900, 1900)), line.words)
    }

    @Test fun lxOverlapUsesLastSoundingWordEndRatherThanLastTokenEnd() {
        val line = LyricParser.parse("[00:01.000]<0,2000>你<500,200>好").single()
        assertEquals(3000L, line.endMs)
        assertEquals(1700L, line.words.last().endMs)
    }

    @Test fun onlineWordTrackEnrichesMatchingLinesWithoutLosingOrdinaryRowsOrSidecars() {
        val lines = LyricParser.parse(
            "[offset:100]\n[00:01.000]你好\n[00:03.000]保留普通句",
            translation = "[00:01.000]Hello\n[00:03.000]Keep",
            romanization = "[00:01.000]Ni hao",
            wordByWord = "[00:01.000]<0,300>你<500,600>好",
        )
        assertEquals(listOf("你好", "保留普通句"), lines.map { it.text })
        assertEquals(listOf(LyricWord("你", 900, 1200), LyricWord("好", 1400, 2000)), lines.first().words)
        assertEquals("Hello", lines.first().translation)
        assertEquals("Ni hao", lines.first().romanization)
        assertEquals("Keep", lines.last().translation)
        assertTrue(lines.last().words.isEmpty())
    }

    @Test fun malformedOrMismatchedWordTracksFallBackToTheExactOrdinaryResult() {
        val raw = "[00:01.000]你好"
        val expected = LyricParser.parse(raw, "[00:01.000]Hello", "[00:01.000]Ni hao")
        val invalid = listOf(
            "", "not lyrics", "[00:01.000]你好", "[00:01.000]<0,-1>你<100,100>好",
            "[00:01.000]<-1,100>你<100,100>好", "[00:01.000]<500,100>你<100,100>好",
            "[00:01.000]<9223372036854775807,100>你<100,100>好",
            "[00:01.000]<0,9223372036854775807>你<100,100>好",
            "[00:01.000]<9999999999999999999999,100>你<100,100>好",
            "[00:01.000]<0,100>其他句", "[00:02.000]<0,100>你<100,100>好",
            "[00:01.000]你<100,100>好", "[00:01.000]<bad>你<100,100>好",
            "[00:01.000]<0,100>你<100,100>好\n[00:01.000]<0,200>你<200,100>好",
        )
        invalid.forEach { assertEquals(it, expected,
            LyricParser.parse(raw, "[00:01.000]Hello", "[00:01.000]Ni hao", wordByWord = it)) }
    }

    @Test fun partialWordTrackOnlyEnrichesValidMatchingRows() {
        val lines = LyricParser.parse("[00:01.000]你好\n[00:03.000]晚安",
            wordByWord = "[00:01.000]<0,500>你<500,500>好\n[00:03.000]<0,-1>晚<500,500>安")
        assertEquals(2, lines.first().words.size)
        assertEquals("晚安", lines.last().text)
        assertTrue(lines.last().words.isEmpty())
    }

    @Test fun validWordOnlyLyricsAreUsableAndStillAttachSidecars() {
        val lines = LyricParser.parse("", translation = "[00:01.000]Hello",
            wordByWord = "[offset:100]\n[00:01.000]<0,300>你好")
        assertEquals(900L, lines.single().startMs)
        assertEquals("Hello", lines.single().translation)
        assertEquals(listOf(LyricWord("你好", 900, 1200)), lines.single().words)
        assertTrue(LyricParser.parse("", wordByWord = "[00:01.000]<0,-1>坏词").isEmpty())
    }

    @Test fun existingEnhancedWordsAndOrdinaryDuplicateRowsAreNotOverwritten() {
        val enhanced = "[00:01.000]<00:01.000>你<00:02.000>好<00:03.000>"
        assertEquals(LyricParser.parse(enhanced),
            LyricParser.parse(enhanced, wordByWord = "[00:01.000]<0,100>你<100,100>好"))
        val duplicated = "[00:01.000]你\n[00:01.000]好"
        assertEquals(LyricParser.parse(duplicated), LyricParser.parse(duplicated,
            wordByWord = "[00:01.000]<0,100>你\n[00:01.000]<0,100>好"))
    }

    @Test fun bulkWordTracksKeepEveryLineAndAreDeterministicAcrossRepeatedLoads() {
        val rows = (0 until 300).map { i ->
            val stamp = "[${(i / 60).toString().padStart(2, '0')}:${(i % 60).toString().padStart(2, '0')}.000]"
            "${stamp}第${i}句" to "$stamp<0,125>第<250,250>${i}<500,750>句"
        }
        val raw = rows.joinToString("\n") { it.first }
        val timed = rows.joinToString("\n") { it.second }
        val lines = LyricParser.parse(raw, wordByWord = timed)
        assertEquals(300, lines.size)
        lines.forEachIndexed { i, line ->
            assertEquals("第${i}句", line.text)
            assertEquals(3, line.words.size)
            assertEquals(i * 1000L + 1250, line.endMs)
        }
        assertEquals(lines, LyricParser.parse(raw, wordByWord = timed))
    }
}
