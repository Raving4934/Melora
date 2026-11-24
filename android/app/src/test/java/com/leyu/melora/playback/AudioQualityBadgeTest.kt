package com.leyu.melora.playback

import androidx.media3.common.Format
import com.leyu.melora.playback.sdk.qualityBadgeForCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQualityBadgeTest {
    @Test
    fun compactLabelsUseTheQualityTierAndKeepUnmeasuredCodecsDistinct() {
        val codes = mapOf(
            AudioQualityTier.Dsd to "DSD", AudioQualityTier.HiRes to "HR",
            AudioQualityTier.Lossless to "SQ", AudioQualityTier.Atmos to "DA",
            AudioQualityTier.High to "HQ", AudioQualityTier.Standard to "SD",
            AudioQualityTier.Economy to "EC", AudioQualityTier.Low to "LQ",
            AudioQualityTier.LoFi to "LF", AudioQualityTier.Mono to "MO",
            AudioQualityTier.Master to "母带", AudioQualityTier.Spatial to "3D", AudioQualityTier.Opus to "OPUS",
        )
        assertEquals(codes, AudioQualityTier.entries.associateWith { AudioQualityBadge(it).compactLabel })
        val opus = AudioSpecification("audio/opus", 48_000, 96_000, channelCount = 2)
        assertEquals("EC", opus.qualityBadge?.compactLabel)
        assertEquals("OPUS 96K", opus.qualityBadge?.label)
        assertEquals("MO", opus.copy(bitrate = 24_000, channelCount = 1).qualityBadge?.compactLabel)
        assertEquals("LF", opus.copy(bitrate = 24_000, channelCount = -1).qualityBadge?.compactLabel)
        assertEquals("OPUS", opus.copy(bitrate = -1).qualityBadge?.compactLabel)
        assertEquals("HR", qualityBadgeForCapabilities(listOf("hires"))?.compactLabel)
    }

    @Test
    fun compressedTiersUseActualRateCodecAndChannelCount() {
        fun badge(mime: String, rate: Int, channels: Int = -1) =
            AudioSpecification(mime, 48_000, rate, channelCount = channels).qualityBadge

        assertEquals("SD 128K", badge("audio/mpeg", 127_997)?.label)
        assertEquals("HQ", badge("audio/mpeg", 319_999)?.label)
        assertEquals("LQ 64K", badge("audio/mpeg", 64_000)?.label)
        assertEquals("LO-FI 24K", badge("audio/mpeg", 24_000)?.label)
        assertEquals("LO-FI 24K", badge("audio/mpeg", 24_000, 2)?.label)
        val mono = Format.Builder().setSampleMimeType("audio/mpeg").setAverageBitrate(24_000).setChannelCount(1).build()
        assertEquals("MONO 24K", AudioSpecification.fromFormat(mono).qualityBadge?.label)
        assertEquals("OPUS 96K", badge("audio/opus", 96_000, 2)?.label)
        assertEquals(AudioQualityBadge(AudioQualityTier.Opus), badge("audio/opus", -1))
        assertNull(badge("audio/mp4a-latm", -1))
    }

    @Test
    fun onlyDeclaredOrMeasuredSpecificationsAppearInSplitBadges() {
        val flac = AudioSpecification("audio/flac", 44_100, -1, 16).qualityBadge
        assertEquals("FLAC", flac?.splitLabel)
        assertEquals("16/44.1", flac?.shortSpec)
        assertEquals("64", AudioSpecification("audio/dsd", 2_822_400, -1).qualityBadge?.shortSpec)
        assertEquals("2.8224 MHz", AudioSpecification("audio/dsd", 2_822_400, -1).sampleRateLabel)
        assertNull(AudioSpecification("audio/dsd", -1, -1).qualityBadge?.shortSpec)
        assertEquals("24-bit", qualityBadgeForCapabilities(listOf("flac24bit"))?.shortSpec)
        assertNull(qualityBadgeForCapabilities(listOf("hires"))?.shortSpec)
        assertEquals("192 kHz", AudioSpecification("audio/flac", 192_000, -1, 24).qualityBadge?.shortSpec)
        assertEquals(AudioQualityTier.Atmos, AudioSpecification("audio/eac3-joc", 48_000, 640_000).qualityBadge?.tier)
        assertEquals(AudioQualityTier.High, AudioSpecification("audio/eac3", 48_000, 640_000).qualityBadge?.tier)
        assertEquals("256K", qualityBadgeForCapabilities(listOf("128k", "256k", "ogg192k"))?.label)
    }

    @Test
    fun aMatchingFileCanSupplyMissingFieldsButCannotOverrideAnotherTracksData() {
        val selected = AudioSpecification("audio/flac", 192_000, -1, 24, 2)
        val file = AudioSpecification("audio/x-flac", 192_000, 4_812_000, 24, 2)
        assertEquals(selected.copy(bitrate = 4_812_000), selected.withKnownFileFields(file))
        for (other in listOf(null, file.copy(mimeType = "audio/alac"), file.copy(bitDepth = 16),
            file.copy(channelCount = 6), file.copy(sampleRate = 48_000))) {
            assertEquals(selected, selected.withKnownFileFields(other))
        }
        val unknown = AudioSpecification(null, -1, -1)
        assertEquals(unknown, unknown.withKnownFileFields(file))
    }
}
