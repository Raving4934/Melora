package com.leyu.melora.ui.my

import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PlaylistImportProgress
import com.leyu.melora.playback.sdk.PlaylistImportResult
import com.leyu.melora.ui.theme.MeloraTheme
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlaylistImportSheetTest {
    @get:Rule val compose = createComposeRule()
    private val saves = AtomicInteger()
    private val reads = AtomicInteger()
    private val dismissed = AtomicBoolean()
    private fun result(warning: String? = null) = PlaylistImportResult(
        "网易云音乐", "我的测试歌单", null,
        listOf(OnlineSong(JSONObject().put("source", "wy").put("songmid", "fixture").put("name", "测试单曲"))),
        1, 0, warning, com.leyu.melora.playback.sdk.PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123"),
    )
    private fun show(
        read: suspend (Context, String, (PlaylistImportProgress) -> Unit) -> PlaylistImportResult = { _, _, _ -> result() },
        saveFails: Boolean = false,
        compact: Boolean = false,
        initialLink: String? = "https://music.163.com/#/playlist?id=123",
        autoRead: Boolean = true,
    ) {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalDensity provides if (compact) Density(5f) else LocalDensity.current) {
              MeloraTheme {
                if (open) PlaylistImportSheet(
                    onDismiss = { open = false; dismissed.set(true) },
                    onImported = { open = false },
                    readPlaylist = { context, text, progress -> reads.incrementAndGet(); read(context, text, progress) },
                    library = kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
                    savePlaylist = { name, loaded, allowCopy ->
                        assertFalse(allowCopy)
                        saves.incrementAndGet()
                        if (saveFails) error("fixture storage failure")
                        assertEquals(result().importSource, loaded.importSource)
                        UserLibrary.UserPlaylist("fixture", name, loaded.songs, loaded.importSource,
                            loaded.songs.mapTo(linkedSetOf()) { it.uid })
                    },
                )
              }
            }
        }
        if (initialLink != null) {
            compose.onNodeWithTag("playlist-import-link").performTextInput(initialLink)
        }
        if (autoRead) {
            compose.waitForIdle()
            compose.onNodeWithTag("playlist-import-submit").assertIsEnabled()
            compose.awaitStable("playlist-import-submit")
            compose.onNodeWithTag("playlist-import-submit").performClick()
        }
    }

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun loadingCopyVisible() =
        hasText("正在识别分享链接") || hasText("正在读取歌单…")

    // 每个状态独立采样；容器和子节点必须同帧，避免将IME整体位移误判为内容跳动。
    private fun bounds(vararg tags: String): List<Rect> {
        val nodes = compose.boundsInSameFrame(
            compose.onNodeWithTag("playlist-import-sheet"),
            *tags.map { compose.onNodeWithTag(it) }.toTypedArray(),
        )
        val sheet = nodes.first()
        return nodes.drop(1).map { it.translate(-sheet.left, -sheet.top) }
    }

    private fun assertSameBounds(expected: androidx.compose.ui.geometry.Rect, actual: androidx.compose.ui.geometry.Rect) {
        assertEquals("left", expected.left, actual.left, 1f)
        assertEquals("top", expected.top, actual.top, 1f)
        assertEquals("right", expected.right, actual.right, 1f)
        assertEquals("bottom", expected.bottom, actual.bottom, 1f)
    }

    private fun captureSheet(filename: String) {
        val bitmap = compose.onNodeWithTag("playlist-import-sheet").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, filename)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun loadingAndPreviewKeepActionsStableAndOnlyExplicitConfirmationSaves() {
        val ready = CompletableDeferred<PlaylistImportResult>()
        show(read = { _, _, progress -> progress(PlaylistImportProgress(2, 100, 250)); ready.await() })
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.get() > 0 }
        compose.runOnIdle { assertEquals("one submit starts one read", 1, reads.get()) }
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        compose.waitForIdle()
        // 抽屉内相对坐标同帧采样，不把 IME 位移当抖动。
        val (before, headerBefore, bodyBefore) = bounds("playlist-import-submit", "playlist-import-header", "playlist-import-body")
        assertEquals(0, saves.get())
        ready.complete(result())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("导入 1 首").fetchSemanticsNodes().isNotEmpty() }
        val (after, headerAfter, bodyAfter) = bounds("playlist-import-submit", "playlist-import-header", "playlist-import-body")
        assertSameBounds(before, after)
        assertSameBounds(headerBefore, headerAfter)
        assertSameBounds(bodyBefore, bodyAfter)
        assertEquals(0, saves.get())
        captureSheet("playlist-import-preview.png")
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

    @Test fun partialResultRequiresExplicitPartialImportAndCanBeReread() {
        show(read = { _, _, _ -> result("第 2 页读取失败，尚未获取完整歌单。") })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("仅导入已获取的 1 首").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, saves.get())
        compose.onNodeWithContentDescription("重新读取").performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }
        assertEquals(0, saves.get())
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

    @Test fun cancellingReadCancelsWorkWithoutCreatingPlaylist() {
        val cancelled = AtomicBoolean()
        show(read = { _, _, _ ->
            try { CompletableDeferred<PlaylistImportResult>().await() } finally { cancelled.set(true) }
        })
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(5_000) { dismissed.get() && cancelled.get() }
        assertEquals(0, saves.get())
    }

    @Test fun loadedLongLinkCollapsesAndRemainsEditableWithoutMovingActions() {
        val sharedText = "https://music.163.com/#/playlist?id=123&share=" + "long-link-segment-".repeat(18)
        show(
            autoRead = false,
            initialLink = sharedText,
            read = { _, input, _ -> assertEquals(sharedText, input); result() },
        )
        compose.awaitStable("playlist-import-submit")
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-import-link-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-link").assertDoesNotExist()
        compose.onNodeWithTag("playlist-import-edit-link").assertIsDisplayed()
        compose.onNodeWithText("粘贴").assertDoesNotExist()
        compose.onNodeWithContentDescription("重新读取").assertIsDisplayed()
        val (loadedSubmit, loadedHeader, loadedBody) = bounds("playlist-import-submit", "playlist-import-header", "playlist-import-body")

        compose.onNodeWithTag("playlist-import-edit-link").performClick()
        compose.onNodeWithText("粘贴").assertIsDisplayed()
        compose.onNodeWithTag("playlist-import-link").assertTextEquals(sharedText)
        compose.onNodeWithTag("playlist-import-collapse-link").performClick()
        compose.onNodeWithTag("playlist-import-link-preview").assertExists()
        val (collapsedSubmit, collapsedHeader, collapsedBody) = bounds("playlist-import-submit", "playlist-import-header", "playlist-import-body")
        assertSameBounds(loadedSubmit, collapsedSubmit)
        assertSameBounds(loadedHeader, collapsedHeader)
        assertSameBounds(loadedBody, collapsedBody)
    }

    @Test fun saveFailureKeepsPreviewAndAllowsRetryInsteadOfReportingSuccess() {
        show(saveFails = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("导入 1 首").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-import-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-name").assertExists()
        compose.onNodeWithTag("playlist-import-submit").assertIsEnabled()
        assertFalse(dismissed.get())
        assertEquals(1, saves.get())
    }
    @Test fun smallViewportCanScrollToConfirmWithoutClippingActions() {
        show(compact = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("导入 1 首").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-submit").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

    @Test fun quickFailureAndRetryDoNotLeaveTransientLoadingCopy() {
        show(
            autoRead = false,
            read = { _, _, _ -> throw IllegalStateException("读取失败") },
        )
        compose.awaitStable("playlist-import-submit")
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { reads.get() == 1 && hasText("读取失败") }
        compose.onNodeWithTag("playlist-import-error").assertExists()

        compose.awaitStable("playlist-import-submit")
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { reads.get() == 2 && hasText("读取失败") }
        compose.onNodeWithTag("playlist-import-error").assertExists()
        captureSheet("playlist-import-error.png")
        assertFalse("快失败重试后不应残留进度文案", loadingCopyVisible())
        compose.onNodeWithTag("playlist-import-progress").assertDoesNotExist()
        compose.onNodeWithTag("playlist-import-submit").assertIsEnabled()
        assertEquals(0, saves.get())
    }

    @Test fun retryKeepsErrorRejectsDuplicateReadsAndPreservesLayout() {
        val response = CompletableDeferred<PlaylistImportResult>()
        show(
            read = { _, _, _ ->
                if (reads.get() == 1) throw IllegalStateException("旧读取错误")
                response.await()
            },
            autoRead = false,
        )
        // 比较相同IME状态：小屏收起键盘会增加可用高度，不属于读取状态导致的布局跳动。
        Espresso.closeSoftKeyboard()
        compose.awaitStable("playlist-import-submit")
        compose.onNodeWithTag("playlist-import-submit").assertIsDisplayed()
        val (initialSubmit, initialBody, initialProgressSlot) = bounds(
            "playlist-import-submit", "playlist-import-body", "playlist-import-progress-slot",
        )
        captureSheet("playlist-import-initial.png")

        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { hasText("旧读取错误") }
        compose.onNodeWithTag("playlist-import-error").assertExists()
        val (errorSubmit, errorBody, errorProgressSlot) = bounds(
            "playlist-import-submit", "playlist-import-body", "playlist-import-progress-slot",
        )
        assertSameBounds(initialSubmit, errorSubmit)
        assertSameBounds(initialBody, errorBody)
        assertSameBounds(initialProgressSlot, errorProgressSlot)

        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        compose.onNodeWithText("旧读取错误").assertExists()
        compose.waitUntil(5_000) { loadingCopyVisible() }
        compose.onNodeWithTag("playlist-import-progress").assertExists()
        val (busySubmit, busyBody, busyProgressSlot, indicator) = bounds(
            "playlist-import-submit", "playlist-import-body", "playlist-import-progress-slot", "playlist-import-progress",
        )
        assertSameBounds(initialSubmit, busySubmit)
        assertEquals(
            "进度指示器应位于固定进度槽的垂直中心",
            initialProgressSlot.center.y,
            indicator.center.y,
            compose.density.density,
        )
        assertSameBounds(initialBody, busyBody)
        assertSameBounds(initialProgressSlot, busyProgressSlot)

        compose.onNodeWithTag("playlist-import-submit").performTouchInput {
            repeat(3) { click() }
        }
        assertEquals("busy 期间的重复点击不能启动第二次请求", 2, reads.get())

        response.complete(result())
        compose.waitUntil(5_000) {
            hasText("导入 1 首") && !loadingCopyVisible() &&
                compose.onAllNodesWithTag("playlist-import-progress").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("playlist-import-name").assertExists()
        val (completeSubmit, completeBody, completeProgressSlot) = bounds(
            "playlist-import-submit", "playlist-import-body", "playlist-import-progress-slot",
        )
        assertSameBounds(initialSubmit, completeSubmit)
        assertSameBounds(initialBody, completeBody)
        assertSameBounds(initialProgressSlot, completeProgressSlot)
        assertEquals(0, saves.get())
    }

    @Test fun failedRereadKeepsPreviewAndStillAllowsImport() {
        val reread = CompletableDeferred<PlaylistImportResult>()
        show(
            read = { _, _, _ ->
                if (reads.get() == 1) result() else reread.await()
            },
        )
        compose.waitUntil(5_000) { hasText("导入 1 首") }
        val (previewSubmit, previewBody) = bounds("playlist-import-submit", "playlist-import-body")
        compose.onNodeWithContentDescription("重新读取").performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }

        compose.onNodeWithTag("playlist-import-name").assertExists()
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        val (rereadingSubmit, rereadingBody) = bounds("playlist-import-submit", "playlist-import-body")
        assertSameBounds(previewSubmit, rereadingSubmit)
        assertSameBounds(previewBody, rereadingBody)

        reread.completeExceptionally(IllegalStateException("重读网络失败"))
        compose.waitUntil(5_000) { hasText("读取失败，保留上次预览。重读网络失败") }
        compose.onNodeWithTag("playlist-import-name").assertExists()
        compose.onNodeWithTag("playlist-import-error").assertExists()
        compose.onNodeWithText("读取失败，保留上次预览。重读网络失败").assertExists()
        compose.onNodeWithText("导入 1 首").assertExists()
        compose.onNodeWithContentDescription("重新读取").assertIsEnabled()
        compose.onNodeWithTag("playlist-import-submit").assertIsEnabled()
        val (rereadErrorSubmit, rereadErrorBody) = bounds("playlist-import-submit", "playlist-import-body")
        assertSameBounds(previewSubmit, rereadErrorSubmit)
        assertSameBounds(previewBody, rereadErrorBody)

        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

}
