package com.leyu.melora.playback

import android.content.Intent
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBackupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri("${instrumentation.context.packageName}.download-fixture", "root")
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Before
    fun selectGrantedDirectory() {
        DownloadFixtureProvider.grantTree(instrumentation, true)
        LocalBackup.selectDirectory(context, tree)
        resolver.call(tree, "reset", null, null)
        assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
    }

    @After
    fun releaseFixture() {
        runCatching { resolver.call(tree, "reset", null, null) }
        if (resolver.persistedUriPermissions.any { it.uri == tree }) resolver.releasePersistableUriPermission(tree, flags)
    }

    @Test
    fun successfulBackupAfterClockRollbackRetainsLatestWriteAndLeavesUnrelatedFiles() = runBlocking<Unit> {
        val first = snapshot(2_000L)
        val otherDevice = snapshot(2_100L, device = "ffffffff")
        LocalBackup.write(context, tree, first, keepLatest = false)
        LocalBackup.write(context, tree, otherDevice, keepLatest = false)
        val unrelated = requireNotNull(DocumentsContract.createDocument(resolver, root, "application/json", "manual.json"))

        val latest = snapshot(1_000L)
        LocalBackup.write(context, tree, latest, keepLatest = true)
        assertEquals(setOf(latest.id.localName, otherDevice.id.localName, "manual.json"), names())
        val latestUri = DocumentsContract.buildDocumentUriUsingTree(tree, latest.id.localName)
        assertEquals(latest.text, resolver.openInputStream(latestUri)!!.use { it.readBackupText() })
        assertTrue(resolver.openInputStream(unrelated)!!.use { it.read() == -1 })
    }

    @Test
    fun failedNewWritePreservesOldSnapshotAndRetryIsIdempotent() = runBlocking<Unit> {
        val first = snapshot(1_000L)
        val latest = snapshot(2_000L)
        LocalBackup.write(context, tree, first, keepLatest = false)
        resolver.call(tree, "failNextWrite", null, null)
        assertTrue(runCatching { LocalBackup.write(context, tree, latest, keepLatest = true) }.isFailure)
        assertEquals(setOf(first.id.localName), names())

        LocalBackup.write(context, tree, latest, keepLatest = true)
        LocalBackup.write(context, tree, latest, keepLatest = true)
        assertEquals(setOf(latest.id.localName), names())
    }

    private fun names(): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root")
        return resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
        }
    }

    private fun snapshot(createdAt: Long, device: String = "deadbeef") =
        BackupSnapshot(BackupFileId(createdAt, device, UUID.randomUUID()), """{"version":1,"exportedAt":$createdAt,"library":"{}"}""")
}
