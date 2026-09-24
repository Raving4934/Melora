package com.leyu.melora.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/** 原生窗口与 Compose 动画使用不同的空闲信号；等待真实几何稳定，不关闭生产动效。 */
internal fun ComposeTestRule.awaitStable(tag: String) = awaitStable(onNodeWithTag(tag))

internal fun ComposeTestRule.awaitStable(node: SemanticsNodeInteraction) {
    var previous: Rect? = null
    var stableSince = 0L
    waitUntil(5_000) {
        val bounds = runCatching { node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot }.getOrNull()
        val now = SystemClock.uptimeMillis()
        if (bounds == null || bounds != previous) {
            previous = bounds
            stableSince = now
            false
        } else now - stableSince >= 128L
    }
}
