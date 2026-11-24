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
import java.util.UUID

class WebDavSyncLedgerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Server {
        var text: String? = null
        var version = 0
        fun read() = text?.let { WebDavDocument(it, "\"$version\"") }
        fun put(value: String, expected: String?): Boolean {
            if (expected != read()?.etag) return false
            text = value
            version++
            return true
        }
    }

    private class Device(val folder: File, val id: String = UUID.randomUUID().toString()) {
        var data = songs()
        suspend fun sync(server: Server, write: suspend (String, String?) -> Boolean = server::put) {
            syncRecords(WebDavSyncLedger(folder), id, { data }, { sent, agreed, commit ->
                data = RecordSyncMerge.merge(sent, agreed, data)
                commit()
            }, server::read, write)
        }
    }

    @Test fun lostResponseThenUndoAndAnotherWriterSurviveProcessRestart() = runBlocking {
        val server = Server()
        val a = Device(temporary.newFolder())
        val b = Device(temporary.newFolder())
        a.data = songs("one")
        a.sync(server)
        b.sync(server)
        a.data = songs("one", "two")
        try {
            a.sync(server) { body, etag ->
                assertTrue(server.put(body, etag))
                throw IOException("response lost")
            }
            fail()
        } catch (_: IOException) { /* 服务器已提交，本机还没有收到回执。 */ }
        b.data = songs("one", "other")
        b.sync(server)
        val restarted = Device(a.folder, a.id)
        restarted.data = songs("one")
        restarted.sync(server)
        b.sync(server)
        assertEquals(setOf("one", "other"), ids(b.data))
        assertEquals(ids(b.data), ids(restarted.data))
        val writes = server.version
        restarted.sync(server)
        assertEquals(writes, server.version)
    }

    @Test fun failedCreateAndConcurrentCreateAreMergedOnRetry() = runBlocking {
        val server = Server()
        val a = Device(temporary.newFolder())
        val b = Device(temporary.newFolder())
        a.data = songs("offline")
        try {
            a.sync(server) { _, _ -> throw IOException("offline") }
            fail()
        } catch (_: IOException) { /* 本次 PUT 未到达服务器。 */ }
        b.data = songs("other")
        var conflict = true
        a.sync(server) { value, etag ->
            if (conflict) {
                conflict = false
                b.sync(server)
            }
            server.put(value, etag)
        }
        assertEquals(setOf("offline", "other"), ids(a.data))
        b.sync(server)
        assertEquals(ids(a.data), ids(b.data))
    }

    @Test fun malformedRemoteNeverReplacesLibraryOrWrites() = runBlocking {
        val a = Device(temporary.newFolder())
        val server = Server().apply { text = "{}" }
        a.data = songs("mine")
        val result = runCatching { a.sync(server) }
        assertTrue(result.isFailure)
        assertEquals(setOf("mine"), ids(a.data))
        assertEquals(0, server.version)
    }

    companion object {
        private fun songs(vararg ids: String) = RecordSyncMerge.project(
            JSONObject().put("favorites", JSONArray(ids.map { JSONObject().put("name", it).put("source", "wy").put("songmid", it) })),
            JSONArray(),
        )
        private fun ids(records: JSONObject): Set<String> {
            val favorites = records.getJSONObject("library").getJSONArray("favorites")
            return (0 until favorites.length()).map { favorites.getJSONObject(it).getString("songmid") }.toSet()
        }
    }
}
