package com.leyu.melora.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigationTest {
    @Test
    fun directionSpecificMotionStaysMonotonicWithoutOvershoot() {
        assertEquals(280, pageMotionSpec(entering = true).durationMillis)
        assertEquals(300, pageMotionSpec(entering = false).durationMillis)
        for (entering in listOf(true, false)) {
            val positions = (0..100).map { pageMotionSpec(entering).easing.transform(it / 100f) }
            assertEquals(0f, positions.first(), 0f)
            assertEquals(1f, positions.last(), 0f)
            assertTrue(positions.all { it in 0f..1f })
            assertTrue(positions.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test
    fun pageEdgesMeetAtEveryPixelInBothDirectionsAndEveryViewport() {
        for (width in listOf(360f, 411f, 1280f, 1440f, 2772f)) {
            for (step in (0..1000) + (1000 downTo 0)) {
                val progress = step / 1000f
                val base = pageTranslationX(progress, width, detail = false)
                val detail = pageTranslationX(progress, width, detail = true)
                assertEquals(width, detail - base, 0f)
                assertTrue(base in -width..0f)
                assertTrue(detail in 0f..width)
            }
            assertEquals(0f, pageTranslationX(0f, width, false), 0f)
            assertEquals(width, pageTranslationX(0f, width, true), 0f)
            assertEquals(-width, pageTranslationX(1f, width, false), 0f)
            assertEquals(0f, pageTranslationX(1f, width, true), 0f)
        }
    }

    @Test
    fun reversalUsesTheSameCoordinatesInsteadOfResettingTheBasePage() {
        val progress = listOf(0f, .23f, .61f, .49f, .34f, .61f, 1f, .61f, .2f, 0f)
        val positions = progress.map { pageTranslationX(it, 1280f, false) }
        assertEquals(positions[2], positions[5], 0f)
        assertEquals(positions[5], positions[7], 0f)
        assertTrue(positions[3] < 0f)
        assertEquals(0f, positions.last(), 0f)
    }

    @Test
    fun returningPageConsumesBackUntilItsCompositionIsReleased() {
        assertTrue(pageReturnInProgress(targetExists = false, retainedExists = true))
        assertFalse(pageReturnInProgress(targetExists = true, retainedExists = true))
        assertFalse(pageReturnInProgress(targetExists = false, retainedExists = false))
    }
}
