package com.leyu.melora.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PcmEffectsEngineTest {
    @Test
    fun speechAndBassActuallyShapeTheirIntendedFrequencyRanges() {
        fun gain(id: String, hz: Double): Double {
            val engine = PcmEffectsEngine(48_000, 1, AudioEffects.preset(id))
            val input = FloatArray(1); val output = FloatArray(1)
            var inEnergy = 0.0; var outEnergy = 0.0
            repeat(24_000) { n ->
                input[0] = (0.2 * sin(n * 2 * PI * hz / 48_000)).toFloat()
                engine.processFrame(input, output)
                if (n >= 8_000) {
                    inEnergy += input[0].toDouble() * input[0]
                    outEnergy += output[0].toDouble() * output[0]
                }
            }
            return kotlin.math.sqrt(outEnergy / inEnergy)
        }
        assertEquals(1.0, gain("off", 2000.0), 0.000001)
        assertTrue(gain("speech", 2000.0) > gain("speech", 62.0) * 1.5)
        assertTrue(gain("speech", 2000.0) > gain("speech", 8000.0) * 1.15)
        assertTrue(gain("bass", 62.0) > gain("bass", 2000.0) * 1.5)
    }

    @Test
    fun stableOffPreservesEveryValidSampleWithoutDelay() {
        val engine = PcmEffectsEngine(48_000, 2)
        val input = floatArrayOf(-1f, 1f)
        val output = FloatArray(2)
        for (sample in listOf(-1f, -0.9999695f, -0.25f, -0.0f, 0f, 0.25f, 0.9999695f, 1f)) {
            input[0] = sample; input[1] = -sample
            engine.processFrame(input, output)
            assertArrayEquals(input, output, 0f)
        }
        assertTrue(engine.isBypassed)
    }

    @Test
    fun everyPresetPairConvergesAndHasFiniteBoundedOutput() {
        val input = FloatArray(2)
        val output = FloatArray(2)
        for (from in AudioEffects.presets) for (to in AudioEffects.presets) {
            val engine = PcmEffectsEngine(48_000, 2, from)
            repeat(2_400) { n ->
                input[0] = (0.75 * sin(n * 2 * PI * 173 / 48_000)).toFloat(); input[1] = -input[0]
                engine.processFrame(input, output)
            }
            engine.request(to)
            repeat(3_000) { n ->
                input[0] = (0.95 * sin(n * 2 * PI * 997 / 48_000)).toFloat(); input[1] = input[0]
                engine.processFrame(input, output)
                assertTrue("${from.id}->${to.id}: ${output.toList()}", output.all { it.isFinite() && abs(it) <= 1f })
            }
            assertEquals("${from.id}->${to.id}", to.id, engine.settledPresetId)
        }
    }

    @Test
    fun rapidRequestsKeepOnlyLatestTargetWithoutRestartingTheRunningTransition() {
        val engine = PcmEffectsEngine(48_000, 2)
        val input = floatArrayOf(0.2f, -0.2f)
        val output = FloatArray(2)
        repeat(40) { request ->
            engine.request(AudioEffects.presets[request % AudioEffects.presets.size])
            repeat(32) { engine.processFrame(input, output) }
        }
        engine.request(AudioEffects.preset("bass"))
        repeat(4_804) { engine.processFrame(input, output) }
        assertEquals("bass", engine.settledPresetId)
    }

    @Test
    fun correlatedFlatChainsDoNotGetAnEqualPowerVolumeBump() {
        val a = AudioEffects.Preset("flat-a", "", "")
        val b = AudioEffects.Preset("flat-b", "", "")
        val engine = PcmEffectsEngine(48_000, 1, a)
        val input = floatArrayOf(0.25f)
        val output = FloatArray(1)
        engine.request(b)
        repeat(2_500) {
            engine.processFrame(input, output)
            assertEquals(0.25f, output[0], 0.0000001f)
        }
    }

    @Test
    fun sineRapidSwitchingHasNoIsolatedLargeJumpOrGainSpike() {
        val engine = PcmEffectsEngine(48_000, 2)
        val input = FloatArray(2)
        val output = FloatArray(2)
        // 调音允许目标频段提升；防突刺应对照各稳态预设，而不是强迫所有预设只能衰减。
        var steadyPeak = 0f
        for (preset in AudioEffects.presets) {
            val reference = PcmEffectsEngine(48_000, 2, preset)
            repeat(12_000) { n ->
                input.fill((0.5 * sin(n * 2 * PI * 220 / 48_000)).toFloat())
                reference.processFrame(input, output)
                if (n > 8_000) steadyPeak = maxOf(steadyPeak, abs(output[0]))
            }
        }
        var previous = 0f
        var peak = 0f
        var jump = 0f
        repeat(24_000) { n ->
            if (n % 900 == 0) engine.request(AudioEffects.presets[(n / 900) % 7])
            input[0] = (0.5 * sin(n * 2 * PI * 220 / 48_000)).toFloat(); input[1] = input[0]
            engine.processFrame(input, output)
            peak = maxOf(peak, abs(output[0])); jump = maxOf(jump, abs(output[0] - previous))
            previous = output[0]
        }
        assertTrue("unexpected peak $peak", peak <= steadyPeak * 1.04f)
        assertTrue("isolated sample jump $jump", jump < 0.04f)
    }

    @Test
    fun boostedFullLevelTonesRemainSinusoidalInsteadOfHavingFlattenedPeaks() {
        for ((id, hz) in listOf("vocal" to 2000.0, "speech" to 2000.0, "bass" to 125.0)) {
            val engine = PcmEffectsEngine(48_000, 1, AudioEffects.preset(id))
            val input = FloatArray(1); val output = FloatArray(1)
            var sine = 0.0; var cosine = 0.0; var energy = 0.0
            repeat(48_000) { n ->
                val angle = n * 2 * PI * hz / 48_000
                input[0] = (0.9 * sin(angle)).toFloat()
                engine.processFrame(input, output)
                assertTrue(abs(output[0]) <= 0.981f)
                if (n >= 24_000) {
                    sine += output[0] * sin(angle)
                    cosine += output[0] * kotlin.math.cos(angle)
                    energy += output[0].toDouble() * output[0]
                }
            }
            val fundamentalEnergy = 2.0 * (sine * sine + cosine * cosine) / 24_000
            val residual = kotlin.math.sqrt(maxOf(0.0, energy - fundamentalEnergy) / energy)
            assertTrue("$id steady-state harmonic/noise residual $residual", residual < 0.005)
        }
    }

    @Test
    fun peakProtectionLinksChannelsWithoutMovingStereoBalance() {
        val engine = PcmEffectsEngine(48_000, 2, AudioEffects.preset("vocal"))
        val input = FloatArray(2); val output = FloatArray(2)
        repeat(12_000) { n ->
            input[0] = (0.99 * sin(n * 2 * PI * 2000 / 48_000)).toFloat()
            input[1] = input[0] * 0.2f
            engine.processFrame(input, output)
            assertEquals(output[0] * 0.2f, output[1], 0.000001f)
            assertTrue(output.all { abs(it) <= 0.981f })
        }
    }

    @Test
    fun silenceStaysSilentAcrossFormatsChannelsAndPresets() {
        for (rate in listOf(4_000, 8_000, 44_100, 48_000, 96_000, 192_000, 384_000)) for (channels in listOf(1, 2, 8)) {
            val engine = PcmEffectsEngine(rate, channels)
            val input = FloatArray(channels); val output = FloatArray(channels)
            for (preset in AudioEffects.presets) {
                engine.request(preset)
                repeat(rate / 16) { engine.processFrame(input, output); assertTrue(output.all { it == 0f }) }
                assertEquals(preset.id, engine.settledPresetId)
            }
        }
    }

    @Test
    fun concertHasRealDelayedTailAndResetRemovesOldTail() {
        val preset = AudioEffects.preset("concert")
        val engine = PcmEffectsEngine(48_000, 2, preset)
        val input = floatArrayOf(0.8f, 0.8f); val output = FloatArray(2)
        engine.processFrame(input, output)
        val directEnergy = output.sumOf { it.toDouble() * it }
        input.fill(0f)
        var tailEnergy = 0.0
        repeat(8_000) { n ->
            engine.processFrame(input, output)
            if (n > 2_000) tailEnergy += output.sumOf { it.toDouble() * it }
        }
        assertTrue("concert must not be an EQ-only placeholder", tailEnergy > 1e-7)
        assertTrue("concert late tail is too faint relative to direct sound", tailEnergy / directEnergy >= 0.05)
        engine.reset(preset)
        repeat(8_000) { engine.processFrame(input, output); assertTrue(output.all { it == 0f }) }
        assertEquals("concert", engine.settledPresetId)
    }

    @Test
    fun offTransitionReachesExactUnityWithoutAnEndpointJump() {
        val engine = PcmEffectsEngine(48_000, 1, AudioEffects.preset("speech"))
        val input = floatArrayOf(0.5f); val output = FloatArray(1)
        repeat(8_000) { engine.processFrame(input, output) }
        engine.request(AudioEffects.preset("off"))
        var previous = output[0]; var jump = 0f
        repeat(3_000) {
            engine.processFrame(input, output)
            jump = maxOf(jump, abs(output[0] - previous)); previous = output[0]
        }
        assertTrue(jump < 0.002f)
        assertEquals(0.5f, output[0], 0f)
        assertTrue(engine.isBypassed)
    }

    @Test
    fun nonFiniteAndOverRangeInputCannotPoisonSubsequentAudio() {
        for (preset in AudioEffects.presets) {
            val engine = PcmEffectsEngine(48_000, 1, preset); val output = FloatArray(1)
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE)) {
                engine.processFrame(floatArrayOf(bad), output)
                assertTrue(output[0].isFinite() && abs(output[0]) <= 1f)
            }
            val input = floatArrayOf(0.2f)
            repeat(4_000) { engine.processFrame(input, output); assertTrue(output[0].isFinite()) }
        }
    }

    @Test
    fun nonConcertChannelsDoNotLeakAndSamePresetDoesNotResetHistory() {
        val preset = AudioEffects.preset("vocal")
        val a = PcmEffectsEngine(44_100, 8, preset); val b = PcmEffectsEngine(44_100, 8, preset)
        val input = FloatArray(8); val outA = FloatArray(8); val outB = FloatArray(8)
        repeat(5_000) { n ->
            input[3] = (0.3 * sin(n * 2 * PI * 97 / 44_100)).toFloat()
            if (n % 32 == 0) a.request(preset)
            a.processFrame(input, outA); b.processFrame(input, outB)
            assertArrayEquals(outB, outA, 0f)
            assertTrue(outA.indices.all { it == 3 || outA[it] == 0f })
        }
    }
}
