package com.leyu.melora.playback.sdk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap

/** 列表封面懒加载：为目录接口未携带封面的歌曲（如酷我/酷狗搜索）按需补齐图片地址。 */
object CoverLoader {
    private const val MAX_CONCURRENT_REQUESTS = 2
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 350L
    internal const val MAX_RESOLVED_ENTRIES = 256
    internal const val COVER_TTL_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Any()
    private val resolved = CoverAddressCache(
        maxEntries = MAX_RESOLVED_ENTRIES,
        valueTtlMs = COVER_TTL_MS,
        lock = cacheLock,
    )
    private data class Resolution(val url: String?)

    private val requestGate = Semaphore(MAX_CONCURRENT_REQUESTS)
    // 与地址缓存共用锁，保证 clear 的 fence 与缓存清理之间没有新旧代次穿插。
    private val inFlight = SingleFlight<String, Resolution>(scope, cacheLock)

    /** 已有 img 是目录返回的权威地址，不注册到补齐缓存；缺图歌曲才订阅可更新的地址流。 */
    fun observe(song: OnlineSong): Flow<String?> = song.img?.let(::flowOf) ?: resolved.observe(song.uid)

    /** 过期地址仍可作为即时展示/播放快照；调用方通过 request / resolve 触发后台重查。 */
    fun cachedUrl(song: OnlineSong): String? = song.img ?: resolved.peek(song.uid)

    fun request(context: Context, song: OnlineSong) {
        if (song.img != null || !resolved.needsRefresh(song.uid)) return
        val appContext = context.applicationContext
        // 先登记SingleFlight再让出线程，避免清理前排队的请求在清理后才注册。
        // 真正的解析仍由SingleFlight的IO作用域执行。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { resolve(appContext, song) }
    }

    /** UI 与下载器共用同一条限流、合并、可重试的封面解析链路。 */
    suspend fun resolve(context: Context, song: OnlineSong): String? {
        song.img?.let { return it }
        val uid = song.uid
        val stale = resolved.peek(uid)
        if (!resolved.needsRefresh(uid)) return stale

        val generation = inFlight.currentGeneration()
        return inFlight.run(uid, generation) {
            resolved.fresh(uid)?.let { return@run Resolution(it) }
            val url = requestGate.withPermit {
                retryNullable(MAX_ATTEMPTS, RETRY_DELAY_MS) {
                    SourceResolver.picFast(context.applicationContext, song)
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
            }
            if (url != null) {
                synchronized(cacheLock) {
                    if (inFlight.currentGeneration() == generation) resolved.put(uid, url)
                }
            }
            Resolution(url)
        }.url ?: resolved.peek(uid)
    }

    /** 清掉所有已解析地址，并让清理前的在途结果失效；活动订阅保留当前画面快照但标记失效。 */
    fun clear() {
        synchronized(cacheLock) {
            inFlight.fence()
            resolved.clear()
        }
    }
}

/**
 * 封面地址的进程内有界缓存。
 *
 * 普通过期项不立即删除：peek / observe 仍能提供旧地址，needsRefresh 只负责让下一次请求重查。
 * clear 失效的活动项只保留给当前 collector 作为画面快照，peek / 新订阅不会再读到它。
 * 正在 collect 的流不参与淘汰，否则同一条列表流会被替换成新 StateFlow 而收不到回写。
 */
internal class CoverAddressCache(
    private val maxEntries: Int,
    private val valueTtlMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lock: Any = Any(),
) {
    private class Entry {
        val state = MutableStateFlow<String?>(null)
        var expiresAtMs = 0L
        var activeSubscribers = 0
        var invalidatedByClear = false
    }

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(valueTtlMs > 0) { "valueTtlMs must be positive" }
    }

    fun peek(uid: String): String? = synchronized(lock) {
        val entry = entries[uid] ?: return@synchronized null
        entry.state.value?.takeUnless { entry.invalidatedByClear }
    }

    fun fresh(uid: String): String? = synchronized(lock) {
        val entry = entries[uid] ?: return@synchronized null
        if (entry.invalidatedByClear) return@synchronized null
        val value = entry.state.value ?: return@synchronized null
        value.takeIf { entry.expiresAtMs > nowMs() }
    }

    fun needsRefresh(uid: String): Boolean = fresh(uid) == null

    fun put(uid: String, url: String) = synchronized(lock) {
        val entry = entries[uid] ?: Entry().also { entries[uid] = it }
        // 新一代结果回写同一个活动 StateFlow，恢复当前画面的实时更新。
        entry.invalidatedByClear = false
        entry.state.value = url
        entry.expiresAtMs = nowMs() + valueTtlMs
        trimLocked()
    }

    /**
     * 清空内部状态并标记失效；活动流过滤 null，因此仍保留上一帧画面，重查成功后原流直接切到新图。
     * 没有活动订阅的失效项立即移除，避免 clear 后留下不可用快照。
     */
    fun clear() = synchronized(lock) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            entry.state.value = null
            entry.expiresAtMs = 0L
            entry.invalidatedByClear = true
            if (entry.activeSubscribers == 0) iterator.remove()
        }
    }

    internal val size: Int
        get() = synchronized(lock) { entries.size }

    fun observe(uid: String): Flow<String?> = flow {
        val (entry, initial) = synchronized(lock) {
            val current = entries[uid] ?: Entry().also { entries[uid] = it }
            current.activeSubscribers++
            trimLocked()
            current to current.state.value
        }
        try {
            // 首次值是订阅建立时的 UI 快照；后续过滤 null，clear 只会让缓存失效，不会让已有画面闪空。
            emit(initial)
            emitAll(entry.state.filterNotNull())
        } finally {
            synchronized(lock) {
                if (entry.activeSubscribers > 0) entry.activeSubscribers--
                trimLocked()
            }
        }
    }

    private fun trimLocked() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.activeSubscribers == 0 && entry.invalidatedByClear) iterator.remove()
        }
        while (entries.size > maxEntries) {
            val victim = entries.entries.firstOrNull { it.value.activeSubscribers == 0 } ?: return
            entries.remove(victim.key)
        }
    }
}

internal suspend fun <T : Any> retryNullable(
    maxAttempts: Int,
    retryDelayMs: Long,
    block: suspend (attempt: Int) -> T?,
): T? {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    repeat(maxAttempts) { index ->
        val result = try {
            block(index + 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (result != null) return result
        if (index + 1 < maxAttempts && retryDelayMs > 0) delay(retryDelayMs)
    }
    return null
}
