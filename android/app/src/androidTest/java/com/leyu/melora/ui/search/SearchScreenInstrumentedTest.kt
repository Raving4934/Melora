package com.leyu.melora.ui.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineCache
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
        OnlineCache.clear("search:songs:$platform:$keyword:")
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
        cachePage(1, "已加载的歌曲")
        val release = CompletableDeferred<Unit>()
        val failedPage = CoroutineScope(Dispatchers.Default).async {
            runCatching {
                OnlineCache.refresh<SongPage>(songSearchCacheKey(platform, keyword, 2), -1) {
                    release.await()
                    throw IOException("测试分页网络中断")
                }
            }
        }
        try {
            compose.waitUntil(5_000) {
                OnlineCache.pendingRefresh<SongPage>(songSearchCacheKey(platform, keyword, 2)) != null
            }
            openSearch()
            compose.awaitStable(compose.onNodeWithText("已加载的歌曲"))
            compose.onNodeWithText("加载更多").performClick()
            compose.awaitStable(compose.onNodeWithText("正在加载…"))
            compose.runOnIdle { release.complete(Unit) }

            val retry = compose.onNodeWithText("加载失败，点击重试")
            compose.awaitStable(retry)
            compose.onNodeWithText("已加载的歌曲").assertIsDisplayed()
            cachePage(2, "恢复加载的歌曲")
            retry.performClick()
            compose.awaitStable(compose.onNodeWithText("恢复加载的歌曲"))
            compose.onNodeWithText("已加载的歌曲").assertIsDisplayed()
            compose.onNodeWithText("加载失败，点击重试").assertDoesNotExist()
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(5_000) { failedPage.await() } }
        }
    }

    @Test fun filteredFirstPageStillAllowsPaginationAfterRecreation() {
        cachePage(1, null)
        cachePage(2, "下一页匹配歌曲")
        openSearch()
        compose.awaitStable(compose.onNodeWithText("加载更多"))

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains(keyword)
        compose.awaitStable(compose.onNodeWithText("加载更多"))
        compose.onNodeWithText("加载更多").performClick()
        compose.awaitStable(compose.onNodeWithText("下一页匹配歌曲"))
    }

    private fun openSearch() {
        restoration.setContent {
            MeloraTheme { SongListStateProvider { SearchScreen(SearchCategory.Song, {}, {}) } }
        }
        compose.awaitStable(compose.onNodeWithText(keyword))
        compose.onNodeWithText(keyword).performClick()
    }

    private fun cachePage(number: Int, title: String?) {
        val songs = listOfNotNull(title?.let {
            OnlineSong(JSONObject().put("source", platform).put("songmid", "$keyword-$number").put("name", it))
        })
        OnlineCache.put(
            songSearchCacheKey(platform, keyword, number),
            SongPage(songs, total = 2, page = number, allPage = 2, pageSize = 1, rawCount = 1),
        )
    }
}
