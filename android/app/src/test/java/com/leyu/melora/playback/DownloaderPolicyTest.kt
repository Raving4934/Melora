package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DownloaderPolicyTest {
    @Test
    fun equivalentHiResAliasIsNotReportedAsAQualityDowngrade() {
        org.junit.Assert.assertEquals("（实际音质：hires）", downloadQualityNote("flac24bit", "hires"))
        org.junit.Assert.assertEquals("（flac24bit 不可用，已使用 flac）", downloadQualityNote("flac24bit", "flac"))
    }

    @Test
    fun lowerOrUnknownQualityDoesNotBlockRequestedUpgrade() {
        assertFalse(downloadQualityMatches(AudioSpecification("audio/mpeg", 44100, 128000), "flac24bit"))
        assertFalse(downloadQualityMatches(AudioSpecification("audio/flac", 96000, -1, 16), "flac24bit"))
        assertFalse(downloadQualityMatches(AudioSpecification("audio/flac", 96000, -1), "flac24bit"))
        assertFalse(downloadQualityMatches(null, "flac24bit"))
        assertTrue(downloadQualityMatches(AudioSpecification("audio/flac", 48000, -1, 24), "flac24bit"))
        assertFalse(downloadQualityMatches(AudioSpecification("audio/flac", 48000, -1, 24), "128k"))
    }

    @Test
    fun actualQualityNotRequestedQualityControlsDuplicateAndFileName() {
        val low = AudioSpecification("audio/mpeg", 44100, 128000)
        val sq = AudioSpecification("audio/flac", 44100, -1, 16)
        val hr = AudioSpecification("audio/flac", 48000, -1, 24)
        assertTrue(sameDownloadedQuality(low, low))
        assertFalse(sameDownloadedQuality(sq, hr))
        assertEquals("歌 - 歌手.flac", downloadVariantName("歌 - 歌手", ".flac", hr, 0))
        assertEquals("歌 - 歌手 [HR].flac", downloadVariantName("歌 - 歌手", ".flac", hr, 1))
        assertEquals("歌 - 歌手 [SQ] (2).flac", downloadVariantName("歌 - 歌手", ".flac", sq, 2))
        assertTrue(matchesCurrentDownloadFileName("歌 - 歌手 [HR].flac", "歌 - 歌手"))
        assertTrue(matchesCurrentDownloadFileName("歌 - 歌手 [SQ] (2).flac", "歌 - 歌手"))
        assertFalse(matchesCurrentDownloadFileName("歌 - 歌手 [Live].flac", "歌 - 歌手"))
    }

    @Test
    fun missingAndZeroLengthFilesAreNotCompleteTargets() {
        val directory = Files.createTempDirectory("melora-download-policy").toFile()
        try {
            val missing = directory.resolve("missing.mp3")
            val empty = directory.resolve("empty.mp3").apply { createNewFile() }
            val nonEmpty = directory.resolve("non-empty.mp3").apply { writeText("audio") }

            assertFalse(isNonEmptyRegularFile(missing))
            assertFalse(isNonEmptyRegularFile(empty))
            assertTrue(isNonEmptyRegularFile(nonEmpty))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun historicalFileNameFromAnotherNamingRuleDoesNotMatchCurrentBaseName() {
        assertFalse(matchesCurrentDownloadFileName("歌 - 歌手.mp3", "歌"))
        assertTrue(matchesCurrentDownloadFileName("歌 - 歌手.mp3", "歌 - 歌手"))
        assertFalse(matchesCurrentDownloadFileName("other/歌 - 歌手.mp3", "歌 - 歌手"))
    }

    @Test
    fun completeCacheHitDoesNotLookupMissingDownloadMetadata() {
        val missingAlbum = song("kw", "1", "歌")
        val complete = song("kw", "2", "歌").apply {
            // song() 已提供歌手，这里补齐专辑以构造完整快照。
            raw.put("albumName", "专辑")
        }

        assertFalse(shouldLookupDownloadMetadata(completeCacheHit = true, missingAlbum))
        assertTrue(shouldLookupDownloadMetadata(completeCacheHit = false, missingAlbum))
        assertFalse(shouldLookupDownloadMetadata(completeCacheHit = false, complete))
    }

    @Test
    fun batchSubmissionKeepsFirstSongPerUidAndDeduplicatesOnlyByUid() {
        val unique = distinctDownloadSongs(
            listOf(
                song("kw", "1", "first"),
                song("kw", "1", "duplicate"),
                song("kw", "2", "second"),
                song("qq", "1", "sameSongMidDifferentSource"),
            ),
        )

        assertEquals(listOf("kw_1", "kw_2", "qq_1"), unique.map(OnlineSong::uid))
        assertEquals("first", unique.first().name)
    }

    private fun song(source: String, songmid: String, name: String) = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", songmid)
            .put("name", name)
            .put("singer", "歌手"),
    )
}
