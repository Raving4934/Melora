package com.leyu.melora.playback.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.leyu.melora.playback.lx.LxScript
import com.leyu.melora.playback.lx.LxScriptEngine
import com.leyu.melora.playback.lx.LxScriptRequestException
import com.leyu.melora.playback.lx.LxScriptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 音源脚本池：管理全部已安装脚本的常驻 QuickJS 实例与平台能力声明。
 *
 * 目录/歌词/封面来自内置 musicSdk，播放地址来自自定义音源脚本。
 * 解析时开启的源优先；自动换源开启时才会回退到未开启的备用源。
 * 启用状态只同步元数据；导入/删除增量刷新，只有手动重载才整池重建。
 */
object LxScriptPool {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "melora-lxpool")
    }.asCoroutineDispatcher()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TAG = "LxScriptPool"

    /** 脚本作用域：开启的源优先用于解析；自动换源时可回退到未开启的备用源。 */
    enum class ScriptScope { ENABLED, DISABLED, ALL }

    private data class Entry(
        var script: LxScript,
        val engine: LxScriptEngine,
        val sources: Map<String, PlatformSupport>,
        val revision: Long,
        val lock: Mutex = Mutex(),
    ) {
        val health = ConcurrentHashMap<String, PlatformHealth>()
        @Volatile var retired = false
    }

    private data class LoadFailure(val revision: Long?, val retryAt: Long)

    data class PlatformSupport(
        val name: String,
        val actions: Set<String>,
        val qualitys: List<String>,
    )

    data class ScriptRequest(
        val requestId: String,
        val scriptId: String,
        val source: String,
        val action: String,
        val info: JSONObject,
        val timeoutMs: Long,
    )

    data class ScriptResult(
        val requestId: String,
        val scriptId: String,
        val data: Any,
        val elapsedMs: Long = 0L,
    ) {
        val url = SourceResolver.extractUrl(data)
        internal var playbackConfirmation: PlaybackConfirmation? = null
    }

    class PlaybackConfirmation internal constructor(
        private val health: PlatformHealth,
        private val generation: Long,
    ) {
        internal fun confirm() = health.succeeded(generation)
    }

    internal sealed interface CandidateVerdict<out T> {
        data class Accepted<T>(val value: T, val verified: Boolean = false) : CandidateVerdict<T>
        data object Healthy : CandidateVerdict<Nothing>
        data object Skipped : CandidateVerdict<Nothing>
        data object Invalid : CandidateVerdict<Nothing>
    }

    data class ScriptMetrics(val hitCount: Long, val averageLatencyMs: Long)

    private class MutableMetrics {
        val hitCount = java.util.concurrent.atomic.AtomicLong(0L)
        val totalLatencyMs = java.util.concurrent.atomic.AtomicLong(0L)
    }

    private var entries: MutableMap<String, Entry> = linkedMapOf()
    @Volatile private var scriptDisplayNames: Map<String, String> = emptyMap()
    private val loadFailures = linkedMapOf<String, LoadFailure>()
    // 只在dispatcher读写：前台可订阅预热/重载已完成的单个源，不等待整批加载锁。
    private val readyListeners = linkedSetOf<(Entry) -> Unit>()

    // 同一首歌的一次解析（含各档、主备和跨平台候选）在一个接口上最多记一次失败。
    internal class ResolutionAttempt {
        private val failedPlatforms = ConcurrentHashMap.newKeySet<PlatformHealth>()

        fun recordFailure(health: PlatformHealth): Boolean = failedPlatforms.add(health)
    }

    internal class PlatformHealth {
        private var consecutiveFailures = 0
        private var blockedUntilMs = 0L
        private var generation = 0L

        @Synchronized fun isBlocked(): Boolean = blockedUntilMs != 0L && SystemClock.elapsedRealtime() < blockedUntilMs

        @Synchronized fun pendingPlaybackConfirmation(): PlaybackConfirmation? =
            if (consecutiveFailures > 0 || isBlocked()) PlaybackConfirmation(this, generation) else null

        @Synchronized fun succeeded(expectedGeneration: Long? = null) {
            // 更晚的计数和已消费的播放确认都会推进代次，旧地址无法再清除健康记录。
            if (expectedGeneration != null && expectedGeneration != generation) return
            generation++
            consecutiveFailures = 0
            blockedUntilMs = 0L
        }

        @Synchronized fun failed(attempt: ResolutionAttempt): Boolean {
            if (!attempt.recordFailure(this)) return false
            generation++
            // 冷却内的新失败使先前的播放确认过期，沿用已安排的重试时间。
            if (isBlocked()) return false
            consecutiveFailures++
            if (consecutiveFailures < 3) return false
            consecutiveFailures = 0
            blockedUntilMs = SystemClock.elapsedRealtime() + 5 * 60_000L
            return true
        }
    }

    private val scriptMetrics = ConcurrentHashMap<String, MutableMetrics>()
    private const val LOAD_RETRY_DELAY_MS = 30_000L

    private fun recordHit(scriptId: String, elapsedMs: Long) {
        val metrics = scriptMetrics.getOrPut(scriptId) { MutableMetrics() }
        metrics.hitCount.incrementAndGet()
        metrics.totalLatencyMs.addAndGet(elapsedMs.coerceAtLeast(0L))
    }

    /** 仅统计本进程内实际胜出的播放解析，不产生额外网络请求或持久化 IO。 */
    fun metrics(scriptId: String): ScriptMetrics? = scriptMetrics[scriptId]?.let { metrics ->
        val hits = metrics.hitCount.get()
        if (hits <= 0L) null else ScriptMetrics(hits, metrics.totalLatencyMs.get() / hits)
    }

    // 初始化互斥：并行解析调用可能同时触发加载，必须串行化避免重复重建引擎。
    private val loadMutex = Mutex()


    private fun store(context: Context) = LxScriptStore(context.applicationContext)

    /** 读取已安装的启用状态，不等待脚本初始化，也不为判断是否有源发起网络请求。 */
    fun hasEnabledScripts(context: Context): Boolean = store(context).hasEnabledScripts()

    internal fun hasMultipleScripts(context: Context, includeDisabled: Boolean): Boolean =
        store(context).list().count { includeDisabled || it.enabled } > 1

    /** 主源当前没有可优先尝试的同档接口时，把已就绪的健康备用提到冷却源之前。 */
    internal suspend fun shouldPreferBackup(
        context: Context,
        source: String,
        supportsQuality: (PlatformSupport) -> Boolean,
    ): Boolean = withContext(dispatcher) {
        if (entries.values.none { !it.retired && it.health[source]?.isBlocked() == true }) return@withContext false
        val sourceStore = store(context)
        var blockedPrimary = false
        var healthyBackup = false
        for (script in sourceStore.list()) {
            val entry = currentEntry(sourceStore, script.id)
            if (entry == null) {
                if (script.enabled) return@withContext false
                continue
            }
            if (entry.musicUrlSupport(source)?.let(supportsQuality) != true) continue
            val blocked = entry.health[source]?.isBlocked() == true
            if (script.enabled) {
                if (!blocked) return@withContext false
                blockedPrimary = true
            } else if (!blocked) {
                healthyBackup = true
            }
        }
        blockedPrimary && healthyBackup
    }

    private fun currentEntry(sourceStore: LxScriptStore, id: String): Entry? =
        entries[id]?.takeIf { !it.retired && it.revision == sourceStore.revision(id) }

    private fun Entry.musicUrlSupport(source: String): PlatformSupport? =
        sources[source]?.takeIf { "musicUrl" in it.actions }

    internal fun declaredPlatforms(sources: JSONObject?): Map<String, PlatformSupport> = buildMap {
        sources?.keys()?.forEach { id ->
            val node = sources.optJSONObject(id) ?: return@forEach
            fun strings(key: String): List<String> = node.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            put(id, PlatformSupport(node.optString("name", id), strings("actions").toSet(), strings("qualitys")))
        }
    }

    /** 冷源或刚被替换的源仍有机会参与；只有所有已安装版本都明确不支持才跳过搜索。 */
    internal suspend fun audioSearchPlatforms(
        context: Context,
        candidates: List<String>,
        supportsQuality: (PlatformSupport) -> Boolean = { true },
    ): List<String> =
        withContext(dispatcher) {
            val sourceStore = store(context)
            val known = sourceStore.list().map { script ->
                currentEntry(sourceStore, script.id) ?: return@withContext candidates
            }
            candidates.filter { platform ->
                known.any { it.musicUrlSupport(platform)?.let(supportsQuality) == true }
            }
        }

    private suspend fun loadEntry(
        context: Context,
        sourceStore: LxScriptStore,
        script: LxScript,
        initTimeoutMs: Long = 15_000L,
    ): Pair<String, Entry>? {
        val (code, revision) = sourceStore.content(script.id) ?: return null
        val engine = LxScriptEngine(context.applicationContext)
        return try {
            val inited = engine.initialize(code, script.id, initTimeoutMs)
            currentCoroutineContext().ensureActive()
            val supports = declaredPlatforms(inited?.optJSONObject("sources"))
            if (supports.isEmpty()) {
                engine.close()
                Log.w(TAG, "脚本「${script.id}」初始化未完成或未声明音源，已跳过")
                null
            } else {
                script.id to Entry(script, engine, supports, revision)
            }
        } catch (cancelled: CancellationException) {
            engine.close()
            throw cancelled
        } catch (error: Throwable) {
            engine.close()
            Log.w(TAG, "脚本「${script.id}」加载失败：${error.message ?: error.javaClass.simpleName}")
            null
        }
    }

    /** 每个已初始化源立即可用，慢/坏脚本不能把同批其它源拦在初始化屏障后面。 */
    private suspend fun loadEntries(
        context: Context,
        sourceStore: LxScriptStore,
        scripts: Collection<LxScript>,
        initTimeoutMs: Long,
        onReady: (Entry) -> Unit,
    ): List<Pair<String, Entry>> = coroutineScope {
        scripts.map { script ->
            async(Dispatchers.IO) {
                loadEntry(context, sourceStore, script, initTimeoutMs)?.let { (id, entry) ->
                    try {
                        val published = withContext(dispatcher) {
                            if (sourceStore.revision(id) != entry.revision) {
                                entry.engine.close()
                                false
                            } else {
                                entries[id] = entry
                                readyListeners.toList().forEach { it(entry) }
                                onReady(entry)
                                true
                            }
                        }
                        if (published) id to entry else null
                    } catch (cancelled: CancellationException) {
                        // 已发布实例由池持有；尚未发布的新实例不能泄漏JNI运行时。
                        withContext(NonCancellable + dispatcher) {
                            if (entries[id] !== entry) entry.engine.close()
                        }
                        throw cancelled
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun syncMetadata(scripts: Collection<LxScript>) {
        val current = scripts.associateBy(LxScript::id)
        scriptDisplayNames = current.mapValues { (_, script) -> script.name }
        current.values.forEach { script -> entries[script.id]?.script = script }
    }

    /** 解析结果里记录的是脚本文件 id，这里换取已同步元数据中的脚本展示名。 */
    fun scriptDisplayName(scriptId: String): String? = scriptDisplayNames[scriptId]

    internal fun scriptsInScope(scripts: Collection<LxScript>, scope: ScriptScope): List<LxScript> = when (scope) {
        ScriptScope.ENABLED -> scripts.filter(LxScript::enabled)
        ScriptScope.DISABLED -> scripts.filterNot(LxScript::enabled)
        ScriptScope.ALL -> scripts.filter(LxScript::enabled) + scripts.filterNot(LxScript::enabled)
    }

    private suspend fun ensureLoaded(
        context: Context,
        scope: ScriptScope,
        failureRetryDelayMs: Long = LOAD_RETRY_DELAY_MS,
        initTimeoutMs: Long = 15_000L,
        onReady: (Entry) -> Unit = {},
    ) = loadMutex.withLock {
        val sourceStore = store(context)
        val scripts = sourceStore.list()
        reconcileLocked(
            context = context,
            sourceStore = sourceStore,
            scripts = scripts,
            changedScriptIds = emptySet(),
            targetScriptIds = scriptsInScope(scripts, scope).mapTo(linkedSetOf()) { it.id },
            failureRetryDelayMs = failureRetryDelayMs,
            initTimeoutMs = initTimeoutMs,
            onReady = onReady,
        )
    }

    /** 已由 scriptsFor 选出的脚本只补齐自身，避免 race/resolve 再次触发整池扫描与加载。 */
    private suspend fun ensureScriptsLoaded(context: Context, scriptIds: Set<String>) {
        val sourceStore = store(context)
        fun current() = scriptIds.all { id ->
            entries[id]?.let { it.revision == sourceStore.revision(id) } == true
        }
        if (scriptIds.isEmpty() || current()) return
        loadMutex.withLock {
            if (current()) return@withLock
            reconcileLocked(
                context = context,
                sourceStore = sourceStore,
                scripts = sourceStore.list(),
                changedScriptIds = emptySet(),
                targetScriptIds = scriptIds,
                failureRetryDelayMs = LOAD_RETRY_DELAY_MS,
            )
        }
    }

    private fun logLoaded(prefix: String, loaded: Collection<Pair<String, Entry>>) {
        if (loaded.isEmpty()) return
        Log.d(
            TAG,
            "$prefix: " + loaded.joinToString(" | ") { (id, entry) ->
                "$id[${entry.sources.keys.joinToString("/")}]${if (entry.script.enabled) "开" else "关"}"
            },
        )
    }

    // 等待在途请求结束再关闭引擎，避免刷新时把正在解析的 QuickJS 运行时强行关掉。
    private suspend fun closeEntries(snapshot: Collection<Entry>) = withContext(NonCancellable) {
        // 已脱离entries后，清理所有权不能随页面/竞速调用方的取消丢失。
        snapshot.forEach { it.retired = true }
        snapshot.forEach { entry ->
            try {
                entry.lock.withLock { entry.engine.close() }
            } catch (error: Throwable) {
                Log.w(TAG, "关闭脚本「${entry.script.id}」失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun closeAll() {
        val snapshot = entries.values.toList()
        entries = linkedMapOf()
        loadFailures.clear()
        closeEntries(snapshot)
        scriptMetrics.clear()
    }

    /**
     * 同步目录并仅加载 targetScriptIds。关闭的备用源不会进入正常播放初始化路径，
     * 但删除、覆盖和版本变化仍会立即物理下线旧引擎。
     */
    private suspend fun reconcileLocked(
        context: Context,
        sourceStore: LxScriptStore,
        scripts: List<LxScript>,
        changedScriptIds: Set<String>,
        targetScriptIds: Set<String>,
        failureRetryDelayMs: Long,
        initTimeoutMs: Long = 15_000L,
        onReady: (Entry) -> Unit = {},
    ) {
        val installed = scripts.associateBy(LxScript::id)
        val contentChanged = entries.values.mapNotNullTo(linkedSetOf()) { entry ->
            entry.script.id.takeIf { sourceStore.revision(it) != entry.revision }
        }
        val retireIds = (entries.keys - installed.keys) +
            changedScriptIds.intersect(installed.keys) + contentChanged
        retireIds.forEach { id ->
            loadFailures.remove(id)
            scriptMetrics.remove(id)
        }
        val retired = retireIds.mapNotNull { entries.remove(it) }
        closeEntries(retired)

        val now = System.currentTimeMillis()
        val targets = targetScriptIds.mapNotNull(installed::get).filter { script ->
            val failure = loadFailures[script.id]
            script.id !in entries && (failure == null || failure.revision != sourceStore.revision(script.id) || failure.retryAt <= now)
        }
        val attemptedRevisions = targets.associate { it.id to sourceStore.revision(it.id) }
        targetScriptIds.mapNotNull(entries::get).forEach { entry ->
            installed[entry.script.id]?.let { entry.script = it }
            onReady(entry)
        }
        val loaded = loadEntries(context, sourceStore, targets, initTimeoutMs, onReady)
        val loadedIds = loaded.mapTo(hashSetOf()) { it.first }
        targets.forEach { script ->
            if (script.id in loadedIds || failureRetryDelayMs <= 0L) {
                loadFailures.remove(script.id)
            } else {
                loadFailures[script.id] = LoadFailure(attemptedRevisions[script.id], System.currentTimeMillis() + failureRetryDelayMs)
            }
        }

        // 初始化期间用户可能再次切换开关，以当前持久化状态为准。
        syncMetadata(sourceStore.list())
        logLoaded("已按需加载脚本", loaded)
    }

    /** 应用启动后后台预热开启源；不阻塞 Application 主线程与首屏。 */
    fun warmEnabled(context: Context) {
        val appContext = context.applicationContext
        requestScope.launch {
            runCatching {
                withContext(dispatcher) {
                    ensureLoaded(appContext, ScriptScope.ENABLED, failureRetryDelayMs = 0L)
                }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    Log.w(TAG, "预热开启音源失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * 增量刷新导入、覆盖或删除的脚本；只初始化已开启脚本，关闭脚本等真实兜底时再加载。
     */
    suspend fun refresh(context: Context, changedScriptIds: Set<String> = emptySet()): Int =
        withContext(dispatcher) {
            loadMutex.withLock {
                val sourceStore = store(context)
                val scripts = sourceStore.list()
                reconcileLocked(
                    context = context,
                    sourceStore = sourceStore,
                    scripts = scripts,
                    changedScriptIds = changedScriptIds,
                    targetScriptIds = scripts.filter(LxScript::enabled).mapTo(linkedSetOf()) { it.id },
                    failureRetryDelayMs = 0L,
                )
                entries.size
            }
        }

    /** 用户主动重载时才关闭现有实例并检查全部已安装脚本。 */
    suspend fun reload(context: Context): Int = withContext(dispatcher) {
        loadMutex.withLock {
            closeAll()
            val sourceStore = store(context)
            val scripts = sourceStore.list()
            reconcileLocked(
                context = context,
                sourceStore = sourceStore,
                scripts = scripts,
                changedScriptIds = emptySet(),
                targetScriptIds = scripts.mapTo(linkedSetOf()) { it.id },
                failureRetryDelayMs = 0L,
            )
            entries.size
        }
    }

    /** 支持指定平台动作的脚本；ALL 时开启的源在前、未开启的源在后。 */
    suspend fun scriptsFor(
        context: Context,
        source: String,
        action: String = "musicUrl",
        scope: ScriptScope = ScriptScope.ENABLED,
    ): List<Pair<LxScript, PlatformSupport>> =
        withContext(dispatcher) {
            ensureLoaded(context, scope)
            val supported = entries.values
                .filter { entry -> entry.sources[source]?.actions?.contains(action) == true }
            val ordered = when (scope) {
                ScriptScope.ENABLED -> supported.filter { it.script.enabled }
                ScriptScope.DISABLED -> supported.filter { !it.script.enabled }
                ScriptScope.ALL -> supported.filter { it.script.enabled } + supported.filter { !it.script.enabled }
            }
            if (ordered.isEmpty()) Log.d(TAG, "无可用脚本: source=$source action=$action scope=$scope")
            ordered.map { it.script to it.sources.getValue(source) }
        }

    private suspend fun requestEntry(
        entry: Entry,
        source: String,
        action: String,
        info: JSONObject,
        timeoutMs: Long,
    ): Any = entry.engine.withCancellation { token ->
        entry.lock.withLock {
            check(!entry.retired) { "音源已更新，请重新解析" }
            entry.engine.request(token, source, action, info, timeoutMs)
        }
    }

    suspend fun resolve(
        context: Context,
        scriptId: String,
        source: String,
        action: String,
        info: JSONObject,
        timeoutMs: Long = 20_000,
    ): Any? {
        val entry = withContext(dispatcher) {
            ensureScriptsLoaded(context, setOf(scriptId))
            entries[scriptId]
        } ?: return null
        return try {
            requestEntry(entry, source, action, info, timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /** 同档音源竞速：初始化一项就投递一项；不创建低码率对冲引擎。 */
    internal suspend fun <T : Any> race(
        context: Context,
        source: String,
        scope: ScriptScope,
        timeoutMs: Long,
        resolutionAttempt: ResolutionAttempt = ResolutionAttempt(),
        request: (LxScript, PlatformSupport) -> ScriptRequest?,
        accept: suspend (ScriptResult) -> CandidateVerdict<T>,
    ): T? = coroutineScope {
        val results = Channel<Pair<ScriptResult, T>>(Channel.UNLIMITED)
        val attempted = mutableSetOf<String>()
        val producer = launch {
            try {
                coroutineScope {
                    val requestsScope = this
                    withContext(dispatcher) {
                        val sourceStore = store(context)
                        val installed = scriptsInScope(sourceStore.list(), scope)
                        val eligible = installed.mapTo(hashSetOf()) { it.id }
                        val blocked = linkedMapOf<String, Pair<Entry, ScriptRequest>>()
                        var hasHealthyCandidate = false
                        fun dispatch(entry: Entry, spec: ScriptRequest) {
                            if (!attempted.add(entry.script.id)) return
                            val health = entry.health.getOrPut(source) { PlatformHealth() }
                            fun failed() {
                                if (health.failed(resolutionAttempt)) Log.w(TAG, "音源接口连续失败，暂停优先使用：${entry.script.id}/$source")
                            }
                            requestsScope.launch(Dispatchers.IO) {
                                try {
                                    val startedAt = System.nanoTime()
                                    val data = requestEntry(
                                        entry = entry,
                                        source = spec.source,
                                        action = spec.action,
                                        info = spec.info,
                                        timeoutMs = spec.timeoutMs,
                                    )
                                    currentCoroutineContext().ensureActive()
                                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                                    val result = ScriptResult(spec.requestId, entry.script.id, data, elapsedMs)
                                    val verdict = if (result.url == null) CandidateVerdict.Invalid else {
                                        result.playbackConfirmation = health.pendingPlaybackConfirmation()
                                        accept(result)
                                    }
                                    currentCoroutineContext().ensureActive()
                                    when (verdict) {
                                        is CandidateVerdict.Accepted -> {
                                            if (verdict.verified) health.succeeded()
                                            results.trySend(result to verdict.value)
                                        }
                                        CandidateVerdict.Healthy -> health.succeeded()
                                        CandidateVerdict.Skipped -> Unit
                                        CandidateVerdict.Invalid -> failed()
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    currentCoroutineContext().ensureActive()
                                    if (error !is LxScriptRequestException || !error.isMediaUnavailable) {
                                        failed()
                                    }
                                    Log.d(TAG, "音源未果(${entry.script.id}/${spec.requestId}): ${error.javaClass.simpleName}")
                                }
                            }
                        }
                        fun ready(entry: Entry) {
                            if (entry.script.id !in eligible) return
                            if (entry.retired || sourceStore.revision(entry.script.id) != entry.revision) return
                            val support = entry.musicUrlSupport(source) ?: return
                            val spec = request(entry.script, support) ?: return
                            // 先确认平台、动作和档位可用；无关平台的健康脚本不能阻止兜底。
                            if (entry.health[source]?.isBlocked() == true) {
                                blocked[entry.script.id] = entry to spec
                            } else {
                                hasHealthyCandidate = true
                                dispatch(entry, spec)
                            }
                        }
                        val listener: (Entry) -> Unit = ::ready
                        readyListeners.add(listener)
                        try {
                            installed.forEach { script ->
                                entries[script.id]?.let(listener)
                            }
                            ensureLoaded(context, scope, initTimeoutMs = timeoutMs, onReady = listener)
                            if (!hasHealthyCandidate) blocked.values.forEach { (entry, spec) -> dispatch(entry, spec) }
                        } finally { readyListeners.remove(listener) }
                    }
                }
            } finally {
                // 所有解析子任务退出后再关通道；初始化完成不代表媒体响应已返回。
                results.close()
            }
        }
        try {
            withTimeoutOrNull(timeoutMs) { results.receiveCatching().getOrNull() }?.let { (result, accepted) ->
                recordHit(result.scriptId, result.elapsedMs)
                accepted
            }
        } finally {
            producer.cancel()
            results.cancel()
        }
    }
}
