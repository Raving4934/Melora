package com.leyu.melora.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 的客观校准：用确定性的多音测量RMS电平、频带倾向和极端输入边界（不是LUFS/主观试听）。
 *
 * 这里不读取预设的实现参数。这样测试校准结果，而不是把当前 EQ 曲线重新编码一遍；
 * 预设可以调整内部参数，测试契约仍保持在 PCM 输出行为层。
 */
class PcmEffectsVoicingTest {
    @Test
    fun broadbandMultisineRmsGainStaysWithinTwoDbAcrossSampleRates() {
        val frequencies = logSpacedFrequencies(count = 24, firstHz = 60.0, lastHz = 12_000.0)
        val sampleRates = listOf(44_100, 48_000, 96_000)

        for (sampleRate in sampleRates) {
            for (presetId in CALIBRATION_PRESET_IDS) {
                val gainDb = measureRmsGainDb(sampleRate, presetId, frequencies)
                val measurement = measurementLabel(sampleRate, presetId, frequencies)
                assertTrue("$measurement produced non-finite RMS gain: $gainDb", gainDb.isFinite())
                assertTrue(
                    "$measurement expected broadband RMS gain in [-2, +2] dB, measured $gainDb dB",
                    gainDb in -2.0..2.0,
                )
            }
        }
    }

    @Test
    fun bassAndSpeechKeepAtLeastThreeDbOfRelativeBandContrast() {
        val lowBand = doubleArrayOf(60.0, 75.0, 95.0, 120.0, 150.0, 190.0, 240.0)
        val voiceBand = doubleArrayOf(700.0, 900.0, 1_200.0, 1_600.0, 2_100.0, 2_800.0, 3_600.0)
        val sampleRate = 48_000

        // 频带整体能量对比，而不是要求每一个频点都只能升或只能降。
        val bassLowDb = measureRmsGainDb(sampleRate, "bass", lowBand)
        val bassVoiceDb = measureRmsGainDb(sampleRate, "bass", voiceBand)
        val speechLowDb = measureRmsGainDb(sampleRate, "speech", lowBand)
        val speechVoiceDb = measureRmsGainDb(sampleRate, "speech", voiceBand)

        assertBandContrast(
            label = "bass low-vs-voice",
            strongerBandDb = bassLowDb,
            weakerBandDb = bassVoiceDb,
        )
        assertBandContrast(
            label = "speech voice-vs-low",
            strongerBandDb = speechVoiceDb,
            weakerBandDb = speechLowDb,
        )

        // 两个预设的目标频带也应在相同测量基准上拉开，而不是靠总音量差异蒙混过关。
        assertBandContrast(
            label = "speech voice-vs-bass voice",
            strongerBandDb = speechVoiceDb,
            weakerBandDb = bassVoiceDb,
        )
        assertBandContrast(
            label = "bass low-vs-speech low",
            strongerBandDb = bassLowDb,
            weakerBandDb = speechLowDb,
        )
    }

    @Test
    fun popAndRockDifferInTheMidrangeNotOnlyInSubBass() {
        val popMid = measureRmsGainDb(48_000, "pop", doubleArrayOf(1000.0))
        val rockMid = measureRmsGainDb(48_000, "rock", doubleArrayOf(1000.0))
        val popAir = measureRmsGainDb(48_000, "pop", doubleArrayOf(8000.0))
        val rockAir = measureRmsGainDb(48_000, "rock", doubleArrayOf(8000.0))
        assertTrue("rock should emphasize guitar/body over pop: $rockMid vs $popMid", rockMid - popMid >= 2.5)
        assertTrue("pop should remain brighter than rock: $popAir vs $rockAir", popAir - rockAir >= 2.5)
    }

    @Test
    fun speechAndSingingVocalsHaveDifferentBodyAndPresence() {
        val vocalBody = measureRmsGainDb(48_000, "vocal", doubleArrayOf(1000.0))
        val speechBody = measureRmsGainDb(48_000, "speech", doubleArrayOf(1000.0))
        val vocalPresence = measureRmsGainDb(48_000, "vocal", doubleArrayOf(4000.0))
        val speechPresence = measureRmsGainDb(48_000, "speech", doubleArrayOf(4000.0))
        assertTrue("speech should emphasize word body: $speechBody vs $vocalBody", speechBody - vocalBody >= 2.0)
        assertTrue("singing should retain more upper presence: $vocalPresence vs $speechPresence", vocalPresence - speechPresence >= 2.0)
    }

    @Test
    fun bassAddsWarmthAboveTheSubBassBand() {
        val bassWarmth = measureRmsGainDb(48_000, "bass", doubleArrayOf(250.0))
        val popWarmth = measureRmsGainDb(48_000, "pop", doubleArrayOf(250.0))
        assertTrue("bass must not rely only on 30-60Hz: $bassWarmth vs $popWarmth", bassWarmth - popWarmth >= 2.5)
    }

    @Test
    fun everyPresetPairHasADifferentSpectralShapeAfterRemovingVolumeOffset() {
        val bands = doubleArrayOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        val responses = EFFECT_PRESET_IDS.associateWith { preset ->
            bands.map { hz -> measureRmsGainDb(48_000, preset, doubleArrayOf(hz)) }
        }
        for (i in EFFECT_PRESET_IDS.indices) for (j in i + 1 until EFFECT_PRESET_IDS.size) {
            val first = EFFECT_PRESET_IDS[i]; val second = EFFECT_PRESET_IDS[j]
            val differences = responses.getValue(first).zip(responses.getValue(second)) { a, b -> a - b }
            val offset = differences.average()
            val distance = kotlin.math.sqrt(differences.map { (it - offset) * (it - offset) }.average())
            // 工程防近重复门槛，不宣称这是所有设备/人的听觉可辨阈值。
            assertTrue("$first/$second volume-independent tone distance $distance dB", distance >= 1.0)
        }
    }

    @Test
    fun fullScaleAsymmetricStereoAndRapidPresetChangesStayFiniteAndBounded() {
        val sampleRate = 48_000
        val frames = 4_800
        val frequencies = logSpacedFrequencies(count = 18, firstHz = 60.0, lastHz = 12_000.0)
        val signal = FullScaleMultisine(sampleRate, frequencies, frames)
        val input = FloatArray(2)
        val output = FloatArray(2)
        val requestPeriod = sampleRate / 250 // 4 ms, shorter than the 50 ms bank crossfade.
        val burstPeriod = sampleRate / 400 // 2.5 ms high-amplitude polarity changes.

        for (initialPresetId in CALIBRATION_PRESET_IDS) {
            val engine = PcmEffectsEngine(sampleRate, 2, AudioEffects.preset(initialPresetId))
            var inputPeak = 0.0

            for (frame in 0 until frames) {
                if (frame % requestPeriod == 0) {
                    val nextPresetId = CALIBRATION_PRESET_IDS[(frame / requestPeriod) % CALIBRATION_PRESET_IDS.size]
                    engine.request(AudioEffects.preset(nextPresetId))
                }

                val polarity = if ((frame / burstPeriod) % 2 == 0) 1.0 else -1.0
                val left = polarity * signal.sample(frame)
                val right = 0.23 * signal.sample(frame, phaseOffset = 0.83)
                input[0] = left.toFloat()
                input[1] = right.toFloat()
                inputPeak = max(inputPeak, abs(left))

                engine.processFrame(input, output)
                for (channel in output.indices) {
                    val value = output[channel]
                    assertTrue(
                        "initial=$initialPresetId frame=$frame channel=$channel produced non-finite $value",
                        value.isFinite(),
                    )
                    assertTrue(
                        "initial=$initialPresetId frame=$frame channel=$channel exceeded ${OUTPUT_BOUND}FS: $value",
                        abs(value) <= OUTPUT_BOUND,
                    )
                }
            }

            assertTrue(
                "stress input was not full-scale enough for initial=$initialPresetId: peak=$inputPeak",
                inputPeak >= 0.95,
            )
        }
    }

    private fun assertBandContrast(label: String, strongerBandDb: Double, weakerBandDb: Double) {
        assertTrue(
            "$label has non-finite measurement: stronger=$strongerBandDb weaker=$weakerBandDb",
            strongerBandDb.isFinite() && weakerBandDb.isFinite(),
        )
        val contrastDb = strongerBandDb - weakerBandDb
        assertTrue(
            "$label expected at least ${MIN_BAND_CONTRAST_DB} dB, measured $contrastDb dB " +
                "(stronger=$strongerBandDb dB, weaker=$weakerBandDb dB)",
            contrastDb >= MIN_BAND_CONTRAST_DB,
        )
    }

    private fun measureRmsGainDb(
        sampleRate: Int,
        presetId: String,
        frequencies: DoubleArray,
    ): Double {
        val engine = PcmEffectsEngine(sampleRate, 1, AudioEffects.preset(presetId))
        val input = FloatArray(1)
        val output = FloatArray(1)
        val warmupFrames = sampleRate / 8
        val measuredFrames = sampleRate / 8
        val amplitudePerTone = SAFE_PEAK / frequencies.size
        var inputEnergy = 0.0
        var outputEnergy = 0.0

        for (frame in 0 until warmupFrames + measuredFrames) {
            val sample = fixedPhaseMultisineSample(
                frame = frame,
                sampleRate = sampleRate,
                frequencies = frequencies,
                amplitudePerTone = amplitudePerTone,
            )
            input[0] = sample.toFloat()
            engine.processFrame(input, output)
            if (frame >= warmupFrames) {
                inputEnergy += sample * sample
                val processed = output[0].toDouble()
                outputEnergy += processed * processed
            }
        }

        return 10.0 * log10(outputEnergy / inputEnergy)
    }

    private fun measurementLabel(sampleRate: Int, presetId: String, frequencies: DoubleArray): String =
        "preset=$presetId rate=${sampleRate}Hz tones=${frequencies.size} " +
            "range=${frequencies.first()}..${frequencies.last()}Hz " +
            "warmup=${sampleRate / 8}frames measure=${sampleRate / 8}frames"

    private class FullScaleMultisine(
        private val sampleRate: Int,
        private val frequencies: DoubleArray,
        frameCount: Int,
    ) {
        private val normalization: Double

        init {
            var peak = 0.0
            for (frame in 0 until frameCount) {
                peak = max(peak, abs(unscaledSample(frame)))
            }
            normalization = 0.98 / peak
        }

        fun sample(frame: Int, phaseOffset: Double = 0.0): Double =
            unscaledSample(frame, phaseOffset) * normalization

        private fun unscaledSample(frame: Int, phaseOffset: Double = 0.0): Double =
            fixedPhaseMultisineSample(
                frame = frame,
                sampleRate = sampleRate,
                frequencies = frequencies,
                amplitudePerTone = 1.0 / frequencies.size,
                phaseOffset = phaseOffset,
            )
    }

    companion object {
        private const val SAFE_PEAK = 0.42
        private const val OUTPUT_BOUND = 1f
        private const val MIN_BAND_CONTRAST_DB = 3.0

        // 六个可见预设 ID 固定写在测试中，防止遗漏或误改持久化ID。
        private val EFFECT_PRESET_IDS = listOf("pop", "vocal", "speech", "rock", "concert", "bass")
        private val CALIBRATION_PRESET_IDS = listOf("off") + EFFECT_PRESET_IDS
    }
}

private fun logSpacedFrequencies(count: Int, firstHz: Double, lastHz: Double): DoubleArray {
    require(count >= 2)
    val logSpan = ln(lastHz / firstHz)
    return DoubleArray(count) { index ->
        firstHz * exp(logSpan * index / (count - 1).toDouble())
    }
}

private fun fixedPhaseMultisineSample(
    frame: Int,
    sampleRate: Int,
    frequencies: DoubleArray,
    amplitudePerTone: Double,
    phaseOffset: Double = 0.0,
): Double {
    var sum = 0.0
    for (index in frequencies.indices) {
        val phase = 2.0 * PI * frequencies[index] * frame / sampleRate + index * 0.37 + phaseOffset
        sum += sin(phase)
    }
    return sum * amplitudePerTone
}
