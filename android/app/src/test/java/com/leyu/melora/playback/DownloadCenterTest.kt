package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCenterTest {
    @Test
    fun explicitTaskIdSurvivesResolvedSongSnapshotAndPausedRoundTrip() {
        val taskId = "local_task-download-center"
        val resolved = OnlineSong(songJson(null).put("source", "kw").put("songmid", "resolved-online"))
        try {
            DownloadCenter.start(taskId, resolved, "等待下载…")
            DownloadCenter.progress(taskId, 38)
            DownloadCenter.paused(taskId, "已暂停")

            val record = DownloadCenter.records.value.first { it.id == taskId }
            assertEquals(taskId, record.id)
            assertEquals(resolved.uid, record.song?.uid)
            assertEquals(DownloadCenter.Status.Paused, record.status)
            assertEquals(38, record.percent)
            assertEquals(DownloadCenter.Status.Paused, DownloadCenter.recordFromJson(DownloadCenter.recordToJson(record))?.status)
        } finally {
            DownloadCenter.remove(taskId)
        }
    }

    @Test
    fun migratesLegacyTopLevelArtworkIntoSongSnapshot() {
        val record = DownloadCenter.recordFromJson(
            recordJson(songJson(img = null)).put("img", "https://example.com/legacy.jpg"),
        )!!

        assertEquals("https://example.com/legacy.jpg", record.img)
        assertEquals("https://example.com/legacy.jpg", record.song?.img)
        assertFalse(DownloadCenter.recordToJson(record).has("img"))
    }

    @Test
    fun keepsCanonicalSongArtworkWhenLegacyFieldDiffers() {
        val record = DownloadCenter.recordFromJson(
            recordJson(songJson(img = "https://example.com/song.jpg"))
                .put("img", "https://example.com/legacy.jpg"),
        )!!

        assertEquals("https://example.com/song.jpg", record.img)
    }

    @Test
    fun enrichesMissingArtworkWithoutMutatingOriginalSong() {
        val original = record(songJson(img = null))
        val enriched = DownloadCenter.withArtwork(original, "https://example.com/resolved.jpg")

        assertNull(original.img)
        assertEquals("https://example.com/resolved.jpg", enriched.img)
        assertEquals(original.id, enriched.id)
        assertEquals(original.updatedAt, enriched.updatedAt)
    }

    @Test
    fun artworkEnrichmentIsIdempotentAndRejectsInvalidUrls() {
        val existing = record(songJson(img = "https://example.com/existing.jpg"))
        assertSame(existing, DownloadCenter.withArtwork(existing, "https://example.com/other.jpg"))

        val missing = record(songJson(img = null))
        assertSame(missing, DownloadCenter.withArtwork(missing, "file:///tmp/cover.jpg"))
        assertSame(missing, DownloadCenter.withArtwork(missing, ""))
    }

    @Test
    fun savedFileUriRoundTripsWithoutLosingOnlineSongIdentity() {
        for (uri in listOf("content://media/external_primary/audio/media/42", "content://documents/document/music%3A42", "file:///storage/emulated/0/Music/Melora/test.flac")) {
            val saved = record(songJson(null)).copy(savedUri = uri)
            val restored = DownloadCenter.recordFromJson(DownloadCenter.recordToJson(saved))!!
            assertEquals(uri, restored.savedUri)
            assertEquals(saved.fileName, restored.fileName)
            assertEquals(saved.song?.uid, restored.song?.uid)
            assertEquals(saved.status, restored.status)
        }
    }

    @Test
    fun legacyJsonWithoutAudioSpecRemainsUnknownWithoutInferringFromDetail() {
        val restored = DownloadCenter.recordFromJson(
            recordJson(songJson(null)).put("detail", "已保存：HR"),
        )!!

        assertNull(restored.audioSpec)
        assertFalse(DownloadCenter.recordToJson(restored).has("audioSpec"))
    }

    @Test
    fun audioSpecJsonRoundTripsStructuredFieldsAndUnknownValues() {
        val spec = AudioSpecification("audio/flac", 96_000, -1, 24)
        val saved = record(songJson(null)).copy(audioSpec = spec)
        val audioSpecJson = DownloadCenter.recordToJson(saved).getJSONObject("audioSpec")

        assertEquals("audio/flac", audioSpecJson.getString("mimeType"))
        assertEquals(96_000, audioSpecJson.getInt("sampleRate"))
        assertEquals(-1, audioSpecJson.getInt("bitrate"))
        assertEquals(24, audioSpecJson.getInt("bitDepth"))
        assertEquals(spec, DownloadCenter.recordFromJson(DownloadCenter.recordToJson(saved))?.audioSpec)

        val unknownJson = DownloadCenter.recordToJson(
            saved.copy(audioSpec = AudioSpecification(null, -1, -1)),
        ).getJSONObject("audioSpec")
        assertTrue(unknownJson.has("mimeType"))
        assertTrue(unknownJson.isNull("mimeType"))
        assertEquals(-1, unknownJson.getInt("sampleRate"))
        assertEquals(-1, unknownJson.getInt("bitrate"))
        assertEquals(-1, unknownJson.getInt("bitDepth"))
    }

    @Test
    fun savedRecordRoundTripsAudioSpecArtworkAndResourceState() {
        val saved = record(songJson("https://example.com/cover.jpg")).copy(
            status = DownloadCenter.Status.Failed,
            percent = 0,
            detail = "网络失败",
            savedUri = "content://media/external_primary/audio/media/42",
            audioSpec = AudioSpecification("audio/flac", 192_000, -1, 24),
        )

        val restored = DownloadCenter.recordFromJson(DownloadCenter.recordToJson(saved))!!

        assertEquals(saved.audioSpec, restored.audioSpec)
        assertEquals(saved.fileName, restored.fileName)
        assertEquals(saved.savedUri, restored.savedUri)
        assertTrue(restored.hasSavedResource)
        assertEquals(saved.img, restored.img)
        assertEquals(saved.detail, restored.detail)
    }

    @Test
    fun oldFilenameOnlyRecordsRemainReadableButRemoteUrlsAreNotLocalFiles() {
        for (value in listOf(null, "", "https://example.com/audio.mp3", "null")) {
            val raw = recordJson(songJson(null))
            if (value != null) raw.put("savedUri", value)
            val restored = DownloadCenter.recordFromJson(raw)!!
            assertNull(restored.savedUri)
            assertEquals("测试歌曲.flac", restored.fileName)
            assertEquals(DownloadCenter.Status.Done, restored.status)
            assertTrue(restored.hasSavedResource)
        }
    }

    @Test
    fun savedResourceSurvivesRetryFailureAndIsClearedExplicitly() {
        val song = OnlineSong(songJson(null).put("songmid", "local-regression"))
        val spec = AudioSpecification("audio/flac", 96_000, -1, 24)
        try {
            DownloadCenter.start(song, "test")
            assertNull(DownloadCenter.saved(song.uid))
            DownloadCenter.done(song.uid, "done", "test.flac", audioSpec = spec)
            val uri = "content://media/external_primary/audio/media/42"
            DownloadCenter.rememberSavedUri(song.uid, uri, spec)
            val saved = DownloadCenter.saved(song.uid)!!
            DownloadCenter.rememberSavedUri(song.uid, uri)
            assertEquals(saved, DownloadCenter.saved(song.uid))
            assertEquals(uri, saved.savedUri)
            DownloadCenter.start(song, "retry")
            val inFlight = DownloadCenter.saved(song.uid)!!
            assertEquals(DownloadCenter.Status.Downloading, inFlight.status)
            assertEquals("test.flac", inFlight.fileName)
            assertEquals(uri, inFlight.savedUri)
            assertEquals(spec, inFlight.audioSpec)

            DownloadCenter.failed(song.uid, "network")
            val failed = DownloadCenter.saved(song.uid)!!
            assertEquals(DownloadCenter.Status.Failed, failed.status)
            assertEquals(uri, failed.savedUri)
            assertEquals(spec, failed.audioSpec)

            DownloadCenter.rememberSavedUri(song.uid, "content://media/other")
            assertEquals("content://media/other", DownloadCenter.saved(song.uid)?.savedUri)
            DownloadCenter.clearSaved(song.uid, "deleted")
            assertNull(DownloadCenter.saved(song.uid))
            val cleared = DownloadCenter.records.value.first { it.id == song.uid }
            assertNull(cleared.fileName)
            assertNull(cleared.savedUri)
            assertFalse(cleared.hasSavedResource)
            assertNull(cleared.audioSpec)
        } finally {
            DownloadCenter.remove(song.uid)
        }
    }

    @Test
    fun changedSavedAddressWithoutAudioSpecDoesNotReusePreviousQuality() {
        val song = OnlineSong(songJson(null).put("songmid", "address-change"))
        val oldSpec = AudioSpecification("audio/flac", 96_000, -1, 24)
        val oldUri = "content://media/external_primary/audio/media/old"
        val newUri = "content://media/external_primary/audio/media/new"
        try {
            DownloadCenter.start(song, "first")
            DownloadCenter.done(song.uid, "已保存：HR", "old.flac", oldUri, oldSpec)
            DownloadCenter.start(song, "retry")

            DownloadCenter.done(song.uid, "已保存：HR", "new.mp3", newUri)

            val replaced = DownloadCenter.records.value.first { it.id == song.uid }
            assertEquals("new.mp3", replaced.fileName)
            assertEquals(newUri, replaced.savedUri)
            assertTrue(replaced.hasSavedResource)
            assertNull(replaced.audioSpec)
        } finally {
            DownloadCenter.remove(song.uid)
        }
    }

    @Test
    fun rememberAudioSpecificationOnlyUpdatesMatchingSavedUri() {
        val song = OnlineSong(songJson(null).put("songmid", "remember-spec"))
        val uri = "content://media/external_primary/audio/media/remember"
        val spec = AudioSpecification("audio/mpeg", 44_100, 320_000)
        try {
            DownloadCenter.start(song, "test")
            DownloadCenter.done(song.uid, "done", "test.mp3", uri)

            DownloadCenter.rememberAudioSpecification(song.uid, "content://media/other", spec)
            assertNull(DownloadCenter.records.value.first { it.id == song.uid }.audioSpec)

            DownloadCenter.rememberAudioSpecification(song.uid, uri, spec)
            assertEquals(spec, DownloadCenter.records.value.first { it.id == song.uid }.audioSpec)
        } finally {
            DownloadCenter.remove(song.uid)
        }
    }

    @Test
    fun failedSavedResourceRoundTripsWithNewLifecycleMarker() {
        val failed = record(songJson(null)).copy(
            status = DownloadCenter.Status.Failed,
            detail = "network",
            savedUri = "content://media/external_primary/audio/media/42",
        )

        val restored = DownloadCenter.recordFromJson(DownloadCenter.recordToJson(failed))!!

        assertEquals(DownloadCenter.Status.Failed, restored.status)
        assertEquals(failed.fileName, restored.fileName)
        assertEquals(failed.savedUri, restored.savedUri)
        assertTrue(restored.hasSavedResource)
    }

    @Test
    fun legacyFailedFilenameIsNotTreatedAsSavedResource() {
        val legacy = recordJson(songJson(null)).put("status", "Failed")

        val restored = DownloadCenter.recordFromJson(legacy)!!

        assertEquals(DownloadCenter.Status.Failed, restored.status)
        assertNull(restored.fileName)
        assertNull(restored.savedUri)
        assertFalse(restored.hasSavedResource)
    }

    @Test
    fun relinkAndStaleDeletionNeverCarryOrClearAnotherResourcesQuality() {
        val song = OnlineSong(songJson(null).put("songmid", "relink-safe"))
        val high = AudioSpecification("audio/flac", 48000, -1, 24)
        try {
            DownloadCenter.start(song, "test")
            DownloadCenter.done(song.uid, "old", "old.flac", "content://media/old", high)
            DownloadCenter.rememberSavedUri(song.uid, "content://media/new")
            assertNull(DownloadCenter.saved(song.uid)?.audioSpec)
            DownloadCenter.rememberAudioSpecification(song.uid, "content://media/new", high)
            DownloadCenter.clearSaved(song.uid, expectedUri = "content://media/old")
            assertEquals("content://media/new", DownloadCenter.saved(song.uid)?.savedUri)
            assertEquals(high, DownloadCenter.saved(song.uid)?.audioSpec)
        } finally { DownloadCenter.remove(song.uid) }
    }

    @Test
    fun deletingSharedFileClearsAliasesButPreservesUpgradedRecord() {
        val original = OnlineSong(songJson(null).put("songmid", "shared-file-original"))
        val alias = OnlineSong(songJson(null).put("source", "tx").put("songmid", "shared-file-alias"))
        try {
            for (song in listOf(original, alias)) {
                DownloadCenter.start(song, "test")
                DownloadCenter.done(song.uid, "saved", "old.mp3", "content://media/shared")
            }
            DownloadCenter.done(original.uid, "upgraded", "new.flac", "content://media/hr")
            DownloadCenter.clearSaved(original.uid, expectedUri = "content://media/shared")
            assertNull(DownloadCenter.saved(alias.uid))
            assertEquals("content://media/hr", DownloadCenter.saved(original.uid)?.savedUri)
        } finally {
            DownloadCenter.remove(original.uid)
            DownloadCenter.remove(alias.uid)
        }
    }

    @Test
    fun sharedResourceDeletionDoesNotInterruptAnotherAliasUpgrade() {
        val song = OnlineSong(songJson(null).put("songmid", "alias-upgrading"))
        try {
            DownloadCenter.start(song, "first")
            DownloadCenter.done(song.uid, "saved", "old.mp3", "content://media/shared")
            DownloadCenter.start(song, "upgrading")
            DownloadCenter.progress(song.uid, 25)
            DownloadCenter.clearSaved("another-platform-id", expectedUri = "content://media/shared")
            val running = DownloadCenter.records.value.first { it.id == song.uid }
            assertEquals(DownloadCenter.Status.Downloading, running.status)
            assertEquals(25, running.percent)
            assertFalse(running.hasSavedResource)
            assertNull(running.audioSpec)
        } finally { DownloadCenter.remove(song.uid) }
    }

    private fun record(songRaw: JSONObject) = DownloadCenter.Record(
        id = "kw_123",
        name = "测试歌曲",
        song = OnlineSong(songRaw),
        status = DownloadCenter.Status.Done,
        percent = 100,
        detail = "已保存",
        fileName = "测试歌曲.flac",
        updatedAt = 123L,
    )

    private fun songJson(img: String?): JSONObject = JSONObject()
        .put("source", "kw")
        .put("songmid", "123")
        .put("name", "测试歌曲")
        .put("singer", "测试歌手")
        .put("albumName", "测试专辑")
        .apply { if (img != null) put("img", img) }

    private fun recordJson(song: JSONObject): JSONObject = JSONObject()
        .put("id", "kw_123")
        .put("name", "测试歌曲")
        .put("song", song)
        .put("status", "Done")
        .put("percent", 100)
        .put("detail", "已保存")
        .put("fileName", "测试歌曲.flac")
        .put("updatedAt", 123L)
}
