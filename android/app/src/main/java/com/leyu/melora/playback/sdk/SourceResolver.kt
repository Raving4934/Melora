package com.leyu.melora.playback.sdk

import android.content.Context
import android.util.Log
import com.leyu.melora.BuildConfig
import com.leyu.melora.playback.MeloraSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * 播放解析链路。
 *
 * 1. 在线音频只由用户导入并启用的脚本解析；目录、封面和歌词不依赖音频源；
 * 2. 自动换源开启时才回退到未开启的备用源，并跨平台检索同曲解析；
 * 3. 歌词与封面由内置目录模块提供（音源脚本只负责播放解析）。
 */
object SourceResolver {
    // 质量从高到低；用户偏好决定起始档位，失败时按既定阶梯降级以优先保证可播放。
    private val QUALITY_ORDER = listOf(
        "master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "wav", "ape", "320k", "192k", "128k",
    )
    private val SWITCH_SOURCES = listOf("kw", "kg", "wy", "tx", "mg")
    // 本地媒体歌曲的 source；联网取封面/歌词前先按标题/歌手搜出真实在线条目
    private const val LOCAL_SOURCE = "local"
    private const val LOCAL_MATCH_SOURCE = "kw"

    data class Resolved(
        val url: String,
        val quality: String,
        val song: OnlineSong,
        val switched: Boolean,
        // 实际文件身份，不是歌曲ID；用于隔离不同解析后端、编码与资源的缓存分片。
        val resourceId: String,
    )

    enum class Purpose { PLAYBACK, CACHE_FILL, DOWNLOAD, REBUFFER }

    internal data class ResolveKey(
        val songUid: String,
        val preferredQuality: String,
        val allowSwitch: Boolean,
    )

    private data class InFlightKey(
        val resolveKey: ResolveKey,
        val refresh: Boolean,
        val purpose: Purpose,
        val rejected: Set<String>,
    )

    private data class CachedUrl(val resolved: Resolved, val expireAt: Long)

    private val cacheLock = Any()
    // 播放地址缓存：按歌曲、目标音质与换源策略隔离，避免播放与下载相互污染。
    private val urlCache = mutableMapOf<ResolveKey, CachedUrl>()
    private val failedResources = mutableMapOf<String, MutableMap<String, Long>>()
    private val observedQualities = linkedMapOf<String, String>()
    // 在应用级作用域合并同参数请求：调用方取消只停止等待，不会毒化其他播放/预取调用方。
    private val inFlight = SingleFlight<InFlightKey, Resolved>(CoroutineScope(SupervisorJob() + Dispatchers.IO), cacheLock)

    private const val URL_TTL_MS = 25 * 60 * 1000L
    private const val BOOK_URL_TTL_MS = 8 * 60 * 1000L
    private const val ALT_TTL_MS = 30 * 60 * 1000L
    private const val MATCH_CACHE_PREFIX = "resolver:matches:"
    private const val ALT_SEARCH_REQUEST_TIMEOUT_MS = 20_000L
    private const val ALT_SEARCH_TOTAL_BUDGET_MS = 35_000L
    private const val PLAYBACK_TIER_BUDGET_MS = 6_000L
    private const val DOWNLOAD_TIER_BUDGET_MS = 20_000L
    private const val SOURCE_REQUEST_TIMEOUT_MS = 4_500L
    private const val BACKUP_START_DELAY_MS = 600L
    private const val ALTERNATIVE_START_DELAY_MS = 1_200L
    private const val PLAYBACK_MATCH_TIMEOUT_MS = 3_000L
    private const val DEGRADED_URL_TTL_MS = 45_000L
    private const val FAILED_RESOURCE_TTL_MS = 5 * 60_000L

    fun clearCache() {
        synchronized(cacheLock) {
            // 先推进统一代次并撤销旧 flight，再清理缓存；取消竞态完成也不得回填。
            inFlight.fence()
            urlCache.clear()
            failedResources.clear()
            observedQualities.clear()
            OnlineCache.clear(MATCH_CACHE_PREFIX)
        }
    }

    /** 解码后的规格只纠正同一物理资源，不能据此改写其它音源/URL的档位。 */
    fun confirmQuality(resourceId: String, quality: String) = synchronized(cacheLock) {
        observedQualities[resourceId] = quality
        while (observedQualities.size > 256) observedQualities.remove(observedQualities.keys.first())
        urlCache.replaceAll { _, cached ->
            if (cached.resolved.resourceId == resourceId) cached.copy(
                resolved = cached.resolved.copy(quality = quality),
                expireAt = if (cached.resolved.quality == quality) cached.expireAt
                    else minOf(cached.expireAt, System.currentTimeMillis() + DEGRADED_URL_TTL_MS),
            ) else cached
        }
    }

    fun observedQuality(resourceId: String?): String? = synchronized(cacheLock) { observedQualities[resourceId] }

    /** 只隔离本曲实际失败的资源，不清空其它曲目/下载缓存，也不把坏URL再交回播放器。 */
    fun rejectResource(songUid: String, resourceId: String) = synchronized(cacheLock) {
        failedResources.getOrPut(songUid) { mutableMapOf() }[resourceId] =
            System.currentTimeMillis() + FAILED_RESOURCE_TTL_MS
        urlCache.entries.removeAll { it.value.resolved.resourceId == resourceId }
    }

    fun isRejected(songUid: String, resourceId: String?): Boolean =
        resourceId != null && resourceId in rejectedResources(songUid)

    /** 跨 UID 完整缓存共享时，遵守任一消费者对同一物理资源的失败冷却，不新增失败账本。 */
    internal fun isResourceRejected(resourceId: String): Boolean = synchronized(cacheLock) {
        val now = System.currentTimeMillis()
        failedResources.values.any { (it[resourceId] ?: 0L) > now }
    }

    private fun rejectedResources(songUid: String): Set<String> = synchronized(cacheLock) {
        val failures = failedResources[songUid] ?: return@synchronized emptySet()
        val now = System.currentTimeMillis()
        failures.entries.removeAll { it.value <= now }
        if (failures.isEmpty()) failedResources.remove(songUid)
        failures.keys.toSet()
    }

    /** 命中缓存则立即返回（非挂起），供预取判重与点击前的快速检查。 */
    fun peek(song: OnlineSong, preferredQuality: String, allowSwitch: Boolean = true): Resolved? =
        peek(ResolveKey(song.uid, normalizedQuality(preferredQuality), allowSwitch))

    private fun peek(key: ResolveKey): Resolved? {
        synchronized(cacheLock) {
            val entry = urlCache[key] ?: return null
            if (entry.expireAt <= System.currentTimeMillis()) {
                urlCache.remove(key, entry)
                return null
            }
            return entry.resolved
        }
    }

    /** 只有用户仍在等待的当前播放，才提交已选中的备用资源；普通解析仍走同一入口。 */
    internal fun selectForPlayback(song: OnlineSong, preferredQuality: String, resolved: Resolved) {
        val key = ResolveKey(song.uid, normalizedQuality(preferredQuality), MeloraSettings.autoSwitchSource.value)
        cache(key, resolved, inFlight.currentGeneration())
    }

    private fun cache(key: ResolveKey, resolved: Resolved, generation: Long) {
        val ttlMs = when {
            !sameQualityTier(key.preferredQuality, resolved.quality) -> DEGRADED_URL_TTL_MS
            resolved.song.isBookChapter -> BOOK_URL_TTL_MS
            else -> URL_TTL_MS
        }
        synchronized(cacheLock) {
            if (generation != inFlight.currentGeneration() || isRejected(key.songUid, resolved.resourceId)) return
            val expireAt = System.currentTimeMillis() + ttlMs
            urlCache[key] = CachedUrl(resolved, expireAt)
            if (resolved.song.uid != key.songUid) {
                urlCache[key.copy(songUid = resolved.song.uid)] = CachedUrl(resolved.copy(switched = false), expireAt)
            }
        }
    }

    suspend fun resolve(
        context: Context,
        song: OnlineSong,
        preferredQuality: String,
        allowSwitch: Boolean = true,
        isRefresh: Boolean = false,
        purpose: Purpose = Purpose.PLAYBACK,
        excludedResources: Set<String> = emptySet(),
    ): Resolved {
        while (true) {
            // 完整缓存/本地文件在播放器与下载器的上游已处理；这里只允许启用源解析新的网络资源。
            check(LxScriptPool.hasEnabledScripts(context)) { NO_SOURCE_MESSAGE }
            val generation = inFlight.currentGeneration()
            val key = ResolveKey(song.uid, normalizedQuality(preferredQuality), allowSwitch)
            // 慢源只在本次恢复中排除，不污染下载、缓存或全局失败名单。
            val rejected = rejectedResources(song.uid) + excludedResources
            val cached = if (isRefresh) null else peek(key)?.takeIf {
                it.resourceId !in rejected &&
                    (purpose == Purpose.PLAYBACK || sameQualityTier(key.preferredQuality, it.quality))
            }
            val resolved = cached ?: try {
                inFlight.run(InFlightKey(key, isRefresh, purpose, rejected), generation) {
                    resolveAndCache(context.applicationContext, song, key, purpose, generation, rejected)
                }
            } catch (_: FlightFencedException) {
                // 仅重建被 clearCache() 主动撤销的旧代次；普通调用方取消不进入此分支。
                currentCoroutineContext().ensureActive()
                continue
            }
            if (generation != inFlight.currentGeneration() || isRejected(song.uid, resolved.resourceId)) continue
            return observedQuality(resolved.resourceId)?.let { resolved.copy(quality = it) } ?: resolved
        }
    }

    private suspend fun resolveAndCache(
        context: Context,
        song: OnlineSong,
        key: ResolveKey,
        purpose: Purpose,
        generation: Long,
        excludedResources: Set<String>,
    ): Resolved {
        val startedAt = System.nanoTime()
        val resolved = supervisorScope {
            // 匹配任务跨音质档复用，按需启动；一个慢平台不再阻塞其它平台的有效候选。
            val matches = if (key.allowSwitch && !song.isBookChapter) {
                SWITCH_SOURCES.filterNot { it == song.source }.map { source ->
                    async(start = CoroutineStart.LAZY) {
                        findMatchedSongs(song, source, PLAYBACK_MATCH_TIMEOUT_MS) { keyword ->
                            OnlineRepository.search(context, source, keyword, limit = 25,
                                background = purpose != Purpose.PLAYBACK,
                                timeoutMs = PLAYBACK_MATCH_TIMEOUT_MS).list
                        }
                    }
                }
            } else emptyList()
            val alternativeSlots = Semaphore(2)
            val lowerResults = ConcurrentHashMap<String, Resolved>()
            fun accept(result: Resolved?, quality: String): Resolved? {
                if (result == null || result.resourceId in excludedResources || isRejected(song.uid, result.resourceId)) return null
                val actual = result.copy(quality = observedQuality(result.resourceId) ?: result.quality, switched = result.song.uid != song.uid)
                if (if (purpose == Purpose.REBUFFER) sameQualityTier(quality, actual.quality)
                    else qualitySatisfies(quality, actual.quality)) return actual
                // 已收到的真实低档响应留给后续档位，不重复请求，也不允许它抢占当前高档。
                if (qualityRank(actual.quality) >= 0) lowerResults[actual.quality] = actual
                return null
            }
            try {
                resolveTiers(key.preferredQuality, purpose) { quality ->
                    lowerResults.values.filter { qualitySatisfies(quality, it.quality) }
                        .maxByOrNull { qualityRank(it.quality) }?.let { return@resolveTiers it }
                    val requestTimeout = if (purpose == Purpose.DOWNLOAD || purpose == Purpose.CACHE_FILL) 8_000L else SOURCE_REQUEST_TIMEOUT_MS
                    suspend fun scripts(candidate: OnlineSong, scope: LxScriptPool.ScriptScope): Resolved? =
                        resolveOnScripts(context, candidate, quality, scope, requestTimeout,
                            if (purpose == Purpose.DOWNLOAD || purpose == Purpose.CACHE_FILL) DOWNLOAD_TIER_BUDGET_MS else PLAYBACK_TIER_BUDGET_MS) { accept(it, quality) != null }
                            ?.let { it.copy(switched = it.song.uid != song.uid) }
                    preferFirst(ALTERNATIVE_START_DELAY_MS,
                        primary = {
                            preferFirst(BACKUP_START_DELAY_MS,
                                primary = { scripts(song, LxScriptPool.ScriptScope.ENABLED) },
                                fallback = { if (key.allowSwitch) scripts(song, LxScriptPool.ScriptScope.DISABLED) else null })
                        },
                        fallback = {
                            firstSuccessful(matches) { match ->
                                match.await().take(3).firstNotNullOfOrNull { candidate ->
                                    alternativeSlots.withPermit { scripts(candidate, LxScriptPool.ScriptScope.ALL) }
                                }
                            }
                        })
                }
            } finally { matches.forEach { it.cancel() } }
        }
        // 恢复候选只有仍卡顿且用户仍想播放时才接管；不能提前污染正常解析缓存。
        if (purpose != Purpose.REBUFFER) cache(key, resolved, generation)
        if (BuildConfig.DEBUG) Log.d("SourceResolver", "解析完成 ${song.source}: ${key.preferredQuality} → ${resolved.quality}, ${(System.nanoTime() - startedAt) / 1_000_000}ms")
        return resolved
    }

    /** 播放/下载/补齐共用唯一阶梯；各档完整尝试同档源，低档不能竞速抢占高档。 */
    internal suspend fun resolveTiers(
        preferredQuality: String,
        purpose: Purpose,
        tierBudgetMs: Long = if (purpose == Purpose.DOWNLOAD || purpose == Purpose.CACHE_FILL) DOWNLOAD_TIER_BUDGET_MS else PLAYBACK_TIER_BUDGET_MS,
        resolve: suspend (String) -> Resolved?,
    ): Resolved {
        val qualities = if (purpose == Purpose.REBUFFER) listOf(normalizedQuality(preferredQuality))
            else qualityAttempts(preferredQuality)
        for ((index, quality) in qualities.withIndex()) {
            val budget = if (index == 0) tierBudgetMs else minOf(tierBudgetMs, 12_000L)
            attempt { withTimeoutOrNull(budget) { resolve(quality) } }
                ?.takeIf {
                    if (purpose == Purpose.REBUFFER) sameQualityTier(quality, it.quality)
                    else qualitySatisfies(quality, it.quality)
                }?.let { return it }
        }
        if (purpose == Purpose.REBUFFER) throw IllegalStateException("同音质备用资源不可用")
        val action = if (purpose == Purpose.DOWNLOAD) "下载" else "播放"
        throw IllegalStateException("各档${action}音质均不可用，请检查网络或音源")
    }

    internal fun qualityAttempts(preferred: String): List<String> {
        val normalized = normalizedQuality(preferred)
        val qualities = linkedSetOf(normalized)
        if (QUALITY_ORDER.indexOf(normalized) < QUALITY_ORDER.indexOf("flac")) qualities.add("flac")
        if (QUALITY_ORDER.indexOf(normalized) <= QUALITY_ORDER.indexOf("320k")) qualities.add("320k")
        qualities.add("128k")
        return qualities.toList()
    }

    internal fun normalizedQuality(preferred: String): String =
        preferred.trim().lowercase(Locale.ROOT).takeIf(QUALITY_ORDER::contains) ?: "320k"

    private suspend fun resolveOnScripts(
        context: Context,
        song: OnlineSong,
        quality: String,
        scope: LxScriptPool.ScriptScope,
        timeoutMs: Long,
        budgetMs: Long,
        accept: (Resolved) -> Boolean,
    ): Resolved? {
        val winner = LxScriptPool.race(context, song.source, scope, budgetMs,
            request = { script, support ->
                selectQuality(quality, support.qualitys)?.let { parameter ->
                    LxScriptPool.ScriptRequest(parameter, script.id, song.source, "musicUrl",
                        JSONObject().put("type", parameter).put("musicInfo", song.raw), timeoutMs)
                }
            },
            accept = { result -> scriptResolution(song, result)?.let(accept) == true }) ?: return null
        return scriptResolution(song, winner)?.let { result ->
            observedQuality(result.resourceId)?.let { result.copy(quality = it) } ?: result
        }
    }

    internal fun scriptResolution(song: OnlineSong, winner: LxScriptPool.ScriptResult): Resolved? {
        val url = extractUrl(winner.data) ?: return null
        val payload = winner.data as? JSONObject
        val actualSource = payload?.optString("source")?.takeIf(String::isNotBlank) ?: song.source
        val actualSong = payload?.optJSONObject("musicInfo")?.let { raw ->
            OnlineSong(JSONObject(raw.toString()).put("source", actualSource))
        } ?: song.takeIf { actualSource == song.source } ?: return null
        if (actualSong.uid != song.uid && alternativeScore(song, actualSong) == null) return null
        val reported = payload?.optString("type")?.trim()?.lowercase(Locale.ROOT)
        val quality = reported?.takeIf { qualityRank(it) >= 0 } ?: winner.requestId
        val stableId = payload?.optString("resourceId")?.takeIf { it.isNotBlank() && it.length <= 1024 }
        // 物理资源标识由脚本命名空间、实际曲目和档位隔离，不能跨歌曲拼接缓存。
        val identity = stableId?.let { "${actualSong.uid}|$quality|$it" } ?: url
        return Resolved(url, quality, actualSong, actualSong.uid != song.uid, scriptResourceId(winner.scriptId, identity))
    }

    const val NO_SOURCE_MESSAGE = "当前未启用可用音源，无法解析在线音频。请前往设置 → 自定义源导入并启用音源。"

    /** 脚本不提供稳定文件ID时保守按URL隔离；无法证明同一文件就不跨新链接拼接旧分片。 */
    internal fun scriptResourceId(scriptId: String, url: String): String =
        "lx:$scriptId:" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    internal fun extractUrl(data: Any?): String? = when (data) {
        is String -> urlFromText(data)
        is JSONObject -> urlFromObject(data)
        else -> null
    }

    private fun urlFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("{")) {
            return runCatching { urlFromObject(JSONObject(trimmed)) }.getOrNull()
        }
        return null
    }

    private fun urlFromObject(obj: JSONObject): String? {
        for (key in listOf("url", "surl", "musicUrl", "play_url", "urlHttps")) {
            val value = obj.optString(key)
            if (value.startsWith("http", ignoreCase = true)) return value
        }
        for (key in listOf("data", "result", "body")) {
            when (val nested = obj.opt(key)) {
                is JSONObject -> urlFromObject(nested)?.let { return it }
                is String -> urlFromText(nested)?.let { return it }
            }
        }
        return null
    }

    private fun equivalentQualities(quality: String): List<String> = when (quality) {
        "flac24bit", "hires", "flac32bit", "master" -> listOf("flac24bit", "hires", "flac32bit", "master")
        else -> listOf(quality)
    }

    /** 目录音质仅用于列表展示，不能否决脚本可提供的档位；脚本参数严格按其声明映射。 */
    internal fun selectQuality(preferred: String, scriptQualitys: List<String>): String? {
        val normalized = normalizedQuality(preferred)
        val supported = scriptQualitys.map { it.trim().lowercase(Locale.ROOT) }
        if (supported.isEmpty()) return normalized
        return (listOf(normalized) + equivalentQualities(normalized)).firstOrNull(supported::contains)
    }

    internal fun sameQualityTier(requested: String, actual: String): Boolean =
        actual.trim().lowercase(Locale.ROOT) in equivalentQualities(normalizedQuality(requested))

    internal fun qualitySatisfies(requested: String, actual: String): Boolean =
        qualityRank(actual) >= qualityRank(normalizedQuality(requested))

    internal fun qualityRank(quality: String): Int = when (quality.trim().lowercase(Locale.ROOT)) {
        "master", "atmos_plus", "atmos", "hires", "flac24bit", "flac32bit" -> 4
        "flac", "wav", "ape" -> 3
        "320k" -> 2
        "192k" -> 1
        "128k", "mp3", "aac", "ogg" -> 0
        // 编码专属标称码率不是MP3档位；只作为最低档兜底，仍保留原始AAC/OGG标签。
        else -> {
            val value = quality.lowercase(Locale.ROOT)
            val bitrate = value.takeIf { it.endsWith("k") }?.removeSuffix("k")?.toIntOrNull()
            when {
                bitrate != null && bitrate >= 320 -> 2
                bitrate != null && bitrate >= 192 -> 1
                bitrate != null && bitrate > 0 -> 0
                Regex("(?:aac|ogg)[1-9][0-9]*k").matches(value) -> 0
                else -> -1
            }
        }
    }

    /** 同档主源先行；失败立即启动后备，慢请求只让同档后备竞速，绝不对冲低音质。 */
    internal suspend fun <T : Any> preferFirst(
        delayMs: Long,
        primary: suspend () -> T?,
        fallback: suspend () -> T?,
    ): T? = supervisorScope {
        val first = async { attempt { primary() } }
        try {
            withTimeoutOrNull(delayMs) { first.await() }?.let { return@supervisorScope it }
            if (first.isCompleted) first.await() ?: fallback()
            else firstSuccessful(listOf<suspend () -> T?>({ first.await() }, fallback)) { it() }
        } finally { first.cancel() }
    }

    internal suspend fun <I, O : Any> firstSuccessful(inputs: List<I>, resolve: suspend (I) -> O?): O? = supervisorScope {
        val results = Channel<O?>(inputs.size.coerceAtLeast(1))
        val jobs = inputs.map { input -> launch { results.send(attempt { resolve(input) }) } }
        try {
            repeat(inputs.size) { results.receive()?.let { return@supervisorScope it } }
            null
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    /** 全平台兜底与内置定向解析共用匹配/缓存，不重复维护两套搜索。 */
    suspend fun findAlternatives(context: Context, song: OnlineSong, background: Boolean = false, force: Boolean = false): List<OnlineSong> {
        if (song.isBookChapter) return emptyList()
        return parallelMapWithinBudget(
            inputs = SWITCH_SOURCES.filterNot { it == song.source },
            budgetMs = ALT_SEARCH_TOTAL_BUDGET_MS,
        ) { source ->
            findMatchedSongs(song, source, ALT_SEARCH_REQUEST_TIMEOUT_MS, force = force) { keyword ->
                OnlineRepository.search(
                    context, source, keyword, limit = 25,
                    background = background, timeoutMs = ALT_SEARCH_REQUEST_TIMEOUT_MS,
                ).list
            }
        }.flatten().sortedByDescending { alternativeScore(song, it) }.distinctBy { it.uid }
    }

    /** 每个目标平台单飞；缓存仅保存有效匹配，网络故障/空结果不能阻塞恢复重试。 */
    internal suspend fun findMatchedSongs(
        song: OnlineSong,
        targetSource: String,
        timeoutMs: Long,
        requireArtist: Boolean = true,
        cacheNamespace: String = "",
        score: (OnlineSong, OnlineSong) -> Int? = ::alternativeScore,
        force: Boolean = false,
        search: suspend (String) -> List<OnlineSong>,
    ): List<OnlineSong> {
        if (song.isBookChapter || song.source.isBlank() || song.songmid.isBlank() ||
            filterStr(song.name).isBlank() || (requireArtist && artistNames(song.singer).isEmpty())
        ) return emptyList()
        val generation = inFlight.currentGeneration()
        val key = matchingCacheKey(song, targetSource, cacheNamespace, generation)
        val result = try {
            attempt {
                // 保留每个等待者的独立预算；最后一个等待者超时/取消时撤销旧flight，避免重试合并到空结果。
                withTimeoutOrNull(timeoutMs) {
                    OnlineCache.refresh(key, ALT_TTL_MS, force = force, cancelWhenUnobserved = true) {
                        withTimeoutOrNull(timeoutMs) {
                            search("${song.name} ${song.singer}".trim()).mapNotNull { candidate ->
                                if (candidate.source != targetSource) return@mapNotNull null
                                score(song, candidate)?.let { it to candidate }
                            }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.uid }
                        }.orEmpty()
                    }
                }
            }.orEmpty()
        } catch (cancelled: CancellationException) {
            // clearCache() 取消的是旧代次的匹配刷新；调用方自己的取消仍需传播。
            currentCoroutineContext().ensureActive()
            if (generation != inFlight.currentGeneration()) return emptyList()
            throw cancelled
        }
        return result.takeIf { generation == inFlight.currentGeneration() }.orEmpty()
    }

    internal fun matchingCacheKey(
        song: OnlineSong,
        targetSource: String,
        cacheNamespace: String = "",
        generation: Long = inFlight.currentGeneration(),
    ): String = "$MATCH_CACHE_PREFIX$generation:$cacheNamespace$targetSource:${alternativeCacheKey(song)}"

    internal fun alternativeCacheKey(song: OnlineSong): String = listOf(
        song.source,
        filterStr(song.name),
        artistNames(song.singer).sorted().joinToString(","),
        filterStr(song.albumName),
        song.intervalSeconds.toString(),
        versionTags(song).sorted().joinToString(","),
    ).joinToString("|")

    /** 同名不等于同录音：歌手与版本必须一致；缺失时长时额外要求同专辑。 */
    internal fun alternativeScore(song: OnlineSong, candidate: OnlineSong): Int? {
        if (song.isBookChapter || candidate.isBookChapter || song.source.isBlank() || song.songmid.isBlank() ||
            candidate.source.isBlank() || candidate.songmid.isBlank()
        ) return null
        val name = filterStr(song.name)
        if (name.isBlank() || filterStr(candidate.name) != name) return null
        val artists = artistNames(song.singer)
        if (artists.isEmpty() || artists != artistNames(candidate.singer)) return null
        if (!sameRecordingVersion(song, candidate)) return null

        val album = filterStr(song.albumName)
        val albumMatch = album.isNotEmpty() && filterStr(candidate.albumName) == album
        val knownDurations = song.intervalSeconds > 0 && candidate.intervalSeconds > 0
        if (knownDurations && kotlin.math.abs(candidate.intervalSeconds - song.intervalSeconds) > 5) return null
        if (!knownDurations && !albumMatch) return null
        return 12 + (if (albumMatch) 2 else 0) + (if (knownDurations) 2 else 0)
    }

    /**
     * 本地文件可能没有歌手标签；此时只在“同名 + 同版本 + 时长接近”证据充分时放宽歌手约束。
     * 在线换源仍使用 alternativeScore 的严格歌手匹配，不受这里影响。
     */
    internal fun localMetadataScore(song: OnlineSong, candidate: OnlineSong): Int? {
        if (artistNames(song.singer).isNotEmpty()) return alternativeScore(song, candidate)
        if (song.isBookChapter || candidate.isBookChapter || song.source.isBlank() || song.songmid.isBlank() ||
            candidate.source.isBlank() || candidate.songmid.isBlank()
        ) return null
        val name = filterStr(song.name)
        if (name.isBlank() || filterStr(candidate.name) != name) return null
        if (!sameRecordingVersion(song, candidate)) return null
        val album = filterStr(song.albumName)
        val albumMatch = album.isNotEmpty() && filterStr(candidate.albumName) == album
        val knownDurations = song.intervalSeconds > 0 && candidate.intervalSeconds > 0
        if (knownDurations && kotlin.math.abs(candidate.intervalSeconds - song.intervalSeconds) > 5) return null
        if (!knownDurations && !albumMatch) return null
        return 8 + (if (albumMatch) 2 else 0) + (if (knownDurations) 4 else 0)
    }

    private val punctuation = Regex("[\\p{P}\\p{Z}\\s]+")
    private val artistSeparator = Regex("\\s*(?:[、,，/&;；]|\\s+(?:feat\\.?|ft\\.?|featuring|and|×|x)\\s+)\\s*", RegexOption.IGNORE_CASE)
    private val unknownArtists = setOf("未知", "未知歌手", "未知艺术家", "群星", "unknown", "unknownartist", "variousartists", "va", "null")
    private val versions = listOf(
        "(?:\\blive\\b|live版|现场|演唱会)", "(?:\\bremix\\b|\\bdj\\b|混音)",
        "(?:\\binstrumental\\b|off[ -]?vocal|伴奏|纯音乐)", "(?:\\bcover\\b|翻唱)",
        "(?:sped[ -]?up|speed[ -]?up|加速)", "(?:\\bslowed\\b|慢速|降速)",
        "(?:\\bremaster(?:ed)?\\b|重制|重录)",
        "(?:\\bacoustic\\b|\\bunplugged\\b|不插电)", "(?:a[ -]?cappella|清唱)",
        "(?:\\bradio[ -]?edit\\b|电台剪辑版)", "(?:\\bdemo\\b|小样)",
        "(?:\\balternate[ -]?take\\b|另一录音版本)",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun filterStr(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(punctuation, "")

    private fun artistNames(value: String): Set<String> {
        val artists = Normalizer.normalize(value, Normalizer.Form.NFKC).split(artistSeparator)
            .map(::filterStr).filter(String::isNotBlank).toSet()
        return artists.takeUnless { it.any(unknownArtists::contains) }.orEmpty()
    }

    internal fun sameRecordingVersion(song: OnlineSong, candidate: OnlineSong): Boolean =
        versionTags(song) == versionTags(candidate)

    private fun versionTags(song: OnlineSong): Set<Int> = versions.indices.filterTo(mutableSetOf()) {
        versions[it].containsMatchIn(Normalizer.normalize("${song.name} ${song.albumName}", Normalizer.Form.NFKC))
    }

    /**
     * 按标题/歌手在默认平台严格匹配真实在线条目（歌名/歌手/版本/时长全对才算）；
     * 本地歌曲联网取封面/歌词与下载标签补全共用，无匹配返回 null。
     */
    suspend fun matchOnline(context: Context, song: OnlineSong, background: Boolean = false, force: Boolean = false): OnlineSong? =
        findMatchedSongs(
            song = song,
            targetSource = LOCAL_MATCH_SOURCE,
            timeoutMs = ALT_SEARCH_REQUEST_TIMEOUT_MS,
            requireArtist = false,
            cacheNamespace = "local:",
            score = ::localMetadataScore,
            force = force,
        ) { keyword ->
            OnlineRepository.search(
                context, LOCAL_MATCH_SOURCE, keyword, limit = 25,
                background = background, timeoutMs = ALT_SEARCH_REQUEST_TIMEOUT_MS,
            ).list
        }.firstOrNull()


    /** 本地歌曲（source=local）没有平台 songmid：换出真实在线条目；在线歌曲原样返回。 */
    suspend fun localAsOnline(context: Context, song: OnlineSong, background: Boolean = false, force: Boolean = false): OnlineSong? =
        if (song.source != LOCAL_SOURCE) song
        else matchOnline(context, song, background, force)

    /**
     * 歌词：由内置目录模块提供（音源脚本只负责播放解析）。
     * 当前平台无词时按同曲匹配换平台再取内置歌词。
     */
    suspend fun lyric(context: Context, song: OnlineSong, background: Boolean = false, preferWordTimings: Boolean = false, force: Boolean = false): OnlineLyric? {
        val base = localAsOnline(context, song, background, force) ?: return null
        var fallback = attempt { OnlineRepository.lyric(context, base.source, base, background) }?.takeIf { it.hasLyrics }
        if (fallback != null && (!preferWordTimings || fallback.lxlyric.isNotBlank())) return fallback
        // 本地补词复用现有同曲/版本匹配和最多三个候选；没有逐字时仍保留首个普通结果。
        for (candidate in findAlternatives(context, base, background, force).take(3)) {
            if (candidate.source == base.source) continue
            val result = attempt { OnlineRepository.lyric(context, candidate.source, candidate, background) }
                ?.takeIf { it.hasLyrics } ?: continue
            if (!preferWordTimings || result.lxlyric.isNotBlank()) return result
            if (fallback == null) fallback = result
        }
        return fallback
    }

    suspend fun pic(context: Context, song: OnlineSong): String? {
        val base = localAsOnline(context, song) ?: return null
        base.img?.let { return it }
        // 封面同样由内置模块提供，脚本仅兜底
        OnlineRepository.pic(context, base.source, base)?.let { return it }
        return picFromScripts(context, base)
    }

    /** 列表封面快速通道：内置目录模块优先（HTTP 直取），脚本兜底。 */
    suspend fun picFast(context: Context, song: OnlineSong): String? {
        val base = localAsOnline(context, song) ?: return null
        base.img?.let { return it }
        OnlineRepository.pic(context, base.source, base, timeoutMs = 8_000, background = true)?.let { return it }
        return picFromScripts(context, base, 8_000)
    }

    private suspend fun picFromScripts(context: Context, song: OnlineSong, timeoutMs: Long = 12_000): String? =
        attempt {
            val scripts = LxScriptPool.scriptsFor(context, song.source, "pic", LxScriptPool.ScriptScope.ENABLED)
            for ((script, _) in scripts) {
                val info = JSONObject().put("musicInfo", song.raw)
                val data = LxScriptPool.resolve(context, script.id, song.source, "pic", info, timeoutMs)
                val url = when (data) {
                    is String -> data
                    is JSONObject -> data.optString("url")
                    else -> null
                }
                if (!url.isNullOrBlank() && url.startsWith("http")) return@attempt url
            }
            null
        }
}

internal class SingleFlight<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val lock: Any = Any(),
) {
    private var generation = 0L
    private class Pending<V>(
        val task: Deferred<V>,
        val cancelWhenUnobserved: Boolean,
        var waiters: Int = 0,
    )
    private val requests = mutableMapOf<K, Pending<V>>()

    fun currentGeneration(): Long = synchronized(lock) { generation }

    internal fun pending(key: K): Deferred<V>? = synchronized(lock) { requests[key]?.task }

    /** 原子失效并主动取消旧代次；普通waiter取消只在无人等待时撤销真正的解析。 */
    fun fence(): Long {
        val (nextGeneration, retired) = synchronized(lock) {
            generation += 1
            generation to requests.values.map { it.task }.also { requests.clear() }
        }
        retired.forEach { it.cancel(FlightFencedException()) }
        return nextGeneration
    }

    /** 按key撤销请求而不推进全局代次，供命名空间缓存清理使用。 */
    fun cancelWhere(predicate: (K) -> Boolean) {
        val retired = synchronized(lock) {
            requests.keys.filter(predicate).mapNotNull { requests.remove(it)?.task }
        }
        retired.forEach { it.cancel() }
    }

    /**
     * 单key共享任务。默认末等待者离开时取消；缓存刷新可选择后台继续。
     * restart可选择只撤销旧任务的提交资格、保留旧等待者，供强刷保持既有返回语义。
     */
    suspend fun run(
        key: K,
        generation: Long = currentGeneration(),
        restart: Boolean = false,
        cancelReplaced: Boolean = true,
        cancelWhenUnobserved: Boolean = true,
        cacheHit: (() -> V?)? = null,
        block: suspend () -> V,
    ): V {
        val pending = synchronized(lock) {
            if (generation != this.generation) throw FlightFencedException()
            cacheHit?.invoke()?.let { return it }
            if (restart) requests.remove(key)?.let { old ->
                if (cancelReplaced) old.task.cancel(FlightFencedException())
            }
            (requests[key] ?: run {
                lateinit var next: Pending<V>
                val task = scope.async(start = CoroutineStart.LAZY) { block() }
                next = Pending(task, cancelWhenUnobserved)
                requests[key] = next
                task.invokeOnCompletion {
                    synchronized(lock) { if (requests[key] === next) requests.remove(key) }
                }
                next
            }).also { it.waiters++; it.task.start() }
        }
        return try {
            pending.task.await()
        } finally {
            synchronized(lock) {
                pending.waiters--
                if (pending.waiters == 0 && pending.cancelWhenUnobserved && requests[key] === pending) {
                    requests.remove(key)
                    pending.task.cancel()
                }
            }
        }
    }

}

private class FlightFencedException : CancellationException("single-flight fenced")

internal suspend fun <I, O : Any> parallelMapWithinBudget(
    inputs: List<I>,
    budgetMs: Long,
    transform: suspend (I) -> O,
): List<O> {
    require(budgetMs > 0) { "budgetMs must be positive" }
    return supervisorScope {
        inputs.map { input ->
            async {
                try {
                    withTimeoutOrNull(budgetMs) { transform(input) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
        }.mapNotNull { request -> request.await() }
    }
}
