package com.leyu.melora.ui.common

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SHEET_CONTENT_TAG = "melora-bottom-sheet-test-content"
private typealias SheetDismissRequest = (() -> Unit) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class MeloraBottomSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun explicitDismissCallsBackOnceOnlyAfterSheetIsHidden() {
        val mounted = mutableStateOf(true)
        var sheetState: SheetState? = null
        var dismissRequest: SheetDismissRequest? = null
        var callbackCount = 0
        var callbackObservedHidden = false

        compose.setContent {
            TestSheetHost(
                mounted = mounted.value,
                onReady = { state, dismiss ->
                    sheetState = state
                    dismissRequest = dismiss
                },
            )
        }
        compose.awaitStable(SHEET_CONTENT_TAG)

        compose.runOnIdle {
            val state = requireNotNull(sheetState)
            requireNotNull(dismissRequest) {
                "Sheet dismiss action must be captured after composition"
            }.invoke {
                callbackCount++
                callbackObservedHidden = !state.isVisible && state.currentValue == SheetValue.Hidden
                mounted.value = false
            }
        }
        compose.waitForIdle()

        assertEquals(1, callbackCount)
        assertTrue("callback must run after the real sheet reaches Hidden", callbackObservedHidden)
        compose.onNodeWithTag(SHEET_CONTENT_TAG).assertDoesNotExist()
    }

    @Test
    fun repeatedDismissRequestsWhileAnimationIsPausedOnlyCloseOnce() {
        val mounted = mutableStateOf(true)
        var dismissRequest: SheetDismissRequest? = null
        var callbackCount = 0
        val wasAutoAdvance = compose.mainClock.autoAdvance

        try {
            compose.setContent {
                TestSheetHost(
                    mounted = mounted.value,
                    onReady = { _, dismiss -> dismissRequest = dismiss },
                )
            }
            compose.awaitStable(SHEET_CONTENT_TAG)
            compose.mainClock.autoAdvance = false

            compose.runOnIdle {
                val dismiss = requireNotNull(dismissRequest)
                repeat(5) {
                    dismiss {
                        callbackCount++
                        mounted.value = false
                    }
                }
            }

            // The hide animation is held by the Compose clock: the real sheet is still mounted.
            compose.onNodeWithTag(SHEET_CONTENT_TAG).assertExists()
            compose.runOnIdle { assertEquals(0, callbackCount) }

            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()

            assertEquals(1, callbackCount)
            compose.onNodeWithTag(SHEET_CONTENT_TAG).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = wasAutoAdvance
        }
    }

    @Test
    fun rejectedHiddenTransitionKeepsSheetMountedAndCanBeRetried() {
        val mounted = mutableStateOf(true)
        val allowHidden = mutableStateOf(false)
        var sheetState: SheetState? = null
        var dismissRequest: SheetDismissRequest? = null
        var callbackCount = 0
        var callbackObservedHidden = false

        compose.setContent {
            TestSheetHost(
                mounted = mounted.value,
                confirmValueChange = { target -> target != SheetValue.Hidden || allowHidden.value },
                onReady = { state, dismiss ->
                    sheetState = state
                    dismissRequest = dismiss
                },
            )
        }
        compose.awaitStable(SHEET_CONTENT_TAG)

        compose.runOnIdle {
            requireNotNull(dismissRequest).invoke {
                callbackCount++
                callbackObservedHidden = sheetState?.isVisible == false
                mounted.value = false
            }
        }
        compose.waitForIdle()

        assertEquals("a rejected Hidden transition must not invoke the callback", 0, callbackCount)
        assertTrue("a rejected Hidden transition must not unmount the sheet", mounted.value)
        compose.onNodeWithTag(SHEET_CONTENT_TAG).assertExists()
        compose.runOnIdle {
            assertTrue(requireNotNull(sheetState).isVisible)
            allowHidden.value = true
        }

        compose.runOnIdle {
            requireNotNull(dismissRequest).invoke {
                callbackCount++
                callbackObservedHidden = sheetState?.isVisible == false
                mounted.value = false
            }
        }
        compose.waitForIdle()

        assertEquals(1, callbackCount)
        assertTrue("retry callback must only run after the sheet is hidden", callbackObservedHidden)
        compose.onNodeWithTag(SHEET_CONTENT_TAG).assertDoesNotExist()
    }

    @Test
    fun scrimTracksPhysicalDragAndRetainsCustomTintAtBothExpandedAnchors() {
        val mounted = mutableStateOf(true)
        var sheetState: SheetState? = null

        compose.setContent {
            TestSheetHost(
                mounted = mounted.value,
                skipPartiallyExpanded = false,
                scrimColor = Color.Red,
                sheetHeightFraction = 0.75f,
                onReady = { state, _ -> sheetState = state },
            )
        }
        compose.awaitStable(SHEET_CONTENT_TAG)

        val state = requireNotNull(sheetState)
        val root = compose.onNode(isDialog())
        val viewportHeightPx = root.fetchSemanticsNode().size.height
        compose.runOnIdle {
            assertTrue("the test host must expose both sheet anchors", state.hasPartiallyExpandedState)
            assertEquals(SheetValue.PartiallyExpanded, state.currentValue)
        }
        assertScrimMatchesPhysicalOffset(captureScrimFrame(state, viewportHeightPx))

        // Expand with an actual pointer gesture; a viewport-relative panel height keeps the
        // partially-expanded anchor available on both phone and tablet screens.
        compose.onNodeWithTag(SHEET_CONTENT_TAG).performTouchInput {
            val start = center
            val distance = height * 0.7f
            down(start)
            repeat(10) { step ->
                moveTo(
                    Offset(start.x, start.y - distance * (step + 1) / 10f),
                    delayMillis = 40,
                )
            }
            up()
        }
        compose.awaitStable(SHEET_CONTENT_TAG)
        compose.runOnIdle { assertEquals(SheetValue.Expanded, state.currentValue) }
        val expandedFrame = captureScrimFrame(state, viewportHeightPx)
        assertScrimMatchesPhysicalOffset(expandedFrame)

        val start = Offset(root.fetchSemanticsNode().size.width / 2f, state.requireOffset() + 100f)
        var pointer = start
        root.performTouchInput { down(start) }
        try {
            fun dragTo(delta: Float): ScrimFrame {
                val from = pointer
                val to = Offset(start.x, start.y + delta)
                root.performTouchInput {
                    repeat(10) { step ->
                        moveTo(from + (to - from) * ((step + 1) / 10f), delayMillis = 40)
                    }
                }
                pointer = to
                // Events are injected on leaving performTouchInput; sample only after drawing.
                compose.waitForIdle()
                return captureScrimFrame(state, viewportHeightPx)
            }
            val first = dragTo(viewportHeightPx * 0.48f)
            val farther = dragTo(viewportHeightPx * 0.60f)
            val reversed = dragTo(viewportHeightPx * 0.48f)
            val returned = dragTo(0f)
            assertTrue("drag must move the sheet down", first.sheetOffsetPx > expandedFrame.sheetOffsetPx)
            assertTrue("second sample must be farther down", farther.sheetOffsetPx > first.sheetOffsetPx)
            listOf(first, farther, reversed, returned).forEach(::assertScrimMatchesPhysicalOffset)
            assertTrue("scrim must fade with displacement", green(farther.pixel) > green(first.pixel) + 8)
            assertPixelsNear(first.pixel, reversed.pixel, tolerance = 12)
            assertPixelsNear(expandedFrame.pixel, returned.pixel, tolerance = 12)
        } finally {
            root.performTouchInput { up() }
        }
    }

    @Test
    fun nativeScrimAccessibilityActionStillDismissesTheSheet() {
        val mounted = mutableStateOf(true)
        var dismissCount = 0

        compose.setContent {
            TestSheetHost(
                mounted = mounted.value,
                onDismissRequest = {
                    dismissCount++
                    mounted.value = false
                },
                onReady = { _, _ -> },
            )
        }
        compose.awaitStable(SHEET_CONTENT_TAG)

        compose.onNode(
            hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).performClick()
        compose.waitForIdle()

        assertEquals(1, dismissCount)
        compose.onNodeWithTag(SHEET_CONTENT_TAG).assertDoesNotExist()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestSheetHost(
    mounted: Boolean,
    confirmValueChange: (SheetValue) -> Boolean = { true },
    onDismissRequest: () -> Unit = {},
    skipPartiallyExpanded: Boolean = true,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    sheetHeightFraction: Float? = null,
    onReady: (SheetState, SheetDismissRequest) -> Unit,
) {
    MeloraTheme {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = skipPartiallyExpanded,
            confirmValueChange = confirmValueChange,
        )
        val dismiss = rememberSheetDismiss(sheetState)
        SideEffect { onReady(sheetState, dismiss) }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.White),
        ) {
            val viewportHeight = maxHeight
            if (mounted) {
                MeloraBottomSheet(
                    onDismissRequest = onDismissRequest,
                    sheetState = sheetState,
                    containerColor = Color.White,
                    scrimColor = scrimColor,
                ) {
                    val contentModifier = sheetHeightFraction?.let { fraction ->
                        Modifier.fillMaxWidth().height(viewportHeight * fraction)
                    } ?: Modifier.size(240.dp)
                    Box(contentModifier.testTag(SHEET_CONTENT_TAG))
                }
            }
        }
    }
}

private data class ScrimFrame(
    val pixel: Int,
    val sheetOffsetPx: Float,
    val viewportHeightPx: Float,
    val hasPartialAnchor: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
private fun captureScrimFrame(sheetState: SheetState, viewportHeightPx: Int): ScrimFrame {
    check(viewportHeightPx > 0) { "test host viewport has not been measured" }
    val bitmap: Bitmap = requireNotNull(
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
    )
    return try {
        // Sample within the app viewport, clear of status/navigation bars and the bottom sheet.
        val pixel = bitmap.getPixel((bitmap.width * 0.1f).toInt(), (bitmap.height * 0.15f).toInt())
        ScrimFrame(
            pixel = pixel,
            sheetOffsetPx = sheetState.requireOffset(),
            viewportHeightPx = viewportHeightPx.toFloat(),
            hasPartialAnchor = sheetState.hasPartiallyExpandedState,
        )
    } finally {
        bitmap.recycle()
    }
}

private fun assertScrimMatchesPhysicalOffset(frame: ScrimFrame) {
    assertTrue("the pixel test requires the partially-expanded anchor", frame.hasPartialAnchor)
    val progress = ((frame.viewportHeightPx - frame.sheetOffsetPx) / (frame.viewportHeightPx / 2f))
        .coerceIn(0f, 1f)
    val expectedRed = 255
    val expectedGreenBlue = (255f * (1f - progress)).toInt()
    assertChannelNear("red", expectedRed, AndroidColor.red(frame.pixel), tolerance = 24)
    assertChannelNear("green", expectedGreenBlue, AndroidColor.green(frame.pixel), tolerance = 24)
    assertChannelNear("blue", expectedGreenBlue, AndroidColor.blue(frame.pixel), tolerance = 24)
}

private fun assertPixelsNear(expected: Int, actual: Int, tolerance: Int) {
    assertChannelNear("red", AndroidColor.red(expected), AndroidColor.red(actual), tolerance)
    assertChannelNear("green", AndroidColor.green(expected), AndroidColor.green(actual), tolerance)
    assertChannelNear("blue", AndroidColor.blue(expected), AndroidColor.blue(actual), tolerance)
}

private fun assertChannelNear(channel: String, expected: Int, actual: Int, tolerance: Int) {
    assertTrue(
        "$channel channel expected $expected ± $tolerance, was $actual",
        kotlin.math.abs(expected - actual) <= tolerance,
    )
}

private fun green(pixel: Int) = AndroidColor.green(pixel)
