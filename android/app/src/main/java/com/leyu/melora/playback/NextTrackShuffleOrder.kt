package com.leyu.melora.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import java.util.Random

@OptIn(UnstableApi::class)
internal fun shuffleWithNextTrack(order: IntArray, next: Int, seed: Long = System.nanoTime()): ShuffleOrder {
    val delegate = DefaultShuffleOrder(order, seed)
    val current = delegate.getPreviousIndex(next)
    return if (current < 0) delegate else NextTrackShuffleOrder(delegate, current, next, Random(seed))
}

/** 仍由 Media3 随机安排新增条目，只守住用户指定的下一首。 */
@OptIn(UnstableApi::class)
private class NextTrackShuffleOrder(
    private val delegate: ShuffleOrder,
    private val current: Int,
    private val next: Int,
    private val random: Random,
) : ShuffleOrder by delegate {
    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val clone = delegate.cloneAndInsert(insertionIndex, insertionCount)
        fun shifted(index: Int): Int = if (index >= insertionIndex) index + insertionCount else index
        val from = shifted(current)
        val to = shifted(next)
        val order = if (clone.getNextIndex(from) == to) clone else {
            val shuffled = clone.shuffledIndices()
            val afterCurrent = shuffled.indexOf(from) + 1
            shuffled.copyInto(shuffled, afterCurrent + 1, afterCurrent, shuffled.indexOf(to))
            shuffled[afterCurrent] = to
            DefaultShuffleOrder(shuffled, random.nextLong())
        }
        return NextTrackShuffleOrder(order, from, to, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val order = delegate.cloneAndRemove(indexFrom, indexToExclusive)
        val removed = indexFrom until indexToExclusive
        if (current in removed || next in removed) return order
        val count = indexToExclusive - indexFrom
        fun shifted(index: Int): Int = if (index >= indexToExclusive) index - count else index
        return NextTrackShuffleOrder(order, shifted(current), shifted(next), random)
    }

    override fun cloneAndMove(fromIndex: Int, toIndexExclusive: Int, newIndex: Int): ShuffleOrder {
        val count = toIndexExclusive - fromIndex
        fun moved(index: Int): Int = when {
            index in fromIndex until toIndexExclusive -> newIndex + index - fromIndex
            index in newIndex until fromIndex -> index + count
            index in toIndexExclusive until newIndex + count -> index - count
            else -> index
        }
        val shuffled = delegate.shuffledIndices()
        for (index in shuffled.indices) shuffled[index] = moved(shuffled[index])
        return NextTrackShuffleOrder(DefaultShuffleOrder(shuffled, random.nextLong()), moved(current), moved(next), random)
    }

    // 继承委托的 cloneAndClear / cloneAndSet，重建队列不带上旧的指定关系。
    private fun ShuffleOrder.shuffledIndices(): IntArray {
        var index = firstIndex
        return IntArray(length) { index.also { index = getNextIndex(it) } }
    }
}
