package com.leyu.melora.ui.my

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.*
import com.leyu.melora.playback.sdk.*
import com.leyu.melora.ui.theme.MeloraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlaylistImportDuplicateSheetTest {
    @get:Rule val compose = createComposeRule()
    private val source = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123&original=1")
    private val alias = PlaylistImportLink.parse("https://music.163.com/playlist/123?changed=2")
    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))
    private val original = UserLibrary.UserPlaylist("first", "手改名称", listOf(song("old")), source, setOf("wy_old"))
    private val loaded = PlaylistImportResult("网易云音乐", "新远端名称", null, listOf(song("new")), 1, 0, null, alias)
    private val library = MutableStateFlow(listOf(original))
    private val creates = AtomicInteger()
    private val commits = AtomicInteger()
    private val updates = AtomicInteger()
    private val imports = AtomicInteger()
    private var savedAsCopy = false
    private var savedName = ""
    private var updatedId = ""

    private fun show(race: Boolean = false, warning: String? = null) {
        if (race) library.value = emptyList()
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            MeloraTheme {
                if (open) PlaylistImportSheet(
                    onDismiss = { open = false },
                    onImported = { imports.incrementAndGet(); open = false },
                    onUpdated = { updates.incrementAndGet(); updatedId = it.id; open = false },
                    library = library,
                    readPlaylist = { _, text, _ -> loaded.copy(importSource = PlaylistImportLink.parse(text), warning = warning) },
                    savePlaylist = { name, result, copy ->
                        creates.incrementAndGet()
                        if (race && !copy) {
                            library.value = listOf(original)
                            throw PlaylistAlreadyImportedException(listOf(original))
                        }
                        savedAsCopy = copy; savedName = name
                        UserLibrary.UserPlaylist("copy", name, result.songs, result.importSource)
                    },
                    commitUpdate = { preview ->
                        commits.incrementAndGet()
                        assertEquals(source, preview.updated.importSource)
                        assertEquals("手改名称", preview.updated.name)
                        preview.updated
                    },
                )
            }
        }
        compose.onNodeWithTag("playlist-import-link").performTextInput(alias.value)
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-import-name").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun duplicateRoutesToActualUpdateSheetAndOnlyDiffConfirmationUpdates() {
        show()
        compose.onNodeWithText("已导入此来源").assertExists()
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("playlist-import-sheet").assertDoesNotExist()
        compose.onNodeWithTag("playlist-sync-sheet").assertExists()
        assertEquals(0, creates.get()); assertEquals(0, commits.get())
        compose.onNodeWithTag("playlist-sync-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-preview").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, commits.get())
        compose.onNodeWithText("确认更新").performClick()
        compose.waitUntil(5_000) { updates.get() == 1 }
        assertEquals("first", updatedId)
        assertEquals(0, imports.get()); assertEquals(0, creates.get()); assertEquals(1, commits.get())
    }

    @Test fun explicitCopyKeepsEditedNameAndDoesNotUpdateExisting() {
        show()
        compose.onNodeWithTag("playlist-import-name").performScrollTo().performTextReplacement("我的副本")
        compose.onNodeWithTag("playlist-import-copy").performScrollTo().performClick()
        compose.waitUntil(5_000) { imports.get() == 1 }
        assertTrue(savedAsCopy); assertEquals("我的副本", savedName)
        assertEquals(1, creates.get()); assertEquals(0, updates.get()); assertEquals(0, commits.get())
    }

    @Test fun cancelDuplicateCreatesNothing() {
        show()
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.onNodeWithTag("playlist-import-sheet").assertDoesNotExist()
        assertEquals(0, creates.get()); assertEquals(0, commits.get())
    }

    @Test fun multipleCopiesRequireSelectionAndUseTheChosenTarget() {
        library.value = listOf(original, original.copy(id = "second", songs = original.songs + song("extra")))
        show()
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        compose.onNodeWithTag("playlist-import-target-second").performScrollTo().performClick()
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("playlist-sync-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认更新").performClick()
        compose.waitUntil(5_000) { updates.get() == 1 }
        assertEquals("second", updatedId); assertEquals(0, creates.get())
    }

    @Test fun duplicateDiscoveredAtSaveKeepsPreviewAndOffersUpdateInsteadOfSuccess() {
        show(race = true)
        compose.onNodeWithTag("playlist-import-name").performScrollTo().performTextReplacement("race draft")
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已导入此来源").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-name").assertTextEquals("race draft")
        assertEquals(0, imports.get()); assertEquals(1, creates.get())
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.onNodeWithTag("playlist-sync-sheet").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, commits.get()); assertEquals(0, updates.get())
    }

    @Test fun changedSourceAfterRoutingCannotBecomeAnUpdateOfAnotherRemotePlaylist() {
        show()
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.runOnIdle {
            library.value = listOf(original.copy(importSource = PlaylistImportLink.parse("https://music.163.com/playlist?id=999")))
        }
        compose.onNodeWithTag("playlist-sync-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-sync-preview").assertDoesNotExist()
        assertEquals(0, commits.get()); assertEquals(0, creates.get())
    }

    @Test fun deletedSelectedCopyDoesNotSilentlyUpdateTheRemainingOne() {
        library.value = listOf(original, original.copy(id = "second"))
        show()
        compose.onNodeWithTag("playlist-import-target-second").performScrollTo().performClick()
        compose.runOnIdle { library.value = listOf(original) }
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        assertEquals(0, creates.get()); assertEquals(0, commits.get())
    }

    @Test fun partialCopyIsExplicitAndDuplicateUpdateStillRejectsPartialReads() {
        show(warning = "分页未完整")
        compose.onNodeWithTag("playlist-import-copy").assertTextContains("仅将已获取的 1 首另存为副本")
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.onNodeWithTag("playlist-sync-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-sync-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-sync-preview").assertDoesNotExist()
        assertEquals(0, commits.get()); assertEquals(0, creates.get())
    }
}
