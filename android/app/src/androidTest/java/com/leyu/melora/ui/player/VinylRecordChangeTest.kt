package com.leyu.melora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.leyu.melora.playback.PlayerCoverStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VinylRecordChangeTest {
    @get:Rule val compose = createComposeRule()

    private data class Record(val uid: String, val artwork: String?)
    private data class Frame(
        val record: Record,
        val scale: Float,
        val armLift: Float,
        val armAngle: Float = 4f,
    )

    private val first = Record("first", "first-cover")
    private val second = Record("second", "second-cover")
    private val latest = Record("latest", "latest-cover")
    private val requested = mutableStateOf(first)
    private val motionEnabled = mutableStateOf(true)
    private lateinit var change: VinylRecordChange
    private lateinit var armAngle: () -> Float

    @After fun restoreClock() {
        compose.mainClock.autoAdvance = true
    }

    @Test fun recordShrinksAfterTheArmLiftsAndReturnsBeforeTheArmLowers() {
        show()
        assertEquals(Frame(first, 1f, 0f), frame())
        compose.runOnIdle { requested.value = second }

        val frames = sampleTransition()
        val shrink = frames.indexOfFirst { it.scale < 0.998f }
        val handoff = frames.indexOfFirst { it.record == second }
        assertTrue("the arm must lift before the disc starts shrinking", shrink > 0 &&
            frames.take(shrink).any { it.armAngle < 3f })
        assertTrue("the label changes during the shrink", handoff > shrink)
        assertEquals(first, frames[shrink].record)
        assertEquals(0.97f, frames[handoff].scale, 0.001f)
        assertTrue(frames.all { it.scale in 0.9699f..1.0001f })
        assertTrue("the arm stays raised through the label change",
            frames.subList(shrink, handoff + 1).all { it.armLift > 0.05f })
        assertTrue("the disc returns to full size before the arm settles",
            frames.drop(handoff).any { it.scale > 0.999f && it.armLift > 0.05f })
        assertEquals(Frame(second, 1f, 0f), frames.last())
    }

    @Test fun chaptersWithTheSameCoverLiftTheArmWithoutResizingTheDisc() {
        show()
        val nextChapter = second.copy(artwork = first.artwork)
        compose.runOnIdle { requested.value = nextChapter }

        val frames = sampleTransition()
        assertTrue(frames.any { it.armAngle < 3f })
        assertTrue(frames.all { it.scale == 1f && it.record.artwork == first.artwork })
        assertEquals(Frame(nextChapter, 1f, 0f), frames.last())
    }

    @Test fun lateArtworkDuringAnArmLiftUpdatesTheLabelWithoutStartingAnotherSwap() {
        requested.value = first.copy(artwork = null)
        show()
        compose.runOnIdle { requested.value = second.copy(artwork = null) }
        advanceUntil { it.armLift > 0.01f }
        compose.runOnIdle { requested.value = second }

        val frames = sampleTransition()
        assertTrue(frames.all { it.scale == 1f })
        assertEquals(Frame(second, 1f, 0f), frames.last())
    }

    @Test fun skippingDuringTheShrinkKeepsTheCurrentScaleAndUpdatesToTheLatestLabel() {
        show()
        compose.runOnIdle { requested.value = second }
        val beforeSkip = advanceUntil { it.scale in 0.976f..0.99f }
        assertEquals(first, beforeSkip.record)

        compose.runOnIdle { requested.value = latest }
        val frames = sampleTransition()
        assertEquals("the animation continues from the current scale",
            beforeSkip.scale, frames.first().scale, 0.008f)
        assertTrue("the skipped label does not appear", frames.none { it.record == second })
        assertTrue(frames.any { it.record == latest && it.scale < 0.99f })
        assertEquals(Frame(latest, 1f, 0f), frames.last())
    }

    @Test fun skippingDuringTheReturnRetargetsTheDiscWithoutJumpingToFullSize() {
        show()
        compose.runOnIdle { requested.value = second }
        val beforeSkip = advanceUntil { it.record == second && it.scale in 0.976f..0.99f }

        compose.runOnIdle { requested.value = latest }
        val frames = sampleTransition()
        assertEquals("the disc retargets from its current size", beforeSkip.scale, frames.first().scale, 0.008f)
        val handoff = frames.indexOfFirst { it.record == latest }
        assertTrue("the final selection takes over before the interrupted disc finishes",
            handoff > 0 && frames.take(handoff).all { it.record == second && it.scale < 1f })
        assertTrue(frames.drop(handoff).all { it.record == latest })
        assertEquals(Frame(latest, 1f, 0f), frames.last())
    }

    @Test fun artworkRefreshAndDisabledMotionSettleImmediatelyWithoutReplayingTheSwap() {
        show()
        val refreshed = first.copy(artwork = "updated-cover")
        compose.runOnIdle { requested.value = refreshed }
        val refreshFrames = sampleTransition()
        assertTrue(refreshFrames.all { it.scale == 1f && it.armLift == 0f })
        assertEquals(Frame(refreshed, 1f, 0f), refreshFrames.last())

        compose.runOnIdle { requested.value = second }
        advanceUntil { it.scale < 0.99f }
        compose.runOnIdle {
            motionEnabled.value = false
            requested.value = latest
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(Frame(latest, 1f, 0f), frame())

        compose.runOnIdle { motionEnabled.value = true }
        assertTrue(sampleTransition().all { it == Frame(latest, 1f, 0f) })
    }

    @Test fun discShrinksAroundItsCenterWhileTheTonearmStaysParked() {
        show(playing = false, renderArtwork = true)
        assertEquals(-45f, frame().armAngle, 0f)
        val before = coverPixels()
        compose.runOnIdle { requested.value = second }
        advanceUntil {
            assertEquals(-45f, it.armAngle, 0f)
            it.scale <= 0.971f
        }
        val shrunk = coverPixels()

        assertEquals(before.at(0.74f, 0.1512f), shrunk.at(0.74f, 0.1512f))
        assertCenteredShrink(before.horizontalDiscSpan(), shrunk.horizontalDiscSpan())
        assertCenteredShrink(before.verticalDiscSpan(), shrunk.verticalDiscSpan())
        assertEquals("the disc stays opaque", before.at(0.50f, 0.70f).blue, shrunk.at(0.50f, 0.70f).blue, 0.05f)
        assertTrue("the arm remains parked for the entire swap", sampleTransition().all { it.armAngle == -45f })
    }

    private fun show(playing: Boolean = true, renderArtwork: Boolean = false) {
        compose.setContent {
            val record = requested.value
            val state = rememberVinylRecordChange(record.uid, record.artwork, motionEnabled.value)
            val angle = rememberVinylTonearmAngle(playing, motionEnabled.value, recordChange = state)
            SideEffect {
                change = state
                armAngle = angle
            }
            if (renderArtwork) {
                Box(
                    Modifier.size(160.dp).background(Color(0xFF6090C0)).testTag("record-change-cover"),
                    contentAlignment = Alignment.Center,
                ) {
                    NowPlayingArtwork(
                        record.artwork, record.uid, PlayerCoverStyle.Vinyl,
                        Modifier.size(128.dp), tonearmAngle = angle, recordChange = state,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun frame(): Frame = compose.runOnIdle {
        Frame(Record(change.displayedUid, change.displayedArtwork), change.scale, change.armLift, armAngle())
    }

    private fun advanceFrame(): Frame {
        compose.mainClock.advanceTimeByFrame()
        return frame()
    }

    private fun sampleTransition() = List(64) { advanceFrame() }

    private fun advanceUntil(condition: (Frame) -> Boolean): Frame {
        repeat(64) {
            val frame = advanceFrame()
            if (condition(frame)) return frame
        }
        error("record change did not reach the expected phase")
    }

    private fun coverPixels() = compose.onNodeWithTag("record-change-cover").captureToImage().toPixelMap()

    private fun PixelMap.at(x: Float, y: Float): Color = this[(width * x).toInt(), (height * y).toInt()]

    private fun PixelMap.horizontalDiscSpan() = discSpan(width) { this[it, height / 2] }

    private fun PixelMap.verticalDiscSpan() = discSpan(height) { this[width / 2, it] }

    private fun discSpan(length: Int, color: (Int) -> Color): IntRange {
        val darkPixels = (0 until length).filter { color(it).blue < 0.36f }
        assertTrue("the disc remains visible against the blue background", darkPixels.isNotEmpty())
        return darkPixels.first()..darkPixels.last()
    }

    private fun assertCenteredShrink(before: IntRange, shrunk: IntRange) {
        val beforeDiameter = before.last - before.first
        val shrunkDiameter = shrunk.last - shrunk.first
        assertTrue("the visible disc shrinks", shrunkDiameter < beforeDiameter)
        assertEquals("the disc shrinks by 3%", beforeDiameter * 0.97f, shrunkDiameter.toFloat(), 2f)
        val beforeCenter = (before.first + before.last) / 2f
        val shrunkCenter = (shrunk.first + shrunk.last) / 2f
        assertEquals("the disc center stays fixed", beforeCenter, shrunkCenter, 1f)
    }
}
