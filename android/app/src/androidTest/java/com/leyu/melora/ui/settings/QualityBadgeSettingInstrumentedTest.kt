package com.leyu.melora.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.QualityBadgeMode
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Rule
import org.junit.Test

class QualityBadgeSettingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun basicSettingsOffersAllModesAndShowsTheCurrentSelection() {
        val original = MeloraSettings.qualityBadgeMode.value
        val initial = QualityBadgeMode.Compact
        try {
            MeloraSettings.updateQualityBadgeMode(initial)
            compose.setContent { MeloraTheme { BasicSettingsSubPage(onBack = {}) } }

            val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
            val modes = QualityBadgeMode.entries
            val modeLabels = modes.map { hasText(it.label) }.reduce { left, right -> left or right }
            val badgeOptions = radio and modeLabels
            val title = "音质徽标样式"
            val setting = compose.onNodeWithText(title)
            var selected = initial
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.awaitStable(setting)
            val selectionOrder = modes.filterNot { it == initial } + initial
            selectionOrder.forEach { mode ->
                // 菜单有独立的弹出窗口，发送下一次触摸前等它交还或获得焦点。
                compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
                setting.performClick()
                compose.waitUntil(5_000) { !compose.activity.hasWindowFocus() }
                modes.forEach { compose.awaitStable(compose.onNode(hasText(it.label) and radio)) }
                compose.onNode(hasText("文字") and radio).assertDoesNotExist()
                compose.onNode(hasText(selected.label) and radio).assertIsSelected()
                compose.onNode(hasText(mode.label) and radio).performClick()
                compose.waitUntil(5_000) {
                    MeloraSettings.qualityBadgeMode.value == mode &&
                        compose.onAllNodes(badgeOptions).fetchSemanticsNodes().isEmpty()
                }
                compose.awaitStable(compose.onNode(hasText(title) and hasText(mode.label)))
                selected = mode
            }
        } finally {
            MeloraSettings.updateQualityBadgeMode(original)
        }
    }
}
