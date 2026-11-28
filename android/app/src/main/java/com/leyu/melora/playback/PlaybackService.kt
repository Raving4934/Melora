package com.leyu.melora.playback

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicInteger
import com.leyu.melora.MainActivity
import com.leyu.melora.R
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var playbackProgress: PlaybackProgress? = null
    private var audioOutputMonitor: AudioOutputMonitor? = null
    private lateinit var bluetoothOutput: StateFlow<Boolean>
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notificationId = notificationIds.incrementAndGet()

    private val artworkListener: (String, String) -> Unit = { _, _ ->
        serviceScope.launch { refreshNotificationMetadata() }
    }

    override fun onCreate() {
        super.onCreate()
        if ((application as? com.leyu.melora.MeloraApplication)?.restoreFailure != null) return
        // 旧版通知默认只读 title；与新版系统媒体卡片的 displayTitle 优先规则保持一致。
        // SystemUI 会异步清理媒体通知；新会话不能复用旧通知键，否则迟到的清理可能 Stop 新播放器。
        // 同一服务实例始终使用同一个ID，元数据、播放状态和曲目更新仍原位刷新。
        setMediaNotificationProvider(object : DefaultMediaNotificationProvider(
            this,
            { notificationId },
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID,
        ) {
            override fun getNotificationContentTitle(mediaMetadata: MediaMetadata): CharSequence? =
                notificationContentTitle(mediaMetadata)
        })
        // 播放与下载共享唯一音频缓存，避免同一首歌重复联网与缓存目录多实例冲突。
        activeService = this
        val cacheDataSourceFactory = AudioCacheStore.playbackDataSourceFactory(this)

        val effects = PcmEffectsAudioProcessor(
            initialPreset = AudioEffects.preset(MeloraSettings.audioEffectPreset.value),
            report = AudioEffects::report,
        )
        val renderers = PlaybackRenderersFactory(this, effects)
        bluetoothOutput = renderers.bluetoothOutput
        val player = ExoPlayer.Builder(this, renderers)
            // 接收解码就绪的即时唤醒；renderer同时保留10ms进度采样，避免逐字歌词抢跑/回退。
            .experimentalSetDynamicSchedulingEnabled(true)
            .setLoadControl(playbackLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()
        audioOutputMonitor = AudioOutputMonitor(player, renderers.outputProbe)
        serviceScope.launch {
            while (isActive) {
                delay(500)
                audioOutputMonitor?.check()
                if (MeloraSettings.notificationLyrics.value ||
                    (MeloraSettings.bluetoothLyrics.value && bluetoothOutput.value)
                ) {
                    refreshNotificationMetadata(currentOnly = true)
                }
            }
        }
        player.followAudioFocusSettings(serviceScope)
        PlaybackController.sleepTimer.attach(player)
        player.applyPlayMode(MeloraSettings.musicPlayMode.value)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build(),
            ).build()

        val progress = PlaybackProgress(player, getSharedPreferences(BookListeningProgress.PREFS, Context.MODE_PRIVATE))
        playbackProgress = progress
        serviceScope.launch {
            while (isActive) {
                delay(5_000)
                progress.checkpoint()
                PlaybackController.checkpointQueuePosition(player)
            }
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        displayedFavorite = null
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(PlaybackSessionCallback(
                onFavorite = ::toggleFavorite,
                // 先答复命令再释放session，避免在途命令等待绑定超时。
                onExit = { serviceScope.launch { exitPlayback() } },
            ))
            .build()

        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                PlaybackController.onPlaybackTimelineChanged(player, timeline)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) refreshCustomLayout()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) refreshNotificationMetadata()
            }
        })
        serviceScope.launch { UserLibrary.favoriteUids.collectLatest { refreshCustomLayout() } }
        serviceScope.launch { MeloraSettings.audioEffectPreset.collect(effects::select) }
        // 目录或设置变动时同步整队；时钟只更新实际播放器当前窗口的歌词。
        TrackRegistry.onArtwork(artworkListener)
        serviceScope.launch {
            combine(
                PlaybackController.state.map { it.queue to it.currentIndex }.distinctUntilChanged(),
                PlaybackController.lyric,
                MeloraSettings.notificationLyrics,
                combine(MeloraSettings.bluetoothLyrics, bluetoothOutput) { enabled, routed ->
                    enabled && routed
                }.distinctUntilChanged(),
                MeloraSettings.showNotificationCover,
            ) { _, _, _, _, _ -> Unit }.collect { refreshNotificationMetadata() }
        }
        refreshCustomLayout()
    }

    private fun refreshNotificationMetadata(currentOnly: Boolean = false) {
        val player = mediaSession?.player ?: return
        syncNotificationMetadata(
            player,
            PlaybackController.lyric.value,
            MeloraSettings.notificationLyrics.value,
            MeloraSettings.showNotificationCover.value,
            currentOnly,
            bluetoothLyricsActive = MeloraSettings.bluetoothLyrics.value && bluetoothOutput.value,
        )
    }

    // ---------- 系统控制中心按钮 ----------

    private fun currentSong(): OnlineSong? {
        val uid = mediaSession?.player?.currentMediaItem?.mediaId ?: return null
        val track = TrackRegistry.get(uid) ?: return null
        return OnlineSong.from(track.raw)
    }

    private fun toggleFavorite(): ListenableFuture<SessionResult> {
        val song = currentSong() ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val result = SettableFuture.create<SessionResult>()
        UserLibrary.enqueueWrite { UserLibrary.toggleFavorite(song) }.invokeOnCompletion { error ->
            result.set(SessionResult(if (error == null) SessionResult.RESULT_SUCCESS else SessionError.ERROR_IO))
        }
        return result
    }

    private fun exitPlayback() {
        val session = mediaSession ?: return
        // 在stop/release之前记录真实断点，退出不删除队列。
        playbackProgress?.checkpoint()
        PlaybackController.exitPlayback(session.player)
        releaseSession()
        stopSelf()
    }

    private var displayedFavorite: Boolean? = null

    private fun refreshCustomLayout() {
        val session = mediaSession ?: return
        val song = currentSong()
        val favorite = song != null && UserLibrary.isFavorite(song.uid)
        if (displayedFavorite == favorite) return
        displayedFavorite = favorite
        session.setCustomLayout(
            listOf(
                CommandButton.Builder(
                    if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
                )
                    .setSessionCommand(PlaybackSessionCallback.Favorite)
                    .setDisplayName("收藏")
                    .setCustomIconResId(if (favorite) R.drawable.ic_media_fav_on else R.drawable.ic_media_fav)
                    .setSlots(CommandButton.SLOT_BACK_SECONDARY)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_STOP)
                    .setSessionCommand(PlaybackSessionCallback.Exit)
                    .setDisplayName("退出")
                    .setCustomIconResId(R.drawable.ic_media_exit)
                    .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
                    .build(),
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((application as? com.leyu.melora.MeloraApplication)?.finishBlockedServiceStart(this, startId) == true) {
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackProgress?.checkpoint()
        val player = mediaSession?.player
        PlaybackController.saveQueue(player = player)
        if (player == null || !player.canContinuePlaybackAfterTaskRemoved()) {
            exitPlayback()
        }
    }

    override fun onDestroy() {
        TrackRegistry.removeArtworkListener(artworkListener)
        AudioCacheStore.cancelPrefetch()
        serviceScope.cancel()
        PlaybackController.saveQueue(player = mediaSession?.player)
        releaseSession()
        AudioEffects.resetState()
        activeService = null
        super.onDestroy()
    }

    private fun releaseSession() {
        audioOutputMonitor?.close()
        audioOutputMonitor = null
        PlaybackController.sleepTimer.attach(null)
        playbackProgress?.close()
        playbackProgress = null
        mediaSession?.run {
            release()
            PlaybackController.clearPresentationMetadata(player)
            player.release()
        }
        mediaSession = null
    }

    companion object {
        private val notificationIds = AtomicInteger(SystemClock.elapsedRealtime().toInt() and Int.MAX_VALUE)

        @Volatile
        private var activeService: PlaybackService? = null

        val isRunning: Boolean get() = activeService != null

        /** 同进程调用不依赖MediaController握手；没有服务时绝不为退出重新启动服务。 */
        @androidx.annotation.MainThread
        internal fun exitIfRunning(): Boolean {
            val service = activeService?.takeIf { it.mediaSession != null } ?: return false
            service.exitPlayback()
            return true
        }
    }
}

/** 队列编辑在会话的实际播放器上串行完成，不能用控制器的异步投影编辑随机后继。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSessionCallback(
    private val onFavorite: () -> ListenableFuture<SessionResult>,
    private val onExit: () -> Unit,
) : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val result = super.onConnect(session, controller)
        if (!canUsePrivateMediaCommands(controller.uid, android.os.Process.myUid(), controller.isTrusted)) return result
        val commands = result.availableSessionCommands.buildUpon().add(Favorite).add(Exit)
        // UID对应本进程的TrackRegistry，不向外部媒体控制器开放此编辑入口。
        if (controller.uid == android.os.Process.myUid()) commands.add(QueueNext)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commands.build())
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (!canUsePrivateMediaCommands(controller.uid, android.os.Process.myUid(), controller.isTrusted)) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        }
        val result = when (customCommand.customAction) {
            Favorite.customAction -> return onFavorite()
            Exit.customAction -> {
                onExit()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            QueueNext.customAction -> {
                if (controller.uid != android.os.Process.myUid()) {
                    SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                } else {
                    val player = session.player as? ExoPlayer
                    val uid = args.getString(TRACK_UID)
                    val message = if (player != null && !uid.isNullOrBlank()) {
                        PlaybackController.applyQueueNext(player, uid)
                    } else null
                    if (message == null) SessionResult(SessionError.ERROR_BAD_VALUE)
                    else SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString(MESSAGE, message) })
                }
            }
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(result)
    }

    companion object {
        val Favorite = SessionCommand("com.leyu.melora.action.FAVORITE", Bundle.EMPTY)
        val Exit = SessionCommand("com.leyu.melora.action.EXIT", Bundle.EMPTY)
        val QueueNext = SessionCommand("com.leyu.melora.action.QUEUE_NEXT", Bundle.EMPTY)
        const val TRACK_UID = "uid"
        const val MESSAGE = "message"
    }
}

/** 实际播放器随服务生命周期接收设置；不依赖UI控制器，首值重放也覆盖服务重建。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.followAudioFocusSettings(scope: CoroutineScope) = scope.launch(Dispatchers.Main.immediate) {
    val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    MeloraSettings.pauseOnOtherAudio.collect { setAudioAttributes(attributes, it) }
}

/** 手机通知优先读取展示标题，标准 title 可供蓝牙接收端显示歌词。 */
internal fun notificationContentTitle(metadata: MediaMetadata): CharSequence? =
    metadata.displayTitle ?: metadata.title

/**
 * 仅由播放服务传入实际ExoPlayer。按播放器的当前索引和位置生成通知字段，
 * 不采用UI快照中的当前曲目，也不重建URI、缓存key、音质或裁剪配置。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun syncNotificationMetadata(
    player: Player,
    lyric: PlayerLyric?,
    lyricsEnabled: Boolean,
    coverEnabled: Boolean,
    currentOnly: Boolean = false,
    bluetoothLyricsActive: Boolean = false,
) {
    val currentIndex = player.currentMediaItemIndex
    val positionMs = player.currentPosition
    val indices = 0 until player.mediaItemCount
    if (currentOnly && currentIndex !in indices) return
    for (index in if (currentOnly) currentIndex..currentIndex else indices) {
        val item = player.getMediaItemAt(index)
        val track = TrackRegistry.get(item.mediaId) ?: continue
        val current = index == currentIndex
        val lyricLine = if (current) {
            notificationLyricLine(lyricsEnabled || bluetoothLyricsActive, item.mediaId, positionMs, lyric)
        } else null
        val bluetoothTitle = lyricLine.takeIf { bluetoothLyricsActive }
        val title = bluetoothTitle ?: track.title
        val displayTitle = when {
            current && lyricsEnabled && track.artist.isNotBlank() -> "${track.title} - ${track.artist}"
            bluetoothTitle != null -> track.title
            else -> null
        }
        val artist = if (lyricsEnabled) lyricLine ?: track.artist else track.artist
        val artwork = playableArtworkUri(track.artwork, coverEnabled)
        val metadata = item.mediaMetadata
        if (metadata.title?.toString() == title &&
            metadata.displayTitle?.toString() == displayTitle &&
            metadata.artist?.toString() == artist &&
            metadata.albumTitle?.toString() == track.album &&
            metadata.artworkUri?.toString() == artwork
        ) continue
        PlaybackController.replacePresentationMetadata(
            player, index,
            item.buildUpon().setMediaMetadata(
                metadata.buildUpon()
                    .setTitle(title)
                    .setDisplayTitle(displayTitle)
                    .setArtist(artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(artwork?.toUri())
                    .build(),
            ).build(),
        )
    }
}

/** 对外提供标准媒体控制；用户库修改和退出等私有命令只允许本应用/系统信任的控制器。 */
internal fun canUsePrivateMediaCommands(controllerUid: Int, appUid: Int, trusted: Boolean): Boolean =
    controllerUid == appUid || trusted

/** 移除任务后只保留有播放意图且处于可播放/缓冲状态的会话。 */
internal fun Player.canContinuePlaybackAfterTaskRemoved(): Boolean =
    playWhenReady && mediaItemCount > 0 &&
        (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
