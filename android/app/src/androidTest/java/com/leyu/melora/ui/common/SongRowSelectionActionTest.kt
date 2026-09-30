package com.leyu.melora.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongRowSelectionActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptedCrossfadeKeepsTheSlotAndNeverClicksThroughToMore() {
        val selection = mutableStateOf(false)
        var more = 0
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                SongRowSelectionAction(selection.value, true, { more++ }, { toggles++ }, Modifier.testTag("action"))
            }
        }
        val slot = compose.onNodeWithTag("action", useUnmergedTree = true)
        slot.assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        val bounds = slot.fetchSemanticsNode().boundsInRoot
        slot.performTouchInput { click(center) }
        assertEquals(1, more)
        compose.mainClock.autoAdvance = false
        try {
            repeat(3) {
                compose.runOnIdle { selection.value = true }
                compose.mainClock.advanceTimeBy(64)
                slot.performTouchInput { click(center) }
                assertEquals(it + 1, toggles)
                assertEquals(it + 1, more)
                compose.runOnIdle { selection.value = false }
                compose.mainClock.advanceTimeBy(32)
                slot.performTouchInput { click(center) }
                assertEquals(it + 2, more)
                assertEquals(it + 1, toggles)
                assertEquals(bounds, slot.fetchSemanticsNode().boundsInRoot)
            }
            compose.mainClock.advanceTimeBy(250)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun slotWithoutMoreKeepsItsSizeAndOnlyTogglesInSelectionMode() {
        val selection = mutableStateOf(false)
        var toggles = 0
        var rowClicks = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.clickable { rowClicks++ }) {
                    SongRowSelectionAction(selection.value, false, null, { toggles++ }, Modifier.testTag("action"))
                }
            }
        }
        val slot = compose.onNodeWithTag("action", useUnmergedTree = true)
        slot.assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        slot.performTouchInput { click(center) }
        assertEquals(0, toggles)
        assertEquals(1, rowClicks)
        compose.runOnIdle { selection.value = true }
        compose.waitForIdle()
        slot.performTouchInput { click(center) }
        assertEquals(1, toggles)
        assertEquals(1, rowClicks)
        compose.runOnIdle { selection.value = false }
        compose.waitForIdle()
        slot.assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        slot.performTouchInput { click(center) }
        assertEquals(1, toggles)
        assertEquals(2, rowClicks)
    }
}
