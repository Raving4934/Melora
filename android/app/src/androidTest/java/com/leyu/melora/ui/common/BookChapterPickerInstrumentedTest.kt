package com.leyu.melora.ui.common

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookChapterPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enteringThousandSelectsOrdinalWithoutWalkingThroughEarlierRanges() {
        val open = mutableStateOf(true)
        val selections = mutableListOf<Int>()
        compose.setContent {
            MeloraTheme {
                if (open.value) BookChapterPicker(1568, 1, false, { open.value = false }, selections::add, {}, { mutableMapOf() }, { error("unexpected search") }, {})
            }
        }
        val field = compose.onNode(hasSetTextAction())
        compose.awaitStable(field)
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithTag("book-chapter-picker").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-chapter-picker.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        field.performTextReplacement("1000")
        field.performImeAction()
        compose.waitUntil(5_000) { selections.isNotEmpty() }
        assertEquals(listOf(1000), selections)
        assertFalse(open.value)
    }

    @Test fun descendingRangeSelectsSourcePageRatherThanInventingAnEpisode() {
        val selected = mutableListOf<Int>()
        val open = mutableStateOf(true)
        compose.setContent {
            MeloraTheme {
                if (open.value) BookChapterPicker(1568, 1, true, { open.value = false }, {}, selected::add, { mutableMapOf() }, { error("unexpected search") }, {})
            }
        }
        val range = compose.onNodeWithContentDescription("目录 1501–1568")
        compose.awaitStable(range)
        range.performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(16), selected)
    }

    @Test fun switchingModesRestoresCompactDrawerAndKeepsOrdinalLocationAvailable() {
        val open = mutableStateOf(true)
        val selected = mutableListOf<Int>()
        compose.setContent {
            MeloraTheme {
                if (open.value) BookChapterPicker(
                    1568, 1, false, { open.value = false }, selected::add, {},
                    { mutableMapOf() }, { error("unexpected search") }, {},
                )
            }
        }

        val drawer = compose.onNodeWithTag("book-chapter-picker")
        val mode = compose.onNodeWithTag("book-picker-mode")
        compose.awaitStable(drawer)
        compose.awaitStable(mode)
        val initial = compose.boundsInSameFrame(drawer, mode)

        compose.onNodeWithText("章节名").performClick()
        compose.awaitStable(drawer)
        compose.awaitStable(mode)
        compose.onNodeWithText("章节名").assertIsSelected()
        compose.onNodeWithTag("book-picker-thumb").assertDoesNotExist()
        val titleMode = compose.boundsInSameFrame(drawer, mode)
        assertEquals("模式切换不改变抽屉宽度", initial[0].width, titleMode[0].width, 0f)
        assertTrue("搜索模式保留足够的结果浏览区", titleMode[0].height >= initial[0].height)
        assertSameSize(initial[1], titleMode[1], "章节名模式切换区")

        compose.onNodeWithText("集数定位").performClick()
        compose.awaitStable(drawer)
        compose.awaitStable(mode)
        compose.onNodeWithText("集数定位").assertIsSelected()
        val ordinalMode = compose.boundsInSameFrame(drawer, mode)
        assertSameSize(initial[0], ordinalMode[0], "恢复集数定位后的抽屉")
        assertSameSize(initial[1], ordinalMode[1], "恢复集数定位后的切换区")

        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("42")
        compose.onNodeWithText("定位").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(42), selected)
        assertFalse(open.value)
    }

    @Test fun titleSearchKeepsBodyHeightAcrossLoadingResultsAndStoppedStates() {
        val open = mutableStateOf(true)
        val selected = mutableListOf<BookChapterMatch>()
        val loadEntered = CompletableDeferred<Int>()
        val blockedLoad = CompletableDeferred<KwBookApi.BookChapters>()
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "picker-test-1")
            .put("albumId", "picker-test-book").put("name", "原始章节标题").put("isBookChapter", true))
        val page = KwBookApi.BookChapters(
            items = listOf(song), hasMore = false, metadata = KwBookApi.BookMetadata(total = 1),
        )
        var loadCalls = 0
        compose.setContent {
            MeloraTheme {
                if (open.value) BookChapterPicker(
                    1568, 1, false, { open.value = false }, {}, {}, { mutableMapOf() },
                    { requestedPage ->
                        loadCalls++
                        if (loadCalls == 1) {
                            loadEntered.complete(requestedPage)
                            blockedLoad.await()
                        } else {
                            assertEquals(1, requestedPage)
                            page
                        }
                    }, selected::add,
                )
            }
        }

        compose.onNodeWithText("章节名").performClick()
        val body = compose.onNodeWithTag("book-picker-body")
        val results = compose.onNodeWithTag("book-chapter-search-results")
        compose.awaitStable(body)
        compose.awaitStable(results)
        val emptyBounds = compose.boundsInSameFrame(body, results)

        val query = compose.onNodeWithTag("book-chapter-title-query")
        compose.awaitStable(query)
        // 单独验证搜索状态布局；IME改变可用窗口高度是另一种场景。
        query.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("原始")) }
        compose.waitUntil(5_000) { loadEntered.isCompleted }
        compose.awaitStable(compose.onNodeWithText("停止"))
        compose.awaitStable(body)
        compose.awaitStable(results)
        val loadingBounds = compose.boundsInSameFrame(body, results)
        assertSameSize(emptyBounds[0], loadingBounds[0], "加载中内容区")
        assertSameSize(emptyBounds[1], loadingBounds[1], "加载中结果区")

        compose.onNodeWithText("停止").performClick()
        val resume = compose.onNodeWithText("继续查找")
        compose.awaitStable(resume)
        compose.awaitStable(compose.onNodeWithText("已停止", substring = true))
        compose.awaitStable(body)
        compose.awaitStable(results)
        val stoppedBounds = compose.boundsInSameFrame(body, results)
        assertSameSize(emptyBounds[0], stoppedBounds[0], "停止后内容区")
        assertSameSize(emptyBounds[1], stoppedBounds[1], "停止后结果区")

        resume.performClick()
        val originalTitle = compose.onNodeWithText("原始章节标题")
        compose.awaitStable(originalTitle)
        compose.awaitStable(body)
        compose.awaitStable(results)
        val resultBounds = compose.boundsInSameFrame(body, results)
        assertSameSize(emptyBounds[0], resultBounds[0], "结果内容区")
        assertSameSize(emptyBounds[1], resultBounds[1], "结果列表区")
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithTag("book-chapter-picker").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-chapter-search-polished.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        originalTitle.performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(1, selected.size)
        assertSame(song, selected.single().song)
        assertEquals("原始章节标题", selected.single().song.name)
        assertEquals(page, selected.single().page)
    }

    @Test fun shortCatalogWrapsItsSingleRowInsteadOfKeepingEmptyHalfScreen() {
        compose.setContent { MeloraTheme {
            BookChapterPicker(297, 1, false, {}, {}, {}, { mutableMapOf() }, { error("unexpected search") }, {})
        } }
        val drawer = compose.onNodeWithTag("book-chapter-picker")
        compose.awaitStable(drawer)
        val bounds = drawer.getUnclippedBoundsInRoot()
        assertTrue("少量分段不应撑出大片空白", bounds.bottom - bounds.top < 330.dp)
        compose.onNodeWithContentDescription("目录 201–297").assertIsDisplayed()
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = drawer.captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-picker-compact-297.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    // 使用真实系统字号运行此用例；ModalBottomSheet是独立窗口，外部LocalDensity覆盖不能代表其字号。
    @Test fun labelsStayUnclippedAndBothInputsNamed() {
        compose.setContent { MeloraTheme {
            BookChapterPicker(297, 1, false, {}, {}, {}, { mutableMapOf() },
                { error("unexpected search") }, {})
        } }
        compose.awaitStable(compose.onNodeWithTag("book-chapter-picker"))
        for (label in listOf("集数定位", "章节名", "定位", "目录分段", "每 100 条", "1–100")) {
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val layout = layouts.single()
            assertEquals("$label 不应省略字", label.length, layout.getLineEnd(0, visibleEnd = true))
            for (offset in label.indices) {
                val glyph = layout.getBoundingBox(offset)
                assertTrue("$label 字形不能超出可见宽度", glyph.left >= -1 && glyph.right <= bounds.width + 1)
                assertTrue("$label 字形不能被父容器裁掉", glyph.top >= -1 && glyph.bottom <= bounds.height + 1)
            }
        }
        compose.onNode(hasContentDescription("集数或章号") and hasSetTextAction()).assertExists()
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithTag("book-chapter-picker").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-picker-font-validated.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("章节名").performClick()
        compose.awaitStable(compose.onNodeWithTag("book-chapter-title-query"))
        compose.onNode(hasContentDescription("章节名") and hasSetTextAction()).assertExists()
    }

    private fun assertSameSize(expected: Rect, actual: Rect, state: String) {
        assertEquals("$state 宽度变化", expected.width, actual.width, 0f)
        assertEquals("$state 高度变化", expected.height, actual.height, 0f)
    }

    @Test fun invalidOrdinalCannotBeSubmittedAndUnknownTotalDoesNotInventRanges() {
        compose.setContent { MeloraTheme { BookChapterPicker(null, 1, false, {}, {}, {}, { mutableMapOf() }, { error("unexpected search") }, {}) } }
        compose.onNodeWithContentDescription("目录 1–100").assertDoesNotExist()
        compose.onNodeWithText("定位").assertIsNotEnabled()
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("0")
        compose.onNodeWithText("定位").assertIsNotEnabled()
        field.performTextReplacement("1000")
        compose.onNodeWithText("定位").assertIsEnabled()
    }
}
