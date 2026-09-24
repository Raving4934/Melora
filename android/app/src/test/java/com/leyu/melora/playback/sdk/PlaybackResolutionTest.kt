package com.leyu.melora.playback.sdk

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
            val result = SourceResolver.resolveTiers("flac24bit", purpose, tierBudgetMs = 1000) { quality ->
                attempts += quality
                delay(80)
                resolved(quality)
            }
            assertEquals("flac24bit", result.quality)
            assertEquals(listOf("flac24bit"), attempts)
        }
    }

    @Test fun slowSourceRecoveryNeverTriesLowerQuality() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = runCatching {
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.REBUFFER, 50) { quality ->
                attempts += quality
                resolved("flac")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("flac24bit"), attempts)
    }

    @Test fun slowSourceRecoveryDoesNotPickMoreBandwidthHungryHigherTier() = runBlocking {
        val result = runCatching {
            SourceResolver.resolveTiers("320k", SourceResolver.Purpose.REBUFFER, 50) { resolved("flac24bit") }
        }
        assertTrue(result.isFailure)
    }

    @Test fun everyTierRetainsItsOwnBudgetAndFailedHighTierDoesNotSkipFlac() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK, 50) { quality ->
            attempts += quality
            if (quality == "flac24bit") awaitCancellation() else resolved(quality)
        }
        assertEquals(listOf("flac24bit", "flac"), attempts)
        assertEquals("flac", result.quality)
    }

    @Test fun reportedDowngradeIsNotAcceptedAsTheRequestedHighTier() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK) { quality ->
            attempts += quality
            resolved("320k")
        }
        assertEquals(listOf("flac24bit", "flac", "320k"), attempts)
        assertEquals("320k", result.quality)
    }

    @Test fun failedPhysicalResourceStopsPrimaryTiersBeforeDowngrading() = runBlocking {
        val attempts = mutableListOf<String>()
        var rejected = false
        val result = runCatching {
            SourceResolver.resolveTiers("320k", SourceResolver.Purpose.PLAYBACK,
                stopAfterFailure = { rejected }) { quality ->
                attempts += quality
                rejected = true
                null
            }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("320k"), attempts)
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

    @Test fun knownPrimaryFallbackSkipsBackupAfterGraceAndCancelsHigherTier() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var candidate: String? = null
        var backups = 0
        val result = SourceResolver.preferFirst(10,
            primary = {
                try { candidate = "primary-hq"; ready.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            },
            fallback = { backups++; "backup-claimed-hr" },
            available = { candidate }, availableSignal = ready)
        assertEquals("primary-hq", result)
        assertEquals(0, backups)
        stopped.await()
    }

    @Test fun primaryCandidateArrivingDuringBackupWaitWinsWithoutWaitingForEitherChain() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val backupEntered = CompletableDeferred<Unit>()
        val primaryStopped = CompletableDeferred<Unit>()
        val backupStopped = CompletableDeferred<Unit>()
        var candidate: String? = null
        val result = withTimeout(1000) { SourceResolver.preferFirst(10,
            primary = {
                try {
                    backupEntered.await()
                    candidate = "primary-hq"; ready.complete(Unit)
                    awaitCancellation()
                } finally { primaryStopped.complete(Unit) }
            },
            fallback = { try { backupEntered.complete(Unit); awaitCancellation() } finally { backupStopped.complete(Unit) } },
            available = { candidate }, availableSignal = ready) }
        assertEquals("primary-hq", result)
        primaryStopped.await(); backupStopped.await()
    }

    @Test fun failedPrimaryAndBackupDoNotLeaveCandidateSignalWaiting() = runBlocking {
        val backupEntered = CompletableDeferred<Unit>()
        val primaryFailed = CompletableDeferred<Unit>()
        val result = withTimeout(1000) { SourceResolver.preferFirst<String>(10,
            primary = { backupEntered.await(); primaryFailed.complete(Unit); null },
            fallback = { backupEntered.complete(Unit); primaryFailed.await(); null },
            availableSignal = CompletableDeferred()) }
        assertNull(result)
    }

    @Test fun completedPrimaryHigherQualityWinsOverItsEarlierLowerCandidate() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var candidate: String? = null
        val result = SourceResolver.preferFirst(1000,
            primary = { candidate = "primary-hq"; ready.complete(Unit); "primary-hr" },
            fallback = { error("No backup for completed primary") },
            available = { candidate }, availableSignal = ready)
        assertEquals("primary-hr", result)
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
            SourceResolver.resolveTiers("flac24bit", SourceResolver.Purpose.PLAYBACK) {
                attempts += it
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
