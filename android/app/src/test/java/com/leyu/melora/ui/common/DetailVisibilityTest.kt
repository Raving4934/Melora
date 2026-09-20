package com.leyu.melora.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailVisibilityTest {
    @Test
    fun `nested detail states keep navigation hidden until all close`() {
        while (DetailVisibility.visible) DetailVisibility.leave()

        DetailVisibility.enter()
        DetailVisibility.enter()
        assertTrue(DetailVisibility.visible)

        DetailVisibility.leave()
        assertTrue(DetailVisibility.visible)

        DetailVisibility.leave()
        assertFalse(DetailVisibility.visible)

        DetailVisibility.leave()
        assertFalse(DetailVisibility.visible)
    }
}
