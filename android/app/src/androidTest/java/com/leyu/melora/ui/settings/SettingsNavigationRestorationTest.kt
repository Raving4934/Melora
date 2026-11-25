package com.leyu.melora.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsNavigationRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningFromDocumentPickerAfterRecreationWritesTheSelectedScript() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "source-export-${System.nanoTime()}").apply { mkdirs() }
        fun filesContext(context: Context) = object : ContextWrapper(context) {
            override fun getFilesDir() = dir
        }
        val code = "/** @name Saved picker */\n// 导出内容"
        val script = LxScriptStore(filesContext(base)).import("saved-picker.js", code)
        val dest = File(dir, "result.js")
        var pending = 0
        var chosenName: Any? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                chosenName = input
                pending = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val returnHome = mutableIntStateOf(0)
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                val context = LocalContext.current
                CompositionLocalProvider(
                    LocalContext provides remember(context) { filesContext(context) },
                    LocalActivityResultRegistryOwner provides owner,
                ) { MeloraTheme { SettingsMasterScreen(requestSeq = returnHome.intValue) } }
            }
            compose.onNodeWithText("自定义源").performClick()
            fun selectScript() {
                pending = 0
                compose.awaitStable(compose.onNodeWithContentDescription("更多操作"))
                compose.onNodeWithContentDescription("更多操作").performClick()
                compose.awaitStable(compose.onNodeWithText("导出此源"))
                compose.onNodeWithText("导出此源").performClick()
                compose.waitUntil(5_000) { pending != 0 }
                assertEquals(script.id, chosenName)
            }
            selectScript()
            compose.runOnIdle { registry.dispatchResult(pending, Activity.RESULT_CANCELED, Intent()) }
            assertFalse(dest.exists())
            selectScript()
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("自定义源管理").assertIsDisplayed()
            compose.runOnIdle { registry.dispatchResult(pending, Activity.RESULT_OK, Intent().setData(Uri.fromFile(dest))) }
            compose.waitUntil(5_000) { dest.isFile && dest.readText() == code }

            val bundle = File(dir, "sources.json")
            val bundleLabel = "导出全部音源（1 个）"
            pending = 0
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(bundleLabel))
            compose.onNodeWithText(bundleLabel).performClick()
            compose.waitUntil(5_000) { pending != 0 }
            assertEquals("melora-sources.json", chosenName)
            compose.runOnIdle { registry.dispatchResult(pending, Activity.RESULT_OK, Intent().setData(Uri.fromFile(bundle))) }
            compose.waitUntil(5_000) { bundle.isFile && bundle.readText().endsWith("}") }
            val payload = JSONObject(bundle.readText())
            assertEquals("melora", payload.getString("app"))
            assertEquals("lx-sources", payload.getString("type"))
            val entry = payload.getJSONArray("scripts").getJSONObject(0)
            assertEquals(script.id, entry.getString("name"))
            assertEquals(code, entry.getString("code"))

            compose.runOnIdle { returnHome.intValue++ }
            compose.awaitStable(compose.onNodeWithText("基本设置"))
            compose.onNodeWithText("自定义源管理").assertDoesNotExist()
        } finally {
            LxScriptStore(filesContext(base)).remove(script.id)
            dir.deleteRecursively()
        }
    }
}
