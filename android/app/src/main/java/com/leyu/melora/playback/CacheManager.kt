package com.leyu.melora.playback

import android.content.Context
import coil3.SingletonImageLoader
import com.leyu.melora.playback.local.LOCAL_COVER_CACHE_DIR
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.SNAPSHOT_DIRECTORY
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CacheStats(
    val imageBytes: Long,
    val audioBytes: Long,
    val lyricBytes: Long,
    val otherBytes: Long,
) {
    val totalBytes: Long get() = imageBytes + audioBytes + lyricBytes + otherBytes
    fun display(): String = formatCacheBytes(totalBytes)
}

internal fun formatCacheBytes(bytes: Long): String {
    val size = bytes.coerceAtLeast(0L)
    val kib = 1024L
    val mib = kib * 1024L
    val gib = mib * 1024L
    return when {
        size < kib -> "$size B"
        size < mib -> formatCacheUnit(size.toDouble() / kib, "KB")
        size < gib -> formatCacheUnit(size.toDouble() / mib, "MB")
        else -> formatCacheUnit(size.toDouble() / gib, "GB")
    }
}

private fun formatCacheUnit(value: Double, unit: String): String {
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.ROOT, pattern, value, unit)
}

/** 缓存管理：统计与清理图片、音频、歌词、URL 解析与临时文件缓存。 */
object CacheManager {
    // Coil 3 默认磁盘缓存目录；统计口径必须与真实 ImageLoader 保持一致。
    private const val IMAGE_DIR = "coil3_disk_cache"
    private const val AUDIO_DIR = "audio_cache"
    private const val LYRIC_DIR = "lyrics"

    @Volatile
    var cachedStats: CacheStats? = null
        internal set

    internal fun invalidateCachedStats() {
        cachedStats = null
    }

    suspend fun stats(context: Context): CacheStats = withContext(Dispatchers.IO) {
        val imageBytes = directorySize(File(context.cacheDir, IMAGE_DIR)) +
            directorySize(File(context.cacheDir, LOCAL_COVER_CACHE_DIR))
        val audioBytes = directorySize(File(context.cacheDir, AUDIO_DIR))
        val lyricBytes = directorySize(File(context.cacheDir, LYRIC_DIR))
        val otherBytes = context.cacheDir.listFiles()
            ?.filterNot {
                it.name == IMAGE_DIR || it.name == LOCAL_COVER_CACHE_DIR ||
                    it.name == AUDIO_DIR || it.name == LYRIC_DIR
            }
            ?.sumOf { fileSize(it) } ?: 0L
        val res = CacheStats(
            imageBytes = imageBytes,
            audioBytes = audioBytes,
            lyricBytes = lyricBytes,
            otherBytes = otherBytes,
        )
        cachedStats = res
        res
    }

    suspend fun audioCacheBytes(context: Context): Long = withContext(Dispatchers.IO) {
        directorySize(File(context.cacheDir, AUDIO_DIR))
    }

    /** 封面/图片缓存：Coil 内存 + 磁盘。 */
    suspend fun clearImages(context: Context) = withContext(Dispatchers.IO) {
        invalidateCachedStats()
        runCatching { SingletonImageLoader.get(context).memoryCache?.clear() }
        runCatching { SingletonImageLoader.get(context).diskCache?.clear() }
        LocalTagReader.clearCoverCache(context)
        if (!PlaybackService.isRunning) {
            runCatching { File(context.cacheDir, IMAGE_DIR).deleteRecursively() }
        }
    }

    /** 音频缓存：始终通过共享缓存实例清空，禁止运行时直接删除其目录。 */
    suspend fun clearAudio(context: Context) = withContext(Dispatchers.IO) {
        invalidateCachedStats()
        AudioCacheStore.clearAll(context)
    }

    /** 歌词缓存：内存 + 磁盘（cacheDir/lyrics）。 */
    suspend fun clearLyrics(context: Context) = withContext(Dispatchers.IO) {
        invalidateCachedStats()
        LyricRepository.clear(context)
    }

    suspend fun clearAll(context: Context) {
        invalidateCachedStats()
        clearImages(context)
        clearAudio(context)
        clearLyrics(context)
        withContext(Dispatchers.IO) {
            OnlineCache.clearDisk(context)
            SourceResolver.clearCache()
            Downloader.clearTemporaryFiles(File(context.cacheDir, Downloader.TEMP_DIRECTORY))
            // 托管目录只能交给自己的清理入口，不能再次递归删除缓存索引或下载中间文件。
            val managedDirectories = setOf(
                IMAGE_DIR, LOCAL_COVER_CACHE_DIR, AUDIO_DIR, LYRIC_DIR, Downloader.TEMP_DIRECTORY, SNAPSHOT_DIRECTORY,
            )
            context.cacheDir.listFiles()
                ?.filterNot { it.name in managedDirectories }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun fileSize(file: File): Long =
        if (file.isDirectory) directorySize(file) else file.length()
}
