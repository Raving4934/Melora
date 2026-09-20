package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** 有界进程缓存；过期数据只作即时展示/播放快照，刷新结果供下次读取。 */
object OnlineCache {
    const val CATALOG_TTL_MS = 15 * 60 * 1000L
    internal const val MAX_ENTRIES = 256
    private data class Entry(val value: Any, val time: Long = System.currentTimeMillis())
    private val lock = Any()
    private val store = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private val refreshing = mutableMapOf<String, Deferred<Any>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, ttlMs: Long): T? = synchronized(lock) {
        store[key]?.takeIf { System.currentTimeMillis() - it.time <= ttlMs }?.value as? T
    }

    /** 仅取旧快照，不代表仍然新鲜；使用方须通过 get / refresh 检查有效期。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(key: String): T? = synchronized(lock) { store[key]?.value as? T }

    fun put(key: String, value: Any) = synchronized(lock) {
        store[key] = Entry(value)
        while (store.size > MAX_ENTRIES) store.remove(store.keys.first())
    }

    /** 在派生刷新任务调度前捕获在途请求；即使它随后完成，也可复用同一次结果。 */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> pendingRefresh(key: String): Deferred<T>? = synchronized(lock) {
        refreshing[key] as Deferred<T>?
    }

    /** 同 key 网络请求合并。单个等待者取消不影响其他等待者；清缓存后旧请求不能回填。 */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> refresh(key: String, ttlMs: Long, load: suspend () -> T): T {
        val request = synchronized(lock) {
            get<T>(key, ttlMs)?.let { return it }
            refreshing[key] ?: run {
                val previous = store[key]
                lateinit var task: Deferred<Any>
                task = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val value = load()
                        synchronized(lock) {
                            if (refreshing[key] === task && store[key] === previous &&
                                (value !is Collection<*> || value.isNotEmpty())
                            ) put(key, value)
                        }
                        value
                    } finally {
                        synchronized(lock) { if (refreshing[key] === task) refreshing.remove(key) }
                    }
                }
                refreshing[key] = task
                task
            }
        }
        return request.await() as T
    }

    /** 可按命名空间失效；重载解析器不应连带清空页面目录缓存。 */
    fun clear(prefix: String = "") = synchronized(lock) {
        store.keys.removeAll { it.startsWith(prefix) }
        val keys = refreshing.keys.filter { it.startsWith(prefix) }
        keys.mapNotNull { refreshing.remove(it) }.forEach { it.cancel() }
    }
}
