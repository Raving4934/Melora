package com.leyu.melora.ui.my

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.awaitIme
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistEditSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createHasOneClickableImportOnTheRightOfTheTitle() {
        var imports = 0
        var confirmations = 0
        var dismissals = 0
        val importing = mutableStateOf(false)
        compose.setContent {
            MeloraTheme {
                if (importing.value) PlaylistImportSheet(onDismiss = {}, onImported = { confirmations++ })
                else PlaylistEditSheet(onImport = { imports++; importing.value = true }, onDismiss = { dismissals++ },
                    onConfirm = { confirmations++ })
            }
        }
        val entry = compose.onNodeWithText("从链接导入")
        compose.awaitIme(entry)
        compose.awaitStable(entry)
        compose.onAllNodesWithText("从链接导入").assertCountEquals(1)
        compose.onNodeWithText("从链接导入歌单").assertDoesNotExist()
        compose.onNodeWithText("为喜欢的音乐留一个位置").assertIsDisplayed().assert(hasNoClickAction())
        entry.assertHasClickAction().assertTouchHeightIsEqualTo(48.dp)
        val (title, action, field) = compose.boundsInSameFrame(
            compose.onNodeWithText("新建歌单"), entry, compose.onNode(hasSetTextAction()),
        )
        assertTrue("入口应位于标题右侧、名称输入框之上", action.left >= title.right && action.bottom <= field.top)
        assertEquals("入口与标题应在同一行居中对齐", title.center.y, action.center.y, 1f)
        val screenshot = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "playlist-create-title.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        entry.performClick()
        compose.awaitStable("playlist-import-sheet")
        compose.onNodeWithText("导入歌单").assertIsDisplayed()
        compose.onNodeWithText("新建歌单").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, imports)
            assertEquals(0, confirmations)
            assertEquals(0, dismissals)
        }
    }

    @Test fun createRequestsFocusOnlyAfterTheSheetFinishesExpanding() {
        val wasAutoAdvance = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent {
                MeloraTheme {
                    PlaylistEditSheet(onDismiss = {}, onConfirm = {})
                }
            }

            val title = compose.onNodeWithText("新建歌单")
            val entry = compose.onNode(hasSetTextAction())
            compose.waitForIdle()
            val initialTop = title.fetchSemanticsNode().boundsInRoot.top
            var observedEntranceMotion = false
            var advancedFrames = 0
            while (advancedFrames < 12 && !observedEntranceMotion) {
                compose.mainClock.advanceTimeByFrame()
                observedEntranceMotion = title.fetchSemanticsNode().boundsInRoot.top != initialTop
                advancedFrames++
            }
            assertTrue("应采到抽屉入场中的中间帧", observedEntranceMotion)
            entry.assertIsNotFocused()

            compose.mainClock.advanceTimeBy(1_000)
            compose.mainClock.autoAdvance = true
            compose.awaitIme(entry)
            entry.assertIsFocused()
        } finally {
            compose.mainClock.autoAdvance = wasAutoAdvance
        }
    }

    @Test fun closingDuringSheetEntryCancelsPendingFocus() {
        val open = mutableStateOf(false)
        val wasAutoAdvance = compose.mainClock.autoAdvance
        try {
            compose.setContent {
                MeloraTheme {
                    if (open.value) {
                        PlaylistEditSheet(onDismiss = { open.value = false }, onConfirm = {})
                    }
                }
            }
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { open.value = true }
            compose.mainClock.advanceTimeByFrame()

            val title = compose.onNodeWithText("新建歌单")
            val entry = compose.onNode(hasSetTextAction())
            val sheetView = (entry.fetchSemanticsNode().root as ViewRootForTest).view
            val initialTop = title.fetchSemanticsNode().boundsInRoot.top
            var observedEntranceMotion = false
            var advancedFrames = 0
            while (advancedFrames < 12 && !observedEntranceMotion) {
                compose.mainClock.advanceTimeByFrame()
                observedEntranceMotion = title.fetchSemanticsNode().boundsInRoot.top != initialTop
                advancedFrames++
            }
            assertTrue("关闭前抽屉应已开始进入动画", observedEntranceMotion)
            entry.assertIsNotFocused()

            compose.runOnIdle { open.value = false }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()

            entry.assertDoesNotExist()
            assertFalse(
                "关闭后取消的自动聚焦不能再弹出键盘",
                ViewCompat.getRootWindowInsets(sheetView)?.isVisible(WindowInsetsCompat.Type.ime()) == true,
            )
        } finally {
            compose.mainClock.autoAdvance = wasAutoAdvance
        }
    }

    @Test fun renameKeepsPlainSubtitleAndNeverOffersImport() {
        var imports = 0
        var confirmed: String? = null
        compose.setContent {
            MeloraTheme {
                PlaylistEditSheet(initialName = "原歌单", isRename = true, onImport = { imports++ },
                    onDismiss = {}, onConfirm = { confirmed = it })
            }
        }
        compose.awaitStable(compose.onNodeWithText("输入新的歌单标题"))
        compose.onNodeWithText("输入新的歌单标题").assert(hasNoClickAction())
        compose.onNodeWithText("从链接导入").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("新的名称")
        val save = compose.onNodeWithText("保存修改").performScrollTo()
        compose.awaitStable(save)
        save.performClick()
        compose.runOnIdle { assertEquals("新的名称", confirmed); assertEquals(0, imports) }
    }

    @Test fun creatingWithoutImportCallbackKeepsNamingAndSubmissionBehavior() {
        val open = mutableStateOf(true)
        var confirmed: String? = null
        compose.setContent {
            MeloraTheme {
                if (open.value) PlaylistEditSheet(onDismiss = { open.value = false }, onConfirm = { confirmed = it })
            }
        }
        compose.awaitStable(compose.onNodeWithText("为喜欢的音乐留一个位置"))
        compose.onNodeWithText("从链接导入").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("  本周喜欢  ")
        val create = compose.onNodeWithText("立即创建").performScrollTo()
        compose.awaitStable(create)
        create.performClick()
        compose.runOnIdle { assertEquals("本周喜欢", confirmed); assertFalse(open.value) }
    }

    @Test fun sameFrameHeaderBoundsIgnoreParentMovementButPreserveRealMisalignment() {
        val parentOffset = mutableStateOf(0.dp)
        val actionOffset = mutableStateOf(0.dp)
        compose.setContent {
            MeloraTheme {
                Row(Modifier.offset { IntOffset(0, parentOffset.value.roundToPx()) }) {
                    Text("标题")
                    Text("操作", Modifier.offset { IntOffset(0, actionOffset.value.roundToPx()) })
                }
            }
        }
        val title = compose.onNodeWithText("标题")
        val action = compose.onNodeWithText("操作")
        val (before, _) = compose.boundsInSameFrame(title, action)
        compose.runOnIdle { parentOffset.value = 80.dp }
        val (movedTitle, movedAction) = compose.boundsInSameFrame(title, action)
        // 确定性反例：混用移动前后的两份坐标会误报错位，同帧结果仍必须严格对齐。
        assertTrue(kotlin.math.abs(before.center.y - movedAction.center.y) > 1f)
        assertEquals(movedTitle.center.y, movedAction.center.y, 1f)
        compose.runOnIdle { actionOffset.value = 8.dp }
        val (misalignedTitle, misalignedAction) = compose.boundsInSameFrame(title, action)
        assertTrue("真实的子节点错位不能被同帧采样抹平", kotlin.math.abs(misalignedTitle.center.y - misalignedAction.center.y) > 1f)
    }

    @Test fun narrowHeaderAtLargeFontKeepsImportLabelOnOneLine() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MeloraTheme {
                    Box(Modifier.requiredWidth(320.dp).padding(16.dp)) {
                        PlaylistSheetHeader(Icons.AutoMirrored.Rounded.QueueMusic, "新建歌单", "为喜欢的音乐留一个位置", onImportClick = {})
                    }
                }
            }
        }
        val entry = compose.onNodeWithText("从链接导入")
        compose.awaitStable(entry)
        val layouts = mutableListOf<TextLayoutResult>()
        entry.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val layout = layouts.single()
        assertFalse(layout.didOverflowHeight)
        assertFalse(layout.isLineEllipsized(0))
        // 用实际文字边界验证裁切，避免段落浮点宽度取整导致误判。
        assertTrue(layout.getLineRight(0) <= layout.size.width)
        assertEquals("从链接导入".length, layout.getLineEnd(0))
        val (title, action) = compose.boundsInSameFrame(compose.onNodeWithText("新建歌单"), entry)
        assertTrue(action.left >= title.right)
        assertEquals(title.center.y, action.center.y, 1f)
        entry.assertHasClickAction()
    }
}
