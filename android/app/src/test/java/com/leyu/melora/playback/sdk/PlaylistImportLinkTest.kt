package com.leyu.melora.playback.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistImportLinkTest {
    @Test
    fun parsesSupportedPublicPlaylistLinksAndPreservesUrl() {
        val cases = listOf(
            "kw" to "https://m.kuwo.cn/h5app/playlist/2736267853?t=qqfriend",
            "kg" to "https://www.kugou.com/songlist/gcid_abc123/",
            "kg" to "https://www.kugou.com/share/a1b2c3d4e5.html",
            "tx" to "https://i.y.qq.com/n2/m/share/details/taoge.html?id=7217720898&ADTAG=qfshare",
            "wy" to "https://music.163.com/#/playlist?id=123456",
            "mg" to "https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=184187437&channel=0146921",
        )

        cases.forEach { (source, url) ->
            val parsed = PlaylistImportLink.parse("歌单分享：$url。")
            assertEquals(source, parsed.source)
            assertEquals(url, parsed.value)
        }
    }

    @Test
    fun acceptsPlaylistQueryIdsAtTheEndOfShareUrls() {
        val qq = "https://i.y.qq.com/n2/m/share/details/taoge.html?id=7217720898"
        val miguH5 = "https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=184187437"
        val miguWeb = "https://music.migu.cn/v5/#/playlist?playlistId=221573417"

        assertEquals(qq, PlaylistImportLink.parse(qq).value)
        assertEquals(miguH5, PlaylistImportLink.parse(miguH5).value)
        assertEquals(miguWeb, PlaylistImportLink.parse(miguWeb).value)
    }

    @Test
    fun recognizesProviderShortLinksWithoutGuessingAnId() {
        val cases = listOf(
            "kg" to "https://t1.kugou.com/a1B2c3D4",
            "tx" to "https://c.y.qq.com/base/fcgi-bin/u?__=AbCdEf",
            "wy" to "https://163cn.tv/a1B2c3",
            "mg" to "http://c.migu.cn/00bTY6",
        )

        cases.forEach { (source, url) ->
            val parsed = PlaylistImportLink.parse(url)
            assertEquals(source, parsed.source)
            assertEquals(url, parsed.value)
        }
    }

    @Test
    fun reportsLocalizedPlatformName() {
        assertEquals("网易云音乐", PlaylistImportLink.parse("https://music.163.com/#/playlist?id=12").platformName)
    }

    @Test
    fun rejectsBareIdsSongsAlbumsLookalikeHostsAndNonHttpLinks() {
        listOf(
            "123456",
            "https://y.qq.com/n/ryqq/songDetail/123456",
            "https://music.163.com/#/album?id=123456",
            "https://notkuwo.cn/playlist_detail/123456",
            "https://kuwo.cn.attacker.example/playlist_detail/123456",
            "ftp://music.163.com/playlist?id=123456",
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { PlaylistImportLink.parse(text) }
        }
    }

    @Test
    fun rejectsAmbiguousMultiplePlaylistLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistImportLink.parse("https://y.qq.com/n/yqq/playlist/1.html https://music.163.com/#/playlist?id=2")
        }
    }
    @Test
    fun acceptsNeteaseMobilePlaylistSharesWithAttributionParameters() {
        val mobile = "https://y.music.163.com/m/playlist?id=123456&userid=700001&creatorId=700002"
        val parsed = PlaylistImportLink.parse("我分享的歌单：$mobile")
        assertEquals("wy", parsed.source)
        assertEquals(mobile, parsed.value)
        assertEquals("wy", PlaylistImportLink.parse("https://y.music.163.com/m/playlist?id=123456").source)
    }

    @Test
    fun mobileHostStillRejectsSongsAlbumsAndLookalikeDomains() {
        listOf(
            "https://y.music.163.com/m/song?id=123456",
            "https://y.music.163.com/m/album?id=123456",
            "https://y.music.163.com.attacker.example/m/playlist?id=123456",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { PlaylistImportLink.parse(url) }
        }
    }

}
