package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSheetNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var back: PlayerSheetBackState
    private val track = UiTrack("navigation-test", "返回测试歌曲", "测试歌手", "测试专辑")

    private fun show(wide: Boolean = false) {
        val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))
        restoration.setContent {
            CompositionLocalProvider(
                LocalSongListState provides shared,
                LocalDensity provides if (wide) Density(1f) else LocalDensity.current,
            ) {
                back = rememberPlayerSheetBackState()
                MeloraTheme {
                    ContinuousPlayerSheet(
                        PlayerUiState(ready = true, current = track, queue = listOf(track), currentIndex = 0),
                        modifier = if (wide) Modifier.requiredSize(900.dp, 600.dp) else Modifier.fillMaxSize(),
                        backState = back,
                    )
                }
            }
        }
    }

    private fun expand() {
        val mini = compose.onAllNodesWithText(track.title, substring = true).onLast()
        compose.awaitStable(mini)
        mini.performClick()
        compose.waitUntil(5_000) { back.sheet.settledValue == PlayerSheetAnchor.Expanded && !back.pending }
    }

    private fun openQueue() {
        val button = compose.onNodeWithContentDescription("播放队列")
        compose.awaitStable(button)
        button.performClick()
        compose.awaitStable(compose.onNodeWithText("收起播放队列"))
    }

    private fun assertCollapsed() {
        compose.waitUntil(5_000) { back.sheet.settledValue == PlayerSheetAnchor.Collapsed && !back.ownsBack }
    }

    @Test fun miniQueueBackAndHeaderCloseReturnToTheUnderlyingPage() {
        show()
        openQueue()
        Espresso.pressBack()
        assertCollapsed()
        openQueue()
        compose.onNodeWithText("收起播放队列").performClick()
        assertCollapsed()
        // 从 Mini 封面重新展开必须回播放内容，不能残留上次队列页面。
        expand()
        compose.onNodeWithTag("player-page-1").assertIsDisplayed()
    }

    @Test fun queueOpenedFromFullPlayerReturnsToThatPlayerBeforeCollapsing() {
        show()
        expand()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeRight() }
        compose.awaitStable("player-page-0")
        openQueue()
        Espresso.pressBack()
        compose.awaitStable("player-page-0")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun miniQueueKeepsItsReturnOriginAfterStateRestoration() {
        show()
        openQueue()
        restoration.emulateSavedInstanceStateRestore()
        compose.awaitStable(compose.onNodeWithText("收起播放队列"))
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun queueOpenedFromLyricsReturnsToLyricsNotCover() {
        show()
        expand()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
        compose.awaitStable("player-page-2")
        openQueue()
        Espresso.pressBack()
        compose.awaitStable("player-page-2")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun lyricsBackCollapsesInOneStepRatherThanReturningToCover() {
        show()
        expand()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
        compose.awaitStable("player-page-2")
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun informationBackAlsoCollapsesInOneStep() {
        show()
        expand()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeRight() }
        compose.awaitStable("player-page-0")
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun expandedInformationBackDoesNotNavigateToHiddenLyricsFirst() {
        show(wide = true)
        expand()
        compose.onNodeWithTag("player-page-tab-0").performClick()
        compose.awaitStable("player-page-0")
        Espresso.pressBack()
        assertCollapsed()
    }
}
