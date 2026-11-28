package com.leyu.melora.ui.common

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AddToPlaylistSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulWriteDeliversSelectionResultWhenBackInterruptsExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UserLibrary.init(instrumentation.targetContext)
        val playlist = UserLibrary.createPlaylist("退场测试 ${System.nanoTime()}")
        val song = OnlineSong(JSONObject()
            .put("source", "kw")
            .put("songmid", "playlist-sheet-race")
            .put("name", "测试歌曲"))
        val added = AtomicInteger()
        val dismissed = AtomicInteger()
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                MeloraTheme {
                    if (visible.value) {
                        AddToPlaylistSheet(
                            songs = listOf(song),
                            onAdded = { added.incrementAndGet() },
                            onDismiss = {
                                dismissed.incrementAndGet()
                                visible.value = false
                            },
                        )
                    }
                }
            }
            val row = compose.onNodeWithText(playlist.name)
            compose.awaitStable(row)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            row.performClick()
            compose.waitUntil(5_000) {
                UserLibrary.playlists.value.any { it.id == playlist.id && it.songs.singleOrNull()?.uid == song.uid }
            }
            // 冻结自动计时后，逐帧调度写入的回调，在退场刚启动时停下。
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                added.get() > 0
            }

            compose.mainClock.advanceTimeByFrame()
            instrumentation.waitForIdleSync()
            row.assertIsNotEnabled()
            assertEquals(0, dismissed.get())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5_000) { dismissed.get() > 0 }
            compose.waitForIdle()

            val stored = UserLibrary.playlists.value.single { it.id == playlist.id }
            assertEquals(listOf(song.uid), stored.songs.map(OnlineSong::uid))
            assertEquals(1, added.get())
            assertEquals(1, dismissed.get())
            row.assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
            UserLibrary.deletePlaylist(playlist.id)
        }
    }
}
