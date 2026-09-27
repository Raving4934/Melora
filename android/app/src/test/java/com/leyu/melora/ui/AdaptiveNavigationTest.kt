package com.leyu.melora.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationTest {
    @Test
    fun persistentDrawerRequiresBothWindowThresholdsAndIncludesTheirBoundaries() {
        assertTrue(shouldUsePersistentDrawer(windowWidth = 600.dp, windowHeight = 480.dp))
        assertTrue(shouldUsePersistentDrawer(windowWidth = 720.dp, windowHeight = 600.dp))

        assertFalse(shouldUsePersistentDrawer(windowWidth = 599.dp, windowHeight = 480.dp))
        assertFalse(shouldUsePersistentDrawer(windowWidth = 600.dp, windowHeight = 479.dp))
        assertFalse(shouldUsePersistentDrawer(windowWidth = 599.dp, windowHeight = 479.dp))
        assertFalse(shouldUsePersistentDrawer(windowWidth = 500.dp, windowHeight = 800.dp))
    }
}
