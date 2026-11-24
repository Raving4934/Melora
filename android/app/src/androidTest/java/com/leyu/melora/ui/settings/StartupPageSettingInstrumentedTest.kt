package com.leyu.melora.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import androidx.test.espresso.Espresso.pressBack
import com.leyu.melora.startup.StartupPage
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StartupPageSettingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun hasRadioRole() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test fun menuShowsAllStartupPagesAndKeepsDiscoverSelected() {
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
            compose.waitUntil { compose.onAllNodes(hasText("发现") and hasRadioRole()).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithText("发现").assertDoesNotExist()
        open()
        StartupPage.entries.forEach { page ->
            compose.onNode(hasText(page.label) and hasRadioRole()).assertIsDisplayed()
        }
        compose.onNode(hasText("本地歌曲") and isSelected()).assertIsSelected()
        compose.onNode(hasText("发现") and hasRadioRole()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil { selected.value == StartupPage.Discover && compose.onAllNodes(hasRadioRole()).fetchSemanticsNodes().isEmpty() }
        open()
        compose.onNode(hasText("发现") and isSelected()).assertIsSelected()
    }

    @Test fun menuOpensOnTheAvailableSideAndBackDismissesIt() {
        val atTop = mutableStateOf(true)
        compose.setContent {
            MeloraTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding(),
                    contentAlignment = if (atTop.value) Alignment.TopEnd else Alignment.BottomEnd) {
                    StartupPageSetting(StartupPage.Search) {}
                }
            }
        }
        fun verticalBounds(node: SemanticsNodeInteraction): Pair<Float, Float> {
            val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
            val top = coordinates.positionOnScreen().y
            return top to top + coordinates.size.height
        }
        val row = compose.onNode(hasText("启动页面") and hasText(StartupPage.Search.label))
        val pane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "启动页面")
        for (opensBelow in listOf(true, false)) {
            compose.runOnIdle { atTop.value = opensBelow }
            val (rowTop, rowBottom) = verticalBounds(row)
            row.performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil { compose.onAllNodes(pane).fetchSemanticsNodes().isNotEmpty() }
            val (menuTop, menuBottom) = verticalBounds(compose.onNode(pane))
            if (opensBelow) assertTrue("顶部设置项应向下展开", menuTop >= rowBottom)
            else assertTrue("底部设置项应向上展开", menuBottom <= rowTop)
            pressBack()
            compose.waitUntil { compose.onAllNodes(pane).fetchSemanticsNodes().isEmpty() }
        }
    }
}
