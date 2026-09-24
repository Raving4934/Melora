package com.leyu.melora.ui.my

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.ui.awaitStable
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
        compose.awaitStable(entry)
        compose.onAllNodesWithText("从链接导入").assertCountEquals(1)
        compose.onNodeWithText("从链接导入歌单").assertDoesNotExist()
        compose.onNodeWithText("为喜欢的音乐留一个位置").assertIsDisplayed().assert(hasNoClickAction())
        entry.assertHasClickAction().assertTouchHeightIsEqualTo(48.dp)
        val title = compose.onNodeWithText("新建歌单").fetchSemanticsNode().boundsInRoot
        val action = entry.fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
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
        val title = compose.onNodeWithText("新建歌单").fetchSemanticsNode().boundsInRoot
        val action = entry.fetchSemanticsNode().boundsInRoot
        assertTrue(action.left >= title.right)
        assertEquals(title.center.y, action.center.y, 1f)
        entry.assertHasClickAction()
    }
}
