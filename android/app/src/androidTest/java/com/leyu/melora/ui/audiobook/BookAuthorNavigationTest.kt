package com.leyu.melora.ui.audiobook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.player.SongsCollectionPage
import com.leyu.melora.ui.player.artistCollection
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实Compose路由与卡片点击；元数据只用缓存夹具，不依赖在线接口或音源。 */
@RunWith(AndroidJUnit4::class)
class BookAuthorNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val author = "测试创作者"
    private val shared = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))

    @After fun cleanup() {
        OnlineCache.clear("book.author.$author")
        OnlineCache.clear("playlistDetail.book.book_album_fixture")
    }

    @Test fun audiobookArtistShowsWholeWorksThenOpensChaptersAndReturns() {
        show()
        compose.onNodeWithText("听书作品").assertIsDisplayed()
        compose.onNodeWithText("测试长篇作品").assertIsDisplayed()
        compose.onNodeWithText("测试章节100").assertDoesNotExist()
        compose.onNodeWithText("播放全部").assertDoesNotExist()
        saveProof("author-portrait")
        val card = compose.onNodeWithText("测试长篇作品").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("测试长篇作品").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("测试章节100").assertIsDisplayed()
        compose.onNodeWithText("播放全部").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("听书作品").assertIsDisplayed()
        compose.onNodeWithText("测试章节100").assertDoesNotExist()
        assertEquals(card, compose.onNodeWithText("测试长篇作品").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun emptyAuthorShowsWorksEmptyStateNotSongSearch() {
        OnlineCache.put("book.author.$author", BookCatalogSnapshot(page = 1, hasMore = false))
        compose.setContent { MeloraTheme { BookAuthorPage(author, {}) } }
        compose.onNodeWithText("暂未找到该作者／主播的听书作品").assertIsDisplayed()
        compose.onNodeWithText("播放全部").assertDoesNotExist()
    }

    @Test fun wideScreenShowsAtLeastFourCardsInOneRow() {
        show(wide = true)
        val titles = listOf("测试长篇作品", "作品2", "作品3", "作品4")
            .map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        saveProof("author-wide")
        assertTrue(titles.zipWithNext().all { (a, b) -> b.left > a.left })
        // 长书名允许两行：同一排卡片的标题底部共线，不能要求文字顶部也一致。
        assertTrue(titles.all { kotlin.math.abs(it.bottom - titles.first().bottom) < 2f })
    }

    private fun saveProof(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(wide: Boolean = false) {
        val books = (1..4).map { number -> OnlinePlaylist(JSONObject()
            .put("id", "book_album_fixture$number").put("name", if (number == 1) "测试长篇作品" else "作品$number")
            .put("kind", "book").put("source", "kw").put("author", author).put("total", 120)) }
        OnlineCache.put("book.author.$author", BookCatalogSnapshot(books, page = 1, hasMore = false))
        val chapter = OnlineSong(JSONObject().put("source", "kw").put("songmid", "fixture-chapter")
            .put("name", "测试章节100").put("singer", author).put("albumId", "fixture1")
            .put("isBookChapter", true).put("albumName", "测试长篇作品"))
        OnlineCache.put("playlistDetail.book.book_album_fixture1", KwBookApi.BookChapters(listOf(chapter), false))
        val collection = artistCollection(UiTrack.fromOnline(chapter), author)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides if (wide) Density(1f) else LocalDensity.current,
                LocalSongListState provides shared,
            ) {
                MeloraTheme {
                    Box(if (wide) Modifier.requiredSize(720.dp, 600.dp) else Modifier.fillMaxSize()) {
                        SongsCollectionPage(collection, {})
                    }
                }
            }
        }
    }
}
