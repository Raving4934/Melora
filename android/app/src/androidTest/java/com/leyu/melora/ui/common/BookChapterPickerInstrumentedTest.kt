package com.leyu.melora.ui.common

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.toPixelMap
import com.leyu.melora.ui.theme.MeloraAppearance
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

    @Test fun oneInputAutomaticallyChangesActionWithoutSwitchingKeyboardOrButtonWidth() {
        val open = mutableStateOf(true)
        val selected = mutableListOf<Int>()
        compose.setContent { MeloraTheme {
            if (open.value) BookChapterPicker(1568, 1, false, { open.value = false }, selected::add, {},
                { mutableMapOf() }, { error("preview search interrupted") }, {})
        } }
        val field = compose.onNodeWithTag("book-chapter-query")
        val action = compose.onNodeWithTag("book-chapter-query-action")
        compose.awaitStable(field)
        compose.onNodeWithText("集数定位").assertDoesNotExist()
        compose.onNodeWithTag("book-picker-mode").assertDoesNotExist()
        compose.onNodeWithContentDescription("定位集数").assertIsNotEnabled()
        compose.onNodeWithText("定位").assertDoesNotExist()
        val initialWidth = action.fetchSemanticsNode().boundsInRoot.width
        field.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("仙卫")) }
        compose.awaitStable(action)
        compose.onNodeWithContentDescription("搜索章节名").assertIsEnabled()
        compose.onNodeWithText("搜索").assertDoesNotExist()
        if (Build.VERSION.SDK_INT >= 26) {
            val pixels = action.captureToImage().toPixelMap()
            val corner = pixels[pixels.width / 8, pixels.height / 8]
            val fill = MeloraAppearance.softFill
            assertEquals("操作图标不能有独立底块", fill.red, corner.red, .02f)
            assertEquals("操作图标应共用输入框背景", fill.green, corner.green, .02f)
            assertEquals(fill.blue, corner.blue, .02f)
        }
        assertEquals(initialWidth, action.fetchSemanticsNode().boundsInRoot.width, 0f)
        assertEquals(androidx.compose.ui.text.input.ImeAction.Search,
            field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ImeAction])
        field.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("第42章")) }
        compose.awaitStable(action)
        compose.onNodeWithContentDescription("定位集数").assertIsEnabled()
        assertEquals(initialWidth, action.fetchSemanticsNode().boundsInRoot.width, 0f)
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        field.performImeAction()
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

        val query = compose.onNodeWithTag("book-chapter-query")
        compose.awaitStable(query)
        query.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("原始")) }
        compose.waitUntil(5_000) { loadEntered.isCompleted }
        val body = compose.onNodeWithTag("book-picker-body")
        val results = compose.onNodeWithTag("book-chapter-search-results")
        compose.awaitStable(body)
        compose.awaitStable(results)
        val emptyBounds = compose.boundsInSameFrame(body, results)

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
    @Test fun labelsStayUnclippedAndUnifiedInputNamed() {
        compose.setContent { MeloraTheme {
            BookChapterPicker(297, 1, false, {}, {}, {}, { mutableMapOf() },
                { error("unexpected search") }, {})
        } }
        compose.awaitStable(compose.onNodeWithTag("book-chapter-picker"))
        for (label in listOf("目录分段", "每 100 条", "1–100")) {
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
        compose.onNode(hasContentDescription("集数或章节名") and hasSetTextAction()).assertExists()
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithTag("book-chapter-picker").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-picker-font-validated.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun numericTitleCanBeSearchedExplicitlyWithoutLocatingAnEpisode() {
        val open = mutableStateOf(true)
        val episodes = mutableListOf<Int>()
        val selected = mutableListOf<BookChapterMatch>()
        var loads = 0
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "numeric-title").put("name", "1984"))
        compose.setContent { MeloraTheme {
            if (open.value) BookChapterPicker(297, 1, false, { open.value = false }, episodes::add, {},
                { mutableMapOf() }, { loads++; KwBookApi.BookChapters(listOf(song), false) }, selected::add)
        } }
        val field = compose.onNodeWithTag("book-chapter-query")
        compose.awaitStable(field)
        field.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("1984")) }
        compose.awaitStable(compose.onNodeWithText("按章节名查找"))
        assertEquals("纯数字不会自动扫描整本目录", 0, loads)
        compose.onNodeWithText("按章节名查找").performClick()
        compose.awaitStable(compose.onNodeWithText("找到 1 条"))
        compose.onNode(hasText("1984") and hasAnyAncestor(hasTestTag("book-chapter-search-results"))).performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertTrue(episodes.isEmpty())
        assertSame(song, selected.single().song)
    }

    @Test fun clearingInputCancelsSearchAndRestoresDirectorySegments() {
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<KwBookApi.BookChapters>()
        compose.setContent { MeloraTheme {
            BookChapterPicker(297, 1, false, {}, {}, {}, { mutableMapOf() },
                { started.complete(Unit); pending.await() }, {})
        } }
        val field = compose.onNodeWithTag("book-chapter-query")
        compose.awaitStable(field)
        field.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("远处")) }
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithContentDescription("清空选集输入").performClick()
        compose.awaitStable(compose.onNodeWithContentDescription("目录 201–297"))
        pending.complete(KwBookApi.BookChapters(emptyList(), false))
        compose.onNodeWithTag("book-chapter-search-results").assertDoesNotExist()
        compose.onNodeWithContentDescription("定位集数").assertIsNotEnabled()
        field.assertTextEquals("")
    }

    private fun assertSameSize(expected: Rect, actual: Rect, state: String) {
        assertEquals("$state 宽度变化", expected.width, actual.width, 0f)
        assertEquals("$state 高度变化", expected.height, actual.height, 0f)
    }

    @Test fun invalidOrdinalCannotBeSubmittedAndUnknownTotalDoesNotInventRanges() {
        compose.setContent { MeloraTheme { BookChapterPicker(null, 1, false, {}, {}, {}, { mutableMapOf() }, { error("unexpected search") }, {}) } }
        compose.onNodeWithContentDescription("目录 1–100").assertDoesNotExist()
        compose.onNodeWithContentDescription("定位集数").assertIsNotEnabled()
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("0")
        compose.onNodeWithContentDescription("定位集数").assertIsNotEnabled()
        field.performTextReplacement("1000")
        compose.onNodeWithContentDescription("定位集数").assertIsEnabled()
    }
}
