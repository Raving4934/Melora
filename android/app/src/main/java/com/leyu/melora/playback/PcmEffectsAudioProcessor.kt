package com.leyu.melora.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** 常驻Media3 PCM节点：主线程只发布目标，全部滤波/淡化状态留在音频线程。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PcmEffectsAudioProcessor(
    initialPreset: AudioEffects.Preset = AudioEffects.preset(AudioEffects.OFF),
    private val report: (AudioEffects.State) -> Unit = {},
) : BaseAudioProcessor() {
    private class Selection(val preset: AudioEffects.Preset)
    private val requestLock = Any()
    @Volatile private var selection = Selection(initialPreset)
    @Volatile private var configured = false
    private var lastReported: AudioEffects.State? = null
    private var engine: PcmEffectsEngine? = null
    private var inputFrame = FloatArray(0)
    private var outputFrame = FloatArray(0)

    fun select(id: String) = synchronized(requestLock) {
        val preset = AudioEffects.preset(id)
        if (preset.id == selection.preset.id && lastReported?.phase in
            listOf(AudioEffects.Phase.Applied, AudioEffects.Phase.Applying)) return@synchronized
        selection = Selection(preset)
        publish(selection, if (configured) AudioEffects.Phase.Applying else AudioEffects.Phase.Idle)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.sampleRate !in 4_000..384_000 || inputAudioFormat.channelCount !in 1..8) {
            publish(selection, AudioEffects.Phase.Failed, "不支持的PCM格式")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 即使off也保持节点活动，预设热切不需要重建AudioSink/flush，off输出走原始字节复制。
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Media3 Pipeline在reset时先flush；尚未配置/配置失败时不能用无效格式建立DSP。
        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET || inputAudioFormat != outputAudioFormat) {
            onReset()
            return
        }
        val request = selection
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val current = engine
        if (current != null && current.sampleRate == rate && current.channelCount == channels) {
            current.reset(request.preset)
        } else {
            engine = PcmEffectsEngine(rate, channels, request.preset)
            inputFrame = FloatArray(channels)
            outputFrame = FloatArray(channels)
        }
        configured = true
        publish(request, AudioEffects.Phase.Idle)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val request = selection
        try {
            val processor = checkNotNull(engine) { "PCM处理器尚未配置" }
            check(inputBuffer.remaining() % inputAudioFormat.bytesPerFrame == 0) { "PCM帧不完整" }
            processor.request(request.preset)
            val output = replaceOutputBuffer(inputBuffer.remaining())
            if (processor.isBypassed) {
                output.put(inputBuffer)
            } else {
                inputBuffer.order(ByteOrder.nativeOrder())
                val floatPcm = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                while (inputBuffer.hasRemaining()) {
                    for (channel in inputFrame.indices) {
                        inputFrame[channel] = if (floatPcm) inputBuffer.float else inputBuffer.short / 32768f
                    }
                    processor.processFrame(inputFrame, outputFrame)
                    for (sample in outputFrame) {
                        if (floatPcm) output.putFloat(sample)
                        else output.putShort((sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                    }
                }
            }
            output.flip()
            publish(request, if (processor.settledPresetId == request.preset.id) {
                AudioEffects.Phase.Applied
            } else AudioEffects.Phase.Applying)
        } catch (error: RuntimeException) {
            publish(request, AudioEffects.Phase.Failed, error.message ?: "PCM音效处理失败")
            throw error
        }
    }

    override fun onReset() {
        configured = false
        engine = null
        inputFrame = FloatArray(0)
        outputFrame = FloatArray(0)
        publish(selection, AudioEffects.Phase.Idle)
    }

    private fun publish(request: Selection, phase: AudioEffects.Phase, message: String? = null) = synchronized(requestLock) {
        // select与状态回报共用短锁，旧buffer完成不得把新请求覆盖为“已启用”。
        if (selection !== request) return@synchronized
        val next = AudioEffects.State(request.preset.id, phase, message)
        if (lastReported != next) {
            lastReported = next
            report(next)
        }
    }
}
