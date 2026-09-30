package com.leyu.melora.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.ApkUpdatePhase
import com.leyu.melora.playback.ApkUpdateState
import com.leyu.melora.playback.UpdateResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkUpdatePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun progressCancelAndRetryKeepReleaseNotesAndPrimaryButtonBounds() {
        val release = UpdateResult(true, "android-v0.1.7", "0.1.6", "本次更新说明", "https://example.test/update.apk", "https://example.test/releases")
        val state = mutableStateOf(ApkUpdateState(ApkUpdatePhase.Available, release))
        var downloads = 0
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                ApkUpdatePanel(state.value,
                    onDownload = { downloads++; state.value = state.value.copy(phase = ApkUpdatePhase.Downloading, bytes = 128, totalBytes = null) },
                    onCancel = { cancellations++; state.value = state.value.copy(phase = ApkUpdatePhase.Available) },
                    onInstall = {}, onCheck = {}, onReleases = {},
                )
            }
        }
        val initial = compose.onNodeWithText("下载 Android APK").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("下载 Android APK").performClick()
        val pending = compose.onNodeWithText("正在下载…").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        assertEquals(initial.top, pending.top, 1f)
        assertEquals(initial.height, pending.height, 1f)
        compose.onNodeWithText("本次更新说明").assertIsDisplayed()
        compose.onNodeWithText("关闭弹层即取消，不在后台下载。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
        compose.onNodeWithText("下载 Android APK").performClick()
        compose.runOnIdle {
            assertEquals(2, downloads)
            state.value = state.value.copy(phase = ApkUpdatePhase.Failed, message = "连接中断，请重试")
        }
        val retry = compose.onNodeWithText("重新下载").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.top, retry.top, 1f)
        assertEquals(initial.height, retry.height, 1f)
        compose.onNodeWithText("本次更新说明").assertIsDisplayed()
        compose.onNodeWithText("重新下载").performClick()
        compose.runOnIdle { assertEquals(3, downloads) }
    }
}
