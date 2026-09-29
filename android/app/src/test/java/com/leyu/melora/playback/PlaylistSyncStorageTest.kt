package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistSyncStorageTest {
    private val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))

    @Test fun sourceAndBaselineSurviveLibraryAndBackupRoundTripWhileLegacyStaysUnbound() = isolated {
        val linked = JSONObject().put("id", "linked").put("name", "手改名")
            .put("songs", JSONArray().put(song("a").raw))
            .put("importSource", JSONObject().put("source", link.source).put("value", link.value))
            .put("lastSyncedUids", JSONArray().put("wy_a").put("wy_removedLocally"))
        val legacy = JSONObject().put("id", "old").put("name", "旧歌单").put("songs", JSONArray())
        UserLibrary.replaceFromBackup(JSONObject().put("playlists", JSONArray().put(linked).put(legacy)).toString())
        val first = UserLibrary.playlists.value.first()
        assertEquals(link, first.importSource)
        assertEquals(setOf("wy_a", "wy_removedLocally"), first.lastSyncedUids)
        assertNull(UserLibrary.playlists.value.last().importSource)
        assertNull(UserLibrary.playlists.value.last().lastSyncedUids)
        val backup = parseBackupDocument(JSONObject().put("library", UserLibrary.exportSnapshot()).toString())
        UserLibrary.replaceFromBackup(backup.library!!)
        assertEquals(link, UserLibrary.playlists.value.first().importSource)
        assertEquals(first.lastSyncedUids, UserLibrary.playlists.value.first().lastSyncedUids)
    }

    @Test fun importedSourceComesFromLoadedResultAndStoresOnlyKnownRemoteUids() = isolated { file ->
        val loaded = kotlinx.coroutines.runBlocking {
            PlaylistImporter.read(link) { page ->
                if (page == 2) error("网络中断")
                SongPage(listOf(song("a"), song("a")), 3, 1, 2)
            }
        }
        assertEquals(link, loaded.importSource)
        assertNotNull(loaded.warning)
        val created = UserLibrary.createImportedPlaylist("自定名称", loaded)
        assertEquals(link, created.importSource)
        assertEquals(setOf("wy_a"), created.lastSyncedUids)
        assertEquals(listOf("wy_a"), created.songs.map { it.uid })
        UserLibrary.replaceFromBackup(file.readText())
        assertEquals(created.id, UserLibrary.playlists.value.single().id)
        assertEquals(link, UserLibrary.playlists.value.single().importSource)
    }

    @Test fun repeatedImportIsRejectedWithoutAnyWriteOrChangesToOriginal() = isolated { file ->
        val first = UserLibrary.createImportedPlaylist("手改名", remote("a"))
        UserLibrary.addToPlaylist(first.id, song("local"))
        val current = UserLibrary.playlists.value.single()
        val primary = file.readBytes()
        val backup = File(file.parentFile, "${file.name}.bak").readBytes()
        val alias = remote("b").copy(importSource = PlaylistImportLink.parse("https://music.163.com/playlist/123?from=other"))
        val error = assertThrows(PlaylistAlreadyImportedException::class.java) {
            UserLibrary.createImportedPlaylist("另一个名称", alias)
        }
        assertEquals(listOf(current), error.matches)
        assertSame(current, UserLibrary.playlists.value.single())
        assertEquals(link, current.importSource)
        assertArrayEquals(primary, file.readBytes())
        assertArrayEquals(backup, File(file.parentFile, "${file.name}.bak").readBytes())
    }

    @Test fun explicitCopyIsAllowedAndEveryExistingCopyIsReturnedAfterRestore() = isolated { file ->
        val original = UserLibrary.createImportedPlaylist("本地名称", remote("a"))
        val copy = UserLibrary.createImportedPlaylist("副本名称", remote("b"), allowCopy = true)
        assertNotEquals(original.id, copy.id)
        UserLibrary.replaceFromBackup(parseBackupDocument(JSONObject().put("library", file.readText()).toString()).library!!)
        val before = UserLibrary.exportSnapshot()
        val error = assertThrows(PlaylistAlreadyImportedException::class.java) {
            UserLibrary.createImportedPlaylist("不会保存", remote("c"))
        }
        assertEquals(listOf(original.id, copy.id), error.matches.map { it.id })
        assertEquals(listOf("本地名称", "副本名称"), error.matches.map { it.name })
        assertEquals(before, UserLibrary.exportSnapshot())
        assertEquals(link, error.matches.first().importSource)
        assertEquals(original.lastSyncedUids, error.matches.first().lastSyncedUids)
    }

    @Test fun simultaneousImportPublishesOnlyOnePlaylistAndNeverWritesRejectedAttempt() = isolated { file ->
        val initial = file.readBytes()
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { n -> pool.submit(java.util.concurrent.Callable {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { UserLibrary.createImportedPlaylist("name$n", remote("$n")) }
            }) }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is PlaylistAlreadyImportedException })
            assertEquals(1, UserLibrary.playlists.value.size)
            assertEquals(1, JSONObject(file.readText()).getJSONArray("playlists").length())
            // Only the winning creation backed up the original snapshot; a loser must not write.
            assertArrayEquals(initial, File(file.parentFile, "${file.name}.bak").readBytes())
        } finally { pool.shutdownNow() }
    }

    @Test fun sameNameSongsAndUnknownSourceNeverGuessAnIdentity() = isolated {
        UserLibrary.createPlaylist("same", listOf(song("a")))
        UserLibrary.createImportedPlaylist("same", remote("a"))
        UserLibrary.createImportedPlaylist("same", remote("a").copy(importSource = PlaylistImportLink.parse("https://music.163.com/playlist?id=456")))
        UserLibrary.createImportedPlaylist("same", remote("a").copy(importSource = PlaylistImportLink.parse("https://y.qq.com/playlist/123")))
        val short = remote("a").copy(importSource = PlaylistImportLink.parse("https://163cn.tv/AbCd12"))
        UserLibrary.createImportedPlaylist("same", short)
        assertThrows(PlaylistAlreadyImportedException::class.java) { UserLibrary.createImportedPlaylist("different", short) }
        UserLibrary.createImportedPlaylist("same", short.copy(importSource = PlaylistImportLink.parse("https://163cn.tv/Other12")))
        assertEquals(6, UserLibrary.playlists.value.size)
    }

    @Test fun failedCopyWriteDoesNotPublishOrDamageExistingPlaylistAndCanRetry() = isolated { file ->
        val original = UserLibrary.createImportedPlaylist("original", remote("a"))
        val primary = file.readBytes()
        val backup = File(file.parentFile, "${file.name}.bak").readBytes()
        val temp = File(file.parentFile, "${file.name}.tmp").apply { mkdir() }
        try {
            assertThrows(java.io.IOException::class.java) { UserLibrary.createImportedPlaylist("copy", remote("b"), allowCopy = true) }
            assertSame(original, UserLibrary.playlists.value.single())
            assertArrayEquals(primary, file.readBytes())
            assertArrayEquals(backup, File(file.parentFile, "${file.name}.bak").readBytes())
        } finally { temp.deleteRecursively() }
        UserLibrary.createImportedPlaylist("copy", remote("b"), allowCopy = true)
        assertEquals(2, UserLibrary.playlists.value.size)
    }

    @Test fun deletionBeforeImportAllowsNewCreationWithoutResurrectingTheOldTarget() = isolated {
        val original = UserLibrary.createImportedPlaylist("original", remote("a"))
        UserLibrary.deletePlaylist(original.id)
        val saved = UserLibrary.createImportedPlaylist("new", remote("b"))
        assertEquals(listOf(saved), UserLibrary.playlists.value)
        assertEquals("new", saved.name)
        assertEquals(listOf("wy_b"), saved.songs.map { it.uid })
    }

    @Test fun cancelledImportDoesNotCreateAndPublicationCancellationStillReportsSuccess() = isolated { file ->
        kotlinx.coroutines.runBlocking {
            var reportedSuccess = false
            var reportedCancellation = false
            lateinit var task: kotlinx.coroutines.Job
            val cancelled = kotlinx.coroutines.Job().apply { cancel() }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                kotlinx.coroutines.runBlocking(cancelled) { UserLibrary.saveImportedPlaylist("cancelled", remote("a")) }
            }
            assertTrue(UserLibrary.playlists.value.isEmpty())
            val observer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                UserLibrary.playlists.collect { lists -> if (lists.isNotEmpty()) task.cancel() }
            }
            task = launch {
                try {
                    val saved = UserLibrary.saveImportedPlaylist("saved", remote("a"))
                    reportedSuccess = saved.name == "saved"
                } catch (_: kotlinx.coroutines.CancellationException) { reportedCancellation = true }
            }
            task.join(); observer.cancel()
            assertTrue(reportedSuccess)
            assertFalse(reportedCancellation)
            assertEquals(1, JSONObject(file.readText()).getJSONArray("playlists").length())
        }
    }

    private fun remote(vararg ids: String) = PlaylistImportResult("网易云音乐", "源名称", null,
        ids.map(::song), ids.size, 0, null, link)

    @Test fun overlappingPagesCannotProduceAConfirmableDeletionOrChangeDiskAndMemory() = isolated { file ->
        val before = UserLibrary.createImportedPlaylist("原歌单", remote("a", "b", "c", "d"))
        val primary = file.readBytes()
        val backupFile = File(file.parentFile, "${file.name}.bak")
        val backup = backupFile.readBytes()
        val snapshot = UserLibrary.exportSnapshot()
        val requested = mutableListOf<Int>()
        val controller = com.leyu.melora.ui.my.PlaylistUpdateController(
            currentPlaylist = { UserLibrary.playlists.value.single() },
            readPlaylist = { text, progress ->
                PlaylistImporter.read(PlaylistImportLink.parse(text), progress) { page ->
                    requested += page
                    SongPage((if (page == 1) listOf("a", "b") else listOf("b", "c")).map(::song),
                        total = 4, page = page, allPage = 2, pageSize = 2)
                }
            },
        )
        kotlinx.coroutines.runBlocking {
            controller.read("")
            assertEquals(listOf(1, 2), requested)
            assertNull("Overlapping pages must not preview removal of managed d", controller.state.value.preview)
            assertTrue(controller.state.value.error.orEmpty().contains("未获取完整"))
            assertNull(controller.confirm())
        }
        assertSame(before, UserLibrary.playlists.value.single())
        assertEquals(snapshot, UserLibrary.exportSnapshot())
        assertArrayEquals(primary, file.readBytes())
        assertArrayEquals(backup, backupFile.readBytes())
    }

    @Test fun overlappingPagesRemainExplicitlySaveableAsAnInitialDeduplicatedImport() = isolated { file ->
        val bytes = file.readBytes()
        val requested = mutableListOf<Int>()
        val loaded = kotlinx.coroutines.runBlocking {
            PlaylistImporter.read(link) { n ->
                requested += n
                val ids = when (n) {
                    1 -> listOf("a", "b")
                    2 -> listOf("b", "c")
                    else -> listOf("d", "e")
                }
                SongPage(ids.map(::song), total = 6, page = n, allPage = 3, pageSize = 2)
            }
        }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(listOf("wy_a", "wy_b", "wy_c", "wy_d", "wy_e"), loaded.songs.map { it.uid })
        assertEquals(1, loaded.duplicates)
        assertTrue(loaded.warning.orEmpty().contains("跨页重复"))
        assertTrue(UserLibrary.playlists.value.isEmpty())
        assertArrayEquals(bytes, file.readBytes())
        val saved = UserLibrary.createImportedPlaylist("明确保存去重结果", loaded)
        assertEquals(loaded.songs.map { it.uid }, saved.songs.map { it.uid })
        UserLibrary.replaceFromBackup(file.readText())
        assertEquals(saved.songs.map { it.uid }, UserLibrary.playlists.value.single().songs.map { it.uid })
    }

    @Test fun confirmReplacesExactlyOnePlaylistAfterPreviewAndPersistsBeforePublishing() = isolated { file ->
        val target = UserLibrary.createImportedPlaylist("手改名", remote("a", "b"))
        val other = UserLibrary.createPlaylist("另一歌单", listOf(song("untouched")))
        UserLibrary.addToPlaylist(target.id, song("local"))
        val before = UserLibrary.playlists.value.first()
        val bytes = file.readText()
        val preview = previewPlaylistSync(before, remote("b", "c"))
        assertEquals(bytes, file.readText())
        assertSame(before, UserLibrary.playlists.value.first())
        val saved = kotlinx.coroutines.runBlocking { UserLibrary.commitPlaylistSync(preview) }
        assertEquals(target.id, saved.id)
        assertEquals("手改名", saved.name)
        assertEquals(listOf("wy_b", "wy_c", "wy_local"), saved.songs.map { it.uid })
        assertEquals(listOf(target.id, other.id), UserLibrary.playlists.value.map { it.id })
        UserLibrary.replaceFromBackup(file.readText())
        assertEquals(saved.songs.map { it.uid }, UserLibrary.playlists.value.first().songs.map { it.uid })
        assertEquals(setOf("wy_b", "wy_c"), UserLibrary.playlists.value.first().lastSyncedUids)
    }

    @Test fun failedWriteKeepsPrimaryBackupAndPublishedStateAndCanRetry() = isolated { file ->
        val before = UserLibrary.createImportedPlaylist("原歌单", remote("a"))
        val preview = previewPlaylistSync(before, remote("b"))
        val primary = file.readBytes()
        val backup = File(file.parentFile, "${file.name}.bak").readBytes()
        val temp = File(file.parentFile, "${file.name}.tmp").apply { mkdir() }
        try {
            assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { UserLibrary.commitPlaylistSync(preview) } }
            assertSame(before, UserLibrary.playlists.value.single())
            assertArrayEquals(primary, file.readBytes())
            assertArrayEquals(backup, File(file.parentFile, "${file.name}.bak").readBytes())
        } finally { temp.deleteRecursively() }
        kotlinx.coroutines.runBlocking { UserLibrary.commitPlaylistSync(preview) }
        assertEquals(listOf("wy_b"), UserLibrary.playlists.value.single().songs.map { it.uid })
    }

    @Test fun concurrentEditDeletionAndRestoreRejectStalePreviewWithoutWriting() = isolated { file ->
        val edits: List<(String) -> Unit> = listOf(
            { UserLibrary.renamePlaylist(it, "并发改名") },
            { UserLibrary.addToPlaylist(it, song("concurrent")) },
            { UserLibrary.removeFromPlaylist(it, "wy_a") },
            { UserLibrary.deletePlaylist(it) },
            { UserLibrary.replaceFromBackup(UserLibrary.exportSnapshot()) },
        )
        edits.forEach { edit ->
            UserLibrary.replaceFromBackup("{}")
            val before = UserLibrary.createImportedPlaylist("target", remote("a"))
            val preview = previewPlaylistSync(before, remote("b"))
            edit(before.id)
            val snapshot = UserLibrary.exportSnapshot()
            val bytes = file.readBytes()
            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { UserLibrary.commitPlaylistSync(preview) } }
            assertEquals(snapshot, UserLibrary.exportSnapshot())
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun cancelledConfirmationDoesNotWrite() = isolated { file ->
        val before = UserLibrary.createImportedPlaylist("target", remote("a"))
        val preview = previewPlaylistSync(before, remote("b"))
        val bytes = file.readBytes()
        val job = kotlinx.coroutines.Job().apply { cancel() }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking(job) { UserLibrary.commitPlaylistSync(preview) }
        }
        assertSame(before, UserLibrary.playlists.value.single())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun malformedSyncMetadataIsRejectedByBackupAndCannotBecomeATrustedLocalSource() {
        val validSource = JSONObject().put("source", "wy").put("value", link.value)
        val variants = listOf(
            JSONObject().put("importSource", JSONObject().put("source", "tx").put("value", link.value)),
            JSONObject().put("importSource", JSONObject().put("source", "wy").put("value", "https://evil.test/playlist/123")),
            JSONObject().put("importSource", "not-an-object"),
            JSONObject().put("importSource", validSource).put("lastSyncedUids", JSONArray().put("wy_a").put(42)),
            JSONObject().put("importSource", validSource).put("lastSyncedUids", "wrong-type"),
            JSONObject().put("lastSyncedUids", JSONArray().put("wy_a")),
        )
        variants.forEach { node ->
            node.put("id", "x").put("name", "x").put("songs", JSONArray())
            assertThrows(IllegalArgumentException::class.java) {
                parseBackupDocument(JSONObject().put("library", JSONObject().put("playlists", JSONArray().put(node))).toString())
            }
            val degraded = playlistFromJson(node)
            assertNull(degraded.importSource)
            assertNull(degraded.lastSyncedUids)
        }
    }

    @Test fun sourceResultMutationAfterPreviewCannotChangeConfirmedContent() = isolated {
        val before = UserLibrary.createImportedPlaylist("target", remote("a"))
        val loaded = remote("b")
        val preview = previewPlaylistSync(before, loaded)
        loaded.songs.single().raw.put("songmid", "unexpected")
        val saved = kotlinx.coroutines.runBlocking { UserLibrary.commitPlaylistSync(preview) }
        assertEquals(listOf("wy_b"), saved.songs.map { it.uid })
    }

    @Test fun inPlaceMetadataEditAfterPreviewIsAConflict() = isolated { file ->
        val before = UserLibrary.createImportedPlaylist("target", remote("a"))
        val preview = previewPlaylistSync(before, remote("b"))
        before.songs.single().raw.put("name", "并发元数据修改")
        val bytes = file.readBytes()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { UserLibrary.commitPlaylistSync(preview) } }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("并发元数据修改", UserLibrary.playlists.value.single().songs.single().name)
    }

    @Test fun exportCountsRemoteBaselineEntriesInTheSameBackupLimitAsRestore() = isolated<Unit> {
        val many = (1..9_000).map { song(it.toString()) }
        UserLibrary.playlists.value = (1..3).map { index ->
            UserLibrary.UserPlaylist("large-$index", "large", many, link, many.mapTo(linkedSetOf()) { it.uid })
        }
        assertThrows(IllegalArgumentException::class.java) { UserLibrary.backupSnapshot() }
    }

    @Test fun cancellationAtPublicationDoesNotMisreportSuccessfulCommitAsCancelled() = isolated { file ->
        kotlinx.coroutines.runBlocking {
            val before = UserLibrary.createImportedPlaylist("target", remote("a"))
            val preview = previewPlaylistSync(before, remote("b"))
            var reportedSuccess = false
            var reportedCancellation = false
            lateinit var task: kotlinx.coroutines.Job
            val observer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                UserLibrary.playlists.collect { lists ->
                    if (lists.singleOrNull()?.songs?.singleOrNull()?.uid == "wy_b") task.cancel()
                }
            }
            task = launch {
                try {
                    val saved = UserLibrary.commitPlaylistSync(preview)
                    reportedSuccess = saved.songs.single().uid == "wy_b"
                } catch (_: kotlinx.coroutines.CancellationException) { reportedCancellation = true }
            }
            task.join(); observer.cancel()
            assertTrue(reportedSuccess)
            assertFalse(reportedCancellation)
            assertEquals("wy_b", playlistFromJson(JSONObject(file.readText()).getJSONArray("playlists").getJSONObject(0)).songs.single().uid)
        }
    }

    @Test fun failedRenameKeepsPublishedPlaylistAndDiskUnchanged() = isolated { file ->
        val playlist = UserLibrary.createPlaylist("原名称", listOf(song("a")))
        val primary = file.readBytes()
        val backup = File(file.parentFile, "${file.name}.bak").readBytes()
        val blocker = File(file.parentFile, "${file.name}.tmp").apply { check(mkdir()) }
        try {
            assertThrows(java.io.IOException::class.java) { UserLibrary.renamePlaylist(playlist.id, "未保存的名称") }
            assertSame(playlist, UserLibrary.playlists.value.single())
            assertArrayEquals(primary, file.readBytes())
            assertArrayEquals(backup, File(file.parentFile, "${file.name}.bak").readBytes())
        } finally { blocker.delete() }
    }

    @Test fun failedFavoriteWriteKeepsPublishedSongsAndDerivedIdsUnchanged() = isolated { file ->
        UserLibrary.setFavorites(listOf(song("a")), true)
        val before = UserLibrary.exportSnapshot()
        val primary = file.readBytes()
        val blocker = File(file.parentFile, "${file.name}.tmp").apply { check(mkdir()) }
        try {
            assertThrows(java.io.IOException::class.java) { UserLibrary.setFavorites(listOf(song("b")), true) }
            assertEquals(before, UserLibrary.exportSnapshot())
            assertEquals(setOf("wy_a"), UserLibrary.favoriteUids.value)
            assertArrayEquals(primary, file.readBytes())
        } finally { blocker.delete() }
    }

    @Test fun failedOrdinaryMutationsNeverEmitTransientStateAndCanRetry() = isolated { file ->
        kotlinx.coroutines.runBlocking {
            val playlist = UserLibrary.createPlaylist("列表", listOf(song("a")))
            UserLibrary.markPlayed(song("a"))
            UserLibrary.addSearchKeyword("旧搜索")
            val before = UserLibrary.exportSnapshot()
            val bytes = file.readBytes()
            val emissions = mutableListOf<List<UserLibrary.UserPlaylist>>()
            val observer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                UserLibrary.playlists.collect { emissions += it }
            }
            val blocker = File(file.parentFile, "${file.name}.tmp").apply { check(mkdir()) }
            try {
                val writes: List<() -> Unit> = listOf(
                    { UserLibrary.deletePlaylist(playlist.id) },
                    { UserLibrary.addToPlaylist(playlist.id, song("b")) },
                    { UserLibrary.removeFromPlaylist(playlist.id, "wy_a") },
                    { UserLibrary.markPlayed(song("b")) },
                    { UserLibrary.clearRecents() },
                    { UserLibrary.addSearchKeyword("未保存搜索") },
                    { UserLibrary.clearSearchHistory() },
                )
                writes.forEach { write ->
                    blocker.mkdirs() // 原子写入的 finally 会清理本次临时路径。
                    assertThrows(java.io.IOException::class.java) { write() }
                    assertEquals(before, UserLibrary.exportSnapshot())
                    assertArrayEquals(bytes, file.readBytes())
                }
                assertEquals(1, emissions.size)
            } finally { blocker.delete(); observer.cancel() }
            UserLibrary.addToPlaylist(playlist.id, song("b"))
            UserLibrary.addToPlaylist(playlist.id, song("b"))
            val committed = UserLibrary.exportSnapshot()
            UserLibrary.replaceFromBackup(committed)
            assertEquals(committed, UserLibrary.exportSnapshot())
            assertEquals(listOf("wy_a", "wy_b"), UserLibrary.playlists.value.single().songs.map { it.uid })
        }
    }

    private fun <T> isolated(block: (File) -> T): T {
        val field = UserLibrary::class.java.getDeclaredField("file").apply { isAccessible = true }
        val previous = field.get(UserLibrary)
        val snapshot = UserLibrary.exportSnapshot()
        val dir = Files.createTempDirectory("melora-playlist-sync").toFile()
        val file = File(dir, "user-library.json")
        field.set(UserLibrary, file)
        return try { UserLibrary.replaceFromBackup("{}"); block(file) } finally {
            try { UserLibrary.replaceFromBackup(snapshot) } finally { field.set(UserLibrary, previous); dir.deleteRecursively() }
        }
    }
}
