package com.leyu.melora.playback

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LyricCacheStoreTest {
    private val root = Files.createTempDirectory("lyrics-test").toFile()
    private fun lyric(uid: String, text: String = "歌词") = PlayerLyric(uid, "歌曲", "歌手", listOf(LyricLine(0, text)), "fixture")
    private fun file(uid: String, version: String = "") = File(root, "${lyricCacheKey(uid, version)}.json")
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun memoryIsBoundedAndKeepsRecentlyReadEntries() = runBlocking {
        val store = LyricCacheStore(maxEntries = 2)
        store.load(root, "a") { lyric("a") }
        store.load(root, "b") { lyric("b") }
        store.load(root, "a") { error("memory miss") }
        store.load(root, "c") { lyric("c") }
        root.listFiles()!!.forEach { it.delete() }
        store.load(root, "a") { error("recent entry evicted") }
        var fetched = false
        store.load(root, "b") { fetched = true; lyric("b") }
        assertTrue(fetched)
    }

    @Test fun oversizedMemoryEntryDoesNotEvictEverySmallEntry() = runBlocking {
        val store = LyricCacheStore(maxMemoryBytes = 600)
        store.load(root, "a") { lyric("a") }
        store.load(root, "large") { lyric("large", "字".repeat(2_000)) }
        root.listFiles()!!.forEach { it.delete() }
        store.load(root, "a") { error("small lyric evicted") }
        var fetched = false
        store.load(root, "large") { fetched = true; lyric("large") }
        assertTrue(fetched)
    }

    @Test fun processRestartUsesDiskAndFileVersionChangesInvalidateIt() = runBlocking {
        LyricCacheStore().load(root, "a", "file-v1") { lyric("a", "旧标签") }
        val store = LyricCacheStore()
        assertEquals("旧标签", store.load(root, "a", "file-v1") { error("disk miss") }!!.lines.single().text)
        assertEquals("新标签", store.load(root, "a", "file-v2") { lyric("a", "新标签") }!!.lines.single().text)
    }

    @Test fun ttlIsNotExtendedByReadsAndFailedRefreshKeepsUsableOldLyrics() = runBlocking {
        var now = 1_000L
        val store = LyricCacheStore(ttlMs = 100, now = { now })
        store.load(root, "a") { lyric("a", "旧歌词") }
        now += 80
        store.load(root, "a") { error("unexpected refresh") }
        now += 80
        assertEquals("旧歌词", store.load(root, "a") { throw java.io.IOException("offline") }!!.lines.single().text)
        assertEquals("新歌词", store.load(root, "a") { lyric("a", "新歌词") }!!.lines.single().text)
    }

    @Test fun diskLruCountsMemoryHitsAndEnforcesFileCount() = runBlocking {
        var now = 1_000L
        val store = LyricCacheStore(maxDiskFiles = 2, now = { now++ })
        store.load(root, "a") { lyric("a") }
        store.load(root, "b") { lyric("b") }
        store.load(root, "a") { error("miss") }
        store.load(root, "c") { lyric("c") }
        assertTrue(file("a").exists())
        assertFalse(file("b").exists())
        assertTrue(file("c").exists())
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test fun diskByteBudgetAndLegacyFilesAreReclaimed() = runBlocking {
        File(root, "old_sanitized_uid.json").writeText("legacy")
        val store = LyricCacheStore(maxDiskBytes = 700)
        repeat(4) { index -> store.load(root, "$index") { lyric("$index", "long".repeat(50)) } }
        assertTrue(root.listFiles()!!.sumOf { it.length() } <= 700)
        assertFalse(File(root, "old_sanitized_uid.json").exists())
        assertFalse(root.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun corruptOrWrongUidCacheNeverBecomesAnotherSongsLyrics() = runBlocking {
        file("a").writeText("{unfinished")
        val store = LyricCacheStore()
        assertEquals("a", store.load(root, "a") { lyric("a") }!!.uid)
        val wrong = JSONObject(file("a").readText()).put("uid", "different")
        file("b").writeText(wrong.toString())
        assertEquals("b", store.load(root, "b") { lyric("b") }!!.uid)
        assertEquals(64, lyricCacheKey("content://some/" + "x".repeat(200), "").length)
        assertNotEquals(lyricCacheKey("a/b", ""), lyricCacheKey("a:b", ""))
    }

    @Test fun cacheKeysKeepTheExistingUtf8DiskProtocol() {
        assertEquals("ffe9aaeaa2a2d5048174df0b80599ef0197ec024c4b051bc9860cff58ef7f9f3", lyricCacheKey("a", ""))
        assertEquals("755f1f909479dbd487680ffd95ebcffcb808e28cc87151a3e14035c7ab21eb6e", lyricCacheKey("a", "file-v1"))
        assertEquals("42700023567e91bcab00cd61f1e32cbc41ebe552a23c6b88307be654cd8f974d", lyricCacheKey("歌曲/一", "file-v2"))
    }

    @Test fun diskTrimReadsAttributesOnceWithoutSeparateFileQueries() = runBlocking {
        var now = 1_000L
        val seed = LyricCacheStore(now = { now++ })
        repeat(6) { index -> seed.load(root, "$index") { lyric("$index") } }
        val stats = mutableMapOf<String, IntArray>()
        val directory = object : File(root.path) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { file ->
                val counts = stats.getOrPut(file.name) { IntArray(4) }
                object : File(file.path) {
                    override fun toPath(): java.nio.file.Path { counts[0]++; return super.toPath() }
                    override fun isFile(): Boolean { counts[1]++; return super.isFile() }
                    override fun length(): Long { counts[2]++; return super.length() }
                    override fun lastModified(): Long { counts[3]++; return super.lastModified() }
                }
            }?.toTypedArray()
        }
        LyricCacheStore(maxDiskFiles = 3, now = { now++ }).load(directory, "6") { lyric("6") }
        assertEquals(7, stats.size)
        stats.forEach { (name, counts) ->
            assertEquals("metadata path read repeatedly: $name", 1, counts[0])
            assertArrayEquals("redundant file queries: $name", intArrayOf(0, 0, 0), counts.copyOfRange(1, 4))
        }
        assertEquals(setOf("4", "5", "6").map { file(it).name }.toSet(), root.list()!!.toSet())
    }

    @Test fun danglingLinksAndDirectoriesDoNotAbortDiskTrimming() = runBlocking {
        val directory = File(root, "keep-directory").apply { mkdirs() }
        val link = File(root, "dangling.json").toPath()
        Files.createSymbolicLink(link, File(root, "missing").toPath())
        var now = 1_000L
        val store = LyricCacheStore(maxDiskFiles = 1, now = { now++ })
        store.load(root, "a") { lyric("a") }
        store.load(root, "b") { lyric("b") }
        assertFalse(file("a").exists())
        assertTrue(file("b").isFile)
        assertTrue(directory.isDirectory)
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun foregroundAndPrefetchShareOneRequest() = runBlocking {
        val store = LyricCacheStore()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { store.load(root, "a") { calls++; started.complete(Unit); finish.await(); lyric("a") } }
        started.await()
        val second = async { store.load(root, "a") { calls++; lyric("a") } }
        finish.complete(Unit)
        assertEquals(listOf(lyric("a"), lyric("a")), listOf(first, second).awaitAll())
        assertEquals(1, calls)
    }

    @Test fun clearRejectsLateNonCancellableResultAndAllowsNewLoads() = runBlocking {
        val store = LyricCacheStore()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val old = async {
            store.load(root, "a") {
                withContext(NonCancellable) { started.complete(Unit); finish.await() }
                lyric("a", "清理前")
            }
        }
        started.await()
        store.clear(root)
        finish.complete(Unit)
        try { old.await(); fail("old task should be cancelled") } catch (_: CancellationException) { }
        assertFalse(file("a").exists())
        assertEquals("清理后", store.load(root, "a") { lyric("a", "清理后") }!!.lines.single().text)
        assertEquals("清理后", LyricCacheStore().load(root, "a") { error("missing disk") }!!.lines.single().text)
    }

    @Test fun requestCapturedBeforeClearCannotStartAfterClear() = runBlocking {
        val store = LyricCacheStore()
        val generation = store.generation()
        store.clear(root)
        try {
            store.load(root, "a", generation = generation) { error("old request executed") }
            fail("old generation accepted")
        } catch (_: CancellationException) { }
        assertFalse(root.exists())
    }

    @Test fun missingLyricsAreRetryableAndMismatchedResultsNeverPersist() = runBlocking {
        val store = LyricCacheStore()
        assertNull(store.load(root, "a") { null })
        assertNotNull(store.load(root, "a") { lyric("a") })
        try { store.load(root, "b") { lyric("c") }; fail("mismatched uid accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(file("b").exists())
    }
}
