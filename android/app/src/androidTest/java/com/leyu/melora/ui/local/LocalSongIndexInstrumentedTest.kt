package com.leyu.melora.ui.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSongIndexInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchingEverySlotSelectsMappedIndexAndHapticsOnce() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = INDEX_LABELS.withIndex().associate { it.value to it.index },
            haptics = haptics,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            INDEX_LABELS.indices.forEach { index ->
                click(cellOffset(index))
            }
        }

        assertEquals(INDEX_LABELS.indices.toList(), selected)
        assertEquals(List(INDEX_LABELS.size) { HapticFeedbackType.TextHandleMove }, haptics.calls)
    }

    @Test
    fun draggingOnlyHapticsOnNewSlotsAndIndependentClicksRepeatFeedback() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = INDEX_LABELS.withIndex().associate { it.value to it.index },
            haptics = haptics,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            val first = cellOffset(4)
            down(first)
            moveTo(first + Offset(0f, 1f))
            moveTo(cellOffset(5))
            moveTo(cellOffset(5) + Offset(0f, 1f))
            moveTo(cellOffset(8))
            up()
        }
        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            click(cellOffset(8))
        }

        assertEquals(listOf(4, 5, 8, 8), selected)
        assertEquals(4, haptics.calls.size)
        assertEquals(List(4) { HapticFeedbackType.TextHandleMove }, haptics.calls)
    }

    @Test
    fun rapidReverseDragUsesTheSlotAtEachDirectionChange() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = INDEX_LABELS.withIndex().associate { it.value to it.index },
            haptics = haptics,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            down(cellOffset(0))
            moveTo(cellOffset(INDEX_LABELS.lastIndex))
            moveTo(cellOffset(0))
            up()
        }

        assertEquals(listOf(0, INDEX_LABELS.lastIndex, 0), selected)
        assertEquals(3, haptics.calls.size)
    }

    @Test
    fun emptySlotStillHapticsWithoutSelecting() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = mapOf("A" to 41),
            haptics = haptics,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            click(cellOffset(INDEX_LABELS.indexOf("#")))
        }

        assertEquals(emptyList<Int>(), selected)
        assertEquals(listOf(HapticFeedbackType.TextHandleMove), haptics.calls)
    }

    @Test
    fun cancelledTouchClearsPreviewAfterSelectingTheInitialSlot() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = INDEX_LABELS.withIndex().associate { it.value to it.index },
            haptics = haptics,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            down(cellOffset(10))
            cancel()
        }

        composeRule.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()
        assertEquals(listOf(10), selected)
        assertEquals(listOf(HapticFeedbackType.TextHandleMove), haptics.calls)
    }

    @Test
    fun smallRailKeepsTopAndBottomSlotsTouchable() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = INDEX_LABELS.withIndex().associate { it.value to it.index },
            haptics = haptics,
            height = 24.dp,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithTag(RAIL_TAG).performTouchInput {
            click(Offset(centerX, 0.1f))
            click(Offset(centerX, height - 0.1f))
        }

        assertEquals(listOf(0, INDEX_LABELS.lastIndex), selected)
        assertEquals(2, haptics.calls.size)
    }

    @Test
    fun semanticClickSelectsTheMappedSlotAndHaptics() {
        val haptics = RecordingHapticFeedback()
        val selected = mutableListOf<Int>()
        setIndex(
            sections = mapOf("Q" to 73),
            haptics = haptics,
            onSelect = { selected += it },
        )

        composeRule.onNodeWithContentDescription("跳转到 Q").performClick()

        assertEquals(listOf(73), selected)
        assertEquals(listOf(HapticFeedbackType.TextHandleMove), haptics.calls)
    }

    private fun setIndex(
        sections: Map<String, Int>,
        haptics: RecordingHapticFeedback,
        height: Dp = 448.dp,
        onSelect: (Int) -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                Box(Modifier.fillMaxWidth().height(height)) {
                    LocalSongIndex(
                        sections = sections,
                        currentSection = null,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }

    private fun TouchInjectionScope.cellOffset(index: Int): Offset = Offset(
        x = centerX,
        y = (index + 0.5f) * height / INDEX_LABELS.size,
    )

    private class RecordingHapticFeedback : HapticFeedback {
        val calls = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            calls += hapticFeedbackType
        }
    }

    private companion object {
        const val RAIL_TAG = "local-song-index"
        const val PREVIEW_TAG = "local-song-index-preview"
        val INDEX_LABELS = listOf("0", "#") + ('A'..'Z').map(Char::toString)
    }
}
