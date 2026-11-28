package com.leyu.melora.playback

import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
        resolver.takePersistableUriPermission(tree, flags)
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

    @Test
    fun aNewlyDueLocalTargetCanCompleteDuringAStagedNetworkRetry() = runBlocking {
        checkLocalBackupDuringNetworkRetry { prefs, _, now ->
            prefs.edit().putLong("success.LOCAL", now - 25 * 60 * 60 * 1_000L).apply()
        }
    }

    @Test
    fun sameSnapshotWrittenToAnOldDirectoryStillBacksUpToTheNewDirectory() = runBlocking {
        checkLocalBackupDuringNetworkRetry { prefs, staged, _ ->
            val previousScope = DocumentsContract.buildTreeDocumentUri(requireNotNull(tree.authority), "previous-dir")
            prefs.edit().putString("done.LOCAL", staged.id.uuid.toString())
                .putString("scope.LOCAL", previousScope.toString()).apply()
        }
    }

    @Test
    fun unreadableNetworkConfigKeepsTheAccountBoundSnapshotForRetry() = runBlocking {
        checkLocalBackupDuringNetworkRetry(networkScope = "previously-readable-account") { prefs, _, _ ->
            prefs.edit().remove("success.LOCAL").apply()
        }
    }

    private suspend fun checkLocalBackupDuringNetworkRetry(
        networkScope: String = "",
        makeLocalDue: (SharedPreferences, BackupSnapshot, Long) -> Unit,
    ) {
        val directory = File(context.cacheDir, "scheduled-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val fixture = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${directory.name}-$name", mode)
        }
        val prefs = fixture.getSharedPreferences("melora-backup-schedule", 0)
        val now = System.currentTimeMillis()
        try {
            prefs.edit().putString("directory", tree.toString()).putString("automatic", "BOTH")
                .putString("scope.LOCAL", tree.toString()).putLong("success.LOCAL", now).apply()
            // 放入旧版只包含网络目的地的待重试快照。隔离目录没有 WebDAV 配置，网络会持续失败。
            val staged = snapshot(now)
            File(directory, "auto-backup-snapshot.json").writeText(staged.text)
            prefs.edit().putString("pending", JSONObject().put("name", staged.id.localName).put("NETWORK", networkScope).toString()).apply()

            assertTrue(BackupSchedule.runAutomatic(fixture, 0x4D4201))
            assertEquals(emptySet<String>(), names())
            makeLocalDue(prefs, staged, now)

            repeat(2) { assertTrue(BackupSchedule.runAutomatic(fixture, 0x4D4201)) }
            assertEquals(setOf(staged.id.localName), names())
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, staged.id.localName)
            assertEquals(staged.text, resolver.openInputStream(uri)!!.use { it.readBackupText() })
            assertEquals(tree.toString(), prefs.getString("scope.LOCAL", null))
            assertEquals(staged.id.uuid.toString(), prefs.getString("done.LOCAL", null))
            val pending = JSONObject(requireNotNull(prefs.getString("pending", null)))
            assertEquals(staged.id.localName, pending.getString("name"))
            val oldBackup = File(directory, "auto-backup-snapshot.json.bak").apply { writeText(staged.text) }
            BackupSchedule.setAutomatic(fixture, BackupDestination.OFF)
            assertTrue(!oldBackup.exists() && !File(directory, "auto-backup-snapshot.json").exists())
            assertTrue(!prefs.contains("pending"))
        } finally {
            fixture.deleteSharedPreferences("${directory.name}-melora-backup-schedule")
            directory.deleteRecursively()
        }
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
