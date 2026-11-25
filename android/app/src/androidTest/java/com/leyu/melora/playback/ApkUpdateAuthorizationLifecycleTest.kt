package com.leyu.melora.playback

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkUpdateAuthorizationLifecycleTest {
    @Test fun grantedAuthorizationReturnsToTheSameApkAfterActivityRecreation() = recreateWithResult(granted = true)

    @Test fun deniedAuthorizationKeepsTheSameApkReadyAfterActivityRecreation() = recreateWithResult(granted = false)

    private fun recreateWithResult(granted: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "apk-authorization-" + System.nanoTime()).apply { mkdir() }
        var approval = false
        val factory = viewModelFactory {
            initializer {
                ApkUpdater(
                    directory,
                    checkRelease = { error("test uses a prepared release") },
                    downloadApk = { _, file, _ -> file.writeText("test APK") },
                    validateApk = { check(it.readText() == "test APK") },
                    canInstall = { approval },
                    savedState = createSavedStateHandle(),
                )
            }
        }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var before: ApkUpdater
                val registry = FakeRegistry()
                val savedRegistry = Bundle()
                lateinit var installed: File
                scenario.onActivity { activity ->
                    before = ViewModelProvider.create(activity, factory)[ApkUpdater::class]
                    before.offerAutomaticUpdate(UpdateResult(true, "android-v99", "1", "", "https://example.test/update.apk", ""))
                    before.download()
                }
                val apk = before.await(ApkUpdatePhase.Ready).apk!!
                scenario.onActivity { before.install() }
                before.await(ApkUpdatePhase.PermissionRequired)
                scenario.onActivity {
                    val launcher = registry.register("apk-authorization", ActivityResultContracts.StartActivityForResult()) {
                        before.permissionReturned()
                    }
                    before.launchPermission { launcher.launch(Intent("test.allow_unknown_app_sources")) }
                    registry.onSaveInstanceState(savedRegistry)
                    launcher.unregister()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val retained = ViewModelProvider.create(activity, factory)[ApkUpdater::class]
                    assertSame(before, retained)
                    assertEquals(ApkUpdatePhase.AwaitingPermission, retained.state.value.phase)
                    assertTrue(apk.isFile)
                    val restored = FakeRegistry()
                    restored.onRestoreInstanceState(savedRegistry)
                    val launcher = restored.register("apk-authorization", ActivityResultContracts.StartActivityForResult()) {
                        retained.permissionReturned()
                    }
                    retained.launchPermission { launcher.launch(Intent("test.allow_unknown_app_sources")) }
                    assertEquals(emptyList<Int>(), restored.launches)
                    approval = granted
                    // Settings 的返回码不表示权限结果，应重新读取授权状态。
                    restored.dispatchResult(registry.launches.single(), Activity.RESULT_CANCELED, null)
                    launcher.unregister()
                }
                val phase = if (granted) ApkUpdatePhase.InstallRequested else ApkUpdatePhase.Ready
                assertEquals(apk, before.await(phase).apk)
                scenario.onActivity { before.launchInstaller { installed = it } }
                if (granted) assertEquals(apk, installed)
                else assertTrue(before.state.value.message.orEmpty().contains("尚未允许"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun ApkUpdater.await(phase: ApkUpdatePhase) = withTimeout(5_000) {
        state.first { it.phase == phase }
    }

    private class FakeRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            launches += requestCode
        }
    }
}
