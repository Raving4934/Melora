package com.leyu.melora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSheetNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var back: PlayerSheetBackState
    private val track = UiTrack("navigation-test", "返回测试歌曲", "测试歌手", "测试专辑")

    private fun show(wide: Boolean = false, forcePortrait: Boolean = false) {
        val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))
        restoration.setContent {
            CompositionLocalProvider(
                LocalSongListState provides shared,
                LocalDensity provides if (wide) Density(1f) else LocalDensity.current,
            ) {
                back = rememberPlayerSheetBackState()
                MeloraTheme {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val hostModifier = if (forcePortrait) {
                            Modifier.size(
                                minOf(maxWidth, 480.dp, maxHeight * 0.65f),
                                minOf(maxHeight, 800.dp),
                            )
                        } else {
                            Modifier.fillMaxSize()
                        }
                        Box(hostModifier.testTag("player-test-host")) {
                            Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("player-test-underlay"))
                            ContinuousPlayerSheet(
                                PlayerUiState(ready = true, current = track, queue = listOf(track), currentIndex = 0),
                                modifier = if (wide) Modifier.requiredSize(900.dp, 600.dp) else Modifier.fillMaxSize(),
                                backState = back,
                            )
                        }
                    }
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

    @Test fun partialCollapseKeepsTheUnderlyingPageCoveredFromEveryPlayerPage() {
        show(forcePortrait = true)
        expand()

        val pager = compose.onNodeWithTag("player-pages")
        pager.performTouchInput { swipeRight() }
        compose.awaitStable("player-page-0")
        assertPanelOpaqueAndRestorePage(0)

        pager.performTouchInput { swipeLeft() }
        compose.awaitStable("player-page-1")
        assertPanelOpaqueAndRestorePage(1)

        pager.performTouchInput { swipeLeft() }
        compose.awaitStable("player-page-2")
        assertPanelOpaqueAndRestorePage(2)
    }

    private fun assertPanelOpaqueAndRestorePage(page: Int) {
        val originalOffset = floatArrayOf(Float.NaN)
        compose.runOnIdle {
            val sheet = back.sheet
            val expanded = sheet.anchors.positionOf(PlayerSheetAnchor.Expanded)
            val collapsed = sheet.anchors.positionOf(PlayerSheetAnchor.Collapsed)
            val offset = sheet.offset
            assertTrue("sheet anchors must be initialized", expanded.isFinite() && collapsed.isFinite())
            assertTrue("sheet must be settled at expanded anchor", abs(offset - expanded) < 1f)
            originalOffset[0] = offset
            assertTrue("sheet must not already be animating", !sheet.isAnimationRunning)

            val partialOffset = expanded + (collapsed - expanded) * 0.4f
            sheet.dispatchRawDelta(partialOffset - offset)
            assertTrue("raw delta must place sheet at 40% collapse", abs(sheet.offset - partialOffset) < 1f)
            assertTrue("raw delta must remain static", !sheet.isAnimationRunning)
        }
        compose.waitForIdle()

        assertPanelDoesNotRevealUnderlay(page)

        compose.runOnIdle {
            val sheet = back.sheet
            sheet.dispatchRawDelta(originalOffset[0] - sheet.offset)
            assertTrue("restored sheet must not animate", !sheet.isAnimationRunning)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player-page-$page").assertIsDisplayed()
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
    }

    private fun assertPanelDoesNotRevealUnderlay(page: Int) {
        val offset = floatArrayOf(Float.NaN)
        compose.runOnIdle { offset[0] = back.sheet.offset }
        val pixels = compose.onNodeWithTag("player-test-host").captureToImage().toPixelMap()
        val sampleYs = listOf(0.70f, 0.82f).map { fraction ->
            maxOf((pixels.height * fraction).toInt(), offset[0].toInt() + 12)
                .coerceIn(0, pixels.height - 1)
        }
        listOf(0.90f, 0.96f).forEach { xFraction ->
            val x = (pixels.width * xFraction).toInt().coerceIn(0, pixels.width - 1)
            sampleYs.forEach { y -> assertNotMagenta(page, pixels[x, y], x, y) }
        }
    }

    private fun assertNotMagenta(page: Int, actual: Color, x: Int, y: Int) {
        val magenta = Color.Magenta
        assertTrue(
            "page $page player panel must stay opaque at ($x,$y), got $actual",
            abs(actual.red - magenta.red) >= 0.08f ||
                abs(actual.green - magenta.green) >= 0.08f ||
                abs(actual.blue - magenta.blue) >= 0.08f ||
                abs(actual.alpha - magenta.alpha) >= 0.08f,
        )
    }

    @Test fun miniExpandAndSystemBackKeepTheMovingPanelOpaque() {
        show(forcePortrait = true)
        val mini = compose.onAllNodesWithText(track.title, substring = true).onLast()
        compose.awaitStable(mini)
        compose.mainClock.autoAdvance = false
        try {
            val sheet = back.sheet
            val collapsed = sheet.anchors.positionOf(PlayerSheetAnchor.Collapsed)
            val expanded = sheet.anchors.positionOf(PlayerSheetAnchor.Expanded)
            assertTrue("sheet anchors must be initialized", collapsed.isFinite() && expanded.isFinite())
            compose.runOnIdle { assertEquals(PlayerSheetAnchor.Collapsed, sheet.settledValue) }

            mini.performClick()
            compose.mainClock.advanceTimeBy(96)
            var offset = floatArrayOf(Float.NaN)
            var animating = false
            compose.runOnIdle {
                offset[0] = sheet.offset
                animating = sheet.isAnimationRunning
            }
            assertTrue("Mini-to-player must be moving", animating && abs(offset[0] - collapsed) > 1f)
            assertTrue("expansion sample must be between anchors", offset[0] > expanded + 1f && offset[0] < collapsed - 1f)
            assertPanelDoesNotRevealUnderlay(1)

            compose.mainClock.advanceTimeBy(2_000)
            compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, sheet.settledValue) }

            val beforeBack = floatArrayOf(Float.NaN)
            compose.runOnIdle { beforeBack[0] = sheet.offset }
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle {
                offset[0] = sheet.offset
                animating = sheet.isAnimationRunning
            }
            assertTrue("system Back must start a moving return animation", animating && abs(offset[0] - beforeBack[0]) > 1f)
            assertTrue("return sample must be between anchors", offset[0] > expanded + 1f && offset[0] < collapsed - 1f)
            assertPanelDoesNotRevealUnderlay(1)

            compose.mainClock.advanceTimeBy(2_000)
            compose.runOnIdle {
                assertEquals(PlayerSheetAnchor.Collapsed, sheet.settledValue)
                assertTrue("system Back must finish at Mini", abs(sheet.offset - collapsed) < 1f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
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
