package com.leyu.melora.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationMetadataTest {
    @Before fun prepare() = TrackRegistry.clear()
    @After fun cleanup() = TrackRegistry.clear()

    @Test
    fun lyricModeAddsArtistToDisplayTitleWithoutChangingCanonicalTitle() {
        val queue = Queue("a")
        val registered = requireNotNull(TrackRegistry.get("a"))

        syncNotificationMetadata(queue.player, lyric("a"), true, false)

        assertEquals("Title a - Artist a", queue.items.single().mediaMetadata.displayTitle)
        assertEquals("Title a", queue.items.single().mediaMetadata.title)
        assertEquals("A line 1", queue.items.single().mediaMetadata.artist)
        assertSame(registered, TrackRegistry.get("a"))
        assertEquals("Title a", registered.title)
    }

    @Test
    fun blankArtistsDoNotAddATitleSeparator() {
        for (artist in listOf("", "  \t\n")) {
            val queue = Queue("a")
            TrackRegistry.register(requireNotNull(TrackRegistry.get("a")).copy(artist = artist))

            syncNotificationMetadata(queue.player, lyric("a"), true, false)

            val metadata = queue.items.single().mediaMetadata
            assertEquals("Title a", metadata.displayTitle ?: metadata.title)
            assertEquals("A line 1", metadata.artist)
        }
    }

    @Test
    fun notificationContentUsesDisplayTitleAndFallsBackToCanonicalTitle() {
        val queue = Queue("a")
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("Title a - Artist a", notificationContentTitle(queue.items.single().mediaMetadata))

        syncNotificationMetadata(queue.player, lyric("a"), false, false)
        assertEquals("Title a", notificationContentTitle(queue.items.single().mediaMetadata))
        assertEquals(null, notificationContentTitle(MediaMetadata.EMPTY))
    }

    @Test
    fun lyricModeKeepsTheArtistInTitleWithoutAnAvailableLyricLine() {
        val missingLines = listOf(
            null,
            lyric("a").copy(lines = emptyList()),
            lyric("a").copy(lines = listOf(LyricLine(0, "  "))),
            lyric("a").copy(lines = listOf(LyricLine(1_000, "Not started"))),
            lyric("other"),
        )
        for (lyric in missingLines) {
            val queue = Queue("a")
            // 没有歌词时 artist 不变，开关仍须单独触发展示标题的写入和清除。
            syncNotificationMetadata(queue.player, lyric, true, false)
            assertEquals("Title a - Artist a", notificationContentTitle(queue.items.single().mediaMetadata))
            assertEquals("Artist a", queue.items.single().mediaMetadata.artist)
            repeat(3) { syncNotificationMetadata(queue.player, lyric, true, false) }
            assertEquals(listOf(0), queue.edits)

            syncNotificationMetadata(queue.player, lyric, false, false)
            assertEquals("Title a", notificationContentTitle(queue.items.single().mediaMetadata))
            assertEquals(null, queue.items.single().mediaMetadata.displayTitle)
            assertEquals("Artist a", queue.items.single().mediaMetadata.artist)
            assertEquals(listOf(0, 0), queue.edits)
        }
    }

    @Test
    fun notificationUpdateKeepsRealCurrentItemAndPositionInsteadOfJumpingToFirst() {
        val queue = Queue("a", "b", "c")
        queue.currentIndex = 1
        queue.positionMs = 1_500

        syncNotificationMetadata(queue.player, lyric("b"), true, false)

        assertEquals(listOf(1), queue.edits)
        assertEquals("b", queue.player.currentMediaItem!!.mediaId)
        assertEquals(1_500L, queue.player.currentPosition)
        assertEquals(listOf("a", "b", "c"), queue.items.map { it.mediaId })
        assertEquals("Artist a", queue.items[0].mediaMetadata.artist)
        assertEquals("B line 2", queue.items[1].mediaMetadata.artist)
        assertEquals("Title b", queue.items[1].mediaMetadata.title)
        assertEquals("Title b - Artist b", notificationContentTitle(queue.items[1].mediaMetadata))
        assertEquals(null, queue.items[0].mediaMetadata.displayTitle)
        assertEquals(null, queue.items[2].mediaMetadata.displayTitle)
    }

    @Test
    fun repeatedModeChangesDoNotRebuildTheQueueOrRewriteTheSameLine() {
        val queue = Queue("a", "b")
        queue.currentIndex = 1
        queue.positionMs = 1_500
        for (mode in listOf(PlayMode.List, PlayMode.Single, PlayMode.Shuffle, PlayMode.List)) {
            queue.mode = mode
            repeat(3) { syncNotificationMetadata(queue.player, lyric("b"), true, false) }
            assertEquals(1, queue.currentIndex)
            assertEquals(1_500L, queue.positionMs)
            assertEquals(mode, queue.mode)
        }
        assertEquals(listOf(1), queue.edits)
        assertEquals("Title b - Artist b", notificationContentTitle(queue.items[1].mediaMetadata))
    }

    @Test
    fun changingTrackRestoresPreviousArtistAndRejectsLateLyrics() {
        val queue = Queue("a", "b")
        queue.positionMs = 500
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        queue.currentIndex = 1
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("Artist a", queue.items[0].mediaMetadata.artist)
        assertEquals("Artist b", queue.items[1].mediaMetadata.artist)
        assertEquals(null, queue.items[0].mediaMetadata.displayTitle)
        assertEquals("Title a", notificationContentTitle(queue.items[0].mediaMetadata))
        assertEquals("Title b - Artist b", notificationContentTitle(queue.items[1].mediaMetadata))
        syncNotificationMetadata(queue.player, lyric("b"), true, false)
        assertEquals("B line 1", queue.items[1].mediaMetadata.artist)
        assertEquals("b", queue.player.currentMediaItem!!.mediaId)
    }

    @Test
    fun duplicateSongsOnlyDecorateTheCurrentQueueOccurrence() {
        val queue = Queue("a", "b", "a")
        queue.currentIndex = 2
        queue.positionMs = 500
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("Artist a", queue.items[0].mediaMetadata.artist)
        assertEquals("A line 1", queue.items[2].mediaMetadata.artist)
        assertEquals(null, queue.items[0].mediaMetadata.displayTitle)
        assertEquals("Title a - Artist a", notificationContentTitle(queue.items[2].mediaMetadata))
        queue.currentIndex = 0
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("A line 1", queue.items[0].mediaMetadata.artist)
        assertEquals("Artist a", queue.items[2].mediaMetadata.artist)
        assertEquals("Title a - Artist a", notificationContentTitle(queue.items[0].mediaMetadata))
        assertEquals(null, queue.items[2].mediaMetadata.displayTitle)
    }

    @Test
    fun disablingLyricsAndSeekingBackRestoreTheRightNotificationText() {
        val queue = Queue("a")
        queue.positionMs = 1_500
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("A line 2", queue.items[0].mediaMetadata.artist)
        queue.positionMs = 500
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("A line 1", queue.items[0].mediaMetadata.artist)
        assertEquals("Title a - Artist a", notificationContentTitle(queue.items[0].mediaMetadata))
        syncNotificationMetadata(queue.player, lyric("a"), false, false)
        assertEquals("Artist a", queue.items[0].mediaMetadata.artist)
        assertEquals("Title a", notificationContentTitle(queue.items[0].mediaMetadata))
        assertEquals(null, queue.items[0].mediaMetadata.displayTitle)
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        assertEquals("Title a - Artist a", notificationContentTitle(queue.items[0].mediaMetadata))
    }

    @Test
    fun metadataChangesPreserveItemConfigurationAndCanonicalRegistryData() {
        val queue = Queue("a")
        val registered = requireNotNull(TrackRegistry.get("a"))
        val item = queue.items[0].buildUpon().setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(400).setEndPositionMs(8_000).build(),
        ).build()
        queue.items[0] = item
        queue.positionMs = 500
        syncNotificationMetadata(queue.player, lyric("a"), true, false)
        val updated = queue.items[0]
        assertEquals(item.clippingConfiguration, updated.clippingConfiguration)
        assertEquals(item.requestMetadata, updated.requestMetadata)
        assertEquals("kept", updated.mediaMetadata.description)
        assertSame(registered, TrackRegistry.get("a"))
        assertEquals("Artist a", registered.artist)
        assertEquals("Title a", registered.title)
        assertEquals("Title a", updated.mediaMetadata.title)
    }

    @Test
    fun emptyOrUnregisteredQueuesAreSafeAndDoNotInventTrackMetadata() {
        val empty = Queue()
        repeat(3) { syncNotificationMetadata(empty.player, lyric("a"), true, false) }
        assertTrue(empty.edits.isEmpty())
        val queue = Queue("external")
        TrackRegistry.clear()
        val before = queue.items.single()
        syncNotificationMetadata(queue.player, lyric("external"), true, false)
        assertSame(before, queue.items.single())
        assertTrue(queue.edits.isEmpty())
    }

    @Test
    fun localAndNetworkArtworkUrisShareTheNotificationPath() {
        assertEquals("file:///cover.jpg", playableArtworkUri("file:///cover.jpg"))
        assertEquals("file:/cover.jpg", playableArtworkUri("file:/cover.jpg"))
        assertEquals("content://media/cover/1", playableArtworkUri("content://media/cover/1"))
        assertEquals("https://example.test/cover.jpg", playableArtworkUri("https://example.test/cover.jpg"))
        assertEquals(null, playableArtworkUri("ftp://example.test/cover.jpg"))
        assertEquals(null, playableArtworkUri("file:///cover.jpg", enabled = false))
    }

    @Test
    fun artworkListenerCanBeRemovedWhenServiceStops() {
        TrackRegistry.register(UiTrack("a", "A", "Artist", "Album"))
        var calls = 0
        val listener: (String, String) -> Unit = { _, _ -> calls++ }
        TrackRegistry.onArtwork(listener)
        try {
            TrackRegistry.updateArtwork("a", "https://example.test/one.jpg")
            TrackRegistry.removeArtworkListener(listener)
            TrackRegistry.removeArtworkListener(listener)
            TrackRegistry.updateArtwork("a", "https://example.test/two.jpg")
            assertEquals(1, calls)
        } finally {
            TrackRegistry.removeArtworkListener(listener)
        }
    }

    private fun lyric(uid: String) = PlayerLyric(
        uid, "Title $uid", "Artist $uid",
        listOf(LyricLine(0, "${uid.uppercase()} line 1"), LyricLine(1_000, "${uid.uppercase()} line 2")), "test",
    )

    /** A real Media3 MediaItem queue; any seek/setMediaItems/transport command fails this test. */
    private class Queue(vararg ids: String) {
        val items = ids.map { id ->
            val track = UiTrack(id, "Title $id", "Artist $id", "Album $id")
            TrackRegistry.register(track)
            MediaItem.Builder().setMediaId(id).setMediaMetadata(
                MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                    .setAlbumTitle(track.album).setDescription("kept").build(),
            ).build()
        }.toMutableList()
        var currentIndex = if (items.isEmpty()) -1 else 0
        var positionMs = 0L
        var mode = PlayMode.List
        val edits = mutableListOf<Int>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> currentIndex
                "getCurrentMediaItem" -> items.getOrNull(currentIndex)
                "getCurrentPosition" -> positionMs
                "getMediaItemAt" -> items[args!![0] as Int]
                "replaceMediaItem" -> {
                    val index = args!![0] as Int
                    items[index] = args[1] as MediaItem
                    edits += index
                    null
                }
                else -> error("Notification update must not call ${method.name}")
            }
        } as Player
    }
}
