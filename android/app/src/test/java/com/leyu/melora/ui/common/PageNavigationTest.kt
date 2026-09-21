package com.leyu.melora.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigationTest {
    @Test
    fun bothLayersUseDirectionSpecificMonotonicMotionWithoutOvershoot() {
        assertEquals(280, pageMotionSpec(entering = true).durationMillis)
        assertEquals(300, pageMotionSpec(entering = false).durationMillis)
        for (entering in listOf(true, false)) {
            val easing = pageMotionSpec(entering).easing
            val positions = (0..100).map { easing.transform(it / 100f) }
            assertEquals(0f, positions.first(), 0f)
            assertEquals(1f, positions.last(), 0f)
            assertTrue(positions.all { it in 0f..1f })
            assertTrue(positions.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test
    fun returningOrCancellingEntryConsumesRepeatedBackUntilSettled() {
        assertTrue(pageReturnInProgress(currentOpen = true, targetOpen = false, running = true))
        assertTrue(pageReturnInProgress(currentOpen = false, targetOpen = false, running = true))
        assertFalse(pageReturnInProgress(currentOpen = false, targetOpen = true, running = true))
        assertFalse(pageReturnInProgress(currentOpen = false, targetOpen = false, running = false))
        assertFalse(pageReturnInProgress(currentOpen = true, targetOpen = true, running = false))
    }
}
