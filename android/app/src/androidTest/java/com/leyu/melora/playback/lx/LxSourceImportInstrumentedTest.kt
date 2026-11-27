package com.leyu.melora.playback.lx

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.BackupRestoreTransaction
import com.leyu.melora.playback.BackupScript
import java.io.ByteArrayInputStream
import java.io.File
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
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
    fun sameVersionImportsPublishContentRevisionsAcrossStoreInstances() = runBlocking<Unit> {
        val original = "// @version 1\nconst value = 'old';"
        val updated = "// @version 1\nconst value = 'new';"
        importer().importLocal(sourceInput(original), "revision.js")
        val first = requireNotNull(store.revision("revision.js"))
        val other = LxScriptStore(fixtureContext)
        assertEquals(first, other.revision("revision.js"))
        store.setEnabled("revision.js", true)
        importer().importLocal(sourceInput(original), "revision.js")
        assertEquals(first, other.revision("revision.js"))
        importer().importLocal(sourceInput(updated), "revision.js")
        val next = requireNotNull(other.revision("revision.js"))
        assertTrue("same-version content overwrite must invalidate old engines", next > first)
        assertEquals(updated to next, other.content("revision.js"))
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
        val initializationFailure = assertThrows(Exception::class.java) {
            runBlocking {
                LxScriptEngine(fixtureContext).use { engine ->
                    engine.inspectSource("const sourceThatNeverInitializes = true;", "no-init.js", timeoutMs = 500)
                }
            }
        }
        assertEquals("脚本未按音源协议完成初始化", initializationFailure.message)
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
    fun linkedScriptExportReimportsAtTheSizeLimitAndStillFollowsItsOriginalLink() = runBlocking<Unit> {
        val originalCode = "// @version 1\nconst original = true;".padEnd(MAX_IMPORTED_SCRIPT_BYTES, ' ')
        val updatedCode = "// @version 2\nconst updated = true;"
        ScriptServer(originalCode).use { server ->
            val sourceImporter = importer()
            val linked = sourceImporter.importUrl(server.url).single()
            val exported = store.exportScript(linked.id)
            assertTrue(exported.toByteArray(Charsets.UTF_8).size > MAX_IMPORTED_SCRIPT_BYTES)
            store.remove(linked.id)

            val restored = sourceImporter.importLocal(sourceInput(exported), "renamed.js").single()
            assertEquals(linked.id, restored.id)
            assertEquals(server.url, restored.originUrl)
            assertEquals(originalCode, store.code(restored.id))
            assertEquals(1, server.requestCount.get())

            server.body.set(exported)
            assertNull(sourceImporter.checkUpdate(restored))
            val reportedOrigin = "https://other.example.test/source.js"
            server.body.set(LxSourcePayload("other-name.js", updatedCode, reportedOrigin).scriptExport())
            val update = requireNotNull(sourceImporter.checkUpdate(restored))
            val updated = sourceImporter.applyUpdate(update)
            assertEquals(linked.id, updated.id)
            assertEquals(server.url, updated.originUrl)
            assertEquals(updatedCode, store.code(updated.id))
            assertNull(sourceImporter.checkUpdate(updated))
            assertEquals(updated, sourceImporter.importUrl(server.url).single())
        }
    }

    @Test
    fun exportedBundleRestoresEachOriginAndKeepsLocalSourcesLocal() = runBlocking<Unit> {
        val original = "// @version 1\nconst fixture = true;"
        val updated = "// @version 2\nconst fixture = false;"
        val localCode = "// @homepage https://example.test\nconst local = true;"
        ScriptServer(original).use { firstServer ->
            ScriptServer(original).use { secondServer ->
                val sourceImporter = importer()
                val first = sourceImporter.importUrl(firstServer.url).single()
                val second = sourceImporter.importUrl(secondServer.url).single()
                val local = sourceImporter.importLocal(sourceInput(localCode), "local.js").single()
                val ids = listOf(first.id, second.id, local.id)
                val exported = store.exportBundle(ids)
                ids.forEach(store::remove)
                val restored = sourceImporter.importLocal(sourceInput(exported), "backup.json").associateBy { it.id }

                assertEquals(ids.toSet(), restored.keys)
                assertEquals(firstServer.url, restored.getValue(first.id).originUrl)
                assertEquals(secondServer.url, restored.getValue(second.id).originUrl)
                assertNull(restored.getValue(local.id).originUrl)
                assertEquals(localCode, store.exportScript(local.id))
                assertEquals(1, firstServer.requestCount.get())
                assertEquals(1, secondServer.requestCount.get())

                firstServer.body.set(updated)
                val update = requireNotNull(sourceImporter.checkUpdate(restored.getValue(first.id)))
                sourceImporter.applyUpdate(update)
                assertEquals(updated, store.code(first.id))
                assertNull(sourceImporter.checkUpdate(restored.getValue(second.id)))
                assertEquals(original, store.code(second.id))
            }
        }
    }

    @Test
    fun legacySourceFilesKeepOnlyAnAlreadyKnownOriginForTheIdenticalScript() = runBlocking<Unit> {
        val code = "// @homepage https://example.test/help\nconst fixture = true;"
        val origin = "https://example.test/source.js"
        val sourceImporter = importer()
        val installed = store.import("linked.js", code, origin)
        val oldBundle = """{"app":"melora","type":"lx-sources","scripts":[{"name":"linked.js","code":${JSONObject.quote(code)}}]}"""
        assertEquals(origin, sourceImporter.importLocal(sourceInput(code), installed.id).single().originUrl)
        assertEquals(origin, sourceImporter.importLocal(sourceInput(oldBundle), "backup.json").single().originUrl)

        val replaced = sourceImporter.importLocal(sourceInput("$code\n// changed"), installed.id).single()
        assertNull(replaced.originUrl)
        store.remove(installed.id)
        assertNull(sourceImporter.importLocal(sourceInput(oldBundle), "backup.json").single().originUrl)
    }

    @Test
    fun urlUpdateAppliesCheckedContentAndRejectsHtml() = runBlocking<Unit> {
        val throwingCode = "// @version 1\nthrow new Error('remote import must not execute');"
        ScriptServer(throwingCode).use { server ->
            val sourceImporter = importer()
            val original = sourceImporter.importUrl(server.url).single()
            assertEquals(throwingCode, store.code(original.id))
            val updatedCode = "// @version 2\nlx.request('${server.url}', {timeout:100});"
            server.body.set(updatedCode)
            val update = requireNotNull(sourceImporter.checkUpdate(original))
            assertEquals("1", update.currentVersion)
            assertEquals("2", update.remoteVersion)
            assertEquals(throwingCode, store.code(original.id))
            store.setEnabled(original.id, true)

            server.body.set("<!doctype html><html>not a script</html>")
            val installed = sourceImporter.applyUpdate(update)
            assertTrue(installed.enabled)
            assertEquals(server.url, installed.originUrl)
            assertEquals(updatedCode, store.code(original.id))
            assertEquals(2, server.requestCount.get())

            assertThrows(Exception::class.java) { runBlocking { sourceImporter.checkUpdate(installed) } }
            assertThrows(Exception::class.java) { runBlocking { sourceImporter.importUrl(server.url) } }
            assertEquals(updatedCode, store.code(original.id))
        }
    }

    @Test
    fun bundleUpdateMatchesOnlyTheOriginalEntryWhenOneRemains() = runBlocking<Unit> {
        val originalCode = "// @name 原音源\nconst original = true;"
        val original = JSONObject().put("name", "original.js").put("code", originalCode)
        val other = JSONObject().put("name", "other.js").put("code", "const other = true;")
        val bundle = JSONObject().put("scripts", JSONArray().put(original).put(other))
        ScriptServer(bundle.toString()).use { server ->
            val sourceImporter = importer()
            val scripts = sourceImporter.importUrl(server.url).associateBy(LxScript::id)
            val updatedCode = "// @version 2\nconst other = 'updated';"
            other.put("code", updatedCode)
            server.body.set(bundle.put("scripts", JSONArray().put(other)).toString())

            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { sourceImporter.checkUpdate(scripts.getValue("original.js")) }
            }
            assertEquals("远端音源包中未找到「原音源」", error.message)
            assertEquals(originalCode, store.code("original.js"))

            val update = requireNotNull(sourceImporter.checkUpdate(scripts.getValue("other.js")))
            assertEquals("other.js", sourceImporter.applyUpdate(update).id)
            assertEquals(updatedCode, store.code("other.js"))
        }
    }

    @Test
    fun inFlightOriginUpdateCannotOverwriteBackupRestore() = runBlocking<Unit> {
        val remoteCode = "const remoteUpdate = true;"
        ScriptServer(remoteCode, holdResponse = true).use { server ->
            val original = store.import("restore-race.js", "const original = true;", server.url)
            store.setEnabled(original.id, true)
            val update = async(Dispatchers.IO) { runCatching { importer().checkUpdate(original) } }
            assertTrue("remote request should reach the response barrier", server.awaitRequest())

            val restoredCode = "const restoredBackup = true;"
            val restoredOrigin = "https://backup.example.test/restore-race.js"
            BackupRestoreTransaction.run(fixtureContext, {}) {
                store.restoreBackup(
                    listOf(BackupScript(original.id, restoredCode, enabled = false, originUrl = restoredOrigin)),
                ) {}
            }

            server.releaseResponse()
            assertTrue("an update prepared before restore must be rejected", update.await().isFailure)
            val restored = store.list().single()
            assertEquals(restoredCode, store.code(original.id))
            assertEquals(restoredOrigin, restored.originUrl)
            assertFalse(restored.enabled)
        }
    }

    @Test
    fun inFlightOriginUpdateRejectsReplacementAndDeletion() = runBlocking<Unit> {
        ScriptServer("const staleResponse = true;", holdResponse = true).use { server ->
            val original = store.import("replacement-race.js", "const original = true;", server.url)
            val update = async(Dispatchers.IO) { runCatching { importer().checkUpdate(original) } }
            assertTrue(server.awaitRequest())

            val replacementOrigin = "https://local.example.test/replacement.js"
            store.import(original.id, "const original = true;", replacementOrigin)
            server.releaseResponse()
            assertTrue("a local source relink must invalidate the pending update", update.await().isFailure)
            assertEquals("const original = true;", store.code(original.id))
            assertEquals(replacementOrigin, store.list().single { it.id == original.id }.originUrl)
        }

        ScriptServer("const staleResponse = true;", holdResponse = true).use { server ->
            val originalCode = "const original = true;"
            val original = store.import("another-update-race.js", originalCode, server.url)
            val update = async(Dispatchers.IO) { runCatching { importer().checkUpdate(original) } }
            assertTrue(server.awaitRequest())

            val interveningCode = "const interveningUpdate = true;"
            store.import(original.id, interveningCode, server.url)
            server.releaseResponse()
            assertTrue("an intervening source update must invalidate the older response", update.await().isFailure)
            assertEquals(interveningCode, store.code(original.id))
            assertEquals(server.url, store.list().single { it.id == original.id }.originUrl)
        }

        ScriptServer("const staleResponse = true;", holdResponse = true).use { server ->
            val original = store.import("deletion-race.js", "const original = true;", server.url)
            val update = async(Dispatchers.IO) { runCatching { importer().checkUpdate(original) } }
            assertTrue(server.awaitRequest())

            store.remove(original.id)
            server.releaseResponse()
            assertTrue("a deleted script must not be recreated by a pending update", update.await().isFailure)
            assertNull(store.code(original.id))
        }
    }

    @Test
    fun applyingCheckedUpdateRejectsReimportOfSameContent() = runBlocking<Unit> {
        val code = "const original = true;"
        ScriptServer("const updated = true;").use { server ->
            val sourceImporter = importer()
            val original = store.import("confirm-race.js", code, server.url)
            val update = requireNotNull(sourceImporter.checkUpdate(original))

            store.remove(original.id)
            store.import(original.id, code, server.url)

            assertThrows(IllegalStateException::class.java) { runBlocking { sourceImporter.applyUpdate(update) } }
            assertEquals(code, store.code(original.id))
            assertEquals(1, server.requestCount.get())
        }
    }

    @Test
    fun sourceMetadataFollowsContentRevisionAndReadsCurrentPreferences() {
        val original = store.import("metadata-revision.js", "// @name 原音源\n// @version 1")
        val other = LxScriptStore(fixtureContext)
        assertEquals(original, other.list().single())
        val originalRevision = store.revision(original.id)

        store.setEnabled(original.id, true)
        assertTrue(other.list().single().enabled)
        assertEquals(originalRevision, other.revision(original.id))

        val replacement = other.import(original.id, "// @name 新音源\n// @version 2")
        assertEquals(replacement, store.list().single())
        assertEquals("新音源", replacement.name)
        assertEquals("2", replacement.version)
        assertFalse(originalRevision == store.revision(original.id))

        store.remove(original.id)
        val addedAgain = store.import(original.id, "// @name 重新导入")
        assertEquals(addedAgain, other.list().single())
        assertFalse(addedAgain.enabled)
    }

    @Test
    fun enableChangeDoesNotInvalidateUnchangedOriginAndNoUpdateDoesNotWrite() = runBlocking<Unit> {
        val code = "const unchanged = true;"
        ScriptServer(code, holdResponse = true).use { server ->
            val original = store.import("unchanged-race.js", code, server.url)
            val file = File(fixtureContext.filesDir, "${LxScriptStore.DIRECTORY}/${original.id}")
            assertTrue(file.setLastModified(1_000L))
            val modifiedAt = file.lastModified()
            val update = async(Dispatchers.IO) { importer().checkUpdate(original) }
            assertTrue(server.awaitRequest())

            store.setEnabled(original.id, true)
            server.releaseResponse()
            assertNull(update.await())
            assertTrue(store.list().single { it.id == original.id }.enabled)
            assertEquals(code, store.code(original.id))
            assertEquals(modifiedAt, file.lastModified())
        }
    }

    private class ScriptServer(initial: String, private val holdResponse: Boolean = false) : Closeable {
        val body = AtomicReference(initial)
        val requestCount = AtomicInteger()
        private val requestArrived = CountDownLatch(1)
        private val responseReleased = CountDownLatch(1)
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/fixture.js"

        fun awaitRequest(): Boolean = requestArrived.await(5, TimeUnit.SECONDS)

        fun releaseResponse() { responseReleased.countDown() }

        private val worker = thread(name = "source-import-fixture", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                requestCount.incrementAndGet()
                socket.use {
                    it.soTimeout = 2_000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* 消费完整请求头。 */ }
                    val bytes = body.get().toByteArray(Charsets.UTF_8)
                    requestArrived.countDown()
                    if (holdResponse) responseReleased.await()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/javascript; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
                }
            }
        }
        override fun close() {
            releaseResponse()
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
