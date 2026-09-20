package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopLyricPolicyTest {
    @Test
    fun singleLineOverridesConfiguredLineCountAndTranslation() {
        assertEquals(1, desktopLyricMaxLines(singleLine = true, configured = 4f))
        assertEquals(
            "原词",
            desktopLyricText("原词", "译文", singleLine = true, maxLines = 4f),
        )
    }

    @Test
    fun multilineShowsTranslationOnlyWhenSpaceAllows() {
        assertEquals(3, desktopLyricMaxLines(singleLine = false, configured = 3f))
        assertEquals(
            "原词\n译文",
            desktopLyricText("原词", "译文", singleLine = false, maxLines = 2f),
        )
        assertEquals(
            "原词",
            desktopLyricText("原词", "译文", singleLine = false, maxLines = 1f),
        )
    }

    @Test
    fun panelExpandsOnlyWhileVisibleAndNeverExceedsScreen() {
        assertEquals(180, desktopLyricOverlayWidth(180, 264, 360, panelVisible = false))
        assertEquals(264, desktopLyricOverlayWidth(180, 264, 360, panelVisible = true))
        assertEquals(288, desktopLyricOverlayWidth(288, 264, 360, panelVisible = true))
        assertEquals(240, desktopLyricOverlayWidth(120, 264, 240, panelVisible = true))
    }

    @Test
    fun panelKeepsValidOffsetsForLeftCenterAndRightGravity() {
        assertEquals(20, desktopLyricWindowOffset(20, 0, false, 96))
        assertEquals(96, desktopLyricWindowOffset(200, 0, false, 96))
        assertEquals(0, desktopLyricWindowOffset(0, 1, false, 96))
        assertEquals(48, desktopLyricWindowOffset(200, 1, false, 96))
        assertEquals(-48, desktopLyricWindowOffset(-200, 1, false, 96))
        assertEquals(20, desktopLyricWindowOffset(20, 2, false, 96))
        assertEquals(0, desktopLyricWindowOffset(-200, 2, false, 96))
        assertEquals(96, desktopLyricWindowOffset(200, 2, false, 96))
        assertEquals(0, desktopLyricWindowOffset(200, 0, false, 0))
    }

    @Test
    fun logicalWindowAlignmentMirrorsInRtlWithoutChangingCenter() {
        for (offset in listOf(-200, -20, 0, 20, 200)) {
            assertEquals(desktopLyricWindowOffset(offset, 2, false, 96), desktopLyricWindowOffset(offset, 0, true, 96))
            assertEquals(desktopLyricWindowOffset(offset, 0, false, 96), desktopLyricWindowOffset(offset, 2, true, 96))
            assertEquals(desktopLyricWindowOffset(offset, 1, false, 96), desktopLyricWindowOffset(offset, 1, true, 96))
        }
    }

    @Test
    fun translationStyleTargetsOnlyTheRenderedTranslation() {
        val current = LyricLine(0, "原词", "译文")
        assertEquals(3..4, desktopLyricTranslationRange(listOf("原词", "译文", "下一句"), current, false))
        assertNull(desktopLyricTranslationRange(listOf("原词"), current, false))
        assertNull(desktopLyricTranslationRange(listOf("原词"), current, true))
        assertNull(desktopLyricTranslationRange(listOf("原词里有译文", "下一句"), current.copy(text = "原词里有译文"), false))
        // 原文与译文完全相同时，也只能给第二行加样式。
        assertEquals(3..4, desktopLyricTranslationRange(listOf("译文", "译文"), current.copy(text = "译文"), false))
        assertEquals(0..1, desktopLyricTranslationRange(listOf("译文", "下一句"), current.copy(text = ""), false))
    }

    private val sampleLines = listOf(
        LyricLine(0L, "第一句", "译文一"),
        LyricLine(1000L, "第二句", null),
        LyricLine(2000L, "第三句", null),
        LyricLine(3000L, "第四句", null),
        LyricLine(4000L, "第五句", null),
    )

    @Test
    fun windowShowsUpcomingLinesAccordingToMaxLines() {
        // 单行模式只看当前句
        assertEquals(
            listOf("第二句"),
            desktopLyricWindow(sampleLines, index = 1, singleLine = true, maxLines = 4f),
        )
    }

    @Test
    fun windowIncludesTranslationAndCapsAtMaxLines() {
        // 当前句 + 翻译占 2 行，剩余额度补后续段落
        assertEquals(
            listOf("第一句", "译文一", "第二句", "第三句"),
            desktopLyricWindow(sampleLines, index = 0, singleLine = false, maxLines = 4f),
        )
        // 仅 1 行额度时只显示当前句
        assertEquals(
            listOf("第一句"),
            desktopLyricWindow(sampleLines, index = 0, singleLine = false, maxLines = 1f),
        )
    }

    @Test
    fun windowStopsAtLastLine() {
        assertEquals(
            listOf("第五句"),
            desktopLyricWindow(sampleLines, index = 4, singleLine = false, maxLines = 4f),
        )
    }
}
