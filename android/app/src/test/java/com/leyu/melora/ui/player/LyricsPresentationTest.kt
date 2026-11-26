package com.leyu.melora.ui.player

import com.leyu.melora.playback.LyricFrame
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.LyricWord
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
    fun immersiveDepthKeepsItsOwnBlurRadii() {
        assertEquals(listOf(0f, 1.3f, 3.25f), (0..2).map { lyricsBlurRadiusDp(it, immersive = false) })
        assertEquals(listOf(0f, 1.4f, 5f), (0..2).map { lyricsBlurRadiusDp(it, immersive = true) })
    }

    @Test
    fun ordinaryDepthHierarchyAlwaysDimsInactiveRowsBelowUnsungActiveText() {
        assertEquals(1f, ordinaryLyricsAlpha(distance = 0, past = false), 0.001f)
        assertEquals(1f, ordinaryLyricsAlpha(distance = 0, past = true), 0.001f)
        for (distance in 1..4) {
            val upcoming = ordinaryLyricsAlpha(distance, past = false)
            val past = ordinaryLyricsAlpha(distance, past = true)
            assertTrue("distance=$distance upcoming should stay dimmer than 0.42f unsung words", upcoming < 0.42f)
            assertTrue("distance=$distance upcoming should stay slightly clearer than past", upcoming > past)
            if (distance > 1) {
                assertTrue(upcoming <= ordinaryLyricsAlpha(distance - 1, past = false))
                assertTrue(past <= ordinaryLyricsAlpha(distance - 1, past = true))
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
    fun tabletCoverHasAReadingFriendlyCapWithoutChangingPhoneSizing() {
        assertEquals(441.6f, playerCoverSideDp(800f, 1000f, 148f), 0.01f)
        assertEquals(496.8f, playerCoverSideDp(540f, 900f, 148f), 0.01f)
        assertEquals(0f, playerCoverSideDp(800f, 100f, 150f), 0f)
    }

    @Test
    fun hidingPreviewReleasesItsSpaceWithoutChangingTheCoverScale() {
        assertEquals(276f, playerCoverSideDp(440f, 300f, 0f), 0.001f)
        assertEquals(0f, playerCoverSideDp(440f, 100f, 150f), 0f)
    }

    @Test
    fun lyricWordLiftRisesQuicklyHoldsSustainAndSettlesSmoothly() {
        val word = LyricWord("风", startMs = 1_000L, endMs = 1_600L)
        assertEquals(0f, lyricWordLift(word, 900L), 0.001f)
        assertEquals(0f, lyricWordLift(word, 1_000L), 0.001f)
        val midRise = lyricWordLift(word, 1_120L)
        assertTrue("mid-rise should be strictly between 0 and 1", midRise in 0.1f..0.99f)
        assertEquals(1f, lyricWordLift(word, 1_240L), 0.001f)
        assertEquals(1f, lyricWordLift(word, 1_500L), 0.001f)
        val settling = lyricWordLift(word, 1_810L)
        assertEquals(0.5f, settling, 0.01f)
        assertEquals(0f, lyricWordLift(word, 2_020L), 0.001f)
        assertEquals(0f, lyricWordLift(LyricWord("零", 500L, 500L), 550L), 0.001f)
    }

    @Test
    fun lyricInterludeDetectsIntroTimedGapAndPlainLrcGapWithoutTouchingOutro() {
        val linesWithIntroAndGap = listOf(
            LyricLine(startMs = 6_000L, text = "第一句", endMs = 8_000L),
            LyricLine(startMs = 14_000L, text = "第二句", endMs = 17_000L),
            LyricLine(startMs = 17_500L, text = "第三句", endMs = 20_000L),
        )
        val intro = lyricInterludeForFrame(linesWithIntroAndGap, LyricFrame(focusIndex = -1, activeIndices = emptySet()))
        assertEquals(LyricInterlude(anchorIndex = 0, beforeFirst = true, startMs = 0L, endMs = 6_000L), intro)

        val midGap = lyricInterludeForFrame(linesWithIntroAndGap, LyricFrame(focusIndex = 0, activeIndices = emptySet()))
        assertEquals(LyricInterlude(anchorIndex = 0, beforeFirst = false, startMs = 8_000L, endMs = 14_000L), midGap)

        // 短于 4s 的间隔和尾奏不触发三点呼吸
        assertNull(lyricInterludeForFrame(linesWithIntroAndGap, LyricFrame(focusIndex = 1, activeIndices = emptySet())))
        assertNull(lyricInterludeForFrame(linesWithIntroAndGap, LyricFrame(focusIndex = 2, activeIndices = emptySet())))

        // 普通 LRC 无 endMs 且整句跨度 >= 10s，在唱完 5s 后进入呼吸提示
        val plainLines = listOf(LyricLine(startMs = 0L, text = "长间奏前"), LyricLine(startMs = 12_000L, text = "间奏后"))
        assertEquals(
            LyricInterlude(anchorIndex = 0, beforeFirst = false, startMs = 5_000L, endMs = 12_000L),
            lyricInterludeForFrame(plainLines, LyricFrame(focusIndex = 0, activeIndices = setOf(0))),
        )
    }

    @Test
    fun interludeDotVisualsProgressAcrossThreeDotsAndRespectBounds() {
        val interlude = LyricInterlude(anchorIndex = 0, beforeFirst = false, startMs = 2_000L, endMs = 8_000L)
        assertNull(interludeDotVisuals(interlude, 1_999L, motionEnabled = true))
        assertNull(interludeDotVisuals(interlude, 8_000L, motionEnabled = true))

        val midDots = requireNotNull(interludeDotVisuals(interlude, 5_000L, motionEnabled = false))
        assertEquals(3, midDots.size)
        assertTrue("first dot should be lit before third dot", midDots[0].alpha > midDots[2].alpha)

        val nearStart = requireNotNull(interludeDotVisuals(interlude, 2_050L, motionEnabled = true))
        val steady = requireNotNull(interludeDotVisuals(interlude, 5_000L, motionEnabled = true))
        val nearEnd = requireNotNull(interludeDotVisuals(interlude, 7_950L, motionEnabled = true))
        assertTrue(nearStart[0].alpha < steady[0].alpha)
        assertTrue(nearEnd[2].scale < steady[2].scale)
    }

    @Test
    fun landscapeImmersivePlacesCoverOnTheLeftAndFullHeightLyricsOnTheRight() {
        for ((width, height) in listOf(818f to 390f, 688f to 360f)) {
            val layout = landscapeImmersiveLayout(width, height)
            assertTrue("cover must stay on the left of lyrics", layout.coverXDp + layout.coverSideDp < layout.lyricsXDp)
            assertTrue("cover should keep generous size in landscape", layout.coverSideDp >= 220f)
            assertTrue("cover and time must fit vertically", layout.coverYDp + layout.coverSideDp + 26f <= height)
            assertTrue("lyrics must use full right column height", layout.lyricsHeightDp >= height - 24f)
            assertTrue("lyrics column must remain wide enough", layout.lyricsWidthDp >= width * 0.50f)
        }
    }

    @Test
    fun stageHeroFontSizeScalesCleanlyAcrossLineLengths() {
        assertEquals(44f, stageHeroFontSizeSp(textLength = 8, widthDp = 820f), 0f)
        assertEquals(38f, stageHeroFontSizeSp(textLength = 16, widthDp = 820f), 0f)
        assertEquals(32f, stageHeroFontSizeSp(textLength = 28, widthDp = 820f), 0f)
        assertEquals(54f, stageHeroFontSizeSp(textLength = 8, widthDp = 1080f), 0f)
        assertEquals(46f, stageHeroFontSizeSp(textLength = 16, widthDp = 1080f), 0f)
        assertEquals(38f, stageHeroFontSizeSp(textLength = 28, widthDp = 1080f), 0f)

        val lines = listOf(
            LyricLine(startMs = 1_000L, text = "一", endMs = 3_000L),
            LyricLine(startMs = 4_000L, text = "二"),
            LyricLine(startMs = 9_000L, text = "三"),
        )
        assertEquals(0f, stageLineProgress(lines, 0, positionMs = 500L, durationMs = 20_000L), 0.001f)
        assertEquals(0.5f, stageLineProgress(lines, 0, positionMs = 2_000L, durationMs = 20_000L), 0.001f)
        assertEquals(1f, stageLineProgress(lines, 0, positionMs = 3_500L, durationMs = 20_000L), 0.001f)
        assertEquals(0.6f, stageLineProgress(lines, 1, positionMs = 7_000L, durationMs = 20_000L), 0.001f)
    }

}
