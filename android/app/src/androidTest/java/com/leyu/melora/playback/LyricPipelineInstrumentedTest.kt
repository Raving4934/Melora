package com.leyu.melora.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android 自带 XML 实现与桌面 JDK 不同，必须在设备上贯穿解析→缓存→时间线。 */
@RunWith(AndroidJUnit4::class)
class LyricPipelineInstrumentedTest {
    @Test fun androidXmlParserPreservesWordTimingDuetAndCachedTimeline() {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><head><metadata><ttm:agent xml:id="v1" type="person"/><ttm:agent xml:id="v2" type="person"/></metadata></head><body><div><p begin="00:01.000" end="00:04.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">你好</span><span begin="00:02.000" end="00:04.000">世界</span><span ttm:role="x-translation">Hello world</span><span ttm:role="x-roman">ni hao shi jie</span></p><p begin="00:03.000" end="00:05.000" ttm:agent="v2"><span begin="00:03.000" end="00:05.000">回应</span></p></div></body></tt>"""
        val lines = LyricParser.parse(raw)
        assertEquals(2, lines.size)
        assertEquals("你好世界", lines[0].text)
        assertEquals(listOf(1000L, 2000L), lines[0].words.map { it.startMs })
        assertEquals("Hello world", lines[0].translation)
        assertEquals("ni hao shi jie", lines[0].romanization)
        assertEquals(LyricAlignment.End, lines[1].alignment)
        val document = PlayerLyric("ttml-device", "Fixture", "Singer", lines, "embedded")
        val restored = requireNotNull(decodePlayerLyric(encodePlayerLyric(document)))
        assertEquals(document, restored)
        val timeline = LyricTimeline(restored.lines)
        assertEquals(setOf(0, 1), timeline.at(3500).activeIndices)
        assertEquals(setOf(0), timeline.at(1500).activeIndices)
        assertEquals(0.5f, lyricWordProgress(lines[0].words[0], 1500), 0f)
    }

    @Test fun androidXmlParserRejectsExternalEntitiesAndRecoversOnNextCall() {
        val invalid = """<!DOCTYPE tt [<!ENTITY external SYSTEM "file:///not-accessible">]><tt><body><div><p begin="1s">&external;</p></div></body></tt>"""
        assertTrue(LyricParser.parse(invalid).isEmpty())
        assertEquals("正常", LyricParser.parse("[00:01.000]正常").single().text)
    }

    @Test fun legacyCacheAndEnhancedLrcAreReadThroughSameModel() {
        val old = org.json.JSONObject("""{"uid":"legacy","lines":[{"timeMs":1000,"text":"旧词","translation":"Translation"}]}""")
        val legacy = requireNotNull(decodePlayerLyric(old))
        assertTrue(legacy.lines.single().words.isEmpty())
        assertEquals(1000L, legacy.lines.single().startMs)
        val enhanced = LyricParser.parse("[00:01.000]<00:01.000>你<00:02.000>好<00:03.000>")
        assertEquals("你好", enhanced.single().text)
        assertEquals(listOf(1000L, 2000L), enhanced.single().words.map { it.startMs })
        assertEquals(listOf(2000L, 3000L), enhanced.single().words.map { it.endMs })
    }
}
