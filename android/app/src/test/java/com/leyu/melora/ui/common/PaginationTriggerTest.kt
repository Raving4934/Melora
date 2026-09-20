package com.leyu.melora.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTriggerTest {
    @Test
    fun `does not load merely because footer is composed`() {
        assertFalse(
            shouldRequestNextPage(
                isScrollInProgress = false,
                enabled = true,
                loading = false,
                totalItems = 10,
                lastVisibleItem = 9,
                prefetchDistance = 2,
            ),
        )
    }

    @Test
    fun `loads near end of an active scroll`() {
        assertTrue(
            shouldRequestNextPage(
                isScrollInProgress = true,
                enabled = true,
                loading = false,
                totalItems = 30,
                lastVisibleItem = 27,
                prefetchDistance = 2,
            ),
        )
    }

    @Test
    fun `does not load while busy or without a next page`() {
        assertFalse(shouldRequestNextPage(true, true, true, 30, 29, 2))
        assertFalse(shouldRequestNextPage(true, false, false, 30, 29, 2))
        assertFalse(shouldRequestNextPage(true, true, false, 30, 20, 2))
    }
}
