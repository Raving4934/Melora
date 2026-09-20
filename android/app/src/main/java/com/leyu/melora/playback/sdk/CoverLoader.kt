package com.leyu.melora.playback.sdk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** 列表封面懒加载：为目录接口未携带封面的歌曲（如酷我/酷狗搜索）按需补齐图片地址。 */
object CoverLoader {
    private const val MAX_CONCURRENT_REQUESTS = 2
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 350L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Resolution(val url: String?)

    private val resolved = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val requestGate = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val inFlight = SingleFlight<String, Resolution>(scope)

    fun observe(song: OnlineSong): Flow<String?> = song.img?.let(::flowOf) ?: stateFor(song.uid).asStateFlow()

    fun cachedUrl(song: OnlineSong): String? = song.img ?: resolved[song.uid]?.value

    fun request(context: Context, song: OnlineSong) {
        if (cachedUrl(song) != null) return
        val appContext = context.applicationContext
        scope.launch { resolve(appContext, song) }
    }

    /** UI 与下载器共用同一条限流、合并、可重试的封面解析链路。 */
    suspend fun resolve(context: Context, song: OnlineSong): String? {
        song.img?.let { return it }
        resolved[song.uid]?.value?.let { return it }
        return inFlight.run(song.uid) {
            resolved[song.uid]?.value?.let { return@run Resolution(it) }
            val url = requestGate.withPermit {
                retryNullable(MAX_ATTEMPTS, RETRY_DELAY_MS) {
                    SourceResolver.picFast(context.applicationContext, song)
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                }
            }
            if (url != null) stateFor(song.uid).value = url
            Resolution(url)
        }.url
    }

    private fun stateFor(uid: String): MutableStateFlow<String?> =
        resolved.getOrPut(uid) { MutableStateFlow(null) }
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
