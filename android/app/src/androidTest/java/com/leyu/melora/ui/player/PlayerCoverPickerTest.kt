package com.leyu.melora.ui.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.playback.UiTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerCoverPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun optionsExposeSelectionAndKeepCallbacksIndependent() {
        val selected = mutableStateOf(PlayerCoverStyle.Circle)
        val visible = mutableStateOf(true)
        val selections = mutableStateListOf<PlayerCoverStyle>()
        val dismissCount = mutableIntStateOf(0)
        val immersiveCount = mutableIntStateOf(0)
        compose.setContent {
            if (visible.value) PlayerAppearanceProvider(dark = true) {
                PlayerCoverPicker(
                    selected = selected.value,
                    track = UiTrack("cover-test-track", "歌曲", "歌手", "专辑", artwork = null),
                    immersive = false,
                    motionEnabled = false,
                    themeMode = ThemeMode.Auto,
                    onThemeModeChange = {},
                    onSelect = { selections.add(it); selected.value = it },
                    onToggleImmersive = { immersiveCount.intValue++ },
                    onDismiss = { dismissCount.intValue++; visible.value = false },
                )
            }
        }

        val options = listOf(
            PlayerCoverStyle.Default to "player-cover-default",
            PlayerCoverStyle.Circle to "player-cover-circle",
            PlayerCoverStyle.Vinyl to "player-cover-vinyl",
        )
        compose.onNodeWithTag("player-cover-default").assertIsNotSelected()
        compose.onNodeWithTag("player-cover-circle").assertIsSelected()
        compose.onNodeWithTag("player-cover-vinyl").assertIsNotSelected()
        options.forEach { (style, tag) ->
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertEquals(style, selected.value)
                assertEquals(style, selections.last())
                assertEquals(0, dismissCount.intValue)
                assertEquals(0, immersiveCount.intValue)
            }
            compose.onNodeWithTag(tag).assertIsSelected()
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(options.map { it.first }, selections.toList()) }
        compose.onNodeWithTag("player-cover-immersive").performClick()
        compose.runOnIdle {
            assertEquals(3, selections.size)
            assertEquals(1, immersiveCount.intValue)
            assertEquals(0, dismissCount.intValue)
        }
        compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
        compose.onNodeWithTag("player-cover-cancel").performClick()
        compose.runOnIdle {
            assertEquals(3, selections.size)
            assertEquals(1, immersiveCount.intValue)
            assertEquals(1, dismissCount.intValue)
        }
        compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
    }

    @Test
    fun themeOptionsUpdateSelectionWithoutChangingCoverImmersiveOrDismissal() {
        val selected = mutableStateOf(PlayerCoverStyle.Circle)
        val selections = mutableStateListOf<PlayerCoverStyle>()
        val themeMode = mutableStateOf(ThemeMode.Auto)
        val themeChanges = mutableStateListOf<ThemeMode>()
        val immersive = mutableStateOf(true)
        val immersiveToggleCount = mutableIntStateOf(0)
        val dismissCount = mutableIntStateOf(0)
        val pickerVisible = mutableStateOf(true)
        compose.setContent {
            if (pickerVisible.value) PlayerAppearanceProvider(dark = true) {
                PlayerCoverPicker(
                    selected = selected.value,
                    track = UiTrack("cover-theme-test-track", "歌曲", "歌手", "专辑", artwork = null),
                    immersive = immersive.value,
                    motionEnabled = false,
                    themeMode = themeMode.value,
                    onThemeModeChange = {
                        themeChanges.add(it)
                        themeMode.value = it
                    },
                    onSelect = { selections.add(it); selected.value = it },
                    onToggleImmersive = {
                        immersiveToggleCount.intValue++
                        immersive.value = !immersive.value
                    },
                    onDismiss = { dismissCount.intValue++; pickerVisible.value = false },
                )
            }
        }

        val options = listOf(
            ThemeMode.Auto to "player-cover-theme-auto",
            ThemeMode.Light to "player-cover-theme-light",
            ThemeMode.Dark to "player-cover-theme-dark",
        )
        options.forEach { (mode, tag) -> compose.onNodeWithTag(tag).assertIsDisplayed() }
        compose.onNodeWithTag("player-cover-theme-auto").assertIsSelected()
        compose.onNodeWithTag("player-cover-theme-light").assertIsNotSelected()
        compose.onNodeWithTag("player-cover-theme-dark").assertIsNotSelected()

        // Exercise a real transition to each mode, including returning to the initial Auto mode.
        listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.Auto).forEach { mode ->
            val tag = "player-cover-theme-${mode.storageValue}"
            compose.onNodeWithTag(tag).performClick()
            compose.runOnIdle {
                assertEquals(mode, themeMode.value)
                assertEquals(mode, themeChanges.last())
                assertEquals(PlayerCoverStyle.Circle, selected.value)
                assertTrue(selections.isEmpty())
                assertTrue(immersive.value)
                assertEquals(0, immersiveToggleCount.intValue)
                assertEquals(0, dismissCount.intValue)
                assertTrue(pickerVisible.value)
            }
            options.forEach { (option, optionTag) ->
                val node = compose.onNodeWithTag(optionTag)
                if (option == mode) node.assertIsSelected() else node.assertIsNotSelected()
            }
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.Auto), themeChanges.toList()) }

        // A fast upward fling must not dismiss the sheet or dispatch unrelated picker callbacks.
        compose.onNodeWithTag("player-cover-picker").performTouchInput { swipeUp(durationMillis = 100) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(PlayerCoverStyle.Circle, selected.value)
            assertTrue(selections.isEmpty())
            assertEquals(ThemeMode.Auto, themeMode.value)
            assertEquals(listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.Auto), themeChanges.toList())
            assertTrue(immersive.value)
            assertEquals(0, immersiveToggleCount.intValue)
            assertEquals(0, dismissCount.intValue)
            assertTrue(pickerVisible.value)
        }
        compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
    }

    @Test
    fun textColorsFollowPlayerTokensAcrossLightDarkAndArtworkToneChanges() {
        val dark = mutableStateOf(false)
        val artworkTone = mutableStateOf(Color(0xFF3679D2))
        val themeMode = mutableStateOf(ThemeMode.Auto)
        var currentColors: PlayerColors? = null

        compose.setContent {
            PlayerAppearanceProvider(dark = dark.value, artworkColor = artworkTone.value) {
                val colors = LocalPlayerColors.current
                SideEffect { currentColors = colors }
                PlayerCoverPicker(
                    selected = PlayerCoverStyle.Circle,
                    track = UiTrack("cover-colors-test-track", "歌曲", "歌手", "专辑", artwork = null),
                    immersive = false,
                    motionEnabled = false,
                    themeMode = themeMode.value,
                    onThemeModeChange = { themeMode.value = it },
                    onSelect = {},
                    onToggleImmersive = {},
                    onDismiss = {},
                )
            }
        }

        val tokenCases = listOf(
            false to Color(0xFF3679D2),
            true to Color(0xFF3679D2),
            true to Color(0xFFD45A31),
            false to Color(0xFFD45A31),
        )
        var previousColors: PlayerColors? = null
        tokenCases.forEach { (isDark, tone) ->
            compose.runOnIdle {
                dark.value = isDark
                artworkTone.value = tone
            }
            compose.waitForIdle()

            lateinit var renderedColors: PlayerColors
            compose.runOnIdle { renderedColors = checkNotNull(currentColors) }
            assertEquals(isDark, renderedColors.isDark)
            if (previousColors != null) {
                assertTrue(previousColors.textPrimary != renderedColors.textPrimary)
            }
            assertPickerTextColors(renderedColors.textPrimary, renderedColors.textSecondary, ThemeMode.Auto)
            assertPickerCardsDisplayed()
            previousColors = renderedColors
        }

        compose.onNodeWithTag("player-cover-theme-light").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.Light, themeMode.value) }
        lateinit var renderedColors: PlayerColors
        compose.runOnIdle { renderedColors = checkNotNull(currentColors) }
        assertPickerTextColors(renderedColors.textPrimary, renderedColors.textSecondary, ThemeMode.Light)
        assertPickerCardsDisplayed()
    }

    private fun assertPickerTextColors(primary: Color, secondary: Color, selectedMode: ThemeMode) {
        assertTextColor("封面类型", primary)
        assertTextColor("进入沉浸模式", primary)
        assertTextColor("滑动聚焦 · 点击应用", secondary)
        assertTextColor("主题模式", secondary)
        assertTextColor("取消", secondary)

        val modeLabels = listOf(
            ThemeMode.Auto to "跟随系统",
            ThemeMode.Light to "浅色",
            ThemeMode.Dark to "深色",
        )
        modeLabels.forEach { (mode, label) ->
            assertTextColor(label, if (mode == selectedMode) primary else secondary)
        }
    }

    private fun assertTextColor(text: String, expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Expected one text layout for '$text'", 1, layouts.size)
        assertEquals("Unexpected text color for '$text'", expected, layouts.single().layoutInput.style.color)
    }

    private fun assertPickerCardsDisplayed() {
        compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
        compose.onNodeWithTag("player-cover-cards").assertIsDisplayed()
        listOf("player-cover-default", "player-cover-circle", "player-cover-vinyl")
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
    }

    @Test
    fun coverCardsStayInOneRowAndSwipeDoesNotApplySelection() {
        val initial = PlayerCoverStyle.Default
        val selected = mutableStateOf(initial)
        val selections = mutableStateListOf<PlayerCoverStyle>()
        compose.setContent {
            PlayerAppearanceProvider(dark = true) {
                PlayerCoverPicker(
                    selected = selected.value,
                    track = UiTrack("cover-test-track", "歌曲", "歌手", "专辑", artwork = null),
                    immersive = false,
                    motionEnabled = false,
                    themeMode = ThemeMode.Auto,
                    onThemeModeChange = {},
                    onSelect = { selections.add(it); selected.value = it },
                    onToggleImmersive = {},
                    onDismiss = {},
                )
            }
        }

        val tags = listOf("player-cover-default", "player-cover-circle", "player-cover-vinyl")
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(3, bounds.size)
        assertTrue(bounds.zipWithNext().all { (left, right) -> right.center.x > left.center.x })
        assertTrue((bounds.maxOf { it.center.y } - bounds.minOf { it.center.y }) < with(compose.density) { 48.dp.toPx() })
        val deck = compose.onNodeWithTag("player-cover-cards").fetchSemanticsNode().boundsInRoot
        if (deck.width <= with(compose.density) { 400.dp.toPx() }) {
            assertTrue(bounds.all { it.height >= it.width * 1.2f })
        }

        compose.onNodeWithTag("player-cover-cards").performTouchInput {
            swipeLeft()
            swipeRight()
        }
        compose.runOnIdle {
            assertEquals(initial, selected.value)
            assertEquals(0, selections.size)
        }
        compose.onNodeWithTag("player-cover-default").assertIsSelected()

        compose.onNodeWithTag("player-cover-vinyl").performClick()
        compose.runOnIdle {
            assertEquals(PlayerCoverStyle.Vinyl, selected.value)
            assertEquals(listOf(PlayerCoverStyle.Vinyl), selections.toList())
        }
    }
}
