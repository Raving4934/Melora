package com.leyu.melora.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 单一软件音效链，仅由音频线程访问。系数在一段转场中保持固定，交叉淡化的是输出，
 * 不是滤波器系数。两组状态/延迟线在格式建立时一次分配，快速请求只覆盖下一个目标。
 */
internal class PcmEffectsEngine(
    val sampleRate: Int,
    val channelCount: Int,
    initialPreset: AudioEffects.Preset = AudioEffects.preset(AudioEffects.OFF),
) {
    init {
        require(sampleRate in 4_000..384_000)
        require(channelCount in 1..8)
    }

    private var current = Bank(sampleRate, channelCount)
    private var next = Bank(sampleRate, channelCount)
    private val currentFrame = FloatArray(channelCount)
    private val nextFrame = FloatArray(channelCount)
    private val transitionFrames = (sampleRate * 0.050).toInt().coerceAtLeast(2)
    private var transitionPosition = -1
    private var requested = initialPreset

    init { current.configure(initialPreset) }

    val settledPresetId: String?
        get() = current.id.takeIf { transitionPosition < 0 && it == requested.id }
    val isBypassed: Boolean get() = settledPresetId == AudioEffects.OFF

    fun request(preset: AudioEffects.Preset) { requested = preset }

    /** seek/真实格式变化才清理状态；普通预设点击不调用reset/flush。 */
    fun reset(preset: AudioEffects.Preset) {
        requested = preset
        current.configure(preset)
        transitionPosition = -1
    }

    fun processFrame(input: FloatArray, output: FloatArray) {
        require(input.size >= channelCount && output.size >= channelCount)
        if (transitionPosition < 0 && current.id != requested.id) {
            next.configure(requested)
            transitionPosition = 0
        }
        current.process(input, currentFrame)
        if (transitionPosition < 0) {
            currentFrame.copyInto(output, endIndex = channelCount)
            return
        }
        next.process(input, nextFrame)
        val t = transitionPosition.toDouble() / (transitionFrames - 1)
        // 等幅而非等功率：同源、相关的两份音频相加不产生中点+3dB。
        val mix = t * t * (3.0 - 2.0 * t)
        for (channel in 0 until channelCount) {
            output[channel] = (currentFrame[channel] * (1.0 - mix) + nextFrame[channel] * mix).toFloat()
        }
        transitionPosition++
        if (transitionPosition == transitionFrames) {
            val previous = current
            current = next
            next = previous
            transitionPosition = -1
        }
    }

    private class Bank(sampleRate: Int, private val channels: Int) {
        private val rate = sampleRate.toDouble()
        private val filters = Array(9) { Shelf(channels) }
        private val room = Room(sampleRate, channels)
        private val wetFrame = FloatArray(channels)
        private var filterCount = 0
        private var gain = 1.0
        private var concert = false
        private val peakRelease = 1.0 - exp(-1.0 / (sampleRate * 0.12))
        private val peakHoldFrames = (sampleRate * 0.015).toInt()
        private var peakGain = 1.0
        private var peakHold = 0
        var id: String = AudioEffects.OFF
            private set

        fun configure(preset: AudioEffects.Preset) {
            id = preset.id
            concert = preset.concert
            filterCount = 0
            peakGain = 1.0
            peakHold = 0
            room.clear()
            if (id == AudioEffects.OFF) { gain = 1.0; return }
            val curve = preset.curve
            // 相邻频点之间用架式滤波渐变，只实现一条音色曲线。
            gain = 10.0.pow((curve.firstOrNull()?.second ?: 0f) / 20.0)
            for (i in 1 until curve.size) {
                val frequency = sqrt(curve[i - 1].first.toDouble() * curve[i].first)
                val delta = (curve[i].second - curve[i - 1].second).toDouble()
                if (frequency < rate * 0.45 && abs(delta) > 0.0001) {
                    filters[filterCount++].configure(rate, frequency, delta)
                }
            }
            // 固定参考频谱的能量校准，不跟随歌曲起伏做AGC，也不再次扣除最高频段增益。
            // 每个对数频带等权，近似宽带音乐的谱分布；不是LUFS测量，单频响度仍应随EQ改变。
            var power = 0.0
            val upperHz = minOf(12_000.0, rate * 0.45)
            for (i in 0 until 64) {
                val hz = 60.0 * (upperHz / 60.0).pow((i + 0.5) / 64.0)
                val omega = 2.0 * PI * hz / rate
                val real = cos(omega)
                val imaginary = sin(omega)
                var response = gain * gain
                for (f in 0 until filterCount) response *= filters[f].powerAt(real, imaginary)
                power += response
            }
            gain /= sqrt(power / 64.0)
        }

        fun process(input: FloatArray, output: FloatArray) {
            for (channel in 0 until channels) {
                val raw = input[channel]
                var sample = if (raw.isFinite()) raw.toDouble().coerceIn(-1.0, 1.0) else 0.0
                if (id != AudioEffects.OFF) {
                    sample *= gain
                    for (i in 0 until filterCount) sample = filters[i].process(sample, channel)
                }
                output[channel] = sample.toFloat()
            }
            if (concert) {
                room.process(output, wetFrame)
                for (channel in 0 until channels) {
                    // 保留直接声，混响左右独立延迟形成空间，不再通过压低中置人声换宽度。
                    output[channel] = (output[channel] + wetFrame[channel] * 0.45f) * 0.9119215f
                }
            }
            if (id != AudioEffects.OFF) {
                // EQ提升后的峰值用声道联动限幅，不逐采样切平波峰，也不按RMS自动抬高安静段。
                // 快速衰减 + 短保持 + 平滑释放，状态属于bank并参与已有交叉淡化；不引入延迟。
                var peak = 0.0
                for (channel in 0 until channels) {
                    if (!output[channel].isFinite()) output[channel] = 0f
                    peak = maxOf(peak, abs(output[channel].toDouble()))
                }
                val required = if (peak > 0.98) 0.98 / peak else 1.0
                if (required <= peakGain) {
                    peakGain = required
                    peakHold = peakHoldFrames
                } else if (peakHold > 0) {
                    peakHold--
                } else {
                    peakGain += (1.0 - peakGain) * peakRelease
                }
                for (channel in 0 until channels) output[channel] = (output[channel] * peakGain).toFloat()
            }
        }
    }

    /** RBJ Audio EQ Cookbook架式滤波；固定系数的direct-form-II转置，各声道独立状态。 */
    private class Shelf(channels: Int) {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)

        fun configure(rate: Double, frequency: Double, db: Double) {
            val a = 10.0.pow(db / 40.0)
            val omega = 2.0 * PI * frequency / rate
            val c = cos(omega)
            val beta = sqrt(a) * sin(omega) * sqrt((a + 1.0 / a) * (1.0 / 0.8 - 1.0) + 2.0)
            val plus = a + 1.0
            val minus = a - 1.0
            val d = plus - minus * c + beta
            b0 = a * (plus + minus * c + beta) / d
            b1 = -2.0 * a * (minus + plus * c) / d
            b2 = a * (plus + minus * c - beta) / d
            a1 = 2.0 * (minus - plus * c) / d
            a2 = (plus - minus * c - beta) / d
            z1.fill(0.0)
            z2.fill(0.0)
        }

        fun powerAt(cosOmega: Double, sinOmega: Double): Double {
            // 用复数模平方，避免低频/高采样率时展开多项式的大数相减精度损失。
            val cosDouble = 2.0 * cosOmega * cosOmega - 1.0
            val sinDouble = 2.0 * sinOmega * cosOmega
            val nr = b0 + b1 * cosOmega + b2 * cosDouble
            val ni = b1 * sinOmega + b2 * sinDouble
            val dr = 1.0 + a1 * cosOmega + a2 * cosDouble
            val di = a1 * sinOmega + a2 * sinDouble
            return (nr * nr + ni * ni) / (dr * dr + di * di)
        }

        fun process(input: Double, channel: Int): Double {
            val output = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * output + z2[channel]
            z2[channel] = b2 * input - a2 * output
            // 极小尾数归零，避免长期静音时浮点次正规数拖慢音频线程。
            if (abs(z1[channel]) < 1e-24) z1[channel] = 0.0
            if (abs(z2[channel]) < 1e-24) z2[channel] = 0.0
            return output
        }
    }

    /** 四路阻尼反馈梳状线+两级全通扩散；内部归一化，不依赖系统aux会话。 */
    private class Room(sampleRate: Int, private val channels: Int) {
        private val feedback = 0.82
        // 四路梳状线按脉冲能量归一化；旧(1-feedback)/4仅对DC归一化，会把尾音压得过低。
        private val combGain = sqrt(1.0 - feedback * feedback) / 2.0
        private val damping = 1.0 - exp(-2.0 * PI * 4_000.0 / sampleRate)
        private val delays = arrayOf(0.0297, 0.0371, 0.0411, 0.0437, 0.0050, 0.0017)
        private val buffers = Array(channels * delays.size) { index ->
            val line = index % delays.size
            val channel = index / delays.size
            FloatArray(((delays[line] + (channel % 2) * 0.0007) * sampleRate).toInt().coerceAtLeast(1))
        }
        private val positions = IntArray(buffers.size)
        private val damped = DoubleArray(channels * 4)

        fun clear() {
            buffers.forEach { it.fill(0f) }
            positions.fill(0)
            damped.fill(0.0)
        }

        fun process(input: FloatArray, output: FloatArray) {
            for (channel in 0 until channels) {
                var sum = 0.0
                for (line in 0..3) {
                    val index = channel * 6 + line
                    val position = positions[index]
                    val delayed = buffers[index][position].toDouble()
                    val state = channel * 4 + line
                    damped[state] += damping * (delayed - damped[state])
                    buffers[index][position] = (input[channel] + feedback * damped[state]).toFloat()
                    positions[index] = (position + 1) % buffers[index].size
                    sum += delayed * combGain
                }
                for (line in 4..5) {
                    val index = channel * 6 + line
                    val position = positions[index]
                    val delayed = buffers[index][position].toDouble()
                    val value = delayed - 0.5 * sum
                    buffers[index][position] = (sum + 0.5 * value).toFloat()
                    positions[index] = (position + 1) % buffers[index].size
                    sum = value
                }
                output[channel] = sum.toFloat()
            }
        }
    }
}
