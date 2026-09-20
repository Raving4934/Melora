package com.leyu.melora.playback

import androidx.media3.datasource.cache.CacheDataSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioCacheStoreTest {
    @Test
    fun playbackDoesNotBlockBehindBackgroundCacheWriter() {
        val playbackFlags = cacheDataSourceFlags(blockOnCache = false)
        val backgroundFlags = cacheDataSourceFlags(blockOnCache = true)

        assertEquals(0, playbackFlags and CacheDataSource.FLAG_BLOCK_ON_CACHE)
        assertEquals(
            CacheDataSource.FLAG_BLOCK_ON_CACHE,
            backgroundFlags and CacheDataSource.FLAG_BLOCK_ON_CACHE,
        )
        assertEquals(
            CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            playbackFlags and CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
        )
    }

    @Test
    fun logicalCacheKeyMatchesHistoricalPlaybackUri() {
        assertEquals(
            "melora://song/kg_123?q=320k",
            audioCacheKey(uid = "kg_123", quality = "320k"),
        )
    }

    @Test
    fun physicalCacheKeySeparatesActualQualityAndSource() {
        val kg320 = audioResourceKey("wy_1", "320k", "kg_2", "file-1")
        assertNotEquals(kg320, audioResourceKey("wy_1", "128k", "kg_2", "file-1"))
        assertNotEquals(kg320, audioResourceKey("wy_1", "320k", "tx_3", "file-1"))
    }

    @Test
    fun physicalKeySeparatesEncodingAndBackendEvenWhenQualityLabelsMatch() {
        val mp3 = audioResourceKey("kw_1", "128k", "kw_1", "fixture:mp3:file-1")
        assertNotEquals(mp3, audioResourceKey("kw_1", "128k", "kw_1", "fixture:aac:file-2"))
        assertNotEquals(mp3, audioResourceKey("kw_1", "128k", "kw_1", "lx:script:file-1"))
        assertEquals(mp3, audioResourceKey("kw_1", "128k", "kw_1", "fixture:mp3:file-1"))
    }

    @Test
    fun cachedResolverIdentityDecodesWithoutChangingCacheKeys() {
        val id = "lx:source-id:hash+/&=音乐"
        val key = audioResourceKey("wy_1", "flac", "kw_2", id)
        assertEquals(id, audioResourceId(key))
        assertEquals(null, audioResourceId("melora://audio/kw_1?q=320k&src=kw_1"))
        assertEquals(null, audioResourceId("melora://audio/kw_1?q=320k&res=%XX"))
        assertEquals(null, audioResourceId(audioCacheKey("kw_1", "320k")))
    }

    @Test
    fun qualityFallbackUsesOnlyUnambiguousLosslessExtensions() {
        assertEquals(".mp3", audioExtensionForQuality("320k"))
        assertEquals(".flac", audioExtensionForQuality("flac24bit"))
        assertEquals(".flac", audioExtensionForQuality("hires"))
        assertEquals(".ape", audioExtensionForQuality("ape"))
        assertEquals(".wav", audioExtensionForQuality("wav"))
        assertEquals(".mp3", audioExtensionForQuality("atmos"))
    }

    @Test
    fun contentTypeAndUrlHintsRecognizeCommonContainers() {
        assertEquals(".flac", audioExtensionFromContentType("audio/flac"))
        assertEquals(".m4a", audioExtensionFromContentType("audio/mp4"))
        assertEquals(".aac", audioExtensionFromContentType("audio/aac"))
        assertEquals(".ape", audioExtensionFromUrl("https://cdn.example/song.APE?token=1"))
        assertEquals(null, audioExtensionFromUrl("https://cdn.example/play?id=1"))
    }

    @Test
    fun fileHeaderOverridesAmbiguousUrlHints() {
        assertHeaderExtension("fLaC00000000".toByteArray(), ".flac")
        assertHeaderExtension("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(), ".mp3")
        assertHeaderExtension(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0, 0), ".mp3")
        assertHeaderExtension(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0, 0), ".aac")
        assertHeaderExtension("0000ftypM4A ".toByteArray(), ".m4a")
        assertHeaderExtension("OggS00000000".toByteArray(), ".ogg")
        assertHeaderExtension("MAC 00000000".toByteArray(), ".ape")
    }

    private fun assertHeaderExtension(header: ByteArray, expected: String) {
        val file = File.createTempFile("melora-audio-header-", ".part")
        try {
            file.writeBytes(header)
            assertEquals(expected, detectAudioExtension(file))
        } finally {
            file.delete()
        }
    }
}
