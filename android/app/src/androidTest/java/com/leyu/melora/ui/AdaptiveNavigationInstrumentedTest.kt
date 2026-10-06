package com.leyu.melora.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.UiTrack
import kotlinx.coroutines.flow.MutableStateFlow
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val compactWindow = mutableStateOf(false)
    private val previousAutoPlay = MeloraSettings.autoPlayOnStart.value
    private val previousExit = MeloraSettings.showExitButton.value

    @After fun restoreSettings() {
        MeloraSettings.autoPlayOnStart.value = previousAutoPlay
        MeloraSettings.showExitButton.value = previousExit
    }

    private fun showApp() {
        MeloraSettings.autoPlayOnStart.value = false
        compose.setContent {
            val original = LocalDensity.current
            val size = LocalWindowInfo.current.containerSize
            // 根据真实像素构造宽/窄dp窗口，固定3倍密度在2560px平板上仍有853dp，根本没有进入窄屏。
            val density = if (compactWindow.value) size.width / 390f
                else minOf(size.width / 1000f, size.height / 700f)
            CompositionLocalProvider(LocalDensity provides Density(density.coerceAtLeast(0.1f), original.fontScale)) {
                MeloraTheme { SongListStateProvider { MeloraApp(initialTab = 5) } }
            }
        }
        compose.waitForIdle()
    }

    @Test fun startupWaitsForPlaybackFlowWithoutFallingThroughToTimeout() {
        // 仅替换测试进程中的状态，不连接播放服务、不读取正式应用的数据。
        @Suppress("UNCHECKED_CAST")
        val playback = PlaybackController::class.java.getDeclaredField("_state").apply {
            isAccessible = true
        }.get(null) as MutableStateFlow<PlayerUiState>
        val previousPlayback = playback.value
        val previousRecents = UserLibrary.recents.value
        val previousDesktop = MeloraSettings.showDesktopLyrics.value
        val autoStart = mutableStateOf(false)
        val visible = mutableStateOf(true)
        try {
            playback.value = PlayerUiState()
            UserLibrary.recents.value = emptyList()
            MeloraSettings.showDesktopLyrics.value = false
            MeloraSettings.autoPlayOnStart.value = false
            compose.setContent {
                if (visible.value) key(autoStart.value) {
                    MeloraTheme { SongListStateProvider { MeloraApp(initialTab = 7) } }
                }
            }
            compose.waitForIdle()
            val regularSubscribers = playback.subscriptionCount.value
            compose.runOnIdle {
                MeloraSettings.autoPlayOnStart.value = true
                autoStart.value = true
            }
            // 就绪等待必须真正订阅StateFlow，而不是对普通value读取创建snapshotFlow。
            compose.waitUntil(1_000) { playback.subscriptionCount.value == regularSubscribers + 1 }
            compose.runOnIdle { playback.value = PlayerUiState(ready = true) }
            compose.waitUntil(1_000) { playback.subscriptionCount.value == regularSubscribers }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            playback.value = previousPlayback
            UserLibrary.recents.value = previousRecents
            MeloraSettings.showDesktopLyrics.value = previousDesktop
        }
    }

    @Test fun fullAppPlayerBackKeepsRootPageAndNavigationAcrossRepeatedExpansions() {
        @Suppress("UNCHECKED_CAST")
        val playback = PlaybackController::class.java.getDeclaredField("_state").apply {
            isAccessible = true
        }.get(null) as MutableStateFlow<PlayerUiState>
        val previousPlayback = playback.value
        val previousRecents = UserLibrary.recents.value
        val previousAutoPlay = MeloraSettings.autoPlayOnStart.value
        val previousDesktopLyrics = MeloraSettings.showDesktopLyrics.value
        val visible = mutableStateOf(true)
        val track = UiTrack("adaptive-navigation-regression", "导航回归歌曲", "测试歌手", "测试专辑")

        try {
            playback.value = PlayerUiState(
                ready = true,
                current = track,
                queue = listOf(track),
                currentIndex = 0,
                playing = false,
                durationMs = 180_000,
            )
            UserLibrary.recents.value = emptyList()
            MeloraSettings.autoPlayOnStart.value = false
            MeloraSettings.showDesktopLyrics.value = false
            compose.setContent {
                if (visible.value) {
                    val original = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(1f, original.fontScale)) {
                        MeloraTheme { SongListStateProvider { MeloraApp(initialTab = 5) } }
                    }
                }
            }
            compose.awaitStable("main-navigation-content")
            compose.onNodeWithText("乐屿 · Melora").assertIsDisplayed()
            compose.onNodeWithText("沉浸式音乐与听书体验").assertIsDisplayed()
            compose.onNodeWithTag("main-navigation-item-5").assertIsSelected()

            repeat(2) {
                val mini = compose.onAllNodesWithText(track.title, substring = true).onLast()
                compose.awaitStable(mini)
                mini.performClick()
                compose.awaitStable("player-heading")

                Espresso.pressBack()
                compose.awaitStable(mini)
                compose.onNodeWithTag("main-navigation-content").assertIsDisplayed()
                compose.onNodeWithText("乐屿 · Melora").assertIsDisplayed()
                compose.onNodeWithTag("main-navigation-item-5").assertIsSelected()
            }

            compose.onNodeWithTag("main-navigation-item-7").performScrollTo().performClick()
            compose.onNodeWithTag("main-navigation-item-7").assertIsSelected()
            compose.onNodeWithTag("main-navigation-item-5").performScrollTo().performClick()
            compose.onNodeWithTag("main-navigation-item-5").assertIsSelected()
            compose.onNodeWithText("乐屿 · Melora").assertIsDisplayed()
        } finally {
            try {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            } finally {
                playback.value = previousPlayback
                UserLibrary.recents.value = previousRecents
                MeloraSettings.autoPlayOnStart.value = previousAutoPlay
                MeloraSettings.showDesktopLyrics.value = previousDesktopLyrics
            }
        }
    }

    @Test fun navigationKeepsSelectionAcrossCompactAndExpandedWindows() {
        showApp()
        compose.onNodeWithTag("main-navigation-drawer").assertIsDisplayed()
        compose.onNodeWithText("乐屿 · Melora").assertIsDisplayed()
        compose.onNodeWithText("沉浸式音乐与听书体验").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开侧栏").assertDoesNotExist()
        compose.onNodeWithTag("main-navigation-item-7").performScrollTo().performClick()
        compose.onNodeWithTag("main-navigation-item-7").assertIsSelected()
        // 相同物理窗口中的dp空间缩小，验证按当前可用空间重排，而非固定tablet布尔值。
        compose.runOnIdle { compactWindow.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("main-navigation-drawer").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
        compose.runOnIdle { compactWindow.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("main-navigation-item-7").assertIsSelected()
    }

    @Test fun configuredExitActionRemainsReachableInTheDrawer() {
        MeloraSettings.showExitButton.value = true
        showApp()
        compose.onNodeWithTag("main-navigation-exit").assertIsDisplayed()
        compose.runOnIdle { MeloraSettings.showExitButton.value = false }
        compose.onNodeWithTag("main-navigation-exit").assertDoesNotExist()
    }
}
