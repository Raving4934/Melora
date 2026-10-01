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
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.abs
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
    private val coverStyleState = mutableStateOf(PlayerCoverStyle.Default)

    private fun showPlayer(
        immersive: Boolean = false,
        wide: Boolean = false,
        miniLyrics: Boolean = true,
        coverStyle: PlayerCoverStyle = PlayerCoverStyle.Default,
    ) {
        coverStyleState.value = coverStyle
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
                    onOpenQueue = {}, onCloseQueue = {}, queuePagerState = rememberPagerState { 2 },
                    onArtworkPositioned = {}, artworkAlpha = { 1f },
                    coverStyle = coverStyleState.value, artworkRotation = { 0f },
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

    @Test fun coverStyleChangesAnimateArtworkGeometryAndRetargetWithoutMovingChrome() {
        showPlayer()
        compose.mainClock.autoAdvance = false
        try {
            fun artworkBounds() = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
            fun assertBoundsNear(expected: Rect, actual: Rect, message: String) {
                assertEquals("$message (left)", expected.left, actual.left, 1f)
                assertEquals("$message (top)", expected.top, actual.top, 1f)
                assertEquals("$message (right)", expected.right, actual.right, 1f)
                assertEquals("$message (bottom)", expected.bottom, actual.bottom, 1f)
            }
            fun assertIntermediate(actual: Rect, start: Rect, end: Rect, label: String) {
                val positionFromStart = maxOf(
                    abs(actual.center.x - start.center.x),
                    abs(actual.center.y - start.center.y),
                )
                val positionFromEnd = maxOf(
                    abs(actual.center.x - end.center.x),
                    abs(actual.center.y - end.center.y),
                )
                assertTrue("$label 封面位置应处于动画中间帧", positionFromStart > 1f && positionFromEnd > 1f)
                val sizeFromStart = maxOf(abs(actual.width - start.width), abs(actual.height - start.height))
                val sizeFromEnd = maxOf(abs(actual.width - end.width), abs(actual.height - end.height))
                assertTrue("$label 封面 size 应处于动画中间帧", sizeFromStart > 1f && sizeFromEnd > 1f)
            }
            fun assertChromeUnchanged(heading: Rect, transport: Rect, label: String) {
                assertBoundsNear(heading, compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot, "$label 标题区域不应移动")
                assertBoundsNear(transport, compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot, "$label transport区域不应移动")
            }

            val defaultArtwork = artworkBounds()
            val heading = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot
            val transport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot

            compose.runOnIdle { coverStyleState.value = PlayerCoverStyle.Circle }
            compose.mainClock.advanceTimeBy(64)
            val circleAt64ms = artworkBounds()
            assertChromeUnchanged(heading, transport, "Default→Circle @64ms")
            // 再加64ms得到从目标切换起约128ms的中间帧。
            compose.mainClock.advanceTimeBy(64)
            val circleAt128ms = artworkBounds()
            assertChromeUnchanged(heading, transport, "Default→Circle @128ms")
            compose.mainClock.advanceTimeBy(600)
            val circleArtwork = artworkBounds()

            assertIntermediate(circleAt64ms, defaultArtwork, circleArtwork, "Default→Circle @64ms")
            assertIntermediate(circleAt128ms, defaultArtwork, circleArtwork, "Default→Circle @128ms")
            assertTrue(
                "Circle封面 size 应与Default不同",
                abs(circleArtwork.width - defaultArtwork.width) > 1f &&
                    abs(circleArtwork.height - defaultArtwork.height) > 1f,
            )
            assertChromeUnchanged(heading, transport, "切换至Circle后")

            // Circle→Default进行中再次反向，目标更新应从当前几何连续接管，而非跳回旧端点。
            compose.runOnIdle { coverStyleState.value = PlayerCoverStyle.Default }
            compose.mainClock.advanceTimeBy(96)
            val returningToDefault = artworkBounds()
            assertIntermediate(returningToDefault, circleArtwork, defaultArtwork, "Circle→Default @96ms")
            assertChromeUnchanged(heading, transport, "Circle→Default中途")

            compose.runOnIdle { coverStyleState.value = PlayerCoverStyle.Circle }
            val immediatelyRetargeted = artworkBounds()
            assertBoundsNear(returningToDefault, immediatelyRetargeted, "反向改目标时封面不应跳变")
            compose.mainClock.advanceTimeBy(600)
            assertBoundsNear(circleArtwork, artworkBounds(), "反向动画应完成在Circle端点")
            assertChromeUnchanged(heading, transport, "反向动画完成后")
        } finally {
            compose.mainClock.autoAdvance = true
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
