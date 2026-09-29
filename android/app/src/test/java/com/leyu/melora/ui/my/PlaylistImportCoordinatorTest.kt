package com.leyu.melora.ui.my

import com.leyu.melora.playback.*
import com.leyu.melora.playback.sdk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistImportCoordinatorTest {
    private val source = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123&original=1")
    private val alias = PlaylistImportLink.parse("https://music.163.com/playlist/123?new=2")
    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))
    private val original = UserLibrary.UserPlaylist("first", "本地名称", listOf(song("a")), source, setOf("wy_a"))
    private val loaded = PlaylistImportResult("网易云音乐", "远端名称", null, listOf(song("b")), 1, 0, null, alias)

    @Test fun kugouAmbiguousOrDifferentlyCasedQueriesNeverOfferTheWrongUpdateTarget() {
        val specialSource = PlaylistImportLink.parse("https://www.kugou.com/yy/special/single/123.html")
        val globalSource = PlaylistImportLink.parse("https://www.kugou.com/share?global_collection_id=456")
        val special = original.copy(id = "special", importSource = specialSource)
        val global = original.copy(id = "global", importSource = globalSource)
        val coordinator = PlaylistImportCoordinator({ listOf(special, global) }) { _, _, _ -> error("not saving") }
        val encoded = loaded.copy(importSource = PlaylistImportLink.parse("${specialSource.value}?global_collection_id=%34%35%36"))
        assertTrue(coordinator.choices(encoded, null).matches.isEmpty())
        assertNull(coordinator.choices(encoded, special.id).target)
        assertThrows(IllegalStateException::class.java) { coordinator.updateTarget(encoded, special.id) }
        val uppercase = loaded.copy(importSource = PlaylistImportLink.parse("${specialSource.value}?GLOBAL_COLLECTION_ID=456"))
        assertEquals(listOf(special), coordinator.choices(uppercase, null).matches)
        assertSame(special, coordinator.updateTarget(uppercase, special.id))
        assertThrows(IllegalStateException::class.java) { coordinator.updateTarget(uppercase, global.id) }
        val plain = loaded.copy(importSource = PlaylistImportLink.parse("${specialSource.value}?global_collection_id=456"))
        assertEquals(listOf(global), coordinator.choices(plain, null).matches)
        assertSame(global, coordinator.updateTarget(plain, global.id))
        // The exact encoded source can still find its own prior import, never a guessed alias.
        val exact = original.copy(id = "encoded", importSource = encoded.importSource)
        assertEquals(listOf(exact), matchingImportedPlaylists(listOf(special, global, exact), encoded.importSource!!))
    }

    @Test fun multipleCopiesRequireAnExplicitTargetAndDeletionNeverFallsBackToAnotherCopy() {
        val second = original.copy(id = "second", name = "我的副本", songs = listOf(song("a"), song("local")))
        var lists = listOf(original, second)
        val coordinator = PlaylistImportCoordinator({ lists }) { _, _, _ -> error("not saving") }
        assertNull(coordinator.choices(loaded, null).target)
        assertEquals(second, coordinator.choices(loaded, second.id).target)
        lists = listOf(original)
        assertThrows(IllegalStateException::class.java) { coordinator.updateTarget(loaded, second.id) }
        assertNull(coordinator.choices(loaded, second.id).target)
        assertSame(original, coordinator.updateTarget(loaded, original.id))
    }

    @Test fun saveRaceReturnsStructuredDuplicatesAndOnlyExplicitCopyPassesTheFlag() = runBlocking {
        val flags = mutableListOf<Boolean>()
        val coordinator = PlaylistImportCoordinator({ emptyList() }) { name, result, copy ->
            flags += copy
            assertEquals("用户编辑名", name)
            assertSame(loaded, result)
            if (!copy) throw PlaylistAlreadyImportedException(listOf(original))
            original.copy(id = "new", name = name, importSource = result.importSource, songs = result.songs)
        }
        assertTrue(coordinator.choices(loaded, null).matches.isEmpty())
        val duplicate = coordinator.save("用户编辑名", loaded) as PlaylistImportSave.Duplicate
        assertEquals(listOf(original), duplicate.matches)
        val saved = coordinator.save("用户编辑名", loaded, allowCopy = true) as PlaylistImportSave.Created
        assertEquals("new", saved.playlist.id)
        assertEquals(listOf(false, true), flags)
    }

    @Test fun diskFailureAndCancellationAreNotReportedAsCreationOrDuplicate() {
        val disk = PlaylistImportCoordinator({ emptyList() }) { _, _, _ -> throw java.io.IOException("full") }
        assertThrows(java.io.IOException::class.java) { runBlocking { disk.save("name", loaded) } }
        var saves = 0
        val cancelled = PlaylistImportCoordinator({ emptyList() }) { _, _, _ -> saves++; original }
        val job = kotlinx.coroutines.Job().apply { cancel() }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking(job) { cancelled.save("name", loaded) } }
        assertEquals(0, saves)
    }

    @Test fun duplicatePreviewChoosesExistingWithoutSavingAndUpdateRereadsOriginalSource() = runBlocking {
        var saves = 0
        var commits = 0
        val coordinator = PlaylistImportCoordinator({ listOf(original) }) { _, _, _ -> saves++; error("must not save") }
        val choice = coordinator.choices(loaded, null)
        assertEquals(listOf(original), choice.matches)
        assertEquals(original, choice.target)
        val target = coordinator.updateTarget(loaded, original.id)
        assertSame(original, target)
        val controller = PlaylistUpdateController({ target }, { text, _ ->
            assertEquals(source.value, text)
            loaded.copy(importSource = source)
        }, { preview -> commits++; preview.updated })
        controller.read("")
        assertEquals(0, saves)
        assertEquals(0, commits)
        assertNotNull(controller.state.value.preview)
        val updated = controller.confirm()!!
        assertEquals(original.id, updated.id)
        assertEquals(original.name, updated.name)
        assertEquals(source, updated.importSource)
        assertEquals(1, commits)
    }
}
