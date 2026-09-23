package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicCacheEvictorTest {
    @Test
    fun failedClearRetainsActualByteAccountingAndStillClearsOtherResources() {
        val evictor = DynamicCacheEvictor { Long.MAX_VALUE }
        val cache = fakeCache(evictor, linkedMapOf("locked" to 100L, "normal" to 200L), "locked")
        evictor.attach(cache)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { evictor.clearAll(cache) }
        assertEquals(setOf("locked"), cache.keys)
        assertEquals(100L, evictor.trackedBytes())
        assertEquals(100L, evictor.trackedBytes("locked"))
    }

    @Test
    fun coldCleanupDoesNotDependOnAsynchronousAttach() {
        val evictor = DynamicCacheEvictor { Long.MAX_VALUE }
        val cache = fakeCache(evictor, linkedMapOf("normal" to 200L), "unused")
        // 尚未attach，不得把清理静默当成no-op。
        evictor.clearAll(cache)
        assertEquals(emptySet<String>(), cache.keys)
        assertEquals(0L, evictor.trackedBytes())
    }

    @Test
    fun failedAutomaticTrimDoesNotHideBytesOrLoopForever() {
        val evictor = DynamicCacheEvictor { 64L * 1024 * 1024 }
        val cache = fakeCache(evictor, linkedMapOf("locked" to 100L * 1024 * 1024), "locked")
        evictor.attach(cache)
        assertEquals(100L * 1024 * 1024, evictor.trackedBytes())
    }

    private fun fakeCache(evictor: DynamicCacheEvictor, sizes: LinkedHashMap<String, Long>, locked: String): androidx.media3.datasource.cache.Cache {
        val type = androidx.media3.datasource.cache.Cache::class.java
        fun span(key: String) = androidx.media3.datasource.cache.CacheSpan(key, 0, sizes.getValue(key), 1, java.io.File("$key.cache"))
        return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "getKeys" -> sizes.keys.toSet()
                "getCachedSpans" -> java.util.TreeSet<androidx.media3.datasource.cache.CacheSpan>().apply {
                    val key = args!![0] as String
                    if (key in sizes) add(span(key))
                }
                "removeResource" -> {
                    val key = args!![0] as String
                    if (key == locked) throw java.io.IOException("locked")
                    evictor.onSpanRemoved(proxy as androidx.media3.datasource.cache.Cache, span(key))
                    sizes.remove(key)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "FixtureCache"
                else -> error("unexpected cache call: ${method.name}")
            }
        } as androidx.media3.datasource.cache.Cache
    }

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
