package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
    fun hidingStatusBarOnlyRemovesItsTopSpace() {
        val base = PaddingValues(top = 8.dp, bottom = 220.dp)
        val visible = withChromeTopInset(base, 96.dp, LayoutDirection.Ltr)
        val hidden = withChromeTopInset(base, 64.dp, LayoutDirection.Ltr)
        assertEquals(32.dp, visible.calculateTopPadding() - hidden.calculateTopPadding())
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

}
