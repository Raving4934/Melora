package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实播放器里保持触点并替换状态，记录 UI 命令，不启动服务或修改用户播放。 */
@RunWith(AndroidJUnit4::class)
class PlayerTransportGestureInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val first = UiTrack("gesture-a", "手势歌曲 A", "测试歌手", "测试专辑")
    private val second = UiTrack("gesture-b", "手势歌曲 B", "测试歌手", "测试专辑")
    private val state = mutableStateOf(PlayerUiState())
    private val seeks = mutableListOf<Pair<String?, Long>>()
    private val skips = mutableListOf<String>()
    private lateinit var back: PlayerSheetBackState
    private lateinit var density: Density
    private var touchSlop = 0f

    private fun playing(track: UiTrack, durationMs: Long = 120_000, positionMs: Long = 12_000) =
        PlayerUiState(
            ready = true, current = track, queue = listOf(first, second),
            currentIndex = if (track.uid == first.uid) 0 else 1,
            durationMs = durationMs, positionMs = positionMs,
        )

    private fun show(expanded: Boolean = false) {
        state.value = playing(first)
        val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))
        compose.setContent {
            density = LocalDensity.current
            touchSlop = LocalViewConfiguration.current.touchSlop
            CompositionLocalProvider(LocalSongListState provides shared) {
                MeloraTheme {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // 与已有导航测试相同的竖屏容器，避免设备宽屏改变被测入口。
                        Box(Modifier.size(minOf(maxWidth, 480.dp, maxHeight * 0.65f), minOf(maxHeight, 800.dp))) {
                            // 与 MeloraApp 一致：空队列卸载播放器和返回状态。
                            if (state.value.current != null) {
                                back = rememberPlayerSheetBackState()
                                ContinuousPlayerSheet(
                                    state = state.value,
                                    modifier = Modifier.fillMaxSize(),
                                    backState = back,
                                    onSeek = { seeks += state.value.current?.uid to it },
                                    onPrevious = { skips += "${state.value.current?.uid}:previous" },
                                    onNext = { skips += "${state.value.current?.uid}:next" },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.awaitStable(mini())
        if (expanded) {
            mini().performClick()
            awaitExpanded()
        }
    }

    private fun mini() = compose.onNodeWithTag("player-mini-track")
    private fun miniLeft() = mini().fetchSemanticsNode().positionInRoot.x
    private fun slider() = compose.onNodeWithTag("player-seek-slider")
    private fun sliderValue() = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun awaitExpanded() {
        compose.mainClock.advanceTimeUntil(5_000) {
            back.sheet.settledValue == PlayerSheetAnchor.Expanded && back.transition == null
        }
        compose.awaitStable("player-heading")
        compose.awaitStable(slider())
    }

    private fun beginSeek(): Float {
        val bounds = slider().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(Offset(bounds.left + bounds.width * 0.2f, bounds.center.y))
            moveTo(Offset(bounds.left + bounds.width * 0.7f, bounds.center.y), delayMillis = 300)
        }
        val preview = sliderValue()
        assertTrue("必须已进入真实 Slider 拖动预览", preview > state.value.durationMs * 0.5f)
        return preview
    }

    private fun assertFreshSeekTargets(track: UiTrack) {
        val preview = beginSeek()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(track.uid to preview.toLong()), seeks)
        }
    }

    private fun replaceTrackWhileSeeking(durationMs: Long) {
        show(expanded = true)
        beginSeek()
        compose.runOnIdle { state.value = playing(second, durationMs = durationMs, positionMs = 7_000) }
        assertEquals("新曲目必须立即清除旧预览", 7_000f, sliderValue(), 1f)
        // 不能只重置外部 dragging：旧触点继续 MOVE 也不能重启新 Slider 的内部拖动。
        compose.onRoot().performTouchInput { moveBy(Offset(6f, 0f)); up() }
        compose.runOnIdle { assertTrue("旧触点不能向新曲目提交 seek: $seeks", seeks.isEmpty()) }
        assertEquals(7_000f, sliderValue(), 1f)
        assertFreshSeekTargets(second)
    }

    @Test fun seekTrackReplacementWithSameDurationCancelsGestureAndPreview() {
        replaceTrackWhileSeeking(durationMs = 120_000)
    }

    @Test fun seekTrackReplacementWithShorterDurationCancelsGestureAndPreview() {
        replaceTrackWhileSeeking(durationMs = 30_000)
    }

    @Test fun seekTrackReplacementAwayAndBackDoesNotReviveOldTouch() {
        show(expanded = true)
        beginSeek()
        compose.runOnIdle { state.value = playing(second, positionMs = 7_000) }
        assertEquals(7_000f, sliderValue(), 1f)
        compose.runOnIdle { state.value = playing(first, positionMs = 3_000) }
        assertEquals(3_000f, sliderValue(), 1f)
        compose.onRoot().performTouchInput { moveBy(Offset(6f, 0f)); up() }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        assertFreshSeekTargets(first)
    }

    @Test fun seekSameTrackSamplesAndMetadataPreserveHeldPreviewAndCommitOnce() {
        show(expanded = true)
        val preview = beginSeek()
        compose.runOnIdle {
            state.value = state.value.copy(current = first.copy(title = "更新后的标题"), positionMs = 2_000)
        }
        assertEquals("同一 uid 的播放采样和元数据不能取消用户拖动", preview, sliderValue(), 1f)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(first.uid to preview.toLong()), seeks) }
        assertEquals(2_000f, sliderValue(), 1f)
    }

    @Test fun seekClearingQueueWhileHeldDoesNotCommit() {
        show(expanded = true)
        beginSeek()
        compose.runOnIdle { state.value = PlayerUiState(ready = true) }
        slider().assertDoesNotExist()
        compose.onRoot().performTouchInput { moveBy(Offset(6f, 0f)); up() }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
    }

    private fun beginMiniSwipe(direction: Int, distanceDp: Float = 80f) {
        val bounds = mini().fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.left + bounds.width * (if (direction < 0) 0.85f else 0.15f), bounds.center.y)
        val distance = with(density) { distanceDp.dp.toPx() } + touchSlop
        val end = start + Offset(direction * distance, 0f)
        compose.onRoot().performTouchInput {
            down(start)
            moveTo(end, delayMillis = 500)
            // 连续静止采样使松手速度为零；松手仍须按位移判断，不能当成取消。
            repeat(4) { moveTo(end, delayMillis = 100) }
        }
        compose.waitForIdle()
    }

    private fun assertMiniReset(originalLeft: Float) {
        compose.mainClock.advanceTimeBy(2_000)
        compose.awaitStable(mini())
        assertEquals("迷你条和共享封面端点必须复位", originalLeft, miniLeft(), 1f)
    }

    private fun cancelMiniSwipe(direction: Int) {
        show()
        val originalLeft = miniLeft()
        beginMiniSwipe(direction)
        assertTrue("取消前必须已越过实际切歌位移阈值",
            direction * (miniLeft() - originalLeft) > with(density) { 56.dp.toPx() })
        compose.onRoot().performTouchInput { cancel() }
        assertMiniReset(originalLeft)
        compose.runOnIdle { assertTrue("ACTION_CANCEL 不能切歌: $skips", skips.isEmpty()) }
        // 取消后的下一次正常手势只能产生一条命令。
        beginMiniSwipe(direction)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf("${first.uid}:${if (direction < 0) "next" else "previous"}"), skips)
        }
    }

    @Test fun miniSwipeCancelBeyondNextThresholdDoesNotSkip() {
        cancelMiniSwipe(direction = -1)
    }

    @Test fun miniSwipeCancelBeyondPreviousThresholdDoesNotSkip() {
        cancelMiniSwipe(direction = 1)
    }

    private fun releaseSlowMiniSwipe(direction: Int) {
        show()
        val originalLeft = miniLeft()
        beginMiniSwipe(direction)
        assertTrue("正常松手前必须已越过实际切歌位移阈值",
            direction * (miniLeft() - originalLeft) > with(density) { 56.dp.toPx() })
        compose.onRoot().performTouchInput { up() }
        assertMiniReset(originalLeft)
        compose.runOnIdle {
            assertEquals(listOf("${first.uid}:${if (direction < 0) "next" else "previous"}"), skips)
        }
    }

    @Test fun miniSwipeZeroVelocityReleaseBeyondNextThresholdSkipsExactlyOnce() {
        releaseSlowMiniSwipe(direction = -1)
    }

    @Test fun miniSwipeZeroVelocityReleaseBeyondPreviousThresholdSkipsExactlyOnce() {
        releaseSlowMiniSwipe(direction = 1)
    }

    @Test fun miniSwipeZeroVelocityReleaseBelowThresholdOnlyResets() {
        show()
        val originalLeft = miniLeft()
        beginMiniSwipe(direction = 1, distanceDp = 24f)
        compose.onRoot().performTouchInput { up() }
        assertMiniReset(originalLeft)
        compose.runOnIdle { assertTrue(skips.isEmpty()) }
    }

    @Test fun miniSwipeFastFlingBelowDistanceThresholdStillSkips() {
        show()
        val bounds = mini().fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.left + bounds.width * 0.85f, bounds.center.y)
        val end = start - Offset(with(density) { 40.dp.toPx() }, 0f)
        compose.onRoot().performTouchInput {
            swipeWithVelocity(start, end, endVelocity = 1_200f)
        }
        compose.runOnIdle { assertEquals(listOf("${first.uid}:next"), skips) }
    }

    @Test fun miniSwipeBecomingInactiveDuringExpansionDoesNotSkip() {
        show()
        beginMiniSwipe(direction = 1)
        compose.runOnIdle { back.requestExpand() }
        awaitExpanded()
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertTrue("迷你条退场必须取消而非提交手势", skips.isEmpty()) }
    }

    @Test fun miniSwipeTrackReplacementCancelsOldTouchAndResetsPreview() {
        show()
        val originalLeft = miniLeft()
        beginMiniSwipe(direction = 1)
        compose.runOnIdle { state.value = playing(second) }
        assertEquals(originalLeft, miniLeft(), 1f)
        compose.onRoot().performTouchInput { moveBy(Offset(6f, 0f)); up() }
        compose.runOnIdle { assertTrue(skips.isEmpty()) }
        beginMiniSwipe(direction = -1)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf("${second.uid}:next"), skips) }
    }

    @Test fun miniTapStillExpandsWithoutSkipping() {
        show()
        mini().performTouchInput { click() }
        awaitExpanded()
        compose.runOnIdle { assertTrue(skips.isEmpty()) }
    }

    @Test fun miniVerticalSwipeStillExpandsWithoutSkipping() {
        show()
        val bounds = mini().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            swipe(bounds.center, Offset(bounds.center.x, height * 0.2f), durationMillis = 500)
        }
        awaitExpanded()
        compose.runOnIdle { assertTrue(skips.isEmpty()) }
    }
}
