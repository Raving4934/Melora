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
    fun clearAndStopCancelPendingPlaybackEvenBeforeControllerConnects() {
        // 直接构造冷连接待处理状态，不为测试向生产入口增加适配器或setter。
        val owner = PlaybackController
        val jobField = owner.javaClass.getDeclaredField("queueLoadJob").apply { isAccessible = true }
        val actionField = owner.javaClass.getDeclaredField("pendingPlayback").apply { isAccessible = true }
        val stateField = owner.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(owner) as MutableStateFlow<PlayerUiState>
        val previous = state.value
        try {
            for (end in listOf(owner::clearQueue, owner::stop)) {
                val pending = Job()
                jobField.set(owner, pending)
                actionField.set(owner, { error("cancelled connection must never start playback") })
                state.value = previous.copy(pendingQueueId = "same-queue")
                end()
                end() // 重复停止/清空必须幂等。
                assertTrue(pending.isCancelled)
                assertNull(jobField.get(owner))
                assertNull(actionField.get(owner))
                assertNull(state.value.pendingQueueId)
            }
        } finally {
            jobField.set(owner, null)
            actionField.set(owner, null)
            state.value = previous
        }
    }

    private fun track(uid: String) = UiTrack(uid, uid, "artist", "album")
}
