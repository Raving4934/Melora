package com.leyu.melora.ui.settings

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
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsContentWidthInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val page = mutableStateOf<SettingsSubPage>(SettingsSubPage.None)
    private val widthDp = mutableStateOf(1280)
    private var density = 1f
    private val pages = listOf(
        SettingsSubPage.None,
        SettingsSubPage.Basic,
        SettingsSubPage.CustomSource,
        SettingsSubPage.Playback,
        SettingsSubPage.Download,
        SettingsSubPage.DesktopLyrics,
        SettingsSubPage.Others,
        SettingsSubPage.About,
        SettingsSubPage.LocalMusic,
    )

    private fun showSettings() {
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 用真实像素构造逻辑窗口，手机模拟器也能验证 1280×800dp 的布局。
                val logicalDensity = minOf(
                    constraints.maxWidth / widthDp.value.toFloat(),
                    constraints.maxHeight / 800f,
                )
                val fontScale = LocalDensity.current.fontScale
                density = logicalDensity
                CompositionLocalProvider(LocalDensity provides Density(logicalDensity, fontScale)) {
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
        val viewport = compose.onNodeWithTag("settings-test-viewport").fetchSemanticsNode().boundsInRoot
        val body = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
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

        val navigation = compose.onNodeWithContentDescription(
            if (subPage == SettingsSubPage.None) "打开侧栏" else "返回",
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "${subPage.title}顶栏导航应留在全宽外壳左侧",
            navigation.center.x - viewport.left < 64f * density,
        )
    }

    @Test
    fun allSettingsPagesFillTheContentPaneWithConsistentGutters() {
        showSettings()
        pages.forEach { subPage ->
            compose.runOnIdle { page.value = subPage }
            compose.waitForIdle()
            assertGeometry(subPage)
        }
    }

    @Test
    fun allSettingsPagesKeepTheirPhoneWidthAndExistingGutters() {
        widthDp.value = 390
        showSettings()
        pages.forEach { subPage ->
            compose.runOnIdle { page.value = subPage }
            compose.waitForIdle()
            assertGeometry(subPage)
        }
    }

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
}
