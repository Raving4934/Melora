package com.leyu.melora.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal const val AUDIO_TRANSFER_BUFFER_BYTES = 64 * 1024

/**
 * 播放与下载共用的唯一音频缓存入口。
 *
 * MediaItem 仍沿用历史 `melora://song/<uid>?q=<preferred>` 逻辑 URI；真正落盘时改用
 * “曲目 uid + 实际音质 + 实际音源 + 媒体文件身份”物理 key，禁止跨编码或文件拼接分片。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object AudioCacheStore {
    private const val TAG = "AudioCacheStore"
    private const val AUDIO_DIR = "audio_cache"
    private const val META_PREFIX = ContentMetadata.KEY_CUSTOM_PREFIX + "melora."
    private const val META_SOURCE_URL = META_PREFIX + "source_url"
    private const val META_SOURCE_IDENTITY = META_PREFIX + "source_identity"
    private const val META_ACTUAL_QUALITY = META_PREFIX + "actual_quality"
    private const val META_VERIFIED_QUALITY = META_PREFIX + "verified_quality"
    private const val META_EXTENSION = META_PREFIX + "extension"
    private const val META_ALIAS_RESOURCE = META_PREFIX + "alias_resource"
    private const val CACHE_FRAGMENT_BYTES = 1024L * 1024L
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"

    private val initLock = Any()
    private val metadataLock = Any()
    private val prefetchLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var evictor: DynamicCacheEvictor? = null

    private var prefetchJob: Job? = null
    private var prefetchKey: String? = null

    /** ExoPlayer 使用：先完成虚拟地址解析，再让共享 CacheDataSource 按物理 key 读写。 */
    fun playbackDataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        val sharedCache = obtainCache(appContext)
        val cacheFactory = readThroughFactory(
            cache = sharedCache,
            upstream = mediaUpstreamFactory(appContext),
            blockOnCache = false,
        )
        return MeloraDataSourceFactory(appContext, cacheFactory)
    }

    /**
     * 下载入口。优先使用“该偏好音质上次解析出的完整实际资源”；未完整命中时才解析网络，
     * 然后继续复用同一实际音质、同一音源的部分 span。
     */
    internal suspend fun openForDownload(
        context: Context,
        song: OnlineSong,
        preferredQuality: String,
        allowSwitch: Boolean,
        excludedResources: Set<String> = emptySet(),
        resolveTimeoutMs: Long? = null,
    ): CachedAudioInput {
        val appContext = context.applicationContext
        val online = NetworkState.isConnected(appContext) && com.leyu.melora.playback.sdk.LxScriptPool.hasEnabledScripts(appContext)
        preferredResource(appContext, song.uid, preferredQuality, online, excludedResources)
            ?.takeIf { !online || SourceResolver.sameQualityTier(preferredQuality, it.actualQuality) }
            ?.let { resource -> openComplete(appContext, resource)?.let { return it } }

        val resolve = suspend {
            SourceResolver.resolve(
                context = appContext,
                song = song,
                preferredQuality = preferredQuality,
                allowSwitch = allowSwitch,
                purpose = SourceResolver.Purpose.DOWNLOAD,
                excludedResources = excludedResources,
            )
        }
        val resolved = if (resolveTimeoutMs == null) {
            resolve()
        } else {
            require(resolveTimeoutMs > 0L)
            withTimeoutOrNull(resolveTimeoutMs) { resolve() }
                ?: throw IllegalStateException("自动换源解析超时，已停止重试")
        }
        val resource = registerResolved(appContext, song.uid, preferredQuality, resolved)
        return openComplete(appContext, resource)
            ?: openReadThrough(appContext, resource)
    }

    /** 供播放解析器在联网前查询完整的新缓存；同时兼容旧版本的完整逻辑 key。 */
    internal fun cachedPlaybackResource(
        context: Context,
        uid: String,
        preferredQuality: String,
    ): AudioCacheResource? {
        val appContext = context.applicationContext
        val online = NetworkState.isConnected(appContext) && com.leyu.melora.playback.sdk.LxScriptPool.hasEnabledScripts(appContext)
        preferredResource(appContext, uid, preferredQuality, online)
            ?.takeIf { resource ->
                isComplete(appContext, resource.key) &&
                    (SourceResolver.sameQualityTier(preferredQuality, resource.actualQuality) || !online)
            }
            ?.let { return it }

        if (online) return null
        val legacyKey = audioCacheKey(uid, preferredQuality)
        return legacyPlaybackResource(appContext, legacyKey, preferredQuality)
    }

    /** 记录一次真实解析，并返回不会与其它音源/实际音质混写的物理资源。 */
    internal fun registerResolved(
        context: Context,
        uid: String,
        preferredQuality: String,
        resolved: SourceResolver.Resolved,
    ): AudioCacheResource {
        val appContext = context.applicationContext
        val sourceIdentity = resolved.song.uid
        val sharedCache = obtainCache(appContext)
        val quality = SourceResolver.observedQuality(resolved.resourceId) ?: resolved.quality
        val physicalKey = audioResourceKey(uid, quality, sourceIdentity, resolved.resourceId)
        // 规格确认后仍复用相同文件的旧key；不重命名/拼接不同音源或URL的分片。
        val existingKey = sharedCache.keys.firstOrNull { key ->
            key.startsWith("melora://audio/$uid?") && audioResourceId(key) == resolved.resourceId &&
                sharedCache.getContentMetadata(key).string(META_VERIFIED_QUALITY) == quality
        }
        val resource = AudioCacheResource(
            key = existingKey ?: physicalKey,
            sourceUrl = resolved.url,
            sourceIdentity = sourceIdentity,
            actualQuality = quality,
            extension = audioExtensionFromUrl(resolved.url),
        )
        synchronized(metadataLock) {
            runCatching {
                sharedCache.applyContentMetadataMutations(
                    resource.key,
                    resource.toMetadataMutations(),
                )
                sharedCache.applyContentMetadataMutations(
                    audioCacheKey(uid, preferredQuality),
                    ContentMetadataMutations()
                        .remove(META_VERIFIED_QUALITY)
                        .set(META_ALIAS_RESOURCE, resource.key)
                        .set(META_SOURCE_URL, resource.sourceUrl.orEmpty())
                        .set(META_SOURCE_IDENTITY, resource.sourceIdentity)
                        .set(META_ACTUAL_QUALITY, resource.actualQuality)
                        .apply { resource.extension?.let { set(META_EXTENSION, it) } },
                )
            }.onFailure { Log.d(TAG, "缓存元数据写入失败：${resource.key}", it) }
        }
        return resource
    }

    /** 普通歌曲形成有效播放记录后，在非计费网络后台补齐当前实际资源。 */
    fun prefetchCurrent(context: Context, uid: String, song: OnlineSong, preferredQuality: String) {
        val appContext = context.applicationContext
        if (song.isBookChapter || !NetworkState.isUnmetered(appContext)) return
        val logicalKey = audioCacheKey(uid, preferredQuality)

        synchronized(prefetchLock) {
            if (prefetchKey == logicalKey && prefetchJob?.isActive == true) return
            val previous = prefetchJob
            previous?.cancel()
            prefetchKey = logicalKey
            prefetchJob = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                try {
                    val resource = preferredResource(appContext, uid, preferredQuality, online = true) ?: run {
                        val resolved = SourceResolver.resolve(
                            context = appContext,
                            song = song,
                            preferredQuality = preferredQuality,
                            allowSwitch = MeloraSettings.autoSwitchSource.value,
                            purpose = SourceResolver.Purpose.CACHE_FILL,
                        )
                        registerResolved(appContext, uid, preferredQuality, resolved)
                    }
                    if (!isComplete(appContext, resource.key) && NetworkState.isUnmetered(appContext)) {
                        cacheWhole(appContext, resource)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // 智能补齐属于体验优化，失败不得影响当前播放。
                    Log.d(TAG, "后台补齐失败：$uid", error)
                }
            }.also { it.start() }
        }
    }

    /** 切歌、停止或清空队列时立即停止上一首的后台补齐。 */
    fun cancelPrefetch() {
        synchronized(prefetchLock) {
            prefetchJob?.cancel()
            // 保留已取消任务的引用；下一首会先 join，确认旧 CacheWriter 完全退出。
            prefetchKey = null
        }
    }

    /** 设置变化后按当前容量上限在 IO 线程收缩。 */
    fun trimNow() {
        scope.launch { evictor?.trimNow() }
    }

    /** 等待后台补齐完全退出后安全清空共享缓存。 */
    suspend fun clearAll(context: Context) {
        val appContext = context.applicationContext
        val sharedCache = obtainCache(appContext)
        val jobs = synchronized(prefetchLock) {
            listOfNotNull(prefetchJob).also {
                prefetchJob?.cancel()
                prefetchJob = null
                prefetchKey = null
            }
        }
        jobs.joinAll()
        // 直接传已取得的实例，不依赖异步attach是否已完成。
        checkNotNull(evictor).clearAll(sharedCache)
    }

    /** 播放解析后的真实 URL 与物理 key。 */
    internal fun applyToDataSpec(resource: AudioCacheResource, dataSpec: DataSpec): DataSpec = dataSpec.buildUpon()
        .setUri(resource.sourceUrl?.toUri() ?: "cache://melora/audio".toUri())
        .setKey(resource.key)
        .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
        .build()

    /** 下载完成后用真实文件头识别结果回写扩展名，供后续零网络导出复用。 */
    internal fun rememberExtension(context: Context, key: String, extension: String) {
        val sharedCache = obtainCache(context.applicationContext)
        synchronized(metadataLock) {
            runCatching {
                sharedCache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations().set(META_EXTENSION, extension),
                )
            }.onFailure { Log.d(TAG, "缓存格式写入失败：$key", it) }
        }
    }

    /** 首次识别实际音轨后落盘；只改元信息，物理文件/片段key保持稳定。 */
    internal fun recordObservedQuality(context: Context, uid: String, resourceId: String, quality: String) {
        val sharedCache = obtainCache(context)
        synchronized(metadataLock) {
            sharedCache.keys.filter { key ->
                (key.startsWith("melora://audio/$uid?") && audioResourceId(key) == resourceId) ||
                    (key.startsWith("melora://song/$uid?") &&
                        audioResourceId(sharedCache.getContentMetadata(key).string(META_ALIAS_RESOURCE).orEmpty()) == resourceId)
            }.forEach { key ->
                sharedCache.applyContentMetadataMutations(key, ContentMetadataMutations()
                    .set(META_VERIFIED_QUALITY, quality).set(META_ACTUAL_QUALITY, quality))
            }
        }
    }

    private fun preferredResource(
        context: Context,
        uid: String,
        preferredQuality: String,
        online: Boolean,
        excludedResources: Set<String> = emptySet(),
    ): AudioCacheResource? {
        val sharedCache = obtainCache(context)
        val keys = sharedCache.keys.filter { it.startsWith("melora://audio/$uid?") }
        // 同曲既有资源的实测规格先恢复到解析层；切换偏好/进程重启也不重新相信源的错标。
        keys.forEach { key ->
            val id = audioResourceId(key)
            val verified = sharedCache.getContentMetadata(key).string(META_VERIFIED_QUALITY)
            if (id != null && verified != null) SourceResolver.confirmQuality(id, verified)
        }
        val alias = sharedCache.getContentMetadata(audioCacheKey(uid, preferredQuality)).string(META_ALIAS_RESOURCE)
        for (key in (listOfNotNull(alias) + keys).distinct()) {
            val metadata = sharedCache.getContentMetadata(key)
            val resourceId = audioResourceId(key)
            if (resourceId != null && resourceId in excludedResources) continue
            if (SourceResolver.isRejected(uid, resourceId)) continue
            val actualQuality = SourceResolver.observedQuality(resourceId) ?: metadata.string(META_ACTUAL_QUALITY) ?: continue
            val sourceIdentity = metadata.string(META_SOURCE_IDENTITY) ?: continue
            if (online && !SourceResolver.sameQualityTier(preferredQuality, actualQuality)) continue
            val complete = isComplete(context, key)
            val resource = AudioCacheResource(key, metadata.string(META_SOURCE_URL), sourceIdentity,
                actualQuality, metadata.string(META_EXTENSION))
            if (complete) return resource
        }
        return null
    }

    private fun legacyPlaybackResource(
        context: Context,
        legacyKey: String,
        preferredQuality: String,
    ): AudioCacheResource? {
        if (!isComplete(context, legacyKey)) return null
        val metadata = obtainCache(context).getContentMetadata(legacyKey)
        return AudioCacheResource(
            key = legacyKey,
            sourceUrl = metadata.sourceUrl(),
            sourceIdentity = "legacy",
            actualQuality = preferredQuality,
            extension = metadata.string(META_EXTENSION),
        )
    }

    private fun openComplete(context: Context, resource: AudioCacheResource): CachedAudioInput? {
        val sharedCache = obtainCache(context)
        val metadata = sharedCache.getContentMetadata(resource.key)
        val length = ContentMetadata.getContentLength(metadata)
        if (length <= 0L || !sharedCache.isCached(resource.key, 0L, length)) return null
        val complete = resource.withMetadata(metadata)
        val dataSpec = DataSpec.Builder()
            .setUri(complete.sourceUrl?.toUri() ?: "cache://melora/audio".toUri())
            .setKey(complete.key)
            .setLength(length)
            .build()
        val dataSource = CacheDataSource.Factory()
            .setCache(sharedCache)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .createDataSource()
        return runCatching {
            dataSource.openAsInput(dataSpec, complete, completeCacheHit = true)
        }.getOrElse {
            runCatching { dataSource.close() }
            null
        }
    }

    private fun openReadThrough(context: Context, resource: AudioCacheResource): CachedAudioInput {
        val sourceUrl = requireNotNull(resource.sourceUrl) { "音频地址为空" }
        val sharedCache = obtainCache(context)
        val dataSpec = DataSpec.Builder()
            .setUri(sourceUrl)
            .setKey(resource.key)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .build()
        return readThroughFactory(sharedCache, mediaUpstreamFactory(context, tagDownloadFailures = true))
            .createDataSource()
            .openAsInput(dataSpec, resource, completeCacheHit = false)
    }

    private suspend fun cacheWhole(context: Context, resource: AudioCacheResource) {
        val sourceUrl = requireNotNull(resource.sourceUrl)
        val sharedCache = obtainCache(context)
        val dataSpec = DataSpec.Builder()
            .setUri(sourceUrl)
            .setKey(resource.key)
            .setFlags(
                DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or
                    DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED,
            )
            .build()
        val dataSource = readThroughFactory(sharedCache, mediaUpstreamFactory(context))
            .createDataSourceForDownloading()
        lateinit var writer: CacheWriter
        writer = CacheWriter(
            dataSource,
            dataSpec,
            null,
            CacheWriter.ProgressListener { _, _, _ ->
                if (!NetworkState.isUnmetered(context)) writer.cancel()
            },
        )
        val networkManager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                writer.cancel()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    writer.cancel()
                }
            }
        }
        runCatching { networkManager?.registerDefaultNetworkCallback(callback) }
        try {
            if (!NetworkState.isUnmetered(context)) return
            runInterruptible { writer.cache() }
        } finally {
            writer.cancel()
            runCatching { networkManager?.unregisterNetworkCallback(callback) }
        }
    }

    private fun isComplete(context: Context, key: String): Boolean {
        val sharedCache = obtainCache(context)
        val length = ContentMetadata.getContentLength(sharedCache.getContentMetadata(key))
        return length > 0L && sharedCache.isCached(key, 0L, length)
    }

    private fun obtainCache(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(initLock) {
            cache?.let { return it }
            val dynamicEvictor = DynamicCacheEvictor {
                MeloraSettings.maxCacheMb.value.toLong() * 1024L * 1024L
            }
            val created = SimpleCache(
                File(context.cacheDir, AUDIO_DIR),
                dynamicEvictor,
                StandaloneDatabaseProvider(context),
            )
            evictor = dynamicEvictor
            cache = created
            // 存量缓存可能达到数 GB；LRU 账本重建禁止阻塞 PlaybackService.onCreate 主线程。
            scope.launch { dynamicEvictor.attach(created) }
            return created
        }
    }

    private fun readThroughFactory(
        cache: SimpleCache,
        upstream: DataSource.Factory,
        blockOnCache: Boolean = true,
    ): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheWriteDataSinkFactory(
            CacheDataSink.Factory()
                .setCache(cache)
                .setFragmentSize(CACHE_FRAGMENT_BYTES)
                .setBufferSize(AUDIO_TRANSFER_BUFFER_BYTES),
        )
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(cacheDataSourceFlags(blockOnCache))

    private fun mediaUpstreamFactory(
        context: Context,
        tagDownloadFailures: Boolean = false,
    ): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        // 所有媒体网络读取走同一个边界：完整缓存被清理/淘汰后也不能绕过脚本使用历史直链。
        return DefaultDataSource.Factory(context, DataSource.Factory {
            val upstream = http.createDataSource()
            object : DataSource by upstream {
                private var resourceId: String? = null

                override fun open(dataSpec: DataSpec): Long {
                    if (!com.leyu.melora.playback.sdk.LxScriptPool.hasEnabledScripts(context)) {
                        throw java.io.IOException(SourceResolver.NO_SOURCE_MESSAGE)
                    }
                    val id = audioResourceId(dataSpec.key.orEmpty())
                    if (id == null || !id.startsWith("lx:")) {
                        throw java.io.IOException("缓存资源已失效，请重新播放以通过当前音源解析")
                    }
                    resourceId = id
                    if (!tagDownloadFailures) return upstream.open(dataSpec)
                    return try { upstream.open(dataSpec) }
                    catch (failure: IOException) { throw DownloadHttpTransferFailure(id, failure) }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!tagDownloadFailures) return upstream.read(buffer, offset, length)
                    return try { upstream.read(buffer, offset, length) }
                    catch (failure: IOException) {
                        throw DownloadHttpTransferFailure(requireNotNull(resourceId), failure)
                    }
                }
            }
        })
    }

    private fun DataSource.openAsInput(
        dataSpec: DataSpec,
        resource: AudioCacheResource,
        completeCacheHit: Boolean,
    ): CachedAudioInput = try {
        val length = open(dataSpec)
        val contentType = responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
        CachedAudioInput(
            stream = OpenedDataSourceInputStream(this),
            resourceKey = resource.key,
            sourceUrl = resource.sourceUrl,
            sourceIdentity = resource.sourceIdentity,
            actualQuality = resource.actualQuality,
            contentType = contentType,
            extension = resource.extension ?: audioExtensionFromContentType(contentType),
            contentLength = length.takeIf { it > 0L },
            completeCacheHit = completeCacheHit,
        )
    } catch (error: Throwable) {
        runCatching { close() }
        throw error
    }

    private fun AudioCacheResource.toMetadataMutations(): ContentMetadataMutations =
        ContentMetadataMutations()
            .set(META_SOURCE_URL, sourceUrl.orEmpty())
            .set(META_SOURCE_IDENTITY, sourceIdentity)
            .set(META_ACTUAL_QUALITY, actualQuality)
            .apply { extension?.let { set(META_EXTENSION, it) } }

    private fun AudioCacheResource.withMetadata(metadata: ContentMetadata): AudioCacheResource = copy(
        sourceUrl = metadata.string(META_SOURCE_URL) ?: sourceUrl ?: metadata.sourceUrl(),
        sourceIdentity = metadata.string(META_SOURCE_IDENTITY) ?: sourceIdentity,
        actualQuality = metadata.string(META_ACTUAL_QUALITY) ?: actualQuality,
        extension = metadata.string(META_EXTENSION) ?: extension,
    )

    private fun ContentMetadata.string(key: String): String? =
        get(key, "")?.takeIf { it.isNotBlank() }

    private fun ContentMetadata.sourceUrl(): String? =
        string(META_SOURCE_URL) ?: ContentMetadata.getRedirectedUri(this)?.toString()
}

internal data class AudioCacheResource(
    val key: String,
    val sourceUrl: String?,
    val sourceIdentity: String,
    val actualQuality: String,
    val extension: String?,
)

internal data class CachedAudioInput(
    val stream: InputStream,
    val resourceKey: String,
    val sourceUrl: String?,
    val sourceIdentity: String,
    val actualQuality: String,
    val contentType: String?,
    val extension: String?,
    val contentLength: Long?,
    val completeCacheHit: Boolean,
)

/** 仅包裹 HTTP upstream 的 open/read IOException；缓存 sink 与目标存储故障不会获得此标记。 */
internal class DownloadHttpTransferFailure(
    val resourceId: String,
    cause: IOException,
) : IOException(cause.message ?: "HTTP 音频传输失败", cause)

@androidx.annotation.OptIn(UnstableApi::class)
private class OpenedDataSourceInputStream(private val dataSource: DataSource) : InputStream() {
    private val singleByte = ByteArray(1)

    override fun read(): Int {
        val read = read(singleByte, 0, 1)
        return if (read == -1) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        dataSource.read(buffer, offset, length)

    override fun close() {
        dataSource.close()
    }
}

/** 前台播放不能等待后台 CacheWriter 的 hole lock；下载/补齐则必须阻塞以复用已有分片。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun cacheDataSourceFlags(blockOnCache: Boolean): Int =
    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
        if (blockOnCache) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0

/** 历史兼容的逻辑播放 URI。 */
internal fun audioCacheKey(uid: String, quality: String): String =
    "melora://song/$uid?q=$quality"

/** 实际落盘 key：即使同一歌曲/档位，编码或上游文件不同也绝不混写。 */
internal fun audioResourceKey(uid: String, actualQuality: String, sourceIdentity: String, resourceId: String): String {
    val encodedSource = URLEncoder.encode(sourceIdentity, Charsets.UTF_8.name())
    val encodedResource = URLEncoder.encode(resourceId, Charsets.UTF_8.name())
    return "melora://audio/$uid?q=$actualQuality&src=$encodedSource&res=$encodedResource"
}

/** 复用既有 key 中的解析器身份，不改磁盘结构；旧缓存缺失身份时保持未知。 */
internal fun audioResourceId(key: String): String? =
    key.substringAfter("&res=", "").substringBefore('&').takeIf { it.isNotBlank() }?.let { encoded ->
        runCatching { java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
    }

internal fun audioExtensionFromUrl(url: String?): String? {
    val path = url?.substringBefore('?')?.lowercase() ?: return null
    return listOf(".flac", ".m4a", ".wav", ".ape", ".ogg", ".aac", ".mp3")
        .firstOrNull(path::endsWith)
}

internal fun audioExtensionFromContentType(contentType: String?): String? = when (contentType?.lowercase()) {
    "audio/flac", "audio/x-flac" -> ".flac"
    "audio/mp4", "audio/x-m4a" -> ".m4a"
    "audio/aac", "audio/x-aac" -> ".aac"
    "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
    "audio/ape", "audio/x-ape", "audio/monkeys-audio" -> ".ape"
    "audio/ogg", "application/ogg" -> ".ogg"
    "audio/mpeg", "audio/mp3" -> ".mp3"
    else -> null
}

/** 只有文件头、响应类型和 URL 都无法识别时，才按实际音质做末级兜底。 */
internal fun audioExtensionForQuality(quality: String): String = when (quality.lowercase()) {
    "hires", "flac24bit", "flac" -> ".flac"
    "ape" -> ".ape"
    "wav" -> ".wav"
    else -> ".mp3"
}
