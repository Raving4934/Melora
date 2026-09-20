package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlaybackArtworkTest {
    @Before
    fun setUp() = TrackRegistry.clear()

    @After
    fun tearDown() = TrackRegistry.clear()

    @Test
    fun artworkResolutionWritesBackToRegisteredTrackAndKeepsOriginalUid() = runBlocking {
        val raw = JSONObject()
            .put("source", "wy")
            .put("songmid", "song-1")
            .put("name", "Original")
            .put("singer", "Singer")
            .put("albumName", "Album")
        val song = OnlineSong(raw)
        val track = UiTrack.fromOnline(song)
        TrackRegistry.register(track)

        assertEquals(
            "https://cover.example/song-1.jpg",
            PlaybackController.resolveAndUpdateArtwork(track) { "https://cover.example/song-1.jpg" },
        )

        val stored = requireNotNull(TrackRegistry.get(track.uid))
        assertEquals("https://cover.example/song-1.jpg", stored.artwork)
        assertEquals(track.uid, stored.uid)
        assertSame(raw, stored.raw)
        assertEquals(track.title, stored.title)
        assertEquals(track.artist, stored.artist)
    }

    @Test
    fun repeatedFailedRequestsDoNotPermanentlyDisableTheSameTrack() = runBlocking {
        val raw = JSONObject()
            .put("source", "wy")
            .put("songmid", "song-retry")
            .put("name", "Retry")
            .put("singer", "Singer")
            .put("albumName", "Album")
        val track = UiTrack.fromOnline(OnlineSong(raw))
        TrackRegistry.register(track)
        var requests = 0
        val resolve: suspend (OnlineSong) -> String? = {
            requests++
            if (requests < 3) null else "https://cover.example/recovered.jpg"
        }

        assertNull(PlaybackController.resolveAndUpdateArtwork(track, resolve))
        assertNull(PlaybackController.resolveAndUpdateArtwork(track, resolve))
        assertEquals(
            "https://cover.example/recovered.jpg",
            PlaybackController.resolveAndUpdateArtwork(track, resolve),
        )

        assertEquals(3, requests)
        assertEquals(
            "https://cover.example/recovered.jpg",
            requireNotNull(TrackRegistry.get(track.uid)).artwork,
        )
    }

    @Test
    fun failedArtworkResolutionDoesNotOverwriteRegisteredTrack() = runBlocking {
        val raw = JSONObject()
            .put("source", "wy")
            .put("songmid", "song-2")
            .put("name", "Original")
            .put("singer", "Singer")
            .put("albumName", "Album")
        val track = UiTrack.fromOnline(OnlineSong(raw))
        TrackRegistry.register(track)

        assertNull(PlaybackController.resolveAndUpdateArtwork(track) { null })

        val stored = requireNotNull(TrackRegistry.get(track.uid))
        assertNull(stored.artwork)
        assertEquals(track.uid, stored.uid)
        assertSame(raw, stored.raw)
    }
}
