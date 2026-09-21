package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayerLyric
import org.junit.Assert.*
import org.junit.Test

class LyricsPresentationTest {
    private val lines = (0..6).map { LyricLine(it * 1000L, "歌词$it") }

    @Test
    fun delayedLyricsFromPreviousTrackNeverEnterTheCurrentPages() {
        val previous = PlayerLyric("previous", "old", "artist", lines, "source")
        assertTrue(playerLyricLines("current", previous).isEmpty())
        assertTrue(playerLyricLines(null, previous).isEmpty())
        assertTrue(playerLyricLines("current", null).isEmpty())
        assertEquals(lines, playerLyricLines("previous", previous))
    }

    @Test
    fun portraitAndLandscapeLyricsUseExactlyTheCoverWidth() {
        // 竖屏宽度受限，横屏高度受限；同一side同时用于封面和歌词，不再有额外8dp缩进。
        for ((width, height, lyricsHeight) in listOf(
            Triple(360f, 620f, 148f), Triple(440f, 280f, 44f), Triple(300f, 340f, 208f),
        )) {
            val side = playerCoverSideDp(width, height, lyricsHeight)
            assertEquals(minOf(width, height - lyricsHeight) * 0.92f, side, 0.001f)
            assertTrue(side <= width && side <= height - lyricsHeight)
        }
    }

    @Test
    fun hidingPreviewReleasesItsSpaceWithoutChangingTheCoverScale() {
        assertEquals(276f, playerCoverSideDp(440f, 300f, 0f), 0.001f)
        assertEquals(0f, playerCoverSideDp(440f, 100f, 150f), 0f)
    }

}
