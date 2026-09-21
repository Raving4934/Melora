package com.leyu.melora.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollToTopTest {
    @Test
    fun nearbyRowsAnimateDirectlyWithoutAnIntermediateJump() {
        assertNull(fastScrollAnchor(0))
        assertNull(fastScrollAnchor(3))
    }

    @Test
    fun distantRowsJumpIntoTheSmallSmoothAnimationWindow() {
        assertEquals(3, fastScrollAnchor(4))
        assertEquals(3, fastScrollAnchor(1_000))
    }
}
