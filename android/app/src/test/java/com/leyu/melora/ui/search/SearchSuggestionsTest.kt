package com.leyu.melora.ui.search

import android.content.ContextWrapper
import com.leyu.melora.playback.PlatformSource
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.PlaylistPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSuggestionsTest {
    private val context = ContextWrapper(null)
    private val keyword = "melora-suggestion-regression"

    @After fun cleanup() {
        listOf("tips", "playlists", "books").forEach { kind ->
            (PlatformSource.musicIds + PlatformSource.All.id).forEach { source -> OnlineCache.clear("search:$kind:$source:$keyword") }
        }
    }

    @Test fun songSuggestionsUseTheSelectedSourceAndKeepEightUniqueNonblankLabels() = runBlocking {
        val labels = (1..10).map { "歌曲 $it - 歌手" }
        OnlineCache.put("search:tips:kw:$keyword", listOf("酷我联想"))
        OnlineCache.put("search:tips:tx:$keyword", listOf("", " ", " ${labels.first()} ", labels.first()) + labels.drop(1))

        assertEquals(labels.take(8), searchSuggestions(context, SearchCategory.Song, PlatformSource.Tencent, keyword))
        assertEquals(listOf("酷我联想"), searchSuggestions(context, SearchCategory.Song, PlatformSource.Kuwo, keyword))
        assertEquals(listOf("酷我联想"), searchSuggestions(context, SearchCategory.Song, PlatformSource.All, keyword))
    }

    @Test fun playlistAndAudiobookSuggestionsUseTheirOwnCatalogs() = runBlocking {
        OnlineCache.put("search:tips:tx:$keyword", listOf("歌曲 - 歌手"))
        cachePlaylists("tx", "企鹅歌单")
        cachePlaylists("kw", "酷我歌单")
        val books = KwBookApi.BookPage(listOf(playlist("kw", "book_album_100", "有声专辑")), hasMore = false)
        OnlineCache.put("search:books:kw:$keyword:1", books)

        assertEquals(listOf("企鹅歌单"), searchSuggestions(context, SearchCategory.Playlist, PlatformSource.Tencent, keyword))
        assertEquals(listOf("酷我歌单"), searchSuggestions(context, SearchCategory.Playlist, PlatformSource.Kuwo, keyword))
        assertEquals(listOf("有声专辑"), searchSuggestions(context, SearchCategory.Audiobook, PlatformSource.Tencent, keyword))
        assertEquals(listOf("有声专辑"), searchSuggestions(context, SearchCategory.Audiobook, PlatformSource.Kugou, keyword))
    }

    @Test fun noCatalogMatchesDoesNotFallBackToSongSuggestions() = runBlocking {
        OnlineCache.put("search:tips:tx:$keyword", listOf("歌曲 - 歌手"))
        cachePlaylists("tx")
        OnlineCache.put("search:books:kw:$keyword:1", KwBookApi.BookPage(emptyList(), hasMore = false))

        assertEquals(emptyList<String>(), searchSuggestions(context, SearchCategory.Playlist, PlatformSource.Tencent, keyword))
        assertEquals(emptyList<String>(), searchSuggestions(context, SearchCategory.Audiobook, PlatformSource.Tencent, keyword))
    }

    @Test fun aggregatedPlaylistSuggestionsIncludeSeveralSourcesAndDeduplicateNames() = runBlocking {
        PlatformSource.musicIds.forEach { cachePlaylists(it) }
        cachePlaylists("kg", "共同歌单", "  ", "酷狗歌单")
        cachePlaylists("tx", "共同歌单", "企鹅歌单")

        val suggestions = searchSuggestions(context, SearchCategory.Playlist, PlatformSource.All, keyword)
        assertEquals(3, suggestions.size)
        assertEquals(setOf("共同歌单", "酷狗歌单", "企鹅歌单"), suggestions.toSet())
    }

    @Test fun fastSourcesAppearWhileASlowSourceIsPendingAndKeepTheirPosition() = runBlocking {
        PlatformSource.musicIds.filterNot { it == "kw" }.forEach { cachePlaylists(it) }
        cachePlaylists("tx", "先到的歌单")
        val finish = CompletableDeferred<Unit>()
        val slow = async(start = CoroutineStart.UNDISPATCHED) {
            OnlineCache.refresh("search:playlists:kw:$keyword:1", -1) {
                finish.await()
                PlaylistPage(listOf(playlist("kw", "kw-1", "慢源歌单")), total = 1, page = 1)
            }
        }
        val preview = CompletableDeferred<List<String>>()
        val suggestions = async {
            searchSuggestions(context, SearchCategory.Playlist, PlatformSource.All, keyword) { preview.complete(it) }
        }
        try {
            assertEquals(listOf("先到的歌单"), withTimeout(5_000) { preview.await() })
            finish.complete(Unit)
            assertEquals(listOf("先到的歌单", "慢源歌单"), withTimeout(5_000) { suggestions.await() })
            val page = searchResultPage(context, SearchCategory.Playlist, PlatformSource.All, keyword, 1)
            assertEquals(listOf("慢源歌单", "先到的歌单"), page.playlists.map(OnlinePlaylist::name))
        } finally {
            finish.complete(Unit)
            slow.await()
        }
    }

    private fun cachePlaylists(source: String, vararg titles: String) {
        val items = titles.mapIndexed { index, title -> playlist(source, "fixture-$index", title) }
        OnlineCache.put("search:playlists:$source:$keyword:1", PlaylistPage(items, total = items.size, page = 1))
    }

    private fun playlist(source: String, id: String, title: String) = OnlinePlaylist(
        JSONObject().put("source", source).put("id", id).put("name", title),
    )
}
