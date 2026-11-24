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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLandscapePlayerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf("夜航", "海边的信", "日出前", "归途").mapIndexed { index, title ->
        UiTrack("phone-landscape-$index", title, "林屿", "一封海边的信")
    }
    private val state = PlayerUiState(
        ready = true, current = tracks.first(), queue = tracks,
        currentIndex = 0, positionMs = 24_000L, durationMs = 120_000L, playing = true,
        audioSpec = AudioSpecification("audio/flac", 192_000, 4_812_000, bitDepth = 24),
    )
    private val seeks = mutableListOf<Long>()
    private var nextClicks = 0
    private lateinit var back: PlayerSheetBackState

    @Suppress("UNCHECKED_CAST")
    private val lyricFlow = PlaybackController.javaClass.getDeclaredField("_lyric").apply { isAccessible = true }
        .get(PlaybackController) as MutableStateFlow<PlayerLyric?>
    private val originalLyrics = lyricFlow.value
    private val originalMiniLyrics = MeloraSettings.miniLyricsEnabled.value
    private val originalCoverStyle = MeloraSettings.playerCoverStyle.value

    @After
    fun restoreFixture() {
        lyricFlow.value = originalLyrics
        MeloraSettings.miniLyricsEnabled.value = originalMiniLyrics
        MeloraSettings.playerCoverStyle.value = originalCoverStyle
    }

    private fun hostShortSideDp(): Float = compose.activity.run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            minOf(bounds.width(), bounds.height()) / resources.displayMetrics.density
        } else {
            resources.configuration.smallestScreenWidthDp.toFloat()
        }
    }

    private fun showPlayer(landscape: Boolean = true, viewport: DpSize? = null, carMode: Boolean = false) {
        val shortSide = hostShortSideDp()
        if (viewport == null) {
            assumeTrue("该用例验证真实手机屏幕", shortSide > 0f && shortSide < 600f)
        } else if (landscape && !carMode) {
            assumeTrue("该用例验证平板的低高度窗口", shortSide >= 600f)
        }
        if (viewport == null || (!landscape && shortSide < 600f)) {
            // 只旋转测试 Activity，保留手机的真实比例、刘海和系统栏设置。
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == expected }
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
        compose.awaitStable("player-seek-slider")
    }

    private fun tab(page: Int) = compose.onNodeWithTag("player-compact-tab-$page")

    private fun playerControl(label: String) =
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("player-compact-controls")))

    private fun rightControlBounds() = compose.boundsInSameFrame(
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
        tab(1).assertIsSelected()
        val (infoTab, coverTab, lyricsTab) = compose.boundsInSameFrame(tab(0), tab(1), tab(2))
        val (cover, miniLyrics, controls) = compose.boundsInSameFrame(
            compose.onNodeWithTag("player-artwork"),
            compose.onNodeWithTag("player-mini-lyrics"),
            compose.onNodeWithTag("player-compact-controls"),
        )
        assertTrue(infoTab.right <= coverTab.left && coverTab.right <= lyricsTab.left)
        assertTrue("封面应低于切换入口", cover.top > coverTab.bottom)
        assertTrue("迷你歌词应在封面下方", miniLyrics.top >= cover.bottom)
        assertTrue("封面应给播放控件保留独立空间", cover.right < controls.left)
        val before = rightControlBounds()
        capture("cover")

        selectTab(0)
        compose.onNodeWithText("音频信息").assertIsDisplayed()
        compose.onNodeWithText("24-bit · 192 kHz · FLAC · 4,812 kbps").assertIsDisplayed()
        assertEquals(before, rightControlBounds())
        selectTab(1)
        compose.onNodeWithTag("player-pages").performTouchInput { swipeLeft(durationMillis = 420L) }
        compose.awaitStable("player-full-lyrics")
        tab(2).assertIsSelected()
        assertEquals(before, rightControlBounds())
        capture("lyrics")
    }

    @Test
    fun phoneLandscapeQueueLeavesTheRightSideInteractiveAndReturnsToLyrics() {
        showPlayer()
        selectTab(2)
        val before = rightControlBounds()
        val open = playerControl("播放队列")
        open.assertIsDisplayed().performClick()
        compose.awaitStable("playback-queue-list")
        val (queue, controls) = compose.boundsInSameFrame(
            compose.onNodeWithTag("playback-queue-list"),
            compose.onNodeWithTag("player-compact-controls"),
        )
        assertTrue("队列列表应位于播放控制左侧", queue.right < controls.left)
        assertEquals(before, rightControlBounds())
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
        assertEquals(before, rightControlBounds())

        open.performClick()
        compose.awaitStable("playback-queue-list")
        Espresso.pressBack()
        compose.awaitStable("player-full-lyrics")
        tab(2).assertIsSelected()
        compose.onNodeWithTag("playback-queue-list").assertIsNotDisplayed()
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
        compose.onNodeWithTag("player-phone-landscape").assertDoesNotExist()
        val cover = compose.onNodeWithTag("player-artwork").assertIsDisplayed()
        val seek = compose.onNodeWithTag("player-seek-slider").assertIsDisplayed()
        val (coverBounds, seekBounds) = compose.boundsInSameFrame(cover, seek)
        assertTrue("播放进度应在封面右侧，封面=$coverBounds，进度=$seekBounds", coverBounds.right < seekBounds.left)
        val openQueue = compose.onNode(hasContentDescription("播放队列") and hasClickAction() and isEnabled())
        openQueue.performScrollTo().assertIsDisplayed()
        openQueue.performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeUntil(5_000) { back.transition == null }
        val header = compose.onNodeWithText("收起播放队列")
        val queue = compose.onNodeWithTag("playback-queue-list")
        try {
            compose.awaitStable(header)
            compose.awaitStable(queue)
        } catch (failure: ComposeTimeoutException) {
            val tree = compose.onRoot().printToString()
            throw AssertionError("短窗未完整显示队列的表头与列表，转场=${back.transition}\n$tree", failure)
        }
        val (artwork, heading, list) = compose.boundsInSameFrame(cover, header, queue)
        assertTrue(
            "短窗应保留右侧队列，封面=$artwork，表头=$heading，队列=$list",
            artwork.right < heading.left && artwork.right < list.left && heading.bottom < list.top,
        )
        seek.assertIsNotDisplayed()
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
