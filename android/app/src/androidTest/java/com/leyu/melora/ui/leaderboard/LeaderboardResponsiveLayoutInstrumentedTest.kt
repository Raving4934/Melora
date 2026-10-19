package com.leyu.melora.ui.leaderboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.sdk.BoardItem
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.common.SkeletonLeaderboard
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 直接验证实际页面和排行榜骨架；榜单与预览全部命中缓存，不触发在线或播放请求。 */
@RunWith(AndroidJUnit4::class)
class LeaderboardResponsiveLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val paneWidth = mutableStateOf(392.dp)
    private val sidebarWidth = mutableStateOf(0.dp)
    private val fontScale = mutableStateOf(1f)
    private val skeleton = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private val fixturePrefix = "test-leaderboard-${System.nanoTime()}"
    private val previousPlatform = MeloraSettings.leaderboardPlatform.value
    private val previousBoards = OnlineCache.peek<List<BoardItem>>("boards.kw")
    private val boards = (0..10).map(::board)
    private fun board(index: Int) =
        BoardItem(
            id = "$fixturePrefix-$index",
            name = "垂类榜单$index" + "很长的榜单标题".repeat(8),
            bangid = "$fixturePrefix-$index",
        )
    private var skeletonHeightPx = 0

    @After fun cleanup() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        OnlineCache.clear("boardSongsPage.kw.$fixturePrefix")
        OnlineCache.clear("boardSongsPreview.kw.$fixturePrefix")
        if (previousBoards == null) OnlineCache.clear("boards.kw")
        else OnlineCache.put("boards.kw", previousBoards)
        MeloraSettings.leaderboardPlatform.value = previousPlatform
    }

    @Test fun featuredRowsFollowTheirContentPaneAcrossResize() {
        show()
        assertFeaturedColumns(1)
        compose.runOnIdle { paneWidth.value = 720.dp }
        assertFeaturedColumns(2)
        compose.runOnIdle { paneWidth.value = 1280.dp; sidebarWidth.value = 208.dp }
        assertFeaturedColumns(3)

        val genres = (3..10).map { index ->
            genreCard(index).layoutBoundsInRootPx()
        }
        genres.take(7).forEach { assertEquals(genres.first().top, it.top, 1f) }
        assertTrue("第八张垂类卡应换行，前七张不能受旧六列上限影响", genres[7].top >= genres.first().bottom)

        compose.runOnIdle { paneWidth.value = 392.dp; sidebarWidth.value = 0.dp }
        assertFeaturedColumns(1)
    }

    @Test fun scrolledThirdFeaturedBoardStaysAnchoredWhenTwoColumnsBecomeThree() {
        paneWidth.value = 720.dp
        // 缩短视口并增加垂类榜单，保证变列前后都能滚动，避免列表过短自动回顶掩盖问题。
        show(paneHeight = 1200.dp, boardItems = (0..59).map(::board))
        assertThirdFeaturedBoardStaysAnchored(itemIndex = 2) {
            paneWidth.value = 1280.dp
            sidebarWidth.value = 208.dp
        }
    }

    @Test fun scrolledThirdFeaturedBoardStaysAnchoredBetweenPhoneAndTablet() {
        show(paneHeight = 1200.dp, boardItems = (0..59).map(::board))
        assertThirdFeaturedBoardStaysAnchored(itemIndex = 3) { paneWidth.value = 720.dp }
        assertThirdFeaturedBoardStaysAnchored(itemIndex = 2) { paneWidth.value = 392.dp }
    }

    @Test fun skeletonAndLoadedCardsHaveTheSameGeometryWithSystemFontScaling() {
        show()
        for (scale in listOf(1f, 1.6f)) {
            for ((width, sidebar) in listOf(392 to 0, 720 to 0, 1280 to 208)) {
                compose.runOnIdle {
                    paneWidth.value = width.dp
                    sidebarWidth.value = sidebar.dp
                    fontScale.value = scale
                    skeleton.value = false
                }
                compose.waitForIdle()
                val header = compose.onNodeWithTag("leaderboard-official-header")
                    .layoutBoundsInRootPx()
                val firstGenre = genreCard(3).layoutBoundsInRootPx()
                assertEquals("测量对象必须是完整方卡，不能是标题或被视口裁切后的可见区域",
                    firstGenre.width, firstGenre.height, 1f)
                // 整行边界已包含 4dp 顶部留白，不能用垂直居中的 Text 顶沿推算整行起点。
                val loadedHeightPx = firstGenre.bottom - header.top
                compose.runOnIdle { skeleton.value = true }
                compose.waitForIdle()
                assertEquals("${width}dp / fontScale=$scale 的骨架不能引起换行或高度跳变，header=$header，card=$firstGenre",
                    loadedHeightPx, skeletonHeightPx.toFloat(), 1.5f)
            }
        }
    }

    @Test fun emptyPreviewsAndLongTitlesKeepTheSameCardHeight() {
        show()
        compose.waitForIdle()
        val populated = genreCard(3).layoutBoundsInRootPx()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        cachePreviews(empty = true)
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(populated, genreCard(3).layoutBoundsInRootPx())
    }

    private fun show(paneHeight: Dp? = null, boardItems: List<BoardItem> = boards) {
        OnlineCache.put("boards.kw", boardItems)
        cachePreviews(empty = false)
        MeloraSettings.leaderboardPlatform.value = "kw"
        compose.setContent {
            // 用较低测试密度让 1280dp 内容可在普通测试设备上完整测量；列数只依赖容器 dp。
            CompositionLocalProvider(LocalDensity provides Density(TEST_DENSITY, fontScale.value)) {
                MeloraTheme {
                    Box(Modifier.requiredWidth(paneWidth.value)
                        .then(if (paneHeight == null) Modifier.fillMaxHeight() else Modifier.requiredHeight(paneHeight))
                        .padding(start = sidebarWidth.value)) {
                        if (visible.value) {
                            // 骨架只叠层测量，循环切换时保留真实页面及已加载的数据。
                            LeaderboardScreen()
                            if (skeleton.value) {
                                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                    SkeletonLeaderboard(
                                        featuredColumns = leaderboardFeaturedColumnCount(maxWidth.value),
                                        genreColumns = leaderboardGenreColumnCount(maxWidth.value),
                                        modifier = Modifier.onSizeChanged { skeletonHeightPx = it.height },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cachePreviews(empty: Boolean) {
        boards.take(3).forEachIndexed { boardIndex, board ->
            val songs = if (empty) emptyList() else (0..2).map { songIndex ->
                OnlineSong(JSONObject().put("source", "kw").put("songmid", "${board.bangid}-$songIndex")
                    .put("name", songTitle(boardIndex, songIndex)).put("singer", "很长的歌手名".repeat(10)))
            }
            val page = SongPage(songs, total = songs.size, page = 1, allPage = 1)
            OnlineCache.put("boardSongsPage.kw.${board.bangid}", page)
            OnlineCache.put("boardSongsPreview.kw.${board.bangid}", page)
        }
    }

    private fun assertThirdFeaturedBoardStaysAnchored(itemIndex: Int, resize: () -> Unit) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(itemIndex)
        val preview = compose.onNodeWithText(songTitle(2, 0), useUnmergedTree = true)
        val before = preview.assertIsDisplayed().layoutBoundsInRootPx()
        compose.runOnIdle(resize)
        compose.waitForIdle()
        val after = preview.assertIsDisplayed().layoutBoundsInRootPx()
        assertEquals("变列后第三张主榜仍应停留在相同视口位置，不能跳到垂类榜单", before.top, after.top, 1f)
    }

    private fun assertFeaturedColumns(columns: Int) {
        compose.waitForIdle()
        val previews = (0..2).map { index ->
            compose.onNodeWithText(songTitle(index, 0), useUnmergedTree = true)
                .layoutBoundsInRootPx()
        }
        previews.forEachIndexed { index, bounds ->
            if (index < columns) {
                assertEquals("同一排的预览应对齐", previews.first().top, bounds.top, 1f)
                if (index > 0) assertTrue(bounds.left > previews[index - 1].left)
            } else {
                assertTrue("下一排主榜应位于第一排下方", bounds.top > previews.first().bottom)
            }
        }
    }

    private fun genreCard(index: Int) =
        compose.onNode(hasClickAction() and hasText(boards[index].name))

    /** 滚动视口和系统区会裁切 boundsInRoot；几何对比必须使用完整布局边界，单位仍为 px。 */
    private fun SemanticsNodeInteraction.layoutBoundsInRootPx(): Rect {
        val node = fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun songTitle(board: Int, song: Int) = "预览$board-$song" + "很长的歌曲名称".repeat(10)

    private companion object {
        const val TEST_DENSITY = 0.25f
    }
}
