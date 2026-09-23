package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 同一个组合中切换真实的空/非空播放状态，覆盖共享封面交接和再次进入。 */
@RunWith(AndroidJUnit4::class)
class PlayerEmptyStateTest {
    @get:Rule val compose = createComposeRule()
    private val old = UiTrack("empty-test-a", "清空前的歌曲", "测试歌手", "测试专辑")
    private val next = UiTrack("empty-test-b", "新加入的歌曲", "测试歌手", "测试专辑")
    private val state = mutableStateOf(PlayerUiState())

    private fun playing(track: UiTrack) = PlayerUiState(ready = true, current = track, queue = listOf(track), currentIndex = 0)

    private fun show() {
        state.value = playing(old)
        val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))
        compose.setContent {
            CompositionLocalProvider(LocalSongListState provides shared) {
                MeloraTheme { ContinuousPlayerSheet(state.value, Modifier.fillMaxSize()) }
            }
        }
    }

    private fun expand(title: String) {
        compose.onAllNodesWithText(title, substring = true).onLast().performClick()
        compose.onNodeWithTag("player-pages").assertIsDisplayed()
    }

    private fun assertEmpty() {
        compose.onAllNodesWithText(old.title, substring = true).assertCountEquals(0)
        compose.onNodeWithTag("player-artwork").assertDoesNotExist()
        compose.onNodeWithTag("player-pages").assertDoesNotExist()
        compose.onNodeWithText("未在播放").assertDoesNotExist()
    }

    @Test fun clearingCollapsedQueueRemovesOldMiniPlayer() {
        show()
        compose.runOnIdle { state.value = PlayerUiState(ready = true) }
        assertEmpty()
    }

    @Test fun clearingExpandedQueueRemovesAllOldArtworkAndNewSongCanOpen() {
        show()
        expand(old.title)
        compose.runOnIdle { state.value = PlayerUiState(ready = true) }
        assertEmpty()
        compose.runOnIdle { state.value = playing(next) }
        expand(next.title)
        compose.onNodeWithTag("player-artwork").assertContentDescriptionEquals("${next.title}，${next.artist}")
        compose.onAllNodesWithText(old.title, substring = true).assertCountEquals(0)
        compose.onNodeWithText("未在播放").assertDoesNotExist()
    }

    @Test fun clearingDuringExpansionDoesNotLeaveMorphArtworkBehind() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText(old.title, substring = true).onLast().performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnUiThread { state.value = PlayerUiState(ready = true) }
        compose.mainClock.autoAdvance = true
        assertEmpty()
    }

    @Test fun replacingCurrentTrackKeepsFullPlayerOpenWithoutEmptyState() {
        show()
        expand(old.title)
        compose.runOnIdle { state.value = playing(next) }
        compose.onNodeWithTag("player-pages").assertIsDisplayed()
        compose.onNodeWithTag("player-artwork").assertContentDescriptionEquals("${next.title}，${next.artist}")
        compose.onNodeWithText("未在播放").assertDoesNotExist()
    }
}
