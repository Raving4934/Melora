package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSpecificationTest {
    @Test
    fun sourceRequestCannotOverrideActualDecodedFormat() {
        assertEquals("128K", AudioSpecification("audio/mpeg", 44100, 128000).qualityBadge)
        assertNull(AudioSpecification("audio/flac", 96000, -1).qualityBadge)
        assertEquals("SQ", AudioSpecification("audio/flac", 96000, -1, 16).qualityBadge)
        assertEquals("HR", AudioSpecification("audio/flac", 96000, -1, 24).qualityBadge)
        assertNull(AudioSpecification(null, -1, -1).qualityBadge)
        assertNull(AudioSpecification("audio/flac", 96000, -1).verifiedQuality)
        assertEquals("128k", AudioSpecification("audio/mpeg", 44100, 128000).verifiedQuality)
        assertEquals("flac24bit", AudioSpecification("audio/flac", 96000, -1, 24).verifiedQuality)
    }

    @Test
    fun losslessBadgeRequiresRealBitDepthEvenAtHighSampleRate() {
        for (mime in listOf("audio/flac", "audio/alac", "audio/raw", "audio/wav", "audio/x-wav", "audio/ape")) {
            for (rate in listOf(44100, 96000, 192000)) {
                assertNull(AudioSpecification(mime, rate, 4_608_000).qualityBadge)
                assertNull(AudioSpecification(mime, rate, 4_608_000, 0).qualityBadge)
                assertEquals("SQ", AudioSpecification(mime, rate, -1, 16).qualityBadge)
                assertEquals("HR", AudioSpecification(mime, rate, -1, 24).qualityBadge)
                assertEquals("HR", AudioSpecification(mime, rate, -1, 32).qualityBadge)
            }
        }
    }

    @Test
    fun localMeasuredMappingUsesBitDepthInsteadOfSampleRate() {
        assertEquals(
            "HR",
            AudioSpecification.fromLocal("audio/flac", "file:///Music/a.flac", 48_000, 0, 24).qualityBadge,
        )
        assertEquals(
            "SQ",
            AudioSpecification.fromLocal("audio/flac", "file:///Music/a.flac", 96_000, 0, 16).qualityBadge,
        )
        assertEquals(
            "HR",
            AudioSpecification.fromLocal("audio/flac", "file:///Music/a.flac", 384_000, 0, 24).qualityBadge,
        )
        assertEquals(
            "MASTER",
            AudioSpecification.fromLocal("audio/unknown", "file:///Music/a.dsf", 2_822_400, 0, -1).qualityBadge,
        )
        assertNull(
            AudioSpecification.fromLocal("audio/unknown", "file:///Music/a.flac", 96_000, 900_000, -1).qualityBadge,
        )
    }

    @Test
    fun compressedBitrateStaysSpecificInsteadOfFallingBackTo128K() {
        assertEquals("192K", AudioSpecification("audio/mpeg", 44_100, 192_000).qualityBadge)
        assertEquals("256K", AudioSpecification("audio/mpeg", 44_100, 256_000).qualityBadge)
        assertEquals("128K", AudioSpecification("audio/mpeg", 44_100, 128_000).qualityBadge)
    }
}
