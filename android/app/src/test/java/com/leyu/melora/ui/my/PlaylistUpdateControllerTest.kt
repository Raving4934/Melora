package com.leyu.melora.ui.my

import com.leyu.melora.playback.*
import com.leyu.melora.playback.sdk.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistUpdateControllerTest {
    private val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))
    private val local get() = UserLibrary.UserPlaylist("local", "本地名", listOf(song("a")), link, setOf("wy_a"))
    private val remote get() = PlaylistImportResult("网易云音乐", "远端名", null, listOf(song("b")), 1, 0, null, link)

    @Test fun partialFailureAndFailedRetryCannotLeaveAnOldConfirmablePreview() = runBlocking {
        var result = remote
        var fail = false
        var commits = 0
        val controller = PlaylistUpdateController({ local }, { _, _ -> if (fail) error("网络失败") else result }, { commits++; it.updated })
        controller.read("")
        assertNotNull(controller.state.value.preview)
        result = remote.copy(warning = "第2页失败")
        controller.read("")
        assertNull(controller.state.value.preview)
        assertTrue(controller.state.value.error!!.contains("未获取完整"))
        assertNull(controller.confirm())
        fail = true
        controller.read("")
        assertNull(controller.state.value.preview)
        assertTrue(controller.state.value.error!!.contains("网络失败"))
        assertFalse(controller.state.value.busy)
        assertEquals(0, commits)
        fail = false; result = remote
        controller.read("")
        assertNotNull(controller.state.value.preview)
        assertNull(controller.state.value.error)
    }

    @Test fun cancellationEvenFromNonCooperativeReaderNeverPublishesPreviewOrError() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = PlaylistUpdateController({ local }, { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            remote
        })
        val task = launch { controller.read("") }
        entered.await(); task.cancel(); release.complete(Unit); task.join()
        assertNull(controller.state.value.preview)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.busy)
    }

    @Test fun busyReadAndConfirmationAreNotDuplicatedAndDeletionIsReported() = runBlocking {
        var reads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var current: UserLibrary.UserPlaylist? = local
        val controller = PlaylistUpdateController({ current }, { _, _ -> reads++; entered.complete(Unit); release.await(); remote })
        val task = launch { controller.read("") }
        entered.await()
        controller.read("")
        assertNull(controller.confirm())
        assertEquals(1, reads)
        release.complete(Unit); task.join()
        current = null
        controller.read("")
        assertNull(controller.state.value.preview)
        assertTrue(controller.state.value.error!!.contains("删除"))
    }

    @Test fun saveFailureKeepsPreviewForRetryAndConflictRequiresFreshRead() = runBlocking {
        var failure: Exception? = java.io.IOException("disk full")
        val controller = PlaylistUpdateController({ local }, { _, _ -> remote }, { failure?.let { throw it }; it.updated })
        controller.read("")
        assertNull(controller.confirm())
        assertNotNull(controller.state.value.preview)
        assertTrue(controller.state.value.error!!.contains("disk full"))
        failure = IllegalStateException("歌单已被修改")
        assertNull(controller.confirm())
        assertNull(controller.state.value.preview)
        assertFalse(controller.state.value.busy)
        failure = null
        controller.read("")
        assertNotNull(controller.confirm())
    }

    @Test fun bindingUsesReturnedValidatedSourceAndEditingLinkInvalidatesPreview() = runBlocking {
        val controller = PlaylistUpdateController({ local.copy(importSource = null, lastSyncedUids = null) }, { _, _ -> remote })
        controller.read("https://music.163.com/#/playlist?id=999")
        assertNull(controller.state.value.preview)
        assertNotNull(controller.state.value.error)
        controller.read(link.value)
        assertTrue(controller.state.value.preview!!.firstBinding)
        controller.editLink()
        assertNull(controller.state.value.preview)
        assertNull(controller.state.value.error)
    }

    @Test fun unchangedReadCannotBeConfirmedOrWritten() = runBlocking {
        var commits = 0
        val controller = PlaylistUpdateController({ local }, { _, _ -> remote.copy(songs = local.songs) }, { commits++; it.updated })
        controller.read("")
        assertFalse(controller.state.value.preview!!.hasChanges)
        assertNull(controller.confirm())
        assertEquals(0, commits)
    }

    @Test fun readingProducesPreviewAndOnlyExplicitConfirmCallsCommit() = runBlocking {
        var commits = 0
        var requested: String? = null
        val controller = PlaylistUpdateController(currentPlaylist = { local },
            readPlaylist = { text, progress ->
                requested = text
                progress(PlaylistImportProgress(1, 1, 1))
                remote
            }, commit = { commits++; it.updated })
        controller.read("ignored input for bound playlist")
        assertEquals(link.value, requested)
        assertEquals(0, commits)
        assertEquals(listOf("wy_b"), controller.state.value.preview!!.added.map { it.uid })
        assertFalse(controller.state.value.loading)
        val saved = controller.confirm()
        assertEquals("local", saved!!.id)
        assertEquals("本地名", saved.name)
        assertEquals(1, commits)
    }
}
