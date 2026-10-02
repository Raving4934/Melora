package com.leyu.melora.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

/** 节点查找会各自推进测试时钟；坐标必须最后在同一次主线程任务中读取。 */
internal fun ComposeTestRule.boundsInSameFrame(vararg nodes: SemanticsNodeInteraction): List<Rect> {
    val semantics = nodes.map { it.fetchSemanticsNode() }
    return runOnIdle { semantics.map { it.boundsInRoot } }
}

/** 自动聚焦的编辑弹窗先等真实IME出现，不能把弹出前的短暂停顿当成布局结束。 */
internal fun ComposeTestRule.awaitIme(node: SemanticsNodeInteraction) {
    val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
    waitUntil(5_000) {
        runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    }
}
