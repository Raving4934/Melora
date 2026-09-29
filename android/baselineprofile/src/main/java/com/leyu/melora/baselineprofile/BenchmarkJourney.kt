package com.leyu.melora.baselineprofile

import android.graphics.Rect
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

internal const val PACKAGE_NAME = "com.leyu.melora.benchmark"
internal val MAIN_PAGES = listOf("排行榜", "发现", "歌单", "听书", "我的列表")

internal fun MacrobenchmarkScope.openMainPage(label: String) {
    clickStable(By.desc("打开侧栏"))
    clickStable(By.text(label))
    check(device.wait(Until.gone(By.text("乐屿 · Melora")), UI_TIMEOUT_MS)) { "侧栏未收回：$label" }
    check(device.wait(Until.hasObject(By.desc("打开侧栏")), UI_TIMEOUT_MS)) { "未返回主页面：$label" }
    device.waitForIdle()
}

/** 标签可能只是菜单项的子节点；等待实际点击区域停止移动，避免点击侧栏入场动画中的旧坐标。 */
private fun MacrobenchmarkScope.clickStable(selector: BySelector) {
    val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
    var previous: Rect? = null
    var stableSince = 0L
    while (SystemClock.elapsedRealtime() < deadline) {
        val target = device.findObject(selector)?.let { node ->
            generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        }
        val bounds = target?.visibleBounds
        val now = SystemClock.elapsedRealtime()
        if (bounds == null || bounds.isEmpty || bounds != previous) {
            previous = bounds
            stableSince = now
        } else if (now - stableSince >= 200L) {
            target.click()
            return
        }
        SystemClock.sleep(50)
    }
    error("点击目标未稳定：$selector")
}

private const val UI_TIMEOUT_MS = 3_000L
