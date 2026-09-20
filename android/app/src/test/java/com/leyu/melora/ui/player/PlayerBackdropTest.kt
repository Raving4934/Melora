package com.leyu.melora.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackdropTest {
    @Test
    fun loadingKeepsTheDisplayedImageAndToneTogether() {
        val request = Any()
        val displayed = "old-128px" to Color(0xFF176BA3)

        assertSame(displayed, playerBackdropAfterLoad(displayed, request, request, null))
    }

    @Test
    fun readyResultReplacesImageAndToneWithoutANeutralIntermediate() {
        val request = Any()
        val displayed = "old-128px" to Color(0xFF176BA3)
        val ready = "new-128px" to Color(0xFF8A5B37)

        // 新解码与缓存命中使用同一个 ready 发布入口。
        val next = playerBackdropAfterLoad(displayed, request, request, Result.success(ready))
        assertSame(ready, next)
        assertSame(ready, playerBackdropAfterLoad(next, request, request, Result.success(ready)))
    }

    @Test
    fun failureMissingArtworkAndTimeoutClearTheOldBackground() {
        val request = Any()
        val displayed = "old-128px"

        assertNull(playerBackdropAfterLoad(displayed, request, request, Result.failure(Exception("decode"))))
        // 无封面/Coil 非 Success/加载超时均以完成后的 null 回退，不等同于 loading。
        assertNull(playerBackdropAfterLoad(displayed, request, request, Result.success(null)))
    }

    @Test
    fun staleSuccessAndFailureCannotReplaceTheLatestDisplay() {
        val oldRequest = Any()
        val latestRequest = Any()
        val displayed = "latest-128px"

        assertSame(displayed, playerBackdropAfterLoad(displayed, oldRequest, latestRequest, Result.success("stale")))
        assertSame(displayed, playerBackdropAfterLoad(displayed, oldRequest, latestRequest, Result.failure(Exception("stale"))))
        assertSame(displayed, playerBackdropAfterLoad(displayed, oldRequest, latestRequest, Result.success(null)))
    }

    @Test
    fun returnToSameUriAndReopenRetryUseANewRequestIdentity() {
        val firstA = Any()
        val secondA = Any()
        val displayed = "B-128px"
        val ready = "A-128px"

        assertSame(displayed, playerBackdropAfterLoad(displayed, firstA, secondA, Result.success(ready)))
        assertSame(ready, playerBackdropAfterLoad(displayed, secondA, secondA, Result.success(ready)))
        val retry = Any()
        assertEquals(ready, playerBackdropAfterLoad(null, retry, retry, Result.success(ready)))
    }

    @Test
    fun solidColorDoesNotShiftAfterThreePasses() {
        val color = 0xFF2A74C8.toInt()
        val source = IntArray(7 * 5) { color }

        val result = blurBackdropPixels(7, 5, source, radius = 3, passes = 3)

        assertTrue(result.all { it == color })
    }

    @Test
    fun edgeSamplesAreClampedInsteadOfWrapping() {
        val source = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())

        val result = blurBackdropPixels(2, 1, source, radius = 1, passes = 1)

        assertArrayEquals(
            intArrayOf(0xFF555555.toInt(), 0xFFAAAAAA.toInt()),
            result,
        )
    }

    @Test
    fun onePixelImageKeepsItsColorAndDimensions() {
        val source = intArrayOf(0xCC15314A.toInt())

        val result = blurBackdropPixels(1, 1, source, radius = 32, passes = 3)

        assertEquals(1, result.size)
        assertEquals(source[0], result[0])
    }

    @Test
    fun inputPixelsAreNotMutated() {
        val source = intArrayOf(
            0xFF112233.toInt(), 0xFF445566.toInt(),
            0xFF778899.toInt(), 0xFFAABBCC.toInt(),
        )
        val original = source.copyOf()

        blurBackdropPixels(2, 2, source, radius = 1, passes = 3)

        assertArrayEquals(original, source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedDimensionsAreRejected() {
        blurBackdropPixels(2, 2, intArrayOf(0xFF000000.toInt()), radius = 1, passes = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedPassCountIsRejected() {
        blurBackdropPixels(1, 1, intArrayOf(0xFFFFFFFF.toInt()), radius = 1, passes = 4)
    }

    @Test
    fun representativeColorKeepsAChromaticSampleAtTheLastBoundary() {
        val source = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF176BA3.toInt(),
        )

        val color = representativeColorFromPixels(3, 2, source, maxAxisSamples = 2)

        assertNotNull(color)
        assertTrue(color!!.blue > color.red)
        assertTrue(color.blue > color.green)
    }

    @Test
    fun representativeColorIgnoresTransparentAndLowSaturationSamples() {
        val transparent = intArrayOf(0x001E88E5, 0x0088CC44)
        val neutral = intArrayOf(0xFF808080.toInt(), 0xFF777777.toInt())

        assertNull(representativeColorFromPixels(2, 1, transparent))
        assertNull(representativeColorFromPixels(2, 1, neutral))
    }

    @Test(expected = IllegalArgumentException::class)
    fun representativeColorRejectsAnUnboundedSampleRequest() {
        representativeColorFromPixels(1, 1, intArrayOf(0xFF2A74C8.toInt()), maxAxisSamples = 17)
    }

    @Test
    fun playerCardsRemainTranslucentAndSelectedItemStaysDistinct() {
        val colors = playerColorsFor(dark = true)
        for (surface in listOf(colors.cardSurface, colors.cardSelected)) {
            assertTrue(surface.alpha > 0f && surface.alpha < 0.5f)
        }
        assertNotEquals(colors.cardSurface, colors.cardSelected)
    }

    @Test
    fun cardsAndProgressTrackRetainWarmAndCoolBackdropColors() {
        val warm = Color(0xFF815A3B)
        val cool = Color(0xFF284F78)
        val colors = playerColorsFor(dark = true)
        for (overlay in listOf(colors.cardSurface, colors.cardSelected, colors.progressInactive)) {
            val overWarm = overlay.compositeOver(warm)
            val overCool = overlay.compositeOver(cool)
            assertNotEquals(overWarm, overCool)
            assertTrue(overWarm.red > overWarm.blue)
            assertTrue(overCool.blue > overCool.red)
        }
    }

    @Test
    fun inactiveProgressTrackIsNeutralTranslucentWhiteWithoutArtwork() {
        val colors = playerColorsFor(dark = true)
        assertEquals(1f, colors.progressInactive.red, 0f)
        assertEquals(1f, colors.progressInactive.green, 0f)
        assertEquals(1f, colors.progressInactive.blue, 0f)
        assertTrue(colors.progressInactive.alpha in 0.15f..0.35f)
        assertTrue(colors.textPrimary.alpha > colors.progressInactive.alpha)
    }

    @Test
    fun secondaryTextRemainsReadableOverBothCardStates() {
        // 纯白封面经现有背景透明度和最浅顶层遮罩后的最亮区域。
        val colors = playerColorsFor(dark = true)
        val backdrop = colors.backdrop.scrimStops.first().second
            .compositeOver(colors.backdrop.baseColor)
        for (surface in listOf(colors.cardSurface, colors.cardSelected)) {
            val card = surface.compositeOver(backdrop)
            val text = colors.textSecondary.compositeOver(card)
            val contrast = (text.luminance() + 0.05f) / (card.luminance() + 0.05f)
            assertTrue("Muted-label contrast: $contrast", contrast >= 4.5f)
        }
    }
}
