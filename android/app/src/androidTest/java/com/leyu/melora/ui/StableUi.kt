package com.leyu.melora.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals

/** 原生窗口与 Compose 动画使用不同的空闲信号；等待真实几何稳定，不关闭生产动效。 */
internal fun ComposeTestRule.awaitStable(tag: String) = awaitStable(onNodeWithTag(tag))

internal fun ComposeTestRule.awaitStable(node: SemanticsNodeInteraction): Rect {
    var previous: Rect? = null
    var stableSince = 0L
    waitUntil(5_000) {
        val bounds = runCatching {
            val semantics = node.assertIsDisplayed().fetchSemanticsNode()
            runOnUiThread { semantics.boundsInRoot.takeUnless { it.isEmpty } }
        }.getOrNull()
        val now = SystemClock.uptimeMillis()
        if (bounds == null || bounds != previous) {
            previous = bounds
            stableSince = now
            false
        } else now - stableSince >= 128L
    }
    return checkNotNull(previous)
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

/** 屏幕回到原配置后再交还方向策略，避免不同用例之间继承一次强制旋转。 */
internal class HostOrientationFixture<A : ComponentActivity>(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<A>, A>,
) {
    private data class Orientation(val configuration: Int, val requested: Int)
    private var original: Orientation? = null

    fun request(configuration: Int) {
        val requested = when (configuration) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> return
        }
        compose.activityRule.scenario.onActivity { activity ->
            original = original ?: Orientation(activity.resources.configuration.orientation, activity.requestedOrientation)
            activity.requestedOrientation = requested
        }
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == configuration }
    }

    fun restore() {
        val saved = original ?: return
        try {
            request(saved.configuration)
        } finally {
            original = null
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = saved.requested
                assertEquals("恢复 Activity 的方向策略", saved.requested, activity.requestedOrientation)
            }
        }
    }
}
