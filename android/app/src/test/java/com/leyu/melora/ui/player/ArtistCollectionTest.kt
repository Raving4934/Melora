package com.leyu.melora.ui.player

import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.UiTrack
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtistCollectionTest {
    @Test fun audiobookCreatorOpensAlbumsInsteadOfSongSearch() {
        val collection = artistCollection(track(book = true), "测试主播")
        assertTrue(collection.isBookAuthor)
        assertEquals("测试主播", collection.artistName)
        assertEquals("作者 / 主播 · 听书作品", collection.subtitle)
        assertNull(collection.albumName)
        assertNull(collection.bookAlbumId)
    }

    @Test fun musicArtistKeepsOriginalSongSearchAndPlatform() {
        val collection = artistCollection(track(book = false).copy(source = "wy"), "测试歌手")
        assertFalse(collection.isBookAuthor)
        assertFalse(collection.preferBook)
        assertEquals("wy", collection.source)
        assertEquals("歌手 · 全部歌曲", collection.subtitle)
    }

    @Test fun audiobookAlbumStillOpensChapterList() {
        val album = SongsCollection("作品", "听书", "作品", "kw", albumName = "作品", preferBook = true)
        assertFalse(album.isBookAuthor)
        assertFalse(album.copy(albumName = null, bookAlbumId = "book_album_fixture").isBookAuthor)
    }

    @Test fun localAndMissingPlatformKeepExistingMetadataFallback() {
        assertEquals("kw", artistCollection(track(false).copy(source = "local"), "作者").source)
        assertEquals("kw", artistCollection(null, "作者").source)
        assertFalse(artistCollection(null, "作者").isBookAuthor)
    }

    @Test fun knownBookAlbumUsesSharedDirectoryWithCanonicalIdentity() {
        val album = SongsCollection("作品", "听书", "作品", "kw", albumName = "作品",
            bookAlbumId = "kw:book_album_123", preferBook = true)
        val target = playerAlbumDestination(album, emptyList()) as FullPlayerDetailTarget.Playlist
        assertEquals("book_album_123", target.value.id)
        assertEquals("kw", target.value.source)
        assertTrue(target.value.isBookAlbum)
    }

    @Test fun missingBookIdRequiresARealMatchingSearchResult() {
        val album = SongsCollection("作品", "听书", "作品", "kw", albumName = "作品", preferBook = true)
        assertNull(playerAlbumDestination(album, emptyList()))
        val match = OnlinePlaylist(JSONObject().put("source", "kw").put("kind", "book")
            .put("id", "book_album_456").put("name", "作品"))
        val target = playerAlbumDestination(album, listOf(match)) as FullPlayerDetailTarget.Playlist
        assertEquals("book_album_456", target.value.id)
        assertNull(playerAlbumDestination(album.copy(bookAlbumId = "null"), emptyList()))
    }

    @Test fun musicAlbumKeepsItsCollectionAndBookAuthorIsNotAnAlbum() {
        val album = SongsCollection("音乐专辑", "专辑", "音乐专辑", "wy", albumName = "音乐专辑")
        assertEquals(FullPlayerDetailTarget.Collection(album), playerAlbumDestination(album, emptyList()))
        assertNull(playerAlbumDestination(artistCollection(track(true), "主播"), emptyList()))
    }

    private fun track(book: Boolean) = UiTrack("fixture", "测试章节", "测试主播", "测试作品", source = "kw",
        raw = JSONObject().put("isBookChapter", book))
}
