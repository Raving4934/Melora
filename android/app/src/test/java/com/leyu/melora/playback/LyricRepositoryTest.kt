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

}
