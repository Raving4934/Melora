package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicCacheEvictorTest {
    @Test
    fun multipleSpansOfOneResourceAreAccumulatedAndRemovedIndividually() {
        val evictor = DynamicCacheEvictor { Long.MAX_VALUE }

        evictor.recordAdded("song", 100)
        evictor.recordAdded("song", 250)
        evictor.recordAdded("other", 50)
        assertEquals(350, evictor.trackedBytes("song"))
        assertEquals(400, evictor.trackedBytes())

        evictor.recordRemoved("song", 100)
        assertEquals(250, evictor.trackedBytes("song"))
        assertEquals(300, evictor.trackedBytes())

        evictor.recordRemoved("song", 999)
        assertEquals(0, evictor.trackedBytes("song"))
        assertEquals(50, evictor.trackedBytes())
    }
}
