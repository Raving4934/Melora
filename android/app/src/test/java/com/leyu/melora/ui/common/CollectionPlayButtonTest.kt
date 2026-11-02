package com.leyu.melora.ui.common

import com.leyu.melora.playback.bookQueueId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CollectionPlayButtonTest {
    @Test
    fun idleCollectionWithoutResumePointPlaysAll() {
        val button = collectionPlayButton(active = false, playing = false, canResume = false, resumeLabel = "继续播放")
        assertEquals(CollectionPlayButton("播放全部", pause = false, CollectionPlayIntent.PlayAll), button)
    }

    @Test
    fun bookWithResumePointContinuesInsteadOfRestartingFromFirstChapter() {
        val button = collectionPlayButton(active = false, playing = false, canResume = true, resumeLabel = "继续收听")
        assertEquals(CollectionPlayButton("继续收听", pause = false, CollectionPlayIntent.Resume), button)
    }

    @Test
    fun pausedActiveQueueTogglesWithoutRebuildingQueue() {
        val music = collectionPlayButton(active = true, playing = false, canResume = false, resumeLabel = "继续播放")
        assertEquals(CollectionPlayButton("继续播放", pause = false, CollectionPlayIntent.Toggle), music)
        // 当前队列优先于断点：同一本书已在播放器中时继续实时位置，不回读持久化断点。
        val book = collectionPlayButton(active = true, playing = false, canResume = true, resumeLabel = "继续收听")
        assertEquals(CollectionPlayIntent.Toggle, book.intent)
        assertEquals("继续收听", book.label)
    }

    @Test
    fun playingActiveQueueOffersPause() {
        for (canResume in listOf(false, true)) {
            val button = collectionPlayButton(active = true, playing = true, canResume = canResume, resumeLabel = "继续收听")
            assertEquals(CollectionPlayButton("暂停播放", pause = true, CollectionPlayIntent.Toggle), button)
        }
    }

    @Test
    fun anotherQueuePlayingDoesNotTakeOverThisCollectionButton() {
        val idle = collectionPlayButton(active = false, playing = true, canResume = false, resumeLabel = "继续播放")
        assertEquals(CollectionPlayIntent.PlayAll, idle.intent)
        assertFalse(idle.pause)
        val book = collectionPlayButton(active = false, playing = true, canResume = true, resumeLabel = "继续收听")
        assertEquals(CollectionPlayIntent.Resume, book.intent)
        assertFalse(book.pause)
    }

    @Test
    fun bookQueueIdentityMatchesPlayBookForEveryAlbumIdForm() {
        val expected = bookQueueId("12345")
        assertEquals(expected, collectionQueueId("kw", "book_album_12345", isBook = true))
        assertEquals(expected, collectionQueueId("kw", "kw:book_album_12345", isBook = true))
        assertEquals(expected, collectionQueueId("kw", "12345", isBook = true))
        // 与卡片按钮的 playlist.<source>.<id> 身份一致。
        assertEquals("playlist.kw.book_album_12345", collectionQueueId("kw", "book_album_12345", isBook = true))
    }

    @Test
    fun musicCollectionsKeepCardQueueIdentity() {
        assertEquals("playlist.wy.3778678", collectionQueueId("wy", "3778678", isBook = false))
        assertEquals("playlist.kw.album_1", collectionQueueId("kw", "album_1", isBook = false))
    }
}
