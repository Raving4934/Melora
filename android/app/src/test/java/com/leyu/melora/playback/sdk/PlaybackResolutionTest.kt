package com.leyu.melora.playback.sdk

import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.PlaybackCandidateProbe
import com.leyu.melora.playback.sdk.LxScriptPool.CandidateVerdict
import com.leyu.melora.playback.sdk.SourceResolver.checkedCandidate
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackResolutionTest {
    private val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "quality-test"))
    private fun resolved(quality: String, resource: String = "good") =
        SourceResolver.Resolved("https://example.test/audio", quality, song, false, resource)

    @Test fun slowPreferredQualityCannotBePreemptedByLowerQuality() = runBlocking {
        for (purpose in SourceResolver.Purpose.entries) {
            val attempts = mutableListOf<String>()
            val result = SourceResolver.resolveTiers("flac24bit", purpose, tierBudgetMs = 1000) { quality, _ ->
                attempts += quality
                delay(80)
                resolved(quality)
            }
            assertEquals("flac24bit", result.quality)
            assertEquals(listOf("flac24bit"), attempts)
        }
    }

    @Test fun candidateHealthTracksMediaEvidenceAndPreservesTheMeasuredQuality() {
        val original = OnlineSong(JSONObject(song.raw.toString()).put("interval", "03:00"))
        val low = SourceResolver.scriptResolution(original,
            LxScriptPool.ScriptResult("flac24bit", "fast", "https://example.test/fast"))!!
        val lowSpec = AudioSpecification("audio/mpeg", 44100, 128000)
        fun check(probe: PlaybackCandidateProbe?) = low.checkedCandidate(original, true, probe)
        assertEquals(CandidateVerdict.Invalid, check(PlaybackCandidateProbe(nonAudio = true)))
        assertEquals(CandidateVerdict.Skipped, check(PlaybackCandidateProbe(lowSpec, 30_000L)))
        assertEquals(CandidateVerdict.Accepted(low.copy(fromPrimary = true)), check(null))
        assertEquals(CandidateVerdict.Accepted(low.copy(quality = "128k", fromPrimary = true), verified = true),
            check(PlaybackCandidateProbe(lowSpec, 180_000L)))
    }

    @Test fun slowSourceRecoveryNeverTriesLowerQuality() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = runCatching {
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.REBUFFER, 50) { quality, _ ->
                attempts += quality
                resolved("flac")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("flac24bit"), attempts)
    }

    @Test fun slowSourceRecoveryDoesNotPickMoreBandwidthHungryHigherTier() = runBlocking {
        val result = runCatching {
            SourceResolver.resolveTiers("320k", SourceResolver.Purpose.REBUFFER, 50) { _, _ -> resolved("flac24bit") }
        }
        assertTrue(result.isFailure)
    }

    @Test fun everyTierRetainsItsOwnBudgetAndFailedHighTierDoesNotSkipFlac() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK, 50) { quality, _ ->
            attempts += quality
            if (quality == "flac24bit") awaitCancellation() else resolved(quality)
        }
        assertEquals(listOf("flac24bit", "flac"), attempts)
        assertEquals("flac", result.quality)
    }

    @Test fun playbackReservesTimeForLowestQualityWithinOneDeadline() = runBlocking {
        val attempts = mutableListOf<String>()
        val deadline = System.nanoTime() + 600_000_000L
        val result = withTimeout(1000) {
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK,
                playbackDeadlineNanos = deadline) { quality, _ ->
                attempts += quality
                if (quality == "128k") resolved(quality) else awaitCancellation()
            }
        }
        assertEquals(listOf("flac24bit", "flac", "320k", "128k"), attempts)
        assertEquals("128k", result.quality)
    }

    @Test fun reportedDowngradeIsNotAcceptedAsTheRequestedHighTier() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK) { quality, _ ->
            attempts += quality
            resolved("320k")
        }
        assertEquals(listOf("flac24bit", "flac", "320k"), attempts)
        assertEquals("320k", result.quality)
    }

    @Test fun slowFullQualityBackupFinishesBeforeTheNextTierCanWin() = runBlocking {
        val tiers = mutableListOf<String>()
        val result = SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK) { quality, _ ->
            tiers += quality
            SourceResolver.preferFirst(10,
                primary = { if (quality == "320k") resolved("320k", "fast-low") else null },
                fallback = { delay(40); resolved(quality, "full-quality") })
        }
        assertEquals(listOf("flac24bit"), tiers)
        assertEquals("full-quality", result.resourceId)
    }

    @Test fun fastPrimaryAvoidsAnyBackupWork() = runBlocking {
        val result = SourceResolver.preferFirst(1000,
            primary = { "primary" }, fallback = { error("no speculative backup for fast source") })
        assertEquals("primary", result)
    }

    @Test fun primaryFailureStartsBackupWithoutWaitingForDelay() = runBlocking {
        val result = withTimeout(1000) {
            SourceResolver.preferFirst(10_000,
                primary = { error("failed") }, fallback = { "backup" })
        }
        assertEquals("backup", result)
    }

    @Test fun backupCanWinSameTierAndCancelSlowPrimary() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val result = SourceResolver.preferFirst(10,
            primary = { try { awaitCancellation() } finally { stopped.complete(Unit) } },
            fallback = { "same-quality-backup" })
        assertEquals("same-quality-backup", result)
        stopped.await()
    }

    @Test fun zeroDelayRunsBothBranchesAndKeepsTheSamePlatformCandidate() = runBlocking {
        val samePlatformStarted = CompletableDeferred<Unit>()
        val alternativeStarted = CompletableDeferred<Unit>()
        val result = withTimeout(1000) {
            SourceResolver.preferFirst(0,
                primary = {
                    samePlatformStarted.complete(Unit)
                    alternativeStarted.await()
                    "same-platform"
                },
                fallback = {
                    assertTrue(samePlatformStarted.isCompleted)
                    alternativeStarted.complete(Unit)
                    null
                },
            )
        }
        assertEquals("same-platform", result)
    }

    @Test fun failedPrimaryAndBackupReturnNoCandidate() = runBlocking {
        val backupEntered = CompletableDeferred<Unit>()
        val primaryFailed = CompletableDeferred<Unit>()
        val result = withTimeout(1000) { SourceResolver.preferFirst<String>(10,
            primary = { backupEntered.await(); primaryFailed.complete(Unit); null },
            fallback = { backupEntered.complete(Unit); primaryFailed.await(); null }) }
        assertNull(result)
    }

    @Test fun slowPlatformDoesNotBlockFirstUsableMatchingPlatform() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val result = withTimeout(1000) {
            SourceResolver.firstSuccessful(listOf("slow", "empty", "fast")) {
                when (it) {
                    "slow" -> try { awaitCancellation() } finally { stopped.complete(Unit) }
                    "empty" -> null
                    else -> "matched"
                }
            }
        }
        assertEquals("matched", result)
        stopped.await()
    }

    @Test fun wholeRequestCancellationDoesNotStartLowerQuality() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        val job = launch {
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK) { quality, _ ->
                attempts += quality
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf("flac24bit"), attempts)
    }

    @Test fun sourceParameterAliasesDoNotDependOnIncompleteCatalogFlags() {
        assertEquals("hires", SourceResolver.selectQuality("flac24bit", listOf("hires", "flac", "128k")))
        assertEquals("flac24bit", SourceResolver.selectQuality("flac24bit", emptyList()))
        assertNull(SourceResolver.selectQuality("flac24bit", listOf("flac", "320k")))
        assertEquals("master", SourceResolver.selectQuality("master", listOf("flac24bit", "master")))
        assertEquals("flac", SourceResolver.selectQuality(" FLAC ", listOf("FLAC")))
    }

    @Test fun failedPhysicalResourceIsSharedAcrossConsumersWithoutRejectingOtherFiles() {
        SourceResolver.clearCache()
        try {
            SourceResolver.rejectResource("lx:source:bad-url")
            assertTrue(SourceResolver.isRejected("lx:source:bad-url"))
            assertFalse(SourceResolver.isRejected("lx:source:new-url"))
            assertFalse(SourceResolver.isRejected(null))
            SourceResolver.clearCache()
            assertFalse(SourceResolver.isRejected("lx:source:bad-url"))
        } finally { SourceResolver.clearCache() }
    }

    @Test fun lastWaiterCancellationStopsUnusedResolutionAndAllowsNewRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val flight = SingleFlight<String, String>(scope)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val waiter = launch {
                flight.run("old-song") {
                    try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            started.await()
            waiter.cancelAndJoin()
            withTimeout(1000) { stopped.await() }
            assertEquals("new", flight.run("old-song") { "new" })
        } finally { scope.cancel() }
    }
    @Test fun equivalentSourceAliasesReuseCacheWithoutTreatingLowerTiersAsEquivalent() {
        assertTrue(SourceResolver.sameQualityTier("flac24bit", "hires"))
        assertFalse(SourceResolver.sameQualityTier("flac24bit", "flac"))
        assertFalse(SourceResolver.sameQualityTier("320k", "128k"))
        assertFalse(SourceResolver.sameQualityTier("128k", "aac100k"))
        assertTrue(SourceResolver.qualitySatisfies("128k", "aac100k"))
        assertFalse(SourceResolver.qualitySatisfies("320k", "aac100k"))
        assertTrue(SourceResolver.qualitySatisfies("128k", "256k"))
        assertTrue(SourceResolver.qualitySatisfies("128k", "96k"))
        assertFalse(SourceResolver.qualitySatisfies("320k", "256k"))
    }

    @Test fun decoderConfirmationCorrectsOnlyTheMatchingPhysicalResource() {
        SourceResolver.clearCache()
        try {
            SourceResolver.confirmQuality("lx:source:file-a", "128k")
            assertEquals("128k", SourceResolver.observedQuality("lx:source:file-a"))
            assertNull(SourceResolver.observedQuality("lx:source:file-b"))
        } finally { SourceResolver.clearCache() }
    }

}
