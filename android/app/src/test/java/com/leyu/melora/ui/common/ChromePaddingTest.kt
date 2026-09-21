package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class ChromePaddingTest {
    @Test fun selfRegistrationDoesNotInvalidateUnchangedMaterialButNestedHeadersDo() {
        val geometry = ChromeHeaderGeometry()
        val own = HazeState(initialBlurEnabled = true)
        val source = derivedStateOf { geometry.sourceFor(1, own) }
        val bounds = derivedStateOf { geometry.minimumTop to geometry.materialBottom(96.dp) }
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        observer.start()
        try {
            observer.observeReads(Any(), { invalidations++ }) {
                assertSame(own, source.value)
                assertEquals(0.dp to 96.dp, bounds.value)
            }
            geometry.update(Any(), 0.dp, 96.dp, 1, own)
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            val child = HazeState(initialBlurEnabled = true)
            geometry.update(Any(), 96.dp, 144.dp, 2, child)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            assertSame(child, source.value)
            assertEquals(0.dp to 144.dp, bounds.value)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun firstFrameMaterialAlreadyCoversTheKnownHeader() {
        val geometry = ChromeHeaderGeometry()
        assertEquals(96.dp, geometry.materialBottom(96.dp))
        val owner = Any()
        geometry.update(owner, 0.dp, 96.dp, 1, null)
        assertEquals(96.dp, geometry.materialBottom(96.dp))
        geometry.update(Any(), 96.dp, 144.dp, 2, null)
        assertEquals(144.dp, geometry.materialBottom(96.dp))
        // 输入区加高后不必等上一帧登记结果才能扩大遮罩。
        assertEquals(180.dp, geometry.materialBottom(180.dp))
    }


    @Test
    fun movingPagesKeepIndependentStatusAndTitleGradients() {
        val hub = ChromeHeaderGeometry()
        val detail = ChromeHeaderGeometry()
        val hubSource = HazeState(initialBlurEnabled = true)
        val detailSource = HazeState(initialBlurEnabled = true)
        hub.update(Any(), 0.dp, 96.dp, 1, hubSource)
        detail.update(Any(), 0.dp, 144.dp, 1, detailSource)
        // 导航目标变化不能让离场页的渐变范围/采样源跳到另一页。
        assertEquals(0.dp, hub.minimumTop)
        assertEquals(96.dp, hub.maximumBottom)
        assertSame(hubSource, hub.sourceFor(1, hubSource))
        assertEquals(0.dp, detail.minimumTop)
        assertEquals(144.dp, detail.maximumBottom)
        assertSame(detailSource, detail.sourceFor(1, detailSource))
    }

    @Test
    fun startupSystemInsetUsesStableFallbackBeforeComposeInsetsArrive() {
        assertEquals(96, resolveSystemTopInsetPx(0, 96, 88))
        assertEquals(96, resolveSystemTopInsetPx(96, 0, 88))
        assertEquals(88, resolveSystemTopInsetPx(0, 0, 88))
    }

    @Test
    fun hiddenStatusBarKeepsTheSameStableLayoutInset() {
        val visible = resolveStableSystemTopInsetPx(0, 96, 96, 88)
        val hidden = resolveStableSystemTopInsetPx(visible, 0, 0, 88)
        assertEquals(visible, hidden)
    }

    @Test
    fun orientationStartsWithResourceFallbackUntilRealInsetsArrive() {
        val initial = resolveStableSystemTopInsetPx(0, 0, 0, 88)
        assertEquals(88, initial)
        assertEquals(96, resolveStableSystemTopInsetPx(initial, 96, 96, 88))
    }

    @Test
    fun landscapeForegroundInsetsKeepCutoutEdgesWithoutDuplicatingStatusBarSpace() {
        val density = Density(2f)
        for ((left, right) in listOf(28 to 0, 0 to 28, 16 to 28)) {
            val horizontal = WindowInsets(left, 24, right, 16).only(WindowInsetsSides.Horizontal)
            for (direction in LayoutDirection.entries) {
                assertEquals(left, horizontal.getLeft(density, direction))
                assertEquals(right, horizontal.getRight(density, direction))
            }
            assertEquals(0, horizontal.getTop(density))
            assertEquals(0, horizontal.getBottom(density))
        }
    }

    @Test
    fun headerOnlyAddsToListTopPadding() {
        val value = withChromeTopInset(PaddingValues(16.dp, 8.dp, 20.dp, 24.dp), 96.dp, LayoutDirection.Ltr)
        assertEquals(104.dp, value.calculateTopPadding())
        assertEquals(16.dp, value.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(20.dp, value.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(24.dp, value.calculateBottomPadding())
    }

    @Test
    fun noHeaderPreservesExistingContentPadding() {
        val value = withChromeTopInset(PaddingValues(12.dp), 0.dp, LayoutDirection.Ltr)
        assertEquals(12.dp, value.calculateTopPadding())
        assertEquals(12.dp, value.calculateBottomPadding())
    }

    @Test
    fun rtlKeepsStartAndEndRatherThanSwappingAbsoluteEdges() {
        val value = withChromeTopInset(PaddingValues(start = 4.dp, end = 20.dp), 64.dp, LayoutDirection.Rtl)
        assertEquals(20.dp, value.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(4.dp, value.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(64.dp, value.calculateTopPadding())
    }

    @Test
    fun combinedTitleAndActionBarReserveBothHeightsOnce() {
        val value = withChromeTopInset(PaddingValues(top = 8.dp), 96.dp + 48.dp, LayoutDirection.Ltr)
        assertEquals(152.dp, value.calculateTopPadding())
    }

    @Test
    fun hidingStatusBarKeepsContentTopSpace() {
        val base = PaddingValues(top = 8.dp, bottom = 220.dp)
        val visible = withChromeTopInset(base, 96.dp, LayoutDirection.Ltr)
        val hidden = withChromeTopInset(base, 96.dp, LayoutDirection.Ltr)
        assertEquals(visible.calculateTopPadding(), hidden.calculateTopPadding())
        assertEquals(220.dp, hidden.calculateBottomPadding())
    }

    @Test
    fun nestedHeadersShareOneGradientExtentAndReleaseItOnNavigation() {
        val geometry = ChromeHeaderGeometry()
        val title = Any()
        val actions = Any()
        val titleSource = HazeState(initialBlurEnabled = true)
        val actionsSource = HazeState(initialBlurEnabled = true)

        geometry.update(title, 0.dp, 96.dp, depth = 1, source = titleSource)
        geometry.update(actions, 96.dp, 144.dp, depth = 1, source = actionsSource)
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(144.dp, geometry.maximumBottom)

        geometry.update(title, 0.dp, 64.dp, depth = 1, source = titleSource)
        assertEquals(144.dp, geometry.maximumBottom)

        geometry.remove(actions)
        assertEquals(64.dp, geometry.maximumBottom)
        geometry.remove(title)
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(0.dp, geometry.maximumBottom)
        assertSame(titleSource, geometry.sourceFor(1, titleSource))
    }

    @Test
    fun statusBarAndBothHeaderRowsShareTheGradientFromScreenTop() {
        val geometry = ChromeHeaderGeometry()
        val titleSource = HazeState(initialBlurEnabled = true)
        val actionsSource = HazeState(initialBlurEnabled = true)

        geometry.update(Any(), 0.dp, 96.dp, depth = 1, source = titleSource)
        geometry.update(Any(), 96.dp, 144.dp, depth = 1, source = actionsSource)

        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(144.dp, geometry.maximumBottom)
    }

    @Test
    fun nestedBodyChoosesInnermostSourceAndFloatingBarDoesNotStealIt() {
        val geometry = ChromeHeaderGeometry()
        val outerBodySource = HazeState(initialBlurEnabled = true)
        val innerBodySource = HazeState(initialBlurEnabled = true)

        geometry.update(Any(), 0.dp, 96.dp, depth = 1, source = outerBodySource)
        geometry.update(Any(), 96.dp, 144.dp, depth = 2, source = innerBodySource)
        geometry.update(Any(), 144.dp, 192.dp, depth = 3, source = null)

        assertSame(innerBodySource, geometry.sourceFor(1, outerBodySource))
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(192.dp, geometry.maximumBottom)
    }

    @Test
    fun removingChildRestoresParentSourceAndRange() {
        val geometry = ChromeHeaderGeometry()
        val parent = Any()
        val child = Any()
        val parentSource = HazeState(initialBlurEnabled = true)
        val childSource = HazeState(initialBlurEnabled = true)

        geometry.update(parent, 0.dp, 96.dp, depth = 1, source = parentSource)
        geometry.update(child, 96.dp, 144.dp, depth = 2, source = childSource)
        assertSame(childSource, geometry.sourceFor(1, parentSource))
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(144.dp, geometry.maximumBottom)

        geometry.remove(child)
        assertSame(parentSource, geometry.sourceFor(1, parentSource))
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(96.dp, geometry.maximumBottom)
    }

    @Test
    fun hidingStatusBarKeepsGradientAtScreenTopAndOnlyMovesHeaderBottoms() {
        val geometry = ChromeHeaderGeometry()
        val title = Any()
        val actions = Any()
        val titleSource = HazeState(initialBlurEnabled = true)
        val actionsSource = HazeState(initialBlurEnabled = true)
        val visibleTitleTop = 0.dp
        val visibleTitleBottom = 96.dp
        val visibleActionsTop = 96.dp
        val visibleActionsBottom = 144.dp
        val hiddenTitleBottom = 64.dp
        val hiddenActionsTop = 64.dp

        geometry.update(title, visibleTitleTop, visibleTitleBottom, depth = 1, source = titleSource)
        geometry.update(actions, visibleActionsTop, visibleActionsBottom, depth = 1, source = actionsSource)
        assertEquals(visibleTitleBottom, visibleActionsTop)
        assertEquals(visibleTitleTop, geometry.minimumTop)
        assertEquals(visibleActionsBottom, geometry.maximumBottom)

        geometry.update(title, 0.dp, hiddenTitleBottom, depth = 1, source = titleSource)
        geometry.update(actions, hiddenActionsTop, 112.dp, depth = 1, source = actionsSource)
        assertEquals(hiddenTitleBottom, hiddenActionsTop)
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(112.dp, geometry.maximumBottom)
        assertEquals(0.dp, visibleTitleTop - geometry.minimumTop)
        assertEquals(32.dp, visibleActionsBottom - geometry.maximumBottom)
    }

    @Test
    fun duplicateRegistrationReplacesTheSameOwnerWithoutLeavingStaleGeometry() {
        val geometry = ChromeHeaderGeometry()
        val owner = Any()
        val staleSource = HazeState(initialBlurEnabled = true)
        val currentSource = HazeState(initialBlurEnabled = true)

        geometry.update(owner, 32.dp, 96.dp, depth = 1, source = staleSource)
        geometry.update(owner, 64.dp, 128.dp, depth = 2, source = currentSource)

        assertEquals(64.dp, geometry.minimumTop)
        assertEquals(128.dp, geometry.maximumBottom)
        assertSame(currentSource, geometry.sourceFor(1, staleSource))

        geometry.remove(owner)
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(0.dp, geometry.maximumBottom)
        assertSame(currentSource, geometry.sourceFor(1, currentSource))
    }
    @Test
    fun playlistControlsPinOnlyWhenTheirNaturalPositionReachesTheHeader() {
        assertFalse(shouldPinPlaylistControls(0, controlBarTop = 175, headerBottom = 96))
        assertTrue(shouldPinPlaylistControls(0, controlBarTop = 96, headerBottom = 96))
        assertTrue(shouldPinPlaylistControls(1, controlBarTop = 60, headerBottom = 96))
    }

    @Test
    fun playlistControlsRemainPinnedWhenTheirPlaceholderLeavesTheViewport() {
        assertTrue(shouldPinPlaylistControls(8, controlBarTop = null, headerBottom = 96))
        assertFalse(shouldPinPlaylistControls(0, controlBarTop = null, headerBottom = 96))
    }

    @Test
    fun returningFromSelectionAndScrollingHomeDoesNotRetainAStickyOffset() {
        assertTrue(shouldPinPlaylistControls(8, controlBarTop = null, headerBottom = 96))
        assertFalse(shouldPinPlaylistControls(0, controlBarTop = 175, headerBottom = 96))
        assertFalse(shouldPinPlaylistControls(0, controlBarTop = 143, headerBottom = 64))
    }

    @Test
    fun statusOnlyParentAndDetailTitleUseTheSameBodySource() {
        val geometry = ChromeHeaderGeometry()
        val statusSource = HazeState(initialBlurEnabled = true)
        val detailSource = HazeState(initialBlurEnabled = true)
        geometry.update(Any(), 0.dp, 32.dp, depth = 1, source = statusSource)
        geometry.update(Any(), 32.dp, 96.dp, depth = 2, source = detailSource)
        geometry.update(Any(), 96.dp, 146.dp, depth = 2, source = null)
        assertEquals(0.dp, geometry.minimumTop)
        assertEquals(146.dp, geometry.maximumBottom)
        assertSame(detailSource, geometry.sourceFor(1, statusSource))
    }

    @Test
    fun newDetailNeverSamplesTheParentWhichContainsItsOwnHeader() {
        val geometry = ChromeHeaderGeometry()
        val parent = HazeState()
        val detail = HazeState()
        geometry.update(Any(), 0.dp, 32.dp, depth = 1, source = parent)
        // 详情的SideEffect尚未注册时也必须直接选自己的正文。
        assertSame(detail, geometry.sourceFor(depth = 2, ownSource = detail))
        val owner = Any()
        geometry.update(owner, 32.dp, 96.dp, depth = 2, source = detail)
        assertSame(detail, geometry.sourceFor(depth = 1, ownSource = parent))
        assertSame(detail, geometry.sourceFor(depth = 2, ownSource = detail))
        geometry.remove(owner)
        assertSame(parent, geometry.sourceFor(depth = 1, ownSource = parent))
    }

    @Test
    fun replacingDetailDoesNotReuseThePreviousSiblingSamplingLayer() {
        val geometry = ChromeHeaderGeometry()
        val previous = HazeState()
        val next = HazeState()
        geometry.update(Any(), 32.dp, 96.dp, depth = 2, source = previous)
        assertSame(next, geometry.sourceFor(depth = 2, ownSource = next))
    }

    @Test
    fun detailTopIsStableWhileParentNavigationHeightIsBeingRemoved() {
        val systemTop = 32.dp
        val parentFrames = listOf(96.dp, 96.dp, 32.dp, 32.dp)
        val detailTops = parentFrames.map { chromeHeaderTopInset(it, systemTop, stackOnParent = false) }
        assertEquals(List(parentFrames.size) { systemTop }, detailTops)
        // 返回栏、分类和操作栏始终从同一原点测量，正文不会跟着父主栏的64dp一起上跳。
        val contentTops = detailTops.map { it + 64.dp + 48.dp + 48.dp }
        assertEquals(List(parentFrames.size) { 192.dp }, contentTops)
    }

    @Test
    fun hiddenStatusBarDoesNotLeaveOneFrameOfOldParentNavigationSpace() {
        for (parentTop in listOf(96.dp, 64.dp, 0.dp)) {
            assertEquals(0.dp, chromeHeaderTopInset(parentTop, 0.dp, stackOnParent = false))
        }
    }

    @Test
    fun searchInputStillStacksUnderThePrimarySearchNavigation() {
        assertEquals(96.dp, chromeHeaderTopInset(96.dp, 32.dp, stackOnParent = true))
        assertEquals(64.dp, chromeHeaderTopInset(64.dp, 0.dp, stackOnParent = true))
    }

    @Test
    fun replacingAnotherDetailDoesNotAccumulateEitherHeaderOrSystemInset() {
        for (systemTop in listOf(0.dp, 24.dp, 32.dp, 44.dp)) {
            val previousDetailBottom = systemTop + 64.dp + 48.dp
            val nextDetailTop = chromeHeaderTopInset(previousDetailBottom, systemTop, stackOnParent = false)
            assertEquals(systemTop, nextDetailTop)
            assertEquals(systemTop + 64.dp, nextDetailTop + 64.dp)
        }
    }

    @Test
    fun fixedPlaylistControlsHaveTheirFullExtentBeforeAnySizeCallback() {
        for (scale in listOf(1f, 2.625f, 3.75f)) {
            val density = Density(scale)
            val geometry = ChromeHeaderGeometry()
            val titleSource = HazeState()
            val controls = Any()
            val height = with(density) { PlaylistControlsHeight.roundToPx().toDp() }
            geometry.update(Any(), 0.dp, 96.dp, depth = 1, source = titleSource)
            geometry.update(controls, 96.dp, 96.dp + height, depth = 1, source = null)
            assertEquals(96.dp + height, geometry.maximumBottom)
            assertSame(titleSource, geometry.sourceFor(1, titleSource))
            geometry.remove(controls)
            assertEquals(96.dp, geometry.maximumBottom)
        }
    }

    @Test
    fun delegatedHeaderKeepsTheFullWidthForLandscapeMaterial() {
        val padding = PaddingValues(start = 32.dp, top = 24.dp, end = 8.dp, bottom = 64.dp)
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val shell = chromeBodyPadding(padding, direction, delegatesHeader = true)
            assertEquals(0.dp, shell.calculateLeftPadding(direction))
            assertEquals(0.dp, shell.calculateRightPadding(direction))
            assertEquals(64.dp, shell.calculateBottomPadding())
            val page = chromeBodyPadding(padding, direction, delegatesHeader = false)
            assertEquals(padding.calculateLeftPadding(direction), page.calculateLeftPadding(direction))
            assertEquals(padding.calculateRightPadding(direction), page.calculateRightPadding(direction))
            assertEquals(64.dp, page.calculateBottomPadding())
        }
    }

    @Test
    fun delegatingTheWholeHeaderDoesNotMoveContentOrDoubleTheStatusInset() {
        for (systemTop in listOf(0.dp, 24.dp, 32.dp, 44.dp)) {
            for (bar in listOf(64.dp, 110.dp, 120.dp)) {
                val splitHeader = chromeContentTopInset(0.dp, systemTop, systemTop, root = false, topBarHeight = bar)
                val wholeHeader = chromeContentTopInset(0.dp, 0.dp, systemTop, root = true, topBarHeight = bar)
                assertEquals(splitHeader, wholeHeader)
                assertEquals(systemTop + bar, wholeHeader)
            }
        }
    }

    @Test
    fun knownTopBarHeightPreventsFirstFrameContentDrop() {
        assertEquals(96.dp, chromeContentTopInset(0.dp, 0.dp, 32.dp, root = true, topBarHeight = 64.dp))
        assertEquals(142.dp, chromeContentTopInset(0.dp, 32.dp, 32.dp, root = false, topBarHeight = 110.dp))
    }

    @Test
    fun unknownTopBarHeightStillUsesMeasuredScaffoldInset() {
        assertEquals(128.dp, chromeContentTopInset(128.dp, 0.dp, 32.dp, root = true, topBarHeight = null))
    }

}
