package com.leyu.melora.playback

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WebDavSyncLedgerTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Server : RecordFiles {
        val files = linkedMapOf<String, String>()
        var loseReply = false
        var truncate = false
        var writes = 0
        var listing: List<String>? = null
        var unavailable: String? = null
        override suspend fun listNames() = listing ?: files.keys.toList().reversed()
        override suspend fun read(name: String): String? {
            if (name == unavailable) throw IOException("server unavailable")
            return files[name]
        }
        override suspend fun writeFile(name: String, text: String) {
            files[name] = if (truncate) text.take(text.length / 2) else text
            writes++
            if (loseReply) { loseReply = false; throw IOException("response lost") }
        }
        override suspend fun deleteFile(name: String) { files.remove(name) }
    }

    private class Device(val folder: File, n: Int) {
        val id = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        var data = songs()
        var interruptApply = false
        var conflicts = emptyList<RecordConflict>()
        suspend fun sync(server: Server, choices: Map<String, Boolean> = emptyMap()) {
            conflicts = WebDavSyncLedger(folder).sync(id, server, { data }, { expected, next, prepare, commit ->
                if (!RecordSyncMerge.contentEquals(data, expected)) false else {
                    prepare()
                    data = next
                    if (interruptApply) { interruptApply = false; error("process killed") }
                    commit()
                    true
                }
            }, choices)
        }
    }
    private fun device(n: Int) = Device(temporary.newFolder(), n)

    @Test fun lostResponseUndoAndAnotherDeviceSurviveRestart() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        a.data = songs("one")
        a.sync(server)
        b.sync(server)
        a.data = songs("one", "two")
        server.loseReply = true
        assertTrue(runCatching { a.sync(server) }.isFailure)
        b.data = songs("one", "other")
        b.sync(server)
        val restarted = Device(a.folder, 1)
        restarted.data = songs("one")
        restarted.sync(server)
        b.sync(server)
        assertEquals(setOf("one", "other"), ids(b.data))
        assertEquals(ids(restarted.data), ids(b.data))
        val writes = server.writes
        restarted.sync(server)
        assertEquals(writes, server.writes)
        assertEquals(2, server.files.size)
    }

    @Test fun aTruncatedUploadCanBeRepairedByItsOwner() = runBlocking {
        val server = Server().apply { truncate = true }
        val a = device(1)
        a.data = songs("完整的记录")
        assertTrue(runCatching { a.sync(server) }.isFailure)
        val name = server.files.keys.single()
        server.truncate = false
        a.sync(server)
        assertEquals(listOf(name), server.files.keys.toList())
        val b = device(2)
        b.sync(server)
        assertEquals(ids(a.data), ids(b.data))
    }

    @Test fun otherDevicesContinueSynchronizingWhileANewerUploadIsIncomplete() = runBlocking {
        val server = Server()
        val phone = device(1)
        val tablet = device(2)
        phone.data = songs("old")
        phone.sync(server)
        tablet.sync(server)

        phone.data = songs("old", "phone")
        server.truncate = true
        assertTrue(runCatching { phone.sync(server) }.isFailure)
        server.truncate = false

        tablet.data = songs("old", "tablet")
        tablet.sync(server)
        val newcomer = device(3)
        newcomer.sync(server)
        assertEquals(setOf("old", "tablet"), ids(newcomer.data))

        phone.sync(server)
        tablet.sync(server)
        newcomer.sync(server)
        assertEquals(setOf("old", "phone", "tablet"), ids(tablet.data))
        assertEquals(ids(tablet.data), ids(newcomer.data))
    }

    @Test fun missingListedHeadFallsBackButUnreadableOrInvalidCompleteHeadFails() = runBlocking {
        val server = Server()
        val phone = device(1)
        val tablet = device(2)
        phone.data = songs("old")
        phone.sync(server)
        tablet.sync(server)

        val previous = server.files.keys.single()
        val incompatible = JSONObject(server.files.getValue(previous)).put("sequence", 2).put("version", 3).toString()
        val next = "melora-records-v2-${phone.id}-2-${RecordReplica.hash(incompatible)}.json"
        server.listing = listOf(next)
        tablet.sync(server)
        assertEquals(setOf("old"), ids(tablet.data))

        server.unavailable = next
        assertEquals("server unavailable", runCatching { tablet.sync(server) }.exceptionOrNull()?.message)
        server.unavailable = null
        server.files[next] = incompatible
        assertTrue(runCatching { tablet.sync(server) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun competingNamesAtTheSameVersionStillStopSync() = runBlocking {
        val server = Server()
        val phone = device(1)
        phone.data = songs("kept")
        phone.sync(server)
        val full = server.files.values.single()
        val other = full + "\n"
        val fork = "melora-records-v2-${phone.id}-1-${RecordReplica.hash(other)}.json"
        server.files[fork] = other.take(8)
        assertTrue(runCatching { device(2).sync(server) }.exceptionOrNull()?.message.orEmpty().contains("互斥"))
    }

    @Test fun simultaneousFirstUploadsAndStaleListingsConvergeWithoutOverwrite() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        server.listing = emptyList()
        a.data = songs("offline")
        b.data = songs("other")
        a.sync(server)
        b.sync(server)
        server.listing = null
        a.sync(server)
        b.sync(server)
        assertEquals(setOf("offline", "other"), ids(a.data))
        assertEquals(ids(a.data), ids(b.data))
        server.listing = emptyList()
        a.sync(server)
        assertEquals(ids(b.data), ids(a.data))
    }

    @Test fun smartMergeCanApplyOtherRowsAndResolvingARenameSurvivesOtherDeviceAndRestart() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        a.data = playlist("原名", "一")
        a.sync(server)
        b.sync(server)
        a.data = playlist("手机名", "一", "二")
        b.data = playlist("平板名", "一", "三", fav = "另一首收藏")
        a.sync(server)
        b.sync(server)
        a.sync(server)
        val conflict = a.conflicts.single()
        assertEquals(conflict.key, b.conflicts.single().key)
        assertEquals(setOf("一", "二", "三"), tracks(a.data))
        assertEquals(setOf("另一首收藏"), ids(a.data))
        // B 的界面此刻预览 A 的名字。只加一首歌不能代替用户同意改名。
        b.data.playlist().getJSONArray("songs").put(song("四"))
        b.sync(server)
        a.sync(server)
        assertEquals(conflict.key, a.conflicts.single().key)
        assertEquals(setOf("一", "二", "三", "四"), tracks(a.data))
        a.sync(server, mapOf(conflict.key to false))
        b.sync(server)
        assertTrue(a.conflicts.isEmpty() && b.conflicts.isEmpty())
        assertEquals("平板名", b.data.playlist().getString("name"))
        val fresh = device(3)
        fresh.sync(server)
        assertEquals("平板名", fresh.data.playlist().getString("name"))
        assertEquals(tracks(a.data), tracks(fresh.data))
    }

    @Test fun offlineDeletionDoesNotResurrectWhenAnotherDeviceAddsAFavorite() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        a.data = songs("keep", "removed")
        a.sync(server)
        b.sync(server)
        b.data = songs("keep")
        b.sync(server)
        a.data = songs("keep", "removed", "new")
        a.sync(server)
        b.sync(server)
        assertEquals(setOf("keep", "new"), ids(b.data))
    }

    @Test fun differentSimultaneousManualDecisionsRemainAConflict() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        a.data = playlist("原", "一")
        a.sync(server)
        b.sync(server)
        a.data = playlist("甲", "一")
        b.data = playlist("乙", "一")
        a.sync(server)
        b.sync(server)
        a.sync(server)
        val key = a.conflicts.single().key
        server.listing = server.files.keys.toList()
        a.sync(server, mapOf(key to true))
        b.sync(server, mapOf(key to false))
        server.listing = null
        a.sync(server)
        b.sync(server)
        assertEquals(1, a.conflicts.size)
        assertEquals(a.conflicts.first().key, b.conflicts.single().key)
    }

    @Test fun firstReadMigratesLegacyBaselineAndCloudOnceWithoutResurrectingDeletedRows() = runBlocking {
        val server = Server()
        val a = device(1)
        File(a.folder, "base.json").writeText(songs("old", "keep").toString())
        server.files["melora-records-v1.json"] = songs("old", "keep", "remote").put("writeReceipts", JSONObject()).toString()
        a.data = songs("keep", "local")
        a.sync(server)
        assertEquals(setOf("keep", "local", "remote"), ids(a.data))
        val legacy = server.files.getValue("melora-records-v1.json")
        val b = device(2)
        b.sync(server)
        assertEquals(ids(a.data), ids(b.data))
        assertEquals(legacy, server.files["melora-records-v1.json"])
    }

    @Test fun migratingAnAcknowledgedV1WriteKeepsAnOfflineUndo() = runBlocking {
        val server = Server()
        val a = device(1)
        val id = "68dfc20b-d9d3-4edc-a03b-4125cf71c451"
        File(a.folder, "base.json").writeText(songs("one").toString())
        val pending = File(a.folder, "pending").apply { mkdir() }
        File(pending, "id").writeText(id)
        File(pending, "sent").writeText(songs("one", "two").toString())
        File(pending, "agreed").writeText(songs("one", "two", "other").toString())
        server.files["melora-records-v1.json"] = songs("one", "two", "other")
            .put("writeReceipts", JSONObject().put(a.id, id)).toString()
        a.data = songs("one")
        a.sync(server)
        val later = device(2)
        later.sync(server)
        assertEquals(setOf("one", "other"), ids(a.data))
        assertEquals(ids(a.data), ids(later.data))
    }

    @Test fun retiredV1TransactionUsesTheAlreadyCommittedBaseline() = runBlocking {
        val server = Server()
        val a = device(1)
        a.data = songs("one", "removedOnCloud")
        File(a.folder, "base.json").writeText(a.data.toString())
        val pending = File(a.folder, "pending").apply { mkdir() }
        File(pending, "id").writeText("")
        File(pending, "sent").writeText(songs("one").toString())
        server.files["melora-records-v1.json"] = songs("one").toString()
        a.sync(server)
        assertEquals(setOf("one"), ids(a.data))
    }

    @Test fun partialChoicesOnDifferentRecordsArePublishedIndependently() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        fun lists(name: String): JSONObject = playlist(name, "一").apply {
            getJSONObject("library").getJSONArray("playlists").put(
                JSONObject(playlist().toString()).put("id", "second").put("name", "第二单$name"))
        }
        a.data = lists("原名")
        a.sync(server)
        b.sync(server)
        a.data = lists("甲")
        b.data = lists("乙")
        a.sync(server)
        b.sync(server)
        a.sync(server)
        assertEquals(2, a.conflicts.size)
        val one = a.conflicts.single { it.remoteLabel.contains("第二单") }
        a.sync(server, mapOf(one.key to false))
        b.sync(server)
        assertEquals(1, b.conflicts.size)
        val second = b.data.getJSONObject("library").getJSONArray("playlists").let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "second" }
        }
        assertEquals("第二单乙", second.getString("name"))
    }

    @Test fun invalidFileOrInterruptedApplyNeverSilentlyErasesLocalChanges() = runBlocking {
        val server = Server()
        val a = device(1)
        val b = device(2)
        a.data = songs("a")
        a.sync(server)
        val name = server.files.keys.single()
        val good = server.files.getValue(name)
        server.files[name] = "{}"
        b.data = songs("b")
        assertTrue(runCatching { b.sync(server) }.isFailure)
        assertEquals(setOf("b"), ids(b.data))
        server.files[name] = good
        b.interruptApply = true
        assertTrue(runCatching { b.sync(server) }.isFailure)
        b.data = songs("a", "b", "new")
        b.sync(server)
        a.sync(server)
        assertEquals(setOf("a", "b", "new"), ids(a.data))
    }

    companion object {
        private fun song(id: String) = JSONObject().put("name", id).put("source", "wy").put("songmid", id)
        private fun songs(vararg ids: String) = RecordSyncMerge.project(
            JSONObject().put("favorites", JSONArray(ids.map(::song))), JSONArray())
        private fun playlist(name: String, vararg tracks: String, fav: String? = null) = RecordSyncMerge.project(
            JSONObject().put("favorites", JSONArray(listOfNotNull(fav?.let(::song))))
                .put("playlists", JSONArray().put(JSONObject().put("id", "list").put("name", name)
                    .put("songs", JSONArray(tracks.map(::song))))), JSONArray())
        private fun JSONObject.playlist() = getJSONObject("library").getJSONArray("playlists").getJSONObject(0)
        private fun tracks(doc: JSONObject) = trackIds(doc.playlist().getJSONArray("songs"))
        private fun ids(doc: JSONObject) = trackIds(doc.getJSONObject("library").getJSONArray("favorites"))
        private fun trackIds(rows: JSONArray) = (0 until rows.length()).map { rows.getJSONObject(it).getString("songmid") }.toSet()
    }
}
