package com.leyu.melora.ui.search

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.SourceAlias
import com.leyu.melora.ui.theme.MeloraTheme
import java.util.concurrent.atomic.AtomicReference
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

    private fun platformOption(platform: PlatformSource) =
        compose.onNode(hasAnyAncestor(isPopup()) and hasText(SourceAlias.displayMusic(platform.id, platform.label)) and hasClickAction())
}
