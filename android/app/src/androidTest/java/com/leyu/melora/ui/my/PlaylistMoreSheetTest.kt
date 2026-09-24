package com.leyu.melora.ui.my

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistMoreSheetTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<String>()

    private fun show(empty: Boolean = false, compact: Boolean = false) {
        val songs = if (empty) emptyList() else listOf(OnlineSong(JSONObject()
            .put("source", "fixture").put("songmid", "1").put("name", "测试歌曲")))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides if (compact) Density(5f) else LocalDensity.current) {
                MeloraTheme {
                    PlaylistMoreSheet(UserLibrary.UserPlaylist("fixture", "测试歌单", songs),
                        onDismiss = { actions += "dismiss" }, onPlayAll = { actions += "play" },
                        onAddToQueue = { actions += "append" }, onRename = { actions += "rename" },
                        onDelete = { actions += "delete" })
                }
            }
        }
    }

    @Test fun appendActionFollowsPlayAllAndDoesNotPlayOrEditPlaylist() {
        show()
        compose.waitForIdle()
        val play = compose.onNodeWithText("播放全部").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val append = compose.onNodeWithText("添加全部歌曲到播放队列").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(append.top > play.top)
        compose.onNodeWithText("添加全部歌曲到播放队列").performClick()
        compose.runOnIdle { assertEquals(listOf("dismiss", "append"), actions) }
    }

    @Test fun emptyPlaylistKeepsRenameAndDeleteWithoutQueueActions() {
        show(empty = true)
        compose.onNodeWithText("播放全部").assertDoesNotExist()
        compose.onNodeWithText("添加全部歌曲到播放队列").assertDoesNotExist()
        compose.onNodeWithText("重命名歌单").assertIsDisplayed()
        compose.onNodeWithText("删除歌单").assertIsDisplayed()
    }

    @Test fun playAllStillUsesItsOwnAction() {
        show()
        compose.onNodeWithText("播放全部").performClick()
        compose.runOnIdle { assertEquals(listOf("dismiss", "play"), actions) }
    }

    @Test fun shorterViewportCanScrollToTheLastAction() {
        show(compact = true)
        compose.onNodeWithText("删除歌单").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("dismiss", "delete"), actions) }
    }
}
