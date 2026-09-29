package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistSyncTest {
    private val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
    private fun song(id: String, source: String = "wy") = OnlineSong(JSONObject()
        .put("source", source).put("songmid", id).put("name", id))
    private fun remote(vararg ids: String) = PlaylistImportResult("网易云音乐", "远端改名", null,
        ids.map { song(it) }, ids.size, 0, null, link)
    private fun local() = UserLibrary.UserPlaylist("same-id", "手动改名", listOf(song("a"), song("b"), song("extra")),
        link, setOf("wy_a", "wy_b"))

    @Test fun incompleteReadsAreNeverAnUpdatePreview() = runBlocking {
        val incomplete = listOf(
            PlaylistImporter.read(link) { n -> if (n == 2) error("offline") else SongPage(listOf(song("a")), 3, n, 3) },
            PlaylistImporter.read(link) { n -> SongPage(listOf(song("a")), 3, n, 3) },
            PlaylistImporter.read(link) { n -> SongPage(if (n == 1) listOf(song("a")) else emptyList(), 3, n, 3) },
            PlaylistImporter.read(link) { SongPage(listOf(song("a")), 3, 1, 1) },
            PlaylistImporter.read(link) { SongPage(listOf(song("a")), 2, 1, 1, rawCount = 2) },
            remote("a").copy(warning = "读取上限"),
        )
        incomplete.forEach { result ->
            assertNotNull(result.warning)
            assertThrows(IllegalArgumentException::class.java) { previewPlaylistSync(local(), result) }
        }
    }

    @Test fun inconsistentPaginationMetadataCannotReachAnUpdatePreview() = runBlocking {
        val first = SongPage(listOf(song("a"), song("b")), 4, 1, 2, pageSize = 2)
        val second = SongPage(listOf(song("c"), song("d")), 4, 2, 2, pageSize = 2)
        val variants = linkedMapOf(
            "total decreased" to second.copy(total = 3),
            "total increased" to second.copy(total = 5, list = listOf(song("c"), song("d"), song("e")), rawCount = 3),
            "total disappeared" to second.copy(total = 0),
            "allPage decreased" to second.copy(allPage = 1),
            "allPage disappeared" to second.copy(allPage = 0),
            "pageSize changed" to second.copy(pageSize = 3),
            "pageSize invalid" to second.copy(pageSize = 0),
            "wrong page" to second.copy(page = 1),
        )
        val accepted = variants.mapNotNull { (label, last) ->
            val result = PlaylistImporter.read(link) { n -> if (n == 1) first else last }
            val rejected = runCatching { previewPlaylistSync(local(), result) }.isFailure
            label.takeUnless { result.warning != null && rejected }
        }
        assertEquals("Metadata contradictions must not authorize managed deletions", emptyList<String>(), accepted)
    }

    @Test fun reportedTotalMustMatchRawRecordsNotTheDeduplicatedSongCount() = runBlocking {
        val records = listOf(song("a"), song("a"), song("b"), song("c"))
        val complete = PlaylistImporter.read(link) { SongPage(records, 4, 1, 1) }
        assertNull(complete.warning)
        assertEquals(1, complete.duplicates)
        assertEquals(listOf("wy_a", "wy_b", "wy_c"), previewPlaylistSync(local(), complete).updated.lastSyncedUids!!.toList())
        for (reported in listOf(3, 5)) {
            val inconsistent = PlaylistImporter.read(link) { SongPage(records, reported, 1, 1) }
            assertNotNull("total=$reported contradicts four raw records even if deduplication yields three", inconsistent.warning)
            assertThrows(IllegalArgumentException::class.java) { previewPlaylistSync(local(), inconsistent) }
        }
    }

    @Test fun consistentPagesCanContainDuplicatesWithinAPageWithoutBeingRejected() = runBlocking {
        val result = PlaylistImporter.read(link) { n ->
            SongPage(if (n == 1) listOf(song("a"), song("a")) else listOf(song("b"), song("c")),
                total = 4, page = n, allPage = 2, pageSize = 2)
        }
        assertNull(result.warning)
        assertEquals(1, result.duplicates)
        assertEquals(listOf("wy_a", "wy_b", "wy_c"), previewPlaylistSync(local(), result).updated.lastSyncedUids!!.toList())
    }

    @Test fun emptyMissingOrMismatchedSourceCannotClearOrRebindAnExistingPlaylist() {
        for (invalid in listOf(remote(), remote("a").copy(importSource = null),
            remote("a").copy(importSource = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=456")),
            remote("a").copy(importSource = PlaylistImportLink("wy", "https://evil.example/playlist/1")))) {
            assertThrows(IllegalArgumentException::class.java) { previewPlaylistSync(local(), invalid) }
        }
    }

    @Test fun firstBindingKeepsEveryExistingSongAndDeduplicatesRemoteOverlap() {
        val before = local().copy(importSource = null, lastSyncedUids = null,
            songs = listOf(song("a"), song("b"), song("extra"), song("extra"), song("b", "tx")))
        val first = previewPlaylistSync(before, remote("b", "b", "c"))
        assertTrue(first.firstBinding)
        assertEquals(listOf("wy_b", "wy_c", "wy_a", "wy_extra", "tx_b"), first.updated.songs.map { it.uid })
        assertTrue(first.removed.isEmpty())
        val next = previewPlaylistSync(first.updated, remote("c"))
        assertEquals(listOf("wy_c", "wy_a", "wy_extra", "tx_b"), next.updated.songs.map { it.uid })
        assertFalse(next.firstBinding)
    }

    @Test fun locallyDeletedRemoteSongsReturnAndRemoteExtrasAreNotDuplicated() {
        val before = local().copy(songs = listOf(song("b"), song("extra"), song("extra")))
        val result = previewPlaylistSync(before, remote("extra", "a", "b", "b"))
        assertEquals(listOf("wy_extra", "wy_a", "wy_b"), result.updated.songs.map { it.uid })
        assertEquals(listOf("wy_a"), result.added.map { it.uid })
        assertTrue(result.removed.isEmpty())
    }

    @Test fun unchangedVersusReorderAndMetadataRefreshAreDistinguished() {
        val before = local()
        assertFalse(previewPlaylistSync(before, remote("a", "b")).hasChanges)
        assertTrue(previewPlaylistSync(before, remote("b", "a")).hasChanges)
        val changed = remote("a", "b")
        changed.songs.first().raw.put("name", "新元数据")
        assertTrue(previewPlaylistSync(before, changed).hasChanges)
    }

    @Test fun duplicateLegacyRemovalsAppearOnlyOnceInDifferenceList() {
        val before = local().copy(songs = listOf(song("a"), song("a"), song("b")))
        val preview = previewPlaylistSync(before, remote("b"))
        assertEquals(listOf("wy_a"), preview.removed.map { it.uid })
    }

    @Test fun previewUsesRemoteOrderRemovesOnlyManagedTracksAndKeepsLocalIdentity() {
        val before = local()
        val preview = previewPlaylistSync(before, remote("b", "c"))
        assertEquals(listOf("wy_b", "wy_c", "wy_extra"), preview.updated.songs.map { it.uid })
        assertEquals(before.id, preview.updated.id)
        assertEquals(before.name, preview.updated.name)
        assertEquals(setOf("wy_b", "wy_c"), preview.updated.lastSyncedUids)
        assertEquals(listOf("wy_c"), preview.added.map { it.uid })
        assertEquals(listOf("wy_a"), preview.removed.map { it.uid })
        assertEquals(listOf("wy_a", "wy_b", "wy_extra"), before.songs.map { it.uid })
    }
}
