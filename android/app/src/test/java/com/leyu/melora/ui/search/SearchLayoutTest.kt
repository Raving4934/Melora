package com.leyu.melora.ui.search

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchLayoutTest {

    @Test
    fun baseChromeHeightMatchesTheRenderedSearchHeader() {
        assertEquals(134.dp, SearchChromeHeaderHeight)
        assertEquals(
            SearchTopBarHeight +
                SearchInputSectionHeight +
                SearchInputSectionBottomSpacing +
                SearchInputContainerBottomPadding,
            SearchChromeHeaderHeight,
        )
        assertEquals(SearchChromeHeaderHeight, searchExpectedTopBarHeight(suggestionsVisible = false))
    }

    @Test
    fun suggestionPanelUsesTheMeasuredHeaderHeight() {
        assertNull(searchExpectedTopBarHeight(suggestionsVisible = true))
    }
}
