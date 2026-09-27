package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.theme.MeloraTheme
import com.leyu.melora.ui.awaitStable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 实际Compose重排：不依赖设备型号，缩窄与展开共用同一播放状态/Pager。 */
@RunWith(AndroidJUnit4::class)
class TabletPlayerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val viewport = mutableStateOf(DpSize(1000.dp, 700.dp))
    private val immersive = mutableStateOf(false)
    private val fontScale = mutableStateOf(1f)
    private val position = mutableLongStateOf(45_000)
    private var collapsed = false

    private fun showPlayer() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                PlayerAppearanceProvider(dark = true) {
                    val lines = (0..20).map { LyricLine(it * 10_000L, "在平板上听见海风，第 $it 句") }
                    Box(Modifier.fillMaxSize()) {
                        FullPlayerPageContent(
                            state = PlayerUiState(current = UiTrack("tablet-fixture", "夜航：很长的歌曲标题也不能挤掉播放设备按钮", "测试歌手", "海边的夜晚"),
                                positionMs = position.longValue, durationMs = 210_000),
                            immersive = immersive.value, onImmersiveChange = { immersive.value = it },
                            lyricPosition = position, motionEnabled = false,
                            lyricFrame = rememberLyricFrame(lines, position), lyricLines = lines,
                            onOpenQueue = {}, queuePagerState = rememberPagerState { 2 },
                            onArtworkPositioned = {}, artworkAlpha = { 1f },
                            coverStyle = PlayerCoverStyle.Default, artworkRotation = { 0f },
                            onPageVisualChanged = { _, _, _ -> },
                            onCollapse = { collapsed = true },
                            modifier = Modifier.size(viewport.value),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun expandedPlayerHasOneCoverAndOneReadingPaneAndCollapseRemainsReachable() {
        showPlayer()
        compose.onNodeWithTag("player-expanded-layout").assertIsDisplayed()
        compose.onAllNodesWithTag("player-artwork").assertCountEquals(1)
        val cover = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
        val reader = compose.onNodeWithTag("player-reading-pane").fetchSemanticsNode().boundsInRoot
        assertTrue(cover.right < reader.left)
        compose.onNodeWithTag("player-full-lyrics").assertIsDisplayed()
        compose.onNodeWithTag("player-collapse").performClick()
        assertTrue(collapsed)
    }

    @Test fun resizingKeepsTheSelectedLyricsPageAndPlaybackPosition() {
        showPlayer()
        compose.onNodeWithTag("player-page-tab-2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("player-page-tab-2").assertIsSelected()
        compose.runOnIdle { viewport.value = DpSize(390.dp, 700.dp) }
        compose.waitForIdle()
        compose.onNodeWithTag("player-expanded-layout").assertDoesNotExist()
        compose.onNodeWithTag("player-page-2").assertIsDisplayed()
        compose.runOnIdle { viewport.value = DpSize(1000.dp, 700.dp) }
        compose.waitForIdle()
        compose.onNodeWithTag("player-page-tab-2").assertIsSelected()
        assertEquals(45_000L, position.longValue)
    }

    @Test fun expandedReaderHasOnlyLyricsThenInformationAndSwipeReachesInformationDirectly() {
        showPlayer()
        compose.onNodeWithTag("player-page-tab-1").assertDoesNotExist()
        compose.onNodeWithText("封面与歌词").assertDoesNotExist()
        compose.onNodeWithTag("player-page-tab-2").assertIsSelected()
        val lyrics = compose.onNodeWithTag("player-page-tab-2").fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithTag("player-page-tab-0").fetchSemanticsNode().boundsInRoot
        assertTrue(lyrics.right <= info.left)
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("player-page-tab-0").assertIsSelected()
        compose.runOnIdle { viewport.value = DpSize(390.dp, 700.dp) }
        compose.waitForIdle()
        compose.onNodeWithTag("player-page-0").assertIsDisplayed()
        compose.runOnIdle { viewport.value = DpSize(1000.dp, 700.dp) }
        compose.waitForIdle()
        compose.onNodeWithTag("player-page-tab-0").assertIsSelected()
        compose.onAllNodesWithTag("player-artwork").assertCountEquals(1)
    }

    @Test fun immersiveKeepsCoverAndLyricsWhileHidingChrome() {
        showPlayer()
        compose.runOnIdle { immersive.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("player-artwork").assertIsDisplayed()
        compose.onNodeWithTag("player-full-lyrics").assertIsDisplayed()
        compose.onNodeWithTag("player-transport").assertIsNotDisplayed()
        compose.onNodeWithTag("player-artwork").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("退出沉浸播放").assertIsDisplayed()
    }

    @Test fun lyricSettingsAlignWithTheFiveTransportActions() {
        showPlayer()
        val lyrics = compose.onNodeWithContentDescription("展开歌词设置").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithContentDescription("循环模式").fetchSemanticsNode().boundsInRoot
        assertEquals(controls.center.y, lyrics.center.y, 1f)
    }

    @Test fun portraitTabletUsesLargerMiniLyricsAndLeavesNoDeadSpaceAboveTransport() {
        viewport.value = DpSize(640.dp, 780.dp)
        showPlayer()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("在平板上听见海风，第 4 句") and hasAnyAncestor(hasTestTag("player-mini-lyrics")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(24.sp, layouts.single().layoutInput.style.fontSize)
        val preview = compose.onNodeWithTag("player-mini-lyrics").fetchSemanticsNode().boundsInRoot
        val transport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot
        assertTrue("歌词应延伸到控制区上方，而非固定5行后留下大片空白", transport.top - preview.bottom in 0f..32f)
        compose.runOnIdle { viewport.value = DpSize(390.dp, 700.dp) }
        compose.waitForIdle()
        layouts.clear()
        compose.onNode(hasText("在平板上听见海风，第 4 句") and hasAnyAncestor(hasTestTag("player-mini-lyrics")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("手机仍使用原16sp迷你歌词", 16.sp, layouts.single().layoutInput.style.fontSize)
    }

    @Test fun enlargedFontsDoNotPushTheTransportOutsideTheWindow() {
        fontScale.value = 1.3f
        showPlayer()
        compose.onNodeWithTag("player-transport").assertIsDisplayed()
        compose.onNodeWithTag("player-collapse").assertIsDisplayed()
        val cover = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
        val transport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot
        assertTrue(cover.bottom <= transport.top)
    }
}

/** 使用真实Sheet手势，验证阅读区边界不把拖动泄漏给播放器。 */
@RunWith(AndroidJUnit4::class)
class TabletPlayerGestureInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draggingCoverCollapsesButDraggingLyricsAtTheirBoundaryDoesNot() {
        compose.setContent {
            MeloraTheme {
                SongListStateProvider {
                    ContinuousPlayerSheet(
                        PlayerUiState(current = UiTrack("tablet-gesture", "平板手势测试", "测试歌手", "测试专辑")),
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val mini = compose.onAllNodesWithText("平板手势测试", substring = true).onLast()
        compose.awaitStable(mini)
        mini.performClick()
        compose.awaitStable("player-expanded-layout")
        val before = compose.onNodeWithTag("player-expanded-layout").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player-full-lyrics").performTouchInput {
            swipe(Offset(center.x, height * .15f), Offset(center.x, height * .85f), 220)
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("player-expanded-layout").fetchSemanticsNode().boundsInRoot
        assertEquals("歌词到达边缘也不能拖走整张播放页", before.top, after.top, 1f)
        compose.onNodeWithTag("player-artwork-pane").performTouchInput {
            swipe(Offset(center.x, height * .15f), Offset(center.x, height * .9f), 220)
        }
        compose.waitForIdle()
        val collapsed = compose.onNodeWithTag("player-expanded-layout").fetchSemanticsNode().boundsInRoot
        assertTrue("封面下拉应把Sheet移到迷你条位置，实际top=${collapsed.top}", collapsed.top > before.top + before.height * .5f)
        compose.onNodeWithTag("player-full-lyrics").assertIsNotDisplayed()
        compose.onAllNodesWithText("平板手势测试", substring = true).onLast().assertIsDisplayed()
    }
}
