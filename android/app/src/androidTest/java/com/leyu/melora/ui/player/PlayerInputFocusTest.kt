package com.leyu.melora.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerInputFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val query = mutableStateOf("")
    private var keyboard: SoftwareKeyboardController? = null
    private val track = UiTrack("focus-test", "焦点测试歌曲", "测试歌手", "测试专辑")

    private fun show() {
        val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))
        compose.setContent {
            CompositionLocalProvider(LocalSongListState provides shared) {
                keyboard = LocalSoftwareKeyboardController.current
                MeloraTheme {
                    Box(Modifier.fillMaxSize()) {
                        BasicTextField(query.value, { query.value = it },
                            Modifier.padding(top = 80.dp, start = 24.dp).testTag("underlying-search"))
                        ContinuousPlayerSheet(PlayerUiState(ready = true, current = track, queue = listOf(track), currentIndex = 0))
                    }
                }
            }
        }
        compose.onNodeWithTag("underlying-search").performClick().performTextInput("爱")
        compose.onNodeWithTag("underlying-search").assertIsFocused()
    }

    private fun resumeActivity() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test fun expandingPlayerEndsHiddenSearchInputAndResumeDoesNotReopenKeyboard() {
        show()
        // 对齐真机路径：用户收起键盘但输入框仍有焦点，再点击迷你播放器。
        compose.runOnIdle { keyboard?.hide() }
        compose.onNodeWithTag("underlying-search").assertIsFocused()
        val mini = compose.onAllNodesWithText(track.title, substring = true).onLast()
        compose.awaitStable(mini)
        mini.performClick()
        compose.awaitStable("player-artwork")
        compose.onNodeWithTag("underlying-search").assertIsNotFocused()
        resumeActivity()
        compose.onNodeWithTag("underlying-search").assertIsNotFocused()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        Espresso.pressBack()
        compose.onNodeWithTag("underlying-search").assertIsNotFocused().assertTextEquals("爱")
        compose.onNodeWithTag("underlying-search").performClick().performTextInput("你")
        compose.onNodeWithTag("underlying-search").assertIsFocused().assertTextEquals("爱你")
    }

    @Test fun returningToAnActiveEditorKeepsItsFocusAndDraft() {
        show()
        resumeActivity()
        compose.onNodeWithTag("underlying-search").assertIsFocused().assertTextEquals("爱")
        compose.onNodeWithTag("underlying-search").performTextInput("你")
        compose.onNodeWithTag("underlying-search").assertTextEquals("爱你")
    }
}
