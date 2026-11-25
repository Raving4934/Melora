package com.leyu.melora.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SearchScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val keyword = "search-fixture-${System.nanoTime()}"
    private val platform = PlatformSource.Kuwo.id
    private val hotKey = "searchHot.Song.$platform"
    private val previousPlatform = MeloraSettings.searchPlatform.value
    private val previousHistory = UserLibrary.searchHistory.value
    private val recommendationKey = "searchRecommend.songs.$platform.${previousHistory.take(3).hashCode()}"
    private val previousHotWords = OnlineCache.peek<List<String>>(hotKey)
    private val previousRecommendations = OnlineCache.peek<List<OnlineSong>>(recommendationKey)
    private val restoration = StateRestorationTester(compose)
    private val libraryFile = UserLibrary::class.java.getDeclaredField("file").apply { isAccessible = true }
    private var previousLibraryFile: Any? = null
    private lateinit var libraryDirectory: File

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        libraryDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "search-library").toFile()
        previousLibraryFile = libraryFile.get(UserLibrary)
        libraryFile.set(UserLibrary, File(libraryDirectory, "user-library.json"))
        MeloraSettings.updateSearchPlatform(platform)
        OnlineCache.put(hotKey, listOf(keyword))
        OnlineCache.put(recommendationKey, emptyList<OnlineSong>())
    }

    @After fun cleanup() {
        OnlineCache.clear("search:songs:$platform:$keyword")
        if (previousHotWords == null) OnlineCache.clear(hotKey)
        else OnlineCache.put(hotKey, previousHotWords)
        if (previousRecommendations == null) OnlineCache.clear(recommendationKey)
        else OnlineCache.put(recommendationKey, previousRecommendations)
        MeloraSettings.updateSearchPlatform(previousPlatform)
        UserLibrary.searchHistory.value = previousHistory
        libraryFile.set(UserLibrary, previousLibraryFile)
        libraryDirectory.deleteRecursively()
    }

    @Test fun failedNextPageKeepsRowsAndRetryAppendsTheSamePage() {
        val titles = (1..30).map { "已加载的歌曲 $it" }
        cachePage(1, *titles.toTypedArray())
        val release = CompletableDeferred<Unit>()
        val failedPage = holdNextPage {
            release.await()
            throw IOException("测试分页网络中断")
        }
        try {
            openSearch()
            compose.awaitStable(compose.onNodeWithText(titles.first()))
            swipeUntilVisible("正在加载…")
            compose.runOnIdle { release.complete(Unit) }

            val retry = compose.onNodeWithText("加载失败，点击重试")
            compose.awaitStable(retry)
            compose.onNodeWithText(titles.last()).assertIsDisplayed()
            cachePage(2, "恢复加载的歌曲")
            retry.performClick()
            swipeUntilVisible("恢复加载的歌曲")
            resultsList().performScrollToIndex(0)
            compose.onNodeWithText(titles.first()).assertIsDisplayed()
            compose.onNodeWithText("加载失败，点击重试").assertDoesNotExist()
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(5_000) { failedPage.await() } }
        }
    }

    @Test fun filteredFirstPageAutomaticallyContinuesAfterRecreation() {
        cachePage(1)
        cachePage(2, "下一页匹配歌曲")
        openSearch()
        compose.awaitStable(compose.onNodeWithText("下一页匹配歌曲"))

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains(keyword)
        compose.awaitStable(compose.onNodeWithText("下一页匹配歌曲"))
    }

    @Test fun swipingPastConsecutiveFilteredPagesFindsTheNextMatch() {
        cachePage(1, allPages = 3)
        cachePage(2, allPages = 3)
        cachePage(3, "第三页匹配歌曲", allPages = 3)
        openSearch()
        compose.awaitStable(compose.onNodeWithText("当前页暂无匹配内容，上滑继续查找"))
        compose.onNodeWithText("第三页匹配歌曲").assertDoesNotExist()
        resultsList().performTouchInput { swipeUp() }
        compose.awaitStable(compose.onNodeWithText("第三页匹配歌曲"))
    }

    @Test fun newKeywordCancelsThePreviousShortResultsPage() {
        val newKeyword = "$keyword-new"
        cachePage(1, "旧关键词结果")
        cachePage(1, "新关键词结果", query = newKeyword, allPages = 1)
        val release = CompletableDeferred<Unit>()
        val oldPage = holdNextPage {
            release.await()
            songPage(2, listOf("旧关键词的迟到结果"))
        }
        try {
            openSearch()
            compose.awaitStable(compose.onNodeWithText("正在加载…"))
            compose.onNodeWithText("旧关键词结果").assertIsDisplayed()

            val search = compose.onNode(hasSetTextAction())
            search.performTextReplacement(newKeyword)
            search.performImeAction()
            compose.awaitStable(compose.onNodeWithText("新关键词结果"))
            release.complete(Unit)
            runBlocking { withTimeout(5_000) { oldPage.await().getOrThrow() } }

            compose.onNodeWithText("新关键词结果").assertIsDisplayed()
            compose.onNodeWithText("旧关键词的迟到结果").assertDoesNotExist()
            compose.onNodeWithText("正在加载…").assertDoesNotExist()
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(5_000) { oldPage.await() } }
        }
    }

    @Test fun playlistGridLoadsOnScrollAndStopsAtTheLastPage() {
        val pages = showPlaylists(1..24)
        compose.awaitStable(compose.onNodeWithText("测试歌单 1"))
        resultsList().performTouchInput { swipeUp(endY = centerY, durationMillis = 650) }
        compose.runOnIdle { assertEquals(emptyList<Int>(), pages.requests) }

        swipeUntilVisible("正在加载…")
        repeat(2) { resultsList().performTouchInput { swipeUp(durationMillis = 160) } }
        compose.onNodeWithText("测试歌单 24").assertIsDisplayed()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(2), pages.requests)
            pages.complete(SearchResults(2, playlists = (25..28).map(::playlist), hasMore = false))
        }

        swipeUntilVisible("测试歌单 28")
        resultsList().performTouchInput { swipeUp() }
        compose.onNodeWithText("测试歌单 28").assertIsDisplayed()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(2), pages.requests)
            assertFalse(pages.list.canScrollForward)
        }
    }

    @Test fun shortPlaylistGridWaitsForASwipeAfterADuplicatePage() {
        val pages = showPlaylists(1..1)
        val loading = compose.onNodeWithText("正在加载…")
        val first = compose.onNodeWithText("测试歌单 1")
        compose.awaitStable(loading)
        compose.runOnIdle {
            assertEquals(listOf(2), pages.requests)
            pages.complete(SearchResults(2, playlists = listOf(playlist(1)), hasMore = true))
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithText("测试歌单 1").assertCountEquals(1)
        loading.assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(2), pages.requests) }

        resultsList().performTouchInput { swipeUp() }
        compose.awaitStable(loading)
        compose.runOnIdle {
            assertEquals(listOf(2, 3), pages.requests)
            pages.complete(SearchResults(3, playlists = listOf(playlist(2)), hasMore = false))
        }
        compose.awaitStable(compose.onNodeWithText("测试歌单 2"))
        first.assertIsDisplayed()
        resultsList().performTouchInput { swipeUp() }
        loading.assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(2, 3), pages.requests) }
    }

    private fun showPlaylists(firstPage: IntRange): PlaylistPages {
        val pages = PlaylistPages(firstPage.map(::playlist))
        compose.setContent {
            MeloraTheme {
                SongListStateProvider {
                    Box(Modifier.size(350.dp, 440.dp)) {
                        SearchResultsContent(
                            listState = pages.list,
                            category = SearchCategory.Playlist,
                            submitted = keyword,
                            selectedPlatform = PlatformSource.Kuwo,
                            results = pages.results.value,
                            loading = pages.loading.value,
                            error = null,
                            favoriteUids = emptySet(),
                            onLoadPage = pages::requestNext,
                            onOpenPlaylist = {},
                            onMoreSong = {},
                            onPlaySong = {},
                        )
                    }
                }
            }
        }
        return pages
    }

    private class PlaylistPages(firstPage: List<OnlinePlaylist>) {
        val results = mutableStateOf(SearchResults(1, playlists = firstPage, hasMore = true))
        val loading = mutableStateOf(false)
        val list = LazyListState()
        val requests = mutableListOf<Int>()

        fun requestNext() {
            requests += results.value.page + 1
            loading.value = true
        }

        fun complete(page: SearchResults) {
            results.value = results.value.withPage(page)
            loading.value = false
        }
    }

    private fun openSearch() {
        restoration.setContent {
            MeloraTheme { SongListStateProvider { SearchScreen(SearchCategory.Song, {}, {}) } }
        }
        compose.awaitStable(compose.onNodeWithText(keyword))
        compose.onNodeWithText(keyword).performClick()
    }

    private fun resultsList() = compose.onNode(hasScrollToIndexAction())

    private fun swipeUntilVisible(text: String) {
        val row = compose.onNodeWithText(text)
        val list = resultsList()
        repeat(16) {
            if (row.isDisplayed()) return
            list.performTouchInput { swipeUp(durationMillis = 220) }
        }
        row.assertIsDisplayed()
    }

    private fun holdNextPage(load: suspend () -> SongPage) =
        CoroutineScope(Dispatchers.Default).async {
            runCatching { OnlineCache.refresh(songSearchCacheKey(platform, keyword, 2), -1, load = load) }
        }.also {
            compose.waitUntil(5_000) {
                OnlineCache.pendingRefresh<SongPage>(songSearchCacheKey(platform, keyword, 2)) != null
            }
        }

    private fun cachePage(number: Int, vararg titles: String, query: String = keyword, allPages: Int = 2) {
        OnlineCache.put(songSearchCacheKey(platform, query, number), songPage(number, titles.toList(), query, allPages))
    }

    private fun songPage(number: Int, titles: List<String>, query: String = keyword, allPages: Int = 2): SongPage {
        val songs = titles.mapIndexed { index, title ->
            OnlineSong(JSONObject().put("source", platform).put("songmid", "$query-$number-$index").put("name", title))
        }
        return SongPage(songs, total = 0, page = number, allPage = allPages, rawCount = songs.size.coerceAtLeast(1))
    }

    private fun playlist(index: Int) =
        OnlinePlaylist(JSONObject().put("source", platform).put("id", index.toString()).put("name", "测试歌单 $index"))
}
