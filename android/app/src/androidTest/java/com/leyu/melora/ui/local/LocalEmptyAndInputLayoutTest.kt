package com.leyu.melora.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import com.leyu.melora.ui.common.PlaylistControlsHeight
import com.leyu.melora.ui.common.ChromeFloatingBar
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.LocalChromeStartBleed
import com.leyu.melora.ui.common.LocalChromeTopInset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.filters.SdkSuppress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.theme.MeloraAppearance
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class LocalEmptyAndInputLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val previousBlur = MeloraSettings.blurTopBar.value
    private val previousDark = MeloraAppearance.isDark
    private val shared = SongListState(mutableStateOf(emptyList<LocalSong>()), mutableStateOf(false), mutableStateOf(null))
    @After fun restore() {
        MeloraSettings.blurTopBar.value = previousBlur
        MeloraAppearance.isDark = previousDark
    }

    private data class StripeSample(val mean: Float, val contrast: Float)
    private data class ChromeFadeSamples(
        val rowTop: StripeSample,
        val rowBottom: StripeSample,
        val tailStart: StripeSample,
        val tailMiddle: StripeSample,
        val tailEnd: StripeSample,
        val restored: StripeSample,
        val sharp: StripeSample,
    )

    private fun stripeSample(image: PixelMap, fixture: DpRect, rootY: Float): StripeSample {
        val y = ((rootY - fixture.top.value) * image.height / (fixture.bottom.value - fixture.top.value))
            .roundToInt().coerceIn(0, image.height - 1)
        val values = (image.width * 3 / 10 until image.width * 7 / 10).map { x ->
            val color = image[x, y]
            color.red * 0.2126f + color.green * 0.7152f + color.blue * 0.0722f
        }
        val mean = values.average().toFloat()
        val contrast = sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size).toFloat()
        return StripeSample(mean, contrast)
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @androidx.compose.runtime.Composable
    private fun StripeSource(source: HazeState) {
        Box(Modifier.fillMaxSize().hazeSource(source).drawBehind {
            val stripeWidth = 2.dp.toPx()
            var x = 0f
            var index = 0
            while (x < size.width) {
                val width = minOf(stripeWidth, size.width - x)
                drawRect(
                    color = if (index++ % 2 == 0) Color.Black else Color.White,
                    topLeft = Offset(x, 0f), size = Size(width, size.height),
                )
                x += width
            }
        })
    }

    private fun awaitChromeFade(boundaryY: Float, label: String): ChromeFadeSamples {
        var latest: ChromeFadeSamples? = null
        compose.waitUntil(5_000) {
            val fixtureNode = compose.onNodeWithTag("stripe-fixture")
            val image = fixtureNode.captureToImage().toPixelMap()
            val bounds = fixtureNode.getUnclippedBoundsInRoot()
            fun at(offset: Float) = stripeSample(image, bounds, boundaryY + offset)
            ChromeFadeSamples(
                rowTop = at(-42f), rowBottom = at(-2f),
                tailStart = at(2f), tailMiddle = at(14f), tailEnd = at(26f),
                restored = at(32f), sharp = at(38f),
            ).also { latest = it }.let {
                it.sharp.contrast > 0.2f && it.rowBottom.contrast < it.sharp.contrast * 0.3f &&
                    it.rowBottom.mean in 0.05f..0.95f
            }
        }
        val samples = checkNotNull(latest) { "$label: 未捕获到条纹像素" }
        val baseline = samples.sharp.contrast
        assertTrue("$label: 第二行上部应保持完整模糊，samples=$samples", samples.rowTop.contrast < baseline * 0.3f)
        assertTrue("$label: 第二行底部不能提前恢复清晰，samples=$samples", samples.rowBottom.contrast < baseline * 0.3f)
        assertTrue("$label: 尾部起点应仍在渐隐中，samples=$samples", samples.tailStart.contrast < samples.tailMiddle.contrast)
        assertTrue("$label: 渐隐起点不能突然露出清晰条纹形成横线，samples=$samples", samples.tailStart.contrast < baseline * 0.025f)
        assertTrue("$label: 渐隐末端应平缓接回正文，samples=$samples", baseline - samples.tailEnd.contrast < baseline * 0.05f)
        assertTrue("$label: 尾部应逐步恢复图案对比度，samples=$samples", samples.tailMiddle.contrast < samples.tailEnd.contrast)
        assertTrue("$label: 渐隐结束后应恢复原始清晰条纹，samples=$samples", samples.restored.contrast > baseline * 0.8f)
        assertTrue("$label: 尾部末端仍应比尾部外更模糊，samples=$samples", samples.tailEnd.contrast < samples.restored.contrast)
        assertTrue("$label: 恢复后的条纹应接近未处理源，samples=$samples", abs(samples.restored.contrast - baseline) < baseline * 0.2f)
        return samples
    }

    @Test fun searchRouteAndQueryRestoreTogetherAndReopeningStartsEmpty() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsPage(content = LocalSongsContent(), onOpenDrawer = {})
                }
            }
        }
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("恢复关键词")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("恢复关键词")
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("在 0 首歌曲中搜索").assertIsDisplayed() }.isSuccess
        }
    }

    @Test fun openingSearchBeforeSortFinishesNeverReportsAnEmptyLibrary() {
        val content = mutableStateOf<LocalSongsContent?>(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsPage(content = content.value, onOpenDrawer = {})
                }
            }
        }
        compose.onNodeWithText("还没有本地歌曲").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()
        compose.onNodeWithText("在 0 首歌曲中搜索").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("测试")
        compose.onNodeWithText("未找到与「测试」相关的歌曲").assertDoesNotExist()
        compose.runOnIdle { content.value = LocalSongsContent() }
        compose.onNodeWithText("未找到与「测试」相关的歌曲").assertIsDisplayed()
    }

    @Test fun emptyStateIsBelowTheWholeFixedHeaderWithAndWithoutBlur() {
        lateinit var list: LazyListState
        var minimumPadding = 0
        compose.setContent {
            list = rememberLazyListState()
            minimumPadding = with(LocalDensity.current) { (110.dp + 24.dp).roundToPx() }
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsListContent(
                        content = LocalSongsContent(),
                        selection = SongSelectionState(), listState = list,
                        onOpenDrawer = {}, onOpenSearch = {}, onOpenSortSheet = {}, onMore = {},
                        onDeleteSelection = {}, onAddToPlaylist = {}, pullEnabled = true,
                        refreshing = false, onRefresh = {},
                    )
                }
            }
        }
        for (blur in listOf(false, true)) {
            compose.runOnIdle { MeloraSettings.blurTopBar.value = blur }
            compose.onNodeWithText("还没有本地歌曲").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue("空态不能绘制到固定栏背后", list.layoutInfo.beforeContentPadding >= minimumPadding)
            }
        }
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @Test fun chromeBlurUpdatesSourceClearsRemovedContentAndKeepsLayoutWhenToggled() {
        val sourceColor = mutableStateOf(Color.Red)
        val showSource = mutableStateOf(true)
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        compose.setContent {
            val source = rememberHazeState()
            MaterialTheme {
                ChromeScaffold(
                    modifier = Modifier.requiredSize(240.dp, 300.dp),
                    containerColor = Color.White, headerColor = Color.White,
                    contentSource = source, expectedTopBarHeight = 100.dp,
                    topBar = { Box(Modifier.fillMaxWidth().height(100.dp).testTag("blur-header")) },
                ) {
                    if (showSource.value) Box(Modifier.fillMaxSize().hazeSource(source).background(sourceColor.value))
                }
            }
        }
        val header = compose.onNodeWithTag("blur-header")
        fun awaitColor(predicate: (Color) -> Boolean) = compose.waitUntil(5_000) {
            val pixels = header.captureToImage().toPixelMap()
            predicate(pixels[pixels.width / 2, pixels.height * 3 / 5])
        }
        fun awaitWhite() = awaitColor { it.red > 0.95f && it.green > 0.95f && it.blue > 0.95f }
        val supportsBlur = HazeBlurDefaults.isBlurEnabledByDefault()
        if (supportsBlur) awaitColor { it.red > it.blue + 0.15f } else awaitWhite()
        val bounds = header.getUnclippedBoundsInRoot()
        compose.runOnIdle { sourceColor.value = Color.Blue }
        if (supportsBlur) awaitColor { it.blue > it.red + 0.15f } else awaitWhite()
        compose.runOnIdle { MeloraSettings.blurTopBar.value = false }
        awaitWhite()
        assertEquals(bounds, header.getUnclippedBoundsInRoot())
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        if (supportsBlur) awaitColor { it.blue > it.red + 0.15f } else awaitWhite()
        assertEquals(bounds, header.getUnclippedBoundsInRoot())
        compose.runOnIdle { showSource.value = false }
        awaitWhite()
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @SdkSuppress(minSdkVersion = 31)
    @Test fun mergedSecondaryRowStaysBlurredAndItsTailJoinsTheBodySmoothly() {
        val clicks = mutableStateOf(0)
        compose.runOnIdle { MeloraSettings.blurTopBar.value = false }
        compose.setContent {
            val source = rememberHazeState()
            val canvas = MeloraAppearance.canvas
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("stripe-fixture")) {
                    ChromeScaffold(
                        modifier = Modifier.fillMaxSize(), containerColor = canvas, headerColor = canvas,
                        contentSource = source, expectedTopBarHeight = 110.dp, hasSecondaryRow = true,
                        topBar = {
                            Column(Modifier.fillMaxWidth().testTag("merged-header")) {
                                Box(Modifier.fillMaxWidth().height(64.dp))
                                Box(Modifier.fillMaxWidth().height(46.dp))
                            }
                        },
                    ) {
                        val topInset = LocalChromeTopInset.current
                        Box(Modifier.fillMaxSize()) {
                            StripeSource(source)
                            Button(
                                onClick = { clicks.value++ },
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                                    .offset(y = topInset - 1.dp).requiredSize(36.dp).testTag("merged-tail-button"),
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("+") }
                        }
                    }
                }
            }
        }
        val header = compose.onNodeWithTag("merged-header")
        for (dark in listOf(false, true)) {
            compose.runOnIdle {
                MeloraAppearance.isDark = dark
                MeloraSettings.blurTopBar.value = false
            }
            compose.waitForIdle()
            val bounds = header.getUnclippedBoundsInRoot()
            compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
            awaitChromeFade(bounds.bottom.value, "合并双行 dark=$dark")
            assertEquals("模糊开关不能改变双行标题栏布局", bounds, header.getUnclippedBoundsInRoot())
            compose.onNodeWithTag("merged-tail-button").performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(if (dark) 2 else 1, clicks.value) }
        }
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @SdkSuppress(minSdkVersion = 31)
    @Test fun pinnedFloatingRowSharesTheTailWithoutChangingLayoutOrBlockingClicks() {
        val pinned = mutableStateOf(false)
        val clicks = mutableStateOf(0)
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        compose.setContent {
            val source = rememberHazeState()
            val canvas = MeloraAppearance.canvas
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("stripe-fixture")) {
                    ChromeScaffold(
                        modifier = Modifier.fillMaxSize(), containerColor = canvas, headerColor = canvas,
                        contentSource = source, expectedTopBarHeight = 64.dp,
                        topBar = { Box(Modifier.fillMaxWidth().height(64.dp).testTag("fixed-header")) },
                    ) {
                        val topInset = LocalChromeTopInset.current
                        Box(Modifier.fillMaxSize()) {
                            StripeSource(source)
                            Button(
                                onClick = { clicks.value++ },
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                                    .offset(y = topInset + PlaylistControlsHeight - 1.dp).requiredSize(36.dp).testTag("floating-tail-button"),
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("+") }
                            ChromeFloatingBar(
                                state = source, topOffset = topInset, height = PlaylistControlsHeight, pinned = pinned.value,
                                modifier = Modifier.fillMaxWidth().offset(y = topInset).testTag("floating-chrome-bar"),
                            ) {}
                        }
                    }
                }
            }
        }
        val header = compose.onNodeWithTag("fixed-header")
        val bar = compose.onNodeWithTag("floating-chrome-bar")
        for (dark in listOf(false, true)) {
            compose.runOnIdle {
                MeloraAppearance.isDark = dark
                pinned.value = false
            }
            compose.waitForIdle()
            val headerBounds = header.getUnclippedBoundsInRoot()
            val barBounds = bar.getUnclippedBoundsInRoot()
            assertTrue("独立吸顶栏应紧接主标题栏", abs(barBounds.top.value - headerBounds.bottom.value) < 1f)
            compose.runOnIdle { pinned.value = true }
            awaitChromeFade(barBounds.bottom.value, "吸顶操作栏 dark=$dark")
            val fixture = compose.onNodeWithTag("stripe-fixture")
            val pixels = fixture.captureToImage().toPixelMap()
            val fixtureBounds = fixture.getUnclippedBoundsInRoot()
            val above = stripeSample(pixels, fixtureBounds, barBounds.top.value - 1f)
            val below = stripeSample(pixels, fixtureBounds, barBounds.top.value + 1f)
            assertTrue("两行材质交界不能出现色差横线，dark=$dark: $above / $below", abs(above.mean - below.mean) < 0.015f)
            assertEquals("pinned 切换不能移动主标题栏", headerBounds, header.getUnclippedBoundsInRoot())
            assertEquals("pinned 切换不能移动操作栏", barBounds, bar.getUnclippedBoundsInRoot())
            compose.onNodeWithTag("floating-tail-button").performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(if (dark) 2 else 1, clicks.value) }
            compose.runOnIdle { pinned.value = false }
            compose.waitForIdle()
            assertEquals("取消吸顶不能改变占位尺寸", barBounds, bar.getUnclippedBoundsInRoot())
        }
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @SdkSuppress(minSdkVersion = 31)
    @Test fun singleRowChromeKeepsItsOriginalFadeAndDoesNotGrowATail() {
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        compose.setContent {
            val source = rememberHazeState()
            val canvas = MeloraAppearance.canvas
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("stripe-fixture")) {
                    ChromeScaffold(
                        modifier = Modifier.fillMaxSize(), containerColor = canvas, headerColor = canvas,
                        contentSource = source, expectedTopBarHeight = 64.dp,
                        topBar = { Box(Modifier.fillMaxWidth().height(64.dp).testTag("single-header")) },
                    ) { StripeSource(source) }
                }
            }
        }
        val header = compose.onNodeWithTag("single-header")
        for (dark in listOf(false, true)) {
            compose.runOnIdle { MeloraAppearance.isDark = dark }
            compose.waitForIdle()
            val bounds = header.getUnclippedBoundsInRoot()
            var samples: List<StripeSample>? = null
            compose.waitUntil(5_000) {
                val fixtureNode = compose.onNodeWithTag("stripe-fixture")
                val image = fixtureNode.captureToImage().toPixelMap()
                val fixture = fixtureNode.getUnclippedBoundsInRoot()
                val values = listOf(
                    stripeSample(image, fixture, bounds.top.value + 8f),
                    stripeSample(image, fixture, bounds.top.value + 44f),
                    stripeSample(image, fixture, bounds.bottom.value - 4f),
                    stripeSample(image, fixture, bounds.bottom.value + 8f),
                    stripeSample(image, fixture, bounds.bottom.value + 24f),
                    stripeSample(image, fixture, bounds.bottom.value + 30f),
                )
                samples = values
                values.last().contrast > 0.2f && values[0].contrast < values.last().contrast * 0.3f &&
                    values[2].contrast > values.last().contrast * 0.65f
            }
            val pixels = checkNotNull(samples)
            val baseline = pixels.last().contrast
            assertTrue("单行栏原渐变应从约60%处开始淡出，dark=$dark: $pixels", pixels[1].contrast < baseline * 0.8f)
            assertTrue("单行栏下沿应恢复原清晰图案，dark=$dark: $pixels", pixels[2].contrast > baseline * 0.65f)
            assertTrue("单行栏下方不得扩绘双行尾巴，dark=$dark: $pixels", abs(pixels[3].contrast - baseline) < baseline * 0.2f)
            assertTrue("单行栏下方图案应持续清晰，dark=$dark: $pixels", abs(pixels[4].contrast - baseline) < baseline * 0.2f)
            assertEquals(bounds, header.getUnclippedBoundsInRoot())
        }
    }

    @OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
    @SdkSuppress(minSdkVersion = 31)
    @Test fun startBleedRendersBlurOutsideThePaneThroughTheNestedPageHost() {
        assumeTrue("需要设备支持真实模糊像素", HazeBlurDefaults.isBlurEnabledByDefault())
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val bleed = mutableStateOf(0.dp)
        val canvas = Color(0xFF202020)
        compose.runOnIdle { MeloraSettings.blurTopBar.value = true }
        compose.setContent {
            val source = rememberHazeState()
            MaterialTheme {
                CompositionLocalProvider(
                    LocalChromeStartBleed provides bleed.value,
                    LocalLayoutDirection provides direction.value,
                ) {
                    Box(Modifier.requiredSize(256.dp, 320.dp).background(canvas).testTag("bleed-fixture")) {
                        ChromeScaffold(
                            modifier = Modifier.offset(x = 8.dp).requiredSize(240.dp, 300.dp),
                            containerColor = canvas,
                            expectedTopBarHeight = 0.dp,
                        ) {
                            DetailPageHost<String>(target = null, detail = {}) {
                                ChromeScaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    containerColor = canvas, headerColor = canvas,
                                    contentSource = source, expectedTopBarHeight = 64.dp,
                                    bottomBar = { Box(Modifier.fillMaxWidth().height(24.dp).testTag("bleed-footer")) },
                                    topBar = {
                                        Box(Modifier.fillMaxWidth().height(64.dp).testTag("bleed-header"))
                                    },
                                ) {
                                    val topInset = LocalChromeTopInset.current
                                    Box(Modifier.fillMaxSize().hazeSource(source).drawBehind {
                                        val bandHeight = 2.dp.toPx()
                                        var y = 0f
                                        var band = 0
                                        while (y < size.height) {
                                            val height = minOf(bandHeight, size.height - y)
                                            drawRect(
                                                if (band++ % 2 == 0) Color.Red else Color.Blue,
                                                topLeft = Offset(0f, y), size = Size(size.width, height),
                                            )
                                            y += height
                                        }
                                    }) {
                                        Box(
                                            Modifier.offset(y = topInset).fillMaxWidth().height(40.dp)
                                                .testTag("bleed-body-anchor"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val fixture = compose.onNodeWithTag("bleed-fixture")
        val header = compose.onNodeWithTag("bleed-header")
        for (rtl in listOf(false, true)) {
            compose.runOnIdle {
                direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                bleed.value = 0.dp
            }
            compose.waitForIdle()
            val anchors = listOf("bleed-header", "bleed-body-anchor", "bleed-footer")
            val originalBounds = anchors.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
            compose.runOnIdle { bleed.value = 8.dp }
            compose.waitForIdle()
            assertEquals("背景外绘不能移动标题、正文和底栏", originalBounds,
                anchors.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() })
            val headerBounds = header.getUnclippedBoundsInRoot()
            val fixtureBounds = fixture.getUnclippedBoundsInRoot()
            // 在完整模糊区比较横向接缝，不能把单行底部原有的正文透出混入边缘断言。
            val y = headerBounds.top.value + (headerBounds.bottom.value - headerBounds.top.value) * 0.25f
            val start = if (rtl) headerBounds.right.value else headerBounds.left.value
            val outward = if (rtl) 1f else -1f
            fun pixel(image: PixelMap, rootX: Float): Color {
                val x = ((rootX - fixtureBounds.left.value) * image.width /
                    (fixtureBounds.right.value - fixtureBounds.left.value)).roundToInt().coerceIn(0, image.width - 1)
                val py = ((y - fixtureBounds.top.value) * image.height /
                    (fixtureBounds.bottom.value - fixtureBounds.top.value)).roundToInt().coerceIn(0, image.height - 1)
                return image[x, py]
            }
            fun colorDistance(first: Color, second: Color) = maxOf(
                abs(first.red - second.red), abs(first.green - second.green), abs(first.blue - second.blue),
            )
            var latest: PixelMap? = null
            compose.waitUntil(5_000) {
                val image = fixture.captureToImage().toPixelMap().also { latest = it }
                val inside = pixel(image, start - outward * 16f)
                inside.red > inside.green + 0.08f && inside.blue > inside.green + 0.08f
            }
            val image = checkNotNull(latest)
            val outerEdge = pixel(image, start + outward * 7.5f)
            val gutter = pixel(image, start + outward * 4f)
            val beforeBoundary = pixel(image, start + outward)
            val afterBoundary = pixel(image, start - outward)
            val inside = pixel(image, start - outward * 16f)
            assertTrue("${if (rtl) "RTL" else "LTR"}: 最外沿保持画布色", colorDistance(outerEdge, canvas) < 0.10f)
            assertTrue(
                "${if (rtl) "RTL" else "LTR"}: 原 pane 外的8dp应含真实模糊色而非白色覆盖，pixel=$gutter",
                colorDistance(gutter, canvas) > 0.04f && colorDistance(gutter, Color.White) > 0.12f &&
                    gutter.red > gutter.green + 0.03f && gutter.blue > gutter.green + 0.03f &&
                    colorDistance(gutter, canvas) < colorDistance(inside, canvas),
            )
            assertTrue(
                "${if (rtl) "RTL" else "LTR"}: 外绘跨原 pane 边界不能突变: $beforeBoundary / $afterBoundary; gutter=$gutter, inside=$inside",
                colorDistance(beforeBoundary, afterBoundary) < 0.15f,
            )
        }
    }

    @Test fun emptyCursorAndPlaceholderShareTheSameVerticalCenterAtDifferentFontScales() {
        val scale = mutableStateOf(1f)
        var tolerance = 0f
        compose.setContent {
            val density = LocalDensity.current
            tolerance = density.density
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale.value),
                    LocalSongListState provides shared,
                ) {
                    LocalSearchPage(
                        content = LocalSongsContent(), query = "", onQueryChange = {}, onCancel = {},
                        selection = SongSelectionState(), onOpenSortSheet = {}, onMore = {},
                        onDeleteSelection = {}, onAddToPlaylist = {},
                    )
                }
            }
        }
        for (fontScale in listOf(1f, 1.4f)) {
            compose.runOnIdle { scale.value = fontScale }
            val field = compose.onNode(hasSetTextAction())
            val hint = compose.onNodeWithText("在 0 首歌曲中搜索", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("搜索框应提供真实文字布局", layouts.isNotEmpty())
            val cursorY = field.fetchSemanticsNode().boundsInRoot.top + layouts.single().getCursorRect(0).center.y
            val hintY = hint.fetchSemanticsNode().boundsInRoot.center.y
            assertTrue("fontScale=$fontScale cursor=$cursorY hint=$hintY", abs(cursorY - hintY) <= tolerance)
        }
    }
}
