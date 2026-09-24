package com.leyu.melora.playback.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistImportLinkTest {
    @Test
    fun parsesSupportedPlaylistUrlMatrixAndPreservesOriginalValues() {
        val cases = listOf(
            "kw" to "https://m.kuwo.cn/h5app/playlist/2736267853?t=qqfriend",
            "kw" to "https://sub.kuwo.cn/newh5app/playlist/2736267853?from=share",
            "kw" to "https://kuwo.cn/playlist_detail/2736267853/",
            "kw" to "https://kuwo.cn/bodian/collection.html?playlistId=2736267853&from=share",
            "kg" to "https://www.kugou.com/songlist/gcid_abc123/",
            "kg" to "https://deep.sub.kugou.com/songlist/gcid_abc123/",
            "kg" to "https://www.kugou.com/share/a1b2c3d4e5.html",
            "kg" to "https://www.kugou.com/yy/special/single/1067062.html",
            "kg" to "https://m.kugou.com/yy/special/single/Abc_123.html?encryp=1&from=wx",
            "kg" to "https://www.kugou.com/yy/special/single/collection_3_509001524_10_0.html",
            "kg" to "https://m.kugou.com/share/?chain=AbCd_123&id=AbCd_123&from=wx",
            "kg" to "https://www.kugou.com/share/?id=AbCd_123",
            "kg" to "https://www.kugou.com/share/index.php?id=AbCd_123",
            "kg" to "https://m.kugou.com/schain/transfer?chain=AbCd_123&pagesize=30",
            "kg" to "https://t.kugou.com/a1B2c3D4",
            "kg" to "https://t1.kugou.com/a1B2c3D4",
            "kg" to "https://t2.kugou.com/a1B2c3D4",
            "kg" to "https://pc.service.kugou.com/yueku/v9/special/single/2546709-3-1111.html",
            "kg" to "https://pc.service.kugou.com/special/single/2546709.html",
            "tx" to "https://y.qq.com/n/m/detail/taoge/index.html?id=7217720898&ADTAG=qfshare",
            "tx" to "https://sub.y.qq.com/musicmac/v6/playlist/detail.html?id=7217720898&from=share",
            "tx" to "https://i.y.qq.com/n2/m/share/details/taoge.html?id=7217720898&ADTAG=qfshare",
            "tx" to "https://c.y.qq.com/base/fcgi-bin/u?__=AbCdEf",
            "tx" to "https://y.qq.com/n/yqq/playlist/7217720898.html",
            "tx" to "https://y.qq.com/n/ryqq/playlist/7707261125",
            "tx" to "https://y.qq.com/n/ryqq_v2/playlist/7707261125",
            "tx" to "https://i.y.qq.com/playlist/7217720898",
            "tx" to "https://c6.y.qq.com/base/fcgi-bin/u?__=AbCdEf",
            "tx" to "https://i2.y.qq.com/n3/other/pages/details/playlist.html?id=123456",
            "wy" to "https://music.163.com/#/playlist?id=123456",
            "wy" to "https://music.163.com/#/playlist/123456",
            "kw" to "https://m.kuwo.cn/newh5app/playlist_detail/123456?t=share",
            "kw" to "https://h5app.kuwo.cn/m/bodian/collection.html?playlistId=123456&source=5",
            "wy" to "https://deep.sub.music.163.com/m/playlist?id=123456&userid=700001&creatorId=700002#share",
            "wy" to "https://163cn.tv/a1B2c3",
            "mg" to "https://music.migu.cn/v3/music/playlist/184187437",
            "mg" to "https://m.music.migu.cn/v3/music/playlist/184187437",
            "mg" to "https://music.migu.cn/v5/music/playlist/184187437",
            "mg" to "https://music.migu.cn/v5/#/playlist?playlistId=221573417",
            "mg" to "https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=184187437&channel=0146921",
            "mg" to "http://c.migu.cn/00bTY6",
        )

        cases.forEach { (source, url) ->
            val parsed = PlaylistImportLink.parse("歌单分享：($url)。")
            assertEquals(url, parsed.value)
            assertEquals(source, parsed.source)
        }
    }

    @Test
    fun preservesTrackingParametersAndFragmentQueryTails() {
        val url = "https://y.music.163.com/m/playlist?id=123456&userid=700001&creatorId=700002#share?trace=abc"
        val parsed = PlaylistImportLink.parse("朋友分享的歌单：$url。")

        assertEquals("wy", parsed.source)
        assertEquals(url, parsed.value)
    }

    @Test
    fun deduplicatesRepeatedPlaylistLinksAfterSharePunctuation() {
        val url = "https://music.163.com/#/playlist?id=123456"
        val parsed = PlaylistImportLink.parse("分享歌单：$url。重复分享：($url)！")

        assertEquals("wy", parsed.source)
        assertEquals(url, parsed.value)
    }

    @Test
    fun reportsLocalizedPlatformName() {
        assertEquals("网易云音乐", PlaylistImportLink.parse("https://music.163.com/#/playlist?id=12").platformName)
    }

    @Test
    fun rejectsUnsupportedHostsSchemesAndNonPlaylistPaths() {
        listOf(
            "123456",
            "https://notkuwo.cn/playlist_detail/123456",
            "https://kuwo.cn.attacker.example/playlist_detail/123456",
            "https://kuwo.cn.evil/playlist_detail/123456",
            "https://kugou.com.attacker.example/songlist/gcid_abc123",
            "https://evil@y.qq.com/n/m/detail/taoge/index.html?id=123456",
            "https://music.163.com@attacker.example/#/playlist?id=123456",
            "https://music.163.com.attacker.example/#/playlist?id=123456",
            "https://163.com/playlist?id=123456",
            "https://qq.com/playlist/123456",
            "https://music.163.com/song?id=123456",
            "https://music.163.com/#/album?id=123456",
            "https://y.qq.com/n/ryqq/songDetail/123456",
            "https://y.qq.com/n/ryqq/albumDetail/123456",
            "https://y.qq.com/n/m/detail/taoge/index.html?id=not-a-number",
            "https://music.migu.cn/v5/music/song/184187437",
            "https://music.migu.cn/v5/music/album/184187437",
            "https://www.kugou.com/songlist/not-a-gcid",
            "https://m.kugou.com/schain/transfer?id=123456",
            "https://www.kugou.com/yy/special/single/not-a-list.html",
            "https://www.kugou.com/yy/special/single/not-a-list.html?encryp=0",
            "https://pc.service.kugou.com/yueku/v9/special/song/2546709-3-1111.html",
            "ftp://music.163.com/playlist/123456",
            "javascript:https://music.163.com/#/playlist?id=123456",
        ).forEach { text ->
            assertThrows("Should reject: $text", IllegalArgumentException::class.java) {
                PlaylistImportLink.parse(text)
            }
        }
    }

    @Test
    fun rejectsMultipleDistinctPlaylistLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistImportLink.parse(
                "https://y.qq.com/n/yqq/playlist/1.html https://music.163.com/#/playlist?id=2",
            )
        }
    }
}
