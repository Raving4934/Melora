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

/** 字节和播放头均在播放线程采集，主线程只观察不可变快照。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioOutputProbe {
    data class Snapshot(val generation: Long = 0, val writtenBytes: Long = 0, val playedFrames: Long = 0)
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

    fun reset() {
        lastHead = null
        writtenBytes = 0
        snapshot = Snapshot(generation = snapshot.generation + 1)
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
        // 普通采样与报错前的最终复查都在播放线程读硬件，主线程不会查询或持有 AudioTrack。
        val output = probe.snapshot
        if (player.isPlaying && output.generation == previousOutput.generation && output.playedFrames > previousOutput.playedFrames) {
            val since = healthySinceMs ?: nowMs.also { healthySinceMs = it }
            // 跨项残留的少量尾音不算恢复，持续输出才清除连续失败计数。
            if (nowMs - since >= 1_000) player.currentMediaItem?.mediaId?.let(PlaybackController::onAudioOutputProgress)
        } else healthySinceMs = null
        previousOutput = output
        val uid = player.currentMediaItem?.mediaId
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
