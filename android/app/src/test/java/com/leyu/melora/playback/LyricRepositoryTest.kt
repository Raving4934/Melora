package com.leyu.melora.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricRepositoryTest {
    @Test
    fun recoverableFailureReturnsNull() = runBlocking {
        assertNull(recoverableOrNull<String> { error("offline") })
    }

    @Test
    fun cancellationIsNeverConvertedToMissingLyric() {
        val error = org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking {
                recoverableOrNull<String> { throw CancellationException("stopped") }
            }
        }

        assertEquals("stopped", error.message)
    }

    @Test
    fun parserKeepsMultipleTagsTranslationsAndOffset() {
        val lyric = "[offset:100]\n[00:01.00][00:02.000]Line"
        val translation = "[00:01.00]译文"

        val lines = LyricParser.parse(lyric, translation)

        assertEquals(listOf(900L, 1_900L), lines.map { it.startMs })
        assertEquals("Line", lines.first().text)
        assertEquals("译文", lines.first().translation)
    }

    @Test
    fun parserAttachesWordByWordTimelineToMatchingLyric() {
        val lines = LyricParser.parse(
            raw = "[00:01.00]Hello",
            wordByWord = "[00:01.00]<0,500>Hel<500,500>lo",
        )

        assertEquals(
            listOf(LyricWord("Hel", 1_000L, 1_500L), LyricWord("lo", 1_500L, 2_000L)),
            lines.single().words,
        )
    }

    @Test
    fun cacheRoundTripStoresTheNewTimelineFields() {
        val lyric = PlayerLyric(
            uid = "song-1",
            title = "Title",
            artist = "Artist",
            source = "fixture",
            lines = listOf(
                LyricLine(
                    startMs = 1_000L,
                    text = "Hello",
                    translation = "你好",
                    endMs = 2_000L,
                    words = listOf(LyricWord("Hel", 1_000L, 1_400L), LyricWord("lo", 1_400L, 2_000L)),
                    romanization = "Ni Hao",
                    alignment = LyricAlignment.End,
                    isBackground = true,
                ),
                LyricLine(startMs = 3_000L, text = "", translation = "孤立译文"),
            ),
        )

        assertEquals(lyric, decodePlayerLyric(encodePlayerLyric(lyric)))
    }

    @Test
    fun cacheDecodeReadsLegacyTimeMsAndKeepsTranslationOnlyRows() {
        val legacy = JSONObject()
            .put("uid", "legacy")
            .put(
                "lines",
                JSONArray().put(
                    JSONObject()
                        .put("timeMs", 1_250L)
                        .put("text", "")
                        .put("translation", "旧译文"),
                ),
            )

        val decoded = decodePlayerLyric(legacy, fallbackUid = "fallback")

        assertEquals(
            listOf(LyricLine(startMs = 1_250L, text = "", translation = "旧译文")),
            decoded?.lines,
        )
    }
    @Test fun malformedPartialWordCacheFallsBackWithoutLosingTextOrTranslation() {
        val lyric = PlayerLyric("partial", "", "", listOf(LyricLine(1000, "Hello", "你好",
            words = listOf(LyricWord("Hel", 1000, 1400)))), "fixture")
        val restored = requireNotNull(decodePlayerLyric(encodePlayerLyric(lyric)))
        assertEquals(lyric.copy(lines = lyric.lines.map { it.copy(words = emptyList()) }), restored)
    }


    @Test fun automaticLocalLyricsOnlyEnrichMatchingRowsAndPreserveManualSource() {
        val track = UiTrack("local", "本地歌曲", "歌手", "专辑")
        val native = PlayerLyric(track.uid, track.title, track.artist,
            listOf(LyricLine(1000, "你好"), LyricLine(3000, "本地保留句")), "本地文件")
        val remote = native.copy(source = "tx", lines = listOf(LyricLine(1000, "你好", translation = "Hello",
            endMs = 2000, words = listOf(LyricWord("你", 1000, 1400), LyricWord("好", 1600, 2000)))))
        val auto = requireNotNull(chooseLyric(track, native, remote, LyricSourceMode.Auto))
        assertEquals(native.lines.map { it.text }, auto.lines.map { it.text })
        assertEquals(remote.lines.single().words, auto.lines.first().words)
        assertNull(auto.lines.first().translation) // 自动补时间轴不新增/替换本地翻译，避免行高突变。
        assertEquals(native, chooseLyric(track, native, remote, LyricSourceMode.Embedded))
        assertEquals(remote, chooseLyric(track, native, remote, LyricSourceMode.Matched))
        assertEquals(native, chooseLyric(track, native, null, LyricSourceMode.Matched))
    }

    @Test fun unmatchedVersionNeverReplacesLocalLyricsAutomatically() {
        val track = UiTrack("local", "标题", "歌手", "专辑")
        val native = PlayerLyric(track.uid, track.title, track.artist, listOf(LyricLine(1000, "你好")), "本地文件")
        val shifted = native.copy(source = "wy", lines = listOf(LyricLine(1200, "你好",
            words = listOf(LyricWord("你好", 1200, 2200)))))
        assertEquals(native, chooseLyric(track, native, shifted, LyricSourceMode.Auto))
        val changed = shifted.copy(lines = listOf(LyricLine(1000, "不同正文", words = listOf(LyricWord("不同正文", 1000, 2000)))))
        assertEquals(native, chooseLyric(track, native, changed, LyricSourceMode.Auto))
        assertEquals(changed, chooseLyric(track, native, changed, LyricSourceMode.Matched))
        assertEquals(shifted, chooseLyric(track, null, shifted, LyricSourceMode.Auto))
        assertNull(chooseLyric(track, null, shifted, LyricSourceMode.Embedded))
    }

    @Test fun sourceModeDefaultsAndUnknownValuesStayAutomatic() {
        assertEquals(LyricSourceMode.Auto, LyricSourceMode.restore(null))
        assertEquals(LyricSourceMode.Auto, LyricSourceMode.restore("removed"))
        LyricSourceMode.entries.forEach { assertEquals(it, LyricSourceMode.restore(it.storageValue)) }
    }

    @Test fun embeddedTimedLyricsAreNotOverwrittenByAnotherTimeAxis() {
        val track = UiTrack("local", "标题", "歌手", "专辑")
        val native = PlayerLyric(track.uid, track.title, track.artist, listOf(LyricLine(1000, "你好",
            words = listOf(LyricWord("你好", 1000, 1800)))), "本地文件")
        val remote = native.copy(source = "tx", lines = listOf(native.lines.single().copy(
            words = listOf(LyricWord("你好", 1000, 2400)))))
        assertEquals(native, chooseLyric(track, native, remote, LyricSourceMode.Auto))
    }
}
