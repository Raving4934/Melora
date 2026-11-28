package com.leyu.melora.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class NextTrackShuffleOrderTest {
    private val initial = intArrayOf(2, 0, 1, 4, 3)

    @Test
    fun pagedInsertionsPreserveExplicitNextAndRandomizeAddedTracks() {
        val newTrackOrders = mutableSetOf<List<Int>>()
        repeat(25) { seed ->
            val before = shuffleWithNextTrack(initial, next = 1, seed = seed.toLong())
            val middle = before.cloneAndInsert(1, 3)
            val after = middle.cloneAndInsert(middle.length, 6)

            assertEquals(4, middle.getNextIndex(0))
            assertEquals(4, after.getNextIndex(0))
            assertEquals(0, after.getPreviousIndex(4))
            assertEquals(middle.indices(), after.indices().filter { it < middle.length })
            assertEquals(listOf(5, 0, 4, 7, 6), middle.indices().filter { it == 0 || it >= 4 })
            newTrackOrders.add(after.indices().filter { it >= middle.length })
        }
        assertTrue(newTrackOrders.size > 1)
    }

    @Test
    fun removingOtherTracksKeepsTheChosenPairThroughLaterInsertions() {
        val before = shuffleWithNextTrack(initial, next = 1, seed = 7L)
        val remaining = before.cloneAndRemove(2, 4)
        val after = remaining.cloneAndInsert(0, 10)

        assertEquals(listOf(0, 1, 2), remaining.indices())
        assertEquals(listOf(10, 11, 12), after.indices().filter { it >= 10 })
        assertEquals(11, after.getNextIndex(10))
    }

    @Test
    fun removingEitherEndpointUsesDefaultShuffleForLaterInsertions() {
        for (remove in listOf(0, 1)) {
            val original = shuffleWithNextTrack(initial, next = 1, seed = 9L)
            val expected = DefaultShuffleOrder(initial, 9L).cloneAndRemove(remove, remove + 1).cloneAndInsert(1, 10)
            val after = original.cloneAndRemove(remove, remove + 1).cloneAndInsert(1, 10)
            assertEquals(expected.indices(), after.indices())
        }
    }

    @Test
    fun movingTheRawRangeRemapsBothEndsOfThePair() {
        val original = shuffleWithNextTrack(initial, next = 1, seed = 10L)
        val moved = original.cloneAndMove(0, 2, 3)

        assertEquals(listOf(0, 3, 4, 2, 1), moved.indices())
        assertEquals(4, moved.cloneAndInsert(moved.length, 10).getNextIndex(3))
    }

    @Test
    fun clearingOrReplacingQueueDropsTheOldPair() {
        val original = shuffleWithNextTrack(initial, next = 1, seed = 8L)
        val empty = original.cloneAndClear()
        val expected = DefaultShuffleOrder(initial, 8L).cloneAndClear()

        assertTrue(empty.indices().isEmpty())
        assertEquals(expected.cloneAndInsert(0, 7).indices(), empty.cloneAndInsert(0, 7).indices())
        assertEquals(
            DefaultShuffleOrder(initial, 20L).cloneAndSet(7, 3).indices(),
            shuffleWithNextTrack(initial, next = 1, seed = 20L).cloneAndSet(7, 3).indices(),
        )
    }

    private fun ShuffleOrder.indices(): List<Int> {
        var index = firstIndex
        val indices = List(length) { index.also { index = getNextIndex(it) } }
        assertEquals((0 until length).toList(), indices.sorted())
        assertEquals(-1, index)
        assertEquals(indices.lastOrNull() ?: -1, lastIndex)
        indices.forEachIndexed { position, raw -> assertEquals(indices.getOrNull(position - 1) ?: -1, getPreviousIndex(raw)) }
        return indices
    }
}
