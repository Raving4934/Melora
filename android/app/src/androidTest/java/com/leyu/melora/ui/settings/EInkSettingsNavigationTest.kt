package com.leyu.melora.ui.settings

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.RecordConflict
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.MeloraTheme
import com.leyu.melora.ui.theme.WithMeloraSheetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EInkSettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun togglingEInkKeepsTheCurrentSettingsPageOpen() {
        val original = MeloraSettings.eInkMode.value
        try {
            MeloraSettings.updateEInkMode(false)
            compose.setContent { MeloraTheme { SettingsMasterScreen() } }
            val entry = hasText("其他设置") and hasClickAction()
            compose.onNode(hasScrollAction()).performScrollToNode(entry)
            val row = compose.onNode(entry)
            compose.awaitStable(row)
            row.performClick()
            val page = compose.onNodeWithText("显示适配")
            val mode = compose.onNodeWithContentDescription("墨水屏模式")
            compose.awaitStable(mode)
            mode.assertIsOff()

            for (enabled in listOf(true, false)) {
                mode.assertIsEnabled().performTouchInput { click() }
                compose.runOnIdle {
                    assertEquals("点击开关应更新设置值", enabled, MeloraSettings.eInkMode.value)
                }
                compose.waitUntil(5_000) { MeloraAppearance.isEInk == enabled }
                compose.awaitStable(mode)
                page.assertIsDisplayed()
                if (enabled) mode.assertIsOn() else mode.assertIsOff()
            }
        } finally {
            MeloraSettings.updateEInkMode(original)
        }
    }

    @Test fun togglingEInkKeepsAnOpenSheetAndItsPendingChoice() {
        val original = MeloraSettings.eInkMode.value
        val conflict = RecordConflict("playlist", "歌单名称", "夜跑", "运动")
        try {
            MeloraSettings.updateEInkMode(false)
            compose.setContent {
                MeloraTheme {
                    WithMeloraSheetTheme(darkTheme = true) {
                        RecordConflictSheet(listOf(conflict), onDismiss = {}) {
                            error("主题切换不应提交选择")
                        }
                    }
                }
            }
            val choice = compose.onNodeWithText(conflict.remoteLabel)
            compose.awaitStable(choice)
            choice.assertIsEnabled().performClick()
            choice.assertIsSelected()
            val dialog = compose.onNode(isDialog())
            val dialogId = dialog.fetchSemanticsNode().id

            for (enabled in listOf(true, false)) {
                compose.runOnIdle { MeloraSettings.updateEInkMode(enabled) }
                compose.waitUntil(5_000) { MeloraAppearance.isEInk == enabled }
                assertEquals(dialogId, dialog.fetchSemanticsNode().id)
                choice.assertIsSelected()
                compose.onNodeWithText("应用选择").assertIsEnabled()
            }
        } finally {
            MeloraSettings.updateEInkMode(original)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardFocusDrawsAnOutlineOnTheEInkSettingsRow() {
        val original = MeloraSettings.eInkMode.value
        val beforeRow = FocusRequester()
        lateinit var host: View
        lateinit var inputModeManager: InputModeManager
        var wasInTouchMode = false
        try {
            MeloraSettings.updateEInkMode(true)
            compose.setContent {
                host = LocalView.current
                inputModeManager = LocalInputModeManager.current
                MeloraTheme {
                    Column(Modifier.width(260.dp).background(Color.White)) {
                        Box(
                            Modifier.size(48.dp).testTag("before-settings-row")
                                .focusRequester(beforeRow).focusable(),
                        )
                        SettingsEntryItem(Icons.Outlined.Tune, Color.Black, "测试设置", onClick = {})
                    }
                }
            }
            compose.waitUntil(5_000) { MeloraAppearance.isEInk }
            compose.runOnIdle {
                wasInTouchMode = host.isInTouchMode
                host.requestFocusFromTouch()
            }
            compose.waitUntil(5_000) {
                compose.runOnUiThread { inputModeManager.inputMode == InputMode.Keyboard }
            }
            val start = compose.onNodeWithTag("before-settings-row")
            val row = compose.onNodeWithText("测试设置")
            compose.awaitStable(row)
            compose.runOnIdle { beforeRow.requestFocus() }
            start.assertIsFocused()
            row.assertIsNotFocused()

            fun hasVisibleOutline(): Boolean {
                val pixels = row.captureToImage().toPixelMap()
                // 横向中线的顶部只包含背景和焦点边框，避开图标与正文。
                return (0 until pixels.height / 6).any { y ->
                    pixels[pixels.width / 2, y].luminance() < 0.20f
                }
            }

            assertFalse(hasVisibleOutline())
            start.performKeyInput { pressKey(Key.Tab) }
            row.assertIsFocused()
            assertTrue("获得键盘焦点的设置行顶部应绘制清晰边框", hasVisibleOutline())
        } finally {
            if (wasInTouchMode) InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            MeloraSettings.updateEInkMode(original)
        }
    }
}
