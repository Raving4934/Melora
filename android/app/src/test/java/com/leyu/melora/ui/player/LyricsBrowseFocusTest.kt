package com.leyu.melora.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsBrowseFocusTest {
    @Test fun returnsNullWhenNothingIsVisible() {
        assertNull(browseFocusedLyricIndex(emptyList()))
    }

    @Test fun picksTheItemWhoseCenterIsClosestToFocusOffsetZero() {
        val items = listOf(
            LyricViewportItemMetrics(index = 4, offset = -120, size = 80), // center -80
            LyricViewportItemMetrics(index = 5, offset = -24, size = 60),  // center +6
            LyricViewportItemMetrics(index = 6, offset = 52, size = 90),   // center +97
        )
        assertEquals(5, browseFocusedLyricIndex(items))
    }

    @Test fun accountsForTallWrappedLinesWhenComparingDistances() {
        val items = listOf(
            LyricViewportItemMetrics(index = 2, offset = -150, size = 260), // center -20
            LyricViewportItemMetrics(index = 3, offset = 125, size = 40),   // center +145
        )
        assertEquals(2, browseFocusedLyricIndex(items))
    }

    @Test fun picksSingleItemEvenWhenFarFromZero() {
        val items = listOf(LyricViewportItemMetrics(index = 0, offset = 210, size = 48))
        assertEquals(0, browseFocusedLyricIndex(items))
    }
}
