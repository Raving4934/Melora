package com.leyu.melora.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioSpecificationTest {
    @Test
    fun sourceRequestCannotOverrideActualDecodedFormat() {
        assertEquals("SD 128K", AudioSpecification("audio/mpeg", 44100, 128000).qualityBadge?.label)
        assertNull(AudioSpecification("audio/flac", 96000, -1).qualityBadge)
        assertEquals("LOSSLESS", AudioSpecification("audio/flac", 96000, -1, 16).qualityBadge?.label)
        assertEquals("HI-RES", AudioSpecification("audio/flac", 96000, -1, 24).qualityBadge?.label)
        assertEquals("320k", AudioSpecification("audio/mpeg", 44100, 319_998).verifiedQuality)
        assertEquals("192k", AudioSpecification("audio/mpeg", 44100, 191_998).verifiedQuality)
        assertNull(AudioSpecification(null, -1, -1).qualityBadge)
        assertNull(AudioSpecification("audio/flac", 96000, -1).verifiedQuality)
        assertEquals("128k", AudioSpecification("audio/mpeg", 44100, 128000).verifiedQuality)
        assertEquals("flac24bit", AudioSpecification("audio/flac", 96000, -1, 24).verifiedQuality)
    }

    @Test
    fun knownLossyCodecRejectsLosslessClaimsEvenWithoutBitrate() {
        for ((mime, expected) in mapOf("audio/mpeg" to "mp3", "audio/opus" to "opus", "audio/mp4a-latm" to "aac", "audio/vorbis" to "ogg")) {
            val spec = AudioSpecification(mime, 48_000, -1)
            for (claim in listOf("master", "flac24bit", "flac")) assertEquals(expected, spec.verifiedStreamQuality(claim))
            assertNull(spec.verifiedStreamQuality("320k"))
            assertNull(spec.verifiedStreamQuality(null))
        }
        assertNull(AudioSpecification("audio/flac", 192_000, -1).verifiedStreamQuality("master"))
    }

    @Test
    fun losslessBadgeRequiresRealBitDepthEvenAtHighSampleRate() {
        for (mime in listOf("audio/flac", "audio/alac", "audio/raw", "audio/wav", "audio/x-wav", "audio/ape")) {
            for (rate in listOf(44100, 96000, 192000)) {
                assertNull(AudioSpecification(mime, rate, 4_608_000).qualityBadge)
                assertNull(AudioSpecification(mime, rate, 4_608_000, 0).qualityBadge)
                assertEquals("LOSSLESS", AudioSpecification(mime, rate, -1, 16).qualityBadge?.label)
                assertEquals("HI-RES", AudioSpecification(mime, rate, -1, 24).qualityBadge?.label)
                assertEquals("HI-RES", AudioSpecification(mime, rate, -1, 32).qualityBadge?.label)
            }
        }
    }

    @Test
    fun localMeasuredMappingUsesBitDepthInsteadOfSampleRate() {
        assertEquals(
            "HI-RES",
            AudioSpecification.fromLocal("audio/flac", "file:///Music/a.flac", 48_000, 0, 24).qualityBadge?.label,
        )
        assertEquals(
            "LOSSLESS",
            AudioSpecification.fromLocal("audio/flac", "file:///Music/a.flac", 96_000, 0, 16).qualityBadge?.label,
        )
        assertEquals(
            "HI-RES",
            AudioSpecification.fromLocal("audio/flac", "file:///Music/a.flac", 384_000, 0, 24).qualityBadge?.label,
        )
        assertEquals(
            "DSD",
            AudioSpecification.fromLocal("audio/unknown", "file:///Music/a.dsf", 2_822_400, 0, -1).qualityBadge?.label,
        )
        assertNull(
            AudioSpecification.fromLocal("audio/unknown", "file:///Music/a.flac", 96_000, 900_000, -1).qualityBadge,
        )
    }

    @Test
    fun compressedBitrateStaysSpecificInsteadOfFallingBackTo128K() {
        assertEquals("192K", AudioSpecification("audio/mpeg", 44_100, 192_000).qualityBadge?.label)
        assertEquals("256K", AudioSpecification("audio/mpeg", 44_100, 256_000).qualityBadge?.label)
        assertEquals("SD 128K", AudioSpecification("audio/mpeg", 44_100, 128_000).qualityBadge?.label)
    }

    @Test
    fun formatMappingUsesAverageBitrateInsteadOfPeakBitrate() {
        val spec = AudioSpecification.fromFormat(format("audio/mp4a-latm", averageBitrate = 96_434, peakBitrate = 165_032))

        assertEquals(96_434, spec.bitrate)
        assertEquals("ECO 96K", spec.qualityBadge?.label)
        assertEquals("aac96k", spec.verifiedQuality)
    }

    @Test
    fun formatMappingKeepsBitrateUnknownWhenOnlyPeakIsKnown() {
        val spec = AudioSpecification.fromFormat(format("audio/mp4a-latm", peakBitrate = 165_032))

        assertEquals(-1, spec.bitrate)
        assertNull(spec.qualityBadge)
        assertNull(spec.verifiedQuality)
    }

    @Test
    fun formatMappingPreservesConstant128And320Bitrates() {
        val low = AudioSpecification.fromFormat(format("audio/mp4a-latm", averageBitrate = 128_000, peakBitrate = 128_000))
        val high = AudioSpecification.fromFormat(format("audio/mp4a-latm", averageBitrate = 320_000, peakBitrate = 320_000))

        assertEquals(128_000, low.bitrate)
        assertEquals("SD 128K", low.qualityBadge?.label)
        assertEquals(320_000, high.bitrate)
        assertEquals("HQ", high.qualityBadge?.label)
    }

    @Test
    fun formatMappingPreservesBitDepthAndChannelCount() {
        val spec = AudioSpecification.fromFormat(
            format("audio/flac", peakBitrate = 165_032, pcmEncoding = C.ENCODING_PCM_24BIT, channelCount = 2),
        )

        assertEquals(-1, spec.bitrate)
        assertEquals(24, spec.bitDepth)
        assertEquals(2, spec.channelCount)
        assertEquals("HI-RES", spec.qualityBadge?.label)
    }

    private fun format(
        mimeType: String,
        averageBitrate: Int = Format.NO_VALUE,
        peakBitrate: Int = Format.NO_VALUE,
        pcmEncoding: Int = Format.NO_VALUE,
        channelCount: Int = Format.NO_VALUE,
    ): Format = Format.Builder()
        .setSampleMimeType(mimeType)
        .setSampleRate(44_100)
        .setAverageBitrate(averageBitrate)
        .setPeakBitrate(peakBitrate)
        .setPcmEncoding(pcmEncoding)
        .setChannelCount(channelCount)
        .build()
}
