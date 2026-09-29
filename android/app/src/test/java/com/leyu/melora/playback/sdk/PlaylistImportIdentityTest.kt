package com.leyu.melora.playback.sdk

import org.junit.Assert.*
import org.junit.Test

class PlaylistImportIdentityTest {
    private fun identity(url: String) = PlaylistImportLink.parse(url).identity()

    @Test fun longLinkFamiliesUseOnlyProvenIdsAndNamespaces() {
        val groups = listOf(
            listOf("https://music.163.com/#/playlist?id=123", "https://music.163.com/#/playlist/123", "https://y.music.163.com/m/playlist?id=123&userid=5", "https://music.163.com/playlist/123/name"),
            listOf("https://m.kuwo.cn/h5app/playlist/123?t=qqfriend", "https://kuwo.cn/playlist_detail/123/", "https://m.kuwo.cn/newh5app/playlist_detail/123?from=wx"),
            listOf("https://kuwo.cn/bodian/collection.html?playlistId=123&source=4", "https://h5app.kuwo.cn/m/bodian/collection.html?source=4&playlistId=123&uid=9"),
            listOf("https://music.migu.cn/v3/music/playlist/123", "https://music.migu.cn/v5/music/playlist/123/", "https://music.migu.cn/v5/#/playlist?playlistId=123", "https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=123&channel=4"),
            listOf("https://www.kugou.com/yy/special/single/123.html", "https://pc.service.kugou.com/special/single/123.html?from=wx"),
            listOf("https://www.kugou.com/songlist/gcid_AbC123/", "https://m.kugou.com/songlist/gcid_AbC123.html?from=share"),
            listOf("https://www.kugou.com/songlist/collection_123.html", "https://m.kugou.com/share?global_collection_id=collection_123&from=wx"),
            listOf("https://www.kugou.com/share/AbCd1234.html", "https://m.kugou.com/share/?chain=AbCd1234&from=wx", "https://m.kugou.com/schain/transfer?chain=AbCd1234", "https://www.kugou.com/share/index.php?id=AbCd1234")
        )
        groups.forEach { urls ->
            urls.forEach { url ->
                assertEquals(url, identity(urls.first()), identity(url))
                assertTrue(url, identity(url).isCanonical)
            }
        }
        assertEquals(groups.size, groups.map { identity(it.first()) }.distinct().size)
        assertNotEquals(identity("https://kuwo.cn/bodian/collection.html?playlistId=123&source=4"), identity("https://kuwo.cn/bodian/collection.html?playlistId=123&source=5"))
        assertNotEquals(identity("https://www.kugou.com/songlist/gcid_AbC123"), identity("https://www.kugou.com/share?global_collection_id=AbC123"))
    }

    @Test fun opaqueOrAmbiguousUrlsFallBackToExactOriginalWithoutGuessing() {
        listOf("https://c.y.qq.com/base/fcgi-bin/u?__=AbCdEf", "https://163cn.tv/a1B2c3", "http://c.migu.cn/00bTY6",
            "https://t2.kugou.com/a1B2c3D4", "https://m.kugou.com/yy/special/single/Abc_123.html?encryp=1",
            "https://pc.service.kugou.com/yueku/v9/special/single/2546709-3-1111.html",
            "https://y.qq.com/w/taoge.html?id=123&id=456").forEach { url ->
            assertFalse(url, identity(url).isCanonical)
            assertEquals(identity(url), identity(url))
            assertNotEquals(identity(url), identity(url + (if ('?' in url) "&" else "?") + "tracking=changed"))
        }
    }

    @Test fun kugouEncodedGlobalCannotFallThroughToADifferentSpecialPlaylist() {
        val url = "https://www.kugou.com/yy/special/single/123.html?global_collection_id=%34%35%36"
        assertExactFallback(url)
        assertNotEquals(identity("https://www.kugou.com/yy/special/single/123.html"), identity(url))
    }

    @Test fun kugouQueryNamesAreCaseSensitiveLikeTheSdk() {
        val special = "https://www.kugou.com/yy/special/single/123.html"
        assertEquals(identity(special), identity("$special?GLOBAL_COLLECTION_ID=456"))
        assertEquals(identity(special), identity("$special?ENCRYP=1"))
        assertEquals(identity(special), identity("$special?unrelated=?global_collection_id=456"))
        assertEquals(identity("https://www.kugou.com/share/index.php?id=AbCd1234"),
            identity("https://www.kugou.com/share/index.php?CHAIN=Other123&id=AbCd1234"))
    }

    @Test fun kugouEncodedEncryptionFlagCannotIdentifyThePlainSpecialId() {
        val special = "https://www.kugou.com/yy/special/single/123.html"
        listOf("%31", "%FF", "%C0%AF", "%ED%A0%80", "%2531").forEach { encoded ->
            assertExactFallback("$special?encryp=$encoded")
        }
        // decodeURIComponent does not turn a plus into a space or the string "1".
        assertEquals(identity(special), identity("$special?encryp=+1"))
        // Syntactically invalid percent escapes are already rejected at the import boundary.
        assertThrows(IllegalArgumentException::class.java) { PlaylistImportLink.parse("$special?encryp=%ZZ") }
        assertThrows(IllegalArgumentException::class.java) { PlaylistImportLink.parse("$special?global_collection_id=%") }
    }

    @Test fun kugouUncertainHigherPriorityQueryNeverSelectsALowerPriorityId() {
        val special = "https://www.kugou.com/yy/special/single/123.html"
        listOf("%FF", "%C0%AF", "%ED%A0%80", "%2534", "A+B", "A%2BB").forEach { value ->
            assertExactFallback("$special?global_collection_id=$value")
        }
        assertExactFallback("$special?from=wx&amp;global_collection_id=456")
        assertExactFallback("https://www.kugou.com/share/AbCd1234.html?chain=%45%46%47%48")
    }

    @Test fun kugouRepeatedRelevantQueriesStayExactWithoutLosingPriority() {
        val special = "https://www.kugou.com/yy/special/single/123.html"
        listOf("global_collection_id=456&global_collection_id=789", "global_collection_id=456&global_collection_id=456",
            "encryp=1&encryp=0", "encryp=0&encryp=1").forEach { query -> assertExactFallback("$special?$query") }
        assertExactFallback("https://www.kugou.com/share/AbCd1234.html?chain=Other123&chain=AbCd1234")
        // The SDK returns global before even reading encryp; irrelevant ambiguity cannot change it.
        assertEquals(identity("https://www.kugou.com/share?global_collection_id=456"),
            identity("$special?global_collection_id=456&encryp=%FF&encryp=1"))
    }

    @Test fun kugouCollectionPrefixIsCaseSensitiveButSharePathIsNot() {
        assertEquals(identity("https://www.kugou.com/share?chain=COLLECTION_123"),
            identity("https://www.kugou.com/share/COLLECTION_123.html"))
    }

    @Test fun otherPlatformsCannotPreferCaseInsensitivePathsOverTheSdkFragmentRoute() {
        assertEquals(identity("https://music.163.com/#/playlist/456"),
            identity("https://music.163.com/PLAYLIST/123#/playlist/456"))
        assertEquals(identity("https://music.migu.cn/v5/#/playlist?playlistId=456"),
            identity("https://music.migu.cn/v3/MUSIC/playlist/123#/playlist?playlistId=456"))
        assertEquals(identity("https://music.migu.cn/v5/#/playlist?playlistId=456"),
            identity("https://h5.nf.migu.cn/app/v4/p/share/playlist/INDEX.html?id=123#/playlist?playlistId=456"))
    }

    @Test fun neteaseTrailingSlashPathQueryTakesPriorityOverFragment() {
        val expected = identity("https://music.163.com/playlist?id=123")
        for (path in listOf("/playlist/", "/m/playlist/", "/other/playlist/")) {
            val url = "https://music.163.com$path?id=123#/playlist?id=456"
            assertEquals(url, expected, identity(url))
            assertNotEquals(identity("https://music.163.com/playlist?id=456"), identity(url))
        }
    }

    @Test fun neteaseUnrecognizedQueryCannotOverrideTheSdkFragmentPlaylist() {
        assertExactFallback("https://music.163.com/playlist?ID=123#/playlist?id=456")
        assertExactFallback("https://music.163.com/playlist?other=?id=123#/playlist?id=456")
    }

    @Test fun bodianUnboundedSdkParameterMatchesCannotBeMistakenForAnotherPlaylist() {
        assertExactFallback("https://kuwo.cn/bodian/collection.html?notplaylistId=456&playlistId=123&source=4")
        assertExactFallback("https://kuwo.cn/bodian/collection.html?playlistId=123&resource=5&source=4")
    }

    @Test fun kuwoSdkRoutesAndGreedyPathMatchesInTheWholeUrlStayConservative() {
        assertExactFallback("https://m.kuwo.cn/h5app/playlist/123?next=/playlist/456")
        assertExactFallback("https://m.kuwo.cn/h5app/playlist/123?next=/bodian/&playlistId=456&source=4")
    }

    private fun assertExactFallback(url: String) {
        assertEquals(url, PlaylistImportIdentity(PlaylistImportLink.parse(url).source, "exact-url", url), identity(url))
    }

    @Test fun qqTrackingAndLongLinkAliasesUseThePlaylistIdWithoutChangingSource() {
        val original = "https://i.y.qq.com/n2/m/share/details/taoge.html?id=7217720898&ADTAG=first&from=wx"
        val parsed = PlaylistImportLink.parse(original)
        assertEquals(identity("https://y.qq.com/n/ryqq_v2/playlist/7217720898"), parsed.identity())
        assertEquals(identity(original.replace("first", "second")), parsed.identity())
        assertEquals(original, parsed.value)
        assertTrue(parsed.identity().isCanonical)
        assertNotEquals(identity("https://music.163.com/playlist?id=7217720898"), parsed.identity())
    }
}
