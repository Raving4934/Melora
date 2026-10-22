package com.leyu.melora.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueuePersistenceTest {
    @Test
    fun queueOrderDefaultsToTheRawQueueIndices() {
        assertTrue(PlayerUiState().queueOrder.isEmpty())
        assertEquals(listOf(0, 1), PlayerUiState(queue = listOf(track("a"), track("b"))).queueOrder)
    }

    @Test
    fun fingerprintChangesWhenSameSizedQueueContentOrIndexChanges() {
        val first = listOf(track("a"), track("b"))
        val second = listOf(track("a"), track("c"))

        assertNotEquals(playbackQueueFingerprint(first, 0), playbackQueueFingerprint(second, 0))
        assertNotEquals(playbackQueueFingerprint(first, 0), playbackQueueFingerprint(first, 1))
    }

    @Test
    fun singleClickReusesAnExistingTrackWithoutReplacingTheQueue() {
        val queue = listOf("a", "b", "c")
        val target = singleTrackQueueTarget(queue, 0, "c")
        assertEquals(2, target.index)
        assertFalse(target.insert)
        assertEquals(listOf("a", "b", "c"), queue)
    }

    @Test
    fun singleClickInsertsOnlyTheSelectedTrackAfterCurrent() {
        val queue = mutableListOf("a", "b", "c")
        val target = singleTrackQueueTarget(queue, 1, "x")
        assertTrue(target.insert)
        queue.add(target.index, "x")
        assertEquals(listOf("a", "b", "x", "c"), queue)
        val repeated = singleTrackQueueTarget(queue, target.index, "x")
        assertFalse(repeated.insert)
        assertEquals(target.index, repeated.index)
    }

    @Test
    fun singleClickHandlesEmptyQueueAndMissingCurrentIndex() {
        assertEquals(SingleTrackQueueTarget(0, true), singleTrackQueueTarget(emptyList(), -1, "a"))
        assertEquals(SingleTrackQueueTarget(0, true), singleTrackQueueTarget(listOf("a"), -1, "b"))
        assertEquals(SingleTrackQueueTarget(1, true), singleTrackQueueTarget(listOf("a"), 0, "b"))
    }

    @Test
    fun singleClickDoesNotConfuseTracksFromDifferentPlatforms() {
        assertTrue(singleTrackQueueTarget(listOf("kw_123"), 0, "wy_123").insert)
        assertFalse(singleTrackQueueTarget(listOf("kw_123"), 0, "kw_123").insert)
    }

    @Test
    fun repeatedClicksAcrossALargeQueueDoNotGrowOrReorderIt() {
        val queue = (0 until 2000).map { "track_$it" }
        repeat(500) { i ->
            val index = i * 3
            assertEquals(SingleTrackQueueTarget(index, false), singleTrackQueueTarget(queue, i, queue[index]))
        }
        assertEquals(2000, queue.size)
    }

    @Test
    fun nextRequestKeepsCurrentAndAlreadyNextTracksInPlace() {
        assertEquals(NextTrackQueueTarget(1, 1), nextTrackQueueTarget(listOf("a", "b", "c"), 1, "b"))
        assertEquals(NextTrackQueueTarget(2, 2), nextTrackQueueTarget(listOf("a", "b", "c"), 1, "c"))
        // 队列原本允许重复曲目；不移动前一个同 UID 条目而意外堆出两个“下一首”。
        assertEquals(NextTrackQueueTarget(2, 2), nextTrackQueueTarget(listOf("c", "b", "c"), 1, "c"))
        assertEquals(NextTrackQueueTarget(2, 2), nextTrackQueueTarget(listOf("b", "c", "b"), 2, "b"))
    }

    @Test
    fun nextRequestHandlesEmptyQueueMissingIndexAndSourceIdentity() {
        assertEquals(NextTrackQueueTarget(-1, 0), nextTrackQueueTarget(emptyList(), -1, "a"))
        assertEquals(NextTrackQueueTarget(1, 0), nextTrackQueueTarget(listOf("a", "b"), -1, "b"))
        assertEquals(NextTrackQueueTarget(-1, 0), nextTrackQueueTarget(listOf("a"), -1, "b"))
        assertEquals(NextTrackQueueTarget(-1, 1), nextTrackQueueTarget(listOf("kw_123"), 0, "wy_123"))
    }

    @Test
    fun nextRequestMovesExistingTrackWithoutGrowingQueueOrReorderingOtherTracks() {
        for (size in 1..20) for (currentIndex in 0 until size) for (selected in 0..size) {
            val queue = (0 until size).map { "track_$it" }.toMutableList()
            val before = queue.toList()
            val current = queue[currentIndex]
            val uid = "track_$selected"
            val target = nextTrackQueueTarget(queue, currentIndex, uid)
            if (target.fromIndex >= 0) queue.removeAt(target.fromIndex)
            queue.add(target.toIndex, uid)
            assertEquals(before.filterNot { it == uid }, queue.filterNot { it == uid })
            assertEquals(size + if (selected == size) 1 else 0, queue.size)
            assertEquals(1, queue.count { it == uid })
            val newCurrent = queue.indexOf(current)
            if (uid != current) assertEquals(uid, queue[newCurrent + 1])
            else assertEquals(before, queue)
            val repeated = nextTrackQueueTarget(queue, newCurrent, uid)
            assertEquals("重复请求不得继续改队列", repeated.fromIndex, repeated.toIndex)
        }
    }

    @Test
    fun shuffleNextPreservesTrackIdentityThroughRawIndexMovesAndIsIdempotent() {
        val random = kotlin.random.Random(7)
        for (size in 1..25) repeat(3) {
            val order = (0 until size).shuffled(random)
            for (current in order) for (next in 0..size) {
                val queue = (0 until size).map { "track_$it" }.toMutableList()
                val before = order.map(queue::get)
                val selected = "track_$next"
                val target = nextTrackQueueTarget(queue, current, selected)
                val result = nextTrackShuffleOrder(order, current, target)
                if (target.fromIndex >= 0) queue.removeAt(target.fromIndex)
                queue.add(target.toIndex, selected)
                val ids = result.map(queue::get)
                val newCurrent = queue.indexOf("track_$current")
                assertEquals(queue.indices.toSet(), result.toSet())
                assertEquals(queue.size, result.size)
                if (next == current) assertEquals(before, ids)
                else {
                    assertEquals(selected, ids[ids.indexOf("track_$current") + 1])
                    assertEquals(before.filterNot { it == selected }, ids.filterNot { it == selected })
                }
                assertEquals(result, nextTrackShuffleOrder(result, newCurrent, nextTrackQueueTarget(queue, newCurrent, selected)))
            }
        }
        assertEquals(listOf(0), nextTrackShuffleOrder(emptyList(), -1, NextTrackQueueTarget(-1, 0)))
    }

    @Test
    fun clearAndExitCancelPendingPlaybackAndLyricsEvenBeforeControllerConnects() {
        // 直接构造冷连接待处理状态，不为测试向生产入口增加适配器或setter。
        val owner = PlaybackController
        val jobField = owner.javaClass.getDeclaredField("queueLoadJob").apply { isAccessible = true }
        val lyricJobField = owner.javaClass.getDeclaredField("lyricJob").apply { isAccessible = true }
        val actionField = owner.javaClass.getDeclaredField("pendingPlayback").apply { isAccessible = true }
        val detailUidField = owner.javaClass.getDeclaredField("detailUid").apply { isAccessible = true }
        val stateField = owner.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        val lyricStateField = owner.javaClass.getDeclaredField("_lyric").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(owner) as MutableStateFlow<PlayerUiState>
        @Suppress("UNCHECKED_CAST")
        val lyricState = lyricStateField.get(owner) as MutableStateFlow<PlayerLyric?>
        val previous = state.value
        val previousLyric = lyricState.value
        val previousLyricJob = lyricJobField.get(owner)
        val previousDetailUid = detailUidField.get(owner)
        try {
            for (end in listOf(owner::clearQueue, { owner.exitPlayback() })) {
                val pending = Job()
                val lyricJob = Job()
                jobField.set(owner, pending)
                lyricJobField.set(owner, lyricJob)
                actionField.set(owner, PendingPlaybackSelection(listOf(track("pending")), insertSingle = true))
                detailUidField.set(owner, "old")
                lyricState.value = PlayerLyric("old", "old", "artist", listOf(LyricLine(0, "old")), "test")
                state.value = previous.copy(
                    current = track("old"), queue = listOf(track("old")),
                    playing = true, pendingQueueId = "same-queue",
                )
                end()
                end() // 重复停止/清空必须幂等。
                assertTrue(pending.isCancelled)
                assertTrue(lyricJob.isCancelled)
                assertNull(jobField.get(owner))
                assertNull(lyricJobField.get(owner))
                assertNull(actionField.get(owner))
                assertNull(detailUidField.get(owner))
                assertNull(lyricState.value)
                assertNull(state.value.pendingQueueId)
                assertNull(state.value.current)
                assertTrue(state.value.queue.isEmpty())
                assertFalse(state.value.playing)
            }
        } finally {
            jobField.set(owner, null)
            lyricJobField.set(owner, previousLyricJob)
            actionField.set(owner, null)
            detailUidField.set(owner, previousDetailUid)
            state.value = previous
            lyricState.value = previousLyric
        }
    }

    private fun track(uid: String) = UiTrack(uid, uid, "artist", "album")
}
