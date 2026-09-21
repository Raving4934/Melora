package com.leyu.melora.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerMotionTest {
    @Test
    fun releaseUsesVelocityBeforePositionAndStaysOnAnAnchor() {
        assertEquals(208f, drawerTarget(10f, 208f, 501f), 0f)
        assertEquals(0f, drawerTarget(200f, 208f, -501f), 0f)
        assertEquals(0f, drawerTarget(208f * .4f, 208f, 0f), 0f)
        assertEquals(208f, drawerTarget(100f, 208f, 0f), 0f)
        for (width in listOf(208f, 655.2f, 832f)) {
            for (offset in listOf(0f, width * .3f, width * .5f, width)) {
                for (velocity in listOf(-12000f, -500f, 0f, 500f, 12000f)) {
                    val target = drawerTarget(offset, width, velocity)
                    assertTrue(target == 0f || target == width)
                }
            }
        }
    }
}
