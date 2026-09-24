package com.leyu.melora.ui.audiobook

import androidx.compose.runtime.saveable.SaverScope
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlinePlaylist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudiobookNavigationSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test fun rankMetadataRoundTripsWithoutCatalogItems() {
        val rank = KwBookApi.BookRankTab(
            "20", "VIP会员榜", listOf(KwBookApi.BookTag("128", "总榜"), KwBookApi.BookTag("130", "新书榜")),
        )
        val saved = with(BookRankTabSaver) { scope.save(rank) } ?: error("rank was not saved")

        assertEquals(rank, BookRankTabSaver.restore(saved))
        assertEquals(6, (saved as List<*>).size)
    }

    @Test fun detailSaverRestoresOnlyStableAlbumMetadataAndSearchRoute() {
        val original = OnlinePlaylist(JSONObject()
            .put("id", "book_album_fixture").put("name", "测试长篇作品")
            .put("source", "kw").put("kind", "book").put("author", "测试主播")
            .put("img", "cover.jpg").put("description", "not saved"))
        val saved = with(BookDetailSaver) { scope.save(BookDetail.Album(original)) }
            ?: error("album was not saved")
        val album = (BookDetailSaver.restore(saved) as BookDetail.Album).playlist
        assertEquals("book_album_fixture", album.id)
        assertEquals("测试长篇作品", album.name)
        assertEquals("kw", album.source)
        assertEquals("测试主播", album.author)
        assertEquals("cover.jpg", album.img)
        assertFalse(album.raw.has("description"))

        val search = with(BookDetailSaver) { scope.save(BookDetail.Search()) }
            ?: error("search route was not saved")
        assertEquals(BookDetail.Search::class, BookDetailSaver.restore(search)!!::class)
    }
}
