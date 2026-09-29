package com.leyu.melora.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.app.ActivityOptionsCompat
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMusicSettingsPermissionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unpersistableTreeResultKeepsFolderAndScanModeUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldFolders = MeloraSettings.localFolders.value
        val oldUseMediaStore = MeloraSettings.localUseMediaStore.value
        val folders = listOf("content://existing/tree/music")
        val treeUri = Uri.parse("content://${context.packageName}.download-fixture/tree/root")
        val result = Intent().setData(treeUri)
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launches++
                dispatchResult(requestCode, Activity.RESULT_OK, result)
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }

        try {
            MeloraSettings.localFolders.value = folders
            MeloraSettings.localUseMediaStore.value = true
            LocalMediaStore.setScanning(false)
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    MeloraTheme { LocalMusicSettingsSubPage(onBack = {}) }
                }
            }

            compose.onNode(hasScrollAction()).performScrollToNode(hasText("添加"))
            compose.onNodeWithText("添加").performScrollTo().performClick()
            compose.waitForIdle()

            assertEquals("目录选择器必须真实收到点击", 1, launches)
            assertEquals(folders, MeloraSettings.localFolders.value)
            assertTrue(MeloraSettings.localUseMediaStore.value)
            assertFalse(LocalMediaStore.isScanning.value)
            assertEquals("无法保留所选文件夹权限，请重新选择目录", PlaybackController.state.value.message)
        } finally {
            MeloraSettings.updateLocalFolders(oldFolders)
            MeloraSettings.updateLocalUseMediaStore(oldUseMediaStore)
        }
    }
}
