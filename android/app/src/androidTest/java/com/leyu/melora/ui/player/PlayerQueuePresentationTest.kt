package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayMode
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.theme.MeloraTheme
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实队列列表的排序、可见锚点与中断；不启动服务、不操作用户播放。 */
@RunWith(AndroidJUnit4::class)
class PlayerQueuePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (0 until 589).map { UiTrack("queue-$it", "歌曲 $it", "歌手 $it", "专辑") }
    private val state = mutableStateOf(PlayerUiState())
    private val visible = mutableStateOf(true)
    private val artworkTone = mutableStateOf<Color?>(null)
    private var selectedIndex = -1
    private var removedIndex = -1

    private fun show(index: Int = 30, motion: Boolean = true, queue: List<UiTrack> = tracks) {
        state.value = PlayerUiState(ready = true, queue = queue, current = queue[index], currentIndex = index)
        compose.setContent {
            MeloraTheme {
                PlayerAppearanceProvider(dark = true, artworkColor = artworkTone.value) {
                    QueuePageContent(
                        state = state.value, onClose = {}, isVisible = visible.value, motionEnabled = motion,
                        onSelect = { selectedIndex = it }, onRemove = { removedIndex = it },
                        modifier = Modifier.requiredSize(360.dp, 600.dp),
                    )
                }
            }
        }
        compose.awaitStable(list())
    }

    private fun row(index: Int) = compose.onNodeWithTag("queue-track-$index")
    private fun list() = compose.onNodeWithTag("playback-queue-list")
    private fun offsetFromListTop(index: Int): Float {
        val (listBounds, rowBounds) = compose.boundsInSameFrame(list(), row(index))
        return rowBounds.top - listBounds.top
    }

    private fun select(index: Int) {
        state.value = state.value.copy(current = state.value.queue[index], currentIndex = index)
    }
    private fun assertCurrentAtTop(index: Int) {
        // 固定动画时钟后仍需等待 Android 完成这一帧的测量和绘制。
        compose.waitUntil(5_000) { row(index).isDisplayed() }
        row(index).assertIsSelected()
        assertEquals("当前项应位于列表可见顶部", 0f, offsetFromListTop(index), 1f)
    }

    private fun scrollValue(): Float {
        val range = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return compose.runOnIdle { range.value() }
    }

    @Test fun firstOpenLargeQueueShowsCurrentInsteadOfQueueBeginning() {
        show(index = 330)
        assertCurrentAtTop(330)
        row(0).assertDoesNotExist()
        compose.onNodeWithText("331 / 589").assertIsDisplayed()
    }

    @Test fun nextSongScrollsContinuouslyRatherThanJumpingOrReplacingTheList() {
        show()
        assertCurrentAtTop(30)
        val startOffset = offsetFromListTop(31)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { select(31) }
            compose.mainClock.advanceTimeBy(96)
            val middleOffset = offsetFromListTop(31)
            assertTrue("中间帧必须向上移动: start=$startOffset middle=$middleOffset", middleOffset < startOffset - 1f)
            assertTrue("中间帧尚未瞬移到终点: middle=$middleOffset", middleOffset > 1f)
            compose.mainClock.advanceTimeBy(1_000)
            assertCurrentAtTop(31)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun rapidNextThenPreviousRetargetsTheVisibleList() {
        show()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { select(31) }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnUiThread { select(32) }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnUiThread { select(30) }
            compose.mainClock.advanceTimeBy(1_000)
            assertCurrentAtTop(30)
            compose.onNodeWithText("31 / 589").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun distantTrackChangeDoesNotAnimateAcrossHundredsOfSongs() {
        show(index = 0)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { select(330) }
            compose.mainClock.advanceTimeBy(64)
            assertCurrentAtTop(330)
            row(0).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun manualBrowsingIsNotStolenByPlaybackAndReopeningLocatesCurrent() {
        show()
        list().performTouchInput { swipeUp(durationMillis = 450) }
        compose.waitForIdle()
        row(30).assertIsNotDisplayed()
        val browsedPosition = scrollValue()
        compose.runOnIdle { select(31) }
        compose.waitForIdle()
        assertEquals("浏览中切歌不能抢回滚动", browsedPosition, scrollValue(), 0.01f)
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        assertCurrentAtTop(31)
    }

    @Test fun downwardGestureAtListTopDoesNotDisableCurrentTrackFollow() {
        show()
        list().performTouchInput { swipeDown(durationMillis = 400) }
        compose.waitForIdle()
        compose.runOnIdle { select(31) }
        assertCurrentAtTop(31)
    }

    @Test fun draggingInterruptsAutomaticFollowAndLaterSongsDoNotPullItBack() {
        show()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { select(31) }
            compose.mainClock.advanceTimeBy(64)
            list().performTouchInput { swipeUp(durationMillis = 400) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        val browsedPosition = scrollValue()
        compose.runOnIdle { select(32) }
        compose.waitForIdle()
        assertEquals(browsedPosition, scrollValue(), 0.01f)
    }

    @Test fun shuffleDisplayUsesEngineIndicesForSelectedCountClickAndRemoval() {
        show()
        compose.runOnIdle {
            state.value = state.value.copy(mode = PlayMode.Shuffle, queueOrder = tracks.indices.reversed().toList())
        }
        assertCurrentAtTop(30)
        compose.onNodeWithText("559 / 589").assertIsDisplayed()
        val (shuffledCurrent, shuffledNext) = compose.boundsInSameFrame(row(30), row(29))
        assertTrue(shuffledNext.top > shuffledCurrent.top)
        row(29).performClick()
        compose.runOnIdle { assertEquals("必须传媒体索引，不能传展示行559", 29, selectedIndex) }
        compose.onNodeWithTag("queue-remove-29").performClick()
        compose.runOnIdle { assertEquals(29, removedIndex) }
        compose.runOnIdle { state.value = state.value.copy(mode = PlayMode.List, queueOrder = tracks.indices.toList()) }
        assertCurrentAtTop(30)
        compose.onNodeWithText("31 / 589").assertIsDisplayed()
        val (orderedCurrent, orderedNext) = compose.boundsInSameFrame(row(30), row(31))
        assertTrue(orderedNext.top > orderedCurrent.top)
    }

    @Test fun removingAnEarlierSongKeepsCurrentIdentityAndViewport() {
        show()
        compose.runOnIdle {
            val remaining = tracks.drop(1)
            state.value = state.value.copy(queue = remaining, queueOrder = remaining.indices.toList(), currentIndex = 29)
        }
        assertCurrentAtTop(29)
        row(29).assertTextContains("歌曲 30")
        compose.onNodeWithText("30 / 588").assertIsDisplayed()
    }

    @Test fun repeatedTrackIdsRemainDistinctClickableQueueEntries() {
        val duplicate = UiTrack("repeated", "同一歌曲", "测试歌手", "专辑")
        val currentBars = hasContentDescription("当前曲目")
        val playingBars = hasContentDescription("正在播放")
        fun marker(index: Int, status: SemanticsMatcher) =
            compose.onNode(status and hasAnyAncestor(hasTestTag("queue-track-$index")), useUnmergedTree = true)
        fun markerBoundsInRow(index: Int, status: SemanticsMatcher): Rect {
            val (rowBounds, markerBounds) = compose.boundsInSameFrame(row(index), marker(index, status).assertIsDisplayed())
            return markerBounds.translate(-rowBounds.left, -rowBounds.top)
        }

        show(index = 0, motion = false, queue = listOf(duplicate, duplicate, tracks[2]))
        row(0).assertIsSelected()
        row(1).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, selectedIndex) }
        compose.onNodeWithTag("queue-remove-1").performClick()
        compose.runOnIdle { assertEquals(1, removedIndex) }

        val markerBounds = markerBoundsInRow(0, currentBars)
        compose.runOnIdle { state.value = state.value.copy(playing = true, buffering = true) }
        marker(0, currentBars).assertIsDisplayed()
        marker(0, playingBars).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(buffering = false, positionAdvancing = true) }
        assertEquals(markerBounds, markerBoundsInRow(0, playingBars))
        compose.onAllNodes(currentBars or playingBars, useUnmergedTree = true).assertCountEquals(1)

        compose.runOnIdle { select(1) }
        row(1).assertIsDisplayed().assertIsSelected()
        row(0).assertIsNotSelected()
        marker(1, playingBars).assertIsDisplayed()
        marker(0, currentBars or playingBars).assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(playing = false, positionAdvancing = false) }
        marker(1, currentBars).assertIsDisplayed()
        marker(1, playingBars).assertDoesNotExist()
    }

    @Test fun currentQueueBarsMatchTheTitleAcrossArtworkColors() {
        val tones = listOf(Color(0xFFCE4B3E), Color(0xFF3672C8))
        val expectedInks = tones.map { playerColorsFor(dark = true, artworkColor = it).textPrimary }
        assertNotEquals(expectedInks[0], expectedInks[1])
        show(index = 0, motion = false, queue = tracks.take(3))
        compose.runOnIdle { state.value = state.value.copy(playing = true, positionAdvancing = true) }
        val currentRow = hasAnyAncestor(hasTestTag("queue-track-0"))
        val bars = compose.onNode(currentRow and hasContentDescription("正在播放"), useUnmergedTree = true)
        val title = compose.onNode(currentRow and hasText(tracks.first().title), useUnmergedTree = true)

        fun SemanticsNodeInteraction.draws(ink: Color): Boolean {
            val pixels = assertIsDisplayed().captureToImage().toPixelMap()
            return (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x ->
                    val pixel = pixels[x, y]
                    abs(pixel.red - ink.red) < 0.025f && abs(pixel.green - ink.green) < 0.025f &&
                        abs(pixel.blue - ink.blue) < 0.025f
                }
            }
        }

        tones.zip(expectedInks).forEach { (tone, ink) ->
            compose.runOnIdle { artworkTone.value = tone }
            assertTrue("current title should use the artwork color for $tone", title.draws(ink))
            assertTrue("playback bars should match the current title for $tone", bars.draws(ink))
        }
    }

    @Test fun disabledMotionSnapsToCurrentAndHiddenChangesAreAlignedOnEntry() {
        show(motion = false)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { select(31) }
            compose.mainClock.advanceTimeBy(32)
            assertCurrentAtTop(31)
            compose.runOnUiThread { visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { select(500) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { visible.value = true }
            compose.mainClock.advanceTimeBy(32)
            assertCurrentAtTop(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun clearingQueueRemovesRowsAndCountWithoutLeavingOldHighlight() {
        show()
        compose.runOnIdle { state.value = PlayerUiState(ready = true) }
        list().assertDoesNotExist()
        row(30).assertDoesNotExist()
        compose.onNodeWithText("0 / 0").assertIsDisplayed()
        compose.onNodeWithText("播放队列为空").assertIsDisplayed()
    }
}
