package com.leyu.melora.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 即时解码唤醒与位置采样共用一条播放循环，不以关闭动态调度换取歌词刷新。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackRenderersFactory(
    context: Context,
    private val effects: PcmEffectsAudioProcessor,
) : DefaultRenderersFactory(context) {
    val outputProbe = AudioOutputProbe()
    private val outputRoute = AudioOutputRoute(AudioTrack::routedOutputTypes)
    val bluetoothOutput = outputRoute.bluetoothOutput

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // 只为API30音频补充异步队列，不改变可能存在的视频轨或其他API的SDK兼容策略。
        val audioCodecFactory = if (Build.VERSION.SDK_INT == 30) {
            DefaultMediaCodecAdapterFactory(context).forceEnableAsynchronous()
        } else getCodecAdapterFactory()
        // 应用只使用平台音频解码器，与原factory默认的EXTENSION_RENDERER_MODE_OFF一致。
        val renderer = object : MediaCodecAudioRenderer(
            context, audioCodecFactory, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, audioSink,
        ) {
            override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
                mediaPeriodId: MediaSource.MediaPeriodId) {
                outputProbe.reset(mediaPeriodId.periodUid)
                super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
            }

            override fun handleMessage(messageType: Int, message: Any?) {
                if (messageType != AudioOutputProbe.CHECK_OUTPUT) {
                    super.handleMessage(messageType, message)
                    return
                }
                val track = outputRoute.track
                outputProbe.sample(track?.playbackHeadPosition, track?.let { (it.sampleRate / 20).coerceAtLeast(1) } ?: 1)
                val failure = message as? AudioOutputProbe.Failure ?: return
                // 有效播放令牌由主线程暂停/切项时取消；renderer提前disabled本身可能就是故障，不能永久排除。
                if (outputProbe.stillStalled(failure)) {
                    // 这是当前播放项的健康错误；TYPE_RENDERER会被SDK归因到预读项并强制跳集。
                    throw ExoPlaybackException.createForUnexpected(
                        failure,
                        ERROR_CODE_AUDIO_OUTPUT_STALLED,
                    )
                }
            }

            override fun getDurationToProgressUs(
                positionUs: Long,
                elapsedRealtimeUs: Long,
                isOnBufferAvailableListenerRegistered: Boolean,
            ): Long = audioProgressIntervalUs(
                suggestedUs = super.getDurationToProgressUs(positionUs, elapsedRealtimeUs, isOnBufferAvailableListenerRegistered),
                started = state == Renderer.STATE_STARTED,
                ended = isEnded,
            )
        }
        outputProbe.renderer = renderer
        out.add(renderer)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val tracks = AudioTrackAudioOutputProvider.Builder(context).build()
        val outputs = object : ForwardingAudioOutputProvider(tracks) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val output = tracks.getAudioOutput(config)
                val track = output.audioTrack
                outputRoute.attach(track)
                outputProbe.reset()
                val routeListener = AudioRouting.OnRoutingChangedListener { outputRoute.refresh(track) }
                track.addOnRoutingChangedListener(routeListener, Handler(checkNotNull(Looper.myLooper())))
                return object : ForwardingAudioOutput(output) {
                    override fun play() {
                        super.play()
                        outputRoute.refresh(track)
                    }

                    override fun pause() {
                        super.pause()
                        outputRoute.refresh(track)
                    }

                    override fun stop() {
                        super.stop()
                        outputRoute.refresh(track)
                    }

                    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
                        val before = buffer.position()
                        val handled = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
                        outputProbe.wrote(buffer.position() - before)
                        return handled
                    }

                    override fun release() {
                        outputRoute.detach(track)
                        track.removeOnRoutingChangedListener(routeListener)
                        super.release()
                    }
                }
            }
        }
        val sink = DefaultAudioSink.Builder(context)
            // float直出/offload会跳过软件音效；保留原整数PCM和Sonic倍速路径。
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioProcessors(arrayOf(effects))
            .setAudioOutputProvider(outputs)
            .build()
        return object : ForwardingAudioSink(sink) {
            override fun getAudioCapabilities() = tracks.audioCapabilities

            override fun flush() {
                outputProbe.reset()
                super.flush()
            }

            override fun reset() {
                outputProbe.reset()
                super.reset()
            }

            override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
            override fun setOffloadMode(offloadMode: Int) = super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        }
    }
}

/** 持有播放线程当前的输出。旧轨释放或已入队的重路由回调不得覆盖新轨状态。 */
internal class AudioOutputRoute<T : Any>(private val readOutputTypes: (T) -> List<Int>) {
    var track: T? = null
        private set
    private val mutableBluetoothOutput = MutableStateFlow(false)
    val bluetoothOutput = mutableBluetoothOutput.asStateFlow()

    fun attach(track: T) {
        this.track = track
        mutableBluetoothOutput.value = false
    }

    fun refresh(track: T) {
        if (this.track === track) {
            mutableBluetoothOutput.value = readOutputTypes(track).any(::isBluetoothAudioOutputDevice)
        }
    }

    fun detach(track: T) {
        if (this.track === track) {
            this.track = null
            mutableBluetoothOutput.value = false
        }
    }
}

internal fun isBluetoothAudioOutputDevice(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_BLE_HEARING_AID,
    AudioDeviceInfo.TYPE_HEARING_AID -> true
    else -> false
}

private fun AudioTrack.routedOutputTypes(): List<Int> = when {
    playState != AudioTrack.PLAYSTATE_PLAYING -> emptyList()
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> routedDevices.map { it.type }
    else -> routedDevice?.let { listOf(it.type) }.orEmpty()
}

/** 保留更早的即时工作；暂停/结束遵循SDK休眠策略，只有播放中限制位置采样间隔。 */
internal fun audioProgressIntervalUs(suggestedUs: Long, started: Boolean, ended: Boolean): Long =
    if (started && !ended) minOf(suggestedUs, 10_000L) else suggestedUs
