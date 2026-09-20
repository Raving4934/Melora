package com.leyu.melora.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalTagFiller
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.SourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放层唯一入口（在线版）。
 *
 * 队列交给 Media3 管理（保留通知栏上下首/系统媒体控制能力），实际流媒体地址通过
 * melora:// 虚拟 URI + ResolvingDataSource 在播放时按需解析（音源脚本 + 跨平台换源）。
 */
object PlaybackController {
    private const val TAG = "PlaybackController"
    private const val PREFS_PROGRESS = "melora-progress"

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null
    private var pendingPlayback: (() -> Unit)? = null
    private var playbackPreflight: Job? = null
    private var urlPrefetchJob: Job? = null
    private var urlPrefetchUid: String? = null
    private var confirmedAudio: Pair<String, String>? = null
    private var registryListenersAttached = false
    private var positionJob: Job? = null
    private val recentPlaybackTracker = RecentPlaybackTracker()
    private val rebufferRecovery = RebufferRecovery()
    private var recoveryJob: Job? = null
    private var recoveryGeneration = 0L
    private var queueLoadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _lyric = MutableStateFlow<PlayerLyric?>(null)
    val lyric: StateFlow<PlayerLyric?> = _lyric.asStateFlow()

    /**
     * 确保当前在线曲目的封面走统一解析链路并回写播放元数据。
     * 在途请求由 artworkJob 合并；每次解析的限流、singleflight、重试和脚本兜底由 CoverLoader 负责。
     */
    fun ensureCurrentArtwork() {
        val track = _state.value.current
        if (artworkUid != track?.uid) {
            artworkJob?.cancel()
            artworkJob = null
            artworkUid = track?.uid
        }
        if (track == null || !track.isOnline) return
        val context = appContext ?: return
        if (artworkJob?.isActive == true) return

        artworkJob = scope.launch {
            // 本地文件内嵌封面优先；即使在线条目已带平台封面，也应展示实际播放文件的封面。
            val localCover = withContext(Dispatchers.IO) {
                LocalMediaStore.matchTrack(track)?.let { LocalTagReader.bestCoverUri(context, it) }
            }
            if (localCover != null) {
                TrackRegistry.updateArtwork(track.uid, localCover)
                return@launch
            }
            if (track.artwork?.let(LocalTagReader::isUsableCoverUri) == true) return@launch
            recoverableOrNull {
                resolveAndUpdateArtwork(track) { song -> CoverLoader.resolve(context, song) }
            }
        }
    }

    private var lyricJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkUid: String? = null
    private val refreshAttempted = mutableSetOf<String>()
    private var lastProgressSaved = 0L
    private var lastSavedPosition = -1L
    private var lastTransitionIndex = -1
    // 最近一次已加载详情/歌词的曲目，publish 时据此检测曲目变化
    private var detailUid: String? = null
    private var consecutiveErrors = 0
    // 队列持久化指纹：避免同一队列反复写盘
    private var lastQueueFingerprint: String? = null
    private var sleepJob: Job? = null
    // 当前队列来源标识（卡片播放按钮据此显示播放/暂停）
    private var currentQueueId: String? = null
    private val _sleepRemaining = MutableStateFlow<Int?>(null)
    val sleepRemaining: StateFlow<Int?> = _sleepRemaining.asStateFlow()

    fun init(context: Context) {
        if (controllerFuture != null) return
        val application = context.applicationContext
        appContext = application
        if (!registryListenersAttached) {
            TrackRegistry.onResolved { scope.launch { publish() } }
            TrackRegistry.onArtwork { _, _ -> scope.launch { publish() } }
            registryListenersAttached = true
        }
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess {
                        controller = it
                        attach(it)
                        val pending = pendingPlayback
                        pendingPlayback = null
                        if (pending == null) {
                            restoreQueue(it)
                        } else {
                            pending()
                        }
                        publish()
                    }
                    .onFailure { error ->
                        controllerFuture = null
                        controller = null
                        pendingPlayback = null
                        _state.value = _state.value.copy(
                            ready = false,
                            pendingQueueId = null,
                            message = "播放服务连接失败，请重试",
                        )
                        Log.e(TAG, "MediaController 连接失败", error)
                    }
            },
            MoreExecutors.directExecutor(),
        )
        if (positionJob == null) {
            positionJob = scope.launch {
                while (true) {
                    refreshPosition()
                    delay(500)
                }
            }
        }
    }

    private fun attach(player: MediaController) {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    interruptRecovery()
                    AudioCacheStore.cancelPrefetch()
                    if (urlPrefetchUid != mediaItem?.mediaId) urlPrefetchJob?.cancel()
                    refreshAttempted.clear()
                    confirmedAudio = null
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                        MeloraSettings.autoClearPlayed.value
                    ) {
                        val active = controller
                        val previous = lastTransitionIndex
                        if (active != null && previous in 0 until active.mediaItemCount &&
                            active.currentMediaItemIndex > previous
                        ) {
                            active.removeMediaItem(previous)
                        }
                    }
                    lastTransitionIndex = controller?.currentMediaItemIndex ?: -1
                    publish()
                    saveQueue()
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) interruptRecovery()
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (recoveryJob != null && (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING)) {
                        interruptRecovery()
                    }
                    if (player.playbackState == Player.STATE_READY &&
                        (events.contains(Player.EVENT_TRACKS_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED))
                    ) confirmAudioQuality(player)
                    publish()
                    updateRecentPlayback(player)
                    if (player.playbackState == Player.STATE_READY) consecutiveErrors = 0
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        saveProgress(force = true)
                        saveQueue()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "播放错误: ${error.errorCodeName}", error)
                    handlePlayerError(error)
                }
            },
        )
        publish()
        updateRecentPlayback(player)
    }

    /** 只展示实际选中的音频轨，不误把未选中的第一个格式当成播放规格。 */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun readAudioSpec(player: Player): AudioSpecification? {
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?: return null
        val index = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return null
        val format = group.getTrackFormat(index)
        val bitDepth = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            else -> -1
        }
        return AudioSpecification(format.sampleMimeType, format.sampleRate, format.bitrate, bitDepth)
    }

    /** onEvents位于Media3状态批处理之后，避免切歌回调中把上一首格式写到新资源。 */
    private fun confirmAudioQuality(player: Player) {
        val uid = player.currentMediaItem?.mediaId ?: return
        val resolution = TrackRegistry.resolved(uid)
        val audioSpec = readAudioSpec(player)
        val resourceId = resolution?.resourceId
        if (resourceId in TrackRegistry.LOCAL_RESOURCE_IDS && audioSpec != null) {
            resolution?.localFile?.let { LocalMediaStore.recordAudioSpecification(it, audioSpec) }
            resolution?.downloadUri?.let { uri ->
                // 旧下载记录按本次真正打开的URI补齐，下载换新文件后旧音轨不能回写到新记录。
                scope.launch(Dispatchers.IO) { DownloadCenter.rememberAudioSpecification(uid, uri, audioSpec) }
            }
            return
        }
        val verified = audioSpec?.verifiedQuality
        if (resourceId != null && verified != null &&
            resourceId.startsWith("lx:") &&
            confirmedAudio != (resourceId to verified)
        ) {
            confirmedAudio = resourceId to verified
            SourceResolver.confirmQuality(resourceId, verified)
            appContext?.let { context ->
                scope.launch(Dispatchers.IO) {
                    recoverableOrNull { AudioCacheStore.recordObservedQuality(context, uid, resourceId, verified) }
                }
            }
        }
    }

    private fun publish() {
        val player = controller ?: return
        val queue = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            TrackRegistry.get(item.mediaId) ?: UiTrack(
                uid = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            )
        }
        val index = player.currentMediaItemIndex
        val hasItems = player.mediaItemCount > 0
        val currentTrack = queue.getOrNull(index)
        val resolution = currentTrack?.uid?.let(TrackRegistry::resolved)
        val audioSpec = readAudioSpec(player)
        val isPlayingIntent = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        _state.value = _state.value.copy(
            ready = true,
            current = currentTrack,
            queue = queue,
            currentIndex = if (index in queue.indices) index else -1,
            playing = isPlayingIntent && hasItems,
            buffering = hasItems && player.playbackState == Player.STATE_BUFFERING,
            // 在线曲目缓冲中且尚未拿到解析结果 = 正在解析音源
            resolving = currentTrack?.isOnline == true &&
                resolution == null && DownloadCenter.saved(currentTrack.uid) == null &&
                player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            mode = player.playMode(),
            speed = player.playbackParameters.speed,
            quality = resolution?.quality,
            resolvedPlatform = resolution?.platform,
            resolvedBy = resolution?.resourceId,
            audioSpec = audioSpec,
            queueId = currentQueueId.takeIf { hasItems },
        )
        // 曲目变化（含启动恢复队列、切歌、自动连播）即加载详情与歌词，不再只依赖过渡事件
        if (currentTrack?.uid != detailUid) {
            detailUid = currentTrack?.uid
            if (currentTrack != null && currentTrack.isOnline) {
                loadTrackDetails()
            } else {
                _lyric.value = null
            }
        }
    }

    private fun refreshPosition() {
        val player = controller ?: return
        val snapshot = _state.value
        if (!snapshot.ready) return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 } ?: snapshot.durationMs
        if (position != snapshot.positionMs || duration != snapshot.durationMs) {
            _state.value = snapshot.copy(positionMs = position, durationMs = duration)
        }
        saveProgress(force = false)
        updateRecentPlayback(player)
        if (recoveryJob != null && !MeloraSettings.autoSwitchSource.value) interruptRecovery()
        observeRebuffering(player)
    }

    private fun interruptRecovery() {
        recoveryGeneration++
        recoveryJob?.cancel()
        recoveryJob = null
        rebufferRecovery.interrupted()
    }

    private fun observeRebuffering(player: Player) {
        val uid = player.currentMediaItem?.mediaId
        val resolution = uid?.let(TrackRegistry::resolved)
        val networkResource = resolution?.fromCompleteCache != true &&
            resolution?.resourceId?.let { it.startsWith("lx:") } == true
        val wantsPlayback = player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        when (rebufferRecovery.update(uid, player.isPlaying, wantsPlayback,
            player.playbackState == Player.STATE_BUFFERING, networkResource,
            MeloraSettings.autoSwitchSource.value && player.isCurrentMediaItemSeekable, SystemClock.elapsedRealtime())) {
            RebufferRecovery.Action.NONE -> Unit
            RebufferRecovery.Action.YIELD_PREFETCH -> AudioCacheStore.cancelPrefetch()
            RebufferRecovery.Action.FIND_ALTERNATIVE -> {
                val context = appContext ?: return
                if (!NetworkState.isConnected(context)) return
                val item = player.currentMediaItem ?: return
                val song = TrackRegistry.get(item.mediaId)?.raw?.let(OnlineSong::from) ?: return
                val previousResource = resolution?.resourceId ?: return
                val quality = readAudioSpec(player)?.verifiedQuality ?: resolution.quality
                val preference = NetworkState.playQuality(context)
                val generation = recoveryGeneration
                rebufferRecovery.attemptStarted()
                recoveryJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val alternative = withContext(Dispatchers.IO) {
                            SourceResolver.resolve(context, song, quality, allowSwitch = true,
                                purpose = SourceResolver.Purpose.REBUFFER, excludedResources = setOf(previousResource))
                        }
                        if (generation != recoveryGeneration || controller !== player ||
                            !MeloraSettings.autoSwitchSource.value || !player.isCurrentMediaItemSeekable ||
                            NetworkState.playQuality(context) != preference || player.currentMediaItem != item ||
                            !player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
                            player.playbackState != Player.STATE_BUFFERING ||
                            TrackRegistry.resolved(item.mediaId)?.resourceId != previousResource) return@launch
                        SourceResolver.selectForPlayback(song, preference, alternative)
                        if (restartBufferedMediaItem(player, item)) {
                            _state.value = _state.value.copy(message = "当前音源较慢，已尝试同音质备用资源")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (generation == recoveryGeneration) {
                            _state.value = _state.value.copy(message = "当前音源较慢，可继续缓存或手动降低音质")
                        }
                    } finally {
                        if (generation == recoveryGeneration) recoveryJob = null
                    }
                }.also { it.start() }
            }
        }
    }

    private fun updateRecentPlayback(player: Player) {
        val track = player.currentMediaItem?.mediaId?.let(TrackRegistry::get)
        val song = OnlineSong.from(track?.raw)
        val shouldRecord = recentPlaybackTracker.update(
            uid = track?.uid.takeIf { song != null },
            isPlaying = player.isPlaying,
            isBookChapter = song?.isBookChapter == true,
            nowMs = SystemClock.elapsedRealtime(),
        )
        if (shouldRecord && song != null && track != null) {
            scope.launch(Dispatchers.IO) { UserLibrary.markPlayed(song) }
            appContext?.takeUnless { TrackRegistry.isLocalResource(track.uid) }?.let { context ->
                val quality = player.currentMediaItem?.localConfiguration?.uri
                    ?.getQueryParameter("q")
                    ?: NetworkState.playQuality(context)
                AudioCacheStore.prefetchCurrent(context, track.uid, song, quality)
            }
        }
    }

    private fun loadTrackDetails() {
        val snapshot = _state.value
        val track = snapshot.current
        // 封面与歌词是独立生命周期；歌词缓存命中也不能跳过封面补齐和下一曲预取。
        ensureCurrentArtwork()
        if (track != null) {
            appContext?.let { LocalTagFiller.consider(it, track) }
        }
        if (track == null || !track.isOnline) {
            lyricJob?.cancel()
            _lyric.value = null
            return
        }

        lyricJob?.cancel()
        val cachedLyric = LyricRepository.cached(track.uid)
        if (cachedLyric != null) {
            _lyric.value = cachedLyric
        } else {
            _lyric.value = null
            val context = appContext
            if (context != null) {
                lyricJob = scope.launch {
                    val lyric = recoverableOrNull { LyricRepository.load(context, track) }
                    if (_state.value.current?.uid == track.uid) _lyric.value = lyric
                }
            }
        }

        val context = appContext ?: return
        // 预取下一首歌词：自动连播时歌词秒现（听书连播同理）
        val next = snapshot.queue.getOrNull(snapshot.currentIndex + 1)
        if (next != null && next.isOnline) {
            scope.launch { LyricRepository.prefetch(context, next) }
        }
    }

    /** 统一的封面 metadata 回写入口；解析策略由调用方提供，使用 track.uid 保留原始曲目身份。 */
    internal suspend fun resolveAndUpdateArtwork(
        track: UiTrack,
        resolve: suspend (OnlineSong) -> String?,
    ): String? {
        val song = OnlineSong.from(track.raw) ?: return null
        return resolve(song)?.also { url ->
            TrackRegistry.updateArtwork(track.uid, url)
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        interruptRecovery()
        val player = controller ?: return
        // 已暂停时仍可能收到在途读取的错误；不能因重试/跳曲覆盖用户刚发出的暂停。
        if (!player.playWhenReady) {
            _state.value = _state.value.copy(message = "加载失败，点击播放重试")
            return
        }
        val snapshot = _state.value
        val track = snapshot.current ?: return
        var retryLocalFallback = false
        if (!track.isOnline || TrackRegistry.isLocalResource(track.uid)) {
            // 本地媒体索引可能过期（文件被移动/删除）：清理条目后回退网络解析；纯本地曲目直接提示
            val staleLocal = LocalMediaStore.matchTrack(track)
            val canFallback = track.isOnline &&
                track.source != com.leyu.melora.playback.local.LocalSong.SOURCE &&
                staleLocal != null &&
                DownloadCenter.saved(track.uid) == null
            if (!canFallback) {
                controller?.pause()
                _state.value = _state.value.copy(
                    message = if (staleLocal != null) "本地文件无法播放，请检查文件是否仍然存在" else "本地文件无法播放，请检查文件和下载目录权限",
                )
                return
            }
            LocalMediaStore.removeIds(setOf(staleLocal.id))
            retryLocalFallback = true
            TrackRegistry.clearResolved(track.uid)
        }
        consecutiveErrors++
        if (consecutiveErrors >= 3) {
            controller?.pause()
            _state.value = _state.value.copy(message = "多个播放链接解析失败，已停止播放")
            consecutiveErrors = 0
            return
        }
        val failed = TrackRegistry.resolved(track.uid)?.resourceId
        if ((failed != null || retryLocalFallback) && refreshAttempted.add(track.uid)) {
            if (failed != null) SourceResolver.rejectResource(track.uid, failed)
            TrackRegistry.clearResolved(track.uid)
            _state.value = snapshot.copy(message = "播放链接失效，正在尝试其它可用资源…")
            // 让唯一DataSource入口重解析，不再先手工解析一次再prepare第二次。
            player.prepare() // prepare保留当前播放意图，无需强行play。
            return
        }
        val reason = when {
            error.errorCodeName.contains("TIMEOUT", ignoreCase = true) -> "网络连接超时"
            error.errorCodeName.contains("NETWORK", ignoreCase = true) -> "网络连接失败"
            error.errorCodeName.contains("IO", ignoreCase = true) -> "音源无法访问"
            error.errorCodeName.contains("PARSING", ignoreCase = true) || error.errorCodeName.contains("DECODER", ignoreCase = true) -> "音频解码失败"
            else -> "音源不可用"
        }
        _state.value = _state.value.copy(message = "播放失败（$reason），已尝试跳过")
        next()
    }

    private fun buildItem(track: UiTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                if (MeloraSettings.showNotificationCover.value) {
                    playableArtworkUri(track.artwork)?.let { setArtworkUri(it.toUri()) }
                }
            }
            .build()
        // 队列只保存歌曲身份；真实打开时统一读取当前网络/偏好，不把旧音质冻结在队列里。
        val uri = if (track.isOnline) TrackRegistry.songUri(track.uid) else track.uid.toUri()
        return MediaItem.Builder()
            .setMediaId(track.uid)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun playQueueNow(tracks: List<UiTrack>, startIndex: Int, queueId: String?) {
        interruptRecovery()
        if (tracks.isEmpty()) return
        val player = controller
        if (player == null) {
            pendingPlayback = { playQueueNow(tracks, startIndex, queueId) }
            _state.value = _state.value.copy(pendingQueueId = queueId)
            return
        }
        currentQueueId = queueId
        _state.value = _state.value.copy(pendingQueueId = null, message = null)
        TrackRegistry.registerAll(tracks)
        val items = tracks.map(::buildItem)
        val index = startIndex.coerceIn(0, items.lastIndex)
        prefetchTrack(tracks[index])
        player.setMediaItems(items, index, C.TIME_UNSET)
        applyProgress(tracks[index])
        player.prepare()
        player.play()
        publish()
    }

    /** 单曲/整队列共享预热入口，保留本地优先与解析 single-flight。 */
    private fun prefetchTrack(track: UiTrack) {
        urlPrefetchJob?.cancel()
        urlPrefetchUid = track.uid
        val context = appContext ?: return
        if (!needsNetworkPrefetch(track)) return
        urlPrefetchJob = scope.launch(Dispatchers.IO) {
            val song = OnlineSong.from(track.raw) ?: return@launch
            recoverableOrNull {
                SourceResolver.resolve(
                    context, song, NetworkState.playQuality(context),
                    allowSwitch = MeloraSettings.autoSwitchSource.value,
                )
            }
        }
    }

    /**
     * 卡片队列唯一入口：缓存快照即播，过期后台单飞刷新，不改变当前队列/进度。
     * 无缓存时发布 pending，切换卡片取消旧等待者，网络结果不抢播。
     */
    fun <T : Any> requestQueue(
        context: Context,
        queueId: String,
        cacheKey: String,
        songs: (T) -> List<OnlineSong>,
        load: suspend () -> T,
    ) {
        val cached = OnlineCache.peek<T>(cacheKey)?.let(songs)
        if (!cached.isNullOrEmpty()) {
            playQueue(context, cached.map(UiTrack::fromOnline), 0, queueId)
            if (OnlineCache.get<T>(cacheKey, OnlineCache.CATALOG_TTL_MS) == null) {
                scope.launch(Dispatchers.IO) {
                    recoverableOrNull { OnlineCache.refresh(cacheKey, OnlineCache.CATALOG_TTL_MS, load) }
                }
            }
            return
        }
        if (_state.value.pendingQueueId == queueId) return
        beginPlayback(context, null)
        _state.value = _state.value.copy(pendingQueueId = queueId)
        // 先登记任务身份，再启动，避免同步缓存完成或旧请求取消回调抢占新请求。
        queueLoadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val tracks = songs(OnlineCache.refresh(cacheKey, OnlineCache.CATALOG_TTL_MS, load)).map(UiTrack::fromOnline)
                check(tracks.isNotEmpty()) { "暂无可播放内容" }
                if (queueLoadJob === coroutineContext[Job]) playQueueNow(tracks, 0, queueId)
            } catch (cancelled: CancellationException) {
                if (queueLoadJob === coroutineContext[Job]) {
                    _state.value = _state.value.copy(pendingQueueId = null)
                }
                throw cancelled
            } catch (error: Throwable) {
                if (queueLoadJob === coroutineContext[Job]) {
                    _state.value = _state.value.copy(
                        pendingQueueId = null,
                        message = error.message ?: "播放失败",
                    )
                }
            }
        }
        queueLoadJob?.start()
    }

    /** 仅用于明确的播放全部、随机播放或批量播放操作；单曲入口使用 playTrack。 */
    fun playQueue(
        context: Context,
        tracks: List<UiTrack>,
        startIndex: Int = 0,
        queueId: String? = null,
        container: UserLibrary.PlayContainer? = null,
    ) {
        if (tracks.isEmpty()) return
        requestPlayback(context, tracks[startIndex.coerceIn(tracks.indices)], container) {
            playQueueNow(tracks, startIndex, queueId)
        }
    }

    /** 实时应用「其他应用发声时自动暂停」：切换音频焦点接管，无需重启服务。 */
    fun applyAudioFocus(enabled: Boolean) {
        runCatching {
            controller?.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                enabled,
            )
        }
    }

    /** 无源只拦截新网络解析；离线资源先检查，失败时不改队列、不打断当前音乐。 */
    private fun requestPlayback(context: Context, track: UiTrack, container: UserLibrary.PlayContainer?, play: () -> Unit) {
        playbackPreflight?.cancel()
        playbackPreflight = null
        if (controller?.currentMediaItem?.mediaId == track.uid ||
            com.leyu.melora.playback.sdk.LxScriptPool.hasEnabledScripts(context) ||
            LocalMediaStore.matchTrack(track) != null || DownloadCenter.saved(track.uid) != null) {
            beginPlayback(context, container)
            play()
            return
        }
        playbackPreflight = scope.launch {
            val cached = withContext(Dispatchers.IO) {
                AudioCacheStore.cachedPlaybackResource(context, track.uid, NetworkState.playQuality(context)) != null
            }
            if (!cached && !com.leyu.melora.playback.sdk.LxScriptPool.hasEnabledScripts(context)) {
                postMessage(context, SourceResolver.NO_SOURCE_MESSAGE)
                return@launch
            }
            playbackPreflight = null
            beginPlayback(context, container)
            play()
        }
    }

    private fun beginPlayback(context: Context, container: UserLibrary.PlayContainer?) {
        appContext = context.applicationContext
        cancelPendingPlayback()
        _state.value = _state.value.copy(message = null)
        if (container != null) UserLibrary.markContainerPlayed(container)
        if (controllerFuture == null) init(context)
    }

    /** 保留现有队列；已有曲目直接跳转，否则仅在当前曲目后插入这一首。 */
    fun playTrack(context: Context, track: UiTrack, container: UserLibrary.PlayContainer? = null) {
        requestPlayback(context, track, container) { playTrackNow(track) }
    }

    private fun playTrackNow(track: UiTrack) {
        interruptRecovery()
        val player = controller
        if (player == null) {
            pendingPlayback = { playTrackNow(track) }
            return
        }
        // 冷启动直接点歌曲也保留上次队列，但不能先触发旧歌自动播放/解析。
        restoreQueue(player, autoPlay = false)
        val target = singleTrackQueueTarget(
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            player.currentMediaItemIndex,
            track.uid,
        )
        TrackRegistry.register(track)
        val changingTrack = player.currentMediaItem?.mediaId != track.uid
        if (changingTrack || player.playbackState == Player.STATE_IDLE) prefetchTrack(track)
        if (target.insert) {
            currentQueueId = null
            player.addMediaItem(target.index, buildItem(track))
        }
        if (player.currentMediaItemIndex != target.index || player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition(target.index)
            applyProgress(track)
        } else if (target.insert) {
            applyProgress(track)
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        saveQueue(force = true)
        publish()
    }

    fun addToQueue(context: Context, track: UiTrack) {
        appContext = context.applicationContext
        val player = controller ?: return
        TrackRegistry.register(track)
        if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId == track.uid }) {
            player.addMediaItem(buildItem(track))
            saveQueue(force = true)
        }
        _state.value = _state.value.copy(message = "已加入播放队列")
        publish()
    }

    fun addToQueueNext(context: Context, track: UiTrack) {
        appContext = context.applicationContext
        val player = controller ?: return
        TrackRegistry.register(track)
        val at = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(at, buildItem(track))
        saveQueue(force = true)
        _state.value = _state.value.copy(message = "已设为下一首播放")
        publish()
    }

    fun removeFromQueue(index: Int) {
        controller?.let { player ->
            if (index in 0 until player.mediaItemCount) {
                player.removeMediaItem(index)
                saveQueue(force = true)
                publish()
            }
        }
    }

    fun toggle() {
        interruptRecovery()
        val player = controller ?: return
        // 缓冲/解析/音频焦点暂时受限时isPlaying为false，但用户仍能暂停等待播放。
        if (player.playWhenReady) {
            player.pause()
            saveProgress(force = true)
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun next() {
        interruptRecovery()
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        player.seekToNextMediaItem()
        player.play()
    }

    fun previous() {
        interruptRecovery()
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        if (player.currentPosition > 3_000) {
            player.seekTo(0)
        } else {
            player.seekToPreviousMediaItem()
        }
        player.play()
    }

    fun seekTo(positionMs: Long) {
        interruptRecovery()
        controller?.seekTo(positionMs)
    }

    fun jumpTo(index: Int) {
        interruptRecovery()
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        saveQueue(force = true)
        player.play()
    }

    fun setSpeed(speed: Float) {
        val player = controller ?: return
        // Media3 默认通过 SonicAudioProcessor 做时间拉伸；显式锁定 pitch=1.0，
        // 防止未来其它播放逻辑修改过音调后，倍速出现尖锐或低沉变声。
        player.setPlaybackParameters(pitchCorrectedPlaybackParameters(speed))
        publish()
    }

    internal fun pitchCorrectedPlaybackParameters(speed: Float): PlaybackParameters =
        PlaybackParameters(speed.coerceIn(0.5f, 2f), 1f)

    fun cycleMode() {
        val player = controller ?: return
        when (player.playMode()) {
            PlayMode.List -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
            PlayMode.Single -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
            PlayMode.Shuffle -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }
        }
        publish()
    }

    private fun cancelPendingPlayback() {
        playbackPreflight?.cancel()
        playbackPreflight = null
        // 撤销播放意图必须独立于MediaController是否已经连接。
        _state.value = _state.value.copy(pendingQueueId = null)
        queueLoadJob?.cancel()
        queueLoadJob = null
        pendingPlayback = null
    }

    fun clearQueue() = endPlayback(stopEngine = false)

    fun stop() = endPlayback(stopEngine = true)

    private fun endPlayback(stopEngine: Boolean) {
        interruptRecovery()
        cancelPendingPlayback()
        AudioCacheStore.cancelPrefetch()
        if (stopEngine) saveProgress(force = true)
        clearSavedQueue()
        controller?.let { player ->
            if (stopEngine) player.stop() else player.pause()
            player.clearMediaItems()
        }
        currentQueueId = null
        _lyric.value = null
        publish()
    }

    fun consumeMessage() {
        if (_state.value.message != null) _state.value = _state.value.copy(message = null)
    }

    /** 睡眠定时：倒计时结束后暂停播放；可选等待当前曲目播完。 */
    fun setSleepTimer(minutes: Int, extendToFinishTrack: Boolean) {
        cancelSleepTimer()
        sleepJob = scope.launch {
            var remaining = minutes * 60
            while (remaining > 0) {
                _sleepRemaining.value = remaining
                delay(1_000)
                remaining--
            }
            _sleepRemaining.value = null
            val player = controller
            if (extendToFinishTrack && player != null && player.isPlaying) {
                val startUid = _state.value.current?.uid
                while (true) {
                    delay(500)
                    val snapshot = _state.value
                    if (!snapshot.playing) break
                    if (snapshot.current?.uid != startUid) {
                        player.pause()
                        break
                    }
                    if (snapshot.durationMs > 0 && snapshot.positionMs >= snapshot.durationMs - 1_500) {
                        player.pause()
                        break
                    }
                }
            } else {
                player?.pause()
            }
            sleepJob = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemaining.value = null
    }

    /** 供下载/歌单等外围操作向全局提示通道投递消息。 */
    fun postMessage(context: Context?, text: String) {
        _state.value = _state.value.copy(message = text)
    }

    private fun progressPrefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_PROGRESS, Context.MODE_PRIVATE)

    private fun saveProgress(force: Boolean) {
        if (!MeloraSettings.rememberProgress.value) return
        val snapshot = _state.value
        val track = snapshot.current ?: return
        if (!track.isOnline || snapshot.positionMs <= 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressSaved < 5_000) return
        if (!force && kotlin.math.abs(snapshot.positionMs - lastSavedPosition) < 4_000) return
        lastProgressSaved = now
        lastSavedPosition = snapshot.positionMs
        val persistAt = persistedProgressMs(snapshot.positionMs, snapshot.durationMs)
        progressPrefs()?.edit {
            if (persistAt <= 0L) remove(track.uid) else putLong(track.uid, persistAt)
        }
    }

    private const val PREFS_QUEUE = "melora-queue"
    private const val KEY_QUEUE = "queue"
    private const val KEY_QUEUE_INDEX = "index"
    private const val MAX_SAVED_QUEUE = 200

    private fun queuePrefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_QUEUE, Context.MODE_PRIVATE)

    private fun trackToJson(track: UiTrack): JSONObject = JSONObject().apply {
        put("uid", track.uid)
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("source", track.source)
        put("artwork", track.artwork)
        put("raw", track.raw)
    }

    private fun trackFromJson(obj: JSONObject): UiTrack? {
        val uid = obj.optString("uid").takeIf { it.isNotBlank() } ?: return null
        return UiTrack(
            uid = uid,
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            album = obj.optString("album"),
            source = obj.optString("source"),
            artwork = obj.optString("artwork").takeIf { it.isNotBlank() },
            raw = obj.optJSONObject("raw"),
        )
    }

    /** 持久化当前队列（超长队列只保存当前位置附近的窗口），供冷启动恢复 mini 播放条。 */
    private fun saveQueue(force: Boolean = false) {
        val player = controller ?: return
        val prefs = queuePrefs() ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val all = (0 until count).mapNotNull { i -> TrackRegistry.get(player.getMediaItemAt(i).mediaId) }
        if (all.size != count) return
        val fingerprint = playbackQueueFingerprint(all, index)
        if (!force && fingerprint == lastQueueFingerprint) return
        val window = if (count <= MAX_SAVED_QUEUE) {
            0 to count
        } else {
            val start = (index - 50).coerceIn(0, count - MAX_SAVED_QUEUE)
            start to (start + MAX_SAVED_QUEUE)
        }
        val array = JSONArray()
        all.subList(window.first, window.second).forEach { array.put(trackToJson(it)) }
        prefs.edit {
            putString(KEY_QUEUE, array.toString())
            putInt(KEY_QUEUE_INDEX, (index - window.first).coerceAtLeast(0))
        }
        lastQueueFingerprint = fingerprint
    }

    private fun clearSavedQueue() {
        queuePrefs()?.edit { clear() }
        lastQueueFingerprint = null
    }

    /** 冷启动恢复上次队列（不自动播放时保持暂停，仅让 mini 播放条出现）。 */
    private fun restoreQueue(player: MediaController, autoPlay: Boolean = MeloraSettings.autoPlayOnStart.value) {
        if (player.mediaItemCount > 0) return
        val prefs = queuePrefs() ?: return
        val json = prefs.getString(KEY_QUEUE, null) ?: return
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return
        val tracks = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::trackFromJson) }
        if (tracks.isEmpty()) return
        TrackRegistry.registerAll(tracks)
        val items = tracks.map(::buildItem)
        val index = prefs.getInt(KEY_QUEUE_INDEX, 0).coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, C.TIME_UNSET)
        applyProgress(tracks[index])
        if (autoPlay) {
            player.prepare()
            player.play()
        }
        lastQueueFingerprint = playbackQueueFingerprint(tracks, index)
        Log.d(TAG, "已恢复上次播放队列：${tracks.size} 首，当前第 ${index + 1} 首")
    }

    private fun applyProgress(track: UiTrack) {
        if (!MeloraSettings.rememberProgress.value) return
        val saved = progressPrefs()?.getLong(track.uid, 0L) ?: 0L
        val song = OnlineSong.from(track.raw)
        if (!shouldRestoreProgress(
                isBookChapter = song?.isBookChapter == true,
                durationMs = (song?.intervalSeconds ?: 0) * 1000L,
                savedMs = saved,
            )
        ) return
        controller?.seekTo(saved)
    }

    private fun Player.playMode(): PlayMode = when {
        shuffleModeEnabled -> PlayMode.Shuffle
        repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.Single
        else -> PlayMode.List
    }
}


internal class RecentPlaybackTracker(
    private val musicThresholdMs: Long = 5_000,
    private val bookThresholdMs: Long = 10_000,
) {
    private var activeUid: String? = null
    private var accumulatedMs = 0L
    private var lastPlayingAtMs: Long? = null
    private var recorded = false

    fun update(uid: String?, isPlaying: Boolean, isBookChapter: Boolean, nowMs: Long): Boolean {
        if (uid != activeUid) {
            activeUid = uid
            accumulatedMs = 0
            lastPlayingAtMs = null
            recorded = false
        }
        if (uid == null || !isPlaying) {
            lastPlayingAtMs = null
            return false
        }
        val previous = lastPlayingAtMs
        lastPlayingAtMs = nowMs
        if (recorded || previous == null) return false
        accumulatedMs += (nowMs - previous).coerceAtLeast(0)
        val threshold = if (isBookChapter) bookThresholdMs else musicThresholdMs
        if (accumulatedMs < threshold) return false
        recorded = true
        return true
    }
}

internal fun needsNetworkPrefetch(track: UiTrack): Boolean =
    track.isOnline &&
        track.source != com.leyu.melora.playback.local.LocalSong.SOURCE &&
        DownloadCenter.saved(track.uid) == null &&
        LocalMediaStore.matchTrack(track) == null

internal fun shouldRestoreProgress(
    isBookChapter: Boolean,
    durationMs: Long,
    savedMs: Long,
    nearEndMs: Long = 10_000L,
    longTrackMs: Long = 10 * 60 * 1000L,
): Boolean {
    if (savedMs <= 5_000L) return false
    if (durationMs > 0 && savedMs >= durationMs - nearEndMs) return false
    return isBookChapter || durationMs >= longTrackMs
}

internal fun persistedProgressMs(positionMs: Long, durationMs: Long, nearEndMs: Long = 2_000L): Long =
    if (durationMs > 0 && positionMs >= durationMs - nearEndMs) 0L else positionMs

internal fun playbackQueueFingerprint(tracks: List<UiTrack>, index: Int): String =
    buildString {
        append(index)
        tracks.forEach { track -> append('\u001f').append(track.uid) }
    }

/** 只使用当前歌曲的歌词；切歌时旧歌词尚未清空也不能写入新歌曲。 */
internal fun notificationLyricLine(
    enabled: Boolean,
    currentUid: String?,
    positionMs: Long,
    lyric: PlayerLyric?,
): String? {
    if (!enabled || currentUid == null || lyric?.uid != currentUid) return null
    return lyric.lines.lastOrNull { it.timeMs <= positionMs }?.text?.takeIf { it.isNotBlank() }
}

/** 单曲队列策略：按 uid 去重，不把来源列表隐式变成播放队列。 */
internal data class SingleTrackQueueTarget(val index: Int, val insert: Boolean)

internal fun singleTrackQueueTarget(queueUids: List<String>, currentIndex: Int, uid: String): SingleTrackQueueTarget {
    val existing = queueUids.indexOf(uid)
    return if (existing >= 0) SingleTrackQueueTarget(existing, insert = false)
    else SingleTrackQueueTarget((currentIndex + 1).coerceIn(0, queueUids.size), insert = true)
}
