package com.leyu.melora.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsContentWidthInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val page = mutableStateOf<SettingsSubPage>(SettingsSubPage.None)
    private val widthDp = mutableStateOf(1280)
    private var density = 1f
    private fun showSettings(fontScale: Float? = null) {
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 用真实像素构造逻辑窗口，手机模拟器也能验证 1280×800dp 的布局。
                val logicalDensity = minOf(
                    constraints.maxWidth / widthDp.value.toFloat(),
                    constraints.maxHeight / 800f,
                )
                val logicalFontScale = fontScale ?: LocalDensity.current.fontScale
                density = logicalDensity
                CompositionLocalProvider(LocalDensity provides Density(logicalDensity, logicalFontScale)) {
                    MeloraTheme {
                        Box(
                            Modifier.requiredSize(widthDp.value.dp, 800.dp)
                                .consumeWindowInsets(WindowInsets.safeDrawing)
                                .testTag("settings-test-viewport"),
                        ) {
                            SettingsPageContent(
                                subPage = page.value,
                                onOpenDrawer = {},
                                onNavigate = { page.value = it },
                                onBack = { page.value = SettingsSubPage.None },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertGeometry(subPage: SettingsSubPage) {
        val scroll = compose.onNode(hasScrollAction())
        val nav = compose.onNodeWithContentDescription(if (subPage == SettingsSubPage.None) "打开侧栏" else "返回")
        compose.awaitStable(scroll)
        compose.awaitStable(nav)
        val (viewport, body, navigation) = compose.boundsInSameFrame(compose.onNodeWithTag("settings-test-viewport"), scroll, nav)
        // 自定义源和关于页将 16dp 留白放在 contentPadding，其余页面放在列表 Modifier。
        val outerGutters = when (subPage) {
            SettingsSubPage.CustomSource, SettingsSubPage.About -> 0
            else -> 32
        }
        val expectedBodyWidth = (widthDp.value - outerGutters) * density
        assertEquals("${subPage.title}正文宽度", expectedBodyWidth, body.width, 2f)
        assertEquals("${subPage.title}正文与原顶栏同起点", viewport.left + outerGutters / 2f * density, body.left, 1f)
        assertEquals("${subPage.title}右侧只保留统一边距，不能单独限宽留白", viewport.right - outerGutters / 2f * density, body.right, 1f)
        assertEquals("${subPage.title}滚动区域顶部不变", viewport.top, body.top, 1f)

        // 这两页把留白放在 contentPadding：仅检查滚动容器会漏掉卡片贴边或限宽回归。
        val cardLabel = when (subPage) {
            SettingsSubPage.CustomSource -> "自动换源"
            SettingsSubPage.About -> "检测更新"
            else -> null
        }
        if (cardLabel != null) {
            val card = requireNotNull(compose.onNodeWithText(cardLabel).fetchSemanticsNode().parent)
                .boundsInRoot
            assertEquals("${subPage.title}卡片左侧16dp边距", viewport.left + 16f * density, card.left, 1f)
            assertEquals("${subPage.title}卡片右侧16dp边距", viewport.right - 16f * density, card.right, 1f)
        }

        assertTrue(
            "${subPage.title}顶栏导航应留在全宽外壳左侧",
            navigation.center.x - viewport.left < 64f * density,
        )
    }

    private fun checkAllPages(width: Int) {
        widthDp.value = width
        showSettings()
        SettingsSubPage.entries.forEach { subPage ->
            compose.runOnIdle { page.value = subPage }
            assertGeometry(subPage)
        }
    }

    @Test fun allSettingsPagesFillTheContentPaneWithConsistentGutters() = checkAllPages(1280)

    @Test fun allSettingsPagesKeepTheirPhoneWidthAndExistingGutters() = checkAllPages(390)

    @Test
    fun resizingKeepsSettingsNavigationWorking() {
        widthDp.value = 390
        showSettings()
        compose.onNodeWithText("基本设置").performClick()
        compose.waitForIdle()
        assertGeometry(SettingsSubPage.Basic)

        compose.runOnIdle { widthDp.value = 1280 }
        compose.waitForIdle()
        assertGeometry(SettingsSubPage.Basic)

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertGeometry(SettingsSubPage.None)
    }

    @Test
    fun narrowSettingsOfferIndependentMasterQualityPreferences() {
        val previousWifi = MeloraSettings.playQualityWifi.value
        val previousMobile = MeloraSettings.playQualityMobile.value
        val previousDownload = MeloraSettings.downloadQuality.value
        try {
            MeloraSettings.updatePlayQualityWifi("320k")
            MeloraSettings.updatePlayQualityMobile("128k")
            MeloraSettings.updateDownloadQuality("flac24bit")
            widthDp.value = 320
            page.value = SettingsSubPage.Playback
            showSettings(fontScale = 1.5f)

            val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
            fun chooseMaster(setting: String, currentLabel: String) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(setting))
                val row = compose.onNodeWithText(setting)
                compose.awaitStable(row)
                compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
                row.performClick()
                compose.waitUntil(5_000) { !compose.activity.hasWindowFocus() }
                for (badge in listOf("SD", "HQ", "SQ", "HR", "母带")) {
                    compose.onNodeWithText(badge, useUnmergedTree = true).assertExists()
                }
                val current = compose.onNode(radio and hasText(currentLabel))
                current.performScrollTo().assertIsSelected()
                val master = compose.onNode(radio and hasText("母带优先"))
                compose.awaitStable(master.performScrollTo())
                master.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(5_000) { compose.activity.hasWindowFocus() && compose.onAllNodes(radio).fetchSemanticsNodes().isEmpty() }
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(setting))
                compose.onNode(hasText(setting) and hasText("母带优先")).assertIsDisplayed()
            }

            compose.onNode(hasScrollAction()).performScrollToNode(hasText("WIFI/以太网首选音质，不可用时自动降级"))
            chooseMaster("WiFi 网络音质", "320k 高品")
            assertEquals("master", MeloraSettings.playQualityWifi.value)
            assertEquals("128k", MeloraSettings.playQualityMobile.value)
            assertEquals("flac24bit", MeloraSettings.downloadQuality.value)

            chooseMaster("移动网络音质", "128k 标清")
            assertEquals("master", MeloraSettings.playQualityMobile.value)
            assertEquals("flac24bit", MeloraSettings.downloadQuality.value)

            compose.runOnIdle { page.value = SettingsSubPage.Download }
            chooseMaster("下载音质", "Hi-Res 24bit")
            assertEquals("master", MeloraSettings.downloadQuality.value)
        } finally {
            MeloraSettings.updatePlayQualityWifi(previousWifi)
            MeloraSettings.updatePlayQualityMobile(previousMobile)
            MeloraSettings.updateDownloadQuality(previousDownload)
        }
    }
}
