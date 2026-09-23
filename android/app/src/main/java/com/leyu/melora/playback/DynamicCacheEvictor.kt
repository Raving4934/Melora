package com.leyu.melora.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.LinkedHashMap
import java.io.IOException

/** 可在运行时读取容量设置的按资源 LRU 淘汰器。 */
@androidx.annotation.OptIn(UnstableApi::class)
class DynamicCacheEvictor(private val maxBytesProvider: () -> Long) : CacheEvictor {
    private val sizes = LinkedHashMap<String, Long>()
    private var currentBytes = 0L
    private var cache: Cache? = null

    override fun requiresCacheSpanTouches(): Boolean = true
    override fun onCacheInitialized() = Unit

    @Synchronized
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        this.cache = cache
        trimLocked(cache)
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        recordAddedLocked(span.key, span.length)
        trimLocked(cache)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        recordRemovedLocked(span.key, span.length)
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        this.cache = cache
        if (oldSpan.key == newSpan.key) {
            sizes.remove(newSpan.key)?.let { sizes[newSpan.key] = it }
        } else {
            recordRemovedLocked(oldSpan.key, oldSpan.length)
            recordAddedLocked(newSpan.key, newSpan.length)
        }
        trimLocked(cache)
    }

    /**
     * SimpleCache 的公开方法与 evictor 回调都持有 Cache 实例锁。
     * 外部操作统一按 Cache → Evictor 顺序取锁，禁止反向锁序造成播放服务启动死锁。
     */
    fun attach(cache: Cache) = withCacheLock(cache) {
        this.cache = cache
        refreshFromCacheLocked(cache)
        trimLocked(cache)
    }

    /** 立即按当前设置值收缩缓存（设置页调整大小时调用）。 */
    fun trimNow() {
        val target = synchronized(this) { cache } ?: return
        withCacheLock(target) {
            refreshFromCacheLocked(target)
            trimLocked(target)
        }
    }

    /** 清空全部缓存内容（播放服务运行中安全清理）。 */
    fun clearAll(target: Cache) {
        withCacheLock(target) {
            val failed = target.keys.toList().filter { key -> runCatching { target.removeResource(key) }.isFailure }
            // 部分删除失败后按真实缓存重建账本，不能把仍存在的数据报成0字节。
            refreshFromCacheLocked(target)
            if (failed.isNotEmpty()) throw IOException("${failed.size} 项音频缓存无法删除，请重试")
        }
    }

    @Synchronized
    internal fun recordAdded(key: String, length: Long) = recordAddedLocked(key, length)

    @Synchronized
    internal fun recordRemoved(key: String, length: Long) = recordRemovedLocked(key, length)

    @Synchronized
    internal fun trackedBytes(key: String? = null): Long = if (key == null) currentBytes else sizes[key] ?: 0L

    private inline fun withCacheLock(cache: Cache, action: () -> Unit) {
        synchronized(cache) {
            synchronized(this) { action() }
        }
    }

    private fun recordAddedLocked(key: String, length: Long) {
        if (length <= 0) return
        sizes[key] = (sizes.remove(key) ?: 0L) + length
        currentBytes += length
    }

    private fun recordRemovedLocked(key: String, length: Long) {
        val previous = sizes[key] ?: return
        val removed = length.coerceIn(0L, previous)
        val remaining = previous - removed
        if (remaining == 0L) sizes.remove(key) else sizes[key] = remaining
        currentBytes = (currentBytes - removed).coerceAtLeast(0L)
    }

    private fun refreshFromCacheLocked(cache: Cache) {
        sizes.clear()
        currentBytes = 0L
        cache.keys.forEach { key ->
            cache.getCachedSpans(key).forEach { span -> if (span.isCached) recordAddedLocked(key, span.length) }
        }
    }

    private fun trimLocked(cache: Cache) {
        val max = maxBytesProvider().coerceAtLeast(MIN_BYTES)
        while (currentBytes > max && sizes.isNotEmpty()) {
            val key = sizes.keys.first()
            val before = currentBytes
            val removed = runCatching { cache.removeResource(key) }.isSuccess
            // 淘汰失败不能阻断正常播放，也不能丢弃失败资源的大小账本或陷入死循环。
            if (!removed || currentBytes >= before) {
                refreshFromCacheLocked(cache)
                break
            }
        }
    }

    private companion object {
        const val MIN_BYTES = 64L * 1024 * 1024
    }
}
