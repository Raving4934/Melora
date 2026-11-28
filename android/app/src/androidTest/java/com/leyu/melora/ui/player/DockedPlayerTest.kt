package com.leyu.melora.ui.player

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.LyricWord
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.samplePosition
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.common.LocalGlassBackdrop
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.theme.MeloraTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class DockedPlayerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun standardBarRemainsOpaqueAndKeepsLyricsWithComponentBlur() {
        val originalTheme = MeloraSettings.themeMode.value
        val originalPlayerTheme = MeloraSettings.playerThemeMode.value
        val originalBlurMode = MeloraSettings.componentBlurMode.value
        val track = UiTrack("docked-player-fixture", "测试歌曲", "测试歌手", "测试专辑")
        val playback = mutableStateOf(PlayerUiState(
            ready = true, current = track, queue = listOf(track), currentIndex = 0, positionMs = 57_000L,
        ))
        val warm = Color(0xFFF4542B)
        val cool = Color(0xFF275DF2)
        val songList = SongListState(mutableStateOf(emptyList()), mutableStateOf(false), mutableStateOf(null))
        lateinit var back: PlayerSheetBackState
        var navigationBarHeightPx = 0

        try {
            MeloraSettings.themeMode.value = ThemeMode.Light
            MeloraSettings.playerThemeMode.value = ThemeMode.Dark
            MeloraSettings.componentBlurMode.value = ComponentBlurMode.Off
            compose.setContent {
                MeloraTheme {
                    val source = rememberHazeState()
                    CompositionLocalProvider(LocalSongListState provides songList, LocalGlassBackdrop provides source) {
                        back = rememberPlayerSheetBackState()
                        val navigation = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                        SideEffect { navigationBarHeightPx = navigation }
                        Box(Modifier.fillMaxSize().testTag("docked-player-host")) {
                            Canvas(Modifier.fillMaxSize().hazeSource(source)) {
                                val half = Size(size.width / 2f, size.height)
                                drawRect(warm, size = half)
                                drawRect(cool, topLeft = Offset(half.width, 0f), size = half)
                            }
                            ContinuousPlayerSheet(playback = playback, backState = back)
                        }
                    }
                }
            }

            val host = compose.onNodeWithTag("docked-player-host")
            val bar = compose.onNodeWithTag("player-mini-bar", useUnmergedTree = true)
            compose.awaitStable(bar)
            val hostBounds = host.getUnclippedBoundsInRoot()
            val barBounds = bar.getUnclippedBoundsInRoot()
            val navigation = with(compose.density) { navigationBarHeightPx.toDp() }
            assertEquals(hostBounds.left.value, barBounds.left.value, 1f)
            assertEquals(hostBounds.right.value, barBounds.right.value, 1f)
            assertEquals(hostBounds.bottom.value, barBounds.bottom.value, 1f)
            assertEquals(64f, (barBounds.bottom - barBounds.top - navigation).value, 1.1f)
            compose.onNodeWithText("0:57", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("0:57 / 0:00", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle { playback.value = playback.value.copy(durationMs = 253_000L) }
            compose.onNodeWithText("0:57 / 4:13", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { playback.value = playback.value.copy(durationMs = 0L) }
            compose.onNodeWithContentDescription("播放").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放队列").assertIsDisplayed()

            fun surfaceColors(): Pair<Color, Color> {
                val offset = compose.runOnIdle { back.sheet.requireOffset() }.roundToInt()
                val pixels = host.captureToImage().toPixelMap()
                val gap = with(compose.density) { 4.dp.roundToPx() }
                return pixels[pixels.width / 4, offset + gap] to pixels[pixels.width * 3 / 4, offset + gap]
            }

            val docked = surfaceColors()
            assertEquals("底栏应遮住下方的冷暖色块", docked.first, docked.second)
            compose.runOnIdle { MeloraSettings.componentBlurMode.value = ComponentBlurMode.All }
            compose.awaitStable(bar)
            assertEquals("模糊组件设置不改变底栏材质", docked, surfaceColors())
            assertEquals(barBounds, bar.getUnclippedBoundsInRoot())
            verifyTimedLyrics(track, playback)

            compose.onNodeWithTag("player-mini-track").performClick()
            compose.mainClock.advanceTimeUntil(5_000) {
                back.sheet.settledValue == PlayerSheetAnchor.Expanded && back.transition == null
            }
            val originalOffset = compose.runOnIdle {
                val sheet = back.sheet
                val offset = sheet.requireOffset()
                val travel = sheet.anchors.positionOf(PlayerSheetAnchor.Collapsed)
                sheet.dispatchRawDelta(travel * 0.88f - offset)
                offset
            }
            assertEquals("收回经过迷你条淡入时，底色应保持一致", docked, surfaceColors())
            compose.runOnIdle { back.sheet.dispatchRawDelta(originalOffset - back.sheet.requireOffset()) }
            Espresso.pressBack()
            compose.mainClock.advanceTimeUntil(5_000) {
                back.sheet.settledValue == PlayerSheetAnchor.Collapsed && back.transition == null
            }
            assertEquals(docked, surfaceColors())
            assertEquals(barBounds, bar.getUnclippedBoundsInRoot())
        } finally {
            MeloraSettings.themeMode.value = originalTheme
            MeloraSettings.playerThemeMode.value = originalPlayerTheme
            MeloraSettings.componentBlurMode.value = originalBlurMode
        }
    }

    private fun verifyTimedLyrics(track: UiTrack, playback: MutableState<PlayerUiState>) {
        @Suppress("UNCHECKED_CAST")
        val lyricFlow = PlaybackController.javaClass.getDeclaredField("_lyric").let {
            it.isAccessible = true
            it.get(PlaybackController) as MutableStateFlow<PlayerLyric?>
        }
        val originalLyric = lyricFlow.value
        val originalPlayback = playback.value
        val line = LyricLine(50_000L, "逐字高亮测试", endMs = 52_000L,
            words = listOf(LyricWord("逐字高亮", 50_000L, 51_000L), LyricWord("测试", 51_000L, 52_000L)))
        try {
            compose.runOnIdle {
                lyricFlow.value = PlayerLyric(track.uid, track.title, track.artist, listOf(line), "test")
                playback.value = originalPlayback.samplePosition(50_000L, SystemClock.elapsedRealtime(), advancing = false)
            }
            val lyric = compose.onNode(
                hasText(line.text) and hasAnyAncestor(hasTestTag("player-mini-bar")), useUnmergedTree = true,
            )
            compose.awaitStable(lyric)
            val bounds = lyric.getUnclippedBoundsInRoot()
            val before = lyric.captureToImage().toPixelMap()
            compose.runOnIdle {
                playback.value = originalPlayback.samplePosition(51_400L, SystemClock.elapsedRealtime(), advancing = false)
            }
            assertEquals("逐字进度不会改变歌词的尺寸与位置", bounds, lyric.getUnclippedBoundsInRoot())
            val after = lyric.captureToImage().toPixelMap()
            var changedPixels = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                if (abs(before[x, y].luminance() - after[x, y].luminance()) > 0.05f) changedPixels++
            }
            assertTrue("同一行歌词应随播放位置逐字高亮，变化像素 $changedPixels", changedPixels > 10)
        } finally {
            compose.runOnIdle {
                lyricFlow.value = originalLyric
                playback.value = originalPlayback
            }
        }
    }
}
