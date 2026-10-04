package com.leyu.melora.ui.my

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.BookListeningProgress
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "library-cards-${System.nanoTime()}"
    private val prefs = context.getSharedPreferences("melora-progress", Context.MODE_PRIVATE)
    private val originalEnabled = MeloraSettings.rememberProgress.value
    private val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "$id-song")
        .put("name", "测试歌曲").put("singer", "测试歌手"))
    private val chapter = OnlineSong(JSONObject().put("source", "kw").put("songmid", "$id-chapter")
        .put("albumId", id).put("albumName", "测试有声专辑").put("name", "第26集")
        .put("singer", "作者甲 / 主播乙").put("isBookChapter", true).put("bookOrdinal", 26).put("bookTotal", 100))
    private val playlist = UserLibrary.RecentContainer("playlist", "$id-list", "测试歌单", null, "kw", "$id-list", 1L)
    // 旧记录没有artist，需从已保存断点的章节元数据恢复作者。
    private val book = UserLibrary.RecentContainer("book", "book_album_$id", "测试有声专辑", null, "kw", id, 2L)

    @After fun cleanup() {
        MeloraSettings.rememberProgress.value = originalEnabled
        prefs.edit().apply { prefs.all.keys.filter { it.contains(id) }.forEach(::remove) }.commit()
    }

    private fun showRecents(includeProgram: Boolean = false) {
        compose.setContent {
            MeloraTheme { SongListStateProvider {
                RecentsPage(if (includeProgram) listOf(song, chapter) else listOf(song), listOf(playlist, book), {}, {}, {}, {})
            } }
        }
        compose.awaitStable(compose.onNodeWithText("最近收听"))
    }

    @Test fun recentSummaryStaysAtTheSamePositionAndCategoriesUseTheSameFlatRows() {
        showRecents()
        val summary = compose.onNodeWithText("最近收听").fetchSemanticsNode().boundsInRoot
        val titleBefore = compose.onNodeWithText("测试歌单", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val flatColor = if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithText("测试歌单").captureToImage().asAndroidBitmap()
            bitmap.getPixel(1, bitmap.height / 2).also { bitmap.recycle() }
        } else null
        compose.onNodeWithText("单曲 1").performClick()
        compose.awaitStable(compose.onNodeWithText("1 条记录"))
        val songsSummary = compose.onNodeWithText("1 条记录").fetchSemanticsNode().boundsInRoot
        assertEquals(summary.left, compose.onNodeWithContentDescription("播放记录").fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(summary.top, songsSummary.top, 1f)
        compose.onNodeWithText("播放全部").assertIsDisplayed()
        compose.onNodeWithText("歌单专辑 1").performClick()
        val title = compose.onNodeWithText("测试歌单", useUnmergedTree = true)
        compose.awaitStable(title)
        val after = title.fetchSemanticsNode().boundsInRoot
        assertEquals("分类不能额外缩进", titleBefore.left, after.left, 1f)
        compose.onNodeWithText("1 条记录").assertDoesNotExist()
        if (flatColor != null && Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithText("测试歌单").captureToImage().asAndroidBitmap()
            assertEquals("分类行不能增加卡片底色", flatColor, bitmap.getPixel(1, bitmap.height / 2))
            bitmap.recycle()
        }
    }

    @Test fun recentBookShowsSavedCreatorAndAlbumProgressInAllAndBookTabs() {
        MeloraSettings.rememberProgress.value = true
        BookListeningProgress.persist(prefs, chapter, 1L, 60_000L, completed = false)
        showRecents()
        compose.onNodeWithText("作者甲 / 主播乙 · 进度 25%").assertIsDisplayed()
        compose.onNodeWithText("听书 1").performClick()
        compose.awaitStable(compose.onNodeWithText("作者甲 / 主播乙 · 进度 25%"))
        saveScreenshot("recent-book-cards.png")
    }

    @Test fun programsAndBookCardsShareTheirRowSizeAndTypographyWithoutCountHeaders() {
        showRecents(includeProgram = true)
        compose.onNodeWithText("听书 1").performClick()
        compose.awaitStable(compose.onNodeWithText("测试有声专辑"))
        compose.onNodeWithText("1 本听书").assertDoesNotExist()
        val bookRow = compose.onNodeWithText("测试有声专辑").fetchSemanticsNode().boundsInRoot
        val bookTitle = compose.onNodeWithText("测试有声专辑", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("节目 1").performClick()
        compose.awaitStable(compose.onNodeWithText("测试有声专辑"))
        compose.onNodeWithText("1 个节目").assertDoesNotExist()
        val programRow = compose.onNodeWithText("测试有声专辑").fetchSemanticsNode().boundsInRoot
        val programTitle = compose.onNodeWithText("测试有声专辑", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(bookRow.top, programRow.top, 1f)
        assertEquals(bookRow.height, programRow.height, 1f)
        assertEquals(bookTitle.left, programTitle.left, 1f)
        assertEquals(bookTitle.top, programTitle.top, 1f)
        assertEquals(bookTitle.height, programTitle.height, 1f)
        compose.onNodeWithContentDescription("更多").assertIsDisplayed()
        saveScreenshot("recent-program-cards.png")
    }

    @Test fun switchingFromSongsOrAllHasNoHeaderOrTargetRowJumpAcrossFrames() {
        showRecents(includeProgram = true)
        val tabs = compose.onNodeWithTag("recent-tabs")
        val tabsBefore = tabs.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            for (origin in listOf("单曲 1", "全部 4")) {
                for ((destination, title) in listOf("歌单专辑 1" to "测试歌单", "听书 1" to "测试有声专辑", "节目 1" to "测试有声专辑")) {
                    compose.onNodeWithText(origin).performClick()
                    compose.mainClock.advanceTimeBy(300)
                    compose.onNodeWithText(destination).performClick()
                    val positions = mutableListOf<Float>()
                    repeat(8) {
                        compose.mainClock.advanceTimeByFrame()
                        positions += compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
                        val currentTabs = tabs.fetchSemanticsNode().boundsInRoot
                        assertEquals("分类栏不能跟着正文抖动", tabsBefore.top, currentTabs.top, 1f)
                        assertEquals(tabsBefore.bottom, currentTabs.bottom, 1f)
                    }
                    compose.mainClock.advanceTimeBy(300)
                    val settled = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
                    positions.forEach { assertEquals("$origin → $destination 不能先按旧顶栏高度绘制", settled, it, 1f) }
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun switchingTabsKeepsEachCategoryScrollPosition() {
        val songs = (1..40).map { index -> OnlineSong(JSONObject().put("source", "kw")
            .put("songmid", "$id-scroll-$index").put("name", "滚动歌曲$index")) }
        compose.setContent {
            MeloraTheme { SongListStateProvider { RecentsPage(songs, listOf(book), {}, {}, {}, {}) } }
        }
        compose.onNodeWithText("单曲 40").performClick()
        val list = compose.onNodeWithTag("recent-tab-list")
        list.performScrollToIndex(20)
        compose.awaitStable(compose.onNodeWithText("滚动歌曲20"))
        val offset = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithText("听书 1").performClick()
        compose.awaitStable(compose.onNodeWithText("测试有声专辑"))
        compose.onNodeWithText("单曲 40").performClick()
        compose.awaitStable(compose.onNodeWithText("滚动歌曲20"))
        assertEquals(offset, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
    }

    @Test fun favoriteBookUsesCreatorAndProgressInsteadOfPlaylistLabel() {
        MeloraSettings.rememberProgress.value = true
        BookListeningProgress.persist(prefs, chapter, 1L, 60_000L, completed = false)
        val favorite = OnlinePlaylist(JSONObject().put("source", "kw").put("id", "book_album_$id")
            .put("name", "测试有声专辑").put("total", 100).put("author", ""))
        compose.setContent {
            MeloraTheme { SongListStateProvider {
                FavoritesPage(emptyList(), emptyList(), emptyList(), emptyList(), listOf(favorite), emptyList(),
                    {}, {}, {}, {}, {}, 3, {})
            } }
        }
        compose.awaitStable(compose.onNodeWithText("作者甲 / 主播乙 · 进度 25%"))
        compose.onAllNodesWithText("歌单").assertCountEquals(1) // 仅保留正常歌单Tab
        saveScreenshot("favorite-book-cards.png")
    }

    private fun saveScreenshot(name: String) {
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
