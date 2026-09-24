package com.leyu.melora.playback.lx

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LxSourceImportInstrumentedTest {
    private lateinit var fixtureContext: FixtureContext
    private lateinit var store: LxScriptStore

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureContext = FixtureContext(base, UUID.randomUUID().toString().replace("-", ""))
        store = LxScriptStore(fixtureContext)
    }

    @After
    fun tearDown() {
        fixtureContext.cleanup()
    }

    @Test
    fun validLocalJsonBundleAndCommentFreeEvalWrappedScriptsImport() = runBlocking<Unit> {
        val local = importer().importLocal(sourceInput(validSource()), "local.js").single()
        assertEquals("local.js", local.id)
        assertEquals(validSource(), store.code(local.id))

        val bundle = JSONObject().put(
            "scripts",
            JSONArray()
                .put(JSONObject().put("name", "bundle-one.js").put("code", validSource()))
                .put(JSONObject().put("name", "bundle-two.js").put("code", validSource("kg"))),
        )
        val bundled = importer().importLocal(sourceInput(bundle.toString()), "sources.json")
        assertEquals(setOf("bundle-one.js", "bundle-two.js"), bundled.map { it.id }.toSet())

        val noCommentsEvalWrapper = "eval(${JSONObject.quote(validSource())})"
        val evaluated = importer().importLocal(sourceInput(noCommentsEvalWrapper), "eval-wrapped.js").single()
        assertEquals("eval-wrapped.js", evaluated.id)
        assertEquals(noCommentsEvalWrapper, store.code(evaluated.id))
        val statusless = validSource().replace("status:true,", "")
        assertEquals("statusless.js", importer().importLocal(sourceInput(statusless), "statusless.js").single().id)
    }

    @Test
    fun rejectsNonSourceFormatsAndScriptsWithoutAUsableProtocol() {
        val invalidDocuments = listOf(
            "plain text that is not JavaScript" to "plain.txt",
            "const renamedButNotASource = true;" to "renamed.js",
            "{\"name\":\"ordinary JSON\",\"actions\":[\"musicUrl\"]}" to "ordinary.json",
            "// @name metadata only\n// @version 1.0" to "metadata.js",
            "lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{kw:{name:'fixture',type:'music',actions:['musicUrl']}}});" to "no-request.js",
            requestAndInit("{}") to "empty-platform.js",
            requestAndInit("{kw:{name:'fixture',type:'music',actions:[]}}") to "empty-actions.js",
            requestAndInit("{kw:{name:'fixture',type:'music',actions:['musicUrl']}}", status = false) to "failed-init.js",
        )
        invalidDocuments.forEach { (code, name) -> assertRejected(code, name) }
        assertRejectedBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00), "binary.js")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun failedLaterBundleEntryWritesNothingAndKeepsSameNameState() = runBlocking<Unit> {
        val oldCode = validSource("kw")
        val origin = "https://fixture.example.test/old.js"
        val old = store.import("same-name.js", oldCode, origin)
        store.setEnabled(old.id, true)

        val bundle = JSONObject().put(
            "scripts",
            JSONArray()
                .put(JSONObject().put("name", "must-not-exist.js").put("code", validSource("kg")))
                .put(JSONObject().put("name", "same-name.js").put("code", "const notASource = true;")),
        )
        assertThrows(Exception::class.java) {
            runBlocking { importer().importLocal(sourceInput(bundle.toString()), "bundle.json") }
        }

        assertNull(store.code("must-not-exist.js"))
        assertEquals(oldCode, store.code(old.id))
        val preserved = store.list().single { it.id == old.id }
        assertTrue(preserved.enabled)
        assertEquals(origin, preserved.originUrl)
    }

    @Test
    fun validationTimeoutDoesNotPersistDelayedInitialization() {
        val delayed = delayedInitSource(1_000)
        assertThrows(Exception::class.java) {
            runBlocking {
                importer(validationTimeoutMs = 200)
                    .importLocal(sourceInput(delayed), "timeout.js")
            }
        }
        assertTrue(store.list().isEmpty())
        assertNull(store.code("timeout.js"))
    }

    @Test
    fun coroutineCancellationDuringDelayedInitializationDoesNotPersist() {
        val delayed = delayedInitSource(2_000)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(500) {
                    importer(validationTimeoutMs = 8_000)
                        .importLocal(sourceInput(delayed), "cancelled.js")
                }
            }
        }
        assertTrue(store.list().isEmpty())
        assertNull(store.code("cancelled.js"))
    }

    @Test
    fun urlImportAndOriginUpdateRejectBadPayloadWithoutReplacingEnabledSource() = runBlocking<Unit> {
        ScriptServer(validSource()).use { server ->
            val importer = importer()
            val original = importer.importUrl(server.url).single()
            store.setEnabled(original.id, true)
            val before = store.list().single()
            val originalCode = store.code(before.id)
            server.body.set("const ordinaryJavaScript = true;")
            assertThrows(Exception::class.java) { runBlocking { importer.updateFromOrigin(before) } }
            assertEquals(before, store.list().single())
            assertEquals(originalCode, store.code(before.id))
            assertThrows(Exception::class.java) { runBlocking { importer.importUrl(server.url) } }
            assertEquals(before, store.list().single())
            server.body.set("<!doctype html><html>not a script</html>")
            assertThrows(Exception::class.java) { runBlocking { importer.importUrl(server.url) } }
            assertEquals(originalCode, store.code(before.id))
            server.body.set(validSource("kg"))
            val update = importer.updateFromOrigin(before)
            assertTrue(update.updated)
            assertTrue(update.script.enabled)
            assertEquals(server.url, update.script.originUrl)
            assertEquals(validSource("kg"), store.code(before.id))
        }
    }

    private class ScriptServer(initial: String) : Closeable {
        val body = AtomicReference(initial)
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/fixture.js"
        private val worker = thread(name = "source-import-fixture", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    it.soTimeout = 2_000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val bytes = body.get().toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/javascript; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
                }
            }
        }
        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    private fun assertRejected(code: String, name: String) {
        val before = store.list()
        assertThrows(Exception::class.java) {
            runBlocking { importer().importLocal(sourceInput(code), name) }
        }
        assertEquals(before, store.list())
        assertNull(store.code(name))
    }

    private fun assertRejectedBytes(bytes: ByteArray, name: String) {
        val before = store.list()
        assertThrows(Exception::class.java) {
            runBlocking { importer().importLocal(ByteArrayInputStream(bytes), name) }
        }
        assertEquals(before, store.list())
        assertNull(store.code(name))
    }

    private fun importer(validationTimeoutMs: Long = DEFAULT_VALIDATION_TIMEOUT_MS) =
        LxSourceImporter(fixtureContext, store, validationTimeoutMs = validationTimeoutMs)

    private fun sourceInput(source: String) = ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))

    private fun validSource(platform: String = "kw"): String = requestAndInit(
        "{$platform:{name:'fixture',type:'music',actions:['musicUrl'],qualitys:['128k']}}",
    )

    private fun requestAndInit(sources: String, status: Boolean = true): String =
        "lx.on(lx.EVENT_NAMES.request, ()=>Promise.resolve('fixture')); " +
            "lx.send(lx.EVENT_NAMES.inited, {status:$status,sources:$sources});"

    private fun delayedInitSource(delayMs: Int): String =
        "lx.on(lx.EVENT_NAMES.request, ()=>Promise.resolve('fixture')); " +
            "setTimeout(()=>lx.send(lx.EVENT_NAMES.inited, {status:true,sources:" +
            "{kw:{name:'fixture',type:'music',actions:['musicUrl'],qualitys:['128k']}}}), $delayMs);"

    private class FixtureContext(base: Context, runId: String) : ContextWrapper(base) {
        private val root = File(base.cacheDir, "lx-source-import-test-$runId")
        private val preferencePrefix = "lx-source-import-test-$runId"

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$preferencePrefix.$name", mode)

        fun cleanup() {
            getSharedPreferences(LxScriptStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            File(
                baseContext.applicationInfo.dataDir,
                "shared_prefs/$preferencePrefix.${LxScriptStore.PREFS}.xml",
            ).delete()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val DEFAULT_VALIDATION_TIMEOUT_MS = 250L
    }
}
