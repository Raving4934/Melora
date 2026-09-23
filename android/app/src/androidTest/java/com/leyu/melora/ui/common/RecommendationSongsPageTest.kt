package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 完整推荐列表使用离线数据；冷加载、缓存和重入均不能被当成可分页榜单。 */
@RunWith(AndroidJUnit4::class)
class RecommendationSongsPageTest {
    @get:Rule val compose = createComposeRule()
    private val cacheKey = "test.recommendation.${System.nanoTime()}"
    private val visible = mutableStateOf(true)
    private val rows = (1..30).map { index ->
        OnlineSong(JSONObject().put("source", "test").put("songmid", index.toString())
            .put("name", "推荐曲目 $index").put("singer", "测试歌手"))
    }
    private val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))

    @After fun cleanup() { OnlineCache.clear(cacheKey) }

    @Test fun coldThirtySongListEndsWithoutFetchingAnotherBatch() {
        val calls = AtomicInteger()
        show { calls.incrementAndGet(); rows }
        assertLastSongWithoutPagination()
        assertEquals(1, calls.get())
        assertEquals(rows, OnlineCache.peek<List<OnlineSong>>(cacheKey))
    }

    @Test fun cachedThirtySongListAndReentryNeverInferAnotherPage() {
        OnlineCache.put(cacheKey, rows)
        val calls = AtomicInteger()
        show { calls.incrementAndGet(); rows }
        assertLastSongWithoutPagination()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        assertLastSongWithoutPagination()
        assertEquals(0, calls.get())
    }

    @Test fun failedLoadCanRetryTheCompleteListWithoutPagination() {
        val calls = AtomicInteger()
        show {
            if (calls.incrementAndGet() == 1) error("暂时无法加载推荐")
            rows
        }
        compose.onNodeWithText("点击重试").assertIsDisplayed().performClick()
        assertLastSongWithoutPagination()
        assertEquals(2, calls.get())
    }

    @Test fun emptyRecommendationHasNoLoadMoreAction() {
        val calls = AtomicInteger()
        show { calls.incrementAndGet(); emptyList() }
        compose.onNodeWithText("暂无歌曲").assertIsDisplayed()
        compose.onAllNodesWithText("上滑加载更多").assertCountEquals(0)
        assertEquals(1, calls.get())
    }

    private fun show(fetch: suspend () -> List<OnlineSong>) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    if (visible.value) {
                        OnlineSongsPage(
                            title = "新歌推荐", subtitle = "近期新歌精选 30 首",
                            queueId = "test.recommendation", cacheKey = cacheKey,
                            onBack = {}, fetchSongs = fetch,
                        )
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun assertLastSongWithoutPagination() {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(rows.lastIndex)
        compose.onNodeWithText("推荐曲目 30").assertIsDisplayed()
        compose.onAllNodesWithText("上滑加载更多").assertCountEquals(0)
        compose.onAllNodesWithText("正在加载…").assertCountEquals(0)
    }
}
