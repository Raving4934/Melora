package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPlaybackQueueTest {
    @Test
    fun pageExtensionPreservesManualNextAndRemovedSongs() {
        val previous = CollectionQueueSource(listOf("a", "b", "removed"))
        val visible = listOf("a", "b", "removed", "c", "d")
        val active = listOf("b", "next")

        val change = collectionQueueUpdate(previous, visible, 1, active.size, active::get)

        assertEquals(0, change.targetIndex)
        assertEquals(listOf("b", "next", "c", "d"), change.applyTo(active, visible))
        assertSame(visible, change.source.knownUids)
    }

    @Test
    fun refreshMergesBeforeSurvivingAnchorsWithoutMovingExistingItems() {
        val previous = CollectionQueueSource(listOf("a", "d"))
        val visible = listOf("first", "a", "b", "c", "d", "last")
        val active = listOf("a", "next", "d")

        val change = collectionQueueUpdate(previous, visible, 4, active.size, active::get)

        assertEquals(5, change.targetIndex)
        assertEquals(listOf("first", "a", "next", "b", "c", "d", "last"), change.applyTo(active, visible))
    }

    @Test
    fun reversedAnchorsLeaveManuallyPromotedSongInPlace() {
        val previous = CollectionQueueSource(listOf("a", "b", "next"))
        val visible = listOf("a", "b", "new", "next")
        val active = listOf("a", "next", "b")

        val change = collectionQueueUpdate(previous, visible, 2, active.size, active::get)

        assertEquals(3, change.targetIndex)
        assertEquals(listOf("a", "next", "b", "new"), change.applyTo(active, visible))
    }

    @Test
    fun temporarilyShorterSourceKeepsKnowledgeOfRemovedSongs() {
        val previous = CollectionQueueSource(listOf("a", "removed", "b"))
        val firstPage = listOf("a")
        val active = listOf("a", "b")
        val reduced = collectionQueueUpdate(previous, firstPage, 0, active.size, active::get)
        val expanded = firstPage + listOf("removed", "b", "new")

        val change = collectionQueueUpdate(reduced.source, expanded, 3, active.size, active::get)

        assertTrue(reduced.insertions.isEmpty())
        assertSame(firstPage, reduced.source.visibleUids)
        assertEquals(2, change.targetIndex)
        assertEquals(listOf("a", "b", "new"), change.applyTo(active, expanded))
    }

    @Test
    fun selectingRemovedSongRestoresOnlyThatSong() {
        val visible = listOf("a", "b", "c", "d")
        val source = CollectionQueueSource(visible)
        val active = listOf("a", "next", "d")

        val change = collectionQueueUpdate(source, visible, 1, active.size, active::get)

        assertEquals(2, change.targetIndex)
        assertEquals(listOf("a", "next", "b", "d"), change.applyTo(active, visible))
    }

    @Test
    fun restoringWindowAddsOnlyTracksMissingFromWindowAndFullQueueRemovalsPersist() {
        val visible = listOf("a", "removed", "b", "c")
        val removed = missingCollectionUids(visible, sequenceOf("a", "b", "c", "manual"))
        val savedWindow = listOf("b")
        val source = CollectionQueueSource(savedWindow + removed)

        val change = collectionQueueUpdate(source, visible, 2, savedWindow.size, savedWindow::get)

        assertEquals(listOf("removed"), removed)
        assertEquals(1, change.targetIndex)
        assertEquals(listOf("a", "b", "c"), change.applyTo(savedWindow, visible))
    }

    @Test
    fun duplicateUidsUseOccurrenceForSelectionAndMissingCounts() {
        val visible = listOf("a", "removed", "a", "d")
        val active = listOf("a", "a", "d")
        val source = CollectionQueueSource(visible)

        val change = collectionQueueUpdate(source, visible, 2, active.size, active::get)
        val expanded = visible + "a"
        val appended = collectionQueueUpdate(source, expanded, 4, active.size, active::get)

        assertEquals(1, change.targetIndex)
        assertTrue(change.insertions.isEmpty())
        assertEquals(3, appended.targetIndex)
        assertEquals(listOf("a", "a", "d", "a"), appended.applyTo(active, expanded))
        assertEquals(listOf("removed", "a"), missingCollectionUids(visible, sequenceOf("a", "d")))
    }

    @Test
    fun matchingSlotDoesNotSelectAnEarlierOccurrenceAfterQueueEdits() {
        val visible = listOf("a", "b", "a", "d")
        val active = listOf("b", "d", "a", "a")
        val source = CollectionQueueSource(visible)

        val change = collectionQueueUpdate(source, visible, 2, active.size, active::get)

        assertEquals(3, change.targetIndex)
        assertTrue(change.insertions.isEmpty())
        assertEquals(active, change.applyTo(active, visible))
    }

    @Test
    fun aRefreshedUniqueRowSelectsTheFirstSurvivingOccurrence() {
        val previous = CollectionQueueSource(listOf("b", "a", "a", "d"))
        val visible = listOf("b", "c", "a", "d")
        val active = listOf("a", "b", "a", "c", "d")
        val refreshed = collectionQueueUpdate(previous, visible, 2, active.size, active::get)

        val repeated = collectionQueueUpdate(refreshed.source, visible, 2, active.size, active::get)

        assertEquals(0, refreshed.targetIndex)
        assertEquals(refreshed.targetIndex, repeated.targetIndex)
        assertTrue(repeated.insertions.isEmpty())
    }

    @Test
    fun repeatedSelectionReadsOnlyItsSlotInWindowOfTenThousandTracks() {
        val visible = List(10_000) { it.toString() }
        val source = CollectionQueueSource(visible)
        var reads = 0

        val change = collectionQueueUpdate(source, visible, 9_998, visible.size) { index ->
            reads++
            visible[index]
        }

        assertEquals(9_998, change.targetIndex)
        assertEquals(1, reads)
        assertSame(source, change.source)
        assertTrue(change.insertions.isEmpty())
    }

    private fun CollectionQueueUpdate.applyTo(active: List<String>, source: List<String>): List<String> =
        active.toMutableList().apply {
            insertions.asReversed().forEach { addAll(it.index, it.sourceIndices.map(source::get)) }
        }
}
