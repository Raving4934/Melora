package com.leyu.melora.playback

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** 即时解码唤醒与位置采样共用一条播放循环，不以关闭动态调度换取歌词刷新。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackRenderersFactory(
    context: Context,
    private val effects: PcmEffectsAudioProcessor,
) : DefaultRenderersFactory(context) {
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
        out.add(object : MediaCodecAudioRenderer(
            context, audioCodecFactory, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, audioSink,
        ) {
            override fun getDurationToProgressUs(
                positionUs: Long,
                elapsedRealtimeUs: Long,
                isOnBufferAvailableListenerRegistered: Boolean,
            ): Long = audioProgressIntervalUs(
                suggestedUs = super.getDurationToProgressUs(positionUs, elapsedRealtimeUs, isOnBufferAvailableListenerRegistered),
                started = state == Renderer.STATE_STARTED,
                ended = isEnded,
            )
        })
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = object : ForwardingAudioSink(
        DefaultAudioSink.Builder(context)
            // float直出/offload会跳过软件音效；保留原整数PCM和Sonic倍速路径。
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

/** 保留更早的即时工作；暂停/结束遵循SDK休眠策略，只有播放中限制位置采样间隔。 */
internal fun audioProgressIntervalUs(suggestedUs: Long, started: Boolean, ended: Boolean): Long =
    if (started && !ended) minOf(suggestedUs, 10_000L) else suggestedUs
