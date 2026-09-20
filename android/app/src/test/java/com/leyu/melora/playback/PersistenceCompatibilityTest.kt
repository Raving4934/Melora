package com.leyu.melora.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class PersistenceCompatibilityTest {
    @Test
    fun atomicWriteReplacesPrimaryAndKeepsPreviousSnapshot() {
        val directory = Files.createTempDirectory("melora-library").toFile()
        try {
            val target = directory.resolve("user-library.json").apply { writeText("old") }
            writeTextAtomically(target, "new")
            assertEquals("new", target.readText())
            assertEquals("old", directory.resolve("user-library.json.bak").readText())
            assertFalse(directory.resolve("user-library.json.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun parserAcceptsLegacyObjectLibraryAndValidatesEverySectionFirst() {
        val library = JSONObject().put("favorites", JSONArray())
        val parsed = parseBackupDocument(
            JSONObject()
                .put("settings", JSONObject().put("playQuality", "320k"))
                .put("library", library)
                .put("scripts", JSONArray().put(JSONObject().put("code", "// source")))
                .toString(),
        )
        assertEquals(library.toString(), parsed.library)
        assertEquals(1, parsed.scripts?.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsFutureBackupBeforeRestore() {
        parseBackupDocument(JSONObject().put("version", 2).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsCorruptLibraryBeforeRestore() {
        parseBackupDocument(JSONObject().put("version", 1).put("library", "not-json").toString())
    }
    @Test
    fun restoreFallsBackOnlyForUnwritableSafPath() {
        val safPath = "content://com.example.documents/tree/primary%3AMusic%2FMelora"
        assertEquals(MeloraSettings.DEFAULT_DOWNLOAD_PATH, restoredDownloadPath(safPath, emptySet()))
        assertEquals(safPath, restoredDownloadPath(safPath, setOf(safPath)))
        assertEquals("/storage/emulated/0/Music/Other", restoredDownloadPath("/storage/emulated/0/Music/Other", emptySet()))
    }

    @Test
    fun settingsBackupIncludesSourceAliasPreference() {
        val before = MeloraSettings.sourceAliasEnabled.value
        try {
            MeloraSettings.updateSourceAlias(false)
            assertEquals(false, BackupSettings.collect().getBoolean("sourceAliasEnabled"))
            MeloraSettings.updateSourceAlias(true)
            assertEquals(true, BackupSettings.collect().getBoolean("sourceAliasEnabled"))
        } finally { MeloraSettings.updateSourceAlias(before) }
    }

    @Test
    fun settingsBackupIncludesEveryDownloadPreference() {
        val oldValues = listOf(
            MeloraSettings.downloadSkipSameName.value,
            MeloraSettings.downloadAutoSwitchSource.value,
            MeloraSettings.downloadConcurrentTasks.value,
            MeloraSettings.downloadFileNameFormat.value,
            MeloraSettings.downloadEmbedCover.value,
            MeloraSettings.downloadEmbedLyric.value,
        )
        try {
            MeloraSettings.downloadSkipSameName.value = true
            MeloraSettings.downloadAutoSwitchSource.value = false
            MeloraSettings.downloadConcurrentTasks.value = 4
            MeloraSettings.downloadFileNameFormat.value = "artist-song"
            MeloraSettings.downloadEmbedCover.value = true
            MeloraSettings.downloadEmbedLyric.value = true

            val settings = BackupSettings.collect()
            assertEquals(true, settings.getBoolean("downloadSkipSameName"))
            assertEquals(false, settings.getBoolean("downloadAutoSwitchSource"))
            assertEquals(4, settings.getInt("downloadConcurrentTasks"))
            assertEquals("artist-song", settings.getString("downloadFileNameFormat"))
            assertEquals(true, settings.getBoolean("downloadEmbedCover"))
            assertEquals(true, settings.getBoolean("downloadEmbedLyric"))
        } finally {
            MeloraSettings.downloadSkipSameName.value = oldValues[0] as Boolean
            MeloraSettings.downloadAutoSwitchSource.value = oldValues[1] as Boolean
            MeloraSettings.downloadConcurrentTasks.value = oldValues[2] as Int
            MeloraSettings.downloadFileNameFormat.value = oldValues[3] as String
            MeloraSettings.downloadEmbedCover.value = oldValues[4] as Boolean
            MeloraSettings.downloadEmbedLyric.value = oldValues[5] as Boolean
        }
    }

    @Test
    fun localSettingsRoundTripRetainsOptionsAndOnlyRestoresAuthorizedDirectories() {
        val original = BackupSettings.collect()
        val originalFolders = MeloraSettings.localFolders.value.toSet()
        val granted = "content://documents/tree/music"
        val revoked = "content://documents/tree/old-phone"
        try {
            MeloraSettings.updateLocalUseMediaStore(false)
            MeloraSettings.updateLocalExcludeShort(false)
            MeloraSettings.updateLocalExcludeSmall(false)
            MeloraSettings.updateLocalAutoFillInfo(true)
            MeloraSettings.updateLocalFolders(listOf(granted, revoked))
            val backup = BackupSettings.collect()
            MeloraSettings.updateLocalUseMediaStore(true)
            MeloraSettings.updateLocalAutoFillInfo(false)
            val notice = applyBackupSettings(backup, setOf(granted), emptySet())
            assertEquals(false, MeloraSettings.localUseMediaStore.value)
            assertEquals(false, MeloraSettings.localExcludeShort.value)
            assertEquals(false, MeloraSettings.localExcludeSmall.value)
            assertEquals(true, MeloraSettings.localAutoFillInfo.value)
            assertEquals(listOf(granted), MeloraSettings.localFolders.value)
            org.junit.Assert.assertTrue(notice.contains("本地音乐目录需要重新授权"))
            // 幂等恢复及旧备份缺字段不能清空当前本地目录/开关。
            applyBackupSettings(backup, setOf(granted), emptySet())
            applyBackupSettings(JSONObject(), setOf(granted), emptySet())
            assertEquals(listOf(granted), MeloraSettings.localFolders.value)
            assertEquals(true, MeloraSettings.localAutoFillInfo.value)
        } finally {
            applyBackupSettings(original, originalFolders, setOf(original.getString("downloadPath")))
        }
    }

}

private fun applyBackupSettings(node: JSONObject, readable: Set<String>, writable: Set<String>): String {
    val (values, notice) = BackupSettings.prepare(node, readable, writable)
    BackupSettings.apply(values)
    return notice
}
