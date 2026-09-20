package com.leyu.melora.ui.common

import com.leyu.melora.playback.sdk.AlbumProfile
import com.leyu.melora.playback.sdk.ArtistProfile
import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CollectionDetailCopyTest {
    @Test
    fun albumUsesReleaseMetadataInsteadOfLoadedCount() {
        val copy = albumHeaderCopy(
            AlbumProfile(
                artist = "周杰伦", releaseDate = "2003-07-31", type = "专辑",
                trackCount = 11, company = "杰威尔",
            ),
            fallbackArtist = "周杰伦",
            songs = emptyList(),
        )
        assertEquals("周杰伦", copy.primary)
        assertEquals("发行 2003-07-31 · 专辑 · 11 首 · 杰威尔", copy.secondary)
        assertFalse(copy.secondary.contains("已加载"))
    }

    @Test
    fun artistUsesAliasesCountsAndRealWorksFallback() {
        val profiled = artistHeaderCopy(
            ArtistProfile(aliases = listOf("Jay Chou", "周董"), songCount = 568, albumCount = 41),
            emptyList(),
        )
        assertEquals("别名：Jay Chou、周董", profiled.primary)
        assertEquals("568 首作品 · 41 张专辑", profiled.secondary)

        val fallback = artistHeaderCopy(null, listOf(song("夜曲"), song("晴天")))
        assertEquals("代表作：夜曲、晴天", fallback.primary)
        assertEquals("", fallback.secondary)
    }

    @Test
    fun bookOnlyShowsIntroAndHeat() {
        val copy = bookHeaderCopy("真实节目简介", "主播", "3.1亿")
        assertEquals("真实节目简介", copy.primary)
        assertEquals("热度 3.1亿", copy.secondary)
        assertFalse(copy.secondary.contains("集"))
        assertFalse(copy.secondary.contains("已加载"))
    }

    private fun song(name: String) = OnlineSong(JSONObject()
        .put("source", "kw").put("songmid", name).put("name", name))
}
