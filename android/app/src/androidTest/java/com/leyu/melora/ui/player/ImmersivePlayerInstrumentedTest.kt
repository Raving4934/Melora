package com.leyu.melora.ui.player

import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame

import android.graphics.Bitmap
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.AudioSpecification
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
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersivePlayerInstrumentedTest {
    @get:Rule val compose = createComposeRule()



    private fun textLayout(text: String, substring: Boolean = false): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private val immersive = mutableStateOf(false)
    private lateinit var view: View

    private fun showPlayer(
        motionEnabled: Boolean = false,
        compact: Boolean = false,
        viewport: DpSize? = null,
        forcePortrait: Boolean = false,
        observeCoverStyle: Boolean = false,
        artworkStyle: PlayerCoverStyle = PlayerCoverStyle.Default,
        artworkUrl: String? = null,
        linesOverride: androidx.compose.runtime.State<List<LyricLine>>? = null,
        positionMs: Long = 2_000L,
        playerVisible: androidx.compose.runtime.State<Boolean>? = null,
        simulateSystemBars: Boolean = false,
    ) {
        val position = mutableLongStateOf(positionMs)
        val defaultLines = listOf(LyricLine(0, "慢慢听见海风"), LyricLine(5_000, "灯火落在远方"))
        compose.setContent {
            if (playerVisible?.value == false) return@setContent
            val lines = linesOverride?.value ?: defaultLines
            val coverStyle = if (observeCoverStyle) {
                MeloraSettings.playerCoverStyle.collectAsState().value
            } else {
                artworkStyle
            }
            view = LocalView.current
            if (!simulateSystemBars) {
                SystemBarsAppearance(darkStatusIcons = false, forceHideStatusBar = immersive.value)
            }
            val density = if (simulateSystemBars || viewport != null) Density(1f) else LocalDensity.current
            CompositionLocalProvider(LocalDensity provides density) {
                PlayerAppearanceProvider(dark = true) {
                    FullPlayerPageContent(
                        state = PlayerUiState(
                            current = UiTrack("immersive-test", "晚风与海", "测试歌手", "测试专辑", artwork = artworkUrl),
                            durationMs = 180_000,
                        ),
                        immersive = immersive.value, onImmersiveChange = { immersive.value = it },
                        lyricPosition = position, motionEnabled = motionEnabled,
                        lyricFrame = rememberLyricFrame(lines, position), lyricLines = lines,
                        onOpenQueue = {}, onCloseQueue = {}, queuePagerState = rememberPagerState { 2 },
                        onArtworkPositioned = {}, artworkAlpha = { 1f },
                        coverStyle = coverStyle, artworkRotation = { 0f },
                        onPageVisualChanged = { _, _, _ -> },
                        modifier = when {
                            viewport != null -> Modifier.requiredSize(viewport)
                                .testTag("immersive-test-viewport")
                            compact -> Modifier.fillMaxWidth().height(340.dp)
                            // 依赖窄屏三页或竖屏几何的用例显式固定视口，不跟随宿主平板尺寸。
                            forcePortrait -> Modifier.requiredSize(360.dp, 640.dp)
                            else -> Modifier.fillMaxSize()
                        },
                    )
                }
            }
        }
    }

    @Test
    fun longPressCoverPickerEntersImmersionAndBackRestoresNormalPlayer() {
        showPlayer()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.statusBars()) ==
                !MeloraSettings.hideStatusBar.value
        }
        compose.awaitStable("player-pages")
        val normal = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
        compose.onNodeWithTag("player-cover-immersive").performScrollTo().also { compose.awaitStable(it) }.performClick()
        compose.waitUntil(5_000) { immersive.value }
        compose.runOnIdle { assertTrue(immersive.value) }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
        }
        compose.awaitStable("player-pages")
        val enlarged = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
        assertTrue("隐藏栏位应释放给正文", enlarged.height > normal.height + 100f)
        compose.onNodeWithTag("player-heading").assertDoesNotExist()
        compose.onNodeWithTag("player-transport").assertDoesNotExist()
        Espresso.pressBack()
        compose.runOnIdle { assertFalse(immersive.value) }
        // 退出状态已提交不等于系统栏 Insets 已恢复并完成布局；仍要求原高度及 1px 精度。
        compose.waitUntil(5_000) {
            val restored = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.statusBars()) ==
                !MeloraSettings.hideStatusBar.value && kotlin.math.abs(normal.height - restored.height) <= 1f
        }
        assertEquals(normal.height, compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test
    fun immersiveNavigationAllowsBackWhileSystemBarsAreHidden() {
        showPlayer()
        compose.runOnIdle { immersive.value = true }
        compose.waitUntil(5_000) {
            val insets = ViewCompat.getRootWindowInsets(view)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
        compose.runOnIdle {
            assertEquals(WindowInsetsController.BEHAVIOR_DEFAULT, view.windowInsetsController?.systemBarsBehavior)
        }
        Espresso.pressBack()
        compose.runOnIdle { assertFalse(immersive.value) }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true
        }
    }

    @Test
    fun selectingCoverFromLongPressPersistsAndReopensSelectedWithoutEnteringImmersion() {
        val original = MeloraSettings.playerCoverStyle.value
        val selectedStyle = PlayerCoverStyle.entries.first { it != original }
        val selectedTag = "player-cover-${selectedStyle.storageValue}"
        try {
            showPlayer(observeCoverStyle = true)
            compose.awaitStable("player-artwork")
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.awaitStable("player-cover-picker")
            compose.runOnIdle { assertFalse(immersive.value) }
            compose.onNodeWithTag(selectedTag).performClick()
            // 选择在抽屉退场后的 onHidden 回调提交，Compose 空闲不等于该回调已执行。
            compose.waitUntil(5_000) { MeloraSettings.playerCoverStyle.value == selectedStyle }
            compose.runOnIdle {
                assertEquals(selectedStyle, MeloraSettings.playerCoverStyle.value)
                assertFalse(immersive.value)
            }
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            compose.onNodeWithTag("player-artwork").assertIsDisplayed()
            compose.onNodeWithTag("player-transport").assertIsDisplayed()

            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.awaitStable("player-cover-picker")
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
            compose.awaitStable("player-artwork")
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.awaitStable("player-cover-picker")
            compose.onNodeWithTag(coverTag).assertIsSelected()

            compose.onNodeWithTag(themeTag).performClick()
            compose.runOnIdle {
                assertEquals(selectedThemeMode, MeloraSettings.playerThemeMode.value)
                assertEquals(originalCoverStyle, MeloraSettings.playerCoverStyle.value)
                assertFalse(immersive.value)
            }
            compose.awaitStable("player-cover-picker")
            compose.onNodeWithTag(themeTag).assertIsSelected()
            compose.onNodeWithTag(coverTag).assertIsSelected()

            compose.onNodeWithTag("player-cover-cancel").performScrollTo().also { compose.awaitStable(it) }.performClick()
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            // Dialog语义消失不代表宿主窗口已恢复获焦与布局；长按前核对真实窗口状态。
            compose.waitUntil(5_000) { view.hasWindowFocus() }
            compose.awaitStable("player-artwork")
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.awaitStable("player-cover-picker")
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
            compose.waitUntil(5_000) { MeloraSettings.playerCoverStyle.value == PlayerCoverStyle.Vinyl }
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
        val artwork = compose.onNodeWithTag("player-artwork")
        compose.awaitStable(artwork)
        fun tapExposedArtwork() = artwork.performTouchInput { click(percentOffset(0.5f, 0.2f)) }
        tapExposedArtwork()
        val controls = compose.onNodeWithTag("immersive-playback-controls").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(2_000)
            tapExposedArtwork()
            compose.mainClock.advanceTimeBy(2_000)
            controls.assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_300)
            controls.assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        tapExposedArtwork()
        compose.onNodeWithContentDescription("退出沉浸播放").performClick()
        compose.runOnIdle { assertFalse(immersive.value) }
    }

    @Test
    fun allThreePagesStayReachableAndLeavingCoverClearsTemporaryControls() {
        showPlayer(forcePortrait = true)
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
        showPlayer(motionEnabled = true, forcePortrait = true)
        // 冻结Compose时钟前先等原生窗口完成首帧布局，否则比较的是两个不同宿主尺寸。
        compose.awaitStable("player-pages")
        compose.awaitStable("player-artwork")
        val original = compose.onNodeWithTag("player-artwork").fetchSemanticsNode()
        val originalCenter = original.boundsInRoot.center
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { immersive.value = true }
        compose.mainClock.advanceTimeBy(160)
        val during = compose.onNodeWithTag("player-artwork").fetchSemanticsNode()
        assertEquals("封面必须沿用同一节点", original.id, during.id)
        val duringCenter = during.boundsInRoot.center
        assertTrue("进入沉浸时封面应上移: original=$originalCenter during=$duringCenter", duringCenter.y < originalCenter.y)
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

    // API 30 起可以独立设置系统栏的当前内距和忽略可见性的内距。
    private fun dispatchPlayerBars(statusVisible: Boolean, navigationVisible: Boolean = statusVisible) {
        val status = WindowInsetsCompat.Type.statusBars()
        val navigation = WindowInsetsCompat.Type.navigationBars()
        val statusInsets = Insets.of(0, 24, 0, 0)
        val navigationInsets = Insets.of(0, 0, 0, 48)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(status, if (statusVisible) statusInsets else Insets.NONE)
            .setInsets(navigation, if (navigationVisible) navigationInsets else Insets.NONE)
            .setInsetsIgnoringVisibility(status, statusInsets)
            .setInsetsIgnoringVisibility(navigation, navigationInsets)
            .setVisible(status, statusVisible)
            .setVisible(navigation, navigationVisible)
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun withSimulatedPortraitPlayer(
        playerVisible: State<Boolean>? = null,
        verify: (SemanticsNodeInteraction) -> Unit,
    ) {
        val originalMiniLyrics = MeloraSettings.miniLyricsEnabled.value
        val originalHideStatusBar = MeloraSettings.hideStatusBar.value
        try {
            MeloraSettings.miniLyricsEnabled.value = true
            MeloraSettings.hideStatusBar.value = false
            showPlayer(
                motionEnabled = true, forcePortrait = true,
                simulateSystemBars = true, playerVisible = playerVisible,
            )
            compose.runOnIdle { dispatchPlayerBars(statusVisible = true) }
            verify(compose.onNodeWithTag("player-artwork"))
        } finally {
            compose.mainClock.autoAdvance = true
            MeloraSettings.miniLyricsEnabled.value = originalMiniLyrics
            MeloraSettings.hideStatusBar.value = originalHideStatusBar
        }
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun portraitArtworkMovesUpWhenNavigationInsetsClearAndRestoresAfterReversal() =
        withSimulatedPortraitPlayer { cover ->
            val original = compose.awaitStable(cover).center

            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                immersive.value = true
                dispatchPlayerBars(statusVisible = false)
            }
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                val early = compose.boundsInSameFrame(cover).single().center
                assertTrue("系统栏消失时封面向下跳动，原位置=$original，当前位置=$early", early.y <= original.y + 1f)
            }
            compose.mainClock.advanceTimeBy(128)
            val during = compose.boundsInSameFrame(cover).single().center
            assertTrue("进入沉浸 160ms 时封面应上移，原位置=$original，当前位置=$during", during.y < original.y)

            compose.runOnIdle {
                immersive.value = false
                dispatchPlayerBars(statusVisible = true)
            }
            compose.mainClock.advanceTimeBy(1_600)
            val restored = compose.boundsInSameFrame(cover).single().center
            assertEquals(original.x, restored.x, 1f)
            assertEquals(original.y, restored.y, 1f)
        }

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun portraitLyricFocusReturnsWithoutDippingBelowItsNormalPosition() =
        verifyPortraitLyricReturn(DpSize(360.dp, 780.dp))

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun largePortraitLyricFocusReturnsWithoutOvershoot() =
        verifyPortraitLyricReturn(DpSize(640.dp, 900.dp))

    private fun verifyPortraitLyricReturn(viewport: DpSize) {
        val originalMiniLyrics = MeloraSettings.miniLyricsEnabled.value
        val originalHideStatusBar = MeloraSettings.hideStatusBar.value
        val activeText = "沿着海边走过那条长街一路听见远方的灯火与潮声"
        val lines = List(20) { index ->
            LyricLine(index * 5_000L, if (index == 8) activeText else "夜航第 ${index + 1} 句")
        }
        try {
            MeloraSettings.miniLyricsEnabled.value = true
            MeloraSettings.hideStatusBar.value = true
            showPlayer(
                motionEnabled = true,
                viewport = viewport,
                artworkStyle = PlayerCoverStyle.Circle,
                linesOverride = mutableStateOf(lines),
                positionMs = 42_000L,
                simulateSystemBars = true,
            )
            val focus = compose.onNodeWithText(activeText, useUnmergedTree = true)
            compose.runOnIdle { dispatchPlayerBars(statusVisible = false, navigationVisible = true) }
            val normalY = compose.awaitStable(focus).center.y
            compose.runOnIdle {
                immersive.value = true
                dispatchPlayerBars(statusVisible = false)
            }
            val immersiveY = compose.awaitStable(focus).center.y

            compose.mainClock.autoAdvance = false
            compose.runOnIdle { immersive.value = false }
            val during = List(36) { frameIndex ->
                if (frameIndex == 7) {
                    compose.runOnIdle { dispatchPlayerBars(statusVisible = false, navigationVisible = true) }
                }
                compose.mainClock.advanceTimeByFrame()
                val bounds = compose.boundsInSameFrame(focus).single()
                assertFalse("退出沉浸第 ${frameIndex + 1} 帧丢失当前歌词", bounds.isEmpty)
                bounds.center.y
            }
            compose.mainClock.advanceTimeBy(1_000)
            val restoredY = compose.boundsInSameFrame(focus).single().center.y
            assertEquals(normalY, restoredY, 1f)

            val trajectory = listOf(immersiveY) + during + restoredY
            val direction = if (normalY >= immersiveY) 1f else -1f
            var furthest = 0f
            for (y in trajectory) {
                val travelled = (y - immersiveY) * direction
                assertTrue("退出时当前句不应折返，轨迹=$trajectory", travelled >= furthest - 4f)
                furthest = maxOf(travelled, furthest)
            }
            val maximumStep = maxOf(5f, abs(normalY - immersiveY) * 0.24f)
            assertTrue("退出时当前句不能突然跳位，轨迹=$trajectory",
                trajectory.zipWithNext().all { (before, after) -> abs(before - after) <= maximumStep })
        } finally {
            compose.mainClock.autoAdvance = true
            MeloraSettings.miniLyricsEnabled.value = originalMiniLyrics
            MeloraSettings.hideStatusBar.value = originalHideStatusBar
        }
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun portraitExitAfterImmersiveLayoutRecreationDoesNotOvershoot() {
        val visible = mutableStateOf(true)
        withSimulatedPortraitPlayer(playerVisible = visible) { cover ->
            val normal = compose.awaitStable(cover).center
            compose.runOnIdle { visible.value = false }
            cover.assertDoesNotExist()
            // 保留宿主的沉浸状态，让重新创建的竖屏播放器直接从沉浸布局开始。
            compose.runOnIdle {
                immersive.value = true
                visible.value = true
            }
            cover.assertExists()
            compose.runOnIdle { dispatchPlayerBars(statusVisible = false) }
            val start = compose.awaitStable(cover).center
            assertTrue("沉浸封面应在普通位置上方，沉浸位置=$start，普通位置=$normal", start.y < normal.y)

            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                immersive.value = false
                dispatchPlayerBars(statusVisible = true)
            }
            repeat(24) {
                compose.mainClock.advanceTimeByFrame()
                val during = compose.boundsInSameFrame(cover).single().center
                assertTrue("退出时封面越过普通位置，普通位置=$normal，当前位置=$during", during.y <= normal.y + 1f)
            }
            compose.mainClock.advanceTimeBy(1_600)
            val restored = compose.boundsInSameFrame(cover).single().center
            assertEquals(normal.x, restored.x, 1f)
            assertEquals(normal.y, restored.y, 1f)
        }
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun delayedNavigationInsetReleaseKeepsImmersiveArtworkContinuous() =
        withSimulatedPortraitPlayer { cover ->
            compose.runOnIdle {
                immersive.value = true
                dispatchPlayerBars(statusVisible = false, navigationVisible = true)
            }
            val before = compose.awaitStable(cover)
            val pages = compose.onNodeWithTag("player-pages")
            val beforeHeight = pages.fetchSemanticsNode().size.height

            compose.mainClock.autoAdvance = false
            compose.runOnIdle { dispatchPlayerBars(statusVisible = false) }
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                val during = compose.boundsInSameFrame(cover).single()
                assertEquals("导航栏回调不应使封面中心跳动", before.center.y, during.center.y, 1f)
                assertEquals("导航栏回调不应使封面宽度突变", before.width, during.width, 1f)
            }
            compose.mainClock.advanceTimeBy(1_600)
            assertEquals(beforeHeight + 48, pages.fetchSemanticsNode().size.height)
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
        val specification = AudioSpecification("audio/flac", 192_000, 4_812_000, bitDepth = 24)
        val clicked = mutableListOf<String>()
        compose.setContent {
            PlayerAppearanceProvider(dark = true) {
                ImmersiveTrackNotes(track, "本地音频", specification, "播放源：本地媒体",
                    { clicked += "album:$it" }, { clicked += "artist:$it" })
            }
        }
        compose.onNodeWithText(track.title).assertIsDisplayed()
        compose.onNodeWithTag("immersive-artist").performScrollTo().performClick()
        compose.onNodeWithTag("immersive-album").performScrollTo().performClick()
        compose.onNodeWithText("HI-RES").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("24-bit · 192 kHz · FLAC · 4,812 kbps").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("artist:${track.artist}", "album:${track.album}"), clicked)
    }

    @Test
    fun portraitImmersionDoesNotComposeTheTitleWatermark() {
        showPlayer(forcePortrait = true)
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("immersive-title-watermark").assertDoesNotExist()
    }

    @Test
    fun landscapeKeepsWatermarkOnlyInImmersiveMode() {
        showPlayer(viewport = DpSize(720.dp, 360.dp))
        compose.onNodeWithTag("immersive-title-watermark").assertDoesNotExist()
        compose.runOnIdle { immersive.value = true }
        compose.onNodeWithTag("immersive-title-watermark").assertExists()
        compose.runOnIdle { immersive.value = false }
        compose.onNodeWithTag("immersive-title-watermark").assertDoesNotExist()
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun phoneLandscapeArtworkStaysOnTheLeftDuringImmersiveTransitions() =
        verifyLandscapeTransition(DpSize(720.dp, 360.dp), PlayerCoverStyle.Default, "player-compact-tab-0")

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun expandedLandscapeArtworkStaysOnTheLeftDuringImmersiveTransitions() =
        verifyLandscapeTransition(DpSize(1000.dp, 640.dp), PlayerCoverStyle.Circle, "player-page-tab-0")

    private fun verifyLandscapeTransition(viewport: DpSize, style: PlayerCoverStyle, informationTab: String) {
        val originalHideStatusBar = MeloraSettings.hideStatusBar.value
        val sourceColor = Color(0xFFE92768)
        val artworkFile = File.createTempFile(
            "landscape-artwork-", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        )
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(sourceColor.toArgb())
                artworkFile.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            MeloraSettings.hideStatusBar.value = false
            showPlayer(
                motionEnabled = true, viewport = viewport, artworkStyle = style,
                artworkUrl = artworkFile.toURI().toString(), simulateSystemBars = true,
            )
            compose.runOnIdle { dispatchPlayerBars(statusVisible = true) }
            val tab = compose.onNodeWithTag(informationTab)
            compose.awaitStable(tab)
            tab.performClick()
            compose.awaitStable("player-page-0")
            tab.assertIsSelected()
            val cover = compose.onNodeWithTag("player-artwork")
            val coverPage = compose.onNodeWithTag("player-page-1")
            val viewportBounds = compose.awaitStable("immersive-test-viewport")
            val normal = compose.awaitStable(cover)
            compose.waitUntil(5_000) { coversSameColor(coverPixel(cover), sourceColor) }
            val normalArtworkId = cover.fetchSemanticsNode().id

            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                immersive.value = true
                dispatchPlayerBars(statusVisible = false, navigationVisible = true)
            }
            val entering = captureLandscapeFrames(cover, sourceColor) {
                dispatchPlayerBars(statusVisible = false)
            }
            val full = settleLandscapeCover(cover)
            assertLandscapeHandoff("进入沉浸", normal, full, entering, viewportBounds.center.x)
            coverPage.assertIsDisplayed()
            compose.onAllNodesWithTag("player-artwork").assertCountEquals(1)
            assertEquals(normalArtworkId, cover.fetchSemanticsNode().id)
            compose.onNodeWithTag("player-transport").assertIsNotDisplayed()

            // 用界面按钮触发退出，之后立即恢复系统栏，验证反向路径。
            cover.performClick()
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithContentDescription("退出沉浸播放").assertIsDisplayed().performClick()
            compose.runOnIdle { dispatchPlayerBars(statusVisible = true) }
            val exiting = captureLandscapeFrames(cover, sourceColor)
            val restored = settleLandscapeCover(cover)
            assertLandscapeHandoff("退出沉浸", full, restored, exiting, viewportBounds.center.x)
            assertEquals(normal.center.x, restored.center.x, 2f)
            assertEquals(normal.center.y, restored.center.y, 2f)
            assertEquals(normal.width, restored.width, 2f)
            assertEquals(normalArtworkId, cover.fetchSemanticsNode().id)
            compose.onNodeWithTag("player-transport").assertIsDisplayed()
            tab.assertIsSelected()
        } finally {
            compose.mainClock.autoAdvance = true
            MeloraSettings.hideStatusBar.value = originalHideStatusBar
            artworkFile.delete()
        }
    }

    private fun captureLandscapeFrames(
        cover: SemanticsNodeInteraction,
        sourceColor: Color,
        onNavigationChanged: (() -> Unit)? = null,
    ): List<Rect> = List(24) { index ->
        if (index == 8 && onNavigationChanged != null) compose.runOnIdle(onNavigationChanged)
        compose.mainClock.advanceTimeByFrame()
        if (index < 4) {
            val pixel = coverPixel(cover)
            assertTrue("第 ${index + 1} 帧应继续显示源图，实际颜色=$pixel", coversSameColor(pixel, sourceColor))
        }
        compose.boundsInSameFrame(cover).single()
    }

    private fun coverPixel(cover: SemanticsNodeInteraction): Color {
        val image = cover.captureToImage()
        // 取上方三分之一的像素，避开沉浸态封面中央的播放按钮。
        return image.toPixelMap(image.width / 2, image.height / 3, width = 1, height = 1)[0, 0]
    }

    private fun coversSameColor(actual: Color, expected: Color): Boolean =
        maxOf(abs(actual.red - expected.red), abs(actual.green - expected.green), abs(actual.blue - expected.blue)) < 0.04f

    private fun settleLandscapeCover(cover: SemanticsNodeInteraction): Rect {
        compose.mainClock.advanceTimeBy(1_000)
        return compose.boundsInSameFrame(cover).single()
    }

    private fun assertLandscapeHandoff(label: String, start: Rect, end: Rect, frames: List<Rect>, viewportCenterX: Float) {
        val track = listOf(start) + frames + end
        assertTrue("$label 时封面应保持可见且位于左栏，轨迹=$track",
            track.all { !it.isEmpty && it.center.x < viewportCenterX })
        val sizeChange = abs(end.width - start.width)
        val centerChange = (end.center - start.center).getDistance()
        val allowedStep = maxOf(5f, centerChange * 0.32f)
        val allowedResize = maxOf(5f, sizeChange * 0.32f)
        assertTrue("$label 时封面不应在相邻帧之间跳位或突变大小，轨迹=$track",
            track.zipWithNext().all { (previous, next) ->
                (next.center - previous.center).getDistance() <= allowedStep &&
                    abs(next.width - previous.width) <= allowedResize
            })
        val interior = (minOf(start.width, end.width) + 3f)..(maxOf(start.width, end.width) - 3f)
        assertTrue("$label 应包含介于起始和结束尺寸之间的动画帧，轨迹=$track",
            frames.any { it.width in interior })
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test
    fun expandedLandscapeKeepsImmersivePagesReachableAfterTransition() {
        showPlayer(motionEnabled = true, viewport = DpSize(1000.dp, 640.dp), simulateSystemBars = true)
        compose.awaitStable("player-artwork")
        compose.runOnIdle {
            immersive.value = true
            dispatchPlayerBars(statusVisible = false)
        }
        val cover = compose.onNodeWithTag("player-artwork")
        compose.awaitStable(cover)
        val pages = compose.onNodeWithTag("player-pages")
        pages.performTouchInput { swipeLeft() }
        compose.awaitStable("player-page-2")
        compose.onNodeWithTag("player-stage-lyrics", useUnmergedTree = true).assertIsDisplayed()
        pages.performTouchInput { swipeRight() }
        compose.awaitStable(cover)
        pages.performTouchInput { swipeRight() }
        compose.awaitStable("immersive-track-notes")
        pages.performTouchInput { swipeLeft() }
        compose.awaitStable(cover)
        cover.performClick()
        compose.awaitStable(compose.onNodeWithContentDescription("退出沉浸播放"))
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
        val controls = listOf(
            "上一首" to "previous", "播放" to "toggle", "下一首" to "next", "退出沉浸播放" to "exit",
        )
        controls.forEachIndexed { index, (label, _) ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed().performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(controls.take(index + 1).map { it.second }, actions) }
        }
    }

    @Test fun desktopLyricShortcutTogglesWithoutChangingToolbarGeometry() {
        val enabled = mutableStateOf(false)
        val requests = mutableListOf<Boolean>()
        compose.setContent {
            PlayerAppearanceProvider(dark = true) {
                LyricsToolsPanel(true, LyricsUiConfig(), {}, {}, enabled.value) {
                    requests += it
                    enabled.value = it
                }
            }
        }
        val off = compose.onNodeWithContentDescription("开启桌面歌词")
        off.assertIsDisplayed().assertIsNotSelected()
        val before = off.fetchSemanticsNode().boundsInRoot
        off.performClick()
        val on = compose.onNodeWithContentDescription("关闭桌面歌词")
        on.assertIsDisplayed().assertIsSelected()
        assertEquals(before, on.fetchSemanticsNode().boundsInRoot)
        on.performClick()
        compose.onNodeWithContentDescription("开启桌面歌词").assertIsNotSelected()
        compose.runOnIdle { assertEquals(listOf(true, false), requests) }
    }

    @Test fun desktopLyricShortcutReflectsSharedSettingsWithoutMovingPlayerContent() {
        val original = MeloraSettings.showDesktopLyrics.value
        try {
            MeloraSettings.showDesktopLyrics.value = false
            showPlayer(forcePortrait = true)
            compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft() }
            val before = compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot
            val ink = DarkPlayerColors.textPrimary
            assertEquals(ink.copy(alpha = 0.85f), textLayout("词").layoutInput.style.color)
            compose.onNodeWithContentDescription("展开歌词设置").performClick()
            assertEquals(ink, textLayout("词").layoutInput.style.color)
            compose.onNodeWithContentDescription("开启桌面歌词").assertIsDisplayed().assertIsNotSelected()
            compose.runOnIdle { MeloraSettings.showDesktopLyrics.value = true }
            compose.onNodeWithContentDescription("关闭桌面歌词").assertIsSelected()
            assertEquals(before, compose.onNodeWithTag("player-pages").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("收起歌词设置").performClick()
            compose.onNodeWithContentDescription("展开歌词设置").assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("关闭桌面歌词").fetchSemanticsNodes().isEmpty()
            }
        } finally { MeloraSettings.showDesktopLyrics.value = original }
    }

    @Test fun lyricFontPreferenceSurvivesPlayerDisposalAndKeepsOtherOptions() {
        val original = MeloraSettings.playerLyrics.value
        val visible = mutableStateOf(true)
        try {
            MeloraSettings.updatePlayerLyrics(LyricsUiConfig(22f, true, true, true))
            showPlayer(forcePortrait = true, playerVisible = visible)
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
        showPlayer(forcePortrait = true)
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

    @Test fun lyricCandidateSummaryHasOneIdentityAndStableTextMetricsAtLargeFontScale() {
        val track = UiTrack("summary", "晚风与海", "测试歌手", "专辑")
        val candidate = mutableStateOf<PlayerLyric?>(null)
        val loading = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        val longTitle = "很长的歌曲名称".repeat(12)
        val match = PlayerLyric(track.uid, track.title, track.artist,
            listOf(LyricLine(0, "不应重复显示的歌词署名", words = listOf(LyricWord("不应重复显示的歌词署名", 0, 1000)))), "kw")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                Box(Modifier.requiredSize(280.dp, 120.dp)) {
                    PlayerAppearanceProvider(dark = false) { LyricsCandidateSummary(track, candidate.value, loading.value) }
                }
            }
        }
        val height = compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height
        compose.onAllNodesWithText("晚风与海 · 测试歌手").assertCountEquals(1)
        compose.runOnIdle { candidate.value = match; loading.value = false }
        compose.onAllNodesWithText("晚风与海 · 测试歌手").assertCountEquals(1)
        compose.onNodeWithText("不应重复显示的歌词署名").assertDoesNotExist()
        compose.onNodeWithText("逐字歌词", substring = true).assertIsDisplayed()
        assertEquals(height, compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height, 1f)

        compose.runOnIdle { candidate.value = match.copy(title = longTitle) }
        compose.waitForIdle()
        assertEquals(height, compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height, 1f)
        val titleLayout = textLayout(longTitle, substring = true)
        assertEquals("long title must use the full two-line budget", 2, titleLayout.lineCount)
        assertTrue("long title should ellipsize instead of clipping the second line", titleLayout.isLineEllipsized(1))
        compose.onNodeWithText("逐字歌词", substring = true).assertIsDisplayed()

        compose.runOnIdle { fontScale.value = 1.6f }
        compose.waitForIdle()
        val largeTitle = textLayout(longTitle, substring = true)
        assertEquals("large-font title must retain two lines: size=${largeTitle.size} input=${largeTitle.layoutInput.style} density=${largeTitle.layoutInput.density} constraints=${largeTitle.layoutInput.constraints} lineHeight=${largeTitle.getLineBottom(0)} bounds=${compose.onNodeWithText(longTitle, substring = true).fetchSemanticsNode().boundsInRoot}", 2, largeTitle.lineCount)
        val largeFontHeight = compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height
        val longTitleBounds = compose.onNodeWithText(longTitle, substring = true).fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("逐字歌词", substring = true).assertIsDisplayed()
        val statusBounds = status.fetchSemanticsNode().boundsInRoot
        val summaryBounds = compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot
        assertTrue("status row must follow the full two-line title", statusBounds.top >= longTitleBounds.bottom)
        assertTrue("status row must fit inside the summary", statusBounds.bottom <= summaryBounds.bottom + 1f)
        val statusLayout = textLayout("逐字歌词", substring = true)
        assertFalse("status row must not be vertically clipped", statusLayout.didOverflowHeight)
        assertFalse("normal source status should not ellipsize", statusLayout.isLineEllipsized(0))
        // Compose 的整像素测量可能比字形浮点宽度少一个舍入像素。
        assertTrue("status glyphs must fit the measured row", statusLayout.getLineRight(0) <= statusLayout.size.width + 1f)

        compose.runOnIdle { candidate.value = null }
        compose.waitForIdle()
        compose.onNodeWithText("暂未找到匹配，保留原有歌词").assertIsDisplayed()
        assertEquals(largeFontHeight, compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot.height, 1f)
        val emptyStatusBounds = compose.onNodeWithText("暂未找到匹配，保留原有歌词").fetchSemanticsNode().boundsInRoot
        val emptySummaryBounds = compose.onNodeWithTag("lyrics-source-candidate").fetchSemanticsNode().boundsInRoot
        assertTrue("large-font status text must remain inside the summary", emptyStatusBounds.bottom <= emptySummaryBounds.bottom + 1f)
        val emptyLayout = textLayout("暂未找到匹配，保留原有歌词")
        assertEquals(1, emptyLayout.lineCount)
        assertFalse("large-font status must not be vertically clipped", emptyLayout.didOverflowHeight)
        assertTrue("narrow status must fit or use its explicit ellipsis contract",
            emptyLayout.isLineEllipsized(0) || emptyLayout.getLineRight(0) <= emptyLayout.size.width + 1f)
    }

    @Test fun backgroundWordEnrichmentDoesNotRecreateTheLyricsViewport() {
        val lines = mutableStateOf(listOf(LyricLine(0, "慢慢听见海风"), LyricLine(5000, "灯火落在远方")))
        showPlayer(forcePortrait = true, linesOverride = lines)
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
