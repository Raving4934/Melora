package com.leyu.melora.playback

import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalQueueCleanupTest {
    @Test fun deletionUsesLocalIdentityNotTitleOrOnlineSongId() {
        val deleted = DeletedLocalFiles(setOf("a"), setOf("file:///fixture/128.mp3"))
        assertTrue(deleted.matches(local("a")))
        assertTrue(deleted.matches(local("alias", "file:///fixture/128.mp3")))
        assertFalse(deleted.matches(local("hr", "file:///fixture/hr.flac")))
        assertFalse(deleted.matches(UiTrack("kw_a", "same title", "artist", "album", "kw",
            raw = JSONObject().put("localUri", "file:///fixture/128.mp3"))))
        assertTrue(deleted.matches(UiTrack("file:///fixture/128.mp3", "direct", "", "")))
    }

    @Test fun removeAllAliasesAndRepeatedOccurrencesButKeepOnlineCounterpart() {
        val online = UiTrack("kw_a", "same title", "artist", "album", "kw")
        val queue = Queue(listOf(local("a"), online, local("a"), local("other")))
        queue.player.removeDeletedLocalItems(DeletedLocalFiles(setOf("a"), emptySet()), queue.tracks::get)
        assertEquals(listOf("kw_a", "local_other"), queue.items.map { it.mediaId })
        assertEquals(listOf(2 to 3, 0 to 1), queue.removed)
    }

    @Test fun batchRemovalGroupsContiguousRangesInReverseOrderWithoutSeeking() {
        val queue = Queue((0..6).map { local("$it") })
        queue.player.removeDeletedLocalItems(DeletedLocalFiles(setOf("0", "1", "3", "4", "5"), emptySet()), queue.tracks::get)
        assertEquals(listOf(3 to 6, 0 to 2), queue.removed)
        assertEquals(listOf("local_2", "local_6"), queue.items.map { it.mediaId })
        assertEquals(0, queue.pauses)
    }

    @Test fun emptyQueueIsPausedAndRepeatedRemovalIsSafe() {
        val queue = Queue(listOf(local("a")))
        val deleted = DeletedLocalFiles(setOf("a"), emptySet())
        queue.player.removeDeletedLocalItems(deleted, queue.tracks::get)
        queue.player.removeDeletedLocalItems(deleted, queue.tracks::get)
        assertTrue(queue.items.isEmpty())
        assertEquals(listOf(0 to 1), queue.removed)
        assertEquals(2, queue.pauses)
    }

    @Test fun savedQueueKeepsCurrentTrackWhenDeletingEarlierItems() {
        val prefs = saved(listOf(local("a"), local("b"), local("c")), index = 2)
        assertTrue(pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(setOf("a"), emptySet())))
        assertEquals(listOf("local_b", "local_c"), prefs.uids())
        assertEquals(1, prefs.values["index"])
        assertEquals("kept-origin", prefs.values["queueId"])
    }

    @Test fun savedCurrentDeletionSelectsFollowingSurvivorNotAnotherDeletedTrack() {
        val prefs = saved((0..4).map { local("$it") }, index = 1)
        pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(setOf("1", "2"), emptySet()))
        assertEquals(listOf("local_0", "local_3", "local_4"), prefs.uids())
        assertEquals(1, prefs.values["index"])
    }

    @Test fun savedLastDeletionFallsBackToFirstSurvivor() {
        val prefs = saved((0..2).map { local("$it") }, index = 2)
        pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(setOf("2"), emptySet()))
        assertEquals(0, prefs.values["index"])
    }

    @Test fun savedQueueUsesUriEvenWhenLocalIdChanged() {
        val prefs = saved(listOf(local("old", "file:///fixture/a.mp3"), local("b")), 0)
        pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(emptySet(), setOf("file:///fixture/a.mp3")))
        assertEquals(listOf("local_b"), prefs.uids())
    }

    @Test fun coldDeletionClearsEntireSavedQueueWhenNothingSurvives() {
        val prefs = saved(listOf(local("a")), 0)
        pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(setOf("a"), emptySet()))
        assertTrue(prefs.values.isEmpty())
        assertFalse(pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(setOf("a"), emptySet())))
    }

    @Test fun unrelatedOrFailedDeletionDoesNotRewriteQueue() {
        val prefs = saved(listOf(local("a")), 0)
        val before = prefs.values.toMap()
        assertFalse(pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(emptySet(), emptySet())))
        assertFalse(pruneSavedLocalQueue(prefs.prefs, DeletedLocalFiles(setOf("other"), emptySet())))
        assertEquals(before, prefs.values)
    }

    @Test fun pendingQueueIsPrunedBeforeServiceConnects() {
        val pending = PendingPlaybackSelection(listOf(local("a"), local("b"), local("c")), index = 1, queueId = "fixture")
        val kept = pending.without(DeletedLocalFiles(setOf("b"), emptySet()))
        assertEquals(listOf("local_a", "local_c"), kept.tracks.map { it.uid })
        assertEquals(1, kept.index)
        assertEquals("fixture", kept.queueId)
    }

    @Test fun deletedPendingSingleBecomesAnExplicitEmptyIntentNotOldQueueAutoplay() {
        val pending = PendingPlaybackSelection(listOf(local("a")), insertSingle = true)
        val kept = pending.without(DeletedLocalFiles(setOf("a"), emptySet()))
        assertTrue(kept.tracks.isEmpty())
        assertTrue(kept.insertSingle)
    }

    @Test fun singleRepeatCurrentDeletionExplicitlyPositionsSurvivor() {
        val queue = Queue(listOf(local("a"), local("b")), Player.REPEAT_MODE_ONE)
        queue.player.removeDeletedLocalItems(DeletedLocalFiles(setOf("a"), emptySet()), queue.tracks::get)
        assertEquals(listOf(0), queue.seeks)
        assertEquals(0, queue.pauses)
    }

    @Test fun singleRepeatOtherDeletionDoesNotRestartCurrentTrack() {
        val queue = Queue(listOf(local("a"), local("b")), Player.REPEAT_MODE_ONE)
        queue.player.removeDeletedLocalItems(DeletedLocalFiles(setOf("b"), emptySet()), queue.tracks::get)
        assertTrue(queue.seeks.isEmpty())
    }

    private fun local(id: String, uri: String = "file:///fixture/$id.mp3") = UiTrack(
        "local_$id", "same title", "artist", "album", "local", raw = JSONObject().put("source", "local").put("songmid", id).put("localUri", uri),
    )

    private fun saved(tracks: List<UiTrack>, index: Int) = Preferences(mutableMapOf(
        "queue" to JSONArray().apply { tracks.forEach { put(JSONObject().put("uid", it.uid).put("source", it.source).put("raw", it.raw)) } }.toString(),
        "index" to index, "queueId" to "kept-origin",
    ))

    private class Queue(list: List<UiTrack>, val repeatMode: Int = Player.REPEAT_MODE_ALL) {
        val tracks = list.associateBy { it.uid }
        val items = list.map { MediaItem.Builder().setMediaId(it.uid).build() }.toMutableList()
        val removed = mutableListOf<Pair<Int, Int>>()
        var pauses = 0
        val seeks = mutableListOf<Int>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getRepeatMode" -> repeatMode
                "getCurrentMediaItem" -> items.firstOrNull()
                "getCurrentMediaItemIndex" -> 0
                "seekToDefaultPosition" -> { seeks += args!![0] as Int; null }
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "removeMediaItems" -> { val start = args!![0] as Int; val end = args[1] as Int; removed += start to end; items.subList(start, end).clear(); null }
                "pause" -> { pauses++; null }
                else -> error("Queue cleanup must not call ${method.name}")
            }
        } as Player
    }

    private class Preferences(val values: MutableMap<String, Any>) {
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args -> when (method.name) {
            "clear" -> { values.clear(); proxy }
            "putString", "putInt" -> { values[args!![0] as String] = args[1]!!; proxy }
            "apply" -> null
            else -> error(method.name)
        } } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args -> when (method.name) {
            "getString" -> values[args!![0]] ?: args[1]
            "getInt" -> values[args!![0]] ?: args[1]
            "edit" -> editor
            else -> error(method.name)
        } } as SharedPreferences
        fun uids(): List<String> = JSONArray(values["queue"] as String).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("uid") } }
    }
}
