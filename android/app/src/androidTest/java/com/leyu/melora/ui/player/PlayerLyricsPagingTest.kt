package com.leyu.melora.ui.player

import com.leyu.melora.ui.awaitStable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import kotlin.math.abs
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.LyricWord
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
    private val originalLyricsConfig = MeloraSettings.playerLyrics.value

    @After fun restoreSettings() {
        MeloraSettings.miniLyricsEnabled.value = originalMiniLyrics
        MeloraSettings.playerLyrics.value = originalLyricsConfig
    }

    private val immersiveState = mutableStateOf(false)
    private val coverStyleState = mutableStateOf(PlayerCoverStyle.Default)
    private val resizedViewport = mutableStateOf<DpSize?>(null)

    private fun showPlayer(
        immersive: Boolean = false,
        wide: Boolean = false,
        tall: Boolean = false,
        miniLyrics: Boolean = true,
        coverStyle: PlayerCoverStyle = PlayerCoverStyle.Default,
    ) {
        coverStyleState.value = coverStyle
        immersiveState.value = immersive
        MeloraSettings.miniLyricsEnabled.value = miniLyrics
        val position = mutableLongStateOf(2_000)
        val lines = listOf(
            LyricLine(0, "点击迷你歌词翻页", words = listOf(LyricWord("点击迷你歌词翻页", 0L, 3_000L))),
            LyricLine(5_000, "下一句歌词"),
        )
        compose.setContent {
            // 在竖屏测试设备内承载720dp宽视口，不让requiredSize溢出物理屏幕影响手势坐标。
            CompositionLocalProvider(LocalDensity provides if (wide || tall) Density(1f) else LocalDensity.current) {
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
                    modifier = (resizedViewport.value?.let { Modifier.requiredSize(it.width, it.height) } ?: when {
                        wide -> Modifier.requiredSize(720.dp, 360.dp)
                        tall -> Modifier.requiredSize(400.dp, 900.dp)
                        // 本组默认验证手机的封面page1→歌词page2；不能随平板宿主变成双栏两页。
                        else -> Modifier.requiredSize(360.dp, 640.dp)
                    }).background(Color.Black),
                )
            }
            }
        }
        compose.waitForIdle()
        compose.awaitStable("player-artwork")
        if (immersive) {
            compose.onNodeWithTag("player-heading").assertDoesNotExist()
            compose.onNodeWithTag("player-transport").assertDoesNotExist()
        }
    }

    @Test fun squareCoverWithoutMiniLyricsSharesVerticalFreeSpace() {
        showPlayer(tall = true, miniLyrics = false)
        compose.onNodeWithTag("player-mini-lyrics").assertDoesNotExist()
        assertCoverCenteredWithoutLyrics()
    }

    @Test fun togglingMiniLyricsRestoresTheCoverLayoutWithoutMovingTransport() {
        showPlayer(tall = true)
        val original = compose.onNodeWithTag("player-artwork").getUnclippedBoundsInRoot()
        val transport = compose.onNodeWithTag("player-transport").getUnclippedBoundsInRoot()
        repeat(2) {
            compose.runOnIdle { MeloraSettings.miniLyricsEnabled.value = false }
            compose.waitForIdle()
            assertCoverCenteredWithoutLyrics()
            assertEquals(transport, compose.onNodeWithTag("player-transport").getUnclippedBoundsInRoot())
            compose.runOnIdle { MeloraSettings.miniLyricsEnabled.value = true }
            compose.waitForIdle()
            compose.onNodeWithTag("player-mini-lyrics").assertIsDisplayed()
            compose.onNodeWithTag("player-poster-heading").assertDoesNotExist()
            assertEquals(original, compose.onNodeWithTag("player-artwork").getUnclippedBoundsInRoot())
            assertEquals(transport, compose.onNodeWithTag("player-transport").getUnclippedBoundsInRoot())
        }
    }

    private fun assertCoverCenteredWithoutLyrics() {
        val page = compose.onNodeWithTag("player-page-1").getUnclippedBoundsInRoot()
        val cover = compose.onNodeWithTag("player-artwork").getUnclippedBoundsInRoot()
        val posterHeading = compose.onNodeWithTag("player-poster-heading").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("关闭迷你歌词后标题应位于封面下方", posterHeading.top >= cover.bottom)
        assertTrue("海报标题应保留在封面页内", posterHeading.bottom <= page.bottom)
        assertTrue("海报封面顶部应留有合理呼吸空白", cover.top >= page.top)
        assertEquals("封面应水平居中", (page.left.value + page.right.value) / 2f,
            (cover.left.value + cover.right.value) / 2f, 1f)
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
            fun maxEdgeMovement(from: Rect, to: Rect) = maxOf(
                abs(to.left - from.left), abs(to.right - from.right),
                abs(to.top - from.top), abs(to.bottom - from.bottom),
            )
            val coverPageTop = compose.onNodeWithTag("player-page-1").fetchSemanticsNode().boundsInRoot.top
            fun assertIntermediate(actual: Rect, square: Rect, circle: Rect, label: String) {
                assertTrue("$label 封面边长应处于动画中间帧: $square → $actual → $circle",
                    square.width - actual.width > 1f && actual.width - circle.width > 1f)
                assertEquals("$label 封面应保持正方形边界", actual.width, actual.height, 1f)
                assertEquals("$label 封面水平中心不能跳动", square.center.x, actual.center.x, 1f)
                // 圆形端的顶部留白是剩余空间的一半。中间帧的剩余空间随边长连续释放，
                // 由整份留白逐渐过渡到半份留白，因此中心纵坐标可能提前接近终点。
                val roundness = (square.width - actual.width) / (square.width - circle.width)
                val circleFreeSpace = (circle.top - coverPageTop) * 2f
                val freeSpace = (circleFreeSpace - (actual.width - circle.width)).coerceAtLeast(0f)
                val expectedTop = coverPageTop + freeSpace * (1f - roundness * 0.5f)
                assertEquals("$label 封面顶边应随留白连续移动", expectedTop, actual.top, 2f)
            }
            fun assertChromeUnchanged(heading: Rect, transport: Rect, label: String) {
                assertBoundsNear(heading, compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot, "$label 标题区域不应移动")
                assertBoundsNear(transport, compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot, "$label transport区域不应移动")
            }

            val defaultArtwork = artworkBounds()
            val heading = compose.onNodeWithTag("player-heading").fetchSemanticsNode().boundsInRoot
            val transport = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot

            compose.runOnIdle { coverStyleState.value = PlayerCoverStyle.Circle }
            // MainTestClock先重组、下一帧才给动画t=0；从实际起点取64/128ms，
            // 否则首个“64ms”只有32ms，在低密度设备上可能只缩小1px。
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.advanceTimeBy(64)
            val circleAt64ms = artworkBounds()
            assertChromeUnchanged(heading, transport, "Default→Circle @64ms")
            // 再加64ms得到动画开始后128ms的中间帧。
            compose.mainClock.advanceTimeBy(64)
            val circleAt128ms = artworkBounds()
            assertChromeUnchanged(heading, transport, "Default→Circle @128ms")
            compose.mainClock.advanceTimeBy(600)
            val circleArtwork = artworkBounds()

            assertIntermediate(circleAt64ms, defaultArtwork, circleArtwork, "Default→Circle @64ms")
            assertIntermediate(circleAt128ms, defaultArtwork, circleArtwork, "Default→Circle @128ms")
            assertTrue("两个采样帧之间封面应继续收缩", circleAt64ms.width > circleAt128ms.width)
            assertChromeUnchanged(heading, transport, "切换至Circle后")

            // Circle→Default进行中再次反向，目标更新应从当前几何连续接管，而非跳回旧端点。
            compose.runOnIdle { coverStyleState.value = PlayerCoverStyle.Default }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.advanceTimeBy(80)
            val frameBeforeRetarget = artworkBounds()
            compose.mainClock.advanceTimeByFrame()
            val returningToDefault = artworkBounds()
            assertIntermediate(returningToDefault, defaultArtwork, circleArtwork, "Circle→Default @96ms")
            assertChromeUnchanged(heading, transport, "Circle→Default中途")

            compose.runOnIdle { coverStyleState.value = PlayerCoverStyle.Circle }
            // 新目标要等重组和动画首帧，原动效仍可能推进一帧。用实测帧速度加像素取整余量判断边界。
            val maxRetargetStep = maxEdgeMovement(frameBeforeRetarget, returningToDefault) * 1.5f + 2f
            var previousFrame = returningToDefault
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                val frame = artworkBounds()
                assertTrue("新目标的首帧应连续：$previousFrame → $frame",
                    maxEdgeMovement(previousFrame, frame) <= maxRetargetStep)
                assertIntermediate(frame, defaultArtwork, circleArtwork, "反向首帧")
                previousFrame = frame
            }
            compose.mainClock.advanceTimeBy(600)
            assertBoundsNear(circleArtwork, artworkBounds(), "反向动画应完成在Circle端点")
            assertChromeUnchanged(heading, transport, "反向动画完成后")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun compactReadingFallbackPreservesPortraitCoverUntilTheUserSelectsLyrics() {
        showPlayer(tall = true)
        val cover = compose.onNodeWithTag("player-page-1")
        fun resize(size: DpSize) = compose.runOnIdle { resizedViewport.value = size }
        val landscape = DpSize(720.dp, 360.dp)
        val portrait = DpSize(400.dp, 900.dp)
        val lyricsTab = compose.onNodeWithTag("player-compact-tab-2")
        repeat(2) { pass ->
            cover.assertIsDisplayed()
            resize(landscape)
            compose.awaitStable(lyricsTab)
            lyricsTab.assertIsSelected()
            if (pass == 1) lyricsTab.performClick()
            resize(portrait)
            compose.awaitStable(if (pass == 0) "player-page-1" else "player-full-lyrics")
        }
    }

    @Test fun immersiveCoverLyricsRespectTheSavedWordLiftSetting() {
        MeloraSettings.playerLyrics.value = originalLyricsConfig.copy(isWordLiftEnabled = true)
        showPlayer(immersive = true)
        val lyric = compose.onNode(
            hasText("点击迷你歌词翻页") and hasAnyAncestor(hasTestTag("player-mini-lyrics")),
            useUnmergedTree = true,
        )
        fun inkTop(): Int {
            compose.awaitStable(lyric)
            val pixels = lyric.captureToImage().toPixelMap()
            return (0 until pixels.height).first { y ->
                (0 until pixels.width).any { x -> pixels[x, y].luminance() > 0.65f }
            }
        }
        val liftedTop = inkTop()
        compose.runOnIdle { MeloraSettings.playerLyrics.value = originalLyricsConfig.copy(isWordLiftEnabled = false) }
        val flatTop = inkTop()
        assertTrue("关闭逐字微浮起后，高亮文字应回到基线：$liftedTop → $flatTop", flatTop > liftedTop)
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
