package com.leyu.melora.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.leyu.melora.playback.BackupDestination
import com.leyu.melora.playback.RecordConflict
import com.leyu.melora.playback.WebDavConfig
import com.leyu.melora.playback.WebDavConfigStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.ui.theme.MeloraTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
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
    fun recordConflictsAcceptPartialChoicesAndAllowDeferringTheRest() {
        val playlist = RecordConflict("playlist", "歌单名称", "夜跑", "运动")
        val book = RecordConflict("book", "听书断点", "第 33 章 · 03:15", "第 30 章 · 00:23")
        val conflicts = mutableStateOf(listOf(playlist, book))
        val visible = mutableStateOf(true)
        var submitted = emptyMap<String, Boolean>()
        compose.setContent {
            MeloraTheme {
                if (visible.value) RecordConflictSheet(conflicts.value, onDismiss = { visible.value = false }) { choices ->
                    submitted = choices
                    conflicts.value = conflicts.value.filterNot { it.key in choices }
                }
            }
        }

        val apply = compose.onNodeWithText("应用选择")
        apply.assertIsNotEnabled()
        compose.onNodeWithText(playlist.remoteLabel).performClick().assertIsSelected()
        apply.assertIsEnabled().performClick()
        compose.waitUntil(5_000) { conflicts.value == listOf(book) }
        compose.runOnIdle { assertEquals(mapOf(playlist.key to false), submitted) }
        compose.onNodeWithText(book.title).assertIsDisplayed()
        compose.onNodeWithText(book.localLabel).assertIsNotSelected()
        apply.assertIsNotEnabled()
        compose.onNodeWithText("稍后处理").performClick()
        compose.waitUntil(5_000) { !visible.value }
        compose.runOnIdle { assertEquals(listOf(book), conflicts.value) }
    }

    @Test
    fun recordConflictChoiceSurvivesAFailedAttemptAndClearsWhenCandidatesChange() {
        val original = RecordConflict("old", "歌单名称", "夜跑", "运动")
        val changed = original.copy(key = "changed", remoteLabel = "有氧运动")
        val conflicts = mutableStateOf(listOf(original))
        compose.setContent {
            MeloraTheme { RecordConflictSheet(conflicts.value, onDismiss = {}) { error("连接已断开") } }
        }

        val apply = compose.onNodeWithText("应用选择")
        compose.onNodeWithText(original.localLabel).performClick()
        apply.performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("连接已断开").isDisplayed() }
        compose.onNodeWithText(original.localLabel).assertIsSelected()
        apply.assertIsEnabled()
        compose.runOnIdle { conflicts.value = listOf(changed) }
        compose.onNodeWithText(changed.localLabel).assertIsNotSelected()
        compose.onNodeWithText(changed.remoteLabel).assertIsDisplayed()
        apply.assertIsNotEnabled()
    }

    @Test
    fun waitingForWebDavDoesNotDisableOrMoveTheLocalBackupSwitch() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = Files.createTempDirectory(target.cacheDir.toPath(), "sync-settings-").toFile()
        val fixture = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = storage
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${storage.name}.$name", mode)
        }
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                WebDavConfigStore.save(fixture, WebDavConfig("http://127.0.0.1:${server.localPort}/"))
                compose.setContent {
                    val activity = LocalContext.current
                    val context = remember(activity) {
                        object : ContextWrapper(activity) {
                            override fun getApplicationContext() = fixture
                        }
                    }
                    CompositionLocalProvider(LocalContext provides context) {
                        MeloraTheme { SyncSettingsSubPage {} }
                    }
                }

                val sync = hasContentDescription("同步我的记录")
                val keepLatest = compose.onNode(hasContentDescription("仅保留最新本地备份"))
                compose.waitUntil(10_000) { compose.onAllNodes(sync and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                val initialBounds = keepLatest.assertIsEnabled().fetchSemanticsNode().boundsInRoot
                compose.onNode(sync).performSemanticsAction(SemanticsActions.OnClick)
                compose.onNode(sync).assertIsNotEnabled()
                server.accept().use {
                    assertEquals(initialBounds, keepLatest.assertIsEnabled().fetchSemanticsNode().boundsInRoot)
                }
                compose.waitUntil(5_000) { compose.onAllNodes(sync and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            storage.deleteRecursively()
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
