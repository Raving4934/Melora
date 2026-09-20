package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderLoadingTest {
    private val testCachePrefix = "recommend.test.loading"

    @After
    fun cleanup() {
        OnlineCache.clear(testCachePrefix)
    }

    @Test
    fun `artist and fallback requests start independently and merge in input order`() = runBlocking {
        val artistSeeds = listOf("artist-a", "artist-b", "artist-c", "artist-d")
        val fallbackBoards = listOf("16", "17", "93")
        val artistStarted = mutableSetOf<String>()
        val boardStarted = mutableSetOf<String>()
        val allArtistsStarted = CompletableDeferred<Unit>()
        val allBoardsStarted = CompletableDeferred<Unit>()
        val releaseArtists = CompletableDeferred<Unit>()
        val releaseBoards = CompletableDeferred<Unit>()

        val result = async {
            Recommender.buildMusicCandidates(
                artistSeeds = artistSeeds,
                fallbackBoards = fallbackBoards,
                excluded = emptySet(),
                search = { seed ->
                    synchronized(artistStarted) {
                        artistStarted += seed
                        if (artistStarted.size == artistSeeds.size) allArtistsStarted.complete(Unit)
                    }
                    releaseArtists.await()
                    listOf(song("$seed-song", seed))
                },
                board = { board ->
                    synchronized(boardStarted) {
                        boardStarted += board
                        if (boardStarted.size == fallbackBoards.size) allBoardsStarted.complete(Unit)
                    }
                    releaseBoards.await()
                    listOf(song("board-$board", "board-$board"))
                },
            )
        }

        withTimeout(5_000) { allArtistsStarted.await() }
        assertEquals(artistSeeds.toSet(), artistStarted)
        assertTrue("回退榜不应在歌手请求完成前启动", boardStarted.isEmpty())
        releaseArtists.complete(Unit)

        withTimeout(5_000) { allBoardsStarted.await() }
        assertEquals(fallbackBoards.toSet(), boardStarted)
        releaseBoards.complete(Unit)

        assertEquals(
            artistSeeds.map { "kw_${it}-song" } + fallbackBoards.map { "kw_board-$it" },
            result.await().map { it.uid },
        )
    }

    @Test
    fun `cancellation propagates from candidate orchestration`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            Recommender.buildMusicCandidates(
                artistSeeds = listOf("artist-a", "artist-b"),
                fallbackBoards = emptyList(),
                excluded = emptySet(),
                search = {
                    started.complete(Unit)
                    release.await()
                    emptyList()
                },
                board = { emptyList() },
            )
        }

        withTimeout(5_000) { started.await() }
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
    }

    @Test
    fun `guess starts mandatory fallback before daily completes`() = runBlocking {
        val dailyStarted = CompletableDeferred<Unit>()
        val releaseDaily = CompletableDeferred<Unit>()
        val artistStarted = CompletableDeferred<Unit>()
        val releaseArtist = CompletableDeferred<Unit>()
        val fallbackStarted = CompletableDeferred<Unit>()
        val releaseFallback = CompletableDeferred<Unit>()

        val result = async {
            Recommender.loadGuessCandidates(
                known = emptySet(),
                fallbackBoards = listOf("16"),
                daily = {
                    dailyStarted.complete(Unit)
                    releaseDaily.await()
                    listOf(song("daily", "daily"))
                },
                artist = {
                    artistStarted.complete(Unit)
                    releaseArtist.await()
                    listOf(song("artist", "artist"))
                },
                board = { board ->
                    fallbackStarted.complete(Unit)
                    releaseFallback.await()
                    listOf(song("board-$board", "board-$board"))
                },
            )
        }

        withTimeout(5_000) {
            dailyStarted.await()
            artistStarted.await()
        }
        releaseArtist.complete(Unit)
        withTimeout(5_000) { fallbackStarted.await() }
        assertFalse("daily 未完成时不应阻塞已确定必需的回退榜", releaseDaily.isCompleted)

        releaseFallback.complete(Unit)
        releaseDaily.complete(Unit)
        val (daily, candidates) = result.await()
        assertEquals(listOf("kw_daily"), daily.map { it.uid })
        assertEquals(listOf("kw_artist", "kw_board-16"), candidates.map { it.uid })
    }

    @Test
    fun `guess orchestration cancellation reaches daily loader`() = runBlocking {
        val dailyStarted = CompletableDeferred<Unit>()
        val dailyCancelled = CompletableDeferred<Unit>()
        val result = async {
            Recommender.loadGuessCandidates(
                known = emptySet(),
                fallbackBoards = emptyList(),
                daily = {
                    dailyStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        emptyList()
                    } finally {
                        dailyCancelled.complete(Unit)
                    }
                },
                artist = { emptyList() },
                board = { emptyList() },
            )
        }

        withTimeout(5_000) { dailyStarted.await() }
        result.cancelAndJoin()
        withTimeout(5_000) { dailyCancelled.await() }
        assertTrue(result.isCancelled)
    }

    @Test
    fun `one discovery refresh generation shares completed force result`() = runBlocking {
        Recommender.beginDiscoveryRefresh()
        var loads = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = true) {
                loads++
                started.complete(Unit)
                release.await()
                listOf("fresh")
            }
        }
        withTimeout(5_000) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = true) {
                loads++
                error("duplicate force load")
            }
        }
        release.complete(Unit)

        assertEquals(listOf("fresh"), first.await())
        assertEquals(listOf("fresh"), second.await())
        assertEquals(listOf("fresh"), Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, true) {
            loads++; error("completed result must be reused within the same generation")
        })
        assertEquals(1, loads)

        Recommender.beginDiscoveryRefresh()
        assertEquals(
            listOf("next"),
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = true) {
                loads++
                listOf("next")
            },
        )
        assertEquals(2, loads)
    }

    @Test
    fun `normal in-flight and force refresh share one stable load`() = runBlocking {
        Recommender.beginDiscoveryRefresh()
        var loads = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val normal = async {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = false) {
                loads++
                started.complete(Unit)
                release.await()
                listOf("fresh")
            }
        }
        withTimeout(5_000) { started.await() }
        val forced = async(start = CoroutineStart.UNDISPATCHED) {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = true) {
                loads++
                error("force must join the normal in-flight load")
            }
        }
        release.complete(Unit)

        assertEquals(listOf("fresh"), normal.await())
        assertEquals(listOf("fresh"), forced.await())
        assertEquals(1, loads)
    }

    @Test
    fun `empty force result keeps old snapshot and can recover without empty wrapper`() = runBlocking {
        OnlineCache.put(testCachePrefix, listOf("old"))
        Recommender.beginDiscoveryRefresh()

        assertTrue(
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = true) {
                emptyList()
            }.isEmpty(),
        )
        assertEquals(listOf("old"), OnlineCache.peek<List<String>>(testCachePrefix))

        assertEquals(
            listOf("recovered"),
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, refresh = true) {
                listOf("recovered")
            },
        )
        assertEquals(listOf("recovered"), OnlineCache.peek<List<String>>(testCachePrefix))
    }

    @Test
    fun `guess uses raw candidates after daily exclusion to fill beyond first thirty`() {
        val daily = (1..30).map { song("daily-$it", "daily-$it") }
        val validAfterDaily = (31..60).map { song("valid-$it", "valid-$it") }
        val rawCandidates = daily + validAfterDaily
        val dailyIds = daily.mapTo(hashSetOf()) { it.uid }

        val loaded = runBlocking {
            Recommender.loadArtistCandidates(listOf("seed")) { rawCandidates }
        }
        val withFallback = runBlocking {
            Recommender.loadFallbackCandidatesIfNeeded(
                candidates = loaded,
                fallbackBoards = emptyList(),
                excluded = dailyIds,
                board = { error("fallback should not be needed") },
            )
        }
        val result = Recommender.selectGuessSongs(withFallback, daily, emptySet())

        assertEquals(validAfterDaily.map { it.uid }, result.map { it.uid })
    }

    @Test
    fun `sufficient raw candidates skip fallback requests entirely`() = runBlocking {
        val candidates = (1..40).map { song("valid-$it", "artist-$it") }
        var requests = 0
        val result = Recommender.loadFallbackCandidatesIfNeeded(
            candidates, listOf("16", "17", "93"), emptySet(),
        ) { requests++; emptyList() }
        assertEquals(candidates, result)
        assertEquals(0, requests)
    }

    @Test
    fun `daily exclusion triggers fallback before final selection`() = runBlocking {
        val daily = (1..30).map { song("daily-$it", "daily-$it") }
        val kept = listOf(song("kept-1", "kept-1"), song("kept-2", "kept-2"))
        val boards = listOf("93", "17", "16")
        val called = mutableSetOf<String>()
        val raw = Recommender.loadFallbackCandidatesIfNeeded(
            daily + kept, boards, daily.mapTo(hashSetOf()) { it.uid },
        ) { board ->
            called += board
            (1..10).map { song("$board-$it", "$board-$it") }
        }
        val result = Recommender.selectGuessSongs(raw, daily, emptySet())
        val expected = kept + boards.flatMap { board -> (1..10).map { song("$board-$it", "$board-$it") } }
        assertEquals(boards.toSet(), called)
        assertEquals(expected.take(30).map { it.uid }, result.map { it.uid })
    }

    @Test
    fun `overlapping refresh generations share the active canonical request`() = runBlocking {
        Recommender.beginDiscoveryRefresh()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, true) {
                calls++; started.complete(Unit); release.await(); listOf("fresh")
            }
        }
        withTimeout(5_000) { started.await() }
        Recommender.beginDiscoveryRefresh()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, true) { calls++; listOf("duplicate") }
        }
        // refreshShared 已同步捕获在途任务，不依赖 IO 调度先后或人为 sleep。
        release.complete(Unit)
        assertEquals(listOf("fresh"), first.await())
        assertEquals(listOf("fresh"), second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `failed force lookup preserves snapshot and allows another attempt`() = runBlocking {
        OnlineCache.put(testCachePrefix, listOf("old"))
        Recommender.beginDiscoveryRefresh()
        val failed = runCatching {
            Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, true) { error("temporary") }
        }
        assertTrue(failed.isFailure)
        assertEquals(listOf("old"), OnlineCache.peek<List<String>>(testCachePrefix))
        assertEquals(listOf("retry"), Recommender.refreshShared<List<String>>(testCachePrefix, 60_000L, true) { listOf("retry") })
    }

    private fun song(id: String, artist: String) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", "song-$id")
            .put("singer", artist),
    )
}
