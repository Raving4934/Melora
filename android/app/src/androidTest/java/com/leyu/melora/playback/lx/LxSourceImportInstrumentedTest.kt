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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
    fun importsLocalScriptsAndJsonBundlesVerbatim() = runBlocking<Unit> {
        val localCode = "const localScript = true;"
        val local = importer().importLocal(sourceInput(localCode), "local.js").single()
        assertEquals("local.js", local.id)
        assertEquals(localCode, store.code(local.id))

        val firstCode = "throw new Error('must not execute');"
        val secondCode = "const neverInitializes = true;"
        val bundle = JSONObject().put(
            "scripts",
            JSONArray()
                .put(JSONObject().put("name", "bundle-one.js").put("code", firstCode))
                .put(JSONObject().put("name", "bundle-two.js").put("code", secondCode)),
        )
        val bundled = importer().importLocal(sourceInput(bundle.toString()), "sources.json")
        assertEquals(setOf("bundle-one.js", "bundle-two.js"), bundled.map { it.id }.toSet())
        assertEquals(firstCode, store.code("bundle-one.js"))
        assertEquals(secondCode, store.code("bundle-two.js"))
    }

    @Test
    fun importsScriptsThatThrowOrDoNotInitializeWithoutExecutingThem() = runBlocking<Unit> {
        val scripts = listOf(
            "throw new Error('initialization must not run during import');" to "throws.js",
            "const sourceThatNeverInitializes = true;" to "no-init.js",
            "lx.send(lx.EVENT_NAMES.inited, {status:false,sources:{}});" to "failed-init.js",
        )
        scripts.forEach { (code, name) ->
            val imported = importer().importLocal(sourceInput(code), name).single()
            assertEquals(name, imported.id)
            assertEquals(code, store.code(name))
        }
    }

    @Test
    fun manualInspectStillRejectsUninitializedAndProtocolInvalidScripts() {
        assertThrows(Exception::class.java) {
            runBlocking {
                LxScriptEngine(fixtureContext).use { engine ->
                    engine.inspectSource("const sourceThatNeverInitializes = true;", "no-init.js", timeoutMs = 500)
                }
            }
        }
        assertThrows(Exception::class.java) {
            runBlocking {
                LxScriptEngine(fixtureContext).use { engine ->
                    engine.inspectSource(
                        "lx.send(lx.EVENT_NAMES.inited, {status:false,sources:{}});",
                        "invalid-protocol.js",
                        timeoutMs = 500,
                    )
                }
            }
        }
    }

    @Test
    fun importingScriptWithTopLevelNetworkRequestDoesNotExecuteIt() = runBlocking<Unit> {
        ScriptServer("unused response").use { server ->
            val code = "lx.request('${server.url}', {timeout:100});"
            val imported = importer().importLocal(sourceInput(code), "network.js").single()

            assertEquals("network.js", imported.id)
            assertEquals(code, store.code(imported.id))
            assertEquals(0, server.requestCount.get())
        }
    }

    @Test
    fun rejectsOnlyLightweightInvalidContentAndJsonStructure() {
        listOf(
            "" to "empty.js",
            "   " to "blank.js",
            "<!doctype html><html></html>" to "html.js",
            "{\"ordinary\":true}" to "ordinary.json",
            "{\"scripts\":[{}]}" to "invalid-bundle.json",
        ).forEach { (code, name) -> assertRejected(code, name) }
        assertRejectedBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()), "binary.js")
        assertRejectedBytes(ByteArray(MAX_IMPORTED_SCRIPT_BYTES + 1) { 'a'.code.toByte() }, "oversized.js")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun failedLaterBundleEntryWritesNothingAndKeepsSameNameState() = runBlocking<Unit> {
        val oldCode = "const previousSource = true;"
        val origin = "https://fixture.example.test/old.js"
        val old = store.import("same-name.js", oldCode, origin)
        store.setEnabled(old.id, true)

        val bundle = JSONObject().put(
            "scripts",
            JSONArray()
                .put(JSONObject().put("name", "must-not-exist.js").put("code", "throw new Error('not run');"))
                .put(JSONObject().put("name", "same-name.js").put("code", "")),
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
    fun urlImportAndOriginUpdateSkipExecutionButRejectHtml() = runBlocking<Unit> {
        val throwingCode = "throw new Error('remote import must not execute');"
        ScriptServer(throwingCode).use { server ->
            val sourceImporter = importer()
            val original = sourceImporter.importUrl(server.url).single()
            assertEquals(throwingCode, store.code(original.id))
            store.setEnabled(original.id, true)
            val before = store.list().single()
            val updatedCode = "lx.request('${server.url}', {timeout:100});"
            server.body.set(updatedCode)
            val update = sourceImporter.updateFromOrigin(before)
            assertTrue(update.updated)
            assertTrue(update.script.enabled)
            assertEquals(server.url, update.script.originUrl)
            assertEquals(updatedCode, store.code(before.id))
            assertEquals(2, server.requestCount.get())

            server.body.set("<!doctype html><html>not a script</html>")
            assertThrows(Exception::class.java) { runBlocking { sourceImporter.updateFromOrigin(update.script) } }
            assertEquals(updatedCode, store.code(before.id))
            assertThrows(Exception::class.java) { runBlocking { sourceImporter.importUrl(server.url) } }
            assertEquals(updatedCode, store.code(before.id))
        }
    }

    private class ScriptServer(initial: String) : Closeable {
        val body = AtomicReference(initial)
        val requestCount = AtomicInteger()
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/fixture.js"
        private val worker = thread(name = "source-import-fixture", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                requestCount.incrementAndGet()
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

    private fun importer() = LxSourceImporter(store)

    private fun sourceInput(source: String) = ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))

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

}
