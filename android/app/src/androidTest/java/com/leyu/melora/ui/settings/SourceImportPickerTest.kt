package com.leyu.melora.ui.settings

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ActivityResultContracts.OpenDocument().createIntent(context, selected)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        return requireNotNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
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
}
