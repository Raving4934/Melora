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

    @Test fun expandedContentRespectsCardWidthAndColumnCap() {
        assertEquals(4, responsiveGridColumns(904.dp))
        assertEquals(6, responsiveGridColumns(1600.dp))
        assertEquals(3, responsiveGridColumns(744.dp, minCardWidth = 240.dp))
    }
}
