package com.leyu.melora.ui.my

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookProgramUiTest {
    @get:Rule val compose = createComposeRule()
    private val chapter = OnlineSong(JSONObject().put("source", "kw").put("songmid", "book-ui-128")
        .put("albumId", "book-ui-fixture").put("albumName", "续听测试专辑").put("name", "第128集")
        .put("singer", "测试主播").put("isBookChapter", true).put("img", "file:///book-ui-fixture.jpg"))

    private fun showMusicMenu(heightDp: Float, fontScale: Float = 1f, onDelete: () -> Unit = {}) {
        compose.setContent {
            val heightPx = LocalWindowInfo.current.containerSize.height
            CompositionLocalProvider(LocalDensity provides Density((heightPx / heightDp).coerceAtLeast(0.1f), fontScale)) {
                MeloraTheme {
                    SongListStateProvider {
                        SongMoreSheet(
                            OnlineSong(JSONObject().put("source", "kw").put("songmid", "music-menu-fixture")
                                .put("name", "普通音乐").put("img", "file:///music-menu-fixture.jpg")),
                            onRemoveFromPlaylist = {}, onDeleteLocal = onDelete, onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.awaitStable(compose.onNodeWithText("普通音乐"))
    }

    @Test fun musicMenuRevealsLastActionWithoutFirstExpandingTheSheet() {
        showMusicMenu(heightDp = 780f)
        // 内容超过半屏但能完整放下；不能先滑动来掩盖半展开时底部操作不可见。
        compose.onNodeWithText("永久删除").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("下一首播放").assertIsDisplayed()
    }

    @Test fun musicMenuKeepsLastActionReachableInAShortWindowWithLargeText() {
        var deleted = 0
        showMusicMenu(heightDp = 420f, fontScale = 1.4f, onDelete = { deleted++ })
        val lastAction = compose.onNodeWithText("永久删除").performScrollTo()
        compose.awaitStable(lastAction)
        lastAction.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun recentProgramClickResumesInsteadOfOpeningAnAlbum() {
        var resumed: String? = null
        var opened = 0
        compose.setContent {
            MeloraTheme {
                SongListStateProvider {
                    RecentsPage(listOf(chapter), emptyList(), {}, { opened++ }, { resumed = it.uid }, {})
                }
            }
        }
        val row = compose.onNodeWithText("续听测试专辑")
        compose.awaitStable(row)
        row.performClick()
        compose.runOnIdle { assertEquals(chapter.uid, resumed); assertEquals(0, opened) }
    }

    @Test fun programMenuKeepsBookActionsAndDispatchesResumeOnce() {
        val open = mutableStateOf(true)
        var resumed = 0
        compose.setContent {
            MeloraTheme {
                SongListStateProvider {
                    if (open.value) SongMoreSheet(chapter, onDismiss = { open.value = false },
                        onOpenBookAlbum = {}, onRemoveRecentProgram = {}, onResumeBook = { resumed++ })
                }
            }
        }
        compose.onNodeWithText("下一首播放").assertDoesNotExist()
        compose.onNodeWithText("添加到歌单").assertDoesNotExist()
        compose.onNodeWithText("下载更高音质").assertDoesNotExist()
        compose.onNodeWithText("从头播放本集").assertExists()
        compose.onNodeWithText("查看专辑").assertExists()
        compose.onNodeWithText("收藏专辑").assertExists()
        compose.onNodeWithText("下载本集").assertExists()
        compose.onNodeWithText("移除播放记录").assertExists()
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-program-menu.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val resume = compose.onNodeWithText("继续收听").performScrollTo()
        compose.awaitStable(resume)
        resume.performClick()
        compose.waitUntil(5_000) { resumed == 1 }
        compose.runOnIdle { assertFalse(open.value); assertEquals(1, resumed) }
    }

    @Test fun chapterMenuRetainsChapterFavoritesWithoutMusicTerminology() {
        compose.setContent {
            MeloraTheme { SongListStateProvider { SongMoreSheet(chapter, onDismiss = {}) } }
        }
        compose.onNodeWithText("播放本集").assertExists()
        compose.onNodeWithText("收藏本集").assertExists()
        compose.onNodeWithText("收藏专辑").assertExists()
        compose.onNodeWithText("添加到歌单").assertDoesNotExist()
    }

    @Test fun programAlbumActionDoesNotPlayAndMusicActionsStayUnchanged() {
        val showBook = mutableStateOf(true)
        var album: String? = null
        var resumed = 0
        compose.setContent {
            MeloraTheme {
                SongListStateProvider {
                    if (showBook.value) SongMoreSheet(chapter, onDismiss = { showBook.value = false },
                        onOpenBookAlbum = { album = it.id }, onResumeBook = { resumed++ })
                    else SongMoreSheet(OnlineSong(JSONObject().put("source", "kw").put("songmid", "music-ui-fixture")
                        .put("name", "普通音乐").put("img", "file:///music-ui-fixture.jpg")), onDismiss = {})
                }
            }
        }
        val viewAlbum = compose.onNodeWithText("查看专辑").performScrollTo()
        compose.awaitStable(viewAlbum)
        viewAlbum.performClick()
        compose.waitUntil(5_000) { album != null }
        assertEquals("book_album_book-ui-fixture", album)
        assertEquals(0, resumed)
        compose.onNodeWithText("添加到歌单").assertExists()
        compose.onNodeWithText("下一首播放").assertExists()
        compose.onNodeWithText("下载本集").assertDoesNotExist()
        compose.onNodeWithText("从头播放本集").assertDoesNotExist()
    }
}
