package com.leyu.melora.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorStateRetryLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingRetryKeepsErrorAndButtonPositionAndRejectsRepeatedTaps() {
        val pending = mutableStateOf(false)
        var requests = 0
        val message = "暂时无法加载，请稍后重试"
        compose.setContent {
            MaterialTheme {
                ErrorState(message, retrying = pending.value, onRetry = {
                    requests++
                    pending.value = true
                })
            }
        }
        val originalMessage = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        val originalButton = compose.onNodeWithText("点击重试").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("点击重试").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        val retry = compose.onNodeWithText("正在重试…").assertIsNotEnabled()
        assertEquals(originalMessage, compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot)
        assertEquals(originalButton.top, retry.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(originalButton.center.x, retry.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        retry.performTouchInput { repeat(10) { click() } }
        compose.runOnIdle { assertEquals(1, requests); pending.value = false }
        compose.onNodeWithText("点击重试").performClick()
        compose.runOnIdle { assertEquals(2, requests) }
    }
}
