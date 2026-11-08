package com.leyu.melora.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

internal const val ERROR_CODE_AUDIO_OUTPUT_STALLED = PlaybackException.CUSTOM_ERROR_CODE_BASE + 1

/** 只在播放线程读取AudioSink，主线程仅读取不可变快照，避免跨线程调用平台AudioTrack。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioOutputProbe {
    data class Snapshot(val generation: Long = 0, val progress: Long = 0, val positionUs: Long = AudioSink.CURRENT_POSITION_NOT_SET)
    class Failure(val snapshot: Snapshot) {
        @Volatile var cancelled = false
    }

    @Volatile var snapshot = Snapshot()
        private set
    lateinit var renderer: Renderer

    fun reset() {
        snapshot = Snapshot(snapshot.generation + 1, snapshot.progress)
    }

    fun position(positionUs: Long) {
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) return
        val old = snapshot
        snapshot = old.copy(
            progress = old.progress + if (positionUs > 0 &&
                (old.positionUs == AudioSink.CURRENT_POSITION_NOT_SET || positionUs > old.positionUs)) 1 else 0,
            positionUs = positionUs,
        )
    }

    fun stillStalled(failure: Failure): Boolean = !failure.cancelled &&
        snapshot.generation == failure.snapshot.generation && snapshot.progress == failure.snapshot.progress

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
    private var pendingFailure: AudioOutputProbe.Failure? = null
    private var closed = false
    private var previousOutput = probe.snapshot
    private var healthySinceMs: Long? = null

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
        if (closed) return
        val output = probe.snapshot
        if (player.isPlaying && output.generation == previousOutput.generation && output.progress != previousOutput.progress) {
            val since = healthySinceMs ?: nowMs.also { healthySinceMs = it }
            // 跨项残留的少量尾音不算恢复，持续输出才清除连续失败计数。
            if (nowMs - since >= 1_000) player.currentMediaItem?.mediaId?.let(PlaybackController::onAudioOutputProgress)
        } else healthySinceMs = null
        previousOutput = output
        if (!watchdog.update(AudioOutputWatchdog.Observation(
                uid = player.currentMediaItem?.mediaId,
                positionMs = player.currentPosition,
                durationMs = player.duration,
                playing = player.isPlaying,
                outputGeneration = output.generation,
                outputProgress = output.progress,
            ), nowMs)) return
        val counters = player.audioDecoderCounters
        counters?.ensureUpdated()
        val uid = player.currentMediaItem?.mediaId
        val resolution = uid?.let(TrackRegistry::resolved)
        Log.e(TAG, "output stalled uid=${playbackDiagnosticId(uid)} position=${player.currentPosition} " +
            "resource=${playbackDiagnosticId(resolution?.resourceId)} cache=${resolution?.fromCompleteCache} " +
            "generation=${output.generation} progress=${output.progress} sinkUs=${output.positionUs} " +
            "input=${counters?.queuedInputBufferCount} output=${counters?.renderedOutputBufferCount} " +
            formatDescription(player.audioFormat))
        val failure = AudioOutputProbe.Failure(output)
        pendingFailure = failure
        player.createMessage(probe.renderer).setType(AudioOutputProbe.CHECK_OUTPUT).setPayload(failure).send()
    }

    private fun reset() {
        pendingFailure?.cancelled = true
        pendingFailure = null
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

    companion object { private const val TAG = "PlaybackAudio" }
}
