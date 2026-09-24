package com.leyu.melora.ui.player

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.LyricsUiConfig
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.LyricWord
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.theme.SystemBarsAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersivePlayerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val immersive = mutableStateOf(false)
    private lateinit var view: View

    private fun showPlayer(
        motionEnabled: Boolean = false,
        compact: Boolean = false,
        landscape: Boolean = false,
        observeCoverStyle: Boolean = false,
        linesOverride: androidx.compose.runtime.State<List<LyricLine>>? = null,
        playerVisible: androidx.compose.runtime.State<Boolean>? = null,
    ) {
        val position = mutableLongStateOf(2_000)
        val defaultLines = listOf(LyricLine(0, "慢慢听见海风"), LyricLine(5_000, "灯火落在远方"))
        compose.setContent {
            if (playerVisible?.value == false) return@setContent
            val lines = linesOverride?.value ?: defaultLines
            val coverStyle = if (observeCoverStyle) {
                MeloraSettings.playerCoverStyle.collectAsState().value
            } else {
                PlayerCoverStyle.Default
            }
            view = LocalView.current
            SystemBarsAppearance(darkStatusIcons = false, forceHideStatusBar = immersive.value)
            PlayerAppearanceProvider(dark = true) {
                FullPlayerPageContent(
                    state = PlayerUiState(current = UiTrack("immersive-test", "晚风与海", "测试歌手", "测试专辑"), durationMs = 180_000),
                    immersive = immersive.value, onImmersiveChange = { immersive.value = it },
                    lyricPosition = position, motionEnabled = motionEnabled,
                    lyricFrame = rememberLyricFrame(lines, position), lyricLines = lines,
                    onOpenQueue = {}, queuePagerState = rememberPagerState { 2 },
                    onArtworkPositioned = {}, artworkAlpha = { 1f },
                    coverStyle = coverStyle, artworkRotation = { 0f },
                    onPageVisualChanged = { _, _, _ -> },
                    modifier = when {
                        landscape -> Modifier.requiredSize(720.dp, 360.dp)
                        compact -> Modifier.fillMaxWidth().height(340.dp)
                        else -> Modifier.fillMaxSize()
                    },
                )
            }
        }
    }

    @Test
    fun longPressCoverPickerEntersImmersionAndBackRestoresNormalPlayer() {
        showPlayer()
        val normal = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
        compose.onNodeWithTag("player-cover-immersive").performClick()
        compose.runOnIdle { assertTrue(immersive.value) }
        val enlarged = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        assertTrue("隐藏栏位应释放给正文", enlarged.height > normal.height + 100f)
        compose.onNodeWithTag("player-heading").assertDoesNotExist()
        compose.onNodeWithTag("player-transport").assertDoesNotExist()
        Espresso.pressBack()
        compose.runOnIdle { assertFalse(immersive.value) }
        assertEquals(normal.height, compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test
    fun selectingCoverFromLongPressPersistsAndReopensSelectedWithoutEnteringImmersion() {
        val original = MeloraSettings.playerCoverStyle.value
        val selectedStyle = PlayerCoverStyle.entries.first { it != original }
        val selectedTag = "player-cover-${selectedStyle.storageValue}"
        try {
            showPlayer(observeCoverStyle = true)
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            compose.runOnIdle { assertFalse(immersive.value) }
            compose.onNodeWithTag(selectedTag).performClick()
            compose.runOnIdle {
                assertEquals(selectedStyle, MeloraSettings.playerCoverStyle.value)
                assertFalse(immersive.value)
            }
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            compose.onNodeWithTag("player-artwork").assertIsDisplayed()
            compose.onNodeWithTag("player-transport").assertIsDisplayed()

            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            compose.onNodeWithTag(selectedTag).assertIsSelected()
            compose.runOnIdle { assertFalse(immersive.value) }
        } finally {
            MeloraSettings.updatePlayerCoverStyle(original)
        }
    }

    @Test
    fun selectingThemeUsesHostSetterKeepsPickerAndReopensWithThemeSelected() {
        val originalThemeMode = MeloraSettings.playerThemeMode.value
        val originalCoverStyle = MeloraSettings.playerCoverStyle.value
        val selectedThemeMode = ThemeMode.entries.first { it != originalThemeMode }
        val themeTag = "player-cover-theme-${selectedThemeMode.storageValue}"
        val coverTag = "player-cover-${originalCoverStyle.storageValue}"
        try {
            showPlayer(observeCoverStyle = true)
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            compose.onNodeWithTag(coverTag).assertIsSelected()

            compose.onNodeWithTag(themeTag).performClick()
            compose.runOnIdle {
                assertEquals(selectedThemeMode, MeloraSettings.playerThemeMode.value)
                assertEquals(originalCoverStyle, MeloraSettings.playerCoverStyle.value)
                assertFalse(immersive.value)
            }
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            compose.onNodeWithTag(themeTag).assertIsSelected()
            compose.onNodeWithTag(coverTag).assertIsSelected()

            compose.onNodeWithTag("player-cover-cancel").performClick()
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            compose.onNodeWithTag(themeTag).assertIsSelected()
            compose.onNodeWithTag(coverTag).assertIsSelected()
            compose.runOnIdle { assertFalse(immersive.value) }
        } finally {
            MeloraSettings.updatePlayerThemeMode(originalThemeMode)
        }
    }

    @Test
    fun choosingCoverWhileImmersedDoesNotExitImmersion() {
        val original = MeloraSettings.playerCoverStyle.value
        try {
            showPlayer()
            compose.runOnIdle { immersive.value = true }
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithTag("player-cover-vinyl").performClick()
            compose.runOnIdle {
                assertEquals(PlayerCoverStyle.Vinyl, MeloraSettings.playerCoverStyle.value)
                assertTrue(immersive.value)
            }
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            compose.onNodeWithTag("player-transport").assertDoesNotExist()
        } finally {
            MeloraSettings.updatePlayerCoverStyle(original)
        }
    }

    @Test
    fun coverControlsHideAfterThreeSecondsAndRepeatedTapRestartsTimeout() {
        showPlayer()
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("player-artwork").performClick()
        compose.onNodeWithTag("immersive-playback-controls").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("player-artwork").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("immersive-playback-controls").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_300)
        compose.onNodeWithTag("immersive-playback-controls").assertDoesNotExist()
        compose.onNodeWithTag("player-artwork").performClick()
        compose.onNodeWithContentDescription("退出沉浸播放").performClick()
        compose.runOnIdle { assertFalse(immersive.value) }
    }

    @Test
    fun allThreePagesStayReachableAndLeavingCoverClearsTemporaryControls() {
        showPlayer()
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("player-artwork").performClick()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("immersive-playback-controls").assertDoesNotExist()
        compose.runOnIdle { assertTrue(immersive.value) }
        compose.onNodeWithTag("player-pages").performTouchInput { swipeRight() }
        compose.onNodeWithTag("player-artwork").assertIsDisplayed()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(immersive.value) }
        compose.onNodeWithTag("immersive-track-notes").assertIsDisplayed()
        compose.onNodeWithTag("player-transport").assertDoesNotExist()
    }

    @Test
    fun immersiveOverridesStatusBarSettingWithoutPersistingIt() {
        val original = MeloraSettings.hideStatusBar.value
        try {
            MeloraSettings.hideStatusBar.value = false
            showPlayer()
            compose.runOnIdle { immersive.value = true }
            compose.waitUntil(5_000) {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
            }
            assertFalse(MeloraSettings.hideStatusBar.value)
            compose.runOnIdle { immersive.value = false }
            compose.waitUntil(5_000) {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true
            }
        } finally { MeloraSettings.hideStatusBar.value = original }
    }

    @Test
    fun entryCanReverseMidFlightWithoutReplacingArtworkOrLeavingLayoutOffset() {
        showPlayer(motionEnabled = true)
        val original = compose.onNodeWithTag("player-artwork").fetchSemanticsNode()
        val originalCenter = original.boundsInRoot.center
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { immersive.value = true }
        compose.mainClock.advanceTimeBy(160)
        val during = compose.onNodeWithTag("player-artwork").fetchSemanticsNode()
        assertEquals("封面必须沿用同一节点", original.id, during.id)
        assertTrue("进入沉浸时封面应上移", during.boundsInRoot.center.y < originalCenter.y)
        compose.runOnIdle { immersive.value = false }
        compose.mainClock.advanceTimeBy(1_600)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) {
            kotlin.math.abs(compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot.center.y - originalCenter.y) < 2f
        }
        val restored = compose.onNodeWithTag("player-artwork").fetchSemanticsNode()
        assertEquals(original.id, restored.id)
        assertEquals(originalCenter.x, restored.boundsInRoot.center.x, 1f)
    }

    @Test
    fun shortViewportDoesNotSqueezeExitOrPlaybackButtonsInsideSmallArtwork() {
        showPlayer(compact = true)
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("player-artwork").performClick()
        for (label in listOf("上一首", "播放", "下一首", "退出沉浸播放")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
            assertTrue(compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot.width > 0f)
        }
        compose.onNodeWithContentDescription("退出沉浸播放").performTouchInput { click(center) }
        compose.runOnIdle { assertFalse(immersive.value) }
    }

    @Test
    fun immersiveNotesKeepFullMetadataAndExistingAlbumArtistCallbacks() {
        val track = UiTrack("notes", "这是一个需要完整展示而不能截断的长歌曲名字", "测试歌手", "完整专辑名字")
        val clicked = mutableListOf<String>()
        compose.setContent {
            PlayerAppearanceProvider(dark = true) {
                ImmersiveTrackNotes(track, "本地音频", "SQ", "播放源：本地媒体",
                    { clicked += "album:$it" }, { clicked += "artist:$it" })
            }
        }
        compose.onNodeWithText(track.title).assertIsDisplayed()
        compose.onNodeWithTag("immersive-artist").performScrollTo().performClick()
        compose.onNodeWithTag("immersive-album").performScrollTo().performClick()
        compose.onNodeWithText("SQ").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("artist:${track.artist}", "album:${track.album}"), clicked)
    }

    @Test
    fun portraitImmersionDoesNotComposeTheTitleWatermark() {
        showPlayer()
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("immersive-title-watermark").assertDoesNotExist()
    }

    @Test
    fun landscapeKeepsWatermarkOnlyInImmersiveMode() {
        showPlayer(landscape = true)
        compose.onNodeWithTag("immersive-title-watermark").assertDoesNotExist()
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("immersive-title-watermark").assertExists()
        compose.runOnIdle { immersive.value = false }
        compose.onNodeWithTag("immersive-title-watermark").assertDoesNotExist()
    }

    @Test
    fun miniControlsDispatchExistingPlaybackActionsAndExit() {
        val actions = mutableListOf<String>()
        compose.setContent {
            PlayerAppearanceProvider(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    ImmersivePlaybackControls(false, { actions += "previous" }, { actions += "toggle" },
                        { actions += "next" }, { actions += "exit" })
                }
            }
        }
        for (label in listOf("上一首", "播放", "下一首", "退出沉浸播放")) {
            compose.onNodeWithContentDescription(label).performTouchInput { click(center) }
        }
        assertEquals(listOf("previous", "toggle", "next", "exit"), actions)
    }

    @Test fun lyricFontPreferenceSurvivesPlayerDisposalAndKeepsOtherOptions() {
        val original = MeloraSettings.playerLyrics.value
        val visible = mutableStateOf(true)
        try {
            MeloraSettings.updatePlayerLyrics(LyricsUiConfig(22f, true, true, true))
            showPlayer(playerVisible = visible)
            compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
            compose.onNodeWithContentDescription("展开歌词设置").performClick()
            compose.onNodeWithContentDescription("增大字号，当前22sp").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(LyricsUiConfig(24f, true, true, true), MeloraSettings.playerLyrics.value) }
            compose.runOnIdle { visible.value = false }
            compose.onNodeWithTag("player-pages").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
            compose.onNodeWithContentDescription("展开歌词设置").performClick()
            compose.onNodeWithContentDescription("增大字号，当前24sp").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("重置字号，恢复22sp").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(LyricsUiConfig(22f, true, true, true), MeloraSettings.playerLyrics.value) }
        } finally { MeloraSettings.updatePlayerLyrics(original) }
    }

    @Test fun lyricSourceMenuKeepsTheExistingPageGeometryAndCannotWriteAnOnlineTrack() {
        showPlayer()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
        val before = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("展开歌词设置").performClick()
        val after = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, 1f)
        compose.onNodeWithContentDescription("歌词来源与写入").performClick()
        compose.onNodeWithTag("lyrics-source-sheet").assertIsDisplayed()
        compose.onNodeWithTag("lyrics-source-write").assertDoesNotExist()
        compose.onAllNodesWithText("晚风与海 · 测试歌手").assertCountEquals(1)
        compose.onNodeWithTag("lyrics-source-confirm-write").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithTag("lyrics-source-sheet").assertDoesNotExist()
        compose.onNodeWithTag("player-pages").assertIsDisplayed()
        compose.runOnIdle { assertFalse(immersive.value) }
    }

    @Test fun lyricCandidateSummaryHasOneIdentityAndStableLoadingAndLongTitleHeight() {
        val track = UiTrack("summary", "晚风与海", "测试歌手", "专辑")
        val candidate = mutableStateOf<PlayerLyric?>(null)
        val loading = mutableStateOf(true)
        val match = PlayerLyric(track.uid, track.title, track.artist,
            listOf(LyricLine(0, "不应重复显示的歌词署名", words = listOf(LyricWord("不应重复显示的歌词署名", 0, 1000)))), "kw")
        compose.setContent {
            Box(Modifier.requiredSize(280.dp, 120.dp)) {
                PlayerAppearanceProvider(dark = false) { LyricsCandidateSummary(track, candidate.value, loading.value) }
            }
        }
        val height = compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height
        compose.onAllNodesWithText("晚风与海 · 测试歌手").assertCountEquals(1)
        compose.runOnIdle { candidate.value = match; loading.value = false }
        compose.onAllNodesWithText("晚风与海 · 测试歌手").assertCountEquals(1)
        compose.onNodeWithText("不应重复显示的歌词署名").assertDoesNotExist()
        compose.onNodeWithText("逐字歌词", substring = true).assertIsDisplayed()
        assertEquals(height, compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.runOnIdle { candidate.value = match.copy(title = "很长的歌曲名称".repeat(12)) }
        assertEquals(height, compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.runOnIdle { candidate.value = null }
        compose.onNodeWithText("暂未找到匹配，保留原有歌词").assertIsDisplayed()
        assertEquals(height, compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun backgroundWordEnrichmentDoesNotRecreateTheLyricsViewport() {
        val lines = mutableStateOf(listOf(LyricLine(0, "慢慢听见海风"), LyricLine(5000, "灯火落在远方")))
        showPlayer(linesOverride = lines)
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
        val before = compose.onNodeWithTag("player-full-lyrics").fetchSemanticsNode()
        compose.runOnIdle {
            lines.value = lines.value.map { line -> line.copy(endMs = line.startMs + 5000,
                words = listOf(com.leyu.melora.playback.LyricWord(line.text, line.startMs, line.startMs + 5000))) }
        }
        val after = compose.onNodeWithTag("player-full-lyrics").fetchSemanticsNode()
        assertEquals(before.id, after.id)
        assertEquals(before.boundsInRoot.height, after.boundsInRoot.height, 1f)
    }
}
