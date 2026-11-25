package com.leyu.melora.playback.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineContentBoundaryTest {
    @Test
    fun lyricMappingPreservesEveryLyricsFieldAndSource() {
        val lyric = OnlineLyric.from(
            JSONObject()
                .put("lyric", "ordinary")
                .put("lxlyric", "word-by-word")
                .put("tlyric", "translation")
                .put("rlyric", "romanization")
                .put("source", "ignored"),
            source = "kw",
        )

        assertEquals("ordinary", lyric.lyric)
        assertEquals("word-by-word", lyric.lxlyric)
        assertEquals("translation", lyric.tlyric)
        assertEquals("romanization", lyric.rlyric)
        assertEquals("kw", lyric.source)
    }

    @Test
    fun lyricMappingDefaultsMissingFieldsToEmptyStrings() {
        val lyric = OnlineLyric.from(JSONObject(), source = "")

        assertEquals("", lyric.lyric)
        assertEquals("", lyric.lxlyric)
        assertEquals("", lyric.tlyric)
        assertEquals("", lyric.rlyric)
        assertEquals("", lyric.source)
    }

    @Test
    fun hasLyricsAcceptsOrdinaryOrWordByWordLyrics() {
        assertTrue(OnlineLyric.from(JSONObject().put("lyric", "ordinary"), source = "tx").hasLyrics)
        assertTrue(OnlineLyric.from(JSONObject().put("lxlyric", "word-by-word"), source = "wy").hasLyrics)
        assertFalse(OnlineLyric.from(JSONObject(), source = "").hasLyrics)
    }

    @Test
    fun identifiesAudiobookChapterFromCanonicalMarker() {
        val music = song("music")
        val chapter = song("chapter", isBookChapter = true)

        assertFalse(music.isBookChapter)
        assertTrue(chapter.isBookChapter)
    }

    @Test
    fun musicCatalogExcludesAudiobookChapters() {
        val music = song("music")
        val chapter = song("chapter", isBookChapter = true)

        assertEquals(listOf(music), listOf(music, chapter).musicOnly())
    }

    @Test
    fun paginationUsesMetadataRatherThanAssumingThirtySongsPerPage() {
        val shortPage = SongPage(listOf(song("one")), total = 2, page = 1, allPage = 2)
        assertTrue(shortPage.hasMore())
        assertFalse(shortPage.copy(page = 2).hasMore(2))
        val fullLastPage = shortPage.copy(entries = List(30) { song("$it") }, total = 30, allPage = 1,
            rawCount = 30, receivedCount = 30)
        assertFalse(fullLastPage.hasMore())
        assertTrue(fullLastPage.copy(total = 0, allPage = 0).hasMore())
        assertFalse(fullLastPage.copy(entries = emptyList(), allPage = 2, rawCount = 0, receivedCount = 0).hasMore())
        assertTrue(shortPage.copy(allPage = 0).hasMore(1))
        assertFalse(shortPage.copy(allPage = 0).hasMore(2))
    }

    @Test
    fun mergedSongSnapshotKeepsRequestedCursorAndStopsDuplicatePages() {
        val firstPage = SongPage(
            entries = List(30) { song("first-$it") },
            total = 60,
            page = 1,
            allPage = 2,
        )
        val secondPage = SongPage(
            entries = List(30) { song("second-$it") },
            total = 60,
            page = 1,
            allPage = 2,
        )

        assertEquals(2, secondPage.forRequestedPage(2).page)
        val merged = firstPage.append(secondPage, requestedPage = 2)
        assertEquals(2, merged.page)
        assertEquals(60, merged.list.size)
        assertFalse(merged.hasMore())

        val duplicate = merged.append(secondPage, requestedPage = 3)
        assertEquals(3, duplicate.page)
        assertEquals(60, duplicate.list.size)
        assertFalse(duplicate.hasMore())
    }

    @Test
    fun unknownTotalKeepsPagingOnlyWhenTheNextPageAddsSongs() {
        val firstPage = SongPage(List(30) { song("unknown-$it") }, 0, 1, 0)
        val nextPage = SongPage(List(30) { song("unknown-${it + 30}") }, 0, 1, 0)

        val merged = firstPage.append(nextPage, requestedPage = 2)
        assertTrue(merged.hasMore())

        val duplicate = merged.append(nextPage, requestedPage = 3)
        assertFalse(duplicate.hasMore())
    }

    @Test fun repeatedPlaylistCatalogPageKeepsExistingCardsAndStopsLoadingMore() {
        val cards = List(30) { playlist(it) }
        val firstPage = PlaylistPage(cards, total = 0, page = 1).toCatalogSnapshot(1, previous = null)
        assertTrue(firstPage.hasMore)

        val repeated = PlaylistPage(cards, total = 0, page = 1).toCatalogSnapshot(2, previous = firstPage)
        assertEquals(cards, repeated.list)
        assertEquals(2, repeated.page)
        assertFalse(repeated.hasMore)
    }

    @Test fun catalogUsesProviderPageBoundariesAndTheRawItemCount() {
        val cards = List(12) { playlist(it) }
        val first = PlaylistPage(cards, total = 45, page = 1, pageSize = 36, rawCount = 36)
        assertTrue(first.toCatalogSnapshot(1, previous = null).hasMore)
        assertTrue(first.copy(total = 0).toCatalogSnapshot(1, previous = null).hasMore)
        assertFalse(first.copy(hasMore = false).toCatalogSnapshot(1, previous = null).hasMore)
        val next = first.copy(list = List(9) { playlist(36 + it) }, page = 2, rawCount = 9)
        assertFalse(next.toCatalogSnapshot(2, previous = first.toCatalogSnapshot(1, previous = null)).hasMore)
    }

    @Test fun catalogWireFieldsReachTheRepositoryAndPagingDecision() {
        val parse = OnlineRepository::class.java.getDeclaredMethod("playlistPage", JSONObject::class.java)
            .apply { isAccessible = true }
        val returnedCards = JSONArray().put(playlist(1).raw.put("source", "mg")).put(JSONObject())
        val response = JSONObject()
            .put("kind", "playlists")
            .put("source", "mg")
            .put("list", returnedCards)
            .put("page", 2)
            .put("total", 99_999)
            .put("limit", 30)
        for (hasMore in listOf(true, false)) {
            response.put("hasMore", hasMore)
            val result = parse.invoke(OnlineRepository, response) as PlaylistPage

            assertEquals(2, result.page)
            assertEquals(99_999, result.total)
            assertEquals(30, result.pageSize)
            assertEquals(2, result.rawCount)
            assertEquals("mg", result.list.single().source)
            assertEquals(hasMore, result.hasMore)
            assertEquals(hasMore, result.toCatalogSnapshot(result.page, previous = null).hasMore)
        }
    }

    private fun playlist(id: Int) =
        OnlinePlaylist(JSONObject().put("source", "kw").put("id", "list-$id").put("name", "歌单 $id"))

    private fun song(id: String, isBookChapter: Boolean = false): OnlineSong = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", id)
            .put("singer", "artist")
            .put("isBookChapter", isBookChapter),
    )
}
