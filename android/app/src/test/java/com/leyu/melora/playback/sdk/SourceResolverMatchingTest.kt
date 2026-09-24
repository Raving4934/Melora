package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceResolverMatchingTest {
    @Before
    fun resetOnlineCache() {
        OnlineCache.clear()
    }

    @After
    fun clearOnlineCache() {
        OnlineCache.clear()
    }

    @Test
    fun alternativeScoreNormalizesNfkcCasePunctuationAndArtistCollections() {
        val original = song(
            source = "kw",
            songmid = "original",
            name = "ＡＢＣ！",
            singer = "A、B & C / D feat. E",
            album = "Ａlbum",
            interval = "03:30",
        )
        val candidate = song(
            source = "kg",
            songmid = "candidate",
            name = "abc",
            singer = "e, d / c & b ft. a",
            album = "album",
            interval = "03:34",
        )

        assertEquals(16, SourceResolver.alternativeScore(original, candidate))
    }

    @Test
    fun alternativeScoreRejectsArtistSubstringsAndNameDurationOnlyEvidence() {
        val original = song(
            name = "Same Title",
            singer = "The Weeknd",
            album = "Album",
            interval = "03:30",
        )

        assertNull(
            SourceResolver.alternativeScore(
                original,
                song(source = "kg", songmid = "substring", singer = "Week", album = "Album", interval = "03:30"),
            ),
        )
        assertNull(
            SourceResolver.alternativeScore(
                original,
                song(source = "kg", songmid = "extra-artist", singer = "The Weeknd feat. Someone Else", album = "Album", interval = "03:30"),
            ),
        )
        assertNull(
            SourceResolver.alternativeScore(
                original,
                song(source = "kg", songmid = "missing-artist", singer = "", album = "Album", interval = "03:30"),
            ),
        )
    }

    @Test
    fun alternativeScoreRejectsBookChaptersAndEmptySourceOrSongmidOnEitherSide() {
        val validOriginal = song(source = "kw", songmid = "original")
        val validCandidate = song(source = "kg", songmid = "candidate")
        val invalidPairs = listOf(
            song(source = "kw", songmid = "book", isBookChapter = true) to validCandidate,
            validOriginal to song(source = "kg", songmid = "book", isBookChapter = true),
            song(source = "", songmid = "original") to validCandidate,
            song(source = "kw", songmid = "") to validCandidate,
            validOriginal to song(source = "", songmid = "candidate"),
            validOriginal to song(source = "kg", songmid = ""),
        )

        invalidPairs.forEach { (song, candidate) ->
            assertNull(SourceResolver.alternativeScore(song, candidate))
        }
    }

    @Test
    fun alternativeScoreRejectsArtistCollectionsContainingUnknownMarkers() {
        listOf("群星", "未知", "null").forEach { marker ->
            val original = song(singer = "A、$marker")
            val candidate = song(source = "kg", songmid = "unknown-$marker", singer = "A & $marker")

            assertNull(SourceResolver.alternativeScore(original, candidate))
        }
    }

    @Test
    fun alternativeScoreRejectsVersionMismatchesExtractedFromTitleAndAlbum() {
        val original = song(name = "Track", singer = "Artist", album = "Album", interval = "03:30")
        val versionedAlbums = listOf(
            "Album (Live)",
            "Album - 伴奏",
            "Album Remix",
            "Album 翻唱",
            "Album sped up",
            "Album slowed",
            "Album Remastered",
        )

        versionedAlbums.forEachIndexed { index, album ->
            assertNull(
                "version mismatch should be rejected: $album",
                SourceResolver.alternativeScore(
                    original,
                    song(source = "kg", songmid = "version-$index", album = album, interval = "03:30"),
                ),
            )
        }

        val liveOriginal = song(name = "Track (Live)", singer = "Artist", album = "Album", interval = "03:30")
        val liveCandidate = song(source = "kg", songmid = "same-live", name = "track [live]", singer = "artist", album = "album", interval = "03:34")
        assertEquals(16, SourceResolver.alternativeScore(liveOriginal, liveCandidate))
    }

    @Test
    fun alternativeScoreRejectsLargeDurationDifferencesAndRequiresAlbumWhenDurationIsMissing() {
        val known = song(name = "Track", singer = "Artist", album = "Album", interval = "03:30")

        assertEquals(
            16,
            SourceResolver.alternativeScore(
                known,
                song(source = "kg", songmid = "within-limit", interval = "03:35"),
            ),
        )
        assertNull(
            SourceResolver.alternativeScore(
                known,
                song(source = "kg", songmid = "over-limit", interval = "03:36"),
            ),
        )

        val missingOriginalDuration = song(name = "Track", singer = "Artist", album = "Album", interval = "00:00")
        assertEquals(
            14,
            SourceResolver.alternativeScore(
                missingOriginalDuration,
                song(source = "kg", songmid = "same-album", album = "Album", interval = "03:30"),
            ),
        )
        assertNull(
            SourceResolver.alternativeScore(
                missingOriginalDuration,
                song(source = "kg", songmid = "missing-album", album = "", interval = "03:30"),
            ),
        )
        assertNull(
            SourceResolver.alternativeScore(
                missingOriginalDuration,
                song(source = "kg", songmid = "different-album", album = "Other Album", interval = "03:30"),
            ),
        )
    }

    @Test
    fun alternativeScoreRequiresAlbumOrDurationEvidenceBeforeApplyingWeights() {
        assertNull(
            SourceResolver.alternativeScore(
                song(name = "Track", singer = "Artist", album = "", interval = "00:00"),
                song(source = "kg", songmid = "base", name = "Track", singer = "Artist", album = "", interval = "00:00"),
            ),
        )
        assertEquals(
            14,
            SourceResolver.alternativeScore(
                song(name = "Track", singer = "Artist", album = "Album", interval = "00:00"),
                song(source = "kg", songmid = "album", name = "Track", singer = "Artist", album = "Album", interval = "00:00"),
            ),
        )
        assertEquals(
            14,
            SourceResolver.alternativeScore(
                song(name = "Track", singer = "Artist", album = "", interval = "03:30"),
                song(source = "kg", songmid = "duration", name = "Track", singer = "Artist", album = "", interval = "03:34"),
            ),
        )
        assertEquals(
            16,
            SourceResolver.alternativeScore(
                song(name = "Track", singer = "Artist", album = "Album", interval = "03:30"),
                song(source = "kg", songmid = "exact", name = "Track", singer = "Artist", album = "Album", interval = "03:34"),
            ),
        )
    }

    @Test
    fun alternativeCacheKeyCanonicalizesEquivalentSongIdentity() {
        val first = song(
            source = "kw",
            name = "ＡＢＣ！",
            singer = "A、B",
            album = "Ａlbum",
            interval = "03:30",
        )
        val equivalent = song(
            source = "kw",
            name = "abc",
            singer = "B & A",
            album = "album",
            interval = "03:30",
        )

        assertEquals(SourceResolver.alternativeCacheKey(first), SourceResolver.alternativeCacheKey(equivalent))
        assertNotEquals(
            SourceResolver.alternativeCacheKey(first),
            SourceResolver.alternativeCacheKey(first.copyRaw(source = "kg")),
        )
        assertNotEquals(
            SourceResolver.alternativeCacheKey(first),
            SourceResolver.alternativeCacheKey(first.copyRaw(interval = "03:31")),
        )
        assertNotEquals(
            SourceResolver.alternativeCacheKey(first),
            SourceResolver.alternativeCacheKey(first.copyRaw(album = "Other Album")),
        )
    }

    @Test
    fun localMetadataScoreAllowsMissingArtistOnlyWithStrongDurationEvidence() {
        val local = song(source = "local", songmid = "local-1", name = "夜曲", singer = "", album = "", interval = "03:46")
        val exact = song(source = "kw", songmid = "exact", name = "夜曲", singer = "周杰伦", album = "十一月的萧邦", interval = "03:48")
        val wrongDuration = song(source = "kw", songmid = "wrong", name = "夜曲", singer = "周杰伦", album = "十一月的萧邦", interval = "04:20")

        assertEquals(12, SourceResolver.localMetadataScore(local, exact))
        assertNull(SourceResolver.localMetadataScore(local, wrongDuration))
    }

    @Test
    fun localMetadataScoreKeepsStrictArtistMatchingWhenArtistExists() {
        val local = song(source = "local", songmid = "local-1", name = "夜曲", singer = "周杰伦", album = "十一月的萧邦", interval = "03:46")
        val wrongArtist = song(source = "kw", songmid = "wrong", name = "夜曲", singer = "其他歌手", album = "十一月的萧邦", interval = "03:46")

        assertNull(SourceResolver.localMetadataScore(local, wrongArtist))
    }

    @Test
    fun findMatchedSongsFiltersTargetSourceSortsByScoreAndDeduplicatesByUid() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val low = song(source = "kg", songmid = "low", album = "", interval = "03:34")
        val duplicateLow = song(source = "kg", songmid = "duplicate", album = "", interval = "03:34")
        val exact = song(source = "kg", songmid = "exact")
        val duplicateExact = song(source = "kg", songmid = "duplicate", album = "Album", interval = "03:30")
        val wrongTarget = song(source = "tx", songmid = "wrong-target")
        val searchCalls = AtomicInteger()

        val resultsForBothTargets = listOf(low, duplicateLow, wrongTarget, exact, duplicateExact)
        val kgResults = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
            searchCalls.incrementAndGet()
            resultsForBothTargets
        }
        val txResults = SourceResolver.findMatchedSongs(original, "tx", timeoutMs = 1_000) {
            searchCalls.incrementAndGet()
            resultsForBothTargets
        }

        assertEquals(listOf("exact", "duplicate", "low"), kgResults.map { it.songmid })
        assertTrue(kgResults.all { it.source == "kg" })
        assertEquals(listOf("wrong-target"), txResults.map { it.songmid })
        assertTrue(txResults.all { it.source == "tx" })
        assertEquals(2, searchCalls.get())
    }

    @Test
    fun findMatchedSongsUsesOnlineCacheAndPrefixClearAllowsRetry() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val candidate = song(source = "kg", songmid = "cached")
        val searchCalls = AtomicInteger()
        suspend fun search(keyword: String): List<OnlineSong> {
            assertTrue(keyword.isNotBlank())
            searchCalls.incrementAndGet()
            return listOf(candidate)
        }

        val first = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000, search = ::search)
        val second = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000, search = ::search)
        val cacheKey = SourceResolver.matchingCacheKey(original, "kg")

        assertEquals(listOf("cached"), first.map { it.songmid })
        assertEquals(listOf("cached"), second.map { it.songmid })
        assertEquals(1, searchCalls.get())
        assertEquals(listOf("cached"), OnlineCache.peek<List<OnlineSong>>(cacheKey)?.map { it.songmid })

        OnlineCache.clear("resolver:matches:")
        assertNull(OnlineCache.peek<List<OnlineSong>>(cacheKey))

        val afterClear = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000, search = ::search)
        assertEquals(listOf("cached"), afterClear.map { it.songmid })
        assertEquals(2, searchCalls.get())
    }

    @Test
    fun forcedMatchRefreshReplacesOnlyThatSongsCachedCandidates() = runBlocking {
        val original = song(source = "wy", songmid = "force-original")
        val other = song(
            source = "wy", songmid = "other-original",
            name = "Another Track", singer = "Other Artist", album = "Other Album",
        )
        val oldCandidate = song(source = "kg", songmid = "old-candidate")
        val newCandidate = song(source = "kg", songmid = "new-candidate")
        val otherCandidate = song(
            source = "kg", songmid = "other-candidate",
            name = "Another Track", singer = "Other Artist", album = "Other Album",
        )
        val originalSearchCalls = AtomicInteger()

        val initial = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
            originalSearchCalls.incrementAndGet()
            listOf(oldCandidate)
        }
        SourceResolver.findMatchedSongs(other, "kg", timeoutMs = 1_000) { listOf(otherCandidate) }
        val originalKey = SourceResolver.matchingCacheKey(original, "kg")
        val otherKey = SourceResolver.matchingCacheKey(other, "kg")

        assertEquals(listOf("old-candidate"), initial.map { it.songmid })
        assertNotEquals(originalKey, otherKey)
        assertEquals(listOf("old-candidate"), OnlineCache.peek<List<OnlineSong>>(originalKey)?.map { it.songmid })
        assertEquals(listOf("other-candidate"), OnlineCache.peek<List<OnlineSong>>(otherKey)?.map { it.songmid })

        val refreshed = SourceResolver.findMatchedSongs(
            original,
            "kg",
            timeoutMs = 1_000,
            force = true,
        ) {
            originalSearchCalls.incrementAndGet()
            listOf(newCandidate)
        }

        assertEquals(listOf("new-candidate"), refreshed.map { it.songmid })
        assertEquals(listOf("new-candidate"), OnlineCache.peek<List<OnlineSong>>(originalKey)?.map { it.songmid })
        assertEquals(listOf("other-candidate"), OnlineCache.peek<List<OnlineSong>>(otherKey)?.map { it.songmid })
        assertEquals(2, originalSearchCalls.get())

        val cached = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
            error("forced matching result should replace the previous cache")
        }
        assertEquals(listOf("new-candidate"), cached.map { it.songmid })
    }

    @Test
    fun findMatchedSongsSharesSingleFlightWhenOneWaiterIsCancelled() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val candidate = song(source = "kg", songmid = "shared")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val searchCalls = AtomicInteger()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
                searchCalls.incrementAndGet()
                started.complete(Unit)
                release.await()
                listOf(candidate)
            }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
                error("same matching key must share the in-flight search")
            }
        }

        first.cancelAndJoin()
        release.complete(Unit)

        assertEquals(listOf("shared"), second.await().map { it.songmid })
        assertEquals(1, searchCalls.get())
    }

    @Test
    fun shortForegroundTimeoutDoesNotCancelLongBackgroundSearchAndReusesItsCache() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val candidate = song(source = "kg", songmid = "background-result")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val searchCalls = AtomicInteger()

        val background = async(start = CoroutineStart.UNDISPATCHED) {
            SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 2_000) {
                searchCalls.incrementAndGet()
                started.complete(Unit)
                release.await()
                listOf(candidate)
            }
        }
        started.await()

        val foreground = withTimeout(1_000) {
            SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 250) {
                searchCalls.incrementAndGet()
                error("foreground waiter must not start a second search")
            }
        }
        assertTrue(foreground.isEmpty())
        assertTrue(background.isActive)

        release.complete(Unit)
        assertEquals(listOf("background-result"), background.await().map { it.songmid })

        val cached = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 250) {
            searchCalls.incrementAndGet()
            error("successful background result should be cached")
        }
        assertEquals(listOf("background-result"), cached.map { it.songmid })
        assertEquals(1, searchCalls.get())
    }

    @Test
    fun findMatchedSongsDoesNotCacheExceptionsOrEmptyResults() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val candidate = song(source = "kg", songmid = "recovered")
        val searchCalls = AtomicInteger()

        val failed = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
            searchCalls.incrementAndGet()
            error("temporary offline")
        }
        val empty = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
            searchCalls.incrementAndGet()
            emptyList()
        }
        val recovered = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
            searchCalls.incrementAndGet()
            listOf(candidate)
        }

        assertTrue(failed.isEmpty())
        assertTrue(empty.isEmpty())
        assertEquals(listOf("recovered"), recovered.map { it.songmid })
        assertEquals(3, searchCalls.get())
    }

    @Test
    fun findMatchedSongsHonorsTimeoutAndCanRecoverOnTheNextAttempt() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val candidate = song(source = "kg", songmid = "after-timeout")
        val blocked = CompletableDeferred<Unit>()
        val searchCalls = AtomicInteger()

        val timedOut = withTimeout(2_000) {
            SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 200) {
                searchCalls.incrementAndGet()
                blocked.await()
                listOf(candidate)
            }
        }
        val recovered = withTimeout(2_000) {
            SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 1_000) {
                searchCalls.incrementAndGet()
                listOf(candidate)
            }
        }

        assertTrue(timedOut.isEmpty())
        assertEquals(listOf("after-timeout"), recovered.map { it.songmid })
        assertEquals(2, searchCalls.get())
    }

    @Test
    fun clearCacheRebuildsMatchSearchAndRetiredCompletionCannotReplaceIt() = runBlocking {
        val original = song(source = "wy", songmid = "original")
        val retiredCandidate = song(source = "kg", songmid = "retired")
        val rebuiltCandidate = song(source = "kg", songmid = "rebuilt")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val searchCalls = AtomicInteger()

        val retired = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 2_000) {
                    assertEquals(1, searchCalls.incrementAndGet())
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    listOf(retiredCandidate)
                }
            } catch (_: CancellationException) {
                emptyList()
            }
        }
        started.await()

        SourceResolver.clearCache()
        val rebuilt = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 2_000) {
            assertEquals(2, searchCalls.incrementAndGet())
            listOf(rebuiltCandidate)
        }
        assertEquals(listOf("rebuilt"), rebuilt.map { it.songmid })

        release.complete(Unit)
        retired.await()

        val cached = SourceResolver.findMatchedSongs(original, "kg", timeoutMs = 500) {
            error("重建后的匹配结果应命中新代次缓存")
        }
        assertEquals(listOf("rebuilt"), cached.map { it.songmid })
        assertEquals(2, searchCalls.get())
    }
}

private fun song(
    source: String = "kw",
    songmid: String = "song-1",
    name: String = "Track",
    singer: String = "Artist",
    album: String = "Album",
    interval: String = "03:30",
    isBookChapter: Boolean = false,
): OnlineSong = OnlineSong(
    JSONObject()
        .put("source", source)
        .put("songmid", songmid)
        .put("name", name)
        .put("singer", singer)
        .put("albumName", album)
        .put("interval", interval)
        .put("isBookChapter", isBookChapter),
)

private fun OnlineSong.copyRaw(
    source: String = this.source,
    songmid: String = this.songmid,
    name: String = this.name,
    singer: String = this.singer,
    album: String = this.albumName,
    interval: String = this.interval,
    isBookChapter: Boolean = this.isBookChapter,
): OnlineSong = song(source, songmid, name, singer, album, interval, isBookChapter)
