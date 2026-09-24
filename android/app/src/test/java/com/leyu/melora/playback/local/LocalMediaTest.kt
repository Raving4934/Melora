package com.leyu.melora.playback.local

import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.DownloadMetadataWriter
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaTest {
    @After
    fun tearDown() {
        LocalMediaStore.clear()
        LocalMediaStore.setScanning(false)
        MeloraSettings.localAutoFillInfo.value = false
    }

    @Test
    fun matchKeyIgnoresCasePunctuationAndDecorations() {
        assertEquals(
            LocalSong.matchKeyOf("夜曲 (Live)", "周杰伦"),
            LocalSong.matchKeyOf("夜曲", "周 杰 伦"),
        )
        assertEquals(
            LocalSong.matchKeyOf("ABC!", "A、B"),
            LocalSong.matchKeyOf("abc", "A B"),
        )
    }

    @Test
    fun hasLocalFileMatchesOnlineSongByTitleArtist() {
        LocalMediaStore.replaceAll(listOf(localSong(title = "夜曲", artist = "周杰伦")))
        assertTrue(LocalMediaStore.hasLocalFile(online(name = "夜曲", singer = "周杰伦")))
        assertFalse(LocalMediaStore.hasLocalFile(online(name = "稻香", singer = "周杰伦")))
        assertTrue(LocalMediaStore.hasLocalFile(online(source = "local", songmid = "other")))
    }

    @Test
    fun matchTrackUsesLocalIdAndTitleArtist() {
        val song = localSong(id = "ms_9", title = "晴天", artist = "周杰伦")
        LocalMediaStore.replaceAll(listOf(song))
        assertEquals(song.id, LocalMediaStore.matchTrack(track(source = "local", uid = "local_ms_9", title = "x", artist = "y"))?.id)
        assertEquals(song.id, LocalMediaStore.matchTrack(track(source = "kw", uid = "kw_1", title = "晴天", artist = "周杰伦"))?.id)
        assertNull(LocalMediaStore.matchTrack(track(source = "kw", uid = "kw_2", title = "七里香", artist = "周杰伦")))
    }

    @Test
    fun updateReplacesOneSongAndRebuildsMatchKey() {
        LocalMediaStore.replaceAll(listOf(localSong(id = "ms_1", title = "夜曲", artist = "")))
        assertNull(LocalMediaStore.match("夜曲", "周杰伦"))
        LocalMediaStore.updateMetadata(localSong(id = "ms_1", title = "夜曲", artist = "周杰伦"))
        assertNotNull(LocalMediaStore.match("夜曲", "周杰伦"))
        assertEquals("周杰伦", LocalMediaStore.find("ms_1")?.artist)
    }

    @Test
    fun localFirstMatchesMissingArtistByExactTitleAndDurationOnly() {
        val local = localSong(id = "blank-artist", artist = "")
        LocalMediaStore.replaceAll(listOf(local))
        val same = online(singer = "周杰伦").copyRaw(interval = "03:00")
        val wrongDuration = online(singer = "周杰伦").copyRaw(interval = "04:00")

        assertEquals(local.id, LocalMediaStore.matchSong(same)?.id)
        assertNull(LocalMediaStore.matchSong(wrongDuration))
    }

    @Test
    fun normalizedVersionsUseClosestDurationInsteadOfHighestBitrate() {
        val studio = localSong(id = "studio", title = "夜曲", durationMs = 180_000, bitrate = 320_000)
        val live = localSong(id = "live", title = "夜曲 (Live)", durationMs = 240_000, bitrate = 128_000)
        LocalMediaStore.replaceAll(listOf(studio, live))

        assertEquals("live", LocalMediaStore.matchSong(online(name = "夜曲 (Live)").copyRaw(interval = "04:00"))?.id)
        assertNull(LocalMediaStore.matchSong(online(name = "夜曲").copyRaw(interval = "05:00")))
    }

    @Test
    fun duplicateIndexUpdatesKeepTheSamePublishedSnapshot() {
        val song = localSong()
        LocalMediaStore.replaceAll(listOf(song))
        val snapshot = LocalMediaStore.songs.value
        LocalMediaStore.replaceAll(listOf(song.copy()))
        LocalMediaStore.updateMetadata(song.copy())
        org.junit.Assert.assertSame(snapshot, LocalMediaStore.songs.value)
    }

    @Test
    fun scanMergeKeepsConcurrentChangesAndNewEntriesWithoutRescan() {
        val deleted = localSong(id = "deleted")
        val concurrent = localSong(id = "updated", artist = "旧歌手", modifiedAt = 10)
        LocalMediaStore.replaceAll(listOf(deleted, concurrent))
        val baseline = LocalMediaStore.snapshot()

        val current = concurrent.copy(artist = "新歌手", modifiedAt = 11)
        val currentNew = localSong(id = "current-new", title = "并发新增")
        LocalMediaStore.replaceAll(listOf(current, currentNew))

        val scannedNew = localSong(id = "scan-new", title = "扫描新增")
        val scanned = listOf(deleted, concurrent.copy(artist = "扫描旧歌手"), scannedNew)
        LocalMediaStore.commitScanned(baseline, scanned)

        assertEquals(
            setOf("updated", "current-new", "scan-new"),
            LocalMediaStore.songs.value.map { it.id }.toSet(),
        )
        assertEquals("新歌手", LocalMediaStore.find("updated")?.artist)
        assertEquals("并发新增", LocalMediaStore.find("current-new")?.title)
    }

    @Test
    fun sourceFailureIsNotAnEmptySuccessAndCancellationStillPropagates() = runBlocking {
        val failed = LocalMediaScanner.scanSource { throw SecurityException("revoked") }
        assertFalse(failed.successful)
        val empty = LocalMediaScanner.scanSource { emptyList() }
        assertTrue(empty.successful)
        val recovered = LocalMediaScanner.scanSource { listOf(localSong()) }
        assertTrue(recovered.successful)
        assertEquals(1, recovered.songs.size)
        try {
            LocalMediaScanner.scanSource { throw CancellationException("cancelled") }
            error("must propagate cancellation")
        } catch (_: CancellationException) { }
    }

    @Test
    fun partialScanKeepsFailedSourceAndReplacesSuccessfulSource() {
        val failedSourceSong = localSong(
            id = "failed-source",
            uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fkeep.mp3",
        )
        val staleSuccessfulSong = localSong(id = "stale-success")
        LocalMediaStore.replaceAll(listOf(failedSourceSong, staleSuccessfulSong))
        val baseline = LocalMediaStore.snapshot()

        val successfulReplacement = localSong(id = "new-success", title = "扫描新增")
        LocalMediaStore.commitScanned(baseline, listOf(successfulReplacement, failedSourceSong))

        assertEquals(
            setOf("failed-source", "new-success"),
            LocalMediaStore.songs.value.map { it.id }.toSet(),
        )
        assertNotNull(LocalMediaStore.find("failed-source"))
        assertNull(LocalMediaStore.find("stale-success"))
    }

    @Test
    fun removedScanSourceIsNotReintroducedByThreeWayMerge() {
        val removedSourceSong = localSong(id = "removed-source")
        LocalMediaStore.replaceAll(listOf(removedSourceSong))
        val baseline = LocalMediaStore.snapshot()

        LocalMediaStore.commitScanned(baseline, emptyList())

        assertNull(LocalMediaStore.find("removed-source"))
    }

    @Test
    fun scanningStateRestoresAfterCancellationAndCanBeUsedAgain() = runBlocking {
        var cancelled = false
        try {
            LocalMediaStore.withScanning {
                assertTrue(LocalMediaStore.isScanning.value)
                throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(LocalMediaStore.isScanning.value)

        LocalMediaStore.withScanning {
            assertTrue(LocalMediaStore.isScanning.value)
        }
        assertFalse(LocalMediaStore.isScanning.value)
    }

    @Test
    fun sameStampConcurrentMetadataWinsOverScannedSnapshot() {
        val baselineSong = localSong(
            id = "same-stamp",
            artist = "旧歌手",
            coverUri = "file:///old-cover.jpg",
            bitDepth = 24,
            modifiedAt = 10,
            sizeBytes = 100,
        )
        LocalMediaStore.replaceAll(listOf(baselineSong))
        val baseline = LocalMediaStore.snapshot()

        val current = baselineSong.copy(
            artist = "并发歌手",
            coverUri = "file:///current-cover.jpg",
            bitDepth = 32,
        )
        LocalMediaStore.replaceAll(listOf(current))
        val scanned = baselineSong.copy(
            artist = "扫描旧歌手",
            coverUri = null,
            bitDepth = -1,
        )
        LocalMediaStore.commitScanned(baseline, listOf(scanned))

        assertEquals("并发歌手", LocalMediaStore.find("same-stamp")?.artist)
        assertEquals("file:///current-cover.jpg", LocalMediaStore.find("same-stamp")?.coverUri)
        assertEquals(32, LocalMediaStore.find("same-stamp")?.bitDepth)
    }

    @Test
    fun scanMergePreservesMeasuredBitDepthAndCoverWhenScanLacksThem() {
        val baselineSong = localSong(
            id = "preserve-measured",
            coverUri = "file:///measured-cover.jpg",
            bitDepth = 24,
            modifiedAt = 10,
            sizeBytes = 100,
        )
        LocalMediaStore.replaceAll(listOf(baselineSong))
        val baseline = LocalMediaStore.snapshot()
        val scanned = baselineSong.copy(coverUri = null, bitDepth = -1)

        LocalMediaStore.commitScanned(baseline, listOf(scanned))

        val merged = LocalMediaStore.find("preserve-measured")!!
        assertEquals("file:///measured-cover.jpg", merged.coverUri)
        assertEquals(24, merged.bitDepth)
    }

    @Test
    fun legacyIndexWithoutEnrichmentFieldsStillLoads() {
        val legacy = localSong().toJson().apply {
            remove("year")
            remove("coverUri")
            remove("infoFilled")
            remove("bitDepth")
        }
        val restored = LocalSong.fromJson(legacy)!!
        assertEquals("ms_1", restored.id)
        assertEquals("夜曲", restored.title)
        assertEquals(0, restored.year)
        assertNull(restored.coverUri)
        assertFalse(restored.infoFilled)
        assertEquals(-1, restored.bitDepth)
    }

    @Test
    fun duplicateLocalMatchesPreferHigherQualityFile() {
        val compressed = localSong(id = "mp3", bitrate = 320_000)
        val lossless = localSong(id = "flac", uri = "file:///Music/a.flac", mimeType = "audio/flac", bitrate = 900_000)
        LocalMediaStore.replaceAll(listOf(compressed, lossless))

        assertEquals("flac", LocalMediaStore.match("夜曲", "周杰伦")?.id)
    }

    @Test
    fun duplicateLosslessMatchesPreferMeasuredBitDepthBeforeRate() {
        val sixteenBitHighRate = localSong(
            id = "16bit-96k",
            uri = "file:///Music/a-16bit.flac",
            mimeType = "audio/flac",
            sampleRate = 96_000,
            bitrate = 4_608_000,
            bitDepth = 16,
        )
        val twentyFourBitLowerRate = localSong(
            id = "24bit-48k",
            uri = "file:///Music/a-24bit.flac",
            mimeType = "audio/flac",
            sampleRate = 48_000,
            bitrate = 1_152_000,
            bitDepth = 24,
        )
        LocalMediaStore.replaceAll(listOf(sixteenBitHighRate, twentyFourBitLowerRate))

        assertEquals("24bit-48k", LocalMediaStore.match("夜曲", "周杰伦")?.id)
    }

    @Test
    fun scanOnlyKeepsEnrichmentWhenFileIsUnchanged() {
        val previous = localSong(
            artist = "网络歌手",
            album = "网络专辑",
            coverUri = "file:///cover.jpg",
            infoFilled = true,
            sampleRate = 96_000,
            bitrate = 1_500_000,
            year = 2024,
            modifiedAt = 10,
            sizeBytes = 100,
        )
        val unchanged = localSong(
            artist = "",
            album = "",
            sampleRate = 0,
            bitrate = 0,
            year = 0,
            modifiedAt = 10,
            sizeBytes = 100,
        )
        val preserved = preserveLocalEnrichment(unchanged, previous)
        assertEquals("网络歌手", preserved.artist)
        assertEquals("file:///cover.jpg", preserved.coverUri)
        assertTrue(preserved.infoFilled)
        assertEquals(previous.bitDepth, preserved.bitDepth)

        val replaced = preserveLocalEnrichment(unchanged.copy(modifiedAt = 11), previous)
        assertEquals("", replaced.artist)
        assertNull(replaced.coverUri)
        assertFalse(replaced.infoFilled)
    }

    @Test
    fun jsonRoundTripKeepsNewMeasuredFieldsLosslessly() {
        val original = localSong(
            artist = "",
            coverUri = "file:///cover.jpg",
            infoFilled = true,
            sampleRate = 48_000,
            bitDepth = 24,
        )
        val raw = original.toJson()
        assertEquals(24, raw.optInt("bitDepth"))
        assertEquals(original, LocalSong.fromJson(raw))
    }

    @Test
    fun oldJsonWithoutMeasuredBitDepthUsesUnknownDefault() {
        val legacy = localSong(coverUri = "file:///old-cover.jpg", infoFilled = true).toJson().apply {
            remove("bitDepth")
        }
        val restored = LocalSong.fromJson(legacy)!!
        assertEquals(-1, restored.bitDepth)
        assertEquals("file:///old-cover.jpg", restored.coverUri)
        assertTrue(restored.infoFilled)
    }

    @Test
    fun cachedCoverDoesNotPretendPhysicalMetadataWriteCompleted() {
        assertTrue(shouldFillLocalInfo(localSong(coverUri = "file:///cache/cover.jpg", infoFilled = false)))
        assertTrue(shouldFillLocalInfo(localSong(coverUri = null, infoFilled = true)))
        assertFalse(shouldFillLocalInfo(localSong(coverUri = "file:///embedded-cover.jpg", infoFilled = true)))
    }

    @Test
    fun gapsOnlyFlagMissingFields() {
        val complete = localInfoGaps(localSong(), hasCover = true, hasLyric = true)
        assertFalse(complete.any)
        val missing = localInfoGaps(localSong(artist = "", album = ""), hasCover = false, hasLyric = false)
        assertTrue(missing.any)
        assertTrue(missing.artist)
        assertTrue(missing.album)
        assertTrue(missing.cover)
        assertTrue(missing.lyric)
        val artistOnly = localInfoGaps(localSong(artist = ""), hasCover = true, hasLyric = true)
        assertTrue(artistOnly.artist)
        assertFalse(artistOnly.album)
        assertFalse(artistOnly.cover)
        assertFalse(artistOnly.lyric)
        val optionalYear = localInfoGaps(localSong(year = 0), hasCover = true, hasLyric = true)
        assertTrue(optionalYear.any)
        assertTrue(optionalYear.year)
        assertFalse(optionalYear.unresolvedRequired)
    }

    @Test
    fun mergeFillsBlanksWithoutOverwritingExisting() {
        val merged = mergeLocalInfo(
            localSong(artist = "", album = "已有专辑", coverUri = null),
            online(singer = "周杰伦", album = "网络专辑"),
            coverUri = "file:///cover.jpg",
        )
        assertEquals("周杰伦", merged.artist)
        assertEquals("已有专辑", merged.album)
        assertEquals("file:///cover.jpg", merged.coverUri)
        val keptCover = mergeLocalInfo(
            localSong(coverUri = "file:///old.jpg", bitDepth = 24),
            online(),
            coverUri = "file:///new.jpg",
        )
        assertEquals("file:///old.jpg", keptCover.coverUri)
        assertEquals(24, keptCover.bitDepth)

        val filledYear = mergeLocalInfo(
            localSong(year = 0),
            online().let { OnlineSong(JSONObject(it.raw.toString()).put("releaseDate", "2005-11-01")) },
            coverUri = null,
        )
        assertEquals(2005, filledYear.year)
        val keptYear = mergeLocalInfo(localSong(year = 1999), online(), coverUri = null)
        assertEquals(1999, keptYear.year)
    }

    @Test
    fun onlineYearSupportsTextAndEpochFields() {
        assertEquals(2024, OnlineSong(JSONObject(online().raw.toString()).put("releaseDate", "2024-03-05")).year)
        assertEquals(2003, OnlineSong(JSONObject(online().raw.toString()).put("publishTime", 1_059_580_800_000L)).year)
        assertNull(OnlineSong(JSONObject(online().raw.toString()).put("releaseDate", "未知")).year)
    }

    @Test
    fun tagExtensionOnlyAllowsWritableContainers() {
        assertEquals(".mp3", tagExtension("audio/mpeg", "content://media/1"))
        assertEquals(".mp3", tagExtension("", "file:///a.mp3"))
        assertEquals(".flac", tagExtension("audio/flac", "file:///a.flac"))
        assertNull(tagExtension("audio/mp4", "file:///a.m4a"))
        assertTrue(DownloadMetadataWriter.supports(".mp3"))
        assertTrue(DownloadMetadataWriter.supports(".flac"))
        assertFalse(DownloadMetadataWriter.supports(".m4a"))
    }

    @Test
    fun qualityBadgesUseMeasuredBitDepthAndDoNotMislabelCompressedFilesAs128k() {
        assertEquals(
            "HR",
            localSong(mimeType = "audio/unknown", uri = "file:///Music/a.flac", sampleRate = 48_000, bitDepth = 24).qualityBadge,
        )
        assertEquals(
            "SQ",
            localSong(mimeType = "audio/flac", sampleRate = 96_000, bitDepth = 16).qualityBadge,
        )
        assertNull(
            localSong(mimeType = "audio/unknown", uri = "file:///Music/a.flac", bitrate = 900_000, bitDepth = -1).qualityBadge,
        )
        assertEquals(
            "MASTER",
            localSong(mimeType = "audio/unknown", uri = "file:///Music/a.dsf", bitDepth = -1).qualityBadge,
        )
        assertEquals("HQ", localSong(mimeType = "audio/mpeg", bitrate = 320_000).qualityBadge)
        assertEquals("128K", localSong(mimeType = "audio/mpeg", bitrate = 128_000).qualityBadge)
        assertEquals("192K", localSong(mimeType = "audio/mpeg", bitrate = 192_000).qualityBadge)
        assertEquals("flac", localSong(mimeType = "audio/flac", sampleRate = 192_000).playbackQuality)
    }

    @Test
    fun recordAudioSpecificationUpdatesLocalIndexAsynchronously() = runBlocking {
        val local = localSong(sampleRate = 0, bitrate = 0, bitDepth = -1)
        LocalMediaStore.replaceAll(listOf(local))

        val job = LocalMediaStore.recordAudioSpecification(
            observed = local,
            spec = AudioSpecification("audio/raw", 48_000, -1, 24),
        )
        job.join()

        val updated = LocalMediaStore.find(local.id)!!
        assertEquals(48_000, updated.sampleRate)
        assertEquals(24, updated.bitDepth)
        assertEquals("HR", updated.qualityBadge)
    }

    @Test
    fun measuredAudioUpdatesOnlyTheObservedFileAndPreservesConcurrentMetadata() = runBlocking {
        val observed = localSong(mimeType = "audio/flac", bitDepth = -1)
        val enriched = observed.copy(coverUri = "file:///new-cover.jpg", artist = "Filled Artist")
        LocalMediaStore.replaceAll(listOf(enriched))
        LocalMediaStore.recordAudioSpecification(observed, AudioSpecification("audio/raw", 48000, -1, 24)).join()
        val updated = LocalMediaStore.find(observed.id)!!
        assertEquals("audio/flac", updated.mimeType)
        assertEquals(enriched.coverUri, updated.coverUri)
        assertEquals(enriched.artist, updated.artist)
        assertEquals(24, updated.bitDepth)
        // 较早启动的联网补全晚到，也不能把实测位深/采样率覆盖回旧快照。
        LocalMediaStore.updateMetadata(observed.copy(coverUri = "file:///late.jpg", modifiedAt = observed.modifiedAt + 100))
        assertEquals(24, LocalMediaStore.find(observed.id)?.bitDepth)
        assertEquals(48000, LocalMediaStore.find(observed.id)?.sampleRate)
        assertEquals("file:///late.jpg", LocalMediaStore.find(observed.id)?.coverUri)

        val replaced = observed.copy(modifiedAt = observed.modifiedAt + 1, bitDepth = 16)
        LocalMediaStore.replaceAll(listOf(replaced))
        LocalMediaStore.recordAudioSpecification(observed, AudioSpecification("audio/flac", 48000, -1, 24)).join()
        assertEquals(replaced, LocalMediaStore.find(observed.id))
        LocalMediaStore.clear()
        LocalMediaStore.recordAudioSpecification(observed, AudioSpecification("audio/flac", 48000, -1, 24)).join()
        assertNull(LocalMediaStore.find(observed.id))
    }

    @Test
    fun autoFillSwitchDefaultsOffAndCanEnable() {
        assertFalse(MeloraSettings.localAutoFillInfo.value)
        MeloraSettings.localAutoFillInfo.value = true
        assertTrue(MeloraSettings.localAutoFillInfo.value)
    }

    @Test
    fun downloadMetadataAlwaysWritesTitleArtistAlbum() {
        val file = java.io.File.createTempFile("melora-info-", ".mp3")
        try {
            file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3, 4, 5, 6, 7, 8))
            DownloadMetadataWriter.write(file, ".mp3", "标题", "歌手", "专辑", null, lyric = null, year = 2005)
            val tag = file.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(tag.contains("TIT2"))
            assertTrue(tag.contains("TPE1"))
            assertTrue(tag.contains("TALB"))
            assertTrue(tag.contains("TYER"))
            assertFalse(tag.contains("APIC"))
            assertFalse(tag.contains("USLT"))
        } finally {
            file.delete()
        }
    }

    private fun OnlineSong.copyRaw(interval: String): OnlineSong = OnlineSong(JSONObject(raw.toString()).put("interval", interval))

    private fun localSong(
        id: String = "ms_1",
        title: String = "夜曲",
        artist: String = "周杰伦",
        album: String = "十一月的萧邦",
        infoFilled: Boolean = false,
        coverUri: String? = null,
        uri: String = "file:///sdcard/Music/$id.mp3",
        mimeType: String = "audio/mpeg",
        sampleRate: Int = 44_100,
        bitrate: Int = 320_000,
        bitDepth: Int = -1,
        year: Int = 2005,
        durationMs: Long = 180_000,
        modifiedAt: Long = 1,
        sizeBytes: Long = 4_000_000,
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
        bitDepth = bitDepth,
        modifiedAt = modifiedAt,
        addedAt = 1,
        year = year,
        folder = "Music",
        coverUri = coverUri,
        infoFilled = infoFilled,
    )

    private fun online(
        source: String = "kw",
        songmid: String = "1",
        name: String = "夜曲",
        singer: String = "周杰伦",
        album: String = "十一月的萧邦",
    ) = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", songmid)
            .put("name", name)
            .put("singer", singer)
            .put("albumName", album),
    )

    private fun track(source: String, uid: String, title: String, artist: String) = UiTrack(
        uid = uid,
        title = title,
        artist = artist,
        album = "",
        source = source,
    )
}
