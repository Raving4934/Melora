package com.leyu.melora.ui.player

import com.leyu.melora.ui.awaitStable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLyricsPagingTest {
    @get:Rule val compose = createComposeRule()
    private val originalMiniLyrics = MeloraSettings.miniLyricsEnabled.value

    @After fun restoreSettings() {
        MeloraSettings.miniLyricsEnabled.value = originalMiniLyrics
    }

    private val immersiveState = mutableStateOf(false)

    private fun showPlayer(immersive: Boolean = false, wide: Boolean = false, miniLyrics: Boolean = true) {
        MeloraSettings.miniLyricsEnabled.value = miniLyrics
        val position = mutableLongStateOf(2_000)
        val lines = listOf(LyricLine(0, "点击迷你歌词翻页"), LyricLine(5_000, "下一句歌词"))
        compose.setContent {
            // 在竖屏测试设备内承载720dp宽视口，不让requiredSize溢出物理屏幕影响手势坐标。
            CompositionLocalProvider(LocalDensity provides if (wide) Density(1f) else LocalDensity.current) {
            PlayerAppearanceProvider(dark = true) {
                FullPlayerPageContent(
                    state = PlayerUiState(current = UiTrack("paging-test", "测试歌曲", "测试歌手", "测试专辑"), durationMs = 180_000),
                    immersive = immersiveState.value, onImmersiveChange = { immersiveState.value = it },
                    lyricPosition = position, motionEnabled = true,
                    lyricFrame = rememberLyricFrame(lines, position), lyricLines = lines,
                    onOpenQueue = {}, queuePagerState = rememberPagerState { 2 },
                    onArtworkPositioned = {}, artworkAlpha = { 1f },
                    coverStyle = PlayerCoverStyle.Default, artworkRotation = { 0f },
                    onPageVisualChanged = { _, _, _ -> },
                    modifier = if (wide) Modifier.requiredSize(720.dp, 360.dp) else Modifier.fillMaxSize(),
                )
            }
            }
        }
        compose.waitForIdle()
        if (immersive) {
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.awaitStable("player-cover-immersive")
            compose.onNodeWithTag("player-cover-immersive").performScrollTo().also { compose.awaitStable(it) }.performClick()
            compose.waitUntil(5_000) { immersiveState.value }
            compose.runOnIdle { assertTrue(immersiveState.value) }
            compose.onNodeWithTag("player-heading").assertDoesNotExist()
            compose.onNodeWithTag("player-transport").assertDoesNotExist()
        }
    }

    @Test fun tapMovesBothPagesContinuouslyAndSettlesOnLyrics() {
        showPlayer()
        assertContinuousTapAndReturn()
    }

    @Test fun immersiveTapKeepsExpandedLayoutDuringPagingAndReturn() {
        showPlayer(immersive = true)
        assertContinuousTapAndReturn()
        assertImmersivePreserved()
    }

    @Test fun immersiveLyricsStayNavigableWhenNormalMiniLyricsAreDisabled() {
        showPlayer(immersive = true, miniLyrics = false)
        assertContinuousTapAndReturn()
        assertImmersivePreserved()
    }

    @Test fun wideImmersiveLayoutUsesTheSamePagingMotion() {
        showPlayer(immersive = true, wide = true)
        assertContinuousTapAndReturn()
        assertImmersivePreserved()
    }

    private fun assertImmersivePreserved() {
        compose.runOnIdle { assertTrue(immersiveState.value) }
        compose.onNodeWithTag("player-heading").assertDoesNotExist()
        compose.onNodeWithTag("player-transport").assertDoesNotExist()
    }

    private fun assertContinuousTapAndReturn() {
        val viewport = compose.onNodeWithTag("player-pages").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("点击迷你歌词翻页") and hasAnyAncestor(hasTestTag("player-page-1"))).performClick()
        compose.mainClock.advanceTimeBy(96)
        val cover = compose.onNodeWithTag("player-page-1").getUnclippedBoundsInRoot()
        val lyrics = compose.onNodeWithTag("player-page-2").getUnclippedBoundsInRoot()
        assertTrue("封面页应正在向左滑出", cover.left < viewport.left)
        assertTrue("歌词页应从右侧滑入而非直接跳转", lyrics.left > viewport.left && lyrics.left < viewport.right)
        assertEquals("两页应保持连续，无空隙或重叠", cover.right.value, lyrics.left.value, 1f)
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(viewport.left.value, compose.onNodeWithTag("player-page-2").getUnclippedBoundsInRoot().left.value, 1f)
        // 在手指仍按住的明确中间位置验连续性；高速整屏swipe可能在96ms前已结束回弹。
        compose.onNodeWithTag("player-pages").performTouchInput {
            down(Offset(width * .1f, center.y))
            moveTo(Offset(width * .4f, center.y), delayMillis = 150)
        }
        compose.mainClock.advanceTimeByFrame()
        try {
            val returningCover = compose.onNodeWithTag("player-page-1").getUnclippedBoundsInRoot()
            val returningLyrics = compose.onNodeWithTag("player-page-2").getUnclippedBoundsInRoot()
            assertTrue("歌词页应向右滑出", returningLyrics.left > viewport.left)
            assertTrue("封面页应从左侧连续滑入", returningCover.left > viewport.left - (viewport.right - viewport.left) && returningCover.left < viewport.left)
            assertEquals("返回时两页应保持连续", returningCover.right.value, returningLyrics.left.value, 1f)
        } finally {
            compose.onNodeWithTag("player-pages").performTouchInput {
                moveTo(Offset(width * .85f, center.y), delayMillis = 150)
                up()
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(viewport.left.value, compose.onNodeWithTag("player-page-1").getUnclippedBoundsInRoot().left.value, 1f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun fingerCanReverseClickAnimationBeforeItFinishes() {
        showPlayer()
        assertGestureTakesOver()
    }

    @Test fun immersiveFingerCanReverseClickAnimationWithoutExitingImmersion() {
        showPlayer(immersive = true)
        assertGestureTakesOver()
        assertImmersivePreserved()
    }

    private fun assertGestureTakesOver() {
        val left = compose.onNodeWithTag("player-pages").getUnclippedBoundsInRoot().left.value
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("点击迷你歌词翻页") and hasAnyAncestor(hasTestTag("player-page-1"))).performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("player-pages").performTouchInput {
            // 中途只有部分页宽需要退回，整屏右滑会越过page1继续翻到page0。
            swipe(
                start = Offset(width * 0.25f, height * 0.5f),
                end = Offset(width * 0.55f, height * 0.5f),
                durationMillis = 250,
            )
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        assertEquals("反向手势应接管并回到封面页", left, compose.onNodeWithTag("player-page-1").getUnclippedBoundsInRoot().left.value, 1f)
    }
}
