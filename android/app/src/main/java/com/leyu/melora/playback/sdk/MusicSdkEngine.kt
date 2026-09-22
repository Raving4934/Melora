package com.leyu.melora.playback.sdk

import android.content.Context
import android.util.Log
import com.leyu.melora.playback.SourceAlias
import com.leyu.melora.playback.lx.LxScriptEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 内置在线目录引擎：在 3 个 QuickJS 实例上运行 musicSdk 移植包。
 * 交互请求走高优队列，后台预取走低优队列；Provider 自己负责业务重试。
 */
object MusicSdkEngine {
    private const val POOL_SIZE = 3
    private const val TAG = "MusicSdkEngine"

    private class Task(
        val action: String,
        val source: String,
        val params: JSONObject,
        val timeoutMs: Long,
    ) {
        val result = CompletableDeferred<JSONObject>()
        @Volatile var cancelled = false
    }

    private class Slot(val index: Int) {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "melora-sdk-$index")
        }.asCoroutineDispatcher()
        val high = Channel<Task>(Channel.UNLIMITED)
        val low = Channel<Task>(Channel.UNLIMITED)

        @Volatile var engine: LxScriptEngine? = null
        @Volatile var loadFailure: Throwable? = null
        @Volatile var worker: Job? = null
    }

    private val slots = List(POOL_SIZE) { Slot(it) }
    private val loadBalancer = SlotLoadBalancer(POOL_SIZE)
    private val startMutex = Mutex()

    @Volatile private var appContext: Context? = null
    @Volatile private var sdkCode: String? = null

    suspend fun call(
        context: Context,
        action: String,
        source: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 25_000,
        background: Boolean = false,
    ): JSONObject = try {
        dispatch(context.applicationContext, action, source, params, timeoutMs, background)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val message = userFacingMusicSdkError(action, source, error)
        Log.w(TAG, "$action/$source 请求失败：${error.message}", error)
        if (message == error.message) throw error
        throw IllegalStateException(message, error)
    }

    private suspend fun dispatch(
        context: Context,
        action: String,
        source: String,
        params: JSONObject,
        timeoutMs: Long,
        background: Boolean,
    ): JSONObject {
        if (sdkCode == null) {
            withContext(Dispatchers.IO) { ensureStarted(context) }
        } else {
            ensureStarted(context)
        }
        val slot = slots[loadBalancer.reserve()]
        val task = Task(action, source, JSONObject(params.toString()), timeoutMs)
        try {
            (if (background) slot.low else slot.high).send(task)
        } catch (error: Throwable) {
            loadBalancer.release(slot.index)
            throw error
        }
        return try {
            withTimeoutOrNull(timeoutMs + 10_000) { task.result.await() }
                ?: throw IllegalStateException("目录请求超时")
        } finally {
            if (!task.result.isCompleted) {
                task.cancelled = true
                task.result.cancel()
            }
        }
    }

    private suspend fun ensureStarted(context: Context) = startMutex.withLock {
        if (sdkCode == null) {
            val loadedCode = context.assets.open("sdk/music-sdk.js").use { it.readBytes().decodeToString() }
            appContext = context.applicationContext
            sdkCode = loadedCode
        }
        val app = appContext ?: return@withLock
        slots.forEach { slot ->
            if (slot.worker != null) return@forEach
            slot.worker = CoroutineScope(slot.dispatcher).launch {
                while (isActive) {
                    val task = receivePriority(slot.high, slot.low)
                    try {
                        if (!task.cancelled) runTask(app, slot, task)
                    } finally {
                        loadBalancer.release(slot.index)
                    }
                }
            }
        }
    }

    private fun runTask(context: Context, slot: Slot, task: Task) {
        val startedAt = System.currentTimeMillis()
        try {
            val engine = ensureEngine(context, slot)
            if (task.cancelled) return
            task.result.complete(engine.sdkCall(task.action, task.source, task.params, task.timeoutMs))
        } catch (error: Throwable) {
            task.result.completeExceptionally(error)
        }
        val cost = System.currentTimeMillis() - startedAt
        if (cost > 1_500) Log.d(TAG, "${task.action}/${task.source} 耗时 ${cost}ms")
    }

    private fun ensureEngine(context: Context, slot: Slot): LxScriptEngine {
        slot.engine?.let { return it }
        slot.loadFailure?.let { throw IllegalStateException("目录引擎加载失败：${it.message}", it) }
        val created = LxScriptEngine(context)
        return try {
            created.load(sdkCode.orEmpty(), "music-sdk.js")
            created.also { slot.engine = it }
        } catch (error: Throwable) {
            created.close()
            slot.loadFailure = error
            throw IllegalStateException("目录引擎加载失败：${error.message}", error)
        }
    }
}

internal suspend fun <T> receivePriority(high: Channel<T>, low: Channel<T>): T {
    high.tryReceive().getOrNull()?.let { return it }
    val (picked, fromHigh) = select<Pair<T, Boolean>> {
        high.onReceive { it to true }
        low.onReceive { it to false }
    }
    if (fromHigh) return picked
    return high.tryReceive().getOrNull()?.also { low.send(picked) } ?: picked
}

internal class SlotLoadBalancer(size: Int) {
    private val pending = IntArray(size.also { require(it > 0) { "size must be positive" } })

    @Synchronized
    fun reserve(): Int {
        val index = pending.indices.minBy { pending[it] }
        pending[index]++
        return index
    }

    @Synchronized
    fun release(index: Int) {
        require(index in pending.indices) { "slot index out of range" }
        check(pending[index] > 0) { "slot has no pending task" }
        pending[index]--
    }

    @Synchronized
    fun snapshot(): List<Int> = pending.toList()
}

private val sdkSourceNames = mapOf(
    "kg" to "酷狗",
    "mg" to "咪咕",
    "kw" to "酷我",
    "tx" to "QQ音乐",
    "wy" to "网易云",
)

private val sdkActionNames = mapOf(
    "search" to "搜索",
    "songlistSearch" to "歌单搜索",
    "hotSearch" to "热搜",
    "boards" to "排行榜",
    "boardSongs" to "榜单",
    "playlistTags" to "歌单分类",
    "playlists" to "歌单列表",
    "playlistSongs" to "歌单",
    "lyric" to "歌词",
    "pic" to "封面",
)

internal fun userFacingMusicSdkError(action: String, source: String, error: Throwable): String {
    val raw = error.message?.trim().orEmpty()
    val normalized = raw.lowercase(Locale.ROOT)
    val isRetryExhausted = normalized.contains("try max num") ||
        normalized.contains("link get failed") ||
        normalized == "failed" ||
        normalized == "filed" ||
        normalized.startsWith("invalid ")
    if (!isRetryExhausted) {
        return raw.ifEmpty { "在线内容请求失败，请稍后重试" }
    }
    val sourceName = SourceAlias.display(source, sdkSourceNames[source].orEmpty())
    val actionName = sdkActionNames[action] ?: "在线内容"
    return "${sourceName}${actionName}暂时无法加载，请稍后重试"
}
