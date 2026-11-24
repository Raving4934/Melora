package com.leyu.melora.ui.common

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.local.LocalSongRow
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.MeloraTheme
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongPlaybackIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun indicatorFollowsTheClickedSongAndPausesWithoutMovingItsTitle() {
        val online = OnlineSong(JSONObject().put("source", "kw").put("songmid", "test-online")
            .put("name", "需要始终以单行省略显示的长标题".repeat(3)).put("singer", "在线歌手"))
        val local = LocalSong(
            id = "test-local", uri = "file:///music/test.mp3", title = "本地歌曲", artist = "本地歌手",
            album = "", durationMs = 120_000, sizeBytes = 1_000, mimeType = "audio/mpeg",
            sampleRate = 44_100, bitrate = 128_000, modifiedAt = 1, addedAt = 1, folder = "music",
        )
        val onlineTrack = UiTrack.fromOnline(online)
        val localTrack = UiTrack.fromOnline(local.toOnlineSong())
        val current = mutableStateOf<UiTrack?>(null)
        val advancing = mutableStateOf(false)
        val shared = SongListState(mutableStateOf(listOf(local)), mutableStateOf(false), current, advancing)
        val onlineRowTag = "online-song-row"
        val localRowTag = "local-song-row"

        compose.setContent {
            MeloraTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    Box(Modifier.fillMaxSize().background(MeloraAppearance.canvas)) {
                        Column(Modifier.width(224.dp)) {
                            Box(Modifier.testTag(onlineRowTag)) {
                                OnlineSongRow(online, onClick = {
                                    current.value = onlineTrack
                                    advancing.value = true
                                })
                            }
                            Box(Modifier.testTag(localRowTag)) {
                                LocalSongRow(
                                    song = local, selectionMode = false, selected = false,
                                    isCurrent = current.value?.uid == localTrack.uid,
                                    onClick = {
                                        current.value = localTrack
                                        advancing.value = true
                                    },
                                    onMore = null,
                                )
                            }
                        }
                    }
                }
            }
        }
        val onlineRow = compose.onNodeWithTag(onlineRowTag, useUnmergedTree = true)
        val localRow = compose.onNodeWithTag(localRowTag, useUnmergedTree = true)
        val onlineTitle = compose.onNodeWithText(online.name, useUnmergedTree = true)
        val localTitle = compose.onNodeWithText(local.title, useUnmergedTree = true)
        val playingBars = compose.onNodeWithContentDescription("正在播放", useUnmergedTree = true)
        val pausedBars = compose.onNodeWithContentDescription("当前曲目", useUnmergedTree = true)

        playingBars.assertDoesNotExist()
        pausedBars.assertDoesNotExist()
        val initialOnline = relativeBounds(onlineRow, onlineTitle).single()
        val initialLocal = relativeBounds(localRow, localTitle).single()

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText(online.name).performClick()
            compose.mainClock.advanceTimeBy(128)
            playingBars.assert(hasAnyAncestor(hasTestTag(onlineRowTag)))
            val (activeTitle, activeBars) = relativeBounds(onlineRow, onlineTitle, playingBars)
            assertTrue("播放标识应位于在线歌曲标题左侧", activeBars.right <= activeTitle.left)
            assertEquals(initialOnline.top, activeTitle.top, 0.5f)
            assertEquals(initialLocal, relativeBounds(localRow, localTitle).single())
            assertSingleEllipsizedLine(onlineTitle)
            val first = playingBars.barHeights()
            compose.mainClock.advanceTimeBy(240)
            assertTrue("播放时应有音柱高度变化", first.zip(playingBars.barHeights()).any { (a, b) -> abs(a - b) > 1 })
            assertEquals(listOf(activeTitle, activeBars), relativeBounds(onlineRow, onlineTitle, playingBars))

            compose.runOnIdle { advancing.value = false }
            compose.mainClock.advanceTimeBy(128)
            playingBars.assertDoesNotExist()
            assertEquals(listOf(activeTitle, activeBars), relativeBounds(onlineRow, onlineTitle, pausedBars))
            val paused = pausedBars.barHeights()
            compose.mainClock.advanceTimeBy(240)
            assertTrue("暂停时音柱应保持静止", paused.zip(pausedBars.barHeights()).all { (a, b) -> abs(a - b) <= 1 })

            compose.runOnIdle { advancing.value = true }
            compose.mainClock.advanceTimeBy(128)
            val resumed = playingBars.barHeights()
            compose.mainClock.advanceTimeBy(240)
            assertTrue("复播后音柱应重新跳动", resumed.zip(playingBars.barHeights()).any { (a, b) -> abs(a - b) > 1 })
            pausedBars.assertDoesNotExist()

            compose.onNodeWithText(local.title).performClick()
            compose.mainClock.advanceTimeBy(128)
            playingBars.assert(hasAnyAncestor(hasTestTag(localRowTag)))
            val (shiftedLocal, switchedBars) = relativeBounds(localRow, localTitle, playingBars)
            assertTrue("切歌后标识应移到本地歌曲标题左侧", switchedBars.right <= shiftedLocal.left)
            // 切歌后旧标识消失，上一首的标题恢复到原位置。
            assertEquals(initialOnline, relativeBounds(onlineRow, onlineTitle).single())
            assertEquals(initialLocal.top, shiftedLocal.top, 0.5f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun relativeBounds(row: SemanticsNodeInteraction, vararg nodes: SemanticsNodeInteraction): List<Rect> {
        val allBounds = compose.boundsInSameFrame(row, *nodes)
        val rowBounds = allBounds.first()
        return allBounds.drop(1).map { it.translate(-rowBounds.left, -rowBounds.top) }
    }

    private fun assertSingleEllipsizedLine(title: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
    }

    private fun SemanticsNodeInteraction.barHeights(): List<Int> {
        val bitmap = captureToImage().asAndroidBitmap()
        return try {
            // 取三根蓝色音柱的中轴，忽略点击涟漪和设备截图的背景色微差。
            List(3) { index ->
                val x = bitmap.width * (2 * index + 1) / 6
                (0 until bitmap.height).count { y ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.blue(pixel) - Color.red(pixel) > 70
                }.also { assertTrue("应能看到每根音柱", it > 0) }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
