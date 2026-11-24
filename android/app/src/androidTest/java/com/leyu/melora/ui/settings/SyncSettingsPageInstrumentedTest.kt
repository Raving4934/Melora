package com.leyu.melora.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.leyu.melora.playback.BackupDestination
import com.leyu.melora.playback.WebDavConfig
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.theme.MeloraTheme
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSettingsPageInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun openPage() {
        compose.setContent { MeloraTheme { SyncSettingsSubPage {} } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("配置") and isEnabled() and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dismissSheet() {
        compose.onNode(
            hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
        ).performSemanticsAction(SemanticsActions.Dismiss)
    }

    @Test
    fun backupsRestoreAndAutomaticHaveDistinctDestinationMenus() {
        openPage()
        fun open(title: String) {
            compose.waitUntil { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
        }
        open("备份")
        listOf("备份至本地", "备份至网络", "备份至本地与网络").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        dismissSheet()
        open("恢复")
        listOf("本地恢复", "网络恢复").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        dismissSheet()
        open("自动备份")
        val option = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        listOf("备份至本地与网络", "备份至本地", "备份至网络", "关闭").forEach {
            compose.onNode(hasText(it) and option).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun backupSheetDisablesUnconfiguredNetworkAndReturnsTheChosenDestination() {
        var selected: BackupDestination? = null
        compose.setContent {
            MeloraTheme { BackupDestinationSheet(restore = false, networkReady = false, onDismiss = {}) { selected = it } }
        }
        compose.onNodeWithText("备份至网络").assertIsNotEnabled()
        compose.onNodeWithText("备份至本地与网络").assertIsNotEnabled()
        compose.onNodeWithText("备份至本地").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { selected == BackupDestination.LOCAL }
    }

    @Test
    fun slowNetworkRestoreKeepsTheDestinationSheetUntilDismissed() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val visible = mutableStateOf(true)
            val config = WebDavConfig("http://127.0.0.1:${server.localPort}/")
            compose.setContent {
                MeloraTheme {
                    if (visible.value) BackupDestinationSheet(
                        restore = true, networkReady = true, networkRestoreConfig = config,
                        onDismiss = { visible.value = false },
                    ) { error("读取列表时不应关闭抽屉") }
                }
            }
            val originalSheet = compose.onNode(isDialog()).fetchSemanticsNode().id
            compose.onNodeWithText("网络恢复").performSemanticsAction(SemanticsActions.OnClick)
            val progress = hasContentDescription("正在读取网络备份")
            compose.waitUntil(5_000) { compose.onAllNodes(progress).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(originalSheet, compose.onNode(isDialog()).fetchSemanticsNode().id)
            dismissSheet()
            compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun serverEditorIsCollapsedUntilOpenedAndDoesNotSaveOnCancel() {
        compose.setContent { MeloraTheme { WebDavConnectionEntry(null, ready = true) { error("意外保存") } } }
        compose.onNodeWithText("服务器地址").assertDoesNotExist()
        compose.onNodeWithText("配置").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("WebDAV 配置").isDisplayed() }
        compose.onNodeWithText("测试配置").assertIsNotEnabled()
        compose.onNodeWithText("保存配置").assertIsNotEnabled()
        compose.onNode(hasContentDescription("关闭 WebDAV 配置")).assertDoesNotExist()
        dismissSheet()
        compose.waitUntil { compose.onAllNodes(hasText("WebDAV 配置")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun serverEditorMasksPasswordAndValidatesTheCurrentDraft() {
        compose.setContent { MeloraTheme { WebDavConnectionEntry(null, ready = true) { error("意外保存") } } }
        compose.onNodeWithText("配置").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("WebDAV 配置").isDisplayed() }

        fun field(label: String) = compose.onNode(hasContentDescription(label) and hasSetTextAction())
        fun checkPasswordDisplay(expected: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            field("密码").performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(expected, layouts.single().layoutInput.text.text)
        }

        field("服务器地址").performTextReplacement("https://example.invalid/dav/")
        val password = "test-password"
        field("密码").performScrollTo().performTextReplacement(password)
        val masked = "•".repeat(password.length)
        checkPasswordDisplay(masked)
        compose.onNode(hasContentDescription("显示密码")).performClick()
        checkPasswordDisplay(password)
        compose.onNode(hasContentDescription("隐藏密码")).performClick()
        checkPasswordDisplay(masked)

        compose.onNodeWithText("测试配置").assertIsEnabled().performClick()
        val accountError = compose.onNodeWithText("请输入 WebDAV 账号")
        compose.waitUntil(5_000) { accountError.isDisplayed() }

        field("子文件夹名称").performScrollTo().performTextReplacement("invalid/folder")
        field("账号").performScrollTo().performTextReplacement("test-user")
        accountError.assertDoesNotExist()
        compose.onNodeWithText("保存配置").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            compose.onNodeWithText("子文件夹名称须为单个目录名，最多 64 个字符").isDisplayed()
        }
    }
}
