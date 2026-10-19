package com.leyu.melora.ui

import androidx.compose.ui.unit.IntSize
import com.leyu.melora.ui.theme.appDisplayScale
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveDisplayTest {
    @Test fun phonesAndConventionalTabletsKeepTheirExistingDimensions() {
        listOf(
            IntSize(1080, 2400) to 3f,
            IntSize(2400, 1080) to 3f,
            IntSize(1600, 2560) to 2f,
            IntSize(2560, 1600) to 2f,
            IntSize(1536, 2048) to 2f,
        ).forEach { (window, density) ->
            assertEquals(window.toString(), 1f, appDisplayScale(window, density), 0f)
        }
    }

    @Test fun threeKWindowUsesOneConsistentScaleForTextAndControls() {
        val systemDensity = 225f / 160f
        val scale = appDisplayScale(IntSize(3072, 1920), systemDensity)
        assertEquals(1.7066667f, scale, 0.0001f)
        assertEquals(1280f, 3072 / (systemDensity * scale), 0.001f)
        assertEquals(800f, 1920 / (systemDensity * scale), 0.001f)
    }

    @Test fun rotationKeepsTheScaleAndResizingUsesOnlyTheCurrentWindow() {
        val density = 225f / 160f
        assertEquals(
            appDisplayScale(IntSize(3072, 1920), density),
            appDisplayScale(IntSize(1920, 3072), density),
            0f,
        )
        assertEquals(1f, appDisplayScale(IntSize(1000, 1920), density), 0f)
        assertEquals(1.3653333f, appDisplayScale(IntSize(1536, 1920), density), 0.0001f)
    }

    @Test fun scalingIsContinuousAtTheTabletBoundaryAndBoundedOnHugeWindows() {
        assertEquals(1f, appDisplayScale(IntSize(1280, 800), 1f), 0f)
        assertEquals(1.00125f, appDisplayScale(IntSize(1280, 801), 1f), 0.0001f)
        assertEquals(2f, appDisplayScale(IntSize(7680, 4320), 1f), 0f)
        assertEquals(1f, appDisplayScale(IntSize.Zero, 1f), 0f)
    }
}
