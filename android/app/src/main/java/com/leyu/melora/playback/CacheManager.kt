package com.leyu.melora.playback

import android.content.Context
import coil3.SingletonImageLoader
import com.leyu.melora.playback.local.LOCAL_COVER_CACHE_DIR
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.SNAPSHOT_DIRECTORY
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
        CoverLoader.clear()
        val loader = SingletonImageLoader.get(context)
        clearCacheGroups(
            "图片内存" to { loader.memoryCache?.clear(); Unit },
            "图片磁盘" to { loader.diskCache?.clear(); Unit },
            "本地封面" to { LocalTagReader.clearCoverCache(context) },
        )
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
        clearCacheGroups(
            "封面" to { clearImages(context) },
            "音频" to { clearAudio(context) },
            "歌词" to { clearLyrics(context) },
            "页面" to { OnlineCache.clearDisk(context) },
            "其他" to { clearOther(context) },
        )
    }

    private suspend fun clearOther(context: Context) = withContext(Dispatchers.IO) {
        SourceResolver.clearCache()
        // 下载中的临时文件由下载器保留；无任务时删除失败必须如实上报。
        Downloader.clearTemporaryFiles(File(context.cacheDir, Downloader.TEMP_DIRECTORY))
        val managedDirectories = setOf(
            IMAGE_DIR, LOCAL_COVER_CACHE_DIR, AUDIO_DIR, LYRIC_DIR, Downloader.TEMP_DIRECTORY, SNAPSHOT_DIRECTORY,
        )
        val other = (context.cacheDir.listFiles() ?: throw IOException("无法读取缓存目录"))
            .filterNot { it.name in managedDirectories }
        clearCacheGroups(*other.map { file -> file.name to suspend {
            if (file.exists() && !file.deleteRecursively()) throw IOException("缓存文件无法删除")
        } }.toTypedArray())
    }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun fileSize(file: File): Long =
        if (file.isDirectory) directorySize(file) else file.length()
}

/** 各组独立清理，部分失败不跳过其余组，也不把失败吞成“全部完成”。 */
internal suspend fun clearCacheGroups(vararg groups: Pair<String, suspend () -> Unit>) {
    val failures = mutableListOf<String>()
    for ((name, clear) in groups) {
        try { clear() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failures += "$name：${error.message ?: error.javaClass.simpleName}" }
    }
    if (failures.isNotEmpty()) throw IOException("部分缓存未清除（${failures.joinToString("；")}）")
}
