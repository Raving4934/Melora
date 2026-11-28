package com.leyu.melora.ui.settings

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsNavigationRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningFromDocumentPickerAfterRecreationWritesTheSelectedScript() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "source-export-${System.nanoTime()}").apply { mkdirs() }
        val store = LxScriptStore(sourceContext(base, dir))
        val code = "/** @name Saved picker */\n// 导出内容"
        val script = store.import("saved-picker.js", code)
        val dest = File(dir, "result.js")
        val picker = SourceExportPicker()
        val returnHome = mutableIntStateOf(0)
        val restore = StateRestorationTester(compose)
        try {
            restore.setContent {
                val context = LocalContext.current
                CompositionLocalProvider(
                    LocalContext provides remember(context) { sourceContext(context, dir) },
                    LocalActivityResultRegistryOwner provides picker,
                ) { MeloraTheme { SettingsMasterScreen(requestSeq = returnHome.intValue) } }
            }
            compose.onNodeWithText("自定义源").performClick()
            fun selectScript() {
                compose.awaitStable(compose.onNodeWithContentDescription("更多操作"))
                compose.onNodeWithContentDescription("更多操作").performClick()
                compose.awaitStable(compose.onNodeWithText("导出此源"))
                compose.onNodeWithText("导出此源").performClick()
                compose.waitUntil(5_000) { picker.fileName == script.id }
            }
            selectScript()
            compose.runOnIdle { picker.finish(null) }
            assertFalse(dest.exists())
            selectScript()
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("自定义源管理").assertIsDisplayed()
            compose.runOnIdle { picker.finish(Uri.fromFile(dest)) }
            compose.waitUntil(5_000) { dest.isFile && dest.readText() == code }

            val bundle = File(dir, "sources.json")
            val bundleLabel = "导出全部音源（1 个）"
            openBundlePicker(bundleLabel, picker)
            compose.runOnIdle { picker.finish(Uri.fromFile(bundle)) }
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
            store.remove(script.id)
            dir.deleteRecursively()
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun pendingBundleExportSurvivesRecreationAndKeepsActionsDisabledUntilWritten() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "pending-bundle-${System.nanoTime()}").apply { mkdirs() }
        val bundle = File(dir, "bundle.json").apply { writeText("旧内容".repeat(1024)) }
        val store = LxScriptStore(sourceContext(base, dir))
        val code = "// @name 待导出的音源\nconst fixture = true;"
        val origin = "https://example.test/source.js"
        val script = store.import("pending.js", code, origin)
        val provider = BlockingExportProvider(bundle)
        val resolver = ContentResolver.wrap(provider)
        var picker = SourceExportPicker()
        val restore = StateRestorationTester(compose)
        val bundleLabel = "导出全部音源（1 个）"
        try {
            restore.setContent {
                val context = LocalContext.current
                CompositionLocalProvider(
                    LocalContext provides remember(context) { sourceContext(context, dir, resolver) },
                    LocalActivityResultRegistryOwner provides remember { picker },
                ) { MeloraTheme { LxSourceScreen() } }
            }
            compose.awaitStable(compose.onNodeWithContentDescription("更多操作"))
            openBundlePicker(bundleLabel, picker)
            compose.runOnIdle {
                picker = picker.restoreWithPendingResult(Uri.parse("content://melora.test/bundle"))
            }
            restore.emulateSavedInstanceStateRestore()

            assertTrue("恢复后应开始写合集", provider.opening.await(5, TimeUnit.SECONDS))
            val exportRow = scrollToBundle(bundleLabel)
            exportRow.assertIsNotEnabled()
            provider.release.countDown()

            compose.waitUntil(5_000) { bundle.readText().endsWith("}") }
            val entries = JSONObject(bundle.readText()).getJSONArray("scripts")
            assertEquals(1, entries.length())
            val entry = entries.getJSONObject(0)
            assertEquals(script.id, entry.getString("name"))
            assertEquals(code, entry.getString("code"))
            assertEquals(origin, entry.getString("originUrl"))
            compose.waitUntil(5_000) { runCatching { exportRow.assertIsEnabled() }.isSuccess }
        } finally {
            provider.release.countDown()
            store.remove(script.id)
            dir.deleteRecursively()
        }
    }

    private fun openBundlePicker(label: String, picker: SourceExportPicker) {
        val row = scrollToBundle(label)
        compose.waitUntil(5_000) { runCatching { row.assertIsEnabled() }.isSuccess }
        row.performClick()
        compose.waitUntil(5_000) { picker.fileName == "melora-sources.json" }
    }

    private fun scrollToBundle(label: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(label)) }.isSuccess
        }
        return compose.onNodeWithText(label)
    }

    private fun sourceContext(base: Context, dir: File, resolver: ContentResolver = base.contentResolver) =
        object : ContextWrapper(base) {
            override fun getFilesDir() = dir
            override fun getContentResolver() = resolver
        }

    private class SourceExportPicker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        private var pending: Int? = null
        var fileName: String? = null
            private set

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            pending = requestCode
            fileName = input as String
        }

        fun finish(uri: Uri?) {
            deliver(checkNotNull(pending), uri)
            pending = null
            fileName = null
        }

        fun restoreWithPendingResult(uri: Uri): SourceExportPicker {
            val state = Bundle().also(::onSaveInstanceState)
            val requestCode = checkNotNull(pending)
            return SourceExportPicker().apply {
                onRestoreInstanceState(state)
                deliver(requestCode, uri)
            }
        }

        private fun deliver(requestCode: Int, uri: Uri?) {
            val result = if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK
            check(dispatchResult(requestCode, result, Intent().setData(uri)))
        }
    }

    private class BlockingExportProvider(private val file: File) : ContentProvider() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opening.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
