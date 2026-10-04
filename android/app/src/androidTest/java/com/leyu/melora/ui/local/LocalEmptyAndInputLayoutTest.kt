package com.leyu.melora.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import com.leyu.melora.ui.common.ChromeScaffold
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.common.SongSelectionState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LocalEmptyAndInputLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val previousBlur = MeloraSettings.blurTopBar.value
    private val shared = SongListState(mutableStateOf(emptyList<LocalSong>()), mutableStateOf(false), mutableStateOf(null))
    @After fun restore() { MeloraSettings.blurTopBar.value = previousBlur }

    @Test fun searchRouteAndQueryRestoreTogetherAndReopeningStartsEmpty() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsPage(content = LocalSongsContent(), onOpenDrawer = {})
                }
            }
        }
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("恢复关键词")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("恢复关键词")
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("在 0 首歌曲中搜索").assertIsDisplayed() }.isSuccess
        }
    }

    @Test fun openingSearchBeforeSortFinishesNeverReportsAnEmptyLibrary() {
        val content = mutableStateOf<LocalSongsContent?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsPage(content = content.value, onOpenDrawer = {})
                }
            }
        }
        compose.onNodeWithText("还没有本地歌曲").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()
        compose.onNodeWithText("在 0 首歌曲中搜索").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("测试")
        compose.onNodeWithText("未找到与「测试」相关的歌曲").assertDoesNotExist()
        compose.runOnIdle { content.value = LocalSongsContent() }
        compose.onNodeWithText("未找到与「测试」相关的歌曲").assertIsDisplayed()
    }

    @Test fun emptyStateIsBelowTheWholeFixedHeaderWithAndWithoutBlur() {
        lateinit var list: LazyListState
        var minimumPadding = 0
        compose.setContent {
            list = rememberLazyListState()
            minimumPadding = with(LocalDensity.current) { (110.dp + 24.dp).roundToPx() }
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsListContent(
                        content = LocalSongsContent(),
                        selection = SongSelectionState(), listState = list,
                        onOpenDrawer = {}, onOpenSearch = {}, onOpenSortSheet = {}, onMore = {},
                        onDeleteSelection = {}, onAddToPlaylist = {}, pullEnabled = true,
                        refreshing = false, onRefresh = {},
                    )
                }
            }
        }
        for (blur in listOf(false, true)) {
            compose.runOnIdle { MeloraSettings.blurTopBar.value = blur }
            compose.onNodeWithText("还没有本地歌曲").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue("空态不能绘制到固定栏背后", list.layoutInfo.beforeContentPadding >= minimumPadding)
            }
        }
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @Test fun chromeBlurUpdatesSourceClearsRemovedContentAndKeepsLayoutWhenToggled() {
        val sourceColor = mutableStateOf(Color.Red)
        val showSource = mutableStateOf(true)
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        compose.setContent {
            val source = rememberHazeState()
            MaterialTheme {
                ChromeScaffold(
                    modifier = Modifier.requiredSize(240.dp, 300.dp),
                    containerColor = Color.White, headerColor = Color.White,
                    contentSource = source, expectedTopBarHeight = 100.dp,
                    topBar = { Box(Modifier.fillMaxWidth().height(100.dp).testTag("blur-header")) },
                ) {
                    if (showSource.value) Box(Modifier.fillMaxSize().hazeSource(source).background(sourceColor.value))
                }
            }
        }
        val header = compose.onNodeWithTag("blur-header")
        fun awaitColor(predicate: (Color) -> Boolean) = compose.waitUntil(5_000) {
            val pixels = header.captureToImage().toPixelMap()
            predicate(pixels[pixels.width / 2, pixels.height * 3 / 5])
        }
        fun awaitWhite() = awaitColor { it.red > 0.95f && it.green > 0.95f && it.blue > 0.95f }
        val supportsBlur = HazeBlurDefaults.isBlurEnabledByDefault()
        if (supportsBlur) awaitColor { it.red > it.blue + 0.15f } else awaitWhite()
        val bounds = header.getUnclippedBoundsInRoot()
        compose.runOnIdle { sourceColor.value = Color.Blue }
        if (supportsBlur) awaitColor { it.blue > it.red + 0.15f } else awaitWhite()
        compose.runOnIdle { MeloraSettings.blurTopBar.value = false }
        awaitWhite()
        assertEquals(bounds, header.getUnclippedBoundsInRoot())
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        if (supportsBlur) awaitColor { it.blue > it.red + 0.15f } else awaitWhite()
        assertEquals(bounds, header.getUnclippedBoundsInRoot())
        compose.runOnIdle { showSource.value = false }
        awaitWhite()
    }

    @Test fun emptyCursorAndPlaceholderShareTheSameVerticalCenterAtDifferentFontScales() {
        val scale = mutableStateOf(1f)
        var tolerance = 0f
        compose.setContent {
            val density = LocalDensity.current
            tolerance = density.density
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale.value),
                    LocalSongListState provides shared,
                ) {
                    LocalSearchPage(
                        content = LocalSongsContent(), query = "", onQueryChange = {}, onCancel = {},
                        selection = SongSelectionState(), onOpenSortSheet = {}, onMore = {},
                        onDeleteSelection = {}, onAddToPlaylist = {},
                    )
                }
            }
        }
        for (fontScale in listOf(1f, 1.4f)) {
            compose.runOnIdle { scale.value = fontScale }
            val field = compose.onNode(hasSetTextAction())
            val hint = compose.onNodeWithText("在 0 首歌曲中搜索", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("搜索框应提供真实文字布局", layouts.isNotEmpty())
            val cursorY = field.fetchSemanticsNode().boundsInRoot.top + layouts.single().getCursorRect(0).center.y
            val hintY = hint.fetchSemanticsNode().boundsInRoot.center.y
            assertTrue("fontScale=$fontScale cursor=$cursorY hint=$hintY", abs(cursorY - hintY) <= tolerance)
        }
    }
}
