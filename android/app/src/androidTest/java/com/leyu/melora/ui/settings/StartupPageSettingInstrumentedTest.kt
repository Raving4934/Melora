package com.leyu.melora.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.leyu.melora.startup.StartupPage
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Rule
import org.junit.Test

class StartupPageSettingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun hasRadioRole() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test fun menuShowsAllSixPagesAndPersistsTheChosenPage() {
        val selected = mutableStateOf(StartupPage.LocalSongs)
        compose.setContent {
            MeloraTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(350.dp)) { StartupPageSetting(selected.value) { selected.value = it } }
                }
            }
        }
        fun open() {
            compose.onNodeWithText("启动页面").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil { compose.onAllNodes(hasText("听书") and hasRadioRole()).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithText("听书").assertDoesNotExist()
        open()
        StartupPage.entries.forEach { page ->
            compose.onNode(hasText(page.label) and hasRadioRole()).assertIsDisplayed()
        }
        compose.onNode(hasText("本地歌曲") and isSelected()).assertIsSelected()
        compose.onNode(hasText("听书") and hasRadioRole()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil { selected.value == StartupPage.Audiobooks && compose.onAllNodes(hasRadioRole()).fetchSemanticsNodes().isEmpty() }
        open()
        compose.onNode(hasText("听书") and isSelected()).assertIsSelected()
    }
}
