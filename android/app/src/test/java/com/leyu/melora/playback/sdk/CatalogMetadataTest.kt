package com.leyu.melora.playback.sdk

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogMetadataTest {
    @Test
    fun artistParserUsesExactMatchAndRealCounts() {
        val body = """{"code":200,"result":{"artists":[
            {"name":"同名歌手.","picUrl":"https://img/fallback","musicSize":2,"albumSize":1},
            {"name":"同名歌手","picUrl":"https://img/exact","alias":["Alias"],"musicSize":88,"albumSize":12}
        ]}}"""
        assertEquals(
            ArtistProfile("https://img/exact", listOf("Alias"), songCount = 88, albumCount = 12),
            artistProfileFromSearch(body, "同名歌手"),
        )
    }

    @Test
    fun artistParserDoesNotBorrowCountsFromDifferentName() {
        val body = """{"code":200,"result":{"artists":[
            {"name":"其他人","picUrl":"https://img/fallback","musicSize":99,"albumSize":20}
        ]}}"""
        assertEquals(ArtistProfile(image = "https://img/fallback"), artistProfileFromSearch(body, "目标歌手"))
    }

    @Test
    fun albumParserRequiresTitleAndArtistMatch() {
        val body = """{"code":200,"result":{"albums":[
            {"name":"同名专辑","artist":{"name":"其他人"},"size":2},
            {"name":"同名专辑","artist":{"name":"目标歌手"},"picUrl":"https://img/album",
             "publishTime":1059580800000,"type":"专辑","size":11,"company":"发行方"}
        ]}}"""
        assertEquals(
            AlbumProfile(
                artist = "目标歌手",
                image = "https://img/album",
                releaseDate = "2003-07-31",
                type = "专辑",
                trackCount = 11,
                company = "发行方",
            ),
            albumProfileFromSearch(body, "同名专辑", "目标歌手"),
        )
        assertNull(albumProfileFromSearch(body, "同名专辑", "不存在的歌手"))
    }

    @Test(expected = IOException::class)
    fun parserRejectsFailedResponse() {
        artistProfileFromSearch("""{"code":503,"message":"busy"}""", "歌手")
    }
}
