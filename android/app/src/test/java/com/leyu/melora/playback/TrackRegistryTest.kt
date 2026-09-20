package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class TrackRegistryTest {
    @Before
    fun setUp() = TrackRegistry.clear()

    @After
    fun tearDown() = TrackRegistry.clear()

    @Test
    fun resolvedNotificationKeepsOriginalSongIdentityForConsumers() {
        val original = onlineSong("wy", "original", "Original", "https://cover/original.jpg")
        TrackRegistry.register(UiTrack.fromOnline(original))

        // 解析完成只发布音质/解析器身份，不应替换用户歌曲快照。
        TrackRegistry.notifyResolved(original.uid, "320k", "lx:script:stream")

        assertOriginalIdentity(original)
    }

    @Test
    fun artworkUpdateOnlyChangesArtworkAndKeepsOriginalSongIdentity() {
        val original = onlineSong("wy", "original", "Original", null)
        TrackRegistry.register(UiTrack.fromOnline(original))

        TrackRegistry.updateArtwork(original.uid, "https://cover/resolved.jpg")

        val stored = requireNotNull(TrackRegistry.get(original.uid))
        assertEquals("https://cover/resolved.jpg", stored.artwork)
        assertEquals(original.uid, stored.uid)
        assertEquals(original.source, stored.source)
        assertEquals(original.name, stored.title)
        assertEquals(original.singer, stored.artist)
        assertEquals(original.albumName, stored.album)
        assertSame(original.raw, stored.raw)

        // 收藏与下载均从 track.raw 读取 OnlineSong，必须仍使用原平台 UID。
        val consumerSong = requireNotNull(OnlineSong.from(stored.raw))
        assertEquals(original.uid, consumerSong.uid)
    }

    @Test
    fun cachedResolutionReplacesQualityAndSourceTogether() {
        val original = onlineSong("wy", "original", "Original", null)
        TrackRegistry.register(UiTrack.fromOnline(original))
        TrackRegistry.notifyResolved(original.uid, "320k", "lx:script:old")
        val cachedKey = audioResourceKey(original.uid, "flac", "kw_123", "fixture:flac:file")
        TrackRegistry.notifyResolved(original.uid, "flac", audioResourceId(cachedKey))
        assertEquals(TrackRegistry.Resolution("flac", "fixture:flac:file"), TrackRegistry.resolved(original.uid))
        assertOriginalIdentity(original)
    }

    @Test
    fun legacyCacheDoesNotInheritPreviousResolver() {
        TrackRegistry.notifyResolved("kw_1", "320k", "lx:script:old")
        TrackRegistry.notifyResolved("kw_1", "128k", audioResourceId(audioCacheKey("kw_1", "128k")))
        assertEquals(TrackRegistry.Resolution("128k", null), TrackRegistry.resolved("kw_1"))
    }

    @Test
    fun retryAndClearRemoveResolutionWithoutReplacingTrack() {
        val original = onlineSong("wy", "original", "Original", null)
        TrackRegistry.register(UiTrack.fromOnline(original))
        TrackRegistry.notifyResolved(original.uid, "320k", "lx:script:old")
        TrackRegistry.clearResolved(original.uid)
        assertNull(TrackRegistry.resolved(original.uid))
        assertOriginalIdentity(original)
        TrackRegistry.notifyResolved(original.uid, "flac", "lx:script:new")
        TrackRegistry.clear()
        assertNull(TrackRegistry.resolved(original.uid))
        assertNull(TrackRegistry.get(original.uid))
    }

    private fun assertOriginalIdentity(original: OnlineSong) {
        val stored = requireNotNull(TrackRegistry.get(original.uid))
        assertEquals(original.uid, stored.uid)
        assertEquals(original.source, stored.source)
        assertEquals(original.name, stored.title)
        assertEquals(original.singer, stored.artist)
        assertEquals(original.albumName, stored.album)
        assertSame(original.raw, stored.raw)
        assertEquals(original.uid, requireNotNull(OnlineSong.from(stored.raw)).uid)
    }

    private fun onlineSong(source: String, songmid: String, name: String, img: String?): OnlineSong =
        OnlineSong(
            JSONObject()
                .put("source", source)
                .put("songmid", songmid)
                .put("name", name)
                .put("singer", "Singer")
                .put("albumName", "Album")
                .put("img", img),
        )
}
