package com.leyu.melora.ui.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class LocalSearchPageTest {
    @Test
    fun newSearchPageStartsEmptyWithoutMutatingTheExitedPage() {
        val exited = LocalSearchPageTarget().apply { query = "melora" }
        val reopened = LocalSearchPageTarget()

        assertEquals("melora", exited.query)
        assertEquals("", reopened.query)
        assertNotSame(exited, reopened)
    }
}
