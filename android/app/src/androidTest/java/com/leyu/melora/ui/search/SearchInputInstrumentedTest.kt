package com.leyu.melora.ui.search

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.theme.MeloraTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchInputInstrumentedTest {
    @get:Rule val compose = createComposeRule()

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
}
