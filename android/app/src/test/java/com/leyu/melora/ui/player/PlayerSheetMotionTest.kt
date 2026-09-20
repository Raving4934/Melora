package com.leyu.melora.ui.player

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.pager.PageSize
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetMotionTest {
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
    fun miniAndFullTextNeverGhostOverEachOther() {
        for (step in 0..100) {
            val progress = step / 100f
            val mini = 1f - playerMotionPhase(progress, 0f, PlayerMiniFadeEnd)
            val full = playerMotionPhase(progress, 0.18f, 0.88f)
            assertTrue(mini == 0f || full == 0f)
            assertTrue(mini in 0f..1f && full in 0f..1f)
        }
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

    private fun anchors(travel: Float) = DraggableAnchors {
        PlayerSheetAnchor.Expanded at 0f
        PlayerSheetAnchor.Collapsed at travel
    }

    private fun target(offset: Float, settled: PlayerSheetAnchor, velocity: Float = 0f) =
        playerSheetTarget(offset, 800f, velocity, 300f, settled)
}
