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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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

    @Suppress("UNCHECKED_CAST")
    private val lyricFlow = PlaybackController.javaClass.getDeclaredField("_lyric").let {
        it.isAccessible = true
        it.get(PlaybackController) as MutableStateFlow<PlayerLyric?>
    }
    private val originalLyric = lyricFlow.value

    @After fun restoreLyrics() { lyricFlow.value = originalLyric }

    private fun supplyLyrics(count: Int = 80) {
        lyricFlow.value = PlayerLyric(track.uid, track.title, track.artist.orEmpty(),
            List(count) { LyricLine(it * 2_000L, "测试歌词第 $it 行") }, "test")
    }

    private fun show(wide: Boolean = false, forcePortrait: Boolean = false, queueSize: Int = 1) {
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
                                PlayerUiState(ready = true, current = track, queue = List(queueSize) { if (it == 0) track else track.copy(uid = "queue-$it", title = "队列歌曲 $it") }, currentIndex = 0),
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
        compose.waitUntil(5_000) {
            back.sheet.settledValue == PlayerSheetAnchor.Expanded && back.transition == null
        }
        compose.awaitStable("player-heading")
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

    private fun pressBackDuringQueueOpening() {
        val queue = compose.onNodeWithContentDescription("播放队列")
        compose.awaitStable(queue)
        compose.mainClock.autoAdvance = false
        try {
            queue.performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { assertEquals(PlayerSheetTransition.OpenQueue, back.transition) }
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(2_500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun navigateToPage(page: Int) {
        val pager = compose.onNodeWithTag("player-pages")
        when (page) {
            0 -> pager.performTouchInput { swipeRight() }
            2 -> pager.performTouchInput { swipeLeft() }
        }
        compose.awaitStable("player-page-$page")
    }

    private fun dragWithSmallDownStart(from: Int, to: Int) {
        val pager = compose.onNodeWithTag("player-pages")
        val horizontalSign = if (to > from) -1f else 1f
        pager.performTouchInput {
            val start = Offset(width * if (to > from) 0.8f else 0.2f, height * 0.28f)
            down(start)
            moveTo(start + Offset(horizontalSign * 4f, 32f), delayMillis = 32)
            repeat(12) { index ->
                val progress = (index + 1) / 12f
                moveTo(
                    start + Offset(horizontalSign * width * 0.65f * progress, 32f + height * 0.10f * progress),
                    delayMillis = 16,
                )
            }
            up()
        }
    }

    private fun assertPagerSettledOn(page: Int) {
        val pagerBounds = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        val pageBounds = compose.onNodeWithTag("player-page-$page").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "page $page should settle at the pager start: page=$pageBounds pager=$pagerBounds",
            abs(pageBounds.left - pagerBounds.left) < 1f,
        )
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

    @Test fun firstBackDuringExpansionReturnsToMini() {
        show(forcePortrait = true)
        val mini = compose.onAllNodesWithText(track.title, substring = true).onLast()
        compose.awaitStable(mini)
        compose.mainClock.autoAdvance = false
        try {
            mini.performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle {
                assertEquals(PlayerSheetTransition.Expand, back.transition)
                assertTrue("the sheet must still be opening at 96ms", back.sheet.isAnimationRunning)
            }
            Espresso.pressBack()
            compose.runOnIdle { assertEquals(PlayerSheetTransition.Collapse, back.transition) }
            Espresso.pressBack() // 同一退出过程重复Back不能穿透到Activity。
            compose.mainClock.advanceTimeBy(2_500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertCollapsed()
    }

    @Test fun firstBackDuringMiniQueueOpeningReturnsToMini() {
        show(forcePortrait = true)
        pressBackDuringQueueOpening()
        assertCollapsed()
    }

    @Test fun firstBackDuringFullPlayerQueueOpeningReturnsToSourcePage() {
        show(forcePortrait = true)
        expand()
        navigateToPage(0)

        pressBackDuringQueueOpening()

        compose.awaitStable("player-page-0")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        assertPagerSettledOn(0)
    }

    @Test fun stationaryTouchDuringBackDoesNotCancelCollapse() {
        show(forcePortrait = true)
        expand()
        compose.mainClock.autoAdvance = false
        try {
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(32)
            val offset = floatArrayOf(Float.NaN)
            compose.runOnIdle {
                assertEquals(PlayerSheetTransition.Collapse, back.transition)
                assertTrue("the return animation must still be running at 32ms", back.sheet.isAnimationRunning)
                offset[0] = back.sheet.offset
            }
            compose.onNodeWithTag("player-test-host").performTouchInput {
                down(Offset(width * 0.5f, (offset[0] + 90f).coerceAtMost(height - 30f)))
                advanceEventTime(80)
                up()
            }
            compose.mainClock.advanceTimeBy(2_500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertCollapsed()
    }

    @Test fun horizontalSwipeDuringExpansionDoesNotCollapseTheSheet() {
        show(forcePortrait = true)
        val mini = compose.onAllNodesWithText(track.title, substring = true).onLast()
        compose.awaitStable(mini)
        compose.mainClock.autoAdvance = false
        try {
            mini.performClick()
            compose.mainClock.advanceTimeBy(64)
            val offset = floatArrayOf(Float.NaN)
            compose.runOnIdle {
                assertEquals(PlayerSheetTransition.Expand, back.transition)
                assertTrue("the sheet must still be opening at 64ms", back.sheet.isAnimationRunning)
                offset[0] = back.sheet.offset
            }
            compose.onNodeWithTag("player-test-host").performTouchInput {
                val y = offset[0] + 90f
                swipe(Offset(width * 0.8f, y), Offset(width * 0.2f, y), durationMillis = 300)
            }
            compose.mainClock.advanceTimeBy(2_500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5_000) {
            back.sheet.settledValue == PlayerSheetAnchor.Expanded && back.transition == null
        }
    }

    @Test fun downStartedHorizontalSwipeFromInformationPageKeepsSheetExpanded() {
        show(forcePortrait = true)
        expand()
        navigateToPage(0)

        dragWithSmallDownStart(from = 0, to = 1)

        compose.awaitStable("player-page-1")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        assertPagerSettledOn(1)
    }

    @Test fun downStartedHorizontalSwipeFromCoverPageKeepsSheetExpanded() {
        show(forcePortrait = true)
        expand()
        navigateToPage(1)

        dragWithSmallDownStart(from = 1, to = 0)

        compose.awaitStable("player-page-0")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        assertPagerSettledOn(0)
    }

    @Test fun lyricsPageDownStartedSwipeKeepsItsCollapseProtection() {
        show(forcePortrait = true)
        expand()
        navigateToPage(2)
        compose.awaitStable("player-full-lyrics")

        dragWithSmallDownStart(from = 2, to = 1)

        compose.awaitStable("player-page-1")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        assertPagerSettledOn(1)
    }

    @Test fun backWhileVerticalDragIsHeldStillCompletesCollapse() {
        show(forcePortrait = true)
        expand()
        val host = compose.onNodeWithTag("player-test-host")
        host.performTouchInput {
            down(Offset(center.x, height * 0.2f))
            moveTo(Offset(center.x, height * 0.4f), delayMillis = 160)
        }
        Espresso.pressBack()
        host.performTouchInput { up() }
        assertCollapsed()
    }

    @Test fun deliberateDownwardDragStillCollapsesThePlayer() {
        show(forcePortrait = true)
        expand()
        compose.onNodeWithTag("player-test-host").performTouchInput {
            swipe(Offset(center.x, height * 0.2f), Offset(center.x, height * 0.85f), 650)
        }
        assertCollapsed()
    }

    @Test fun upwardQueueSwipeStillReturnsToPlayerWithOneBack() {
        show(forcePortrait = true)
        expand()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeUp() }
        compose.awaitStable(compose.onNodeWithText("收起播放队列"))
        Espresso.pressBack()
        compose.awaitStable("player-page-1")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        assertPagerSettledOn(1)
    }

    /** 每段拖动之间实际重组/绘制，不能把整条手势在同一帧一次性注入。 */
    private fun dragQueueAcrossFrames(upward: Boolean) {
        val host = compose.onNodeWithTag("player-test-host")
        val bounds = host.fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.width * 0.9f, bounds.height * if (upward) 0.8f else 0.15f)
        val end = Offset(start.x, bounds.height * if (upward) 0.1f else 0.9f)
        host.performTouchInput { down(start) }
        repeat(16) { step ->
            val fraction = (step + 1) / 16f
            host.performTouchInput { moveTo(start + (end - start) * fraction, delayMillis = 24) }
            compose.waitForIdle()
        }
        host.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test fun informationPageQueueRoundTripAcrossFramesKeepsPlayerExpanded() {
        show(forcePortrait = true)
        expand()
        navigateToPage(0)
        repeat(3) {
            dragQueueAcrossFrames(upward = true)
            compose.onNodeWithText("收起播放队列").assertIsDisplayed()
            dragQueueAcrossFrames(upward = false)
            compose.onNodeWithTag("player-page-0").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
        }
    }

    @Test fun coverPageQueueRoundTripAcrossFramesKeepsPlayerExpanded() {
        show(forcePortrait = true)
        expand()
        repeat(3) {
            dragQueueAcrossFrames(upward = true)
            compose.onNodeWithText("收起播放队列").assertIsDisplayed()
            dragQueueAcrossFrames(upward = false)
            compose.onNodeWithTag("player-page-1").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
        }
    }

    @Test fun queueListDownwardSwipeSettlesFullyBackToPlayer() {
        show(forcePortrait = true)
        expand()
        val headingTop = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot.top
        openQueue()
        compose.onNodeWithTag("player-test-host").performTouchInput {
            swipe(Offset(width * 0.4f, height * 0.45f), Offset(width * 0.4f, height * 0.95f), 400)
        }
        compose.waitForIdle()
        val heading = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot
        assertEquals("list handoff must return the full player to its original position", headingTop, heading.top, 1f)
        compose.onNodeWithTag("player-page-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun informationContentUpwardSwipeSettlesFullyIntoQueue() {
        show(forcePortrait = true)
        expand()
        navigateToPage(0)
        openQueue()
        val queueTop = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top
        Espresso.pressBack()
        compose.awaitStable("player-page-0")
        compose.onNodeWithTag("player-page-0").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val queue = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot
        assertEquals("content handoff must settle queue exactly like the queue button", queueTop, queue.top, 1f)
    }

    @Test fun lyricsPageQueueSwipeFromControlsReturnsToLyrics() {
        supplyLyrics()
        show(forcePortrait = true)
        expand()
        navigateToPage(2)
        compose.onNodeWithTag("player-transport").performTouchInput { swipeUp() }
        compose.awaitStable(compose.onNodeWithText("收起播放队列"))
        dragQueueAcrossFrames(upward = false)
        compose.onNodeWithTag("player-page-2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
    }

    @Test fun lyricsAtEndCanHandUpwardGestureToQueue() {
        supplyLyrics(count = 3)
        show(forcePortrait = true)
        expand()
        navigateToPage(2)
        compose.onNodeWithTag("player-full-lyrics").performTouchInput { swipeUp() }
        compose.awaitStable(compose.onNodeWithText("收起播放队列"))
        Espresso.pressBack()
        compose.awaitStable("player-page-2")
        compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
    }

    @Test fun longQueueScrollStaysInQueueUntilItsTopThenReturnsToPlayer() {
        show(forcePortrait = true, queueSize = 80)
        expand()
        openQueue()
        val host = compose.onNodeWithTag("player-test-host")
        host.performTouchInput {
            swipe(Offset(width * 0.4f, height * 0.85f), Offset(width * 0.4f, height * 0.4f), 700)
        }
        compose.onNodeWithText("收起播放队列").assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("80-row queue") {
            it.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount == 80
        })
        assertTrue("queue list must actually scroll", list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        list.performScrollToIndex(0)
        compose.waitForIdle()
        // 明确到达列表顶部再执行一次下滑，不能把已经返回播放页后的第二次下拉当作队列手势。
        host.performTouchInput {
            swipe(Offset(width * 0.4f, height * 0.4f), Offset(width * 0.4f, height * 0.95f), 400)
        }
        compose.awaitStable("player-page-1")
        compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
    }

    @Test fun listFlingDuringMiniQueueExpansionCannotInterruptSheetArrival() {
        show(forcePortrait = true, queueSize = 80)
        val button = compose.onNodeWithContentDescription("播放队列")
        compose.awaitStable(button)
        compose.mainClock.autoAdvance = false
        try {
            button.performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle { assertTrue("sheet must still be between anchors", back.sheet.offset > 1f) }
            compose.onNodeWithTag("player-test-host").performTouchInput {
                swipe(Offset(width * 0.4f, height * 0.55f), Offset(width * 0.4f, height * 0.9f), 240)
            }
            compose.mainClock.advanceTimeBy(2_500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithText("收起播放队列").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
        Espresso.pressBack()
        assertCollapsed()
    }

    private fun queueList() = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("200-row queue") {
        it.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount == 200
    })

    /** 允许回到任一端，但松手后不能停在两个页面之间，更不能收起整个播放器。 */
    private fun assertQueueOrPlayerSettled(playerTop: Float, queueTop: Float) {
        compose.runOnIdle { assertEquals("queue gestures must not collapse sheet", 0f, back.sheet.offset, 1f) }
        val heading = runCatching { compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot.top }.getOrNull()
        val queue = runCatching { compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top }.getOrNull()
        assertTrue("page must reach one endpoint: heading=$heading expected=$playerTop queue=$queue expected=$queueTop",
            (heading != null && abs(heading - playerTop) < 1f) || (queue != null && abs(queue - queueTop) < 1f))
    }

    @Test fun queueListFlingThenHeaderDragAlwaysSettles() {
        show(forcePortrait = true, queueSize = 200)
        expand()
        val playerTop = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot.top
        openQueue()
        val queueTop = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top
        val host = compose.onNodeWithTag("player-test-host")
        compose.mainClock.autoAdvance = false
        try {
            host.performTouchInput { swipe(Offset(width * 0.4f, height * 0.8f), Offset(width * 0.4f, height * 0.4f), 150) }
            compose.mainClock.advanceTimeBy(32)
            host.performTouchInput { swipe(Offset(width * 0.4f, height * 0.14f), Offset(width * 0.4f, height * 0.65f), 450) }
            compose.mainClock.advanceTimeBy(3_000)
        } finally { compose.mainClock.autoAdvance = true }
        assertQueueOrPlayerSettled(playerTop, queueTop)
    }

    @Test fun partialQueueRegrabInsideScrolledListAlwaysSettles() {
        show(forcePortrait = true, queueSize = 200)
        expand()
        val playerTop = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot.top
        openQueue()
        val queueTop = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top
        queueList().performScrollToIndex(15)
        val host = compose.onNodeWithTag("player-test-host")
        compose.mainClock.autoAdvance = false
        try {
            host.performTouchInput { swipe(Offset(width * 0.4f, height * 0.14f), Offset(width * 0.4f, height * 0.4f), 500) }
            compose.mainClock.advanceTimeBy(32)
            // 页仍在回弹，下一次触摸落在已滚到中段的队列列表。
            host.performTouchInput {
                down(Offset(width * 0.4f, height * 0.75f))
                moveBy(Offset(0f, -height * 0.12f), delayMillis = 150)
            }
            compose.mainClock.advanceTimeBy(160)
            host.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(3_000)
        } finally { compose.mainClock.autoAdvance = true }
        assertQueueOrPlayerSettled(playerTop, queueTop)
    }

    @Test fun interruptedQueueReturnDoesNotFlingAnAlreadyScrolledList() {
        show(forcePortrait = true, queueSize = 200)
        expand()
        val playerTop = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot.top
        openQueue()
        val queueTop = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top
        queueList().performScrollToIndex(15)
        val host = compose.onNodeWithTag("player-test-host")
        compose.mainClock.autoAdvance = false
        try {
            host.performTouchInput { swipe(Offset(width * 0.4f, height * 0.14f), Offset(width * 0.4f, height * 0.45f), 350) }
            compose.mainClock.advanceTimeBy(16)
            host.performTouchInput {
                down(Offset(width * 0.4f, height * 0.78f))
                moveBy(Offset(0f, height * 0.10f), delayMillis = 100)
                up()
            }
            compose.mainClock.advanceTimeBy(3_000)
        } finally { compose.mainClock.autoAdvance = true }
        assertQueueOrPlayerSettled(playerTop, queueTop)
    }

    @Test fun reversingNestedQueueDragDoesNotLeavePagerWaitingForListFling() {
        show(forcePortrait = true, queueSize = 200)
        expand()
        val playerTop = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot.top
        openQueue()
        val queueTop = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top
        val listOffset = queueList().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val host = compose.onNodeWithTag("player-test-host")
        compose.mainClock.autoAdvance = false
        try {
            // 列表顶部下拖令父页部分露出，同一次手势短距离反向松手。
            host.performTouchInput {
                down(Offset(width * 0.4f, height * 0.42f))
                repeat(12) { moveBy(Offset(0f, height * 0.025f), delayMillis = 16) }
            }
            compose.mainClock.advanceTimeByFrame()
            val during = compose.onNodeWithText("收起播放队列").fetchSemanticsNode().boundsInRoot.top
            assertTrue("must really drag pager off its anchor: $during > $queueTop", during > queueTop + 100f)
            host.performTouchInput {
                repeat(5) { moveBy(Offset(0f, -height * 0.02f), delayMillis = 16) }
                up()
            }
            compose.mainClock.advanceTimeBy(1_000)
            val offsetAfter = queueList().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("partial-page release must not fling the queue list", listOffset, offsetAfter, 1f)
            assertQueueOrPlayerSettled(playerTop, queueTop)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun backDuringNestedQueueSnapReturnsOnceWithoutReopening() {
        show(forcePortrait = true, queueSize = 200)
        expand()
        openQueue()
        val host = compose.onNodeWithTag("player-test-host")
        compose.mainClock.autoAdvance = false
        try {
            host.performTouchInput {
                down(Offset(width * 0.4f, height * 0.42f))
                repeat(12) { moveBy(Offset(0f, height * 0.025f), delayMillis = 16) }
            }
            compose.mainClock.advanceTimeByFrame()
            host.performTouchInput {
                repeat(5) { moveBy(Offset(0f, -height * 0.02f), delayMillis = 16) }
                up()
            }
            compose.mainClock.advanceTimeBy(32)
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(2_000)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("player-page-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0f, back.sheet.offset, 1f) }
        Espresso.pressBack()
        assertCollapsed()
    }

    @Test fun verticalLyricsBrowseDoesNotCollapseOrTurnPages() {
        supplyLyrics()
        show(forcePortrait = true)
        expand()
        navigateToPage(2)
        compose.onNodeWithTag("player-full-lyrics").performTouchInput { swipeUp() }
        compose.awaitStable("player-page-2")
        compose.runOnIdle { assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue) }
        assertPagerSettledOn(2)
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
