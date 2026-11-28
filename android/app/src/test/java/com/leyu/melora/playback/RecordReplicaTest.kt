package com.leyu.melora.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordReplicaTest {
    private val phone = "00000000-0000-0000-0000-000000000001"
    private val tablet = "00000000-0000-0000-0000-000000000002"
    private val car = "00000000-0000-0000-0000-000000000003"

    @Test
    fun historyCompactsBothRollingWindowsAfterHundredsOfPlays() {
        var replica = RecordReplica(phone)
        var previous = recordDocument()
        for (played in 1..450) {
            val current = historyWindow(played)
            replica = RecordHeads(listOf(replica)).edit(replica, previous, current)
            previous = current
        }

        val decoded = roundTrip(replica)
        assertEquals(UserLibrary.MAX_RECENTS + UserLibrary.MAX_RECENT_CONTAINERS, decoded.edits.size)
        assertEquals(451L - UserLibrary.MAX_RECENTS, decoded.historyFloor.getValue("recents"))
        assertEquals(451L - UserLibrary.MAX_RECENT_CONTAINERS, decoded.historyFloor.getValue("recentContainers"))
        val stale = RecordHeads(emptyList()).edit(RecordReplica(tablet), recordDocument(), historyWindow(50))
        val view = RecordHeads(listOf(decoded, stale)).view(decoded, emptyMap())
        assertTrue(RecordSyncMerge.contentEquals(previous, view.records))
    }

    @Test
    fun compactionRetainsAllEntriesAtTheBoundaryTimestamp() {
        val limit = UserLibrary.MAX_RECENTS
        val first = recent(1, 4)
        val tied = recent(2, 4)
        val newer = (3..limit).map { recent(it, it.toLong() + 2) }
        val baseline = libraryWithRecents(listOf(first, tied) + newer)
        val initial = RecordHeads(emptyList()).edit(RecordReplica(phone), recordDocument(), baseline)
        val current = libraryWithRecents(listOf(tied) + newer + recent(limit + 1, limit.toLong() + 3))
        val rolled = roundTrip(RecordHeads(listOf(initial)).edit(initial, baseline, current))

        val removed = RecordKey("recents", RecordSyncMerge.rowKey("recents", first))
        val surviving = RecordKey("recents", RecordSyncMerge.rowKey("recents", tied))
        assertEquals(4L, rolled.historyFloor.getValue("recents"))
        assertEquals(4L, rolled.edits.getValue(removed).deletedPlaybackTime)
        assertNotNull(rolled.edits.getValue(surviving).value)

        val advanced = libraryWithRecents(newer + recent(limit + 1, limit.toLong() + 3) + recent(limit + 2, limit.toLong() + 4))
        val compacted = RecordHeads(listOf(rolled)).edit(rolled, current, advanced)
        assertFalse(removed in compacted.edits || surviving in compacted.edits)
    }

    @Test
    fun explicitRecentDeletionDoesNotResurrectAndConcurrentReplaySurvives() {
        val baseline = libraryWithRecents(listOf(recent(1, 100)))
        val first = RecordHeads(emptyList()).edit(RecordReplica(phone), recordDocument(), baseline)
        val deleted = roundTrip(RecordHeads(listOf(first)).edit(RecordReplica(tablet), baseline, recordDocument()))
        assertEquals(100L, deleted.edits.values.single().deletedPlaybackTime)
        val beforeReplay = RecordHeads(listOf(first, deleted)).view(first, emptyMap()).records
        assertEquals(0, beforeReplay.getJSONObject("library").getJSONArray("recents").length())

        val replay = libraryWithRecents(listOf(recent(1, 120)))
        val replayed = RecordHeads(listOf(first)).edit(first, baseline, replay)
        val merged = RecordHeads(listOf(replayed, deleted)).view(replayed, emptyMap())
        assertTrue(merged.conflicts.isEmpty())
        assertTrue(RecordSyncMerge.contentEquals(replay, merged.records))

        val cleared = RecordHeads(listOf(first, deleted)).edit(deleted, recordDocument(), recordDocument(cleared = 110))
        assertTrue(cleared.edits.isEmpty())
        val kept = RecordHeads(listOf(cleared, replayed)).view(cleared, emptyMap()).records
        assertEquals(110L, kept.getJSONObject("library").getLong("historyClearedAt"))
        assertEquals(1, kept.getJSONObject("library").getJSONArray("recents").length())
    }

    @Test
    fun sameDeviceRewindSurvivesLaterProgressUntilEveryCandidateHasSeenIt() {
        val baseline = bookAt(100_000, 1)
        val started = RecordHeads(emptyList()).edit(RecordReplica(phone), recordDocument(), baseline)
        val advanced = bookAt(200_000, 2)
        val beforeRewind = RecordHeads(listOf(started)).edit(started, baseline, advanced)
        val rewind = bookAt(150_000, 3)
        val rewound = RecordHeads(listOf(beforeRewind)).edit(beforeRewind, advanced, rewind)
        val continued = bookAt(160_000, 4)
        val afterRewind = roundTrip(RecordHeads(listOf(rewound)).edit(rewound, rewind, continued))
        assertEquals(RecordStamp(phone, rewound.sequence), afterRewind.edits.values.single().rewind)

        val remote = bookAt(190_000, 5)
        val offline = RecordHeads(listOf(started)).edit(RecordReplica(tablet), baseline, remote)
        val unresolved = RecordHeads(listOf(afterRewind, offline)).view(afterRewind, emptyMap())
        assertEquals(1, unresolved.conflicts.size)

        val aware = RecordHeads(listOf(rewound)).edit(RecordReplica(tablet), rewind, remote)
        val safe = RecordHeads(listOf(afterRewind, aware)).view(afterRewind, emptyMap())
        assertTrue(safe.conflicts.isEmpty())
        assertTrue(RecordSyncMerge.contentEquals(remote, safe.records))
    }

    @Test
    fun inconsistentAncestorsDoNotMakeOneOfTwoConcurrentRenamesLookUnchanged() {
        val base = mapOf(car to 1L)
        val first = playlist("周末")
        val second = playlist("运动")
        val key = RecordKey("playlists", "same")
        val a = RecordReplica(phone, 1, edits = mapOf(key to RecordEdit(base + (phone to 1L), base, second, first)))
        val b = RecordReplica(tablet, 1, edits = mapOf(key to RecordEdit(base + (tablet to 1L), base, first, second)))

        val conflict = RecordHeads(listOf(a, b)).view(a, emptyMap()).conflicts.single()
        assertTrue(conflict.localLabel.contains("周末"))
        assertTrue(conflict.remoteLabel.contains("运动"))
    }

    @Test
    fun identicalConcurrentRenamesFromTwoDevicesPresentOneChoice() {
        val baseline = recordDocument(mapOf(RecordKey("playlists", "same") to playlist("原名")))
        val initial = RecordHeads(emptyList()).edit(RecordReplica(phone), recordDocument(), baseline)
        val shared = RecordHeads(listOf(initial))
        fun renamed(name: String) = recordDocument(mapOf(RecordKey("playlists", "same") to playlist(name)))
        val a = shared.edit(initial, baseline, renamed("通勤"))
        val b = shared.edit(RecordReplica(tablet), baseline, renamed("轻音乐"))
        val c = shared.edit(RecordReplica(car), baseline, renamed("轻音乐"))

        val heads = RecordHeads(listOf(c, a, b))
        val conflict = heads.view(a, emptyMap()).conflicts.single()
        val agreed = heads.view(a, mapOf(conflict.key to (conflict.localLabel == "轻音乐")))
        assertTrue(agreed.conflicts.isEmpty())
        assertTrue(RecordSyncMerge.contentEquals(renamed("轻音乐"), agreed.records))
    }

    @Test
    fun thirdDeviceCanAddTracksWithoutSettlingAnotherDevicesPendingRename() {
        val key = RecordKey("playlists", "same")
        val baseline = recordDocument(mapOf(key to playlist("原名", "原有")))
        for (previousOnCar in listOf(false, true)) {
            val founder = if (previousOnCar) car else phone
            val initial = RecordHeads(emptyList()).edit(RecordReplica(founder), recordDocument(), baseline)
            val prior = RecordHeads(listOf(initial))
            val phoneVersion = prior.edit(if (founder == phone) initial else RecordReplica(phone), baseline,
                recordDocument(mapOf(key to playlist("通勤", "原有", "手机新曲"))))
            val tabletVersion = prior.edit(RecordReplica(tablet), baseline,
                recordDocument(mapOf(key to playlist("轻音乐", "原有", "平板新曲"))))
            val carVersion = if (previousOnCar) initial else RecordReplica(car)
            val heads = RecordHeads(listOf(phoneVersion, tabletVersion, carVersion))
            val preview = heads.view(carVersion, emptyMap()).records
            val carEdit = JSONObject(preview.toString())
            carEdit.getJSONObject("library").getJSONArray("playlists").getJSONObject(0)
                .getJSONArray("songs").put(song("车机新曲"))
            val edited = roundTrip(heads.edit(carVersion, preview, carEdit))

            val updated = RecordHeads(listOf(phoneVersion, tabletVersion, edited))
            val choice = updated.view(edited, emptyMap()).conflicts.single()
            assertEquals(setOf("通勤", "轻音乐"), setOf(choice.localLabel, choice.remoteLabel))
            val agreed = updated.view(edited, mapOf(choice.key to (choice.localLabel == "轻音乐")))
            val saved = agreed.records.getJSONObject("library").getJSONArray("playlists").getJSONObject(0)
            assertEquals("轻音乐", saved.getString("name"))
            val songs = saved.getJSONArray("songs")
            assertEquals(setOf("原有", "手机新曲", "平板新曲", "车机新曲"),
                (0 until songs.length()).map { songs.getJSONObject(it).getString("songmid") }.toSet())
        }
    }

    private fun historyWindow(last: Int): JSONObject {
        val recents = (last downTo maxOf(1, last - UserLibrary.MAX_RECENTS + 1)).map { recent(it, it.toLong()) }
        val containers = (last downTo maxOf(1, last - UserLibrary.MAX_RECENT_CONTAINERS + 1)).map {
            JSONObject().put("source", "wy").put("kind", "playlist").put("id", "list-$it").put("name", "列表 $it")
                .put("updatedAt", it.toLong())
        }
        return libraryWithRecents(recents, containers)
    }

    private fun libraryWithRecents(recents: List<JSONObject>, containers: List<JSONObject> = emptyList()): JSONObject {
        val library = JSONObject().put("recents", JSONArray(recents)).put("recentContainers", JSONArray(containers))
        return RecordSyncMerge.project(library, JSONArray())
    }

    private fun recent(id: Int, time: Long) = JSONObject().put("source", "wy").put("songmid", "track-$id")
        .put("name", "歌曲 $id").put("playedAt", time)

    private fun song(id: String) = JSONObject().put("source", "wy").put("songmid", id).put("name", id)

    private fun playlist(name: String, vararg songs: String) =
        JSONObject().put("id", "same").put("name", name).put("songs", JSONArray(songs.map(::song)))

    private fun bookAt(position: Long, time: Long): JSONObject {
        val chapter = JSONObject().put("source", "kw").put("songmid", "chapter").put("name", "一章")
            .put("albumId", "book_album_42").put("isBookChapter", true)
        val point = JSONObject().put("song", chapter).put("positionMs", position).put("durationMs", 600_000L)
            .put("completed", false).put("updatedAt", time)
        return RecordSyncMerge.project(JSONObject(), JSONArray(listOf(point)))
    }

    private fun roundTrip(replica: RecordReplica): RecordReplica {
        val text = replica.encode()
        val name = "melora-records-v2-${replica.device}-${replica.sequence}-${text.sha256Hex()}.json"
        return RecordReplica.decode(name, text)
    }
}
