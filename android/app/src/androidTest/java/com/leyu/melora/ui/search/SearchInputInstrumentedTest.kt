package com.leyu.melora.ui.search

import android.graphics.Bitmap
import android.view.PixelCopy
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.SourceAlias
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.LocalGlassBackdrop
import com.leyu.melora.ui.theme.MeloraTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchInputInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchHintHasReadableContrastOverOppositeArtwork() {
        val originalMode = MeloraSettings.themeMode.value
        val originalBlur = MeloraSettings.componentBlurMode.value
        val artwork = mutableStateOf(Color.White)
        val query = TextFieldState()
        try {
            compose.setContent {
                MeloraTheme {
                    ChromeScaffold(expectedTopBarHeight = SearchInputSectionHeight, topBar = {
                        SearchInputSection(
                            SearchCategory.Audiobook, query, PlatformSource.All, false, emptyList(),
                            onSubmit = {}, onClear = {}, onPlatformMenuExpandedChange = {}, onPlatformChange = {},
                        )
                    }) { Box(Modifier.fillMaxSize().background(artwork.value)) }
                }
            }
            val hint = compose.onNodeWithText("搜索有声书、小说、评书...", useUnmergedTree = true)
            for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                for (blur in listOf(false, true)) {
                    compose.runOnIdle {
                        MeloraSettings.themeMode.value = mode
                        MeloraSettings.componentBlurMode.value = if (blur) ComponentBlurMode.Header else ComponentBlurMode.Off
                        artwork.value = if (mode == ThemeMode.Dark) Color.White else Color.Black
                    }
                    compose.awaitStable(hint)
                    val pixels = hint.captureToImage().toPixelMap()
                    val background = pixels[0, 0].toArgb()
                    val contrast = (0 until pixels.height).maxOf { y ->
                        (0 until minOf(pixels.height, pixels.width)).maxOf { x ->
                            ColorUtils.calculateContrast(pixels[x, y].toArgb(), background)
                        }
                    }
                    assertTrue("搜索提示对比度过低 mode=$mode blur=$blur ratio=$contrast", contrast >= 4.5)
                }
            }
        } finally {
            MeloraSettings.themeMode.value = originalMode
            MeloraSettings.componentBlurMode.value = originalBlur
        }
    }

    @Test fun lastSuggestionIsReachableWithLimitedHeight() {
        val labels = (1..8).map { "歌单 $it" }
        val submitted = mutableListOf<String>()
        val query = TextFieldState("歌单")
        compose.setContent {
            MeloraTheme {
                Box(Modifier.height(180.dp)) {
                    SearchInputSection(
                        category = SearchCategory.Playlist, queryState = query, selectedPlatform = PlatformSource.Tencent,
                        platformMenuExpanded = false, suggestions = labels, onSubmit = { submitted += it },
                        onClear = {}, onPlatformMenuExpandedChange = {}, onPlatformChange = {},
                    )
                }
            }
        }
        val last = compose.onNodeWithText(labels.last())
        assertFalse(last.isDisplayed())
        last.performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(labels.last()), submitted) }
    }

    @Test fun chineseCompositionCanCommitAndSubmitInTheSameImeEventBatch() {
        val queryState = TextFieldState()
        val connection = AtomicReference<InputConnection>()
        val submitted = mutableListOf<String>()
        compose.setContent {
            InterceptPlatformTextInput(interceptor = { request, _ ->
                connection.set(request.createInputConnection(EditorInfo()))
                awaitCancellation()
            }) {
                MeloraTheme {
                    SearchInputSection(
                        category = SearchCategory.Audiobook,
                        queryState = queryState,
                        selectedPlatform = PlatformSource.All,
                        platformMenuExpanded = false,
                        suggestions = listOf("仙逆广播剧"),
                        onSubmit = { submitted += it },
                        onClear = { queryState.clearText() },
                        onPlatformMenuExpandedChange = {},
                        onPlatformChange = {},
                    )
                }
            }
        }

        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        compose.waitUntil(5_000) { connection.get() != null }
        compose.runOnIdle { connection.get().setComposingText("xian", 1) }
        field.assertTextContains("xian")
        compose.runOnIdle {
            assertNotNull(queryState.composition)
            val ime = connection.get()
            ime.setComposingText("xianni", 1)
            ime.commitText("仙逆", 1)
            ime.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            assertNull(queryState.composition)
            assertEquals(listOf("仙逆"), submitted)
        }
        field.assertTextContains("仙逆")
        compose.onNodeWithText("仙逆广播剧").performClick()
        compose.onNodeWithContentDescription("清除").performClick()
        compose.runOnIdle {
            assertEquals(listOf("仙逆", "仙逆广播剧"), submitted)
            assertEquals("", queryState.text.toString())
        }
    }

    @Test fun platformMenuMatchesTheMeasuredAnchorAcrossWidthsWithAppScaling() {
        val selected = mutableStateOf(PlatformSource.All)
        val expanded = mutableStateOf(true)
        val useFullWidth = mutableStateOf(false)
        val queryState = TextFieldState("乐屿")
        val submitted = mutableListOf<String>()
        compose.setContent {
            val systemDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(systemDensity * 1.3f)) {
                MeloraTheme {
                    val width = if (useFullWidth.value) {
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    } else {
                        Modifier.width(210.dp)
                    }
                    Box(width.testTag("search-anchor")) {
                        SearchInputSection(
                            category = SearchCategory.Song,
                            queryState = queryState,
                            selectedPlatform = selected.value,
                            platformMenuExpanded = expanded.value,
                            suggestions = listOf("乐屿推荐"),
                            onSubmit = { submitted += it },
                            onClear = { queryState.clearText() },
                            onPlatformMenuExpandedChange = { expanded.value = it },
                            onPlatformChange = {
                                selected.value = it
                                expanded.value = false
                            },
                        )
                    }
                }
            }
        }

        val anchor = compose.onNodeWithTag("search-anchor")
        val popup = compose.onNode(isPopup())
        fun assertAligned() {
            val anchorWidth = anchor.fetchSemanticsNode().size.width
            val menu = popup.assertIsDisplayed().fetchSemanticsNode()
            assertEquals(anchorWidth, menu.size.width)
            assertEquals(anchorWidth.toFloat(), menu.boundsInRoot.width, 1f)
        }
        compose.mainClock.advanceTimeBy(500)
        assertAligned()
        platformOption(PlatformSource.All).assertIsSelected()
        platformOption(PlatformSource.Kuwo).performClick()
        popup.assertDoesNotExist()
        compose.runOnIdle { useFullWidth.value = true }
        compose.onNodeWithContentDescription("选择平台").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertAligned()
        platformOption(PlatformSource.Kuwo).assertIsSelected()
        platformOption(PlatformSource.Tencent).performClick()
        popup.assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithText("乐屿推荐").performClick()
        compose.runOnIdle {
            assertEquals(PlatformSource.Tencent, selected.value)
            assertEquals(listOf("乐屿", "乐屿推荐"), submitted)
        }
    }

    @Test fun narrowPlatformMenuReflowsAndShowsCompleteLabelsWithLargeText() {
        val expanded = mutableStateOf(false)
        val queryState = TextFieldState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                MeloraTheme {
                    Box(Modifier.width(164.dp)) {
                        SearchInputSection(
                            category = SearchCategory.Playlist,
                            queryState = queryState,
                            selectedPlatform = PlatformSource.All,
                            platformMenuExpanded = expanded.value,
                            suggestions = emptyList(),
                            onSubmit = {},
                            onClear = {},
                            onPlatformMenuExpandedChange = { expanded.value = it },
                            onPlatformChange = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("选择平台").performClick()
        val first = platformOption(PlatformSource.Kuwo).fetchSemanticsNode().positionInRoot
        val second = platformOption(PlatformSource.Kugou).fetchSemanticsNode().positionInRoot
        assertEquals(first.x, second.x, 1f)
        assertTrue(second.y > first.y)
        for (platform in PlatformSource.entries) {
            val label = SourceAlias.displayMusic(platform.id, platform.label)
            platformOption(platform).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasAnyAncestor(isPopup()) and hasText(label), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(label, layout.hasVisualOverflow)
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun lightPlatformMenuSamplesArtworkBehindItsSelectableCards() {
        val originalMode = MeloraSettings.themeMode.value
        val originalBlur = MeloraSettings.componentBlurMode.value
        val darkArtwork = Color(0xFF38343E)
        val lightArtwork = Color(0xFFECE3D6)
        val artwork = mutableStateOf(darkArtwork)
        val category = mutableStateOf(SearchCategory.Song)
        val selected = mutableStateOf(PlatformSource.All)
        val expanded = mutableStateOf(false)
        val query = TextFieldState()
        try {
            MeloraSettings.themeMode.value = ThemeMode.Light
            MeloraSettings.componentBlurMode.value = ComponentBlurMode.All
            compose.setContent {
                MeloraTheme {
                    val source = rememberHazeState()
                    Box(Modifier.fillMaxSize().hazeSource(source).background(artwork.value)) {
                        CompositionLocalProvider(LocalGlassBackdrop provides source) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp)) {
                                SearchInputSection(
                                    category = category.value,
                                    queryState = query,
                                    selectedPlatform = selected.value,
                                    platformMenuExpanded = expanded.value,
                                    suggestions = emptyList(),
                                    onSubmit = {},
                                    onClear = {},
                                    onPlatformMenuExpandedChange = { expanded.value = it },
                                    onPlatformChange = { selected.value = it; expanded.value = false },
                                )
                            }
                        }
                    }
                }
            }

            val button = compose.onNodeWithContentDescription("选择平台")
            val menu = compose.onNode(isPopup())
            val allPlatforms = platformOption(PlatformSource.All)
            val kuwo = platformOption(PlatformSource.Kuwo)
            button.performClick()
            compose.awaitStable(kuwo)
            allPlatforms.assertIsSelected()
            val (root, active, inactive) = compose.boundsInSameFrame(menu, allPlatforms, kuwo)
            val outsideOffset = with(compose.density) { 4.dp.toPx() }
            val insideOffset = with(compose.density) { 6.dp.toPx() }
            val sampleRadius = with(compose.density) { 1.dp.roundToPx() }.coerceAtLeast(1)
            fun sample(pixels: PixelMap, option: Rect, xOffset: Float) =
                pixels.averageColor(
                    (option.left - root.left + xOffset).roundToInt(),
                    (option.center.y - root.top).roundToInt(),
                    sampleRadius,
                )

            val panelTint = Color(0xFFF6F7FA).copy(alpha = 0.78f)
            // Popup 有独立窗口，直接从该窗口读取实际合成结果。
            val node = menu.fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            val bounds = node.boundsInWindow
            val region = android.graphics.Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.right.roundToInt(), bounds.bottom.roundToInt())
            fun captureMenu(): Bitmap {
                val result = CompletableFuture<PixelCopy.Result>()
                val request = PixelCopy.Request.Builder.ofWindow(view).setSourceRect(region).build()
                PixelCopy.request(request, Runnable::run) { result.complete(it) }
                val copy = result.get(2, TimeUnit.SECONDS)
                assertEquals(PixelCopy.SUCCESS, copy.status)
                return copy.bitmap
            }
            val firstImage = captureMenu()
            val first = firstImage.asImageBitmap().toPixelMap()
            val darkPanel = sample(first, inactive, -outsideOffset)
            val darkCard = sample(first, inactive, insideOffset)
            assertColorNear("浮层应使用 78% 冷白玻璃", panelTint.compositeOver(darkArtwork), darkPanel)
            assertColorNear(
                "未选平台使用 4.5% 透色卡面",
                Color(0xFF0F172A).copy(alpha = 0.045f).compositeOver(darkPanel),
                darkCard,
                tolerance = 0.025f,
            )
            assertColorNear(
                "选中平台使用 12% 蓝色填充",
                Color(0xFF3567E8).copy(alpha = 0.12f).compositeOver(sample(first, active, -outsideOffset)),
                sample(first, active, insideOffset),
                tolerance = 0.025f,
            )
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            File(cache, "light-search-platform-menu.png").outputStream().use {
                firstImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            compose.runOnIdle { artwork.value = lightArtwork }
            compose.waitUntil(conditionDescription = "浮层和卡面随搜索底图一起变亮", timeoutMillis = 5_000) {
                val updated = captureMenu().asImageBitmap().toPixelMap()
                val panel = sample(updated, inactive, -outsideOffset)
                val card = sample(updated, inactive, insideOffset)
                colorDistance(panel, panelTint.compositeOver(lightArtwork)) < 0.035f &&
                    panel.red - darkPanel.red > 0.08f && card.red - darkCard.red > 0.08f
            }
            kuwo.performClick()
            menu.assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(PlatformSource.Kuwo, selected.value)
                category.value = SearchCategory.Playlist
            }
            button.performClick()
            compose.awaitStable(kuwo)
            kuwo.assertIsSelected()
            platformOption(PlatformSource.Tencent).performClick()
            menu.assertDoesNotExist()
            compose.runOnIdle { assertEquals(PlatformSource.Tencent, selected.value) }
        } finally {
            MeloraSettings.themeMode.value = originalMode
            MeloraSettings.componentBlurMode.value = originalBlur
        }
    }

    private fun PixelMap.averageColor(centerX: Int, centerY: Int, radius: Int): Color {
        var red = 0f
        var green = 0f
        var blue = 0f
        for (y in centerY - radius..centerY + radius) {
            for (x in centerX - radius..centerX + radius) {
                val pixel = this[x, y]
                red += pixel.red
                green += pixel.green
                blue += pixel.blue
            }
        }
        val width = 2 * radius + 1
        val samples = width * width
        return Color(red / samples, green / samples, blue / samples)
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color, tolerance: Float = 0.035f) =
        assertTrue("$message，预期 $expected，实测 $actual", colorDistance(expected, actual) < tolerance)

    private fun colorDistance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun platformOption(platform: PlatformSource) =
        compose.onNode(hasAnyAncestor(isPopup()) and hasText(SourceAlias.displayMusic(platform.id, platform.label)) and hasClickAction())
}
