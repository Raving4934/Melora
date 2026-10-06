package com.leyu.melora.ui.player

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.pager.PageSize
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetMotionTest {
    @Test
    fun movingSheetKeepsTheVisibleTopContourAndBottomCornersOutsideTheViewport() {
        for (density in listOf(1f, 3.15f)) {
            for (travel in listOf(1f, 24f, 100f, 165f, 272f, 792f, 1200f)) {
                for (step in 0..1000) {
                    val progress = step / 1000f
                    val offset = travel * (1f - progress)
                    val shape = playerSheetShape(progress, offset.dp)
                    val size = Size(400f * density, (travel + 88f) * density)
                    val scale = Density(density)
                    val top = shape.topStart.toPx(size, scale)
                    val bottom = shape.bottomStart.toPx(size, scale)
                    val originalRadius = 22f * playerMotionPhase(progress, 0f, 0.04f) *
                        (1f - playerMotionPhase(progress, 0.85f, 1f)) * density
                    assertEquals(originalRadius, top, 0.0001f)
                    assertEquals(top, shape.topEnd.toPx(size, scale), 0f)
                    assertEquals(bottom, shape.bottomEnd.toPx(size, scale), 0f)
                    assertTrue("bottom corner must remain below the viewport", bottom <= offset * density + 0.0001f)
                    if (travel >= 165f) assertEquals("ordinary windows use uniform corners", top, bottom, 0f)
                }
            }
        }
    }

    @Test
    fun sheetBackIsOwnedUntilTheExactCollapsedEndpointIncludingPartialDrags() {
        for (settled in PlayerSheetAnchor.entries) {
            for (target in PlayerSheetAnchor.entries) {
                for (progress in listOf(0f, 0.0005f, 0.2f, 0.5f, 0.98f, 1f)) {
                    val fullyCollapsed = settled == PlayerSheetAnchor.Collapsed &&
                        target == PlayerSheetAnchor.Collapsed && progress == 0f
                    assertEquals(
                        "settled=$settled target=$target progress=$progress",
                        if (fullyCollapsed) PlayerSheetBackAction.PassThrough else PlayerSheetBackAction.Collapse,
                        playerSheetBackAction(settled, target, progress),
                    )
                }
            }
        }
    }

    @Test
    fun entryRequestsCollapseWhileExitTransitionAndAnimationConsumeBack() {
        assertEquals(PlayerSheetBackAction.Collapse, playerSheetBackAction(
            PlayerSheetAnchor.Collapsed, PlayerSheetAnchor.Expanded, 0.5f,
            animationRunning = true, transition = PlayerSheetTransition.Expand,
        ))
        assertEquals(PlayerSheetBackAction.Consume, playerSheetBackAction(
            PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Collapsed, 0.5f, animationRunning = true,
        ))
        assertEquals(PlayerSheetBackAction.Consume, playerSheetBackAction(
            PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
            transition = PlayerSheetTransition.Collapse,
        ))
        assertEquals(PlayerSheetBackAction.Consume, playerSheetBackAction(
            PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
            transition = PlayerSheetTransition.ReturnToPlayer,
        ))
    }

    @Test
    fun expandedQueueReturnsToPlayerBeforeCollapsingButPartialQueueCollapses() {
        assertEquals(PlayerSheetBackAction.ReturnToPlayer, playerSheetBackAction(
            PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
            transition = PlayerSheetTransition.OpenQueue, queueReturnTarget = PlayerSheetAnchor.Expanded,
        ))
        assertEquals(
            PlayerSheetBackAction.ReturnToPlayer,
            playerSheetBackAction(PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f, queueReturnTarget = PlayerSheetAnchor.Expanded),
        )
        assertEquals(
            PlayerSheetBackAction.Collapse,
            playerSheetBackAction(PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 0.5f, queueReturnTarget = PlayerSheetAnchor.Expanded),
        )
        assertEquals(
            PlayerSheetBackAction.Consume,
            playerSheetBackAction(
                PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
                transition = PlayerSheetTransition.ReturnToPlayer, queueReturnTarget = PlayerSheetAnchor.Expanded,
            ),
        )
        assertEquals(
            PlayerSheetBackAction.PassThrough,
            playerSheetBackAction(PlayerSheetAnchor.Collapsed, PlayerSheetAnchor.Collapsed, 0f, queueReturnTarget = PlayerSheetAnchor.Expanded),
        )
    }

    @Test
    fun miniQueueReturnsToUnderlyingPageAndStillGuardsTransitionBack() {
        assertEquals(PlayerSheetBackAction.Collapse, playerSheetBackAction(
            PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
            transition = PlayerSheetTransition.OpenQueue, queueReturnTarget = PlayerSheetAnchor.Collapsed,
        ))
        assertEquals(PlayerSheetBackAction.Collapse,
            playerSheetBackAction(PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
                queueReturnTarget = PlayerSheetAnchor.Collapsed))
        assertEquals(PlayerSheetBackAction.Consume,
            playerSheetBackAction(
                PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, 1f,
                transition = PlayerSheetTransition.Collapse, queueReturnTarget = PlayerSheetAnchor.Collapsed,
            ))
    }

    @Test
    fun replacingEntryWithCollapseKeepsOwnershipAndSameIntentDoesNotRestart() = runBlocking {
        val clock = BroadcastFrameClock()
        val scopeJob = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + clock + scopeJob)
        val state = PlayerSheetBackState(AnchoredDraggableState(PlayerSheetAnchor.Collapsed)).apply {
            sheet.updateAnchors(anchors(800f))
        }
        val oldFinallyStarted = CompletableDeferred<Unit>()
        val releaseOldFinally = CompletableDeferred<Unit>()
        var collapseStarts = 0
        var frameTime = 1_000_000_000L

        try {
            state.transitionTo(scope, PlayerSheetTransition.Expand) {
                try {
                    state.sheet.animateTo(PlayerSheetAnchor.Expanded, tween(100))
                } finally {
                    withContext(NonCancellable) {
                        oldFinallyStarted.complete(Unit)
                        releaseOldFinally.await()
                    }
                }
            }
            yield()
            val entryJob = scopeJob.children.single()
            assertEquals(PlayerSheetTransition.Expand, state.transition)
            assertTrue(state.sheet.isAnimationRunning)

            repeat(2) {
                clock.sendFrame(frameTime)
                frameTime += 16_000_000L
                yield()
            }
            assertTrue(state.sheet.offset > 0f && state.sheet.offset < 800f)

            state.transitionTo(scope, PlayerSheetTransition.Collapse) {
                collapseStarts++
                state.sheet.animateTo(PlayerSheetAnchor.Collapsed, tween(100))
            }
            oldFinallyStarted.await()
            yield()
            assertEquals(1, collapseStarts)
            assertEquals(PlayerSheetTransition.Collapse, state.transition)
            assertTrue(state.sheet.isAnimationRunning)

            state.transitionTo(scope, PlayerSheetTransition.Collapse) { collapseStarts++ }
            assertEquals("same intent must not launch a second animation", 1, collapseStarts)
            assertEquals(PlayerSheetTransition.Collapse, state.transition)

            releaseOldFinally.complete(Unit)
            entryJob.join() // Wait through PlayerSheetBackState's finally, not just the test block's finally.
            assertEquals("cancelled entry finally must not release the active collapse", PlayerSheetTransition.Collapse, state.transition)
            assertEquals(PlayerSheetBackAction.Consume, state.action())
            assertTrue(state.sheet.isAnimationRunning)

            repeat(20) {
                if (state.sheet.isAnimationRunning) {
                    clock.sendFrame(frameTime)
                    frameTime += 16_000_000L
                    yield()
                }
            }
            assertEquals(PlayerSheetAnchor.Collapsed, state.sheet.settledValue)
            assertEquals(1, collapseStarts)
            assertEquals(null, state.transition)
        } finally {
            releaseOldFinally.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun detailScopeIsReleasedOnlyAtTheSettledCollapsedEndpoint() {
        assertTrue(playerSheetIsCollapsed(PlayerSheetAnchor.Collapsed, PlayerSheetAnchor.Collapsed, 0f))
        assertFalse(playerSheetIsCollapsed(PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Collapsed, 0f))
        assertFalse(playerSheetIsCollapsed(PlayerSheetAnchor.Collapsed, PlayerSheetAnchor.Expanded, 0f))
        assertFalse(playerSheetIsCollapsed(PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Expanded, .2f))
        assertFalse(playerSheetIsCollapsed(PlayerSheetAnchor.Collapsed, PlayerSheetAnchor.Collapsed, .1f))
        assertFalse(playerSheetIsCollapsed(PlayerSheetAnchor.Collapsed, PlayerSheetAnchor.Collapsed, Float.NaN))
    }

    @Test
    fun progressUsesPhysicalTravelAndClampsOvershoot() {
        assertEquals(0f, playerSheetProgress(800f, 800f), 0f)
        assertEquals(0.5f, playerSheetProgress(400f, 800f), 0f)
        assertEquals(1f, playerSheetProgress(0f, 800f), 0f)
        assertEquals(1f, playerSheetProgress(-40f, 800f), 0f)
        assertEquals(0f, playerSheetProgress(900f, 800f), 0f)
        assertEquals(0f, playerSheetProgress(Float.NaN, 800f), 0f)
        assertEquals(0f, playerSheetProgress(0f, 0f), 0f)
    }

    @Test
    fun smallDragReturnsToItsSettledEndpoint() {
        assertEquals(PlayerSheetAnchor.Expanded, target(100f, PlayerSheetAnchor.Expanded))
        assertEquals(PlayerSheetAnchor.Collapsed, target(700f, PlayerSheetAnchor.Collapsed))
        assertEquals(PlayerSheetAnchor.Collapsed, target(300f, PlayerSheetAnchor.Expanded))
        assertEquals(PlayerSheetAnchor.Expanded, target(500f, PlayerSheetAnchor.Collapsed))
    }

    @Test
    fun releaseVelocityCanReverseAnInterruptedDrag() {
        assertEquals(PlayerSheetAnchor.Expanded, target(600f, PlayerSheetAnchor.Collapsed, -500f))
        assertEquals(PlayerSheetAnchor.Collapsed, target(200f, PlayerSheetAnchor.Expanded, 500f))
    }

    @Test
    fun measuredArtworkKeepsExactEndpointsAndRemainsSquare() {
        val mini = Rect(14f, 9f, 60f, 55f)
        val full = Rect(32f, 120f, 332f, 420f)
        assertEquals(mini, playerArtworkBounds(mini, full, -1f))
        assertEquals(full, playerArtworkBounds(mini, full, 2f))
        for (step in 0..100) {
            val rect = playerArtworkBounds(mini, full, step / 100f)
            assertEquals(rect.width, rect.height, 0.001f)
            assertTrue(rect.width in mini.width..full.width)
        }
        assertEquals(Rect(23f, 64.5f, 196f, 237.5f), playerArtworkBounds(mini, full, 0.5f))
    }

    @Test
    fun fullContentLeavesEarlyAndMiniAppearsOnlyNearItsEndpoint() {
        var previousFull = 0f
        var previousMini = 1f
        for (step in 0..1000) {
            val progress = step / 1000f
            val full = playerMotionPhase(progress, PlayerContentFadeStart, 1f)
            val mini = 1f - playerMotionPhase(progress, 0f, PlayerMiniFadeEnd)
            assertTrue("不能同时显示全屏控件和Mini: p=$progress", full == 0f || mini == 0f)
            assertTrue(full in 0f..1f && mini in 0f..1f)
            assertTrue(full >= previousFull && mini <= previousMini)
            if (progress <= PlayerContentFadeStart) assertEquals(0f, full, 0f)
            if (progress >= PlayerMiniFadeEnd) assertEquals(0f, mini, 0f)
            previousFull = full
            previousMini = mini
        }
        assertEquals(1f, previousFull, 0f)
        assertEquals(0f, previousMini, 0f)
        // 中段正文已退场；面板背景仍须遮挡底页，由真实截图回归单独验证。
        assertEquals(0f, playerMotionPhase(0.5f, PlayerContentFadeStart, 1f), 0f)
    }

    @Test
    fun landscapeHasTwoPanesEvenOnCompactPhones() {
        assertTrue(playerUsesTwoPanes(800f, 360f))
        assertTrue(playerUsesTwoPanes(600f, 360f))
        assertFalse(playerUsesTwoPanes(360f, 800f))
        assertTrue(playerUsesTwoPanes(540f, 320f))
        assertFalse(playerUsesTwoPanes(320f, 540f))
        assertFalse(playerUsesTwoPanes(800f, 800f))
    }

    @Test
    fun expandedPlayerDependsOnAvailableWindowNotDeviceOrientation() {
        assertTrue(playerUsesExpandedLayout(1280f, 800f))
        assertTrue(playerUsesExpandedLayout(900f, 1280f))
        assertTrue(playerUsesTwoPanes(900f, 1280f))
        assertTrue(playerUsesExpandedLayout(840f, 480f))
        assertFalse(playerUsesExpandedLayout(839f, 800f))
        assertFalse(playerUsesExpandedLayout(1280f, 479f))
        assertFalse(playerUsesExpandedLayout(360f, 800f))
        assertFalse(playerUsesExpandedLayout(800f, 360f))
    }

    @Test
    fun skipBurstAllowsThreeImmediateTapsThenRejectsTheFourth() {
        val gate = PlayerSkipBurstGate()

        assertTrue(gate.tryAcquire(0L))
        assertTrue(gate.tryAcquire(1L))
        assertTrue(gate.tryAcquire(2L))
        assertFalse(gate.tryAcquire(3L))
    }

    @Test
    fun rejectedTapExtendsTheCooldownFromTheRejectedTap() {
        val gate = PlayerSkipBurstGate()

        repeat(3) { assertTrue(gate.tryAcquire(it.toLong())) }
        assertFalse(gate.tryAcquire(100L))
        assertFalse(gate.tryAcquire(699L))
        assertFalse(gate.tryAcquire(700L))
        // 699/700本身也是点击，静默窗口必须从最后被拒绝的700重新计算。
        assertTrue(gate.tryAcquire(1_300L))
    }

    @Test
    fun cooldownRestoresTheBudgetAtExactlySixHundredMilliseconds() {
        val gate = PlayerSkipBurstGate()

        repeat(3) { assertTrue(gate.tryAcquire(it.toLong())) }
        assertFalse(gate.tryAcquire(3L))
        assertTrue(gate.tryAcquire(603L))
        assertTrue(gate.tryAcquire(604L))
        assertTrue(gate.tryAcquire(605L))
        assertFalse(gate.tryAcquire(606L))
    }

    @Test
    fun alternatingDirectionsStillShareOneBudget() {
        val gate = PlayerSkipBurstGate()
        val executedDirections = mutableListOf<String>()

        fun tap(direction: String, at: Long) {
            if (gate.tryAcquire(at)) executedDirections += direction
        }

        tap("previous", 0L)
        tap("next", 1L)
        tap("previous", 2L)
        tap("next", 3L)

        assertEquals(listOf("previous", "next", "previous"), executedDirections)
    }

    @Test
    fun rejectedTapDoesNotReplayLaterWithoutAnotherTap() {
        val gate = PlayerSkipBurstGate()
        var executions = 0

        fun tap(at: Long) {
            if (gate.tryAcquire(at)) executions += 1
        }

        tap(0L)
        tap(1L)
        tap(2L)
        tap(3L)
        assertEquals(3, executions)

        // 时间经过本身不会触发任何补执行；只有新的点击才会尝试取得预算。
        assertEquals(3, executions)
        tap(603L)
        assertEquals(4, executions)
    }

    @Test
    fun playerPageSnapTrajectoryIsMonotonicBoundedAcrossPhoneAndTabletTravel() {
        val sampleIntervalNanos = 16_000_000L
        val earlySampleNanos = 100_000_000L

        for (distance in listOf(360f, 720f, 1080f, 2560f, 3200f)) {
            val animation = playerPageSnapAnimation(0f, distance)
            val earlyValue = animation.getValueFromNanos(earlySampleNanos)
            assertTrue(
                "distance=$distance earlyValue=$earlyValue",
                earlyValue in 0f..(distance * 0.75f),
            )

            var previousValue = animation.getValueFromNanos(0L)
            var playTimeNanos = sampleIntervalNanos
            while (playTimeNanos < animation.durationNanos) {
                val value = animation.getValueFromNanos(playTimeNanos)
                assertTrue("distance=$distance time=$playTimeNanos value=$value", value >= -0.01f)
                assertTrue("distance=$distance time=$playTimeNanos value=$value", value <= distance + 0.01f)
                assertTrue(
                    "distance=$distance time=$playTimeNanos previous=$previousValue value=$value",
                    value >= previousValue - 0.01f,
                )
                previousValue = value
                playTimeNanos += sampleIntervalNanos
            }

            val endValue = animation.getValueFromNanos(animation.durationNanos)
            assertTrue("distance=$distance endValue=$endValue", endValue >= previousValue - 0.01f)
            assertEquals(distance, endValue, 0f)
        }
    }

    @Test
    fun playerPageSnapTrajectoryIsSymmetricInBothDirectionsAndReachesEachEndpoint() {
        val sampleIntervalNanos = 16_000_000L

        for (distance in listOf(360f, 720f, 1080f, 2560f, 3200f)) {
            val forward = playerPageSnapAnimation(0f, distance)
            val backward = playerPageSnapAnimation(distance, 0f)
            // 比较避开 duration 边界，避免结束阈值的取整差异掩盖轨迹对称性。
            val comparisonEndNanos = minOf(forward.durationNanos, backward.durationNanos) - sampleIntervalNanos

            var playTimeNanos = 0L
            while (playTimeNanos <= comparisonEndNanos) {
                val forwardValue = forward.getValueFromNanos(playTimeNanos)
                val backwardValue = backward.getValueFromNanos(playTimeNanos)
                assertEquals(
                    "distance=$distance time=$playTimeNanos",
                    distance,
                    forwardValue + backwardValue,
                    0.1f,
                )
                playTimeNanos += sampleIntervalNanos
            }

            assertEquals(distance, forward.getValueFromNanos(forward.durationNanos), 0f)
            assertEquals(0f, backward.getValueFromNanos(backward.durationNanos), 0f)
        }
    }

    @Test
    fun actualDragStateTracksReversalAndClampsBothEdges() {
        val state = AnchoredDraggableState(PlayerSheetAnchor.Collapsed)
        state.updateAnchors(anchors(800f))
        assertEquals(800f, state.offset, 0f)
        assertEquals(-300f, state.dispatchRawDelta(-300f), 0f)
        assertEquals(500f, state.offset, 0f)
        assertEquals(200f, state.dispatchRawDelta(200f), 0f)
        assertEquals(700f, state.offset, 0f)
        assertEquals(-700f, state.dispatchRawDelta(-1000f), 0f)
        assertEquals(0f, state.offset, 0f)
        assertEquals(800f, state.dispatchRawDelta(2000f), 0f)
        assertEquals(800f, state.offset, 0f)
    }

    @Test
    fun resizingKeepsTheChosenAnchorAndRepeatedUpdatesAreIdempotent() {
        for (anchor in PlayerSheetAnchor.entries) {
            val state = AnchoredDraggableState(anchor)
            state.updateAnchors(anchors(800f))
            repeat(3) { state.updateAnchors(anchors(300f), newTarget = anchor) }
            assertEquals(if (anchor == PlayerSheetAnchor.Collapsed) 300f else 0f, state.offset, 0f)
            assertEquals(anchor, state.targetValue)
        }
    }

    @Test
    fun portraitKeepsContentInsetsWithoutInsettingThePagerViewport() {
        for (controls in listOf(false, true)) {
            val padding = playerPaneContentPadding(twoPanes = false, controls = controls)
            for (direction in LayoutDirection.entries) {
                assertEquals(22.dp, padding.calculateStartPadding(direction))
                assertEquals(22.dp, padding.calculateEndPadding(direction))
            }
            assertEquals(0.dp, padding.calculateTopPadding())
            assertEquals(0.dp, padding.calculateBottomPadding())
        }
    }

    @Test
    fun landscapeMovesOnlyOuterInsetsInsidePagesAndKeepsOriginalContentWidth() {
        val artwork = playerPaneContentPadding(twoPanes = true, controls = false)
        val controls = playerPaneContentPadding(twoPanes = true, controls = true)
        for (direction in LayoutDirection.entries) {
            assertEquals(22.dp, artwork.calculateStartPadding(direction))
            assertEquals(0.dp, artwork.calculateEndPadding(direction))
            assertEquals(0.dp, controls.calculateStartPadding(direction))
            assertEquals(22.dp, controls.calculateEndPadding(direction))
        }
        // 旧布局先扣左右22再平分；新布局平分后各自扣外侧22，内容宽度完全相同。
        for (width in listOf(540f, 800f, 1280f)) {
            val oldContentWidth = (width - 44f - 28f) / 2f
            val newContentWidth = (width - 28f) / 2f - 22f
            assertEquals(oldContentWidth, newContentWidth, 0f)
        }
    }

    @Test
    fun fullWidthPagerKeepsBothNeighboursOutsideTheViewportAtRest() {
        with(PageSize.Fill) {
            with(Density(1f)) {
                for (viewport in listOf(320, 396, 800, 1440)) {
                    // Pager不扣正文的22dp；否则pageWidth < viewport时下一页就会露出。
                    val pageWidth = calculateMainAxisPageSize(availableSpace = viewport, pageSpacing = 0)
                    assertEquals(viewport, pageWidth)
                    for (currentPage in 0..2) {
                        for (neighbour in 0..2) {
                            if (neighbour == currentPage) continue
                            val left = (neighbour - currentPage) * pageWidth
                            val right = left + pageWidth
                            assertTrue("page $neighbour leaks into $currentPage", right <= 0 || left >= viewport)
                        }
                    }
                    // 区分本次回归：如果仍把双侧22dp交给contentPadding，下一页会提前44dp进入。
                    val insetPageWidth = calculateMainAxisPageSize(availableSpace = viewport - 44, pageSpacing = 0)
                    assertTrue(insetPageWidth < viewport)
                }
            }
        }
    }

    private fun playerPageSnapAnimation(initialValue: Float, targetValue: Float) =
        TargetBasedAnimation(
            animationSpec = PlayerPageSnapSpec,
            typeConverter = Float.VectorConverter,
            initialValue = initialValue,
            targetValue = targetValue,
            initialVelocity = 0f,
        )

    private fun anchors(travel: Float) = DraggableAnchors {
        PlayerSheetAnchor.Expanded at 0f
        PlayerSheetAnchor.Collapsed at travel
    }

    private fun target(offset: Float, settled: PlayerSheetAnchor, velocity: Float = 0f) =
        playerSheetTarget(offset, 800f, velocity, 300f, settled)
}
