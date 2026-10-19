package com.leyu.melora.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsiveGridLayoutTest {
    @Test fun sidebarContentUsesTwoColumnsInsteadOfTheWindowFour() {
        assertEquals(2, responsiveGridColumns((720 - 208 - 32).dp))
        assertEquals(2, responsiveGridColumns((720 - 208 - 32).dp, horizontalSpacing = 10.dp))
    }

    @Test fun compactPhoneContentKeepsTwoColumns() {
        listOf(288, 328, 358, 398, 448).forEach { width ->
            assertEquals(2, responsiveGridColumns(width.dp))
        }
    }

    @Test fun columnThresholdIncludesAllHorizontalGaps() {
        assertEquals(2, responsiveGridColumns(563.dp))
        assertEquals(3, responsiveGridColumns(564.dp))
        assertEquals(2, responsiveGridColumns(559.dp, horizontalSpacing = 10.dp))
        assertEquals(3, responsiveGridColumns(560.dp, horizontalSpacing = 10.dp))
    }

    @Test fun expandedContentKeepsTargetCardWidthWithoutASixColumnCap() {
        assertEquals(4, responsiveGridColumns(904.dp))
        assertEquals(6, responsiveGridColumns(1331.dp))
        assertEquals(7, responsiveGridColumns(1332.dp))
        assertEquals(8, responsiveGridColumns(1600.dp))
        assertEquals(16, responsiveGridColumns(3072.dp))
        assertEquals(3, responsiveGridColumns(744.dp, minCardWidth = 240.dp))
    }

    @Test fun expandedContentUsesTheRequestedCardWidthAndSpacing() {
        assertEquals(6, responsiveGridColumns(1515.dp, minCardWidth = 208.dp, horizontalSpacing = 10.dp))
        assertEquals(7, responsiveGridColumns(1516.dp, minCardWidth = 208.dp, horizontalSpacing = 10.dp))
        assertEquals(8, responsiveGridColumns(1720.dp, minCardWidth = 200.dp, horizontalSpacing = 12.dp))
    }
}
