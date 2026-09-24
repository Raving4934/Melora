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
        compose.onNodeWithTag("playlist-import-link").performTextInput("https://music.163.com/#/playlist?id=123")
        compose.onNodeWithTag("playlist-import-submit").performScrollTo().performClick()
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
        val bitmap = compose.onNodeWithTag("playlist-import-sheet").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "playlist-import-preview.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

}
