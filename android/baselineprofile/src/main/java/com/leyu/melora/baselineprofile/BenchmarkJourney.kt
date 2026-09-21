package com.leyu.melora.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val PACKAGE_NAME = "com.leyu.melora.benchmark"
internal val MAIN_PAGES = listOf("排行榜", "发现", "歌单", "听书", "我的列表")

internal fun MacrobenchmarkScope.openMainPage(label: String) {
    val menu = requireNotNull(device.wait(Until.findObject(By.desc("打开侧栏")), UI_TIMEOUT_MS)) {
        "未找到侧栏入口"
    }
    menu.click()
    device.waitForIdle()

    val entry = requireNotNull(device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MS)) {
        "侧栏中未找到页面：$label"
    }
    entry.click()
    device.waitForIdle()
}

private const val UI_TIMEOUT_MS = 3_000L
