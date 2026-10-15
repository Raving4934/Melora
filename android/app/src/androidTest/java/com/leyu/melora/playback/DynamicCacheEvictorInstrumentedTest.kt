package com.leyu.melora.playback

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 独立目录、稀疏测试文件，不下载音频、不分配大块内存、不修改用户缓存。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DynamicCacheEvictorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "cache-lru-test-${System.nanoTime()}")
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private var limit = Long.MAX_VALUE
    private lateinit var evictor: DynamicCacheEvictor

    @Before fun setup() {
        database = StandaloneDatabaseProvider(context)
        open()
    }

    @After fun cleanup() {
        cache.release()
        SimpleCache.delete(directory, database)
        database.close()
    }

    private fun open() {
        evictor = DynamicCacheEvictor { limit }
        cache = SimpleCache(directory, evictor, database)
        cache.checkInitialization()
    }

    private fun write(key: String, position: Long = 0) {
        val hole = requireNotNull(cache.startReadWriteNonBlocking(key, position, CHUNK))
        try {
            val file = cache.startFile(key, position, CHUNK)
            RandomAccessFile(file, "rw").use { it.setLength(CHUNK) }
            cache.commitFile(file, CHUNK)
        } finally { cache.releaseHoleSpan(hole) }
    }

    @Test fun runtimeLimitAfterEmptyInitializationNeedsNoSeparateAttach() {
        limit = 64 * MB
        write("old")
        Thread.sleep(5)
        write("recent")
        assertEquals(setOf("recent"), cache.keys)
        assertEquals(CHUNK, cache.cacheSpace)
    }

    @Test fun latestTouchedFragmentProtectsTheWholeResourceAfterReopen() {
        write("song", 0)
        Thread.sleep(5)
        write("other")
        Thread.sleep(5)
        write("song", CHUNK)
        cache.release()
        limit = 80 * MB
        open()
        assertEquals(setOf("song"), cache.keys)
        assertEquals(2 * CHUNK, cache.cacheSpace)
        assertTrue(cache.isCached("song", 0, 2 * CHUNK))
    }

    @Test fun capacityReductionUsesPersistedTouchOrder() {
        write("first")
        Thread.sleep(5)
        write("second")
        Thread.sleep(5)
        assertTrue(requireNotNull(cache.startReadWriteNonBlocking("first", 0, 1)).isCached)
        limit = 64 * MB
        evictor.trimNow()
        assertEquals(setOf("first"), cache.keys)
        assertEquals(CHUNK, evictor.trackedBytes())
    }

    private companion object {
        const val MB = 1024L * 1024
        const val CHUNK = 40 * MB
    }
}
