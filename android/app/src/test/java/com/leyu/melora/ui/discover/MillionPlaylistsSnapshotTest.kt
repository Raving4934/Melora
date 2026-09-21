package com.leyu.melora.ui.discover

import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MillionPlaylistsSnapshotTest {
    @After fun cleanup() = OnlineCache.clear(MILLION_PLAYLISTS_CACHE_KEY)

    private fun playlist(id: String, count: Long = 1_000_000, source: String = "kw") = OnlinePlaylist(
        JSONObject().put("id", id).put("source", source).put("play_num", count),
    )

    @Test fun firstPageKeepsTheExistingMillionThreshold() {
        val page = millionPlaylistsSnapshot(null, listOf(playlist("below", 999_999), playlist("at"), playlist("above", 1_000_001)), 1)
        assertEquals(listOf("at", "above"), page.items.map { it.id })
        assertEquals(1, page.page)
        assertTrue(page.hasMore)
    }

    @Test fun appendedPagesKeepIdentityAndCursorTogetherAcrossReentry() {
        val first = millionPlaylistsSnapshot(null, listOf(playlist("a")), 1)
        val second = millionPlaylistsSnapshot(first, listOf(playlist("a"), playlist("b"), playlist("a", source = "other")), 2)
        OnlineCache.put(MILLION_PLAYLISTS_CACHE_KEY, second)
        val restored = checkNotNull(OnlineCache.get<MillionPlaylistsSnapshot>(MILLION_PLAYLISTS_CACHE_KEY, OnlineCache.CATALOG_TTL_MS))
        assertEquals(listOf("kw_a", "kw_b", "other_a"), restored.items.map { "${it.source}_${it.id}" })
        assertEquals(3, restored.page + 1)
        assertTrue(restored.hasMore)
        assertEquals(listOf("a"), first.items.map { it.id })
    }

    @Test fun lastPageAndEmptyResultsStayTerminalWhenCached() {
        val last = millionPlaylistsSnapshot(null, listOf(playlist("a")), 6)
        assertFalse(last.hasMore)
        val exhausted = millionPlaylistsSnapshot(last, emptyList(), 6)
        assertEquals(last.items, exhausted.items)
        assertFalse(exhausted.hasMore)
        OnlineCache.put(MILLION_PLAYLISTS_CACHE_KEY, exhausted)
        assertEquals(exhausted, OnlineCache.peek<MillionPlaylistsSnapshot>(MILLION_PLAYLISTS_CACHE_KEY))
        assertFalse(millionPlaylistsSnapshot(null, emptyList(), 1).hasMore)
    }

    @Test fun refreshReplacesRatherThanAppendsToAnOldCursor() {
        val old = millionPlaylistsSnapshot(null, listOf(playlist("old")), 4)
        OnlineCache.put(MILLION_PLAYLISTS_CACHE_KEY, old)
        assertNull(OnlineCache.get<MillionPlaylistsSnapshot>(MILLION_PLAYLISTS_CACHE_KEY, -1))
        assertEquals(old, OnlineCache.peek<MillionPlaylistsSnapshot>(MILLION_PLAYLISTS_CACHE_KEY))
        val fresh = millionPlaylistsSnapshot(null, listOf(playlist("fresh")), 1)
        OnlineCache.put(MILLION_PLAYLISTS_CACHE_KEY, fresh)
        assertEquals(1, OnlineCache.peek<MillionPlaylistsSnapshot>(MILLION_PLAYLISTS_CACHE_KEY)?.page)
        assertEquals(listOf("fresh"), fresh.items.map { it.id })
    }
}
