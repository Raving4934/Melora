package com.leyu.melora.playback

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.playback.lx.LxScriptStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

/** 使用独立files/cache目录和偏好名。只走真实ContentResolver/文件，不读写用户库或用户音源。 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    companion object {
        private val context by lazy { BackupContext(InstrumentationRegistry.getInstrumentation().targetContext) }
        @AfterClass @JvmStatic fun cleanupFixture() { context.cleanup() }
    }

    @Before
    fun resetFixture() {
        context.commitAction = null
        BackupManager.recoverInterruptedRestore(context)
        context.filesDir.deleteRecursively(); context.filesDir.mkdirs()
        context.cacheDir.deleteRecursively(); context.cacheDir.mkdirs()
        for (name in listOf(MeloraSettings.PREFS, LxScriptStore.PREFS)) {
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        MeloraSettings.init(context)
        MeloraSettings.reloadAfterRestore()
        UserLibrary.init(context)
        UserLibrary.replaceFromBackup("{}")
        LxScriptStore(context)
    }

    @Test
    fun localSortSelectionSurvivesSettingsReload() {
        MeloraSettings.updateLocalSortField(LocalSortField.AddedAt)
        MeloraSettings.updateLocalSortAscending(false)
        assertEquals("added_at", context.getSharedPreferences(MeloraSettings.PREFS, 0)
            .getString(MeloraSettings.KEY_LOCAL_SORT_FIELD, null))
        assertFalse(context.getSharedPreferences(MeloraSettings.PREFS, 0)
            .getBoolean(MeloraSettings.KEY_LOCAL_SORT_ASCENDING, true))

        MeloraSettings.localSortField.value = LocalSortField.FileName
        MeloraSettings.localSortAscending.value = true
        MeloraSettings.reloadAfterRestore()

        assertEquals(LocalSortField.AddedAt, MeloraSettings.localSortField.value)
        assertFalse(MeloraSettings.localSortAscending.value)
    }

    @Test
    fun realFileExportImportRoundTripRestoresAllPortableDataAndKeepsOtherSources() = runBlocking<Unit> {
        MeloraSettings.updatePlaylistTag("tag-id", "测试分类")
        MeloraSettings.updatePlaylistSort(1)
        MeloraSettings.updateLyricBackground(true)
        MeloraSettings.updateNotificationLyrics(true)
        MeloraSettings.updateLocalAutoFillInfo(true)
        UserLibrary.replaceFromBackup(library("original").toString())
        val store = LxScriptStore(context)
        val originUrl = "https://example.test/saved.js"
        store.import("saved.js", "// original test source", originUrl)
        val target = document("round-trip.json")
        assertTrue(BackupManager.export(context, target).getOrThrow().contains("备份完成"))
        val parsed = parseBackupDocument(File(target.path!!).readText())
        val exportedScripts = requireNotNull(parsed.scripts)
        assertEquals("测试分类", parsed.settings!!.getString("playlistTagName"))
        assertEquals("saved.js", exportedScripts.single().fileName)
        assertEquals(originUrl, exportedScripts.single().originUrl)

        MeloraSettings.updatePlaylistTag("different", "changed")
        MeloraSettings.updateLyricBackground(false)
        UserLibrary.replaceFromBackup(library("changed").toString())
        store.import("saved.js", "// changed test source", "https://stale.example.test/saved.js")
        store.import("other.js", "// not part of backup")
        BackupManager.import(context, target).getOrThrow()
        assertEquals("original", UserLibrary.favorites.value.single().name)
        assertEquals("tag-id", MeloraSettings.playlistTagId.value)
        assertEquals("测试分类", MeloraSettings.playlistTagName.value)
        assertTrue(MeloraSettings.lyricBackground.value)
        assertTrue(MeloraSettings.notificationLyrics.value)
        assertTrue(MeloraSettings.localAutoFillInfo.value)
        assertEquals(setOf("saved.js", "other.js"), store.list().map { it.id }.toSet())
        assertEquals("// original test source", store.code("saved.js"))
        assertEquals(originUrl, store.list().single { it.id == "saved.js" }.originUrl)
        val first = UserLibrary.exportSnapshot()
        val sourceFile = File(context.filesDir, "lx-sources/saved.js")
        val previousStamp = sourceFile.lastModified()
        BackupManager.import(context, target).getOrThrow()
        assertEquals("unchanged scripts must not be rewritten/restarted", previousStamp, sourceFile.lastModified())
        assertEquals(first, UserLibrary.exportSnapshot())
        assertEquals(2, store.list().size)
        assertFalse(File(context.filesDir, "backup-restore").exists())
    }

    @Test
    fun legacyBackupClearsAStaleLinkedSourceOriginWithoutRewritingCode() = runBlocking<Unit> {
        val store = LxScriptStore(context)
        val code = "// legacy source"
        store.import("legacy.js", code, "https://example.test/legacy.js")
        val sourceFile = File(context.filesDir, "lx-sources/legacy.js")
        val previousStamp = sourceFile.lastModified()
        val target = document("legacy-origin.json")
        File(target.path!!).writeText(JSONObject()
            .put("version", 1)
            .put("scripts", JSONArray().put(JSONObject()
                .put("fileName", "legacy.js")
                .put("code", code)
                .put("enabled", false)))
            .toString())

        BackupManager.import(context, target).getOrThrow()

        assertNull(store.list().single().originUrl)
        assertEquals(previousStamp, sourceFile.lastModified())
    }

    @Test
    fun malformedAndOversizedDocumentsNeverChangeExistingState() = runBlocking<Unit> {
        seedExistingData()
        val before = diskSnapshot()
        val malformed = document("bad.json")
        File(malformed.path!!).writeText("""{"settings":{"showExitButton":false},"library":{"favorites":[42]}}""")
        assertTrue(BackupManager.import(context, malformed).isFailure)
        assertEquals(before, diskSnapshot())
        val large = document("too-large.json")
        File(large.path!!).outputStream().use { output ->
            val block = ByteArray(64 * 1024) { ' '.code.toByte() }
            repeat(MAX_BACKUP_BYTES / block.size + 1) { output.write(block) }
        }
        assertTrue(BackupManager.import(context, large).isFailure)
        assertEquals(before, diskSnapshot())
        assertEquals("existing", UserLibrary.favorites.value.single().name)
    }

    @Test
    fun sourcePreferenceCommitFailureRollsBackSettingsLibraryAndEverySourceFile() = runBlocking<Unit> {
        seedExistingData()
        val before = diskSnapshot()
        context.commitAction = { name -> if (name == LxScriptStore.PREFS) {
            context.commitAction = null; false
        } else true }
        assertTrue(BackupManager.import(context, incoming()).isFailure)
        assertEquals(before, diskSnapshot())
        assertTrue(MeloraSettings.showExitButton.value)
        assertEquals("existing", UserLibrary.favorites.value.single().name)
        assertNull(LxScriptStore(context).code("new.js"))
    }

    @Test
    fun cancellationAfterSettingsCommitRollsBackBeforeReturning() = runBlocking<Unit> {
        seedExistingData()
        val before = diskSnapshot()
        val uri = incoming()
        var cancelled = false
        val task = launch {
            val job = currentCoroutineContext()[Job]!!
            context.commitAction = { name ->
                if (name == MeloraSettings.PREFS) { context.commitAction = null; job.cancel() }
                true
            }
            try { BackupManager.import(context, uri) } catch (_: CancellationException) { cancelled = true }
        }
        task.join()
        assertTrue(cancelled)
        assertEquals(before, diskSnapshot())
        assertEquals("existing", UserLibrary.favorites.value.single().name)
    }

    @Test
    fun interruptedReadyJournalRecoversOriginalDataAndRecoveryIsIdempotent() {
        seedExistingData()
        val before = diskSnapshot()
        BackupRestoreTransaction.prepare(context)
        MeloraSettings.updateShowExit(false)
        UserLibrary.replaceFromBackup(library("partially-restored").toString())
        LxScriptStore(context).import("partial.js", "// partial source")
        BackupManager.recoverInterruptedRestore(context)
        MeloraSettings.reloadAfterRestore(); UserLibrary.reloadAfterRestore()
        assertEquals(before, diskSnapshot())
        assertTrue(MeloraSettings.showExitButton.value)
        assertEquals("existing", UserLibrary.favorites.value.single().name)
        BackupManager.recoverInterruptedRestore(context)
        assertEquals(before, diskSnapshot())
    }

    @Test
    fun unknownFieldsAreIgnoredAndOtherSettingsStillRestore() = runBlocking<Unit> {
        val uri = document("legacy.json")
        File(uri.path!!).writeText(JSONObject().put("settings", JSONObject()
            .put("followSystemTheme", "FaLsE").put("playQuality", "flac")
            .put("unknownObjectSetting", JSONObject()).put("unknownArraySetting", JSONArray())
            .put("showExitButton", false).put("dataChannel", "app")
            .put("localFolders", JSONArray().put("content://missing/tree/music"))
            .put("downloadPath", "content://missing/tree/downloads")).toString())
        val result = BackupManager.import(context, uri).getOrThrow()
        assertEquals(ThemeMode.Light, MeloraSettings.themeMode.value)
        assertEquals("flac", MeloraSettings.playQualityWifi.value)
        assertFalse(MeloraSettings.showExitButton.value)
        assertFalse(BackupSettings.collect().has("dataChannel"))
        assertTrue(MeloraSettings.localFolders.value.isEmpty())
        assertEquals(MeloraSettings.DEFAULT_DOWNLOAD_PATH, MeloraSettings.downloadPath.value)
        assertTrue(result.contains("授权"))
    }

    @Test
    fun contentProviderExportFailureRestoresPreviousDocument() = runBlocking<Unit> {
        val authority = InstrumentationRegistry.getInstrumentation().context.packageName + ".backup-fixture"
        val uri = Uri.parse("content://$authority/backup/fail-once.json")
        context.contentResolver.call(uri, "reset", null, null)
        val previous = "{\"keep\":\"previous backup\"}"
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(previous.toByteArray()) }
        context.contentResolver.call(uri, "failNextWrite", null, null)
        assertTrue(BackupManager.export(context, uri).isFailure)
        assertEquals(previous, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        // The next export uses the same real provider path, then real import accepts the file.
        BackupManager.export(context, uri).getOrThrow()
        BackupManager.import(context, uri).getOrThrow()
        context.contentResolver.call(uri, "reset", null, null)
    }

    @Test
    fun permanentlyFailingProviderKeepsTheOnlyOriginalCopyInPrivateStorage() = runBlocking<Unit> {
        val authority = InstrumentationRegistry.getInstrumentation().context.packageName + ".backup-fixture"
        val uri = Uri.parse("content://$authority/backup/fail-both.json")
        context.contentResolver.call(uri, "reset", null, null)
        val original = "{\"original\":\"do not lose\"}"
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(original.toByteArray()) }
        context.contentResolver.call(uri, "failBothWrites", null, null)
        val failure = BackupManager.export(context, uri).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("副本已保留"))
        val saved = context.filesDir.listFiles()!!.single { it.name.startsWith("backup-export-recovery-") }
        assertEquals(original, saved.readText())
        BackupManager.recoverInterruptedRestore(context) // 重启后仍发现待取回副本。
        assertTrue(BackupManager.hasExportRecovery.value)
        val recovered = document("saved-original.json")
        BackupManager.saveExportRecovery(context, recovered).getOrThrow()
        assertEquals(original, File(recovered.path!!).readText())
        assertFalse(saved.exists())
        assertFalse(BackupManager.hasExportRecovery.value)
        context.contentResolver.call(uri, "reset", null, null)
    }

    private fun seedExistingData() {
        MeloraSettings.updateShowExit(true)
        UserLibrary.replaceFromBackup(library("existing").toString())
        LxScriptStore(context).import("existing.js", "// existing test source")
    }
    private fun incoming(): Uri = document("incoming.json").also { uri ->
        File(uri.path!!).writeText(JSONObject().put("version", 1)
            .put("settings", JSONObject().put("showExitButton", false))
            .put("library", library("incoming"))
            .put("scripts", JSONArray().put(JSONObject().put("fileName", "new.js").put("code", "// new test source").put("enabled", false)))
            .toString())
    }
    private fun library(name: String) = JSONObject().put("favorites", JSONArray().put(
        JSONObject().put("source", "kw").put("songmid", "test-id").put("name", name)))
    private fun document(name: String): Uri = Uri.fromFile(File(context.cacheDir, name).apply { createNewFile() })
    private fun diskSnapshot(): Map<String, String> = buildMap {
        context.filesDir.walkTopDown().filter { it.isFile }.forEach { put(it.relativeTo(context.filesDir).path, it.readText()) }
        for (name in listOf(MeloraSettings.PREFS, LxScriptStore.PREFS)) {
            put("prefs:$name", context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().toString())
        }
    }

    private class BackupContext(base: Context) : ContextWrapper(base) {
        private val root = File(base.cacheDir, "isolated-backup-tests").apply { mkdirs() }
        var commitAction: ((String) -> Boolean)? = null
        fun cleanup() {
            commitAction = null
            root.deleteRecursively()
            for (name in listOf(MeloraSettings.PREFS, LxScriptStore.PREFS)) {
                super.deleteSharedPreferences("isolated-backup-tests.$name")
            }
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun startService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent): Boolean = true
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences("isolated-backup-tests.$name", mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean = if (commitAction?.invoke(name) == false) false else editor.commit()
                    }
                }
            }
        }
    }
}
