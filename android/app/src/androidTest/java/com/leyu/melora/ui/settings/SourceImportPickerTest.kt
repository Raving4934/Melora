package com.leyu.melora.ui.settings

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceImportPickerTest {
    @get:Rule val compose = createComposeRule()

    private fun select(label: String): Array<String> {
        var selected = emptyArray<String>()
        compose.setContent {
            MeloraTheme {
                ImportSourceSheet(
                    onDismiss = {},
                    onLocalFile = { selected = it },
                    onOnlineUrl = { fail("不应触发网络导入") },
                )
            }
        }
        val option = compose.onNodeWithText(label)
        compose.awaitStable(option)
        option.performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { selected.isNotEmpty() } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ActivityResultContracts.OpenDocument().createIntent(context, selected)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        return requireNotNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test fun autoSwitchIdentifiesItselfToAccessibilityServices() {
        compose.setContent { MeloraTheme { LxSourceScreen() } }
        compose.onNodeWithContentDescription("自动换源").assertIsToggleable().assertIsEnabled()
    }

    @Test fun scriptPickerOnlyAllowsJavaScriptTypes() {
        val types = select("选择 JS 文件").toSet()
        assertEquals(setOf("application/javascript", "text/javascript", "application/x-javascript"), types)
        val mimeTypes = MimeTypeMap.getSingleton()
        assertTrue(mimeTypes.getMimeTypeFromExtension("js") in types)
        for (extension in listOf("txt", "json", "jpg", "zip", "mp3", "pdf")) {
            assertFalse("$extension 不能作为 JS 选择", mimeTypes.getMimeTypeFromExtension(extension) in types)
        }
    }

    @Test fun backupPickerRemainsSeparateAndOnlyAllowsJson() {
        assertArrayEquals(arrayOf("application/json"), select("恢复音源备份"))
    }

    @Test fun onlineImportShowsValidationForTheCurrentUrlAndClearsItWhenEdited() {
        compose.setContent { MeloraTheme { LxSourceScreen() } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("导入音源脚本"))
        compose.onNodeWithText("导入音源脚本").performClick()
        compose.onNodeWithText("从链接导入").performClick()
        val submit = compose.onNodeWithText("立即导入")
        compose.awaitStable(submit)
        submit.assertIsNotEnabled()

        val url = compose.onNode(hasSetTextAction())
        val invalidUrl = compose.onNodeWithText("音源脚本链接格式无效")
        val invalidFragment = compose.onNodeWithText("音源链接不能包含 fragment")
        url.performTextReplacement("file:///source.js")
        submit.assertIsEnabled().performClick()
        compose.awaitStable(invalidUrl)

        url.performTextReplacement("https://example.invalid/source.js#tag")
        invalidUrl.assertDoesNotExist()
        submit.performClick()
        compose.awaitStable(invalidFragment)

        compose.onNodeWithContentDescription("清空").performClick()
        invalidFragment.assertDoesNotExist()
        submit.assertIsNotEnabled()
    }
}
