package com.leyu.melora.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong

internal const val ERROR_CODE_AUDIO_OUTPUT_STALLED = PlaybackException.CUSTOM_ERROR_CODE_BASE + 1
internal const val ERROR_CODE_AUDIO_PREVIEW = PlaybackException.CUSTOM_ERROR_CODE_BASE + 2

/** 字节和播放头均在播放线程采集，主线程只观察不可变快照。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioOutputProbe {
    data class Snapshot(val generation: Long = 0, val writtenBytes: Long = 0, val playedFrames: Long = 0, val periodUid: Any? = null)
    class Failure(
        val snapshot: Snapshot,
        val uid: String,
        val itemIndex: Int,
        val resumePositionMs: Long,
        val recoveryGeneration: Long,
    ) : IllegalStateException("Playback position advanced without audio output") {
        @Volatile var cancelled = false
    }

    @Volatile var snapshot = Snapshot()
        private set
    lateinit var renderer: Renderer
    private var writtenBytes = 0L
    private var lastHead: Int? = null

    fun reset(periodUid: Any? = snapshot.periodUid) {
        lastHead = null
        writtenBytes = 0
        snapshot = Snapshot(generation = snapshot.generation + 1, periodUid = periodUid)
    }

    fun wrote(bytes: Int) { writtenBytes += bytes }

    // AudioTrack 的播放头是会回绕的 32 位帧计数。保留高水位，小幅回落和反弹不算输出。
    fun sample(head: Int?, minimumFrames: Int) {
        val old = snapshot
        val previous = lastHead
        val delta = if (head != null && previous != null) head - previous else 0
        val advanced = delta.takeIf { it >= minimumFrames } ?: 0
        if (previous == null || advanced > 0) lastHead = head
        if (advanced > 0 || writtenBytes != old.writtenBytes) {
            snapshot = old.copy(writtenBytes = writtenBytes, playedFrames = old.playedFrames + advanced)
        }
    }

    fun stillStalled(failure: Failure): Boolean = !failure.cancelled && snapshot == failure.snapshot

    companion object {
        const val CHECK_OUTPUT = Renderer.MSG_CUSTOM_BASE
    }
}

/** READY走时却无输出必须变成真实播放错误，使Media3进入IDLE后复用已有错误恢复。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioOutputMonitor(
    private val player: ExoPlayer,
    private val probe: AudioOutputProbe,
    private val watchdog: AudioOutputWatchdog = AudioOutputWatchdog(),
) : Player.Listener, AnalyticsListener {
    class PreviewFailure(val uid: String, val itemIndex: Int, val resourceId: String) :
        IllegalStateException("Audio is much shorter than the catalog recording") {
        @Volatile var cancelled = false
    }

    private var pendingFailure: AudioOutputProbe.Failure? = null
    private var pendingPreview: PreviewFailure? = null
    private var closed = false
    private var previousOutput = probe.snapshot
    private var healthySinceMs: Long? = null
    private var playbackGeneration = -1L
    private var pendingPlayback: TrackRegistry.NetworkPlayback? = null

    init {
        player.addListener(this)
        player.addAnalyticsListener(this)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = reset()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        reset()
        Log.i(TAG, "item uid=${playbackDiagnosticId(mediaItem?.mediaId)} index=${player.currentMediaItemIndex} reason=$reason")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) reset()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (closed || pendingPreview != null || pendingFailure != null || !player.isPlaying ||
            !events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY) ||
            player.isPlayingAd || player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic
        ) return
        val uid = player.currentMediaItem?.mediaId ?: return
        val resource = TrackRegistry.resolved(uid)?.resourceId?.takeIf { it.startsWith("lx:") } ?: return
        val song = OnlineSong.from(TrackRegistry.get(uid)?.raw) ?: return
        if (song.isBookChapter || song.source == LocalSong.SOURCE || !isPreviewDuration(song.intervalSeconds, player.duration)) return

        val failure = PreviewFailure(uid, player.currentMediaItemIndex, resource)
        pendingPreview = failure
        Log.w(TAG, "preview uid=${playbackDiagnosticId(uid)} expectedSeconds=${song.intervalSeconds} durationMs=${player.duration}")
        // 在播放线程结束旧资源，让控制器从 IDLE 使用同一套错误恢复。
        this.player.createMessage { _, _ ->
            if (!failure.cancelled) throw ExoPlaybackException.createForUnexpected(failure, ERROR_CODE_AUDIO_PREVIEW)
        }.send()
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        Log.i(TAG, "format window=${eventTime.windowIndex} current=${player.currentMediaItemIndex} ${formatDescription(format)}")
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.i(TAG, "decoder initialized window=${eventTime.windowIndex} name=$decoderName")
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        Log.i(TAG, "track initialized window=${eventTime.windowIndex} config=$audioTrackConfig")
    }

    override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        Log.i(TAG, "track released window=${eventTime.windowIndex}")
    }

    fun check(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (closed || pendingPreview != null) return
        // 普通采样与报错前的最终复查都在播放线程读硬件，主线程不会查询或持有 AudioTrack。
        val output = probe.snapshot
        val item = player.currentMediaItem
        observeOutput(item, output, nowMs)
        previousOutput = output
        val uid = item?.mediaId
        if (!watchdog.update(AudioOutputWatchdog.Observation(
                uid = uid,
                positionMs = player.currentPosition,
                durationMs = player.duration,
                playing = player.isPlaying,
                output = output,
            ), nowMs) || uid == null) {
            if (player.isPlaying) player.createMessage(probe.renderer).setType(AudioOutputProbe.CHECK_OUTPUT).send()
            return
        }
        val counters = player.audioDecoderCounters
        counters?.ensureUpdated()
        val resolution = TrackRegistry.resolved(uid)
        Log.e(TAG, "output stalled uid=${playbackDiagnosticId(uid)} position=${player.currentPosition} resume=${watchdog.resumePositionMs} " +
            "resource=${playbackDiagnosticId(resolution?.resourceId)} cache=${resolution?.fromCompleteCache} " +
            "generation=${output.generation} writtenBytes=${output.writtenBytes} playedFrames=${output.playedFrames} " +
            "input=${counters?.queuedInputBufferCount} output=${counters?.renderedOutputBufferCount} " +
            formatDescription(player.audioFormat))
        val failure = AudioOutputProbe.Failure(output, uid, player.currentMediaItemIndex,
            watchdog.resumePositionMs, PlaybackController.recoveryGeneration)
        pendingFailure = failure
        player.createMessage(probe.renderer).setType(AudioOutputProbe.CHECK_OUTPUT).setPayload(failure).send()
    }

    private fun observeOutput(item: MediaItem?, output: AudioOutputProbe.Snapshot, nowMs: Long) {
        if (item == null || !player.isPlaying || output.generation != previousOutput.generation ||
            output.playedFrames <= previousOutput.playedFrames) {
            healthySinceMs = null
            return
        }
        if (playbackGeneration != output.generation && isCurrentPeriod(output)) {
            playbackGeneration = output.generation
            healthySinceMs = null
            // 固定到这个输出代次；尚未播完的旧缓冲不能替后来重开的地址确认。
            pendingPlayback = TrackRegistry.resolved(item.mediaId)?.networkPlayback
                ?.takeIf { it.itemUri == item.localConfiguration?.uri }
        }
        val since = healthySinceMs ?: nowMs.also { healthySinceMs = it }
        if (nowMs - since < STABLE_OUTPUT_MS) return
        PlaybackController.onAudioOutputProgress(item.mediaId)

        val playback = pendingPlayback ?: return
        val firstByteMs = playback.firstNetworkByteMs ?: return
        // 开头可能来自部分缓存，至少观察到联网后的一秒实际输出。
        if (nowMs - firstByteMs < STABLE_OUTPUT_MS) return
        pendingPlayback = null
        if (playbackGeneration == output.generation && isCurrentPeriod(output) &&
            playback.itemUri == item.localConfiguration?.uri && TrackRegistry.resolved(item.mediaId)?.networkPlayback === playback
        ) playback.confirmation.confirm()
    }

    private fun isCurrentPeriod(output: AudioOutputProbe.Snapshot): Boolean {
        val timeline = player.currentTimeline
        val index = player.currentPeriodIndex
        return index in 0 until timeline.periodCount && timeline.getUidOfPeriod(index) == output.periodUid
    }

    private fun reset() {
        pendingFailure?.cancelled = true
        pendingFailure = null
        pendingPreview?.cancelled = true
        pendingPreview = null
        watchdog.reset()
        healthySinceMs = null
    }

    fun close() {
        closed = true
        reset()
        player.removeListener(this)
        player.removeAnalyticsListener(this)
    }

    private fun formatDescription(format: Format?): String =
        "mime=${format?.sampleMimeType} bitrate=${format?.averageBitrate} rate=${format?.sampleRate} channels=${format?.channelCount}"

    private companion object {
        const val TAG = "PlaybackAudio"
        const val STABLE_OUTPUT_MS = 1_000L
    }
}
