package com.leyu.melora.playback.sdk

import android.content.Context
import android.util.Log
import com.leyu.melora.playback.PlatformSource
import com.leyu.melora.playback.SourceAlias
import com.leyu.melora.playback.lx.LxScriptEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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
    private const val LOAD_RETRY_DELAY_NANOS = 2_000_000_000L
    private const val TAG = "MusicSdkEngine"

    private class LoadFailure(cause: Throwable) :
        IllegalStateException("目录引擎加载失败：${cause.message}", cause) {
        private val retryAtNanos = System.nanoTime() + LOAD_RETRY_DELAY_NANOS

        fun isCooling(nowNanos: Long): Boolean = retryAtNanos - nowNanos > 0
    }

    private class Task(
        val action: String,
        val source: String,
        val params: JSONObject,
        val timeoutMs: Long,
    ) {
        val result = CompletableDeferred<JSONObject>()
    }

    private class Slot(val index: Int) {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "melora-sdk-$index")
        }.asCoroutineDispatcher()
        val high = Channel<Task>(Channel.UNLIMITED)
        val low = Channel<Task>(Channel.UNLIMITED)

        @Volatile var engine: LxScriptEngine? = null
        @Volatile var loadFailure: LoadFailure? = null
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
        val snapshot = JSONObject(params.toString())
        return withTimeoutOrNull(timeoutMs + 10_000) {
            val attempted = BooleanArray(slots.size)
            var lastFailure: LoadFailure? = null
            repeat(slots.size) {
                val now = System.nanoTime()
                val index = loadBalancer.reserve { index ->
                    !attempted[index] && slots[index].loadFailure?.isCooling(now) != true
                }
                if (index < 0) {
                    throw (lastFailure ?: slots.firstNotNullOfOrNull { it.loadFailure }
                        ?: IllegalStateException("目录引擎暂不可用，请稍后重试"))
                }
                attempted[index] = true
                val slot = slots[index]
                val task = Task(action, source, snapshot, timeoutMs)
                // 成功入队后只由 worker 归还计数，调用方取消时只取消结果。
                if (!(if (background) slot.low else slot.high).trySend(task).isSuccess) {
                    loadBalancer.release(index)
                    error("目录队列已关闭")
                }
                try {
                    return@withTimeoutOrNull task.result.await()
                } catch (failure: LoadFailure) {
                    // 只有加载失败能换槽，业务请求已经发出时交给上层决定是否重试。
                    lastFailure = failure
                } finally {
                    if (!task.result.isCompleted) task.result.cancel()
                }
            }
            throw checkNotNull(lastFailure)
        } ?: throw IllegalStateException("目录请求超时")
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
                        if (task.result.isActive) runTask(app, slot, task)
                    } finally {
                        loadBalancer.release(slot.index)
                    }
                }
            }
        }
    }

    private suspend fun runTask(context: Context, slot: Slot, task: Task) = supervisorScope {
        val startedAt = System.currentTimeMillis()
        val invocation = async(start = CoroutineStart.LAZY) {
            ensureEngine(context, slot).sdkCall(task.action, task.source, task.params, task.timeoutMs)
        }
        // 结果同时承担排队/运行态的取消信号；只中止本次调用，保留slot与QuickJS实例。
        val cancellation = task.result.invokeOnCompletion { if (task.result.isCancelled) invocation.cancel() }
        try {
            task.result.complete(invocation.await())
        } catch (error: Throwable) {
            task.result.completeExceptionally(error)
        } finally {
            cancellation.dispose()
        }
        val cost = System.currentTimeMillis() - startedAt
        if (cost > 1_500) Log.d(TAG, "${task.action}/${task.source} 耗时 ${cost}ms")
    }

    private fun ensureEngine(context: Context, slot: Slot): LxScriptEngine {
        slot.engine?.let { return it }
        slot.loadFailure?.takeIf { it.isCooling(System.nanoTime()) }?.let { throw it }
        var created: LxScriptEngine? = null
        return try {
            val engine = LxScriptEngine(context)
            created = engine
            engine.load(checkNotNull(sdkCode), "music-sdk.js")
            slot.engine = engine
            slot.loadFailure = null
            engine
        } catch (error: Throwable) {
            created?.close()
            if (error is CancellationException) throw error
            val failure = LoadFailure(error)
            slot.loadFailure = failure
            throw failure
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
    fun reserve(isAvailable: (Int) -> Boolean = { true }): Int {
        var index = -1
        for (candidate in pending.indices) {
            if (isAvailable(candidate) && (index < 0 || pending[candidate] < pending[index])) {
                index = candidate
            }
        }
        if (index < 0) return index
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
    val sourceName = SourceAlias.display(source, PlatformSource.musicPlatform(source)?.sdkLabel.orEmpty())
    val actionName = sdkActionNames[action] ?: "在线内容"
    return "${sourceName}${actionName}暂时无法加载，请稍后重试"
}
