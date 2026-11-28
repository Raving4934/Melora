package com.leyu.melora.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class BasicSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @SdkSuppress(minSdkVersion = 33)
    @Test fun blurModesDisplayAndApplyOnANarrowScreen() {
        val originalBlurMode = MeloraSettings.componentBlurMode.value
        try {
            MeloraSettings.updateComponentBlurMode(ComponentBlurMode.Off)
            showBasicSettings(fontScale = 2f)

            val activity = compose.activity
            val blurSetting = hasText("模糊组件") and hasText("需要 Android 13 及以上版本")
            val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
            val modes = listOf(
                "模糊标题栏" to ComponentBlurMode.Header,
                "模糊所有组件" to ComponentBlurMode.All,
                "关闭" to ComponentBlurMode.Off,
            )
            var selected = "关闭"
            for ((label, mode) in modes) {
                val row = blurSetting and hasText(selected)
                scrollTo(row)
                val trigger = compose.onNode(row).performScrollTo()
                compose.awaitStable(trigger)
                compose.waitUntil("模糊设置行可接收触摸", 5_000) {
                    compose.runOnUiThread { activity.hasWindowFocus() }
                }
                trigger.performClick()
                val popup = compose.onNode(isPopup())
                val popupView = (popup.fetchSemanticsNode().root as ViewRootForTest).view
                modes.forEach { (option, _) ->
                    compose.awaitStable(compose.onNode(radio and hasText(option)))
                }
                compose.onNode(radio and hasText(selected)).assertIsSelected()
                val destination = compose.onNode(radio and hasText(label)).performScrollTo()
                compose.awaitStable(destination)
                compose.waitUntil("实际模糊选项窗口获焦", 5_000) {
                    compose.runOnUiThread { popupView.hasWindowFocus() }
                }
                destination.assertIsDisplayed().performTouchInput { click() }
                compose.runOnIdle {
                    assertEquals("触摸「$label」应应用对应模式", mode, MeloraSettings.componentBlurMode.value)
                }
                compose.waitUntil("选择「$label」后移除模糊选项窗口", 5_000) {
                    compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty()
                }
                compose.waitUntil("选择「$label」后设置页重新获焦", 5_000) {
                    compose.runOnUiThread { activity.hasWindowFocus() }
                }
                selected = label
                val selectedRow = blurSetting and hasText(label)
                compose.onNode(selectedRow).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText(label), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val lastLine = layout.lineCount - 1
                // 语义回调按最大可用宽度重建段落，短文本控件仍按自然宽度测量。
                assertFalse("$label 的行高不应被截断", layout.didOverflowHeight)
                assertFalse("$label 不应显示省略号", layout.isLineEllipsized(lastLine))
                assertEquals(label.length, layout.getLineEnd(lastLine, visibleEnd = true))
            }
        } finally {
            MeloraSettings.updateComponentBlurMode(originalBlurMode)
        }
    }

    @SdkSuppress(maxSdkVersion = 32)
    @Test fun unsupportedSystemShowsBlurOffAndKeepsTheStoredMode() {
        val originalBlurMode = MeloraSettings.componentBlurMode.value
        try {
            MeloraSettings.updateComponentBlurMode(ComponentBlurMode.All)
            showBasicSettings()
            val row = hasText("模糊组件") and hasText("需要 Android 13 及以上版本") and hasText("关闭")
            scrollTo(row)
            compose.onNode(row).assertIsNotEnabled()
            assertEquals(ComponentBlurMode.All, MeloraSettings.componentBlurMode.value)
        } finally {
            MeloraSettings.updateComponentBlurMode(originalBlurMode)
        }
    }

    @Test fun behaviorSectionRetainsTheStartupPageVisibilityRule() {
        val original = MeloraSettings.restoreLastPage.value
        try {
            MeloraSettings.updateRestoreLastPage(true)
            showBasicSettings()
            scrollTo(hasText("行为"))
            compose.onNode(hasText("行为")).assertIsDisplayed()
            scrollTo(hasText("启动时恢复上次页面"))
            compose.onNode(hasText("启动页面")).assertDoesNotExist()
            val restore = compose.onNodeWithContentDescription("启动时恢复上次页面")
            restore.assertIsOn().performClick()
            scrollTo(hasText("启动页面"))
            compose.onNode(hasText("启动页面")).assertIsDisplayed()
            scrollTo(hasText("显示退出按钮"))
            compose.onNodeWithContentDescription("显示退出按钮").assertIsDisplayed()
            scrollTo(hasText("下拉刷新"))
            compose.onNodeWithContentDescription("下拉刷新").assertIsDisplayed()
            scrollTo(hasText("启动时恢复上次页面"))
            restore.assertIsOff().performClick()
            compose.onNode(hasText("启动页面")).assertDoesNotExist()
        } finally {
            MeloraSettings.updateRestoreLastPage(original)
        }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    private fun showBasicSettings(fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MeloraTheme {
                    Box(Modifier.width(320.dp)) {
                        BasicSettingsSubPage(onBack = {})
                    }
                }
            }
        }
    }
}
