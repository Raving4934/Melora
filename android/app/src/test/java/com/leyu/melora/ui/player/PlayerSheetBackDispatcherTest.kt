package com.leyu.melora.ui.player

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.BroadcastFrameClock
import com.leyu.melora.ui.common.RootBackCallback
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetBackDispatcherTest {
    @Test
    fun miniRegisteredBeforeCatalogStillOwnsBackAfterExpanding() = runBlocking {
        val host = Host()
        host.openCatalog() // The disabled mini callback was registered before this detail.
        host.state.sheet.snapTo(PlayerSheetAnchor.Expanded)
        host.back()
        assertEquals(listOf("Collapse"), host.actions)
    }

    @Test
    fun partialDragIncludingLastFractionBeforeCollapsedDoesNotExitCatalog() {
        for (distance in listOf(0.4f, 160f, 400f, 784f)) {
            val host = Host()
            host.openCatalog()
            host.state.sheet.dispatchRawDelta(-distance)
            host.back()
            host.back() // Pending collapse consumes repeated back before any animation frame.
            assertEquals("distance=$distance", listOf("Collapse", "Consume"), host.actions)
            assertFalse(host.catalog!!.isEnabled)
        }
    }

    @Test
    fun pendingAtCollapsedEndpointBlocksCatalogAndReleasesOnlyAfterCompletion() {
        val host = Host()
        host.openCatalog()
        host.state.pending = true // Opening was requested but coroutine/frame has not run.
        assertEquals(800f, host.state.sheet.offset, 0f)
        assertTrue(host.state.ownsBack)
        host.back()
        host.back()
        host.state.pending = false // Completed/cancelled at the exact collapsed endpoint.
        assertFalse(host.state.ownsBack)
        host.back()
        host.back()
        host.back()
        assertEquals(listOf("Consume", "Consume", "catalog", "hint", "background"), host.actions)
    }

    @Test
    fun realEntryAndExitAnimationsKeepLaterCatalogDisabled() = runBlocking {
        val clock = BroadcastFrameClock()
        val host = Host()
        host.openCatalog()
        for (target in listOf(PlayerSheetAnchor.Expanded, PlayerSheetAnchor.Collapsed)) {
            val animation = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                host.state.sheet.animateTo(target, tween(100))
            }
            try {
                assertTrue(host.state.sheet.isAnimationRunning)
                host.back() // Animation is running even before its first frame.
                var frameTime = 1_000_000_000L
                repeat(3) {
                    clock.sendFrame(frameTime)
                    frameTime += 16_000_000L
                    yield()
                }
                assertTrue(host.state.sheet.isAnimationRunning)
                assertTrue(host.state.sheet.offset > 0f && host.state.sheet.offset < 800f)
                host.back()
                repeat(20) {
                    if (!animation.isCompleted) {
                        clock.sendFrame(frameTime)
                        frameTime += 16_000_000L
                        yield()
                    }
                }
                assertTrue("animation must reach its endpoint with the test clock", animation.isCompleted)
                assertEquals(target, host.state.sheet.settledValue)
            } finally {
                animation.cancelAndJoin()
            }
        }
        assertEquals(listOf("Consume", "Consume", "Consume", "Consume"), host.actions)
        assertFalse(host.state.ownsBack)
        host.back()
        assertEquals("catalog", host.actions.last())
    }

    @Test
    fun expandedInternalDialogThenQueueThenSheetRetainPriorityOverLaterCatalog() = runBlocking {
        val host = Host()
        host.openCatalog() // Newer even than the player's internal callback.
        host.state.sheet.snapTo(PlayerSheetAnchor.Expanded)
        host.dialogOpen = true
        host.queueVisible = true
        host.back()
        host.back()
        host.back()
        host.queueVisible = false
        host.state.pending = false
        host.back()
        assertEquals(listOf("dialog", "ReturnToPlayer", "Consume", "Collapse"), host.actions)
    }

    @Test
    fun transitionSuspendsExpandedChildHandlersButNotOuterPlayer() = runBlocking {
        val host = Host()
        host.openCatalog()
        host.state.sheet.snapTo(PlayerSheetAnchor.Expanded)
        host.dialogOpen = true
        host.state.pending = true
        host.back()
        host.state.pending = false
        host.back()
        assertEquals(listOf("Consume", "dialog"), host.actions)
    }

    @Test
    fun settledMiniPassesThroughToCatalogThenNormalRootDoubleBack() {
        val host = Host()
        host.openCatalog()
        host.back()
        host.back()
        host.back()
        assertEquals(listOf("catalog", "hint", "background"), host.actions)
    }

    @Test
    fun restoredExpandedStateOwnsBackBeforeAnchorsAreMeasured() {
        val state = PlayerSheetBackState(AnchoredDraggableState(PlayerSheetAnchor.Expanded))
        assertTrue(state.ownsBack)
        assertEquals(PlayerSheetBackAction.Collapse, state.action())
    }

    /** Real AndroidX dispatcher, with the same enabled scopes and shared state as the app. */
    private class Host {
        val actions = mutableListOf<String>()
        val state = PlayerSheetBackState(AnchoredDraggableState(PlayerSheetAnchor.Collapsed)).apply {
            sheet.updateAnchors(DraggableAnchors {
                PlayerSheetAnchor.Expanded at 0f
                PlayerSheetAnchor.Collapsed at 800f
            })
        }
        var dialogOpen = false
        var queueVisible = false
        var catalog: OnBackPressedCallback? = null
        private val root = RootBackCallback({ 100L }, { actions += "hint" }, { actions += "background" })
        private val dispatcher = OnBackPressedDispatcher().apply { addCallback(root) }
        private val player = callback {
            root.reset()
            val action = state.action(queueVisible)
            actions += action.name
            if (action == PlayerSheetBackAction.Collapse || action == PlayerSheetBackAction.ReturnToPlayer) {
                state.pending = true
            }
        }.apply { isEnabled = false; dispatcher.addCallback(this) }
        private val dialog = callback {
            root.reset()
            actions += "dialog"
            dialogOpen = false
        }.apply { isEnabled = false; dispatcher.addCallback(this) }

        fun openCatalog() {
            catalog = callback {
                root.reset()
                actions += "catalog"
                catalog?.remove()
                catalog = null
            }.also { dispatcher.addCallback(it) }
        }

        fun back() {
            val action = state.action(queueVisible)
            player.isEnabled = action != PlayerSheetBackAction.PassThrough
            // MeloraApp supplies this to the entire underlying page tree, not to the player.
            catalog?.isEnabled = !state.ownsBack
            dialog.isEnabled = dialogOpen && state.sheet.offset <= 8f && action != PlayerSheetBackAction.Consume
            if (state.ownsBack || catalog != null) root.reset()
            dispatcher.onBackPressed()
        }
    }

    companion object {
        private fun callback(onBack: () -> Unit) = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        }
    }
}
