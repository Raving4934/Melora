package com.leyu.melora.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.ui.common.MeloraAlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.ui.theme.AdaptiveDisplay
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class AdaptiveDisplayInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val windowSize = mutableStateOf(IntSize(3072, 1920))
    private val fontScale = mutableStateOf(1f)
    private val configurationWidthDp = mutableStateOf<Int?>(null)

    @Composable
    private fun DisplayHost(content: @Composable () -> Unit) {
        val actual = LocalWindowInfo.current
        val actualConfiguration = LocalConfiguration.current
        val widthDp = configurationWidthDp.value
        val configuration = remember(actualConfiguration, widthDp) {
            Configuration(actualConfiguration).apply {
                if (widthDp != null) screenWidthDp = widthDp
            }
        }
        val window = remember(actual) {
            object : WindowInfo by actual {
                override val containerSize: IntSize get() = windowSize.value
            }
        }
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalWindowInfo provides window,
            LocalDensity provides Density(225f / 160f, fontScale.value),
        ) {
            AdaptiveDisplay { MeloraTheme(content = content) }
        }
    }

    @Test fun controlsAndFontsUseTheSameScaleWhilePreservingSystemLargeText() {
        var observed: Density? = null
        compose.setContent {
            DisplayHost {
                val density = LocalDensity.current
                SideEffect { observed = density }
                Box(Modifier.size(100.dp).testTag("scaled-control"))
            }
        }
        compose.waitForIdle()
        assertEquals(240f, compose.onNodeWithTag("scaled-control").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(2.4f, requireNotNull(observed).density, 0.0001f)
        compose.runOnIdle { fontScale.value = 1.6f }
        compose.waitForIdle()
        val scaled = requireNotNull(observed)
        val system = Density(225f / 160f, 1.6f)
        assertEquals(1.6f, scaled.fontScale, 0f)
        // 保留 Android 非线性字号换算，而不是自己拿 sp * fontScale 近似。
        assertEquals(with(system) { 32.sp.toDp().value }, with(scaled) { 32.sp.toDp().value }, 0.001f)
        assertEquals(240f, compose.onNodeWithTag("scaled-control").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun rotationAndSplitWindowReflowDoNotReplaceRememberedContent() {
        var identity: Any? = null
        var observed: Density? = null
        compose.setContent {
            DisplayHost {
                val retained = remember { Any() }
                val density = LocalDensity.current
                SideEffect { identity = retained; observed = density }
                Box(Modifier.size(100.dp).testTag("scaled-control"))
            }
        }
        compose.waitForIdle()
        val original = identity
        compose.runOnIdle { windowSize.value = IntSize(1920, 3072) }
        compose.waitForIdle()
        assertSame(original, identity)
        assertEquals(2.4f, requireNotNull(observed).density, 0.0001f)
        compose.runOnIdle { windowSize.value = IntSize(1000, 1920) }
        compose.waitForIdle()
        assertSame(original, identity)
        assertEquals(225f / 160f, requireNotNull(observed).density, 0f)
        assertEquals(141f, compose.onNodeWithTag("scaled-control").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun dialogsAndSheetsInheritTheRootScaleWithoutScalingAgain() {
        val showSheet = mutableStateOf(false)
        var dialogDensity: Density? = null
        var sheetDensity: Density? = null
        compose.setContent {
            DisplayHost {
                if (!showSheet.value) {
                    MeloraAlertDialog(
                        onDismissRequest = {}, title = {}, confirmButton = {}, dismissButton = {},
                        containerColor = MeloraAppearance.canvas, text = {
                        val density = LocalDensity.current
                        SideEffect { dialogDensity = density }
                        Box(Modifier.size(100.dp).testTag("dialog-control"))
                    })
                } else {
                    MeloraBottomSheet(
                        onDismissRequest = {},
                        sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = MeloraAppearance.canvas,
                    ) {
                        val density = LocalDensity.current
                        SideEffect { sheetDensity = density }
                        Box(Modifier.size(100.dp).testTag("sheet-control"))
                    }
                }
            }
        }
        compose.awaitStable("dialog-control")
        assertEquals(2.4f, dialogDensity!!.density, 0.0001f)
        assertEquals(240f, compose.onNodeWithTag("dialog-control").fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.runOnIdle { showSheet.value = true }
        compose.awaitStable("sheet-control")
        assertEquals(2.4f, sheetDensity!!.density, 0.0001f)
        assertEquals(240f, compose.onNodeWithTag("sheet-control").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun resizingAnOpenSheetPreservesItsBodyAndUpdatesBodyAndHandleDensity() {
        var bodyIdentity: Any? = null
        var windowView: Any? = null
        var bodyDensity: Density? = null
        var handleDensity: Density? = null
        compose.setContent {
            DisplayHost {
                MeloraBottomSheet(
                    onDismissRequest = {},
                    sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MeloraAppearance.canvas,
                    dragHandle = {
                        val density = LocalDensity.current
                        SideEffect { handleDensity = density }
                        Box(Modifier.size(100.dp, 48.dp).testTag("retained-sheet-handle"))
                    },
                ) {
                    val identity = remember { Any() }
                    val density = LocalDensity.current
                    val view = LocalView.current
                    SideEffect { bodyIdentity = identity; bodyDensity = density; windowView = view }
                    Box(Modifier.size(100.dp).testTag("retained-sheet-body"))
                }
            }
        }
        compose.awaitStable("retained-sheet-body")
        val original = requireNotNull(bodyIdentity)
        val originalWindow = requireNotNull(windowView)
        assertEquals(2.4f, requireNotNull(bodyDensity).density, 0.0001f)
        assertEquals(2.4f, requireNotNull(handleDensity).density, 0.0001f)

        // 模拟真实分屏的配置更新：仅宽度变化，系统 density/fontScale 与应用倍率不变。
        compose.runOnIdle { configurationWidthDp.value = 777 }
        compose.awaitStable("retained-sheet-body")
        assertSame(original, bodyIdentity)
        assertSame(originalWindow, windowView)
        assertEquals(2.4f, requireNotNull(bodyDensity).density, 0.0001f)

        compose.runOnIdle { windowSize.value = IntSize(1536, 1920) }
        compose.awaitStable("retained-sheet-body")
        assertSame(original, bodyIdentity)
        assertSame(originalWindow, windowView)
        assertEquals(1.92f, requireNotNull(bodyDensity).density, 0.0001f)
        assertEquals(1.92f, requireNotNull(handleDensity).density, 0.0001f)
        assertEquals(192f, compose.onNodeWithTag("retained-sheet-body").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(192f, compose.onNodeWithTag("retained-sheet-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width, 1f)

        compose.runOnIdle { fontScale.value = 1.6f }
        compose.awaitStable("retained-sheet-body")
        assertSame(original, bodyIdentity)
        assertEquals(1.6f, requireNotNull(bodyDensity).fontScale, 0f)
        assertEquals(1.6f, requireNotNull(handleDensity).fontScale, 0f)
    }

    @Test fun sheetDragThresholdUsesCurrentDensityWithoutReplacingState() {
        var sheetState: SheetState? = null
        var touchSlop = 0f
        compose.setContent {
            DisplayHost {
                val state = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
                SideEffect { sheetState = state }
                MeloraBottomSheet(
                    onDismissRequest = {},
                    sheetState = state,
                    containerColor = MeloraAppearance.canvas,
                    dragHandle = {
                        val slop = LocalViewConfiguration.current.touchSlop
                        SideEffect { touchSlop = slop }
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("threshold-sheet-handle"))
                    },
                ) {
                    Box(Modifier.fillMaxWidth().height(240.dp).testTag("threshold-sheet-body"))
                }
            }
        }
        compose.awaitStable("threshold-sheet-body")
        val original = requireNotNull(sheetState)
        compose.runOnIdle { windowSize.value = IntSize(1000, 1920) }
        compose.awaitStable("threshold-sheet-body")
        assertSame(original, sheetState)
        val initialOffset = compose.runOnIdle { original.requireOffset() }
        // Material 将把手合并到可点击语义节点；触摸实际把手必须查未合并树。
        val handle = compose.onNodeWithTag("threshold-sheet-handle", useUnmergedTree = true)
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, touchSlop + 100f), delayMillis = 1_000)
        }
        try {
            compose.runOnIdle {
                // 100px 介于新阈值 78.75px 与旧阈值 134.4px 之间；尚未松手，不受抛速影响。
                assertEquals(100f, original.requireOffset() - initialOffset, 1f)
                assertEquals(SheetValue.Hidden, original.targetValue)
            }
        } finally {
            handle.performTouchInput { cancel() }
        }
    }

    @Test fun dialogExposesALocalizedPaneTitle() {
        var expectedPaneTitle = ""
        compose.setContent {
            DisplayHost {
                val paneTitle = LocalContext.current.getString(android.R.string.dialog_alert_title)
                SideEffect { expectedPaneTitle = paneTitle }
                MeloraAlertDialog(
                    onDismissRequest = {}, title = {}, confirmButton = {}, dismissButton = {},
                    containerColor = MeloraAppearance.canvas,
                    text = { Box(Modifier.size(100.dp).testTag("pane-dialog-body")) },
                )
            }
        }
        compose.awaitStable("pane-dialog-body")
        assertTrue(expectedPaneTitle.isNotBlank())
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, expectedPaneTitle))
            .assertExists()
    }

    @Test fun dialogActionsStayCompactAndConfirmComesFirstWhenWrapping() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MeloraTheme {
                    MeloraAlertDialog(
                        onDismissRequest = {}, title = {}, text = {},
                        containerColor = MeloraAppearance.canvas,
                        confirmButton = { Box(Modifier.size(300.dp, 48.dp).testTag("confirm")) },
                        dismissButton = { Box(Modifier.size(300.dp, 48.dp).testTag("dismiss")) },
                    )
                }
            }
        }
        compose.awaitStable("confirm")
        val confirm = compose.onNodeWithTag("confirm").fetchSemanticsNode().boundsInRoot
        val dismiss = compose.onNodeWithTag("dismiss").fetchSemanticsNode().boundsInRoot
        assertTrue(confirm.bottom <= dismiss.top)
        assertEquals(12f, dismiss.top - confirm.bottom, 1f)
        assertEquals(confirm.right, dismiss.right, 1f)
    }
}
