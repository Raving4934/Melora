package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestSheetHost(
    mounted: Boolean,
    confirmValueChange: (SheetValue) -> Boolean = { true },
    onReady: (SheetState, SheetDismissRequest) -> Unit,
) {
    MeloraTheme {
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = confirmValueChange,
        )
        val dismiss = rememberSheetDismiss(sheetState)
        SideEffect { onReady(sheetState, dismiss) }

        if (mounted) {
            MeloraBottomSheet(
                onDismissRequest = {},
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Box(Modifier.size(240.dp).testTag(SHEET_CONTENT_TAG))
            }
        }
    }
}
