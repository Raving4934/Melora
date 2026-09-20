package com.leyu.melora.playback

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.leyu.melora.MainActivity
import com.leyu.melora.R
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val artworkListener: (String, String) -> Unit = { _, _ ->
        serviceScope.launch { refreshNotificationMetadata() }
    }

    private val favoriteCommand = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
    private val exitCommand = SessionCommand(ACTION_EXIT, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        // 播放与下载共享唯一音频缓存，避免同一首歌重复联网与缓存目录多实例冲突。
        isRunning = true
        val cacheDataSourceFactory = AudioCacheStore.playbackDataSourceFactory(this)

        val effects = PcmEffectsAudioProcessor(
            initialPreset = AudioEffects.preset(MeloraSettings.audioEffectPreset.value),
            report = AudioEffects::report,
        )
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = object : ForwardingAudioSink(
                DefaultAudioSink.Builder(context)
                    // Media3的float直出/offload会跳过自定义链；保持原integer PCM和Sonic倍速路径。
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(false)
                    .setAudioProcessors(arrayOf(effects))
                    .build(),
            ) {
                override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                override fun getFormatSupport(format: Format): Int =
                    if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                    else AudioSink.SINK_FORMAT_UNSUPPORTED
                override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
                override fun setOffloadMode(offloadMode: Int) = super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(playbackLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                MeloraSettings.pauseOnOtherAudio.value,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build(),
            ).build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val result = super.onConnect(session, controller)
                    if (!canUsePrivateMediaCommands(controller.uid, android.os.Process.myUid(), controller.isTrusted)) return result
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(
                            result.availableSessionCommands.buildUpon()
                                .add(favoriteCommand)
                                .add(exitCommand)
                                .build(),
                        )
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (!canUsePrivateMediaCommands(controller.uid, android.os.Process.myUid(), controller.isTrusted)) {
                        return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED))
                    }
                    when (customCommand.customAction) {
                        ACTION_FAVORITE -> {
                            toggleFavorite()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        ACTION_EXIT -> {
                            exitPlayback()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshCustomLayout()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                refreshCustomLayout()
            }

        })
        // 收藏状态变化（应用内或系统按钮）实时同步图标
        serviceScope.launch {
            UserLibrary.favoriteUids.collectLatest { refreshCustomLayout() }
        }
        // 音效预设切换（应用内）立即生效
        serviceScope.launch {
            MeloraSettings.audioEffectPreset.collect(effects::select)
        }
        // 通知元数据由实际播放器唯一写入；不能用远端控制器的时间线投影反向替换播放项。
        TrackRegistry.onArtwork(artworkListener)
        serviceScope.launch {
            combine(
                PlaybackController.state,
                PlaybackController.lyric,
                MeloraSettings.notificationLyrics,
                MeloraSettings.showNotificationCover,
            ) { _, _, _, _ -> Unit }.collect { refreshNotificationMetadata() }
        }
        refreshCustomLayout()
    }

    private fun refreshNotificationMetadata() {
        val player = mediaSession?.player ?: return
        syncNotificationMetadata(
            player,
            PlaybackController.lyric.value,
            MeloraSettings.notificationLyrics.value,
            MeloraSettings.showNotificationCover.value,
        )
    }

    // ---------- 系统控制中心按钮 ----------

    private fun currentSong(): OnlineSong? {
        val uid = mediaSession?.player?.currentMediaItem?.mediaId ?: return null
        val track = TrackRegistry.get(uid) ?: return null
        return OnlineSong.from(track.raw)
    }

    private fun toggleFavorite() {
        val song = currentSong() ?: return
        UserLibrary.toggleFavorite(song)
    }

    private fun exitPlayback() {
        AudioCacheStore.cancelPrefetch()
        val player = mediaSession?.player ?: return
        player.stop()
        player.clearMediaItems()
        stopSelf()
    }

    private fun refreshCustomLayout() {
        val session = mediaSession ?: return
        val song = currentSong()
        val favorite = song != null && UserLibrary.isFavorite(song.uid)
        session.setCustomLayout(
            listOf(
                CommandButton.Builder(
                    if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
                )
                    .setSessionCommand(favoriteCommand)
                    .setDisplayName("收藏")
                    .setCustomIconResId(if (favorite) R.drawable.ic_media_fav_on else R.drawable.ic_media_fav)
                    .setSlots(CommandButton.SLOT_BACK_SECONDARY)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_STOP)
                    .setSessionCommand(exitCommand)
                    .setDisplayName("退出")
                    .setCustomIconResId(R.drawable.ic_media_exit)
                    .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
                    .build(),
            ),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        TrackRegistry.removeArtworkListener(artworkListener)
        AudioCacheStore.cancelPrefetch()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        AudioEffects.resetState()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_FAVORITE = "com.leyu.melora.action.FAVORITE"
        private const val ACTION_EXIT = "com.leyu.melora.action.EXIT"

        @Volatile
        var isRunning = false
            private set
    }
}

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
) {
    val currentIndex = player.currentMediaItemIndex
    val positionMs = player.currentPosition
    for (index in 0 until player.mediaItemCount) {
        val item = player.getMediaItemAt(index)
        val track = TrackRegistry.get(item.mediaId) ?: continue
        val artist = if (index == currentIndex) {
            notificationLyricLine(lyricsEnabled, item.mediaId, positionMs, lyric) ?: track.artist
        } else track.artist
        val artwork = playableArtworkUri(track.artwork, coverEnabled)
        val metadata = item.mediaMetadata
        if (metadata.title?.toString() == track.title &&
            metadata.artist?.toString() == artist &&
            metadata.albumTitle?.toString() == track.album &&
            metadata.artworkUri?.toString() == artwork
        ) continue
        player.replaceMediaItem(
            index,
            item.buildUpon().setMediaMetadata(
                metadata.buildUpon()
                    .setTitle(track.title)
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
