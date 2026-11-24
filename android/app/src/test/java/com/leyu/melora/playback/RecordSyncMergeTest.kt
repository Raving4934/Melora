package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.PlaylistImportLink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSyncMergeTest {
    @Test fun firstSyncUnionsAllKindsOfFavoritesAndKeepsLocalOnlyData() {
        val local = JSONObject()
            .put("favorites", JSONArray(listOf(song("left"), song("device", source = "local").put("uri", "content://device/1"))))
            .put("favoritePlaylists", JSONArray(listOf(playlistFavorite("left"))))
            .put("favoriteAlbums", JSONArray(listOf(collection("left"))))
            .put("favoriteArtists", JSONArray(listOf(collection("left"))))
            .put("searchHistory", JSONArray(listOf("本机关键词")))
        val remote = JSONObject()
            .put("favorites", JSONArray(listOf(song("right"))))
            .put("favoritePlaylists", JSONArray(listOf(playlistFavorite("right"))))
            .put("favoriteAlbums", JSONArray(listOf(collection("right"))))
            .put("favoriteArtists", JSONArray(listOf(collection("right"))))

        val shared = RecordSyncMerge.review(null, project(local), project(remote)).records
        val applied = RecordSyncMerge.applyLibrary(local, shared)
        assertEquals(setOf("left", "right"), trackIds(shared.library().getJSONArray("favorites")).toSet())
        assertEquals(setOf("left", "right", "device"), trackIds(applied.getJSONArray("favorites")).toSet())
        for (field in listOf("favoritePlaylists", "favoriteAlbums", "favoriteArtists")) {
            assertEquals(setOf("left", "right"), rows(shared.library().getJSONArray(field)).map { it.getString("name") }.toSet())
        }
        assertFalse(shared.library().has("searchHistory"))
        assertEquals("本机关键词", applied.getJSONArray("searchHistory").getString(0))
        assertEquals("content://device/1", rows(applied.getJSONArray("favorites")).first { it.optString("source") == "local" }.getString("uri"))
        assertTrue(RecordSyncMerge.contentEquals(project(applied), shared))
    }

    @Test fun aStaleOfflineDeviceDoesNotRestoreAnUnfavoritedSong() {
        val baseline = snapshot(listOf(song("keep"), song("removed")))
        val updatedServer = snapshot(listOf(song("keep"), song("fromServer")))
        val disconnected = snapshot(listOf(song("fromOffline"), song("keep"), song("removed").put("img", "https://covers.example/new.png")))

        val merged = RecordSyncMerge.review(baseline, disconnected, updatedServer).records
        assertEquals(setOf("keep", "fromServer", "fromOffline"), favoriteIds(merged).toSet())
        val next = RecordSyncMerge.review(merged, merged, merged).records
        assertTrue(RecordSyncMerge.contentEquals(merged, next))
    }

    @Test fun concurrentPlaylistChangesMergeNameMembersAndOrder() {
        val baseline = playlistSnapshot(playlist("same", "original", song("a"), song("b"), song("c")))
        val local = playlistSnapshot(playlist("same", "renamed", song("b"), song("c"), song("localAddition")))
        val remote = playlistSnapshot(playlist("same", "original", song("a"), song("remoteAddition"), song("b")))

        val merged = RecordSyncMerge.review(baseline, local, remote).records.playlist()
        assertEquals("renamed", merged.getString("name"))
        assertEquals(listOf("remoteAddition", "b", "localAddition"), trackIds(merged.getJSONArray("songs")))
    }

    @Test fun choosingBetweenConcurrentRenamesPreservesBothDevicesTrackEdits() {
        val baseline = playlistSnapshot(playlist("same", "原名", song("a"), song("b"), song("c")))
        val local = playlistSnapshot(playlist("same", "通勤", song("a"), song("b"), song("localAddition")))
        val remote = playlistSnapshot(playlist("same", "轻音乐", song("b"), song("c"), song("remoteAddition")))
        val review = RecordSyncMerge.review(baseline, local, remote)
        val conflict = review.conflicts.single()
        assertEquals("通勤", conflict.localLabel)
        assertEquals("轻音乐", conflict.remoteLabel)
        assertEquals("通勤", review.records.playlist().getString("name"))

        val resolved = RecordSyncMerge.review(baseline, local, remote) {
            if (it.key == conflict.key) false else null
        }
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals("轻音乐", resolved.records.playlist().getString("name"))
        assertEquals(
            listOf("b", "remoteAddition", "localAddition"),
            trackIds(resolved.records.playlist().getJSONArray("songs")),
        )
        val remoteNewName = playlistSnapshot(playlist("same", "周末", song("b"), song("c"), song("remoteAddition")))
        val next = RecordSyncMerge.review(baseline, local, remoteNewName) { if (it.key == conflict.key) false else null }
        assertNotEquals(conflict.key, next.conflicts.single().key)
    }

    @Test fun removingAPlaylistConflictsWithASubstantiveConcurrentEdit() {
        val baseline = playlistSnapshot(playlist("same", "收藏夹", song("a")))
        val edited = playlistSnapshot(playlist("same", "收藏夹", song("a"), song("b")))
        val deleted = project(JSONObject())
        val review = RecordSyncMerge.review(baseline, edited, deleted)
        assertEquals("删除记录", review.conflicts.single().remoteLabel)
        assertEquals(listOf("a", "b"), trackIds(review.records.playlist().getJSONArray("songs")))

        val resolved = RecordSyncMerge.review(baseline, edited, deleted) { false }
        assertEquals(0, resolved.records.library().getJSONArray("playlists").length())
        assertTrue(resolved.conflicts.isEmpty())

        val coverChanged = playlistSnapshot(playlist("same", "收藏夹", song("a").put("img", "https://covers.example/a.jpg")))
        val noConflict = RecordSyncMerge.review(baseline, coverChanged, deleted)
        assertEquals(0, noConflict.records.library().getJSONArray("playlists").length())
        assertTrue(noConflict.conflicts.isEmpty())
    }

    @Test fun preservingAPlaylistReorderKeepsAnAdditionFromAnotherDevice() {
        val baseline = playlistSnapshot(playlist("same", "歌单", song("a"), song("b"), song("c")))
        val local = playlistSnapshot(playlist("same", "歌单", song("a"), song("c"), song("b")))
        val remote = playlistSnapshot(playlist("same", "歌单", song("a"), song("added"), song("b"), song("c")))
        val review = RecordSyncMerge.review(baseline, local, remote)
        assertEquals(listOf("a", "c", "added", "b"), trackIds(review.records.playlist().getJSONArray("songs")))
        assertTrue(review.conflicts.isEmpty())
    }

    @Test fun importedPlaylistExclusionsFollowEditsAndKeepTracksManaged() {
        val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
        fun imported(name: String, songs: List<String>, baseline: List<String>, exclusions: List<String> = emptyList()) =
            playlist("same", name, *songs.map(::song).toTypedArray())
                .put("importSource", JSONObject().put("source", link.source).put("value", link.value))
                .put("lastSyncedUids", JSONArray(baseline.map { "wy_$it" }))
                .put("excludedImportedUids", JSONArray(exclusions.map { "wy_$it" }))
        val baseline = playlistSnapshot(imported("original", listOf("a", "b"), listOf("a", "b")))
        val local = playlistSnapshot(imported("renamed", listOf("b", "c"), listOf("a", "b", "c"), listOf("a")))
        val remote = playlistSnapshot(imported("original", listOf("a", "b", "d"), listOf("a", "b", "d")))

        val merged = RecordSyncMerge.review(baseline, local, remote).records.playlist()
        assertEquals("renamed", merged.getString("name"))
        assertEquals(setOf("b", "c", "d"), trackIds(merged.getJSONArray("songs")).toSet())
        assertEquals(setOf("wy_a", "wy_b", "wy_c", "wy_d"), strings(merged.getJSONArray("lastSyncedUids")).toSet())
        assertEquals(listOf("wy_a"), strings(merged.getJSONArray("excludedImportedUids")))
        assertEquals(link.value, merged.getJSONObject("importSource").getString("value"))
    }

    @Test fun aManuallyAddedTrackSurvivesConcurrentRemovalFromImportedSource() {
        val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
        fun imported(vararg ids: String) = playlist("same", "歌单", *ids.map { song(it) }.toTypedArray())
            .put("importSource", JSONObject().put("source", link.source).put("value", link.value))
            .put("lastSyncedUids", JSONArray(ids.map { "wy_$it" }))
            .put("locallyAddedUids", JSONArray())
        val baseline = playlistSnapshot(imported("a", "b"))
        val local = playlistSnapshot(imported("a", "b").put("locallyAddedUids", JSONArray(listOf("wy_a"))))
        val remote = playlistSnapshot(imported("b"))

        val merged = RecordSyncMerge.review(baseline, local, remote).records.playlist()
        assertEquals(setOf("a", "b"), trackIds(merged.getJSONArray("songs")).toSet())
        assertEquals(listOf("wy_a"), strings(merged.getJSONArray("locallyAddedUids")))
        assertEquals(listOf("wy_b"), strings(merged.getJSONArray("lastSyncedUids")))
    }

    @Test fun competingImportSourcesRequestAChoiceAndKeepTracksFromBoth() {
        fun imported(url: String, chapter: String) = playlistSnapshot(
            playlist("same", "歌单", song("original"), song(chapter)).apply {
                val link = PlaylistImportLink.parse(url)
                put("importSource", JSONObject().put("source", link.source).put("value", link.value))
                put("lastSyncedUids", JSONArray(listOf("wy_$chapter")))
            }
        )
        val baseline = playlistSnapshot(playlist("same", "歌单", song("original")))
        val local = imported("https://music.163.com/#/playlist?id=123", "localTrack")
        val remote = imported("https://www.kuwo.cn/playlist_detail/123", "remoteTrack")
        val review = RecordSyncMerge.review(baseline, local, remote)
        assertTrue(review.conflicts.single().localLabel.startsWith("网易云音乐"))
        assertTrue(review.conflicts.single().remoteLabel.startsWith("酷我音乐"))
        val resolved = RecordSyncMerge.review(baseline, local, remote) { false }
        assertTrue(resolved.conflicts.isEmpty())
        val playlist = resolved.records.playlist()
        assertEquals("kw", playlist.getJSONObject("importSource").getString("source"))
        assertEquals(listOf("wy_remoteTrack"), strings(playlist.getJSONArray("lastSyncedUids")))
        assertEquals(listOf("original", "remoteTrack", "localTrack"), trackIds(playlist.getJSONArray("songs")))
    }

    @Test fun aManualExclusionConflictsWithAManualAdditionOfTheSameTrack() {
        val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
        fun imported(vararg ids: String) = playlist("same", "歌单", *ids.map { song(it) }.toTypedArray())
            .put("importSource", JSONObject().put("source", link.source).put("value", link.value))
            .put("lastSyncedUids", JSONArray(listOf("wy_a", "wy_b")))
        val baseline = playlistSnapshot(imported("a", "b"))
        val local = playlistSnapshot(imported("a", "b", "leftOnly").put("locallyAddedUids", JSONArray(listOf("wy_a"))))
        val remote = playlistSnapshot(imported("b", "rightOnly").put("excludedImportedUids", JSONArray(listOf("wy_a"))))
        val pending = RecordSyncMerge.review(baseline, local, remote)
        assertEquals("保留歌曲", pending.conflicts.single().localLabel)
        assertEquals(setOf("a", "b", "leftOnly", "rightOnly"), trackIds(pending.records.playlist().getJSONArray("songs")).toSet())
        val resolved = RecordSyncMerge.review(baseline, local, remote) { false }
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals(setOf("b", "leftOnly", "rightOnly"), trackIds(resolved.records.playlist().getJSONArray("songs")).toSet())
    }

    @Test fun deletingASharedMixedPlaylistPreservesOnlyUntouchedLocalTracks() {
        val localPlaylist = playlist(
            "mixed", "混合歌单", song("sharedSong"),
            song("localTrack", source = "local").put("uri", "content://device/track"),
        )
        val local = JSONObject().put("playlists", JSONArray(listOf(localPlaylist)))
        val baseline = project(local)
        val merged = RecordSyncMerge.review(baseline, baseline, project(JSONObject())).records

        val applied = RecordSyncMerge.applyLibrary(local, merged)
        val detached = applied.getJSONArray("playlists").getJSONObject(0)
        assertNotEquals("mixed", detached.getString("id"))
        assertEquals(listOf("localTrack"), trackIds(detached.getJSONArray("songs")))
        assertEquals(0, project(applied).library().getJSONArray("playlists").length())

        detached.getJSONArray("songs").put(song("newOnlineSong"))
        val sharedAgain = project(applied).playlist()
        assertEquals(detached.getString("id"), sharedAgain.getString("id"))
        assertEquals(listOf("newOnlineSong"), trackIds(sharedAgain.getJSONArray("songs")))
    }

    @Test fun noRemoteFileKeepsTheEntireLocalProjection() {
        val local = snapshot(listOf(song("offline")))
        val baseline = snapshot(listOf(song("stale")))
        assertTrue(RecordSyncMerge.contentEquals(local, RecordSyncMerge.review(baseline, local, null).records))
    }

    @Test fun playbackHistoryCombinesEventsByPlayTimeAndNewerReplaySurvivesClear() {
        val original = history(
            listOf(song("replayed").put("playedAt", 10L), song("legacy")),
            listOf(container("old", 10L)),
        )
        val local = history(emptyList(), emptyList(), clearedAt = 20L)
        val remote = history(
            listOf(song("afterClear").put("playedAt", 40L), song("replayed").put("playedAt", 30L), song("beforeClear").put("playedAt", 15L)),
            listOf(container("new", 25L), container("old", 10L)),
        )

        val merged = RecordSyncMerge.review(original, local, remote).records.library()
        assertEquals(listOf("afterClear", "replayed"), trackIds(merged.getJSONArray("recents")))
        assertEquals(listOf("new"), rows(merged.getJSONArray("recentContainers")).map { it.getString("id") })
        assertEquals(20L, merged.getLong("historyClearedAt"))
    }

    @Test fun aNewReplaySurvivesEvictionFromAnotherDevicesRecentHistory() {
        val original = history(listOf(song("replayed").put("playedAt", 10L)), emptyList())
        val local = history(listOf(song("newer").put("playedAt", 20L)), emptyList())
        val remote = history(listOf(song("replayed").put("playedAt", 30L)), emptyList())

        val merged = RecordSyncMerge.review(original, local, remote).records.library()
        assertEquals(listOf("replayed", "newer"), trackIds(merged.getJSONArray("recents")))
    }

    @Test fun concurrentChapterSwitchAndRewindCanBeResolvedByTheUser() {
        val original = project(JSONObject(), JSONArray(listOf(book("01", 800, 10L))))
        val local = project(JSONObject(), JSONArray(listOf(book("02", 900, 20L))))
        val remote = project(JSONObject(), JSONArray(listOf(book("01", 100, 30L, albumId = "kw:book_album_42"))))
        val review = RecordSyncMerge.review(original, local, remote)
        assertEquals(1, review.conflicts.size)
        assertEquals("02", review.records.book().getJSONObject("song").getString("songmid"))

        val resolved = RecordSyncMerge.review(original, local, remote) { false }
        val progress = resolved.records.book()
        assertTrue(resolved.conflicts.isEmpty())
        assertEquals(100L, progress.getLong("positionMs"))
        assertEquals("01", progress.getJSONObject("song").getString("songmid"))
        assertEquals(30L, progress.getLong("updatedAt"))
    }

    @Test fun forwardProgressInTheSameChapterMergesAndASequentialRewindIsPreserved() {
        fun point(position: Long, time: Long) = project(JSONObject(), JSONArray(listOf(book("01", position, time))))
        val baseline = point(200, 10)
        val local = point(700, 20)
        val remote = point(500, 30)
        val review = RecordSyncMerge.review(baseline, local, remote)
        assertTrue(review.conflicts.isEmpty())
        assertEquals(700L, review.records.book().getLong("positionMs"))

        val rewound = point(100, 40)
        val sequential = RecordSyncMerge.review(review.records, review.records, rewound)
        assertTrue(sequential.conflicts.isEmpty())
        assertEquals(100L, sequential.records.book().getLong("positionMs"))

        val concurrent = RecordSyncMerge.review(baseline, local, rewound)
        assertEquals(1, concurrent.conflicts.size)
        assertNotEquals(concurrent.conflicts.single().localLabel, concurrent.conflicts.single().remoteLabel)
    }

    @Test fun chapterVariantsWithIdenticalDisplayNamesUseTheirChapterNumbers() {
        fun chapter(id: String, ordinal: Int) = book(id, 800L, 10L).apply {
            getJSONObject("song").put("name", "节目").put("bookOrdinal", ordinal)
        }
        val local = project(JSONObject(), JSONArray(listOf(chapter("chapter-one", 1))))
        val remote = project(JSONObject(), JSONArray(listOf(chapter("chapter-two", 2))))
        val review = RecordSyncMerge.review(null, local, remote)
        val conflict = review.conflicts.single()
        assertTrue(conflict.localLabel.endsWith("第 1 章"))
        assertTrue(conflict.remoteLabel.endsWith("第 2 章"))
    }

    @Test fun unbasedPlaylistVariantsWithDifferentTracksRequestAChoice() {
        val local = playlist("same", "歌单", song("a"))
        val remote = playlist("same", "歌单", song("b"))
        val key = RecordSyncMerge.rowKey("playlists", local)
        val pending = RecordSyncMerge.reviewUnbasedRow("playlists", key, local, remote) { null }
        assertEquals(local, pending.first)
        val conflict = pending.second.single()
        assertNotEquals(conflict.localLabel, conflict.remoteLabel)
        val resolved = RecordSyncMerge.reviewUnbasedRow("playlists", key, local, remote) { it.key != conflict.key }
        assertEquals(remote, resolved.first)
        assertTrue(resolved.second.isEmpty())
    }

    @Test fun projectionOmitsDeviceUrisWithoutLosingLocalMetadataOnAReadBack() {
        val online = song("sample")
            .put("img", "content://device/art")
            .put("extra", JSONObject().put("localPath", "/storage/emulated/0/track.mp3").put("hash", "00"))
        val local = JSONObject().put("favorites", JSONArray(listOf(online)))
        val projected = project(local)
        val shared = projected.library().getJSONArray("favorites").getJSONObject(0)
        assertFalse(shared.has("img"))
        assertFalse(shared.getJSONObject("extra").has("localPath"))
        assertEquals("00", shared.getJSONObject("extra").getString("hash"))
        assertTrue(RecordSyncMerge.contentEquals(projected, RecordSyncMerge.parse(projected.toString())))
        val applied = RecordSyncMerge.applyLibrary(local, projected)
        assertTrue(RecordSyncMerge.contentEquals(online, applied.getJSONArray("favorites").getJSONObject(0)))
    }

    @Test fun invalidAndPartialRemoteFilesFailBeforeTheyCanDeleteLocalRows() {
        val missing = snapshot(emptyList()).apply { library().remove("recents") }
        assertThrows(IllegalArgumentException::class.java) { RecordSyncMerge.parse(missing.toString()) }
        val duplicate = snapshot(listOf(song("dupe"))).apply { library().getJSONArray("favorites").put(song("dupe")) }
        assertThrows(IllegalArgumentException::class.java) { RecordSyncMerge.parse(duplicate.toString()) }
        val localFile = snapshot(emptyList()).apply { library().getJSONArray("favorites").put(song("private", source = "local")) }
        assertThrows(IllegalArgumentException::class.java) { RecordSyncMerge.parse(localFile.toString()) }
        val badTimestamp = history(listOf(song("old")), emptyList()).apply {
            library().getJSONArray("recents").getJSONObject(0).put("playedAt", Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) { RecordSyncMerge.parse(badTimestamp.toString()) }
    }

    @Test fun mergingValidFilesThatExceedTheLibraryLimitFailsBeforeWriting() {
        val local = snapshot((0 until 25_001).map { song("left$it") })
        val remote = snapshot((0 until 25_000).map { song("right$it") })
        assertThrows(IllegalArgumentException::class.java) { RecordSyncMerge.review(null, local, remote) }
    }

    private fun project(library: JSONObject, books: JSONArray = JSONArray()): JSONObject = RecordSyncMerge.project(library, books)
    private fun snapshot(favorites: List<JSONObject>): JSONObject = project(JSONObject().put("favorites", JSONArray(favorites)))
    private fun playlistSnapshot(playlist: JSONObject): JSONObject = project(JSONObject().put("playlists", JSONArray(listOf(playlist))))

    private fun song(id: String, source: String = "wy"): JSONObject =
        JSONObject().put("songmid", id).put("name", id).put("source", source)

    private fun collection(id: String): JSONObject = JSONObject().put("name", id).put("source", "wy")
    private fun playlistFavorite(id: String): JSONObject = collection(id).put("id", id)

    private fun playlist(id: String, name: String, vararg songs: JSONObject): JSONObject =
        JSONObject().put("id", id).put("name", name).put("songs", JSONArray(songs.toList()))

    private fun history(songs: List<JSONObject>, containers: List<JSONObject>, clearedAt: Long = 0L): JSONObject =
        project(JSONObject().put("recents", JSONArray(songs)).put("recentContainers", JSONArray(containers)).apply {
            if (clearedAt > 0L) put("historyClearedAt", clearedAt)
        })

    private fun container(id: String, timestamp: Long): JSONObject =
        JSONObject().put("id", id).put("name", id).put("source", "wy").put("kind", "playlist")
            .put("queueId", "playlist:wy:$id").put("updatedAt", timestamp)

    private fun book(chapter: String, position: Long, time: Long, albumId: String = "book_album_42"): JSONObject =
        JSONObject().put("song", song(chapter, "kw").put("albumId", albumId).put("isBookChapter", true))
            .put("positionMs", position).put("durationMs", 1_000L).put("completed", false).put("updatedAt", time)

    private fun JSONObject.library(): JSONObject = getJSONObject("library")
    private fun JSONObject.playlist(): JSONObject = library().getJSONArray("playlists").getJSONObject(0)
    private fun JSONObject.book(): JSONObject = getJSONArray("books").getJSONObject(0)
    private fun favoriteIds(document: JSONObject): List<String> = trackIds(document.library().getJSONArray("favorites"))
    private fun trackIds(array: JSONArray): List<String> = rows(array).map { it.getString("songmid") }
    private fun rows(array: JSONArray): List<JSONObject> = List(array.length(), array::getJSONObject)
    private fun strings(array: JSONArray): List<String> = List(array.length(), array::getString)
}
