package com.leyu.melora.playback

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalFilePresence
import com.leyu.melora.playback.local.localFilePresence
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
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlinePlaylist
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(disconnected: MediaController) {
            if (controller !== disconnected) return
            controller = null
            controllerFuture = null
            bookQueue?.stop()
            bookQueue = null
            interruptRecovery()
            cancelPendingPlayback()
            lyricJob?.cancel()
            artworkJob?.cancel()
            detailUid = null
            _lyric.value = null
            // 断连不等于用户清空：保留磁盘队列供下次连接恢复，但不能再操作失效控制器。
            val previous = _state.value
            _state.value = PlayerUiState(mode = previous.mode, speed = previous.speed,
                message = if (previous.current != null) "播放服务已断开，请重新选择歌曲" else previous.message)
        }
    }
    private var pendingPlayback: PendingPlaybackSelection? = null
    private var playbackPreflight: Job? = null
    private var urlPrefetchJob: Job? = null
    private var urlPrefetchUid: String? = null
    private var confirmedAudio: Pair<String, String>? = null
    private var registryListenersAttached = false
    private var positionJob: Job? = null
    private var localQueueCheck: Job? = null
    private var localQueueObserver: ContentObserver? = null
    private var editingQueue = false
    private val recentPlaybackTracker = RecentPlaybackTracker()
    private val rebufferRecovery = RebufferRecovery()
    private var recoveryJob: Job? = null
    private var recoveryGeneration = 0L
    private var queueLoadJob: Job? = null
    private var bookQueue: BookPlaybackQueue? = null
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
        if (localQueueObserver == null) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { checkLocalQueue(application) }
            }
            runCatching {
                application.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
                localQueueObserver = observer
            }
        }
        if (!registryListenersAttached) {
            TrackRegistry.onResolved { scope.launch { publish() } }
            TrackRegistry.onArtwork { _, _ -> scope.launch { publish() } }
            registryListenersAttached = true
        }
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).setListener(controllerListener).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess {
                        controller = it
                        attach(it)
                        val pending = pendingPlayback
                        pendingPlayback = null
                        when {
                            pending == null -> restoreQueue(it)
                            pending.tracks.isEmpty() -> restoreQueue(it, autoPlay = false)
                            pending.insertSingle -> playTrackNow(pending.tracks.single())
                            else -> playQueueNow(pending.tracks, pending.index, pending.queueId)
                        }
                        publish()
                        checkLocalQueue(application)
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
        bookQueue = BookPlaybackQueue(
            player, scope, TrackRegistry::get, ::buildItem, KwBookApi::album,
            changed = { saveQueue(force = true); publish() },
            reportError = { _state.value = _state.value.copy(message = it) },
        )
        // 以当前控制器为边界维护上一首身份，避免旧 MediaController 的索引泄漏到新控制器。
        var previousMediaId = player.currentMediaItem?.mediaId
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (controller !== player || !player.isConnected) return
                    interruptRecovery()
                    AudioCacheStore.cancelPrefetch()
                    if (urlPrefetchUid != mediaItem?.mediaId) urlPrefetchJob?.cancel()
                    refreshAttempted.clear()
                    confirmedAudio = null
                    val currentMediaId = mediaItem?.mediaId
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                        MeloraSettings.autoClearPlayed.value &&
                        previousMediaId != null && previousMediaId != currentMediaId
                    ) {
                        val previousIndex = (0 until player.mediaItemCount).firstOrNull { index ->
                            player.getMediaItemAt(index).mediaId == previousMediaId
                        }
                        if (previousIndex != null) player.removeMediaItem(previousIndex)
                    }
                    previousMediaId = currentMediaId
                    publish()
                    saveQueue()
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) interruptRecovery()
                }

                override fun onEvents(eventsPlayer: Player, events: Player.Events) {
                    if (controller !== player || !player.isConnected) return
                    bookQueue?.check()
                    if (recoveryJob != null && (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING)) {
                        interruptRecovery()
                    }
                    if (player.playbackState == Player.STATE_READY &&
                        (events.contains(Player.EVENT_TRACKS_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED))
                    ) confirmAudioQuality(player)
                    publish()
                    updateRecentPlayback(player)
                    if (player.playbackState == Player.STATE_READY) consecutiveErrors = 0
                    val timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
                    if (timelineChanged) previousMediaId = player.currentMediaItem?.mediaId
                    if (timelineChanged || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        // 删除最后一首/外部清空也同步落盘，不能等下一次点歌时复活旧队列。
                        saveQueue(force = timelineChanged && player.mediaItemCount == 0)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "播放错误: ${error.errorCodeName}", error)
                    handlePlayerError(error)
                }
            },
        )
        // init在恢复队列/处理待播请求后统一发布，不先暴露一个临时空队列。
        updateRecentPlayback(player)
    }

    /** 只展示实际选中的音频轨，不误把未选中的第一个格式当成播放规格。 */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun readAudioSpec(player: Player): AudioSpecification? {
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?: return null
        val index = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return null
        return AudioSpecification.fromFormat(group.getTrackFormat(index))
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
        if (editingQueue) return
        val player = controller?.takeIf { it.isConnected } ?: return
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
            positionSampleRealtimeMs = SystemClock.elapsedRealtime(),
            positionAdvancing = player.isPlaying,
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
            if (currentTrack != null) {
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
            _state.value = snapshot.copy(
                positionMs = position, durationMs = duration,
                positionSampleRealtimeMs = SystemClock.elapsedRealtime(),
                positionAdvancing = player.isPlaying,
            )
        }
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
        if (track == null) {
            lyricJob?.cancel()
            _lyric.value = null
            return
        }

        lyricJob?.cancel()
        _lyric.value = null
        appContext?.let { context ->
            lyricJob = scope.launch {
                // 同一入口验证本地文件版本，不能因UID命中旧内存而跳过物理标签更新。
                val lyric = recoverableOrNull { LyricRepository.load(context, track) }
                if (_state.value.current?.uid == track.uid) _lyric.value = lyric
            }
        }

        val context = appContext ?: return
        // 预取下一首歌词：自动连播时歌词秒现（听书连播同理）
        val next = snapshot.queue.getOrNull(snapshot.currentIndex + 1)
        if (next != null) {
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
        val expectedUid = player.currentMediaItem?.mediaId
        val generation = recoveryGeneration
        scope.launch {
            val failedTrack = expectedUid?.let(TrackRegistry::get)
            val uri = failedTrack?.let(::localResourceUri)
            val context = appContext
            if (uri != null && context != null) {
                when (withContext(Dispatchers.IO) { localFilePresence(context, uri, failedTrack?.raw?.optString("localFolder")) }) {
                    LocalFilePresence.Missing -> {
                        val ids = if (failedTrack?.source == LocalSong.SOURCE) setOf(failedTrack.uid.removePrefix("${LocalSong.SOURCE}_")) else emptySet()
                        onLocalFilesDeleted(context, ids, setOf(uri)).join()
                        return@launch
                    }
                    LocalFilePresence.Unknown -> {
                        if (failedTrack?.isOnline != true || failedTrack.source == LocalSong.SOURCE) {
                            if (player.currentMediaItem?.mediaId == expectedUid && generation == recoveryGeneration) {
                                player.pause()
                                _state.value = _state.value.copy(message = "本地文件暂时无法访问，请检查文件或目录权限")
                            }
                            return@launch
                        }
                        // 在线条目的本地副本不可访问，继续原有网络回退，不把它当成文件删除。
                    }
                    LocalFilePresence.Present -> Unit
                }
            }
            if (player.currentMediaItem?.mediaId != expectedUid || generation != recoveryGeneration) return@launch
            // 已暂停时仍可能收到在途读取的错误；不能因重试/跳曲覆盖用户刚发出的暂停。
            if (!player.playWhenReady) {
                _state.value = _state.value.copy(message = "加载失败，点击播放重试")
                return@launch
            }
            val snapshot = _state.value
            val track = snapshot.current ?: return@launch
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
                    return@launch
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
                return@launch
            }
            val failed = TrackRegistry.resolved(track.uid)?.resourceId
            if ((failed != null || retryLocalFallback) && refreshAttempted.add(track.uid)) {
                if (failed != null) SourceResolver.rejectResource(track.uid, failed)
                TrackRegistry.clearResolved(track.uid)
                _state.value = snapshot.copy(message = "播放链接失效，正在尝试其它可用资源…")
                // 让唯一DataSource入口重解析，不再先手工解析一次再prepare第二次。
                player.prepare() // prepare保留当前播放意图，无需强行play。
                return@launch
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
    }

    private fun buildItem(track: UiTrack): MediaItem {
        if (track.source == LocalSong.SOURCE) {
            LocalMediaStore.matchTrack(track)?.let { local ->
                TrackRegistry.register(track.copy(raw = JSONObject(track.raw?.toString() ?: "{}").put("localUri", local.uri).put("localFolder", local.folder)))
            }
        }
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
            pendingPlayback = PendingPlaybackSelection(tracks, startIndex, queueId)
            _state.value = _state.value.copy(pendingQueueId = queueId)
            return
        }
        bookQueue?.stop()
        currentQueueId = queueId
        _state.value = _state.value.copy(pendingQueueId = null, message = null)
        TrackRegistry.registerAll(tracks)
        val items = tracks.map(::buildItem)
        val index = startIndex.coerceIn(0, items.lastIndex)
        prefetchTrack(tracks[index])
        player.setMediaItems(items, index, C.TIME_UNSET)
        val bookId = OnlineSong.from(tracks[index].raw)?.bookId()
        if (bookId != null && queueId == bookQueueId(bookId) &&
            tracks.all { OnlineSong.from(it.raw)?.bookId() == bookId }
        ) bookQueue?.start(bookId)
        player.prepareAndPlay()
        saveQueue(force = true)
        publish()
        appContext?.let(::checkLocalQueue)
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

    /** 听书卡片“播放全部”：保留完整目录快照的分页信息，后续章节由播放层续页。 */
    fun playBook(context: Context, playlist: OnlinePlaylist) {
        val id = playlist.id.removePrefix("kw:").removePrefix("book_album_")
        if (id.isBlank()) return
        UserLibrary.markContainerPlayed(
            UserLibrary.PlayContainer("book", playlist.id, playlist.name, playlist.img, playlist.source, bookQueueId(id)),
        )
        requestQueue<KwBookApi.BookChapters>(
            context, bookQueueId(id), "playlistDetail.book.book_album_$id", { it.items },
        ) { KwBookApi.album(id, 1) }
    }

    /** 任意单章入口都在这里接入同书目录；不会把听书语义散落到各个页面。 */
    private fun playBookChapterNow(track: UiTrack, id: String) {
        val player = controller
        if (player != null && bookQueue?.albumId == id) {
            val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.uid }
            if (index != null) {
                if (player.currentMediaItemIndex != index || player.playbackState == Player.STATE_ENDED) {
                    player.seekToDefaultPosition(index)
                }
                bookQueue?.retry()
                player.prepareAndPlay()
                saveQueue(force = true)
                publish()
                return
            }
        }
        val cached = OnlineCache.peek<KwBookApi.BookChapters>("playlistDetail.book.book_album_$id")?.items.orEmpty()
        val index = cached.indexOfFirst { it.uid == track.uid }
        val tracks = if (index >= 0) cached.map(UiTrack::fromOnline) else listOf(track)
        playQueueNow(tracks, index.coerceAtLeast(0), bookQueueId(id))
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
            playQueueNow(tracks, startIndex, queueId ?: container?.queueId)
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
        val bookId = OnlineSong.from(track.raw)?.bookId()
        val origin = container ?: bookId?.let {
            UserLibrary.PlayContainer("book", "book_album_$it", track.album.ifBlank { track.title }, track.artwork, track.source, bookQueueId(it))
        }
        requestPlayback(context, track, origin) {
            if (bookId != null) playBookChapterNow(track, bookId) else playTrackNow(track)
        }
    }

    private fun playTrackNow(track: UiTrack) {
        interruptRecovery()
        val player = controller
        if (player == null) {
            pendingPlayback = PendingPlaybackSelection(listOf(track), insertSingle = true)
            return
        }
        // 冷启动直接点歌曲也保留上次队列，但不能先触发旧歌自动播放/解析。
        restoreQueue(player, autoPlay = false)
        bookQueue?.stop()
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
        }
        player.prepareAndPlay()
        saveQueue(force = true)
        publish()
        appContext?.let(::checkLocalQueue)
    }

    /** 批量追加只修改队尾，不解析音源、不打断当前播放；同一UID只加入一次。 */
    fun addToQueue(context: Context, tracks: List<UiTrack>) {
        appContext = context.applicationContext
        if (tracks.isEmpty()) {
            _state.value = _state.value.copy(message = "没有可加入的歌曲")
            return
        }
        val player = controller
        if (player == null) {
            _state.value = _state.value.copy(message = "播放服务尚未就绪，请稍后重试")
            return
        }
        restoreQueue(player, autoPlay = false)
        val queued = (0 until player.mediaItemCount).mapTo(hashSetOf()) { player.getMediaItemAt(it).mediaId }
        val additions = tracks.filter { queued.add(it.uid) }
        if (additions.isEmpty()) {
            _state.value = _state.value.copy(message = "歌曲已在播放队列中")
            return
        }
        bookQueue?.stop()
        TrackRegistry.registerAll(additions)
        player.addMediaItems(additions.map(::buildItem))
        saveQueue(force = true)
        _state.value = _state.value.copy(message = "已加入播放队列 ${additions.size} 首")
        publish()
    }

    fun addToQueueNext(context: Context, track: UiTrack) {
        appContext = context.applicationContext
        val player = controller ?: return
        bookQueue?.stop()
        TrackRegistry.register(track)
        val at = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        player.addMediaItem(at, buildItem(track))
        saveQueue(force = true)
        _state.value = _state.value.copy(message = "已设为下一首播放")
        publish()
    }

    /** 删除确认后的唯一收尾入口；不启动播放服务，冷态队列也同步清理。 */
    fun onLocalFilesDeleted(context: Context, ids: Set<String>, uris: Set<String>): Job = scope.launch {
        if (ids.isEmpty() && uris.isEmpty()) return@launch
        appContext = context.applicationContext
        val deleted = withContext(Dispatchers.IO) {
            val aliasIds = uris.mapNotNull { LocalMediaStore.findByUri(it)?.id }.toSet()
            val references = LocalMediaStore.songs.value.filter { it.id in ids || it.id in aliasIds || it.uri in uris }
            val files = DeletedLocalFiles(ids + aliasIds + references.map { it.id }, uris + references.map { it.uri })
            try {
                LocalMediaStore.removeIds(files.ids)
            } catch (error: IOException) {
                // 文件已经物理删除，资料落盘失败不能阻断队列收尾或使应用崩溃。
                Log.e(TAG, "删除后的本地歌曲资料保存失败", error)
            }
            DownloadCenter.records.value.filter { it.hasSavedResource && it.savedUri in files.uris }.distinctBy { it.savedUri }.forEach {
                DownloadCenter.clearSaved(it.id, "本地文件已删除", expectedUri = it.savedUri)
            }
            files
        }
        pendingPlayback = pendingPlayback?.without(deleted)
        if (pendingPlayback?.tracks?.isEmpty() == true) _state.value = _state.value.copy(pendingQueueId = null)
        queuePrefs()?.let { pruneSavedLocalQueue(it, deleted) }
        lastQueueFingerprint = null
        val player = controller ?: return@launch
        val current = player.currentMediaItem?.mediaId?.let(TrackRegistry::get)
        val restartOnline = current != null && current.source != LocalSong.SOURCE &&
            localResourceUri(current) in deleted.uris
        if (current?.let(deleted::matches) == true || restartOnline) interruptRecovery()
        editingQueue = true
        try {
            // 只清本地解析结果，不清其它歌曲的网络地址缓存。
            for (index in 0 until player.mediaItemCount) {
                val uid = player.getMediaItemAt(index).mediaId
                val resolution = TrackRegistry.resolved(uid)
                if (resolution?.localFile?.id in deleted.ids || resolution?.localFile?.uri in deleted.uris ||
                    resolution?.downloadUri in deleted.uris) TrackRegistry.clearResolved(uid)
            }
            player.removeDeletedLocalItems(deleted)
            if (player.mediaItemCount == 0) {
                currentQueueId = null
                _lyric.value = null
            } else if (restartOnline && player.currentMediaItem?.mediaId == current?.uid) {
                val position = player.currentPosition.coerceAtLeast(0L)
                val index = player.currentMediaItemIndex
                player.stop()
                player.seekTo(index, position)
                // 暂停时只失效旧读取，等用户恢复再解析；不替用户按播放键。
                if (player.playWhenReady) player.prepare()
            } else if (player.playWhenReady && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        } finally { editingQueue = false }
        saveQueue(force = true)
        publish()
    }

    /** 只核验队列引用的文件。媒体库通知/前台恢复触发，IO异步且合并，不扫描全库。 */
    fun checkLocalQueue(context: Context): Job {
        localQueueCheck?.cancel()
        return scope.launch {
            delay(250)
            val player = controller
            val tracks = if (player != null && player.mediaItemCount > 0) {
                (0 until player.mediaItemCount).mapNotNull { TrackRegistry.get(player.getMediaItemAt(it).mediaId) }
            } else {
                val prefs = context.getSharedPreferences(PREFS_QUEUE, Context.MODE_PRIVATE)
                val array = runCatching { JSONArray(prefs.getString(KEY_QUEUE, null) ?: "[]") }.getOrNull() ?: JSONArray()
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::trackFromJson) }
            }
            val resources = tracks.mapNotNull { track -> localResourceUri(track)?.let { track to it } }
            if (resources.isEmpty()) return@launch
            val missing = withContext(Dispatchers.IO) {
                resources.distinctBy { it.second }.filter { (owner, uri) ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val folder = owner.raw?.optString("localFolder")?.takeIf(String::isNotBlank)
                        ?: TrackRegistry.resolved(owner.uid)?.localFile?.folder
                    localFilePresence(context.applicationContext, uri, folder) == LocalFilePresence.Missing
                }.mapTo(hashSetOf()) { it.second }
            }
            if (missing.isNotEmpty()) {
                val ids = resources.filter { it.second in missing && it.first.source == LocalSong.SOURCE }
                    .mapTo(hashSetOf()) { it.first.uid.removePrefix("${LocalSong.SOURCE}_") }
                onLocalFilesDeleted(context, ids, missing).join()
            }
        }.also { localQueueCheck = it }
    }

    private fun localResourceUri(track: UiTrack): String? = when {
        track.source == LocalSong.SOURCE -> LocalMediaStore.matchTrack(track)?.uri
            ?: track.raw?.optString("localUri")?.takeIf(String::isNotBlank)
        !track.isOnline && (track.uid.startsWith("file:") || track.uid.startsWith("content:")) -> track.uid
        else -> TrackRegistry.resolved(track.uid)?.let { it.localFile?.uri ?: it.downloadUri }
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

    /** 各播放入口都可接管冷启动恢复出的未准备队列，不依赖是否曾按过播放键。 */
    private fun Player.prepareAndPlay() {
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }

    fun toggle() {
        interruptRecovery()
        val player = controller ?: return
        // 缓冲/解析/音频焦点暂时受限时isPlaying为false，但用户仍能暂停等待播放。
        if (player.playWhenReady) {
            player.pause()
        } else {
            bookQueue?.retry()
            player.prepareAndPlay()
        }
    }

    fun next() {
        interruptRecovery()
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        if (bookQueue?.next() == true) {
            // 若已自然播完，play保留续播意图，不prepare重放当前章节。
            player.play()
            return
        }
        player.seekToNextMediaItem()
        player.prepareAndPlay()
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
        player.prepareAndPlay()
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
        player.prepareAndPlay()
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
        if (bookQueue?.albumId != null) {
            _state.value = _state.value.copy(message = "听书按章节顺序播放")
            return
        }
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

    fun clearQueue() = endPlayback(stopEngine = false, player = controller)

    // 通知栏持有服务端Player，即使界面尚未连接，也必须清理同一份持久化队列。
    fun stop(player: Player? = controller) = endPlayback(stopEngine = true, player = player)

    private fun endPlayback(stopEngine: Boolean, player: Player?) {
        localQueueCheck?.cancel()
        localQueueCheck = null
        interruptRecovery()
        cancelPendingPlayback()
        AudioCacheStore.cancelPrefetch()
        editingQueue = true
        try {
            bookQueue?.stop()
            player?.let {
                if (stopEngine) it.stop() else it.pause()
                it.clearMediaItems()
            }
        } finally {
            editingQueue = false
            // 停止/暂停回调不能在清空过程中把旧队列重新保存。
            clearSavedQueue()
        }
        currentQueueId = null
        lyricJob?.cancel()
        artworkJob?.cancel()
        detailUid = null
        _lyric.value = null
        // 不依赖异步MediaController回调，也不在无控制器时遗留旧曲目。
        val previous = _state.value
        _state.value = PlayerUiState(
            ready = controller?.isConnected == true, mode = previous.mode, speed = previous.speed,
        )
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
        if (editingQueue) return
        val player = controller?.takeIf { it.isConnected } ?: return
        val prefs = queuePrefs() ?: return
        val count = player.mediaItemCount
        if (count == 0) {
            if (force) clearSavedQueue()
            return
        }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val all = (0 until count).mapNotNull { i -> TrackRegistry.get(player.getMediaItemAt(i).mediaId) }
        if (all.size != count) return
        val fingerprint = "${bookQueue?.albumId}:${currentQueueId}:" + playbackQueueFingerprint(all, index)
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
            putString("bookId", bookQueue?.albumId)
            putString("queueId", currentQueueId)
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
        currentQueueId = prefs.getString("queueId", null)
        val bookId = prefs.getString("bookId", null)?.takeIf { id ->
            tracks.all { OnlineSong.from(it.raw)?.bookId() == id }
        }
        player.setMediaItems(items, index, C.TIME_UNSET)
        if (bookId != null) bookQueue?.start(bookId)
        if (autoPlay) {
            player.prepareAndPlay()
        }
        lastQueueFingerprint = "${bookQueue?.albumId}:${currentQueueId}:" + playbackQueueFingerprint(tracks, index)
        Log.d(TAG, "已恢复上次播放队列：${tracks.size} 首，当前第 ${index + 1} 首")
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
    return lyric.lines.getOrNull(lyricIndexAt(lyric.lines, positionMs))?.text?.takeIf { it.isNotBlank() }
}

/** 单曲队列策略：按 uid 去重，不把来源列表隐式变成播放队列。 */
internal data class SingleTrackQueueTarget(val index: Int, val insert: Boolean)

internal fun singleTrackQueueTarget(queueUids: List<String>, currentIndex: Int, uid: String): SingleTrackQueueTarget {
    val existing = queueUids.indexOf(uid)
    return if (existing >= 0) SingleTrackQueueTarget(existing, insert = false)
    else SingleTrackQueueTarget((currentIndex + 1).coerceIn(0, queueUids.size), insert = true)
}
