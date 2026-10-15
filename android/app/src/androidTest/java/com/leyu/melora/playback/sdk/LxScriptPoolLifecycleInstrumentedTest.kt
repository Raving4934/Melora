package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.BackupScript
import com.leyu.melora.playback.lx.LxScriptEngine
import com.leyu.melora.playback.lx.LxScriptStore
import com.quickjs.QuickJS
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
            accept = { true })
        assertEquals("https://example.test/new.mp3", winner?.data)
    }

    @Test(timeout = 10_000) fun directResolveReconcilesSameVersionOverwrite() = runBlocking {
        store.import("a.js", script("new"))
        assertEquals("https://example.test/new.mp3", LxScriptPool.resolve(context, "a.js", "kw", "musicUrl", JSONObject()))
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
