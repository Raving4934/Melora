package com.leyu.melora.ui.common

import androidx.compose.ui.test.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import kotlinx.coroutines.flow.MutableStateFlow
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCatalogNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val id = "catalog-${System.nanoTime()}"
    private val requests = CopyOnWriteArrayList<Int>()

    private fun show(load: suspend (Int) -> KwBookApi.BookChapters = ::page) {
        val album = OnlinePlaylist(JSONObject().put("source", "kw").put("id", "book_album_$id")
            .put("name", "目录测试专辑").put("total", 1500).put("img", "file:///catalog-fixture.jpg"))
        compose.setContent {
            MeloraTheme { SongListStateProvider {
                PlaylistDetailContent(album, {}, modifier = Modifier.testTag("catalog-fixture"), loadBookPage = { _, number -> requests += number; load(number) })
            } }
        }
        compose.waitUntil(5_000) { requests.contains(1) }
        compose.awaitStable(compose.onNodeWithTag("book-chapter-picker-trigger"))
    }

    private fun jump(ordinal: Int) {
        val picker = compose.onNodeWithTag("book-chapter-picker-trigger")
        compose.awaitStable(picker)
        picker.performClick()
        val field = compose.onNode(hasSetTextAction())
        compose.awaitStable(field)
        field.performTextReplacement(ordinal.toString())
        field.performImeAction()
    }

    private fun awaitChapter(ordinal: Int) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("目录第${ordinal}集").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("目录第${ordinal}集").assertIsDisplayed()
    }

    @Test fun jumpingToThousandFetchesOnlyTargetPageAndDoesNotAutoAdvanceAtPageEnd() {
        val playing = PlaybackController.state.value.current?.uid
        show()
        jump(1000)
        awaitChapter(1000)
        compose.awaitStable(compose.onNodeWithText("目录第1000集"))
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithTag("catalog-fixture").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-catalog-1000.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertEquals(listOf(1, 10), requests.toList())
        assertEquals(playing, PlaybackController.state.value.current?.uid)
        compose.onNodeWithText("第 901–1000 章").assertExists()
    }

    @Test fun supersededSlowJumpCannotReplaceNewerChapterWindow() {
        val delayed = CompletableDeferred<KwBookApi.BookChapters>()
        show { number -> if (number == 10) withContext(NonCancellable) { delayed.await() } else page(number) }
        try {
            jump(1000)
            compose.waitUntil(5_000) { requests.contains(10) }
            // 加载时入口保持原字宽，仍允许选择新目标取消旧请求。
            val pendingPicker = compose.onNodeWithTag("book-chapter-picker-trigger")
            compose.awaitStable(pendingPicker)
            pendingPicker.performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("400")
            compose.onNode(hasSetTextAction()).performImeAction()
            awaitChapter(400)
            delayed.complete(page(10))
            compose.awaitStable(compose.onNodeWithText("目录第400集"))
            compose.onNodeWithText("第 301–400 章").assertExists()
            assertEquals(listOf(1, 10, 4), requests.toList())
        } finally { delayed.complete(page(10)) }
    }

    @Test fun initialSkeletonUsesTheSameRowGeometryAndChapterPressAreaHugsItsLabel() {
        val delayed = CompletableDeferred<KwBookApi.BookChapters>()
        try {
            show { delayed.await() }
            val toolbar = compose.onNodeWithTag("book-chapter-toolbar")
            val picker = compose.onNodeWithTag("book-chapter-picker-trigger")
            compose.awaitStable(toolbar)
            picker.assertIsNotEnabled()
            val loadingRow = toolbar.fetchSemanticsNode().boundsInRoot
            val loadingPicker = picker.fetchSemanticsNode().boundsInRoot
            if (Build.VERSION.SDK_INT >= 28) {
                val bitmap = compose.onNodeWithTag("catalog-fixture").captureToImage().asAndroidBitmap()
                File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-actions-skeleton.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            delayed.complete(page(1))
            compose.waitUntil(5_000) { compose.onAllNodesWithText("播放全部").fetchSemanticsNodes().isNotEmpty() }
            compose.awaitStable(toolbar)
            picker.assertIsEnabled()
            val readyRow = toolbar.fetchSemanticsNode().boundsInRoot
            val readyPicker = picker.fetchSemanticsNode().boundsInRoot
            assertEquals("骨架和内容行的顶部应一致", loadingRow.top, readyRow.top, 1f)
            assertEquals("骨架和内容行的高度应一致", loadingRow.height, readyRow.height, 1f)
            assertEquals("章节占位应按真实文字度量", loadingPicker.width, readyPicker.width, 1f)
            val label = compose.onNodeWithText("第 1–100 章", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            assertTrue("点击背景只覆盖章节文字及两侧8dp，不得延伸至排序按钮",
                readyPicker.width - label.width <= 16f * density + 1f)
            if (Build.VERSION.SDK_INT >= 28) {
                val bitmap = compose.onNodeWithTag("catalog-fixture").captureToImage().asAndroidBitmap()
                File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-actions-ready.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { delayed.complete(page(1)) }
    }

    @Test fun singleRowActionsAndSortingKeepTheToolbarAtItsUnpinnedOrPinnedPosition() {
        show()
        fun switchAndCheck(action: String, range: String) {
            val toolbar = compose.onNodeWithTag("book-chapter-toolbar")
            compose.awaitStable(toolbar)
            val before = toolbar.fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription(action).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(range).fetchSemanticsNodes().isNotEmpty() }
            compose.awaitStable(toolbar)
            val after = toolbar.fetchSemanticsNode().boundsInRoot
            assertEquals("排序不能把工具栏顶上去：$action", before.top, after.top, 1f)
            assertEquals(before.bottom, after.bottom, 1f)
        }
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
        val actions = listOf(
            compose.onNodeWithText("播放全部"),
            compose.onNodeWithTag("book-chapter-picker-trigger"),
            compose.onNodeWithContentDescription("切换为倒序"),
            compose.onNodeWithContentDescription("收藏", useUnmergedTree = true),
            compose.onNodeWithContentDescription("批量管理", useUnmergedTree = true),
        ).map { it.fetchSemanticsNode().boundsInRoot }
        actions.zipWithNext().forEach { (left, right) ->
            assertTrue("播放/选集/排序/收藏/多选按顺序在同一行", left.center.x < right.center.x)
            assertEquals(left.center.y, right.center.y, 4f)
        }
        // 专辑信息仍在屏幕内，排序不得主动收起页头。
        switchAndCheck("切换为倒序", "第 1401–1500 章")
        switchAndCheck("切换为正序", "第 1–100 章")
        // 用户主动定位后已吸顶，排序也不应令工具栏上下跳动。
        jump(450)
        awaitChapter(450)
        switchAndCheck("切换为倒序", "第 1401–1500 章")
        switchAndCheck("切换为正序", "第 1–100 章")
    }

    @Test fun locatingCurrentChapterLeavesItsWholeTitleBelowPinnedControls() {
        @Suppress("UNCHECKED_CAST")
        val state = PlaybackController.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            .get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val original = state.value
        val chapter = page(1).items[2]
        try {
            state.value = original.copy(current = UiTrack.fromOnline(chapter))
            show()
            // 从未吸顶的首屏、远页、倒序各定位一次，实际检查标题相对操作栏的位置。
            fun locateAndCheck() {
                val locate = compose.onNodeWithContentDescription("定位当前播放")
                compose.awaitStable(locate)
                val locateBounds = locate.fetchSemanticsNode().boundsInRoot
                val pageBounds = compose.onNodeWithTag("catalog-fixture").fetchSemanticsNode().boundsInRoot
                assertTrue("定位图标应在列表右下角", locateBounds.center.x > pageBounds.width * .8f &&
                    locateBounds.center.y > pageBounds.top + pageBounds.height * .8f)
                locate.performClick()
                awaitChapter(3)
                val target = compose.onNodeWithText("目录第3集")
                compose.awaitStable(target)
                val targetBounds = target.fetchSemanticsNode().boundsInRoot
                val toolbarBounds = compose.onNodeWithTag("book-chapter-toolbar").fetchSemanticsNode().boundsInRoot
                assertTrue("定位行不能被选集/翻页工具栏遮住: $targetBounds / $toolbarBounds",
                    targetBounds.top >= toolbarBounds.bottom)
                assertEquals(chapter.uid, state.value.current?.uid)
            }
            locateAndCheck()
            if (Build.VERSION.SDK_INT >= 28) {
                val bitmap = compose.onNodeWithTag("catalog-fixture").captureToImage().asAndroidBitmap()
                File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-current-located.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            jump(1000)
            awaitChapter(1000)
            locateAndCheck()
            compose.onNodeWithContentDescription("切换为倒序").performClick()
            compose.waitUntil(5_000) { requests.contains(15) }
            compose.awaitStable(compose.onNodeWithContentDescription("切换为正序"))
            locateAndCheck()
        } finally { state.value = original }
    }

    private fun page(number: Int) = KwBookApi.BookChapters(
        items = ((number - 1) * 100 + 1..number * 100).map { ordinal -> OnlineSong(JSONObject()
            .put("source", "kw").put("songmid", "$id-$ordinal").put("albumId", id)
            .put("albumName", "目录测试专辑").put("name", "目录第${ordinal}集").put("isBookChapter", true)
            .put("bookPage", number).put("bookOrdinal", ordinal)
            .put("bookPageEnd", ordinal % 100 == 0).put("bookHasMore", number < 15)
            .put("img", "file:///catalog-fixture.jpg")) },
        hasMore = number < 15, metadata = KwBookApi.BookMetadata(total = 1500), page = number,
    )
}
