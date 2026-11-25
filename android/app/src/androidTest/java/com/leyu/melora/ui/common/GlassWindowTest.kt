package com.leyu.melora.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.Tag
import com.leyu.melora.playback.sdk.TagInfo
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.playlist.PlaylistTopBarFilter
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.MeloraTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class GlassWindowTest {
    @get:Rule val compose = createComposeRule()

    private val originalBlur = MeloraSettings.componentBlurMode.value
    private val originalTheme = MeloraSettings.themeMode.value

    @Before fun useLightGlass() {
        MeloraSettings.componentBlurMode.value = ComponentBlurMode.All
        MeloraSettings.themeMode.value = ThemeMode.Light
    }

    @After fun restoreTheme() {
        MeloraSettings.componentBlurMode.value = originalBlur
        MeloraSettings.themeMode.value = originalTheme
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun sheetAndDialogDiffuseFineDetailAndFollowThePageColor() {
        val dialog = mutableStateOf(false)
        val pageColor = mutableStateOf(Color.Red)
        compose.setContent {
            MeloraTheme {
                val source = rememberHazeState()
                Canvas(Modifier.fillMaxSize().hazeSource(source)) {
                    drawRect(pageColor.value)
                    var x = 0f
                    val stripe = 2.dp.toPx()
                    while (x < size.width) {
                        drawRect(Color.Black, Offset(x, 0f), Size(stripe, size.height))
                        x += 2 * stripe
                    }
                }
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    val sample = Modifier.fillMaxWidth().height(80.dp).testTag("glass-sample")
                    if (dialog.value) {
                        MeloraAlertDialog(
                            onDismissRequest = {}, title = {}, text = { Box(sample) },
                            confirmButton = {}, dismissButton = {}, containerColor = MeloraAppearance.card,
                        )
                    } else {
                        MeloraBottomSheet(onDismissRequest = {}, sheetState = rememberMeloraBottomSheetState(true),
                            dragHandle = { Box(Modifier.size(36.dp, 4.dp).testTag("glass-handle").background(Color.Green)) },
                        ) {
                            Box(sample)
                        }
                    }
                }
            }
        }
        val sample = compose.onNodeWithTag("glass-sample")
        for (isDialog in listOf(false, true)) {
            compose.runOnIdle { dialog.value = isDialog }
            compose.awaitStable(sample)
            if (!isDialog) {
                val handle = compose.onNodeWithTag("glass-handle", useUnmergedTree = true).captureToImage().toPixelMap()
                assertEquals("把手应绘制在玻璃前面", Color.Green, handle[handle.width / 2, handle.height / 2])
            }
            for (mode in listOf(ComponentBlurMode.Off, ComponentBlurMode.Header)) {
                compose.runOnIdle { MeloraSettings.componentBlurMode.value = mode }
                compose.waitUntil(5_000) {
                    val pixels = sample.captureToImage().toPixelMap()
                    val panel = pixels[pixels.width / 2, pixels.height / 2]
                    panel.red > 0.9f && abs(panel.red - panel.blue) < 0.025f
                }
            }
            compose.runOnIdle { MeloraSettings.componentBlurMode.value = ComponentBlurMode.All }
            for (color in listOf(Color.Red, Color.Blue)) {
                compose.runOnIdle { pageColor.value = color }
                compose.waitUntil(conditionDescription = "glass diffuses 2dp stripes, dialog=$isDialog page=$color", timeoutMillis = 5_000) {
                    val pixels = sample.captureToImage().toPixelMap()
                    val ink = (pixels.width / 4 until pixels.width * 3 / 4).map { x ->
                        val p = pixels[x, pixels.height / 2]
                        if (color == Color.Red) p.red - p.blue else p.blue - p.red
                    }
                    // 大色块能透过来，同时 2dp 条纹在面板内融合；纯色和简单透明底均不满足。
                    ink.min() > 0.05f && ink.max() - ink.min() < 0.08f
                }
            }
        }
    }

    @Test fun playlistCategoryTitleFlowsIntoTheFirstSectionWithoutARule() {
        val key = "playlistTags.${MeloraSettings.playlistPlatform.value}"
        val previous = OnlineCache.peek<TagInfo>(key)
        OnlineCache.put(key, TagInfo(listOf(Tag("fixture", "华语")), emptyList()))
        try {
            compose.setContent {
                MeloraTheme {
                    val source = rememberHazeState()
                    Box(Modifier.fillMaxSize().hazeSource(source).background(Color(0xFF526C93))) {
                        CompositionLocalProvider(LocalGlassBackdrop provides source) {
                            PlaylistTopBarFilter(onOpenPlatformPicker = {})
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("选择歌单分类").performClick()
            val window = compose.onNode(isDialog())
            val title = compose.onNodeWithText("歌单分类", substring = true)
            val firstSection = compose.onNodeWithText("热门推荐")
            compose.awaitStable(firstSection)
            val (root, heading, section) = compose.boundsInSameFrame(window, title, firstSection)
            val pixels = window.captureToImage().toPixelMap()
            val start = (heading.bottom - root.top).roundToInt()
            val end = (section.top - root.top).roundToInt()
            assertTrue("标题和首组之间应有留白", end > start + 1)
            val x = pixels.width / 2
            for (y in start until end) {
                val a = pixels[x, y]
                val b = pixels[x, y + 1]
                val jump = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
                assertTrue("歌单分类标题下方有横线，像素行 $y 的色差为 $jump", jump < 0.016f)
            }
        } finally {
            if (previous == null) OnlineCache.clear(key) else OnlineCache.put(key, previous)
        }
    }

    @Test fun lightSongMenuPlacesActionsDirectlyOnPlanBFrost() =
        verifySongMenu(ThemeMode.Light, "light-plan-b-song-menu.png")

    @Test fun darkLocalSongMenuPlacesActionsDirectlyOnGlass() =
        verifySongMenu(ThemeMode.Dark, "dark-song-menu.png")

    @Test fun darkPlaylistPickerShowsAReadableNewNameHint() {
        MeloraSettings.themeMode.value = ThemeMode.Dark
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "hint-fixture").put("name", "测试歌曲"))
        compose.setContent { MeloraTheme { AddToPlaylistSheet(listOf(song), onDismiss = {}) } }
        val hint = compose.onNodeWithText("新建歌单名称…", useUnmergedTree = true)
        compose.awaitStable(hint)
        val layouts = mutableListOf<TextLayoutResult>()
        hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val ink = layouts.single().layoutInput.style.color
        val fill = MeloraAppearance.softFill
        assertTrue("深色输入框的启用态提示应清晰可读", (ink.luminance() + 0.05f) / (fill.luminance() + 0.05f) >= 4.5f)
    }

    private fun verifySongMenu(theme: ThemeMode, screenshotName: String) {
        MeloraSettings.themeMode.value = theme
        val artwork = Color(0xFF59616A)
        val local = LocalSong(
            id = "glass-menu-fixture", uri = "file:///music/glass-menu-fixture.mp3",
            title = "本地歌曲示例", artist = "测试歌手", album = "", durationMs = 120_000,
            sizeBytes = 1_000, mimeType = "audio/mpeg", sampleRate = 44_100, bitrate = 128_000,
            modifiedAt = 1, addedAt = 1, folder = "music",
        )
        val song = local.toOnlineSong()
        val shared = SongListState(mutableStateOf(listOf(local)), mutableStateOf(false), mutableStateOf(null))
        val open = mutableStateOf(true)
        val removed = mutableStateOf(false)
        compose.setContent {
            MeloraTheme {
                val source = rememberHazeState()
                Box(Modifier.fillMaxSize().hazeSource(source).background(artwork)) {
                    CompositionLocalProvider(LocalGlassBackdrop provides source, LocalSongListState provides shared) {
                        if (open.value) {
                            SongMoreSheet(
                                song = song,
                                allowDownload = false,
                                localCoverSong = local,
                                onDeleteLocal = { removed.value = true },
                                onDismiss = { open.value = false },
                            )
                        }
                    }
                }
            }
        }
        val window = compose.onNode(isDialog())
        val action = compose.onNode(hasText("永久删除") and hasClickAction())
        compose.awaitStable(action)
        val (root, row) = compose.boundsInSameFrame(window, action)
        val pixels = window.captureToCache(screenshotName).toPixelMap()
        val rowX = (row.left - root.left).roundToInt()
        val rowY = (row.center.y - root.top).roundToInt()
        val inset = with(compose.density) { 5.dp.roundToPx() }
        val panel = pixels[rowX - inset, rowY]
        val expected = if (theme == ThemeMode.Dark) expectedDarkPanel(artwork) else expectedLightPanel(artwork, 0.78f)
        assertColorsNear("单曲菜单的玻璃底板", expected, panel, tolerance = 0.025f)
        assertColorsNear("操作行应直接继承玻璃底色", panel, pixels[rowX + inset, rowY], tolerance = 0.014f)
        action.performClick()
        compose.waitUntil(5_000) { removed.value && !open.value }
    }

    @Test fun darkPlaylistCategoryDimsBrightArtworkAndShowsTheSelectedMistBlue() =
        verifyPlaylistCategory(ThemeMode.Dark)

    @Test fun lightPlaylistCategoryUsesPlanBFrostAndRecessedChips() =
        verifyPlaylistCategory(ThemeMode.Light)

    private fun verifyPlaylistCategory(theme: ThemeMode) {
        val category = Tag("glass-category-fixture", "华语")
        val key = "playlistTags.${MeloraSettings.playlistPlatform.value}"
        val previousTags = OnlineCache.peek<TagInfo>(key)
        val previousId = MeloraSettings.playlistTagId.value
        val previousName = MeloraSettings.playlistTagName.value
        val brightArtwork = Color(0xFFFFEED2)
        val darkArtwork = Color(0xFF331A40)
        val artwork = mutableStateOf(brightArtwork)
        val dark = theme == ThemeMode.Dark
        MeloraSettings.themeMode.value = theme
        MeloraSettings.playlistTagId.value = ""
        MeloraSettings.playlistTagName.value = "全部歌单"
        OnlineCache.put(key, TagInfo(listOf(category), emptyList()))
        try {
            compose.setContent {
                MeloraTheme {
                    val source = rememberHazeState()
                    Box(Modifier.fillMaxSize().hazeSource(source)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(artwork.value)
                            val right = size.width * 0.7f
                            drawRect(Color(0xFF535DAB), Offset(right, 0f), Size(size.width - right, size.height))
                        }
                        CompositionLocalProvider(LocalGlassBackdrop provides source) {
                            PlaylistTopBarFilter(onOpenPlatformPicker = {})
                        }
                    }
                }
            }
            val filter = compose.onNodeWithContentDescription("选择歌单分类")
            val window = compose.onNode(isDialog())
            fun chip(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

            filter.performClick()
            val selected = chip("全部歌单")
            val option = chip(category.name)
            compose.awaitStable(selected)
            selected.assertIsSelected()
            option.assertIsNotSelected()
            val (root, selectedBounds, optionBounds) = compose.boundsInSameFrame(window, selected, option)
            val inset = with(compose.density) { 6.dp.roundToPx() }
            val selectedLeft = (selectedBounds.left - root.left).roundToInt()
            val selectedRight = (selectedBounds.right - root.left).roundToInt()
            val sampleY = (selectedBounds.center.y - root.top).roundToInt()
            val optionX = (optionBounds.left - root.left).roundToInt() + inset
            val optionY = (optionBounds.center.y - root.top).roundToInt()
            val panelX = selectedLeft - inset

            val filename = if (dark) "dark-playlist-category.png" else "light-plan-b-playlist-category.png"
            val pixels = window.captureToCache(filename).toPixelMap()
            val panel = pixels[panelX, sampleY]
            val fill = pixels[selectedLeft + inset, sampleY]
            val chipBase = pixels[optionX, optionY]
            val scrim = pixels[pixels.width / 2, pixels.height / 8]
            val chipTint = if (dark) Color.White else Color(0xFF0F172A)
            val expectedPanel = if (dark) expectedDarkPanel(brightArtwork) else expectedLightPanel(brightArtwork)
            assertColorsNear("封面经过遮罩与抽屉底色混合", expectedPanel, panel, if (dark) 0.035f else 0.025f)
            assertEquals("背景遮罩浓度", if (dark) 0.20f else 0.10f, scrim.alpha, 0.015f)
            if (!dark) assertColorsNear("浅色遮罩应使用冷灰色", Color(0xFF0F172A), scrim, 0.04f)
            assertColorsNear(
                "未选分类的 5% 透色底",
                chipTint.copy(alpha = 0.05f).compositeOver(pixels[panelX, optionY]),
                chipBase,
                tolerance = if (dark) 0.012f else 0.016f,
            )
            if (dark) {
                assertColorsNear("选中分类的 20% 雾蓝填充", Color(0xFF72A0FF).copy(alpha = 0.2f).compositeOver(panel), fill)
                val rimX = pixels.width / 3
                val rimY = (0 until sampleY).first { y -> pixels[rimX, y].alpha > 0.98f }
                assertColorsNear(
                    "顶边应为 6% 白色微光",
                    Color.White.copy(alpha = 0.06f).compositeOver(pixels[rimX, rimY + inset]),
                    pixels[rimX, rimY],
                    tolerance = 0.025f,
                )
                val edgeWidth = with(compose.density) { 2.dp.roundToPx() }.coerceAtLeast(1)
                val edge = (selectedLeft until selectedLeft + edgeWidth).maxOf { pixels[it, sampleY].blue }
                assertTrue("选中分类应含细蓝色描边，实测边线蓝通道 $edge、底色 $fill", edge > fill.blue + 0.08f)
            } else {
                assertColorsNear("浅色选中分类使用方案 B 的蓝色", Color(0xFF3567E8), fill)
            }
            val textInset = with(compose.density) { 10.dp.roundToPx() }
            val textTop = (selectedBounds.top - root.top).roundToInt() + inset
            val textBottom = (selectedBounds.bottom - root.top).roundToInt() - inset
            val selectedText = if (dark) Color(0xFFA8C5FF) else Color.White
            assertTrue("选中分类文字应在填充上清晰可见", (selectedLeft + textInset until selectedRight - textInset).any { x ->
                (textTop until textBottom).any { y -> colorDistance(pixels[x, y], selectedText) < 0.04f }
            })

            compose.runOnIdle { artwork.value = darkArtwork }
            val expectedAfter = if (dark) expectedDarkPanel(darkArtwork) else expectedLightPanel(darkArtwork)
            compose.waitUntil(conditionDescription = "玻璃随底页颜色变化", timeoutMillis = 5_000) {
                val changed = window.captureToImage().toPixelMap()[panelX, sampleY]
                colorDistance(expectedAfter, changed) < 0.025f && colorDistance(panel, changed) > 0.05f
            }
            option.performClick()
            compose.waitUntil(conditionDescription = "歌单分类已应用且抽屉已关闭", timeoutMillis = 5_000) {
                MeloraSettings.playlistTagId.value == category.id &&
                    compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
            }
            compose.awaitStable(filter)
            filter.performClick()
            compose.awaitStable(option)
            option.assertIsSelected()
            selected.assertIsNotSelected()
        } finally {
            MeloraSettings.updatePlaylistTag(previousId, previousName)
            if (previousTags == null) OnlineCache.clear(key) else OnlineCache.put(key, previousTags)
        }
    }

    private fun expectedDarkPanel(pageColor: Color): Color {
        val dimmed = Color.Black.copy(alpha = 0.20f).compositeOver(pageColor)
        return Color(0xFF0E1118).copy(alpha = 0.70f).compositeOver(dimmed)
    }

    private fun expectedLightPanel(pageColor: Color, tintAlpha: Float = 0.80f): Color {
        val dimmed = Color(0xFF0F172A).copy(alpha = 0.10f).compositeOver(pageColor)
        return Color(0xFFF6F7FA).copy(alpha = tintAlpha).compositeOver(dimmed)
    }

    private fun SemanticsNodeInteraction.captureToCache(filename: String): ImageBitmap {
        val screenshot = captureToImage()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(cache, filename).outputStream().use {
            screenshot.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return screenshot
    }

    private fun assertColorsNear(message: String, expected: Color, actual: Color, tolerance: Float = 0.035f) {
        assertTrue("$message 的预期颜色为 $expected，实测 $actual", colorDistance(expected, actual) < tolerance)
    }

    private fun colorDistance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
}
