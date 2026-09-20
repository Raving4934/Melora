package com.leyu.melora.playback.local

import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaDownloadRegistrationTest {
    @After
    fun tearDown() {
        LocalMediaStore.clear()
        LocalMediaStore.setScanning(false)
    }

    @Test
    fun registerDownloadedAppendsOneFileWithoutReplacingOtherLocalFiles() {
        val other = localSong(id = "other", uri = "file:///Music/other.flac")
        LocalMediaStore.replaceAll(listOf(other))

        val registered = LocalMediaStore.registerDownloaded(
            song = online(),
            uri = "content://media/external/audio/media/42",
            spec = AudioSpecification("audio/mpeg", 44_100, 320_000),
            durationMs = 180_000,
            sizeBytes = 4_200_000,
            displayName = "夜曲 - 周杰伦.mp3",
        )

        assertEquals("ms_42", registered.id)
        assertEquals(0L, registered.modifiedAt)
        assertEquals(2, LocalMediaStore.count)
        assertEquals(other, LocalMediaStore.find(other.id))
        assertEquals(registered, LocalMediaStore.find(registered.id))
    }

    @Test
    fun registerDownloadedUsesScannerDocHashAndReusesSameUriId() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FMelora%2F%E5%A4%9C%E6%9B%B2.flac"
        val expectedId = "doc_${uri.hashCode().toUInt().toString(16)}"
        val first = LocalMediaStore.registerDownloaded(
            song = online(),
            uri = uri,
            spec = AudioSpecification("audio/flac", 96_000, 0, 24),
            durationMs = 180_000,
            sizeBytes = 9_000_000,
            displayName = "夜曲 - 周杰伦.flac",
        )
        val second = LocalMediaStore.registerDownloaded(
            song = online(name = "夜曲（新版）"),
            uri = uri,
            spec = AudioSpecification("audio/flac", 48_000, 0, 16),
            durationMs = 181_000,
            sizeBytes = 8_000_000,
            displayName = "夜曲 - 周杰伦.flac",
            modifiedAt = 1234L,
        )

        assertEquals(expectedId, first.id)
        assertEquals(first.id, second.id)
        assertEquals(1234L, second.modifiedAt)
        assertEquals("夜曲（新版）", second.title)
        assertEquals(16, second.bitDepth)
        assertEquals(48_000, second.sampleRate)
        assertEquals(1, LocalMediaStore.count)
    }

    @Test
    fun registerDownloadedPreservesSameUriEnrichmentAndCover() {
        val existing = localSong(
            id = "user-local-id",
            uri = "file:///Music/Melora/夜曲.flac",
            artist = "用户补全歌手",
            album = "用户补全专辑",
            year = 2001,
            modifiedAt = 77L,
            addedAt = 88L,
            coverUri = "file:///covers/keep.jpg",
            infoFilled = true,
            bitDepth = 16,
        )
        LocalMediaStore.replaceAll(listOf(existing))

        val registered = LocalMediaStore.registerDownloaded(
            song = online(name = "网络标题", singer = "网络歌手", album = "网络专辑"),
            uri = existing.uri,
            spec = AudioSpecification("audio/flac", 96_000, 0, 24),
            durationMs = 181_000,
            sizeBytes = 10_000_000,
            displayName = "网络标题.flac",
            modifiedAt = 99L,
        )

        assertEquals(existing.id, registered.id)
        assertEquals("用户补全歌手", registered.artist)
        assertEquals("用户补全专辑", registered.album)
        assertEquals(2001, registered.year)
        assertEquals(existing.coverUri, registered.coverUri)
        assertTrue(registered.infoFilled)
        assertEquals(88L, registered.addedAt)
        assertEquals(99L, registered.modifiedAt)
        assertEquals(24, registered.bitDepth)
        assertEquals(181_000L, registered.durationMs)
        assertEquals(10_000_000L, registered.sizeBytes)
    }

    @Test
    fun localQualityUpgradeRequiresEvidenceAndDoesNotGuessUnknowns() {
        val localHr = localSong(mimeType = "audio/flac", sampleRate = 48_000, bitDepth = 24)
        assertTrue(LocalMediaStore.isHigherQuality(localHr, AudioSpecification("audio/mpeg", 44_100, 320_000)))
        assertTrue(LocalMediaStore.isHigherQuality(localSong(mimeType = "audio/flac", bitDepth = -1), AudioSpecification("audio/mpeg", 44_100, 320_000)))
        assertTrue(LocalMediaStore.isHigherQuality(localSong(mimeType = "audio/mpeg", bitrate = 320_000), AudioSpecification("audio/mpeg", 44_100, 128_000)))

        assertFalse(LocalMediaStore.isHigherQuality(localSong(mimeType = "audio/flac", bitDepth = -1), AudioSpecification("audio/flac", 48_000, 0, 24)))
        assertFalse(LocalMediaStore.isHigherQuality(localSong(mimeType = "audio/mpeg", bitrate = 320_000), AudioSpecification("audio/mpeg", 44_100, 0)))
        assertFalse(LocalMediaStore.isHigherQuality(localSong(mimeType = "audio/mpeg", bitrate = 320_000), AudioSpecification("audio/aac", 44_100, 128_000)))
        assertFalse(LocalMediaStore.isHigherQuality(localSong(mimeType = "audio/flac", sampleRate = 48_000, bitDepth = 16), AudioSpecification("audio/flac", 96_000, 0, 24)))
    }

    @Test
    fun onlinePlaybackChoosesHigherQualityWithinSameVersionNotNearestEncodingDuration() {
        val low = localSong(id = "low", durationMs = 180_000, bitrate = 128_000)
        val high = localSong(id = "high", durationMs = 180_050, mimeType = "audio/flac", bitDepth = 24)
        val live = localSong(id = "live", title = "夜曲 (Live)", durationMs = 180_000,
            mimeType = "audio/flac", bitDepth = 32)
        LocalMediaStore.replaceAll(listOf(low, high, live))
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "1").put("name", "夜曲")
            .put("singer", "周杰伦").put("albumName", "十一月的萧邦").put("interval", "03:00"))
        assertEquals("high", LocalMediaStore.matchSong(song)?.id)
        // 用户点选本地文件仍尊重具体ID，不把显式128K替换成另一个文件。
        assertEquals("low", LocalMediaStore.matchSong(low.toOnlineSong())?.id)
    }

    @Test
    fun mediaStoreAliasRegistrationUpdatesExistingIdentityInsteadOfAppendingDuplicate() {
        val scanned = localSong(id = "ms_1631", uri = "content://media/external/audio/media/1631",
            artist = "用户补全歌手", coverUri = "file:///covers/keep.jpg", infoFilled = true)
        val other = localSong(id = "ms_1632", uri = "content://media/external/audio/media/1632")
        LocalMediaStore.replaceAll(listOf(scanned, other))
        val alias = "content://media/external_primary/audio/media/1631"
        repeat(3) {
            LocalMediaStore.registerDownloaded(online(), alias, AudioSpecification("audio/mpeg", 44100, 128000),
                180000, 4_000_000, "夜曲.mp3", modifiedAt = 1)
        }
        assertEquals(2, LocalMediaStore.count)
        assertEquals(2, LocalMediaStore.songs.value.map { it.id }.toSet().size)
        val updated = LocalMediaStore.find("ms_1631")!!
        assertEquals("用户补全歌手", updated.artist)
        assertEquals(scanned.coverUri, updated.coverUri)
        assertTrue(updated.infoFilled)
        assertEquals(updated, LocalMediaStore.findByUri(alias))
        assertEquals(updated, LocalMediaStore.findByUri(scanned.uri))
        assertEquals(other, LocalMediaStore.find(other.id))
    }

    @Test
    fun oldDuplicateAliasesAreMergedBeforeAnyIndexIsPublished() {
        val complete = localSong(id = "ms_1631", uri = "content://media/external/audio/media/1631",
            artist = "已补全歌手", album = "已补全专辑", coverUri = "file:///covers/keep.jpg", infoFilled = true)
        val alias = complete.copy(uri = "content://media/external_primary/audio/media/1631", artist = "", album = "",
            coverUri = null, infoFilled = false, bitDepth = -1)
        // 不同媒体ID的高音质副本不能因同名被合并。
        val high = complete.copy(id = "ms_1632", uri = "content://media/external_primary/audio/media/1632",
            mimeType = "audio/flac", bitDepth = 24)
        LocalMediaStore.replaceAll(listOf(complete, alias, high))
        assertEquals(2, LocalMediaStore.count)
        val merged = LocalMediaStore.find(complete.id)!!
        assertEquals(complete.artist, merged.artist)
        assertEquals(complete.album, merged.album)
        assertEquals(complete.coverUri, merged.coverUri)
        assertTrue(merged.infoFilled)
        assertEquals(merged, LocalMediaStore.findByUri(complete.uri))
        assertEquals(merged, LocalMediaStore.findByUri(alias.uri))
        assertEquals(high, LocalMediaStore.find(high.id))
        LocalMediaStore.replaceAll(LocalMediaStore.snapshot())
        assertEquals(2, LocalMediaStore.count)
        LocalMediaStore.removeIds(setOf(complete.id))
        assertEquals(listOf(high), LocalMediaStore.songs.value)
    }

    private fun localSong(
        id: String = "ms_1",
        uri: String = "file:///Music/$id.mp3",
        title: String = "夜曲",
        artist: String = "周杰伦",
        album: String = "十一月的萧邦",
        year: Int = 2005,
        durationMs: Long = 180_000,
        sizeBytes: Long = 4_000_000,
        mimeType: String = "audio/mpeg",
        sampleRate: Int = 44_100,
        bitrate: Int = 320_000,
        modifiedAt: Long = 1L,
        addedAt: Long = 1L,
        coverUri: String? = null,
        infoFilled: Boolean = false,
        bitDepth: Int = -1,
    ) = LocalSong(
        id = id,
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        sampleRate = sampleRate,
        bitrate = bitrate,
        modifiedAt = modifiedAt,
        addedAt = addedAt,
        year = year,
        folder = "Music",
        coverUri = coverUri,
        infoFilled = infoFilled,
        bitDepth = bitDepth,
    )

    private fun online(
        name: String = "夜曲",
        singer: String = "周杰伦",
        album: String = "十一月的萧邦",
    ) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", "1")
            .put("name", name)
            .put("singer", singer)
            .put("albumName", album),
    )
}
