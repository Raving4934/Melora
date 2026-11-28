package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.BackupRestoreTransaction
import com.leyu.melora.playback.BackupScript
import com.leyu.melora.playback.lx.LxScriptEngine
import com.leyu.melora.playback.lx.LxScriptStore
import com.quickjs.QuickJS
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真 Pool/Store/QuickJS，独立文件及偏好命名空间；不用用户音源或外网。 */
@RunWith(AndroidJUnit4::class)
class LxScriptPoolLifecycleInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureId = UUID.randomUUID().toString()
    private val context = object : ContextWrapper(base) {
        val root = File(base.cacheDir, "pool-lifecycle-$fixtureId")
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("pool-lifecycle-$fixtureId.$name", mode)
    }
    private val store = LxScriptStore(context)
    private val dispatcher get() = field(LxScriptPool, "dispatcher") as CoroutineDispatcher

    @Before fun setUp() = runBlocking {
        store.import("a.js", script("old"))
        store.import("b.js", script("other"))
        store.setEnabled("a.js", true)
        assertEquals(2, LxScriptPool.reload(context))
    }

    @After fun tearDown() = runBlocking<Unit> {
        store.list().forEach { store.remove(it.id) }
        LxScriptPool.reload(context)
        context.getSharedPreferences(LxScriptStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.root.deleteRecursively()
    }

    @Test(timeout = 10_000) fun cancelledReloadStillClosesEveryDetachedRuntime() = retirement(reload = true)
    @Test(timeout = 10_000) fun cancelledRefreshClosesOnlyTheDetachedRuntime() = retirement(reload = false)

    private fun retirement(reload: Boolean) = runBlocking {
        val old = withContext(dispatcher) { entries().toMap() }
        val lock = field(old.getValue("a.js"), "lock") as Mutex
        lock.lock()
        val refresh = async(Dispatchers.IO) {
            if (reload) LxScriptPool.reload(context) else LxScriptPool.refresh(context, setOf("a.js"))
        }
        try {
            withTimeout(2_000) {
                while (withContext(dispatcher) { "a.js" in entries() }) delay(5)
            }
            refresh.cancel()
        } finally {
            lock.unlock()
            refresh.cancelAndJoin()
        }
        assertTrue("detached A runtime must close after cancellation", runtime(old.getValue("a.js")).isReleased)
        assertEquals("reload also owns B cleanup; refresh retains B", reload, runtime(old.getValue("b.js")).isReleased)
    }

    @Test(timeout = 10_000) fun sameVersionOverwriteWithoutRefreshCannotWinRaceWithTheOldEngine() = runBlocking {
        val page = async(Dispatchers.IO) {
            store.import("a.js", script("new"))
            currentCoroutineContext().cancel() // 内容已提交，页面在refresh获得调度前取消。
            LxScriptPool.refresh(context, setOf("a.js"))
        }
        page.join()
        assertTrue(page.isCancelled)
        val winner = LxScriptPool.race(context, "kw", LxScriptPool.ScriptScope.ENABLED, 3_000,
            request = { source, _ -> LxScriptPool.ScriptRequest("fixture", source.id, "kw", "musicUrl", JSONObject(), 2_000) },
            accept = { LxScriptPool.CandidateVerdict.Accepted(it) })
        assertEquals("https://example.test/new.mp3", winner?.data)
    }

    @Test(timeout = 10_000) fun directResolveReconcilesSameVersionOverwrite() = runBlocking {
        store.import("a.js", script("new"))
        assertEquals("https://example.test/new.mp3", LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
    }

    @Test(timeout = 10_000) fun recoveringTheBackupJournalInvalidatesScriptMetadataAndTheLoadedEngine() = runBlocking {
        val original = store.list().first { it.id == "a.js" }
        BackupRestoreTransaction.prepare(context)
        try {
            val candidate = store.import("a.js", script("provisional").replace(original.name, "待回滚的脚本"))
            assertEquals(candidate, store.list().first { it.id == "a.js" })
            assertEquals("https://example.test/provisional.mp3",
                LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
            val candidateRevision = store.revision("a.js")

            BackupRestoreTransaction.recover(context)

            assertEquals(original, store.list().first { it.id == "a.js" })
            assertNotEquals(candidateRevision, store.revision("a.js"))
            assertEquals("https://example.test/old.mp3",
                LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
        } finally {
            BackupRestoreTransaction.recover(context)
        }
    }

    @Test(timeout = 10_000) fun backupOverwriteAndDeleteReimportInvalidateLoadedContent() = runBlocking {
        store.restoreBackup(listOf(BackupScript("a.js", script("restored"), enabled = true))) {}
        assertEquals("https://example.test/restored.mp3", LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
        store.remove("a.js")
        store.import("a.js", script("reimported"))
        assertEquals("https://example.test/reimported.mp3", LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
    }

    @Test(timeout = 10_000) fun newContentBypassesFailureCooldownWithAnUnchangedVersion() = runBlocking {
        store.import("a.js", "// @version 1\nthrow new Error('broken fixture');")
        LxScriptPool.refresh(context, setOf("a.js"))
        assertTrue(LxScriptPool.scriptsFor(context, "kw").isEmpty())
        store.import("a.js", script("recovered"))
        assertEquals("https://example.test/recovered.mp3", LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
    }

    @Test(timeout = 10_000) fun unchangedContentAndEnableSwitchPreserveInitializedRuntime() = runBlocking {
        val before = withContext(dispatcher) { entries().getValue("a.js") }
        store.import("a.js", script("old"))
        store.setEnabled("a.js", false)
        store.setEnabled("a.js", true)
        assertEquals("https://example.test/old.mp3", LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
        LxScriptPool.scriptsFor(context, "kw")
        assertSame(before, withContext(dispatcher) { entries().getValue("a.js") })
        assertFalse(runtime(before).isReleased)
    }

    @Test(timeout = 10_000) fun singleTrackUnavailableDoesNotBlockTheSourceForOtherSongs() = runBlocking {
        prepareHealthFixture("MUSIC_UNAVAILABLE")
        repeat(3) { assertNull(healthRace(fail = true)) }
        assertEquals("https://example.test/available.mp3", healthRace(fail = false)?.data)
    }

    @Test(timeout = 10_000) fun unexpectedScriptErrorsStillTripTheCircuitBreaker() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        repeat(3) { assertNull(healthRace(fail = true)) }
        // B 也是合适的健康源，因此不能为了 A 恢复而绕过有效的熔断。
        assertNull(healthRace(fail = false))
    }

    @Test(timeout = 10_000) fun qualityRetriesInOneResolutionCountAsOneFailure() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        val attempt = LxScriptPool.ResolutionAttempt()
        repeat(4) { assertNull(healthRace(fail = true, attempt = attempt)) }
        assertEquals("a.js", healthRace(fail = false)?.scriptId)
    }

    @Test(timeout = 10_000) fun confirmedAudioClearsFailuresWithoutCountingTheSameResolutionAgain() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        val attempt = LxScriptPool.ResolutionAttempt()
        assertNull(healthRace(fail = true, attempt = attempt))
        assertEquals("a.js", healthRace(fail = false, attempt = attempt) {
            LxScriptPool.CandidateVerdict.Accepted(it, verified = true)
        }?.scriptId)
        assertNull(healthRace(fail = true, attempt = attempt))
        repeat(2) { assertNull(healthRace(fail = true)) }
        assertEquals("a.js", healthRace(fail = false)?.scriptId)
    }

    @Test(timeout = 10_000) fun circuitIsScopedToPlatformAndScriptContentRevision() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        for (id in listOf("a.js", "b.js")) {
            val code = store.code(id)!!.replace("sources:{", "sources:{wy:{actions:['musicUrl'],qualitys:['320k']},")
            store.import(id, code)
        }
        repeat(3) { assertNull(healthRace(fail = true)) }
        val supportsExpected = { support: LxScriptPool.PlatformSupport -> "320k" in support.qualitys }
        assertTrue(LxScriptPool.shouldPreferBackup(context, "kw", supportsExpected))
        assertFalse(LxScriptPool.shouldPreferBackup(context, "wy", supportsExpected))
        assertEquals("a.js", healthRace(fail = false, source = "wy")?.scriptId)
        assertNull(healthRace(fail = false))
        store.import("a.js", store.code("a.js")!! + "\n// new revision")
        assertFalse(LxScriptPool.shouldPreferBackup(context, "kw", supportsExpected))
        assertEquals("a.js", healthRace(fail = false)?.scriptId)
    }

    @Test(timeout = 10_000) fun platformSearchPrunesOnlyWhenAllScriptRevisionsAreKnown() = runBlocking {
        val candidates = listOf("kw", "wy")
        assertEquals(listOf("kw"), LxScriptPool.audioSearchPlatforms(context, candidates))
        store.import("b.js", script("updated").replace("sources:{kw", "sources:{wy"))
        assertEquals(candidates, LxScriptPool.audioSearchPlatforms(context, candidates))
        LxScriptPool.scriptsFor(context, "wy", scope = LxScriptPool.ScriptScope.DISABLED)
        assertEquals(candidates, LxScriptPool.audioSearchPlatforms(context, candidates))
    }

    @Test(timeout = 10_000) fun queuedRequestsCancelledByTheRaceDoNotTripTheCircuitBreaker() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        val lock = withContext(dispatcher) { field(entries().getValue("a.js"), "lock") as Mutex }
        lock.lock()
        try {
            repeat(3) { assertNull(healthRace(fail = false, timeoutMs = 150)) }
        } finally {
            lock.unlock()
        }
        assertEquals("https://example.test/available.mp3", healthRace(fail = false)?.data)
    }

    @Test(timeout = 10_000) fun cancelledValidationLeavesPreviousFailuresIntact() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        repeat(2) { assertNull(healthRace(fail = true)) }
        val validating = CompletableDeferred<Unit>()
        val race = async {
            healthRace(fail = false) {
                validating.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(2_000) { validating.await() }
        race.cancelAndJoin()
        // 取消中的候选不清零也不新增失败。未知但可尝试播放的 URL 同样不清零。
        assertEquals("a.js", healthRace(fail = false)?.scriptId)
        assertNull(healthRace(fail = true))
        assertNull(healthRace(fail = false))
    }

    @Test(timeout = 10_000) fun playbackConfirmationClearsTheFailureStreakOnlyOnce() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        assertNull(requireNotNull(healthRace(fail = false)).playbackConfirmation)
        repeat(2) { assertNull(healthRace(fail = true)) }
        val first = requireNotNull(healthRace(fail = false)?.playbackConfirmation)
        val another = requireNotNull(healthRace(fail = false)?.playbackConfirmation)

        first.confirm()
        assertNull(healthRace(fail = true))
        assertEquals("a.js", healthRace(fail = false)?.scriptId)

        repeat(2) { assertNull(healthRace(fail = true)) }
        first.confirm()
        another.confirm()
        assertNull(healthRace(fail = false))
    }

    @Test(timeout = 10_000) fun delayedConfirmationCannotClearANewerFailure() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        repeat(2) { assertNull(healthRace(fail = true)) }
        val pending = requireNotNull(healthRace(fail = false)?.playbackConfirmation)
        assertNull(healthRace(fail = true))
        pending.confirm()
        assertNull(healthRace(fail = false))
    }

    @Test(timeout = 10_000) fun newerFailureDuringCooldownInvalidatesPlaybackConfirmation() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        repeat(3) { assertNull(healthRace(fail = true)) }
        val primaryOnly = LxScriptPool.ScriptScope.ENABLED
        val pending = requireNotNull(healthRace(fail = false, scope = primaryOnly)?.playbackConfirmation)
        val newAttempt = LxScriptPool.ResolutionAttempt()

        assertNull(healthRace(fail = true, scope = primaryOnly, attempt = newAttempt))
        pending.confirm()
        assertNull(healthRace(fail = false))

        val recovered = requireNotNull(healthRace(fail = false, scope = primaryOnly)?.playbackConfirmation)
        assertNull(healthRace(fail = true, scope = primaryOnly, attempt = newAttempt))
        recovered.confirm()
        assertEquals("a.js", healthRace(fail = false)?.scriptId)
    }

    @Test(timeout = 10_000) fun playbackConfirmationClearsOnlyItsRequestPlatform() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        for (id in listOf("a.js", "b.js")) {
            val code = store.code(id)!!.replace("sources:{", "sources:{wy:{actions:['musicUrl'],qualitys:['320k']},")
            store.import(id, code)
        }
        for (source in listOf("kw", "wy")) repeat(3) { assertNull(healthRace(fail = true, source = source)) }
        assertNull(healthRace(fail = false))
        val confirmation = requireNotNull(healthRace(fail = false, scope = LxScriptPool.ScriptScope.ENABLED)?.playbackConfirmation)

        confirmation.confirm()
        assertEquals("a.js", healthRace(fail = false)?.scriptId)
        assertNull(healthRace(fail = false, source = "wy"))
    }

    @Test(timeout = 10_000) fun playbackConfirmationCannotClearReplacementScriptsFailures() = runBlocking {
        prepareHealthFixture("UNEXPECTED_ERROR")
        repeat(3) { assertNull(healthRace(fail = true)) }
        val previous = requireNotNull(healthRace(fail = false, scope = LxScriptPool.ScriptScope.ENABLED)?.playbackConfirmation)
        store.import("a.js", store.code("a.js")!! + "\n// new revision")
        repeat(3) { assertNull(healthRace(fail = true)) }

        previous.confirm()
        assertNull(healthRace(fail = false))
    }

    @Test(timeout = 10_000) fun unrelatedPlatformDoesNotPreventBlockedSourceFallback() = runBlocking {
        assertCompatibleFallback("tx", "musicUrl", "320k")
    }

    @Test(timeout = 10_000) fun unrelatedActionDoesNotPreventBlockedSourceFallback() = runBlocking {
        assertCompatibleFallback("kw", "lyric", "320k")
    }

    @Test(timeout = 10_000) fun unsupportedQualityDoesNotPreventBlockedSourceFallback() = runBlocking {
        assertCompatibleFallback("kw", "musicUrl", "128k")
    }

    private suspend fun assertCompatibleFallback(platform: String, action: String, quality: String) {
        prepareHealthFixture("UNEXPECTED_ERROR", platform, action, quality)
        repeat(3) { assertNull(healthRace(fail = true)) }
        assertEquals("https://example.test/available.mp3", healthRace(fail = false)?.data)
    }

    private suspend fun prepareHealthFixture(
        errorCode: String,
        platform: String = "kw",
        action: String = "musicUrl",
        quality: String = "320k",
    ) {
        store.import("a.js", """
            lx.send('inited', {sources:{kw:{actions:['musicUrl'],qualitys:['320k']}}});
            lx.on('request', ({info}) => {
              if (info.fail) throw Object.assign(new Error('fixture unavailable'), {code:'$errorCode'});
              return 'https://example.test/available.mp3';
            });
        """.trimIndent())
        store.import("b.js", """
            lx.send('inited', {sources:{'$platform':{actions:['$action'],qualitys:['$quality']}}});
            lx.on('request', () => 'https://example.test/not-selected.mp3');
        """.trimIndent())
        LxScriptPool.reload(context)
    }

    private suspend fun healthRace(
        fail: Boolean,
        timeoutMs: Long = 2_000,
        source: String = "kw",
        attempt: LxScriptPool.ResolutionAttempt = LxScriptPool.ResolutionAttempt(),
        scope: LxScriptPool.ScriptScope = LxScriptPool.ScriptScope.ALL,
        acceptPrimary: suspend (LxScriptPool.ScriptResult) -> LxScriptPool.CandidateVerdict<LxScriptPool.ScriptResult> =
            { LxScriptPool.CandidateVerdict.Accepted(it) },
    ) = LxScriptPool.race(
        context, source, scope, timeoutMs, attempt,
        request = { script, support ->
            SourceResolver.selectQuality("320k", support.qualitys)?.let { quality ->
                LxScriptPool.ScriptRequest(quality, script.id, source, "musicUrl", JSONObject().put("fail", fail), 1_000)
            }
        },
        accept = { result ->
            if (result.scriptId == "a.js") acceptPrimary(result) else LxScriptPool.CandidateVerdict.Skipped
        },
    )

    @Suppress("UNCHECKED_CAST")
    private fun entries() = field(LxScriptPool, "entries") as Map<String, Any>
    private fun runtime(entry: Any): QuickJS = field(field(entry, "engine") as LxScriptEngine, "quickJs") as QuickJS
    private fun field(target: Any, name: String): Any =
        checkNotNull(target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target))
    private fun script(marker: String) = """
        // @name Pool lifecycle fixture
        // @version 1
        lx.send('inited', {sources:{kw:{actions:['musicUrl'],qualitys:['320k']}}});
        lx.on('request', () => 'https://example.test/$marker.mp3');
    """.trimIndent()
}
