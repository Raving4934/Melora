package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayMode
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.theme.MeloraTheme
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
    private var selectedIndex = -1
    private var removedIndex = -1

    private fun show(index: Int = 30, motion: Boolean = true, queue: List<UiTrack> = tracks) {
        state.value = PlayerUiState(ready = true, queue = queue, current = queue[index], currentIndex = index)
        compose.setContent {
            MeloraTheme {
                PlayerAppearanceProvider(dark = true) {
                    QueuePageContent(
                        state = state.value, onClose = {}, isVisible = visible.value, motionEnabled = motion,
                        onSelect = { selectedIndex = it }, onRemove = { removedIndex = it },
                        modifier = Modifier.requiredSize(360.dp, 600.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row(index: Int) = compose.onNodeWithTag("queue-track-$index")
    private fun list() = compose.onNodeWithTag("playback-queue-list")
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot
    private fun select(index: Int) {
        state.value = state.value.copy(current = state.value.queue[index], currentIndex = index)
    }
    private fun assertCurrentAtTop(index: Int) {
        row(index).assertIsDisplayed().assertIsSelected()
        assertEquals("当前项应位于列表可见顶部", bounds(list()).top, bounds(row(index)).top, 1f)
    }
    private fun scrollValue(): Float = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun firstOpenLargeQueueShowsCurrentInsteadOfQueueBeginning() {
        show(index = 330)
        assertCurrentAtTop(330)
        row(0).assertDoesNotExist()
        compose.onNodeWithText("331 / 589").assertIsDisplayed()
    }

    @Test fun nextSongScrollsContinuouslyRatherThanJumpingOrReplacingTheList() {
        show()
        assertCurrentAtTop(30)
        val startTop = bounds(row(31)).top
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { select(31) }
            compose.mainClock.advanceTimeBy(96)
            val middleTop = bounds(row(31)).top
            val targetTop = bounds(list()).top
            assertTrue("中间帧必须向上移动: start=$startTop middle=$middleTop", middleTop < startTop - 1f)
            assertTrue("中间帧尚未瞬移到终点: middle=$middleTop target=$targetTop", middleTop > targetTop + 1f)
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
        assertTrue(bounds(row(29)).top > bounds(row(30)).top)
        row(29).performClick()
        compose.runOnIdle { assertEquals("必须传媒体索引，不能传展示行559", 29, selectedIndex) }
        compose.onNodeWithTag("queue-remove-29").performClick()
        compose.runOnIdle { assertEquals(29, removedIndex) }
        compose.runOnIdle { state.value = state.value.copy(mode = PlayMode.List, queueOrder = tracks.indices.toList()) }
        assertCurrentAtTop(30)
        compose.onNodeWithText("31 / 589").assertIsDisplayed()
        assertTrue(bounds(row(31)).top > bounds(row(30)).top)
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
        show(index = 0, queue = listOf(duplicate, duplicate, tracks[2]))
        row(0).assertIsSelected()
        row(1).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, selectedIndex) }
        compose.onNodeWithTag("queue-remove-1").performClick()
        compose.runOnIdle { assertEquals(1, removedIndex) }
        compose.runOnIdle { select(1) }
        row(1).assertIsDisplayed().assertIsSelected()
        row(0).assertIsNotSelected()
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
