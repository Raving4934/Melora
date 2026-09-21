package com.leyu.melora.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun switchMainPages() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        },
    ) {
        MAIN_PAGES.forEach(::openMainPage)
    }

    /** 隔离包中固定300首收藏/100首最近及24张本地封面，排除网络波动。 */
    @Test
    fun libraryDetailRoundTrips() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 3),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait(Intent().setClassName(PACKAGE_NAME, "com.leyu.melora.MainActivity").putExtra("tab", "6"))
            val seeded = device.executeShellCommand("content call --uri content://$PACKAGE_NAME.fixture --method seed")
            check(seeded.contains("songs=300")) { "测试目录初始化失败：$seeded" }
            device.waitForIdle()
        },
    ) {
        listOf("收藏" to "我的收藏", "最近" to "最近播放").forEach { (entry, title) ->
            requireNotNull(device.wait(Until.findObject(By.text(entry)), 3_000L)).click()
            check(device.wait(Until.hasObject(By.text(title)), 3_000L)) { "未进入$title" }
            device.waitForIdle()
            device.pressBack()
            check(device.wait(Until.hasObject(By.text("我的列表")), 3_000L)) { "返回后目录页丢失" }
            device.waitForIdle()
        }
    }

}
