package com.leyu.melora.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import com.leyu.melora.playback.local.LocalMediaIoCoordinator
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/** 曲目注册表：MediaItem 的 melora:// 虚拟 URI 与真实曲目信息之间的桥梁。 */
object TrackRegistry {
    private val tracks = ConcurrentHashMap<String, UiTrack>()
    data class Resolution(
        val quality: String,
        val resourceId: String?,
        val requestedQuality: String? = null,
        val platform: String? = null,
        val fromCompleteCache: Boolean = false,
        val localFile: LocalSong? = null,
        val downloadUri: String? = null,
    )
    // 音质与解析器必须同一次更新，缓存命中/重解析不能留下另一条链路的旧来源。
    private val resolutions = ConcurrentHashMap<String, Resolution>()
    private val resolvedListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val artworkListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    fun register(track: UiTrack) {
        tracks[track.uid] = track
    }

    fun registerAll(list: List<UiTrack>) {
        list.forEach { register(it) }
    }

    fun get(uid: String): UiTrack? = tracks[uid]

    fun clear() {
        tracks.clear()
        resolutions.clear()
    }

    fun resolved(uid: String): Resolution? = resolutions[uid]

    /** 本地资源（下载文件或本地媒体文件）：播放层据此跳过网络预取/重试。 */
    fun isLocalResource(uid: String): Boolean = resolutions[uid]?.resourceId in LOCAL_RESOURCE_IDS

    fun clearResolved(uid: String) {
        if (resolutions.remove(uid) != null) resolvedListeners.forEach { it(uid) }
    }

    fun onResolved(listener: (String) -> Unit) {
        resolvedListeners.add(listener)
    }

    fun removeResolvedListener(listener: (String) -> Unit) {
        resolvedListeners.remove(listener)
    }

    fun onArtwork(listener: (String, String) -> Unit) {
        artworkListeners.add(listener)
    }

    fun removeArtworkListener(listener: (String, String) -> Unit) {
        artworkListeners.remove(listener)
    }

    fun updateArtwork(uid: String, url: String) {
        val track = tracks[uid] ?: return
        if (track.artwork == url) return
        tracks[uid] = track.copy(artwork = url)
        artworkListeners.forEach { it(uid, url) }
    }

    fun notifyResolved(uid: String, quality: String, resourceId: String?, requestedQuality: String? = null, platform: String? = null, fromCompleteCache: Boolean = false, localFile: LocalSong? = null, downloadUri: String? = null) {
        resolutions[uid] = Resolution(quality, resourceId, requestedQuality, platform, fromCompleteCache, localFile, downloadUri)
        resolvedListeners.forEach { it(uid) }
    }

    fun songUri(uid: String): Uri = "melora://song/$uid".toUri()

    val LOCAL_RESOURCE_IDS = setOf("local", "localmedia")
}

/**
 * 播放数据源工厂：把 melora:// 虚拟地址在打开时解析为真实流媒体地址。
 * 运行于 ExoPlayer 加载线程，脚本解析与跨平台换源在 SourceResolver 内完成。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MeloraDataSourceFactory(context: Context, base: DataSource.Factory) : DataSource.Factory {
    private val appContext = context.applicationContext
    private val baseFactory = base

    override fun createDataSource(): DataSource = ResolvingDataSource(
        // 本地 file/content 读租约覆盖 open→close；http 仍直接走网络缓存链路。
        LocalMediaIoCoordinator.wrap(
            DefaultDataSource(appContext, baseFactory.createDataSource()),
            appContext,
        ),
    ) { dataSpec ->
        val uri = dataSpec.uri
        if (uri.scheme != "melora") return@ResolvingDataSource dataSpec
        val uid = uri.pathSegments.lastOrNull() ?: throw IOException("无效曲目地址")
        val track = TrackRegistry.get(uid) ?: throw IOException("曲目信息缺失")

        // 纯本地曲目由 ID 明确指定，不能因同名/同 UID 下载记录或网络解析跨版本。
        if (track.source == LocalSong.SOURCE) {
            val local = LocalMediaStore.matchTrack(track)
            val localUri = local?.uri ?: track.raw?.optString("localUri")?.takeIf(String::isNotBlank)
                ?: throw FileNotFoundException("本地文件不存在或索引已失效")
            TrackRegistry.notifyResolved(uid, local?.playbackQuality ?: "local", "localmedia", localFile = local)
            return@ResolvingDataSource dataSpec.buildUpon().setUri(localUri.toUri()).setKey(null).build()
        }

        val savedDownload = DownloadCenter.saved(uid)
        val localMatch = LocalMediaStore.matchTrack(track)
        // 没有已保存下载时保持原有本地优先；查询只读内存索引，不扫描文件、不读取标签。
        if (savedDownload == null) {
            localMatch?.let { local ->
                TrackRegistry.notifyResolved(uid, local.playbackQuality, "localmedia", localFile = local)
                return@ResolvingDataSource dataSpec.buildUpon().setUri(local.uri.toUri()).setKey(null).build()
            }
        }
        // 下载记录不再无条件压过本地文件：只有下载规格已知且本地规格明确更高才切本地。
        if (savedDownload != null && localMatch != null &&
            savedDownload.audioSpec?.let { LocalMediaStore.isHigherQuality(localMatch, it) } == true
        ) {
            TrackRegistry.notifyResolved(uid, localMatch.playbackQuality, "localmedia", localFile = localMatch)
            return@ResolvingDataSource dataSpec.buildUpon().setUri(localMatch.uri.toUri()).setKey(null).build()
        }
        if (savedDownload != null) {
            // 读取失败也必须保留原地址，播放错误处理才能区分永久删除与目录暂时失权。
            TrackRegistry.notifyResolved(uid, "local", "local", downloadUri = savedDownload.savedUri)
            // 不在加载线程清除记录或直接换源，由统一错误处理在确认文件状态后接管。
            val local = Downloader.downloadedUri(appContext, uid) ?: run {
                DownloadCenter.failed(uid, "本地文件不存在或目录权限已失效")
                throw FileNotFoundException("本地文件不存在或目录权限已失效，请检查下载目录")
            }
            TrackRegistry.notifyResolved(uid, "local", "local", localFile = LocalMediaStore.findByUri(local.toString()), downloadUri = local.toString())
            return@ResolvingDataSource dataSpec.buildUpon().setUri(local).setKey(null).build()
        }
        val song = OnlineSong.from(track.raw) ?: throw IOException("曲目信息缺失")
        // 兼容旧队列的q参数，但不再让它覆盖用户当前设置。
        val preferredQuality = NetworkState.playQuality(appContext)

        AudioCacheStore.cachedPlaybackResource(appContext, song, preferredQuality)?.let { cached ->
            TrackRegistry.notifyResolved(uid, cached.actualQuality, audioResourceId(cached.key), preferredQuality, cached.sourceIdentity.substringBefore('_'), fromCompleteCache = true)
            return@ResolvingDataSource AudioCacheStore.applyToDataSpec(cached, dataSpec)
        }

        // 确认本次未命中缓存后再撤销历史结果；不在切歌时清空已预加载的来源。
        // 先通知 UI 进入解析态，慢源等待期间不能继续显示上次的“缓存”。
        TrackRegistry.clearResolved(uid)
        val resolved = runBlocking {
            SourceResolver.resolve(
                context = appContext,
                song = song,
                preferredQuality = preferredQuality,
                allowSwitch = MeloraSettings.autoSwitchSource.value,
            )
        }
        TrackRegistry.notifyResolved(uid, resolved.quality, resolved.resourceId, preferredQuality, resolved.song.source)
        val resource = AudioCacheStore.registerResolved(appContext, uid, preferredQuality, resolved)
        AudioCacheStore.applyToDataSpec(resource, dataSpec)
    }
}
