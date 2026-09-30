package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.ui.search.PlatformSource
import com.leyu.melora.ui.search.SearchCategory
import com.leyu.melora.ui.search.SearchResultsContent
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveGridLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sidebarAllocationIsMeasuredBeforeChoosingColumns() {
        var availableWidth = 0f
        var columns = 0
        compose.setContent {
            val actual = LocalWindowInfo.current
            val density = Density(1f)
            val window = object : WindowInfo by actual {
                override val containerSize = with(density) { IntSize(720.dp.roundToPx(), 960.dp.roundToPx()) }
            }
            CompositionLocalProvider(LocalWindowInfo provides window, LocalDensity provides density) {
                BoxWithConstraints(Modifier.size(720.dp, 960.dp).padding(start = 208.dp)) {
                    availableWidth = maxWidth.value
                    columns = responsiveGridColumns(maxWidth)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(512f, availableWidth, 1f)
        assertEquals(2, columns)
    }

    @Test fun searchPlaylistAndBookGridsFollowTheirPaneAcrossResizeAndReload() {
        val paneWidth = mutableStateOf(720.dp)
        val sidebar = mutableStateOf(208.dp)
        val windowWidth = mutableStateOf(720.dp)
        val category = mutableStateOf(SearchCategory.Playlist)
        val loading = mutableStateOf(false)
        val playlists = (0..5).map {
            OnlinePlaylist(JSONObject().put("id", "grid-$it").put("name", "网格$it")
                .put("source", "kw").put("author", "测试"))
        }
        compose.setContent {
            val actual = LocalWindowInfo.current
            val density = Density(1f)
            val window = object : WindowInfo by actual {
                override val containerSize = with(density) { IntSize(windowWidth.value.roundToPx(), 960.dp.roundToPx()) }
            }
            CompositionLocalProvider(LocalWindowInfo provides window, LocalDensity provides density) {
                MeloraTheme {
                    Box(Modifier.size(paneWidth.value, 960.dp).padding(start = sidebar.value)) {
                        SearchResultsContent(
                            listState = rememberLazyListState(), category = category.value, submitted = "测试",
                            selectedPlatform = PlatformSource.Kuwo, loading = loading.value, error = null,
                            playlists = playlists, songs = emptyList(), hasMore = false, loadingMore = false,
                            favoriteUids = emptySet(), onRetry = {}, onLoadMore = {}, onOpenPlaylist = {},
                            onMoreSong = {}, onPlaySong = {},
                        )
                    }
                }
            }
        }
        fun assertColumns(count: Int) {
            compose.waitForIdle()
            val first = compose.onNodeWithText("网格0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            for (index in 1 until count) {
                val next = compose.onNodeWithText("网格$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals("同一行的标题应对齐", first.top, next.top, 1f)
            }
            val nextRow = compose.onNodeWithText("网格$count", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("下一张卡片应换行", nextRow.top > first.bottom)
        }
        assertColumns(2)
        // 内容区不变时，窗口扩大不能改变该区域的列数。
        compose.runOnIdle { windowWidth.value = 1440.dp }
        assertColumns(2)
        compose.runOnIdle { loading.value = true }
        compose.waitForIdle()
        compose.runOnIdle { loading.value = false; category.value = SearchCategory.Audiobook }
        assertColumns(2)
        compose.runOnIdle { paneWidth.value = 1112.dp }
        assertColumns(4)
        compose.runOnIdle { paneWidth.value = 390.dp; sidebar.value = 0.dp; category.value = SearchCategory.Playlist }
        assertColumns(2)
    }
}
