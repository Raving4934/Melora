package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.audiobook.BookCatalogSnapshot
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真正的播放器父手势，而非单独渲染详情；缓存章节/作品夹具，不请求网络。 */
@RunWith(AndroidJUnit4::class)
class PlayerCollectionGestureTest {
    @get:Rule val compose = createComposeRule()
    private val artist = "手势测试作者"
    private val album = "手势测试专辑"
    private val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))

    @After fun cleanup() {
        OnlineCache.clear("book.author.$artist")
        OnlineCache.clear("catalog.artist.$artist")
        OnlineCache.clear("playlistDetail.book.book_album_gesture-fixture")
    }

    @Test fun albumScrollCannotCollapsePlayerAndToolbarBackStillWorks() {
        show()
        compose.onNodeWithContentDescription("查看专辑歌曲").performClick()
        assertDetailKeepsItsPositionDuringScroll()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("出自专辑").assertIsDisplayed()
        assertNormalPlayerCanStillCollapse()
    }

    @Test fun artistScrollCannotCollapsePlayerAndSystemBackStillWorks() {
        show()
        compose.onNodeWithContentDescription("查看歌手歌曲").performClick()
        assertDetailKeepsItsPositionDuringScroll()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("参与创作的艺术家").assertIsDisplayed()
        assertNormalPlayerCanStillCollapse()
    }

    private fun show() {
        // page0的头像也走缓存，避免UI回归依赖在线元数据服务。
        val cached = Class.forName("com.leyu.melora.playback.sdk.CatalogMetadata\$Cached")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(null)
        OnlineCache.put("catalog.artist.$artist", cached)
        val chapters = (1..40).map { index -> OnlineSong(JSONObject()
            .put("source", "fixture").put("songmid", "gesture-$index").put("name", "测试章节$index")
            .put("singer", artist).put("albumName", album).put("albumId", "gesture-fixture")
            .put("img", "file:///android_asset/gesture-fixture.png").put("isBookChapter", true)) }
        OnlineCache.put("playlistDetail.book.book_album_gesture-fixture", KwBookApi.BookChapters(chapters, false))
        OnlineCache.put("book.author.$artist", BookCatalogSnapshot((1..20).map { index ->
            OnlinePlaylist(JSONObject().put("id", "book_album_gesture-$index").put("name", "测试作品$index")
                .put("kind", "book").put("source", "fixture").put("author", artist))
        }, page = 1, hasMore = false))
        compose.setContent {
            CompositionLocalProvider(LocalSongListState provides shared) {
                MeloraTheme {
                    ContinuousPlayerSheet(PlayerUiState(current = UiTrack.fromOnline(chapters.first())), Modifier.fillMaxSize())
                }
            }
        }
        compose.onAllNodesWithText("测试章节1", substring = true).onLast().performClick()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("出自专辑").assertIsDisplayed()
    }

    private fun assertDetailKeepsItsPositionDuringScroll() {
        // DetailPageHost 入场完成后再检查工具栏，避免采样进场动画的中间态。
        compose.waitForIdle()
        val back = compose.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { swipeUp() }
        repeat(4) {
            compose.onRoot().performTouchInput {
                swipe(Offset(center.x, height * .3f), Offset(center.x, height * .85f), 180)
            }
            val after = compose.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("滚动不能把整张播放器往下拖", back.top, after.top, 1f)
        }
    }

    private fun assertNormalPlayerCanStillCollapse() {
        compose.onRoot().performTouchInput {
            swipe(Offset(center.x, height * .25f), Offset(center.x, height * .9f), 220)
        }
        compose.onNodeWithTag("player-pages").assertIsNotDisplayed()
    }
}
