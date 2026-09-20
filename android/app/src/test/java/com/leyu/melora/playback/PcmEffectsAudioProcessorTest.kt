package com.leyu.melora.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessingPipeline
import com.google.common.collect.ImmutableList
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class PcmEffectsAudioProcessorTest {
    private fun configured(
        preset: String = AudioEffects.OFF,
        encoding: Int = C.ENCODING_PCM_16BIT,
        channels: Int = 2,
        rate: Int = 48_000,
        report: (AudioEffects.State) -> Unit = {},
    ): PcmEffectsAudioProcessor = PcmEffectsAudioProcessor(AudioEffects.preset(preset), report).also {
        val format = AudioProcessor.AudioFormat(rate, channels, encoding)
        assertEquals(format, it.configure(format))
        assertTrue("off must remain active for hot switching", it.isActive)
        it.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun pcm(frames: Int, channels: Int = 2, value: Short = 8192): ByteBuffer =
        ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * channels) { putShort(value) }
            flip()
        }

    private fun compositePcm(
        frames: Int = 4_608,
        channels: Int = 2,
        rate: Int = 48_000,
    ): ByteBuffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder()).apply {
        repeat(frames) { frame ->
            val left = compositeSample(frame, rate, 0.0)
            val right = compositeSample(frame, rate, 0.37)
            repeat(channels) { channel ->
                val sample = if (channel % 2 == 0) left else right
                putShort((sample * 28_000.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        flip()
    }

    private fun compositeSample(frame: Int, rate: Int, phaseOffset: Double): Double =
        0.18 * sin(2.0 * PI * 500.0 * frame / rate + phaseOffset) +
            0.20 * sin(2.0 * PI * 2_000.0 * frame / rate + phaseOffset) +
            0.12 * sin(2.0 * PI * 8_000.0 * frame / rate + phaseOffset)

    private fun ByteBuffer.bytes() = ByteArray(remaining()).also { get(it) }

    private fun drainPipeline(pipeline: AudioProcessingPipeline, output: ByteArrayOutputStream) {
        while (true) {
            val buffer = pipeline.output
            if (!buffer.hasRemaining()) return
            output.write(buffer.bytes())
        }
    }

    private fun feedPipeline(pipeline: AudioProcessingPipeline, input: ByteBuffer): ByteArray =
        ByteArrayOutputStream().also {
            pipeline.queueInput(input)
            drainPipeline(pipeline, it)
        }.toByteArray()

    private fun finishPipeline(pipeline: AudioProcessingPipeline): ByteArray =
        ByteArrayOutputStream().also {
            pipeline.queueEndOfStream()
            repeat(16) { _ ->
                if (!pipeline.isEnded) drainPipeline(pipeline, it)
            }
            assertTrue("pipeline did not finish EOS after draining", pipeline.isEnded)
        }.toByteArray()

    private fun feedSample(pipeline: AudioProcessingPipeline, sample: ByteBuffer): ByteArray =
        feedPipeline(pipeline, sample.duplicate().order(ByteOrder.nativeOrder()))

    private fun stablePresetOutput(
        processor: PcmEffectsAudioProcessor,
        pipeline: AudioProcessingPipeline,
        sample: ByteBuffer,
        preset: String,
    ): ByteArray {
        processor.select(preset)
        feedSample(pipeline, sample)
        return feedSample(pipeline, sample)
    }

    private fun componentDb(
        bytes: ByteArray,
        frequency: Double,
        rate: Int = 48_000,
        channels: Int = 2,
    ): Double {
        val frames = bytes.size / (channels * 2)
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        var sine = 0.0
        var cosine = 0.0
        for (frame in 0 until frames) {
            val value = samples.getShort(frame * channels * 2).toDouble() / Short.MAX_VALUE
            val phase = 2.0 * PI * frequency * frame / rate
            sine += value * sin(phase)
            cosine += value * kotlin.math.cos(phase)
        }
        val amplitude = 2.0 * sqrt(sine * sine + cosine * cosine) / frames
        return 20.0 * log10(amplitude.coerceAtLeast(1.0e-12))
    }

    @Test
    fun offCopiesOnlyRemainingBytesExactlyForBothEncodings() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val processor = configured(encoding = encoding)
            val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 32 else 16
            val input = ByteBuffer.allocateDirect(bytes + 8).order(ByteOrder.nativeOrder())
            input.putInt(0x12345678)
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (sample in floatArrayOf(-1f, -0f, 0f, .25f, -.25f, 1f, .9999695f, -.9999695f)) input.putFloat(sample)
            } else {
                for (sample in shortArrayOf(Short.MIN_VALUE, -1, 0, 1, 8192, -8192, Short.MAX_VALUE, 19)) input.putShort(sample)
            }
            input.flip(); input.position(4)
            val expected = input.duplicate().bytes()
            processor.queueInput(input)
            assertFalse(input.hasRemaining())
            assertArrayEquals(expected, processor.output.bytes())
            assertFalse(processor.output.hasRemaining())
        }
    }

    @Test
    fun selectionIsNotAppliedUntilPcmPassesAndRapidRequestsConvergeToLatest() {
        val states = mutableListOf<AudioEffects.State>()
        val processor = configured(report = states::add)
        assertEquals(AudioEffects.Phase.Idle, states.last().phase)
        processor.select("rock")
        processor.queueInput(pcm(200)); processor.output
        assertEquals(AudioEffects.Phase.Applying, states.last().phase)
        processor.select("concert")
        processor.select("speech")
        assertEquals("speech", states.last().presetId)
        assertEquals(AudioEffects.Phase.Applying, states.last().phase)
        repeat(30) { processor.queueInput(pcm(200)); processor.output }
        assertEquals(AudioEffects.State("speech", AudioEffects.Phase.Applied), states.last())
        val reports = states.size
        processor.select("speech")
        processor.queueInput(pcm(200)); processor.output
        assertEquals("stable state should not churn UI flows", reports, states.size)
    }

    @Test
    fun bufferBoundariesDoNotChangeProcessedAudio() {
        val all = configured(preset = "concert")
        val chunked = configured(preset = "concert")
        all.select("bass"); chunked.select("bass")
        val source = pcm(7_000).apply {
            for (i in 0 until capacity() / 2) putShort(i * 2, ((i * 7919) % 32768 - 16384).toShort())
        }
        all.queueInput(source.duplicate().order(ByteOrder.nativeOrder()))
        val expected = all.output.bytes()
        val actual = ByteBuffer.allocate(expected.size)
        while (source.hasRemaining()) {
            val count = minOf(source.remaining(), 137 * 4)
            val part = source.slice().order(ByteOrder.nativeOrder()).apply { limit(count) }
            chunked.queueInput(part)
            actual.put(chunked.output)
            source.position(source.position() + count)
        }
        assertArrayEquals(expected, actual.array())
    }

    @Test
    fun seekFlushDropsConcertTailWithoutRecreatingProcessor() {
        val processor = configured(preset = "concert")
        processor.queueInput(pcm(300, value = 20_000)); processor.output
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm(8_000, value = 0))
        assertTrue(processor.output.bytes().all { it == 0.toByte() })
        assertTrue(processor.isActive)
    }

    @Test
    fun pendingFormatDoesNotAlterOldStreamBeforeFlush() {
        val processor = configured(preset = "vocal")
        val reference = configured(preset = "vocal")
        processor.configure(AudioProcessor.AudioFormat(96_000, 1, C.ENCODING_PCM_FLOAT))
        processor.queueInput(pcm(300)); reference.queueInput(pcm(300))
        assertArrayEquals(reference.output.bytes(), processor.output.bytes())
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val floatInput = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder()).apply {
            repeat(1024) { putFloat(.2f) }; flip()
        }
        processor.queueInput(floatInput)
        val output = processor.output
        assertEquals(4096, output.remaining())
        while (output.hasRemaining()) assertTrue(output.float.isFinite())
    }

    @Test
    fun floatProcessingHandlesInvalidSamplesWithoutPoisoningLaterFrames() {
        val processor = configured(preset = "bass", encoding = C.ENCODING_PCM_FLOAT, channels = 1)
        val input = ByteBuffer.allocateDirect(400).order(ByteOrder.nativeOrder()).apply {
            putFloat(Float.NaN); putFloat(Float.POSITIVE_INFINITY); putFloat(Float.NEGATIVE_INFINITY)
            repeat(97) { putFloat(.1f) }; flip()
        }
        processor.queueInput(input)
        val output = processor.output
        while (output.hasRemaining()) assertTrue(output.float.let { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun lowRateMonoSpeechAndHighRateMultichannelKeepFrameCounts() {
        for (rate in listOf(4_000, 384_000)) for (channels in listOf(1, 8)) {
            val processor = configured(preset = "speech", channels = channels, rate = rate)
            processor.queueInput(pcm(300, channels = channels))
            assertEquals(300 * channels * 2, processor.output.remaining())
        }
    }

    @Test
    fun endOfStreamAndResetObeyMedia3Lifecycle() {
        val states = mutableListOf<AudioEffects.State>()
        val processor = configured(preset = "speech", report = states::add)
        processor.queueInput(pcm(500))
        processor.queueEndOfStream()
        assertFalse(processor.isEnded)
        assertTrue(processor.output.hasRemaining())
        assertTrue(processor.isEnded)
        processor.reset()
        assertFalse(processor.isActive)
        assertEquals(AudioEffects.Phase.Idle, states.last().phase)
        processor.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm(500, channels = 1))
        assertEquals(1000, processor.output.remaining())
        assertEquals(AudioEffects.State("speech", AudioEffects.Phase.Applied), states.last())
    }

    @Test
    fun selectingBeforeFormatPreparationUsesLatestSavedChoice() {
        val states = mutableListOf<AudioEffects.State>()
        val processor = PcmEffectsAudioProcessor(report = states::add)
        processor.select("concert"); processor.select("vocal")
        assertEquals(AudioEffects.State("vocal", AudioEffects.Phase.Idle), states.last())
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm(128)); processor.output
        assertEquals(AudioEffects.State("vocal", AudioEffects.Phase.Applied), states.last())
    }

    @Test
    fun realMedia3PipelineCanFlushAndResetBeforePreparationAndRepeatedlyAfterStop() {
        val processor = PcmEffectsAudioProcessor()
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        repeat(2) { pipeline.reset() }
        assertFalse(pipeline.isOperational)
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcm(600)
        val expected = input.duplicate().bytes()
        pipeline.queueInput(input)
        assertArrayEquals(expected, pipeline.output.bytes())
        repeat(2) { pipeline.reset() }
        assertFalse(processor.isActive)
    }

    @Test
    fun realMedia3PipelineHotSwitchProducesDifferentStablePcmAndRestoresOffExactly() {
        val processor = PcmEffectsAudioProcessor()
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, pipeline.configure(format))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue(pipeline.isOperational)

        val sample = compositePcm()
        val expectedOff = sample.duplicate().order(ByteOrder.nativeOrder()).bytes()
        val pop = stablePresetOutput(processor, pipeline, sample, "pop")
        val rock = stablePresetOutput(processor, pipeline, sample, "rock")
        val vocal = stablePresetOutput(processor, pipeline, sample, "vocal")
        val speech = stablePresetOutput(processor, pipeline, sample, "speech")

        processor.select(AudioEffects.OFF)
        feedSample(pipeline, sample)
        val off = feedSample(pipeline, sample)
        val eosTail = finishPipeline(pipeline)

        assertFalse("pop and rock must not collapse to the same PCM", pop.contentEquals(rock))
        assertFalse("vocal and speech must not collapse to the same PCM", vocal.contentEquals(speech))
        assertFalse("a non-off preset must change the composite PCM", pop.contentEquals(expectedOff))
        assertTrue(
            "pop/rock target band is too close: ${componentDb(pop, 500.0)} vs ${componentDb(rock, 500.0)} dB",
            abs(componentDb(pop, 500.0) - componentDb(rock, 500.0)) >= 1.0,
        )
        assertTrue(
            "vocal/speech target band is too close: ${componentDb(vocal, 8_000.0)} vs ${componentDb(speech, 8_000.0)} dB",
            abs(componentDb(vocal, 8_000.0) - componentDb(speech, 8_000.0)) >= 3.5,
        )
        assertArrayEquals("settled off must restore the exact input bytes", expectedOff, off)
        assertTrue("EOS must be drained through the pipeline", eosTail.isEmpty())
        assertTrue(pipeline.isEnded)
    }

    @Test
    fun realMedia3PipelineCanCleanUpAFailedReconfiguration() {
        val processor = PcmEffectsAudioProcessor()
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            pipeline.configure(AudioProcessor.AudioFormat(0, 2, C.ENCODING_PCM_16BIT))
        }
        pipeline.reset()
        assertFalse(processor.isActive)
    }

    @Test
    fun invalidFormatAndPartialFramesFailExplicitlyInsteadOfCorruptingChannels() {
        val states = mutableListOf<AudioEffects.State>()
        val processor = PcmEffectsAudioProcessor(report = states::add)
        for (format in listOf(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT),
            AudioProcessor.AudioFormat(48_000, 9, C.ENCODING_PCM_16BIT),
            AudioProcessor.AudioFormat(0, 2, C.ENCODING_PCM_16BIT),
        )) {
            assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) { processor.configure(format) }
            assertEquals(AudioEffects.Phase.Failed, states.last().phase)
        }
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertThrows(IllegalStateException::class.java) { processor.queueInput(ByteBuffer.allocateDirect(3)) }
        assertEquals(AudioEffects.Phase.Failed, states.last().phase)
    }
}
