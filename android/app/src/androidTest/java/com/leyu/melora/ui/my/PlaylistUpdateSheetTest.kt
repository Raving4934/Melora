package com.leyu.melora.ui.my

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.*
import com.leyu.melora.playback.sdk.*
import com.leyu.melora.ui.theme.MeloraTheme
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlaylistUpdateSheetTest {
    @get:Rule val compose = createComposeRule()
    private val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123&tracking=original-long-share-link-kept-exactly")
    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))
    private val local get() = UserLibrary.UserPlaylist("local", "手动名称", listOf(song("旧歌曲")), link, setOf("wy_旧歌曲"))
    private val result get() = PlaylistImportResult("网易云音乐", "远端名称", null,
        (1..80).map { song("新歌曲$it") }, 80, 0, null, link)
    private val commits = AtomicInteger()
    private val reads = AtomicInteger()
    private val dismissed = AtomicInteger()
    private fun show(
        playlist: UserLibrary.UserPlaylist = local,
        read: suspend () -> PlaylistImportResult = { result },
        save: suspend (PlaylistSyncPreview) -> UserLibrary.UserPlaylist = { it.updated },
    ) {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            MeloraTheme {
                if (open) PlaylistUpdateSheet(playlist, onDismiss = { open = false; dismissed.incrementAndGet() },
                    onUpdated = { open = false }, currentPlaylist = { playlist },
                    readPlaylist = { _, _, _ -> reads.incrementAndGet(); read() },
                    commit = { commits.incrementAndGet(); save(it) })
            }
        }
    }
    private fun clickRead() = compose.onNodeWithTag("playlist-sync-submit").performClick()
    private fun scrollToText(text: String) = compose.onNodeWithTag("playlist-sync-content").performScrollToNode(hasText(text))
    private fun waitPreview() = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("playlist-sync-preview").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun differenceListScrollsAndOnlyConfirmationWrites() {
        show(); clickRead(); waitPreview()
        assertEquals(0, commits.get())
        compose.onNodeWithTag("playlist-sync-content").performScrollToNode(hasTestTag("playlist-sync-rules-detail"))
        compose.onNodeWithTag("playlist-sync-rules-detail").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("确认更新").assertIsDisplayed()
        scrollToText("+ 新歌曲80")
        compose.onNodeWithText("+ 新歌曲80").assertIsDisplayed()
        scrollToText("− 旧歌曲")
        compose.onNodeWithText("− 旧歌曲").assertIsDisplayed()
        // Footer remains visible even when the diff is scrolled to its end.
        compose.onNodeWithText("确认更新").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { commits.get() == 1 }
    }

    @Test fun incompleteResultOffersRetryNeverConfirmation() {
        show(read = { if (reads.get() == 1) result.copy(warning = "分页失败") else result })
        clickRead()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-sync-error").assertIsDisplayed()
        compose.onNodeWithTag("playlist-sync-preview").assertDoesNotExist()
        assertEquals(0, commits.get())
        compose.onNodeWithText("重试读取").performClick()
        waitPreview(); assertEquals(2, reads.get())
    }

    @Test fun cancelDuringLoadingDoesNotSave() {
        val gate = CompletableDeferred<PlaylistImportResult>()
        show(read = { gate.await() }); clickRead()
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.onNodeWithTag("playlist-sync-submit").assertIsNotEnabled().performClick()
        assertEquals(1, reads.get())
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(5_000) { dismissed.get() == 1 }
        gate.complete(result)
        compose.waitForIdle(); assertEquals(0, commits.get())
    }

    @Test fun firstBindingExplainsSafeMergeAndRequiresLinkBeforeReading() {
        show(playlist = local.copy(importSource = null, lastSyncedUids = null))
        compose.onNodeWithTag("playlist-sync-submit").assertIsNotEnabled()
        compose.onNodeWithTag("playlist-sync-link").performTextInput(link.value)
        clickRead(); waitPreview()
        compose.onNodeWithText("首次绑定保留全部现有歌曲，仅合并远端内容；不会猜测旧歌单中哪些歌曲已被原平台删除。")
            .assertIsDisplayed()
        compose.onNodeWithText("确认绑定并更新").assertIsDisplayed().assertIsEnabled()
        assertEquals(0, commits.get())
    }

    @Test fun unchangedPreviewCompletesWithoutCommitting() {
        show(read = { result.copy(songs = local.songs, reportedTotal = 1) })
        clickRead(); waitPreview()
        compose.onNodeWithText("歌单已是最新").assertIsDisplayed()
        compose.onNodeWithText("取消").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { dismissed.get() == 1 }
        compose.onNodeWithTag("playlist-sync-sheet").assertDoesNotExist()
        assertEquals(0, commits.get())
    }

    @Test fun unchangedPreviewCanReadAgainWithoutCommitting() {
        show(read = { result.copy(songs = local.songs, reportedTotal = 1) })
        clickRead(); waitPreview()
        compose.onNodeWithText("重新读取").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }
        waitPreview()
        assertEquals(0, commits.get())
        assertEquals(0, dismissed.get())
    }

    @Test fun rulesAreAlwaysVisibleWithoutDisclosureWhileOriginalSourceStaysCollapsibleAndCopyable() {
        show()
        compose.onNodeWithText(link.value).assertDoesNotExist()
        compose.onNodeWithTag("playlist-sync-rules-detail").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("更新规则").assertDoesNotExist()
        compose.onNodeWithText("来源详情").performClick()
        scrollToText(link.value)
        compose.onNodeWithText(link.value).assertIsDisplayed()
        scrollToText("复制链接")
        compose.onNodeWithText("复制链接").performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(link.value, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        scrollToText("来源详情")
        compose.onNodeWithText("来源详情").performClick()
        compose.onNodeWithText(link.value).assertDoesNotExist()
        compose.onNodeWithTag("playlist-sync-content").performScrollToNode(hasTestTag("playlist-sync-rules-detail"))
        compose.onNodeWithTag("playlist-sync-rules-detail").assertIsDisplayed().assertHasNoClickAction()
        compose.onAllNodesWithTag("playlist-sync-rules-detail").assertCountEquals(1)
        compose.onNodeWithTag("playlist-sync-rules-detail").assertTextEquals(PLAYLIST_UPDATE_RULES)
    }

    @Test fun savingDisablesBothActionsAndCannotCommitTwice() {
        val gate = CompletableDeferred<Unit>()
        show(save = { gate.await(); it.updated }); clickRead(); waitPreview()
        compose.onNodeWithText("确认更新").performClick()
        compose.waitUntil(5_000) { commits.get() == 1 }
        compose.onNodeWithTag("playlist-sync-submit").assertIsNotEnabled().performClick()
        compose.onNodeWithText("重新读取").assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, commits.get())
        assertEquals(1, reads.get())
        assertEquals(0, dismissed.get())
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-sheet").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun saveErrorRemainsVisibleAndOffersRetrySave() {
        show(save = { if (commits.get() == 1) throw java.io.IOException("磁盘已满") else it.updated })
        clickRead(); waitPreview()
        scrollToText("− 旧歌曲")
        compose.onNodeWithText("确认更新").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-sync-error").assertIsDisplayed()
        compose.onNodeWithText("重试保存").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { commits.get() == 2 }
        assertEquals(1, reads.get())
    }
}
