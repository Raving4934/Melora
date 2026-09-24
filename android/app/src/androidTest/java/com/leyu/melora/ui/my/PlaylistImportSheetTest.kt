package com.leyu.melora.ui.my

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        1, 0, warning,
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
                    savePlaylist = { name, songs ->
                        saves.incrementAndGet()
                        if (saveFails) error("fixture storage failure")
                        UserLibrary.UserPlaylist("fixture", name, songs)
                    },
                )
              }
            }
        }
        if (initialLink != null) {
            compose.onNodeWithTag("playlist-import-link").performTextInput(initialLink)
        }
        if (autoRead) {
            compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        }
    }

    private fun hasText(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun loadingCopyVisible() =
        hasText("正在识别分享链接") || hasText("正在读取歌单…")

    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
        val sheet = compose.onNodeWithTag("playlist-import-sheet").fetchSemanticsNode().boundsInRoot
        // 收起键盘会整体移动抽屉；这里只比较抽屉内部的状态/操作布局，不把系统IME位移当抖动。
        return compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.translate(-sheet.left, -sheet.top)
    }

    private fun assertSameBounds(expected: androidx.compose.ui.geometry.Rect, actual: androidx.compose.ui.geometry.Rect) {
        assertEquals("left", expected.left, actual.left, 1f)
        assertEquals("top", expected.top, actual.top, 1f)
        assertEquals("right", expected.right, actual.right, 1f)
        assertEquals("bottom", expected.bottom, actual.bottom, 1f)
    }

    private fun assertStatusHeight(bounds: androidx.compose.ui.geometry.Rect) {
        assertEquals("状态区应固定为 136dp", 136f * compose.density.density, bounds.height, 2f)
    }

    private fun captureSheet(filename: String) {
        val bitmap = compose.onNodeWithTag("playlist-import-sheet").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, filename)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun loadingAndPreviewKeepActionsStableAndOnlyExplicitConfirmationSaves() {
        val ready = CompletableDeferred<PlaylistImportResult>()
        show(read = { _, _, progress -> progress(PlaylistImportProgress(2, 100, 250)); ready.await() })
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        compose.waitForIdle()
        val before = compose.onNodeWithTag("playlist-import-submit").fetchSemanticsNode().boundsInRoot
        assertEquals(0, saves.get())
        ready.complete(result())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("导入 1 首").fetchSemanticsNodes().isNotEmpty() }
        val after = compose.onNodeWithTag("playlist-import-submit").fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, 1f)
        assertEquals(before.top, after.top, 2f)
        assertEquals(0, saves.get())
        captureSheet("playlist-import-preview.png")
        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

    @Test fun partialResultRequiresExplicitPartialImportAndCanBeReread() {
        show(read = { _, _, _ -> result("第 2 页读取失败，尚未获取完整歌单。") })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("仅导入已获取的 1 首").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, saves.get())
        compose.onNodeWithText("重新读取").performScrollTo().performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }
        assertEquals(0, saves.get())
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

    @Test fun cancellingReadCancelsWorkWithoutCreatingPlaylist() {
        val cancelled = AtomicBoolean()
        show(read = { _, _, _ ->
            try { CompletableDeferred<PlaylistImportResult>().await() } finally { cancelled.set(true) }
        })
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.waitUntil(5_000) { dismissed.get() && cancelled.get() }
        assertEquals(0, saves.get())
    }

    @Test fun saveFailureKeepsPreviewAndAllowsRetryInsteadOfReportingSuccess() {
        show(saveFails = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("导入 1 首").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist-import-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-name").assertExists()
        compose.onNodeWithTag("playlist-import-submit").assertIsEnabled()
        assertFalse(dismissed.get())
        assertEquals(1, saves.get())
    }
    @Test fun smallViewportCanScrollToConfirmWithoutClippingActions() {
        show(compact = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("导入 1 首").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

    @Test fun quickFailureAndRetryDoNotLeaveTransientLoadingCopy() {
        show(
            autoRead = false,
            read = { _, _, _ -> throw IllegalStateException("读取失败") },
        )
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { reads.get() == 1 && hasText("读取失败") }
        compose.onNodeWithTag("playlist-import-error").assertExists()

        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
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
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().assertIsDisplayed()
        val initialSubmit = bounds("playlist-import-submit")
        val initialStatus = bounds("playlist-import-status")
        assertStatusHeight(initialStatus)
        captureSheet("playlist-import-initial.png")

        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { hasText("旧读取错误") }
        compose.onNodeWithTag("playlist-import-error").assertExists()
        assertSameBounds(initialSubmit, bounds("playlist-import-submit"))
        assertSameBounds(initialStatus, bounds("playlist-import-status"))

        compose.onNodeWithTag("playlist-import-submit").performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        compose.onNodeWithText("旧读取错误").assertExists()
        assertSameBounds(initialSubmit, bounds("playlist-import-submit"))

        compose.waitUntil(5_000) { loadingCopyVisible() }
        compose.onNodeWithTag("playlist-import-progress").assertExists()
        val indicator = bounds("playlist-import-progress")
        assertEquals(
            "进度指示器应位于状态区底部 32dp 槽的垂直中心",
            initialStatus.bottom - 16f * compose.density.density,
            indicator.center.y,
            compose.density.density,
        )
        assertSameBounds(initialStatus, bounds("playlist-import-status"))

        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performTouchInput {
            repeat(3) { click() }
        }
        assertEquals("busy 期间的重复点击不能启动第二次请求", 2, reads.get())

        response.complete(result())
        compose.waitUntil(5_000) {
            hasText("导入 1 首") && !loadingCopyVisible() &&
                compose.onAllNodesWithTag("playlist-import-progress").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("playlist-import-name").assertExists()
        assertSameBounds(initialSubmit, bounds("playlist-import-submit"))
        assertSameBounds(initialStatus, bounds("playlist-import-status"))
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
        val previewSubmit = bounds("playlist-import-submit")
        val previewStatus = bounds("playlist-import-status")
        assertStatusHeight(previewStatus)
        compose.onNodeWithText("重新读取").performScrollTo().performClick()
        compose.waitUntil(5_000) { reads.get() == 2 }

        compose.onNodeWithTag("playlist-import-name").assertExists()
        compose.onNodeWithTag("playlist-import-submit").assertIsNotEnabled()
        assertSameBounds(previewSubmit, bounds("playlist-import-submit"))
        assertSameBounds(previewStatus, bounds("playlist-import-status"))

        reread.completeExceptionally(IllegalStateException("重读网络失败"))
        compose.waitUntil(5_000) { hasText("读取失败，保留上次预览。重读网络失败") }
        compose.onNodeWithTag("playlist-import-name").assertExists()
        compose.onNodeWithTag("playlist-import-error").assertExists()
        compose.onNodeWithText("读取失败，保留上次预览。重读网络失败").assertExists()
        compose.onNodeWithText("导入 1 首").assertExists()
        compose.onNodeWithText("重新读取").assertIsEnabled()
        compose.onNodeWithTag("playlist-import-submit").assertIsEnabled()
        assertSameBounds(previewSubmit, bounds("playlist-import-submit"))
        assertSameBounds(previewStatus, bounds("playlist-import-status"))

        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { saves.get() == 1 }
    }

}
