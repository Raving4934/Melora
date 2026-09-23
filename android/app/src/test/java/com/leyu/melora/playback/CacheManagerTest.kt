package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CacheManagerTest {
    @Test
    fun cacheSizesUseReadableUnitsWithoutRoundingSmallCachesToZero() {
        assertEquals("0 B", formatCacheBytes(0))
        assertEquals("512 B", formatCacheBytes(512))
        assertEquals("1.0 KB", formatCacheBytes(1024))
        assertEquals("968 KB", formatCacheBytes(968L * 1024))
        assertEquals("1.5 MB", formatCacheBytes(3L * 1024 * 1024 / 2))
        assertEquals("1.0 GB", formatCacheBytes(1024L * 1024 * 1024))
    }

    @Test
    fun totalDisplayUsesTheSameReadableFormatter() {
        val stats = CacheStats(
            imageBytes = 768L * 1024,
            audioBytes = 0,
            lyricBytes = 200L * 1024,
            otherBytes = 0,
        )

        assertEquals("968 KB", stats.display())
    }

    @Test
    fun cacheStatsTotalAndDisplayUseTheSameByteCounters() {
        val stats = CacheStats(
            imageBytes = 1024L,
            audioBytes = 2L * 1024L * 1024L,
            lyricBytes = 3L,
            otherBytes = 4L,
        )

        assertEquals(2L * 1024L * 1024L + 1031L, stats.totalBytes)
        assertEquals("2.0 MB", stats.display())
    }

    @Test
    fun formatCacheBytesClampsNegativeValuesAndUsesBinaryUnits() {
        assertEquals("0 B", formatCacheBytes(-1L))
        assertEquals("1.0 KB", formatCacheBytes(1024L))
        assertEquals("1.0 MB", formatCacheBytes(1024L * 1024L))
    }

    @Test
    fun invalidatingCachedStatsDropsTheWarmStartSnapshot() {
        CacheManager.cachedStats = CacheStats(1L, 2L, 3L, 4L)

        CacheManager.invalidateCachedStats()

        assertNull(CacheManager.cachedStats)
    }
    @Test
    fun idleDownloadTemporaryCleanupDoesNotDeleteNeighboringFilesAndCanRepeat() {
        val root = Files.createTempDirectory("melora-cache-cleanup").toFile()
        try {
            val directory = File(root, Downloader.TEMP_DIRECTORY).apply { mkdirs() }
            File(directory, "interrupted.part").writeText("old interrupted download")
            val neighbor = File(root, "keep.cache").apply { writeText("keep") }
            assertTrue(Downloader.clearTemporaryFiles(directory))
            assertFalse(directory.exists())
            assertTrue(neighbor.exists())
            assertTrue(Downloader.clearTemporaryFiles(directory))
        } finally {
            root.deleteRecursively()
        }
    }

}

class CacheCleanupResultTest {
    @org.junit.Test fun partialFailureStillAttemptsOtherGroupsAndReportsFailure() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        try {
            clearCacheGroups(
                "封面" to { calls += "封面"; throw java.io.IOException("只读目录") },
                "歌词" to { calls += "歌词" },
                "音频" to { calls += "音频"; throw java.io.IOException("磁盘错误") },
            )
            org.junit.Assert.fail("不应报告全部成功")
        } catch (error: java.io.IOException) {
            org.junit.Assert.assertTrue(error.message!!.contains("封面：只读目录"))
            org.junit.Assert.assertTrue(error.message!!.contains("音频：磁盘错误"))
        }
        org.junit.Assert.assertEquals(listOf("封面", "歌词", "音频"), calls)
    }

    @org.junit.Test fun cancellationIsNotConvertedToCleanupFailure() = kotlinx.coroutines.runBlocking {
        var called = false
        try {
            clearCacheGroups(
                "取消" to { throw kotlinx.coroutines.CancellationException("cancel") },
                "后续" to { called = true },
            )
            org.junit.Assert.fail("取消丢失")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        org.junit.Assert.assertFalse(called)
    }
}
