package com.leyu.melora.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLandscapePlayerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf("夜航", "没有一朵花生来就是花", "日出前", "归途").mapIndexed { index, title ->
        UiTrack("phone-landscape-$index", title, "林屿", "一封海边的信")
    }
    private var state by mutableStateOf(
        PlayerUiState(
            ready = true, current = tracks.first(), queue = tracks,
            currentIndex = 0, positionMs = 24_000L, durationMs = 120_000L, playing = true,
            audioSpec = AudioSpecification("audio/flac", 192_000, 4_812_000, bitDepth = 24),
        )
    )
    private val seeks = mutableListOf<Long>()
    private var nextClicks = 0
    private lateinit var back: PlayerSheetBackState
    private data class HostOrientation(val configuration: Int, val requested: Int)
    private var originalHostOrientation: HostOrientation? = null

    @Suppress("UNCHECKED_CAST")
    private val lyricFlow = PlaybackController.javaClass.getDeclaredField("_lyric").apply { isAccessible = true }
        .get(PlaybackController) as MutableStateFlow<PlayerLyric?>
    private val originalLyrics = lyricFlow.value
    private val originalMiniLyrics = MeloraSettings.miniLyricsEnabled.value
    private val originalCoverStyle = MeloraSettings.playerCoverStyle.value
    private val originalHideStatusBar = MeloraSettings.hideStatusBar.value

    @After
    fun restoreFixture() {
        try {
            restoreHostOrientation()
        } finally {
            lyricFlow.value = originalLyrics
            MeloraSettings.miniLyricsEnabled.value = originalMiniLyrics
            MeloraSettings.playerCoverStyle.value = originalCoverStyle
            MeloraSettings.hideStatusBar.value = originalHideStatusBar
        }
    }

    private fun restoreHostOrientation() {
        val original = originalHostOrientation ?: return
        val scenario = compose.activityRule.scenario
        try {
            val request = when (original.configuration) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> null
            }
            if (request != null) {
                // 先由仍在台前的 Activity 带动真实屏幕回转，再交还方向策略。
                scenario.onActivity { it.requestedOrientation = request }
                awaitHostOrientation(original.configuration)
            }
        } finally {
            scenario.onActivity {
                it.requestedOrientation = original.requested
                assertEquals("恢复 Activity 的方向策略", original.requested, it.requestedOrientation)
            }
        }
    }

    private fun awaitHostOrientation(expected: Int) {
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == expected }
    }

    private fun hostShortSideDp(): Float = compose.activity.run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            minOf(bounds.width(), bounds.height()) / resources.displayMetrics.density
        } else {
            resources.configuration.smallestScreenWidthDp.toFloat()
        }
    }

    private fun showPlayer(
        landscape: Boolean = true,
        viewport: DpSize? = null,
        carMode: Boolean = false,
        simulateHiddenBars: Boolean = false,
    ) {
        val shortSide = hostShortSideDp()
        if (viewport == null) {
            assumeTrue("该用例验证真实手机屏幕", shortSide > 0f && shortSide < 600f)
        } else if (landscape && !carMode && !simulateHiddenBars) {
            assumeTrue("该用例验证平板的低高度窗口", shortSide >= 600f)
        }
        if (viewport == null || simulateHiddenBars || (!landscape && shortSide < 600f)) {
            // 只旋转测试 Activity，保留手机的真实比例、刘海和系统栏设置。
            compose.activityRule.scenario.onActivity {
                originalHostOrientation = originalHostOrientation ?: HostOrientation(
                    it.resources.configuration.orientation,
                    it.requestedOrientation,
                )
                it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            awaitHostOrientation(expected)
        }

        val track = tracks.first()
        lyricFlow.value = PlayerLyric(
            track.uid, track.title, track.artist,
            listOf("远处的船亮起灯", "潮声沿着岸边慢慢退去", "把风的声音写进这封信", "我等天光落在海面")
                .mapIndexed { index, line -> LyricLine(index * 12_000L, line) },
            "fixture",
        )
        MeloraSettings.miniLyricsEnabled.value = true
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Default
        if (simulateHiddenBars) MeloraSettings.hideStatusBar.value = true
        compose.setContent {
            val density = if (viewport != null && landscape) Density(1f) else LocalDensity.current
            val configuration = LocalConfiguration.current
            val uiConfiguration = if (carMode) Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_CAR
            } else configuration
            CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides uiConfiguration) {
                MeloraTheme {
                    SongListStateProvider {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            back = rememberPlayerSheetBackState()
                            val playerModifier = when {
                                viewport == null -> Modifier.fillMaxSize()
                                landscape -> Modifier.requiredSize(viewport.width, viewport.height)
                                    .consumeWindowInsets(WindowInsets.safeDrawing)
                                // 竖屏直接缩短局部视口，按真实密度换算系统栏内距。
                                else -> Modifier.size(viewport.width, viewport.height)
                            }
                            ContinuousPlayerSheet(
                                state = state,
                                modifier = playerModifier,
                                backState = back,
                                onSeek = { seeks += it },
                                onNext = { nextClicks++ },
                            )
                        }
                    }
                }
            }
        }
        val mini = compose.onNodeWithTag("player-mini-track")
        compose.awaitStable(mini)
        mini.performClick()
        compose.mainClock.advanceTimeUntil(5_000) {
            back.sheet.settledValue == PlayerSheetAnchor.Expanded && back.transition == null
        }
        if (simulateHiddenBars) {
            // 固定局部视口的内距，避免宿主系统栏吞掉短窗的可用高度。
            val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
            val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val insets = WindowInsetsCompat.Builder().setInsets(bars, Insets.NONE).setVisible(bars, false).build()
            compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        }
        compose.awaitStable("player-seek-slider")
    }

    private fun tab(page: Int) = compose.onNodeWithTag("player-compact-tab-$page")

    private fun playerControl(label: String) =
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("player-compact-controls")))

    private fun playerTitle(title: String) =
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("player-heading")), useUnmergedTree = true)

    private fun selectLongTitle() = playerTitle(tracks[1].title).also { title ->
        compose.runOnIdle { state = state.copy(current = tracks[1], currentIndex = 1) }
        compose.awaitStable(title)
    }

    private fun leftControlBounds() = compose.boundsInSameFrame(
        compose.onNodeWithTag("player-artwork"),
        compose.onNodeWithTag("player-heading"),
        compose.onNodeWithTag("player-seek-slider"),
        playerControl("暂停"),
        playerControl("下一首"),
    )

    private fun selectTab(page: Int) {
        tab(page).assertIsDisplayed().performClick()
        compose.awaitStable("player-page-$page")
        tab(page).assertIsSelected()
    }

    @Test
    fun phoneLandscapeTabsAndPagingKeepPlaybackControlsInPlace() {
        showPlayer()
        compose.onNodeWithTag("player-phone-landscape").assertIsDisplayed()
        tab(2).assertIsSelected()
        tab(0).assertIsDisplayed()
        tab(1).assertDoesNotExist()
        val (lyricsTab, infoTab) = compose.boundsInSameFrame(tab(2), tab(0))
        val (cover, lyrics, controls) = compose.boundsInSameFrame(
            compose.onNodeWithTag("player-artwork"),
            compose.onNodeWithTag("player-full-lyrics"),
            compose.onNodeWithTag("player-compact-controls"),
        )
        assertTrue(lyricsTab.right <= infoTab.left)
        assertTrue("封面与控制区应常驻左侧，歌词位于右侧", controls.right <= lyrics.left && cover.right < lyrics.left)
        assertTrue("歌词应位于切换胶囊下方", lyrics.top >= lyricsTab.bottom)
        val before = leftControlBounds()
        capture("cover")

        selectTab(0)
        compose.onNodeWithText("音频信息").assertIsDisplayed()
        compose.onNodeWithText("24-bit · 192 kHz · FLAC · 4,812 kbps").performScrollTo().assertIsDisplayed()
        assertEquals(before, leftControlBounds())
        selectTab(2)
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft(durationMillis = 420L) }
        compose.awaitStable("player-page-0")
        tab(0).assertIsSelected()
        compose.onNodeWithTag("player-pages").performTouchInput { swipeRight(durationMillis = 420L) }
        compose.awaitStable("player-full-lyrics")
        tab(2).assertIsSelected()
        assertEquals(before, leftControlBounds())
        capture("lyrics")
    }

    @Test
    fun phoneLandscapeQueueLeavesTheRightSideInteractiveAndReturnsToLyrics() {
        showPlayer()
        val before = leftControlBounds()
        val open = playerControl("播放队列")
        open.assertIsDisplayed().performClick()
        compose.awaitStable("playback-queue-list")
        val (queue, controls) = compose.boundsInSameFrame(
            compose.onNodeWithTag("playback-queue-list"),
            compose.onNodeWithTag("player-compact-controls"),
        )
        assertTrue("队列列表应位于左侧封面与播放控制右侧", controls.right <= queue.left)
        assertEquals(before, leftControlBounds())
        playerControl("暂停").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        capture("queue")

        playerControl("下一首").performTouchInput { click() }
        compose.onNodeWithTag("player-seek-slider").performTouchInput {
            swipe(Offset(width * 0.25f, center.y), Offset(width * 0.75f, center.y), durationMillis = 420L)
        }
        compose.runOnIdle {
            assertEquals(1, nextClicks)
            assertEquals(1, seeks.size)
            assertTrue("拖动应提交接近四分之三处的进度：$seeks", seeks.single() in 75_000L..105_000L)
        }
        compose.onNodeWithTag("playback-queue-list").assertIsDisplayed()
        playerControl("收起播放队列").performClick()
        compose.awaitStable("player-full-lyrics")
        tab(2).assertIsSelected()
        assertEquals(before, leftControlBounds())

        open.performClick()
        compose.awaitStable("playback-queue-list")
        Espresso.pressBack()
        compose.awaitStable("player-full-lyrics")
        tab(2).assertIsSelected()
        compose.onNodeWithTag("playback-queue-list").assertIsNotDisplayed()
    }

    @Test
    fun letterboxedLyricsAtTheTopDoNotCollapseButTheCoverStillDoes() {
        showPlayer(viewport = DpSize(720.dp, 360.dp), carMode = true)
        val lyrics = compose.onNodeWithTag("player-full-lyrics")
        val lyricList = compose.onNode(
            hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("player-full-lyrics")),
            useUnmergedTree = true,
        )
        lyricList.performScrollToIndex(0)
        lyrics.performTouchInput {
            swipeDown(startY = height * 0.12f, endY = height * 0.93f, durationMillis = 300L)
        }
        compose.runOnIdle {
            assertEquals(PlayerSheetAnchor.Expanded, back.sheet.settledValue)
            assertEquals(0f, back.sheet.offset, 1f)
        }
        lyrics.assertIsDisplayed()

        compose.onNodeWithTag("player-compact-controls").performTouchInput {
            swipeDown(startY = height * 0.12f, endY = height * 0.93f, durationMillis = 300L)
        }
        compose.mainClock.advanceTimeUntil(5_000) { back.sheet.settledValue == PlayerSheetAnchor.Collapsed }
    }

    @Test
    fun narrowLandscapeKeepsTheTitleAndAllSecondaryControlsReachable() {
        showPlayer(viewport = DpSize(440.dp, 320.dp), carMode = true)
        val title = playerTitle(tracks.first().title).assertIsDisplayed()
        val (titleBounds, artworkBounds) = compose.boundsInSameFrame(title, compose.onNodeWithTag("player-artwork"))
        assertTrue("窄栏歌名应和封面纵向分布", titleBounds.bottom <= artworkBounds.top)
        val actions = listOf("循环模式", "定时与播放设置", "音效设置", "播放队列", "更多操作")
        actions.forEach { label ->
            val action = playerControl(label)
            action.performScrollTo()
            compose.awaitStable(action)
            action.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            assertTrue("$label 应能整颗按钮滚入可视区域", action.fetchSemanticsNode().boundsInRoot.width >= 39f)
        }
        val queue = playerControl("播放队列").performScrollTo()
        compose.awaitStable(queue)
        queue.performTouchInput { click() }
        compose.awaitStable("playback-queue-list")
    }

    @Test
    fun compactLandscapeShowsTheWholeLongTitle() {
        showPlayer(viewport = DpSize(720.dp, 360.dp), simulateHiddenBars = true)
        val layouts = mutableListOf<TextLayoutResult>()
        selectLongTitle().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = layouts.single()
        assertEquals("这一视口应能单行显示完整歌名，实测宽度 ${text.size.width}", 1, text.lineCount)
        assertFalse("歌名结尾应完整可见", text.isLineEllipsized(0))
        assertEquals(tracks[1].title.length, text.getLineEnd(0, visibleEnd = true))
    }

    @Test
    fun shortWideLandscapeShowsTheWholeBottomRowWithoutScrolling() {
        showPlayer(viewport = DpSize(760.dp, 280.dp), simulateHiddenBars = true)
        val more = playerControl("更多操作")
        val visibleBounds = compose.awaitStable(more)
        assertEquals("更多操作按钮应完整可见", more.fetchSemanticsNode().size.height.toFloat(), visibleBounds.height, 1f)
    }

    @Test
    fun stackedLandscapeKeepsControlsStillWhenTheTitleWraps() {
        showPlayer(viewport = DpSize(560.dp, 430.dp), simulateHiddenBars = true)
        val slider = compose.onNodeWithTag("player-seek-slider")
        val pause = playerControl("暂停")
        val next = playerControl("下一首")
        val before = compose.boundsInSameFrame(slider, pause, next)
        selectLongTitle()
        assertEquals("切换长短歌名时进度条和播放键应留在原位", before, compose.boundsInSameFrame(slider, pause, next))
    }

    @Test
    fun phonePortraitKeepsArtworkBetweenHeadingAndTransport() {
        showPlayer(landscape = false)
        assertPortraitArtworkOrder()
    }

    @Test
    fun shortPortraitKeepsArtworkBetweenHeadingAndTransport() {
        showPlayer(landscape = false, viewport = DpSize(360.dp, 540.dp))
        assertPortraitArtworkOrder()
    }

    private fun assertPortraitArtworkOrder() {
        compose.onNodeWithTag("player-phone-landscape").assertDoesNotExist()
        val artwork = compose.onNodeWithTag("player-artwork")
        try {
            compose.awaitStable(artwork)
        } catch (failure: ComposeTimeoutException) {
            val tree = compose.onRoot().printToString()
            throw AssertionError("竖屏封面未稳定显示\n$tree", failure)
        }
        val headingTag = hasTestTag("player-heading")
        val (heading, title, cover, transport) = compose.boundsInSameFrame(
            compose.onNode(headingTag).assertIsDisplayed(),
            compose.onNode(hasText(tracks.first().title) and hasAnyAncestor(headingTag)).assertIsDisplayed(),
            artwork.assertIsDisplayed(),
            compose.onNodeWithTag("player-transport").assertIsDisplayed(),
        )
        // 标题容器已经包含底部留白，短窗的封面可以贴着容器边缘。
        assertTrue(
            "标题应在封面上方，标题容器=$heading，文字=$title，封面=$cover",
            heading.bottom <= cover.top && title.bottom < cover.top,
        )
        assertTrue("封面应在控制区上方，封面=$cover，控制区=$transport", cover.bottom < transport.top)
    }

    @Test
    fun tabletShortWindowKeepsQueueInTheExistingRightPane() {
        showPlayer(viewport = DpSize(800.dp, 440.dp))
        assertQueueReplacesOnlyRightPane()
    }

    @Test
    fun carShortWindowKeepsQueueInTheExistingRightPane() {
        showPlayer(viewport = DpSize(800.dp, 440.dp), carMode = true)
        assertQueueReplacesOnlyRightPane()
    }

    private fun assertQueueReplacesOnlyRightPane() {
        compose.onNodeWithTag("player-phone-landscape").assertIsDisplayed()
        val cover = compose.onNodeWithTag("player-artwork").assertIsDisplayed()
        val seek = compose.onNodeWithTag("player-seek-slider").assertIsDisplayed()
        val controls = compose.onNodeWithTag("player-compact-controls").assertIsDisplayed()
        val lyrics = compose.onNodeWithTag("player-full-lyrics").assertIsDisplayed()
        val (controlsBounds, lyricsBounds) = compose.boundsInSameFrame(controls, lyrics)
        assertTrue("封面与控制区应常驻左侧，歌词位于右侧", controlsBounds.right <= lyricsBounds.left)
        val openQueue = playerControl("播放队列")
        openQueue.assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeUntil(5_000) { back.transition == null }
        val header = compose.onNode(hasText("播放队列") and hasAnyAncestor(hasTestTag("player-compact-content")))
        val close = compose.onNode(
            hasContentDescription("收起播放队列") and hasAnyAncestor(hasTestTag("player-compact-content")),
        )
        val queue = compose.onNodeWithTag("playback-queue-list")
        try {
            compose.awaitStable(header)
            compose.awaitStable(queue)
        } catch (failure: ComposeTimeoutException) {
            val tree = compose.onRoot().printToString()
            throw AssertionError("短窗未完整显示队列的表头与列表，转场=${back.transition}\n$tree", failure)
        }
        close.assertIsDisplayed().assertHasClickAction()
        val (artwork, leftControls, heading, list) = compose.boundsInSameFrame(cover, controls, header, queue)
        assertTrue(
            "短窗应保留右侧队列，封面=$artwork，左栏=$leftControls，表头=$heading，队列=$list",
            leftControls.right <= heading.left && leftControls.right <= list.left && heading.bottom < list.top,
        )
        seek.assertIsDisplayed()
        cover.assertIsDisplayed()
    }

    private fun capture(page: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val image = compose.onNodeWithTag("player-phone-landscape").captureToImage().asAndroidBitmap()
        try {
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            File(cache, "phone-landscape-$page.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
}
