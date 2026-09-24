package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class BookPlaybackQueueTest {
    @Test fun onlyBookChaptersWithAlbumIdentityUseBookQueue() {
        assertNull(chapter(1).let { OnlineSong(JSONObject(it.raw.toString()).put("isBookChapter", false)) }.bookId())
        assertNull(OnlineSong(JSONObject(chapter(1).raw.toString()).put("albumId", "")).bookId())
        assertEquals("fixture", chapter(1).bookId())
        assertEquals("fixture", OnlineSong(JSONObject(chapter(1).raw.toString()).put("albumId", "kw:book_album_fixture")).bookId())
    }

    @Test fun chapter100Loads101WithoutFetchingPreviousPages() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = followingBookChapters(chapter(100)) { id, page ->
            assertEquals("fixture", id)
            calls += page
            page(page)
        }
        assertEquals(listOf(2), calls)
        assertEquals("kw_chapter_101", result.first().uid)
        assertEquals("kw_chapter_200", result.last().uid)
    }

    @Test fun middleOfPageStartsWithFollowingChapter() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = followingBookChapters(chapter(155)) { _, p -> calls += p; page(p) }
        assertEquals(listOf(2), calls)
        assertEquals((156..200).map { "kw_chapter_$it" }, result.map { it.uid })
    }

    @Test fun trimmedPersistedWindowDoesNotSkipUnsavedRemainderOfPage() = runBlocking {
        // 原队列有1000章，只保存第501..700章或第556..755章，恢复以实际保存的尾章为准。
        for (tail in listOf(700, 755)) {
            val result = followingBookChapters(chapter(tail)) { _, p -> page(p) }
            assertEquals("kw_chapter_${tail + 1}", result.first().uid)
        }
    }

    @Test fun finalChapterDoesNotFetchOrWrapToBeginning() = runBlocking {
        val final = chapter(237, end = true, more = false)
        assertTrue(followingBookChapters(final) { _, _ -> error("must not fetch") }.isEmpty())
    }

    @Test fun lastPagePartialRemainderStopsAtRealEnd() = runBlocking {
        val result = followingBookChapters(chapter(230, more = false)) { _, p ->
            assertEquals(3, p)
            KwBookApi.BookChapters((201..237).map { chapter(it, end = it == 237, more = false) }, false, page = p)
        }
        assertEquals(7, result.size)
        assertEquals("kw_chapter_237", result.last().uid)
    }

    @Test fun legacyRecentChapterIsLocatedByUidNotTitleNumber() = runBlocking {
        val legacy = OnlineSong(JSONObject(chapter(155).raw.toString()).apply {
            remove("bookPage"); remove("bookPageEnd"); remove("bookHasMore")
            put("name", "序章 / 无数字的章节名")
        })
        val calls = mutableListOf<Int>()
        val result = followingBookChapters(legacy) { _, p -> calls += p; page(p) }
        assertEquals(listOf(1, 2), calls)
        assertEquals("kw_chapter_156", result.first().uid)
    }

    @Test fun legacyPageTailContinuesOnNextPage() = runBlocking {
        val legacy = OnlineSong(JSONObject(chapter(100).raw.toString()).apply { remove("bookPage") })
        val calls = mutableListOf<Int>()
        val result = followingBookChapters(legacy) { _, p -> calls += p; page(p) }
        assertEquals(listOf(1, 2), calls)
        assertEquals("kw_chapter_101", result.first().uid)
    }

    @Test fun emptyResponseIsRetryableNotMistakenForBookFinished() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { followingBookChapters(chapter(100)) { _, _ -> KwBookApi.BookChapters(emptyList(), false) } }
        }
    }

    @Test fun wrongAlbumCannotPolluteQueue() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { followingBookChapters(chapter(100)) { _, _ ->
                KwBookApi.BookChapters(listOf(OnlineSong(JSONObject(chapter(101).raw.toString()).put("albumId", "other"))), true)
            } }
        }
    }

    @Test fun movedChapterIsNotSilentlySkipped() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { followingBookChapters(chapter(155)) { _, _ -> page(3) } }
        }
    }

    @Test fun repeatedLegacyPageFailsRatherThanLoopingForever() {
        var calls = 0
        val legacy = OnlineSong(JSONObject(chapter(355).raw.toString()).apply { remove("bookPage") })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { followingBookChapters(legacy) { _, _ -> calls++; page(1) } }
        }
        assertEquals(2, calls)
    }

    @Test fun cancelledLegacyLookupStopsBeforeNextRequest() {
        var calls = 0
        val legacy = OnlineSong(JSONObject(chapter(355).raw.toString()).apply { remove("bookPage") })
        assertThrows(CancellationException::class.java) {
            runBlocking { followingBookChapters(legacy) { _, _ ->
                calls++
                currentCoroutineContext().cancel()
                page(1)
            } }
        }
        assertEquals(1, calls)
    }

    @Test fun paginationDoesNotDelaySelectionOrFetchWholeBook() = runBlocking {
        val loaded = CompletableDeferred<Unit>()
        val response = CompletableDeferred<KwBookApi.BookChapters>()
        val queue = Queue(chapter(100))
        val manager = manager(queue, this) { _, _ -> loaded.complete(Unit); response.await() }
        manager.start("fixture")
        loaded.await()
        assertEquals("kw_chapter_100", queue.items[queue.index].mediaId)
        assertEquals(1, queue.items.size)
        response.complete(page(2))
        await { queue.items.size == 101 }
        assertEquals(0, queue.index)
        assertEquals("kw_chapter_101", queue.items[1].mediaId)
        manager.stop()
    }

    @Test fun pauseWhileFetchingNeverResumesOrSeeks() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<KwBookApi.BookChapters>()
        val queue = Queue(chapter(100)).apply { state = Player.STATE_ENDED }
        val manager = manager(queue, this) { _, _ -> started.complete(Unit); response.await() }
        manager.start("fixture")
        started.await()
        queue.playWhenReady = false
        response.complete(page(2))
        await { queue.items.size == 101 }
        assertFalse(queue.playWhenReady)
        assertEquals(0, queue.index)
        // 用户恢复播放后，才进入已补好的下一章。
        queue.playWhenReady = true
        manager.check()
        assertEquals(1, queue.index)
        manager.stop()
    }

    @Test fun endedChapterAdvancesWhenNextPageArrives() = runBlocking {
        val queue = Queue(chapter(100)).apply { state = Player.STATE_ENDED }
        val manager = manager(queue, this) { _, p -> page(p) }
        manager.start("fixture")
        await { queue.index == 1 }
        assertEquals("kw_chapter_101", queue.items[queue.index].mediaId)
        assertTrue(queue.playWhenReady)
        manager.stop()
    }

    @Test fun manualNextAtPageBoundaryWaitsForNextChapterInsteadOfRestartingCurrent() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<KwBookApi.BookChapters>()
        val queue = Queue(chapter(100))
        val manager = manager(queue, this) { _, _ -> started.complete(Unit); response.await() }
        manager.start("fixture")
        started.await()
        assertTrue(manager.next())
        assertEquals(0, queue.index)
        response.complete(page(2))
        await { queue.index == 1 }
        assertEquals("kw_chapter_101", queue.items[1].mediaId)
        manager.stop()
    }

    @Test fun reconnectOnlyRecognizesCompleteSingleAlbumBookQueues() {
        assertNull(Queue().player.bookAlbumId(TrackRegistry::get))
        val chapters = Queue(chapter(1), chapter(2))
        assertEquals("fixture", chapters.player.bookAlbumId(TrackRegistry::get))
        assertNull(chapters.player.bookAlbumId { null })
        val otherAlbum = Queue(chapter(1), otherBook(chapter(2)))
        assertNull(otherAlbum.player.bookAlbumId(TrackRegistry::get))
        val music = OnlineSong(JSONObject(chapter(2).raw.toString()).put("isBookChapter", false))
        assertNull(Queue(chapter(1), music).player.bookAlbumId(TrackRegistry::get))
    }

    @Test fun persistedMusicModesNeverMakeBookChaptersRepeatOrShuffle() = runBlocking {
        for (mode in PlayMode.entries) {
            val queue = Queue(chapter(1))
            queue.player.applyPlayMode(mode)
            val manager = manager(queue, this) { _, _ -> error("尾章未到，不应加载") }
            manager.start("fixture")
            assertEquals(Player.REPEAT_MODE_OFF, queue.repeat)
            assertFalse(queue.shuffle)
            manager.stop()
            assertEquals(mode.repeat, queue.repeat)
            assertEquals(mode.shuffled, queue.shuffle)
            manager.stop()
            assertEquals(mode.repeat, queue.repeat)
        }
    }

    @Test fun restoringMusicPreferenceDuringBookOnlyChangesTheModeUsedAfterLeavingIt() = runBlocking {
        val queue = Queue(chapter(1))
        val manager = manager(queue, this) { _, _ -> error("尾章未到，不应加载") }
        manager.applyMusicMode(PlayMode.Single)
        assertEquals(Player.REPEAT_MODE_ONE, queue.repeat)
        manager.start("fixture")
        manager.applyMusicMode(PlayMode.Shuffle)
        assertEquals(Player.REPEAT_MODE_OFF, queue.repeat)
        assertFalse(queue.shuffle)
        manager.stop()
        assertEquals(Player.REPEAT_MODE_ALL, queue.repeat)
        assertTrue(queue.shuffle)
    }

    @Test fun switchingBooksDiscardsPreviousRequestAndRestoresMusicMode() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<KwBookApi.BookChapters>()
        val queue = Queue(chapter(100)).apply { repeat = Player.REPEAT_MODE_ONE; shuffle = true }
        val manager = manager(queue, this) { id, p ->
            if (id == "fixture") { oldStarted.complete(Unit); oldResponse.await() }
            else KwBookApi.BookChapters(page(p).items.map { otherBook(it) }, true, page = p)
        }
        manager.start("fixture")
        oldStarted.await()
        assertEquals(Player.REPEAT_MODE_OFF, queue.repeat)
        assertFalse(queue.shuffle)
        queue.replace(otherBook(chapter(100)))
        manager.start("other")
        oldResponse.complete(page(2))
        await { queue.items.size == 101 }
        assertTrue(queue.items.all { it.mediaId.startsWith("kw_other_") })
        manager.stop()
        assertEquals(Player.REPEAT_MODE_ONE, queue.repeat)
        assertTrue(queue.shuffle)
    }

    @Test fun failureKeepsBookAndRetriesOnlyWhenRequested() = runBlocking {
        var calls = 0
        val errors = mutableListOf<String>()
        val queue = Queue(chapter(100))
        val manager = manager(queue, this, errors) { _, p ->
            calls++
            if (calls == 1) error("暂时离线")
            page(p)
        }
        manager.start("fixture")
        await { errors.size == 1 }
        repeat(100) { manager.check() }
        assertEquals(1, calls)
        assertEquals("fixture", manager.albumId)
        assertEquals(1, queue.items.size)
        manager.retry()
        await { queue.items.size == 101 }
        assertEquals(2, calls)
        manager.stop()
    }

    @Test fun clearQueueCancelsInFlightPages() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<KwBookApi.BookChapters>()
        val queue = Queue(chapter(100))
        val manager = manager(queue, this) { _, _ -> started.complete(Unit); response.await() }
        manager.start("fixture")
        started.await()
        queue.items.clear()
        manager.check()
        response.complete(page(2))
        delay(30)
        assertNull(manager.albumId)
        assertTrue(queue.items.isEmpty())
    }

    private fun manager(
        queue: Queue,
        scope: CoroutineScope,
        errors: MutableList<String> = mutableListOf(),
        load: suspend (String, Int) -> KwBookApi.BookChapters,
    ) = BookPlaybackQueue(
        queue.player, scope, TrackRegistry::get, { MediaItem.Builder().setMediaId(it.uid).build() },
        load, {}, { errors += it },
    )

    private suspend fun await(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(5)
    }

    private fun otherBook(song: OnlineSong) = OnlineSong(JSONObject(song.raw.toString())
        .put("albumId", "other").put("songmid", "other_${song.songmid}"))

    private class Queue(vararg songs: OnlineSong) {
        val items = mutableListOf<MediaItem>()
        var index = 0
        var state = Player.STATE_READY
        var playWhenReady = true
        var repeat = Player.REPEAT_MODE_ALL
        var shuffle = false
        init { replace(*songs) }
        fun replace(vararg songs: OnlineSong) {
            items.clear()
            songs.forEach {
                TrackRegistry.register(UiTrack.fromOnline(it))
                items += MediaItem.Builder().setMediaId(it.uid).build()
            }
            index = 0
        }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> index
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getMediaItemAt" -> items[args!![0] as Int]
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "getRepeatMode" -> repeat
                "setRepeatMode" -> { repeat = args!![0] as Int; null }
                "getShuffleModeEnabled" -> shuffle
                "setShuffleModeEnabled" -> { shuffle = args!![0] as Boolean; null }
                "hasNextMediaItem" -> index < items.lastIndex
                "seekToDefaultPosition" -> { index = args!![0] as Int; state = Player.STATE_READY; null }
                "addMediaItems" -> { @Suppress("UNCHECKED_CAST") items.addAll(args!![0] as List<MediaItem>); null }
                else -> error("Unexpected playback command ${method.name}")
            }
        } as Player
    }

    private fun page(number: Int) = KwBookApi.BookChapters(
        ((number - 1) * 100 + 1..number * 100).map { chapter(it) }, true, page = number,
    )

    private fun chapter(number: Int, end: Boolean = number % 100 == 0, more: Boolean = true) = OnlineSong(
        JSONObject().put("source", "kw").put("songmid", "chapter_$number").put("name", "章节 $number")
            .put("albumId", "fixture").put("isBookChapter", true)
            .put("bookPage", (number - 1) / 100 + 1).put("bookPageEnd", end).put("bookHasMore", more),
    )
}
