package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayerLyric
import org.junit.Assert.*
import org.junit.Test

class VinylLyricsPreviewTest {
    private val lines = (0..6).map { LyricLine(it * 1000L, "歌词$it") }

    @Test
    fun startMiddleAndEndAlwaysHaveFiveSlots() {
        for (index in lines.indices) assertEquals(5, vinylLyricPreviewRows(lines, index, "标题").size)
        assertEquals(listOf(null, null, "歌词0", "歌词1", "歌词2"), vinylLyricPreviewRows(lines, 0, "标题"))
        assertEquals(listOf("歌词1", "歌词2", "歌词3", "歌词4", "歌词5"), vinylLyricPreviewRows(lines, 3, "标题"))
        assertEquals(listOf("歌词4", "歌词5", "歌词6", null, null), vinylLyricPreviewRows(lines, 6, "标题"))
    }

    @Test
    fun shortAndMissingLyricsReserveTheSameArea() {
        assertEquals(listOf(null, null, "歌词0", null, null), vinylLyricPreviewRows(lines.take(1), 0, "标题"))
        assertEquals(listOf(null, null, "标题", null, null), vinylLyricPreviewRows(emptyList(), 0, "标题"))
        assertEquals(5, vinylLyricPreviewRows(emptyList(), 0, null).size)
    }

    @Test
    fun progressingLyricsKeepTheActiveLineInTheCenterSlot() {
        for (index in lines.indices) assertEquals(lines[index].text, vinylLyricPreviewRows(lines, index, "标题")[2])
    }
    @Test
    fun delayedLyricsFromPreviousTrackNeverEnterTheCurrentPages() {
        val previous = PlayerLyric("previous", "old", "artist", lines, "source")
        assertTrue(playerLyricLines("current", previous).isEmpty())
        assertTrue(playerLyricLines(null, previous).isEmpty())
        assertTrue(playerLyricLines("current", null).isEmpty())
        assertEquals(lines, playerLyricLines("previous", previous))
    }

    @Test
    fun translatedAndWrappedRowsKeepTheSameViewportCenter() {
        for (viewport in listOf(120, 400, 801, 1400)) {
            val padding = viewport / 2
            for (rowHeight in listOf(30, 65, 130, 240, 500)) {
                val offset = lyricCenterScrollOffset(rowHeight)
                val center = padding - offset + rowHeight / 2f
                assertEquals("viewport=$viewport row=$rowHeight", viewport / 2f, center, 1f)
                assertTrue(offset >= 0)
            }
        }
    }

    @Test
    fun shorterFirstAndLastRowsAreNotClampedAwayFromTheAnchor() {
        for (rows in listOf(listOf(30, 500, 65), listOf(500, 30, 500))) {
            val viewport = 400
            val padding = viewport / 2
            val gap = 14
            val content = padding * 2 + rows.sum() + gap * (rows.size - 1)
            val maxScroll = content - viewport
            var preceding = 0
            rows.forEach { height ->
                val target = preceding + lyricCenterScrollOffset(height)
                assertTrue("target $target must be reachable", target in 0..maxScroll)
                preceding += height + gap
            }
        }
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
