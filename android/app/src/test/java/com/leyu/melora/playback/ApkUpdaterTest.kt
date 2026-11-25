package com.leyu.melora.playback

import androidx.lifecycle.SavedStateHandle

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkUpdaterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = UpdateResult(true, "android-v0.1.7", "0.1.6", "notes", "https://example.test/update.apk", "https://example.test/releases")

    @Test fun cancelThenRetryRejectsLateProgressAndDeletesOldPartialFile() = runBlocking {
        val directory = temporary.newFolder()
        val started = CompletableDeferred<File>()
        val letOldFinish = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val updater = updater(directory, download = { _, file, progress ->
            if (attempts.incrementAndGet() == 1) {
                file.writeText("partial")
                started.complete(file)
                // 模拟取消后仍迟到的回调/写入；绝不能覆盖新的 Ready 状态或新文件。
                withContext(NonCancellable) {
                    letOldFinish.await()
                    progress(999, null)
                    file.writeText("stale")
                }
            } else {
                file.writeText("complete")
                progress(8, 8)
            }
        })
        try {
            updater.checkForUpdates()
            updater.await(ApkUpdatePhase.Available)
            updater.download()
            val oldFile = withTimeout(5_000) { started.await() }
            updater.cancel()
            assertEquals(ApkUpdatePhase.Available, updater.state.value.phase)
            updater.download()
            val ready = updater.await(ApkUpdatePhase.Ready)
            letOldFinish.complete(Unit)
            eventually { !oldFile.exists() }
            assertEquals(ready, updater.state.value)
            assertEquals("complete", ready.apk!!.readText())
            assertEquals(listOf(ready.apk), directory.listFiles()!!.toList())
        } finally {
            letOldFinish.complete(Unit)
            updater.close()
        }
        eventually { directory.listFiles().isNullOrEmpty() }
    }

    @Test fun failedTransferDeletesPartialFileAndRetryCreatesOnlyVerifiedApk() = runBlocking {
        val directory = temporary.newFolder()
        val attempts = AtomicInteger()
        val updater = updater(directory, download = { _, file, _ ->
            file.writeText("partial")
            if (attempts.incrementAndGet() == 1) throw IOException("连接中断")
            file.writeText("complete")
        })
        try {
            updater.checkForUpdates()
            updater.await(ApkUpdatePhase.Available)
            updater.download()
            val failed = updater.await(ApkUpdatePhase.Failed)
            assertEquals("连接中断", failed.message)
            assertNull(failed.apk)
            assertTrue(directory.listFiles().isNullOrEmpty())
            updater.download()
            val ready = updater.await(ApkUpdatePhase.Ready)
            assertEquals(2, attempts.get())
            assertEquals("apk", ready.apk!!.extension)
            assertEquals("complete", ready.apk.readText())
        } finally { updater.close() }
    }

    @Test fun closeCancelsInFlightWorkAndDoesNotAllowNewTasks() = runBlocking {
        val directory = temporary.newFolder()
        val started = CompletableDeferred<Unit>()
        val updater = updater(directory, download = { _, file, _ ->
            file.writeText("partial")
            started.complete(Unit)
            awaitCancellation()
        })
        updater.checkForUpdates()
        updater.await(ApkUpdatePhase.Available)
        updater.download()
        withTimeout(5_000) { started.await() }
        updater.close()
        val closedState = updater.state.value
        updater.checkForUpdates()
        updater.download()
        eventually { directory.listFiles().isNullOrEmpty() }
        assertEquals(closedState, updater.state.value)
    }

    @Test fun badPackageNeverBecomesReadyAndLeavesNoInstallableFile() = runBlocking {
        val directory = temporary.newFolder()
        val updater = updater(directory, validate = { throw IllegalStateException("安装包损坏") })
        try {
            updater.checkForUpdates()
            updater.await(ApkUpdatePhase.Available)
            updater.download()
            assertEquals("安装包损坏", updater.await(ApkUpdatePhase.Failed).message)
            assertTrue(directory.listFiles().isNullOrEmpty())
            updater.install()
            assertEquals(ApkUpdatePhase.Failed, updater.state.value.phase)
        } finally { updater.close() }
    }

    @Test fun deniedPermissionCanRetryAndGrantedReturnRevalidatesBeforeHandoff() = runBlocking {
        val directory = temporary.newFolder()
        var allowed = false
        val validations = AtomicInteger()
        val updater = updater(directory, validate = { check(it.readText() == "complete"); validations.incrementAndGet() }, canInstall = { allowed })
        updater.checkForUpdates()
        updater.await(ApkUpdatePhase.Available)
        updater.download()
        val file = updater.await(ApkUpdatePhase.Ready).apk!!
        try {
            updater.install()
            updater.await(ApkUpdatePhase.PermissionRequired)
            var settingsOpened = false
            updater.launchPermission { settingsOpened = true }
            assertTrue(settingsOpened)
            updater.permissionReturned()
            updater.await(ApkUpdatePhase.Ready)
            assertTrue(file.exists())
            updater.install()
            updater.await(ApkUpdatePhase.PermissionRequired)
            updater.launchPermission { }
            allowed = true
            updater.permissionReturned()
            updater.await(ApkUpdatePhase.InstallRequested)
            assertEquals(5, validations.get())
            var handedFile: File? = null
            updater.launchInstaller { handedFile = it }
            assertEquals(file, handedFile)
            assertEquals(ApkUpdatePhase.Ready, updater.state.value.phase)
            assertTrue(updater.state.value.message!!.contains("系统安装器"))
        } finally { updater.close() }
        // 安装器可能还在读取，不因关闭弹层删除已交接 APK。
        assertTrue(file.exists())
    }

    @Test fun installationLaunchFailureKeepsReadyUntilSheetCloses() = runBlocking {
        val directory = temporary.newFolder()
        val updater = updater(directory)
        updater.checkForUpdates()
        updater.await(ApkUpdatePhase.Available)
        updater.download()
        val file = updater.await(ApkUpdatePhase.Ready).apk!!
        try {
            updater.install()
            updater.await(ApkUpdatePhase.InstallRequested)
            updater.launchInstaller { throw IllegalStateException("no installer") }
            assertEquals(ApkUpdatePhase.Ready, updater.state.value.phase)
            assertTrue(file.exists())
            assertTrue(updater.state.value.message!!.contains("无法打开"))
        } finally { updater.close() }
        assertFalse(file.exists())
    }

    @Test fun replayingThePermissionEffectDoesNotLaunchTheSameSettingsRequestAgain() = runBlocking {
        var allowed = false
        val validations = AtomicInteger()
        val updater = updater(
            temporary.newFolder(),
            validate = { check(it.readText() == "complete"); validations.incrementAndGet() },
            canInstall = { allowed },
            initialRelease = release,
        )
        try {
            updater.download()
            val apk = updater.await(ApkUpdatePhase.Ready).apk!!
            updater.install()
            updater.install()
            updater.await(ApkUpdatePhase.PermissionRequired)
            var permissionLaunches = 0
            updater.launchPermission { permissionLaunches++ }
            updater.launchPermission { permissionLaunches++ }
            assertEquals(1, permissionLaunches)
            allowed = true
            updater.permissionReturned()
            updater.permissionReturned()
            updater.await(ApkUpdatePhase.InstallRequested)
            val installed = mutableListOf<File>()
            updater.launchInstaller { installed += it }
            updater.launchInstaller { installed += it }
            assertEquals(listOf(apk), installed)
            assertEquals(3, validations.get())
        } finally { updater.close() }
    }

    @Test fun restoredAuthorizationValidatesOnlyTheRequestedFileAndConsumesTheResultOnce() = runBlocking {
        val directory = temporary.newFolder()
        val originalState = SavedStateHandle()
        val original = updater(directory, canInstall = { false }, initialRelease = release, savedState = originalState)
        try {
            val file = original.beginAuthorization()
            val untouched = File(directory, "update-neighbor.apk").apply { writeText("other update") }
            val validated = mutableListOf<File>()
            val recoveredState = snapshot(originalState)
            val recovered = updater(directory, validate = { validated += it }, savedState = recoveredState)
            try {
                assertEquals(ApkUpdatePhase.AwaitingPermission, recovered.state.value.phase)
                recovered.launchPermission { error("不得重放已发起的授权") }
                recovered.permissionReturned()
                recovered.permissionReturned()
                recovered.await(ApkUpdatePhase.InstallRequested)
                val handedOff = mutableListOf<File>()
                recovered.launchInstaller { handedOff += it }
                recovered.launchInstaller { handedOff += it }
                assertEquals(listOf(file), validated)
                assertEquals(listOf(file), handedOff)
                assertTrue(recoveredState.keys().isEmpty())
                assertEquals("other update", untouched.readText())
            } finally { recovered.close() }
        } finally { original.close() }
    }

    @Test fun restoredAuthorizationWithExpiredOrMissingFileCanDownloadAgain() = runBlocking {
        for (missing in listOf(false, true)) {
            val directory = temporary.newFolder()
            val originalState = SavedStateHandle()
            val original = updater(directory, canInstall = { false }, initialRelease = release, savedState = originalState)
            try {
                val file = original.beginAuthorization()
                assertTrue(if (missing) file.delete() else file.setLastModified(1))
                val recovered = updater(directory, savedState = snapshot(originalState))
                try {
                    recovered.permissionReturned()
                    assertNull(recovered.await(ApkUpdatePhase.Failed).apk)
                    recovered.launchInstaller { error("不得交接已过期或缺失的安装包") }
                    recovered.download()
                    val newFile = recovered.await(ApkUpdatePhase.Ready).apk!!
                    assertTrue(newFile != file && newFile.isFile)
                } finally { recovered.close() }
            } finally { original.close() }
        }
    }

    @Test fun aDismissedRequestCannotResumeAnotherDownloadAfterItsLateReturn() = runBlocking {
        val directory = temporary.newFolder()
        val originalState = SavedStateHandle()
        val original = updater(directory, canInstall = { false }, initialRelease = release, savedState = originalState)
        try {
            val dropped = original.beginAuthorization()
            original.dismiss()
            assertFalse(dropped.exists())
            val recovered = updater(directory, initialRelease = release, savedState = snapshot(originalState))
            try {
                recovered.download()
                val another = recovered.await(ApkUpdatePhase.Ready).apk!!
                recovered.install()
                assertEquals(ApkUpdatePhase.Ready, recovered.state.value.phase)
                recovered.permissionReturned()
                assertEquals(another, recovered.state.value.apk)
                assertEquals(ApkUpdatePhase.Ready, recovered.state.value.phase)
                recovered.install()
                recovered.await(ApkUpdatePhase.InstallRequested)
                recovered.launchInstaller { assertEquals(another, it) }
            } finally { recovered.close() }
        } finally { original.close() }
    }

    @Test fun removedPackageBeforeInstallationBecomesDownloadRetryNotInstallRequest() = runBlocking {
        val updater = updater(temporary.newFolder())
        try {
            updater.checkForUpdates()
            updater.await(ApkUpdatePhase.Available)
            updater.download()
            updater.await(ApkUpdatePhase.Ready).apk!!.delete()
            updater.install()
            assertNull(updater.await(ApkUpdatePhase.Failed).apk)
            updater.download()
            assertNotNull(updater.await(ApkUpdatePhase.Ready).apk)
        } finally { updater.close() }
    }

    @Test fun checkFailureCanRetryButSameVersionRemainsLatest() = runBlocking {
        var attempts = 0
        val updater = updater(temporary.newFolder(), checkRelease = {
            attempts++
            if (attempts == 1) release.copy(hasUpdate = false, checkFailed = true, message = "HTTP 503")
            else release.copy(hasUpdate = false, latestVersion = "android-v0.1.6")
        })
        try {
            updater.checkForUpdates()
            assertEquals("HTTP 503", updater.await(ApkUpdatePhase.Failed).message)
            updater.checkForUpdates()
            updater.await(ApkUpdatePhase.Latest)
            updater.download()
            assertEquals(ApkUpdatePhase.Latest, updater.state.value.phase)
        } finally { updater.close() }
    }

    @Test fun discoveredReleaseCanDownloadImmediately() = runBlocking {
        val updater = updater(
            temporary.newFolder(),
            checkRelease = { error("已发现新版，无需再次查询") },
            initialRelease = release,
        )
        try {
            assertEquals(ApkUpdatePhase.Available, updater.state.value.phase)
            assertEquals(release, updater.state.value.release)
            updater.download()
            assertNotNull(updater.await(ApkUpdatePhase.Ready).apk)
        } finally { updater.close() }
    }

    @Test fun manualAndAutomaticChecksShareOneSheetWithoutTakingOverItsState() = runBlocking {
        val finishCheck = CompletableDeferred<Unit>()
        val updater = updater(temporary.newFolder(), checkRelease = {
            finishCheck.await()
            release
        })
        val automatic = release.copy(latestVersion = "android-v0.1.8")
        try {
            updater.checkForUpdates()
            updater.await(ApkUpdatePhase.Checking)
            updater.offerAutomaticUpdate(automatic)
            updater.dismiss(onlyAutomatic = true)
            assertEquals(ApkUpdatePhase.Checking, updater.state.value.phase)
            finishCheck.complete(Unit)
            assertEquals(release, updater.await(ApkUpdatePhase.Available).release)

            updater.dismiss()
            updater.offerAutomaticUpdate(automatic)
            updater.checkForUpdates()
            assertEquals(automatic, updater.state.value.release)
            updater.dismiss(onlyAutomatic = true)
            assertEquals(ApkUpdatePhase.Idle, updater.state.value.phase)
        } finally { updater.close() }
    }

    private fun updater(
        directory: File,
        download: suspend (String, File, (Long, Long?) -> Unit) -> Unit = { _, file, _ -> file.writeText("complete") },
        validate: (File) -> Unit = { check(it.readText() == "complete") },
        canInstall: () -> Boolean = { true },
        checkRelease: suspend () -> UpdateResult = { release },
        initialRelease: UpdateResult? = null,
        savedState: SavedStateHandle? = null,
    ) = ApkUpdater(directory, checkRelease, download, validate, canInstall, Dispatchers.Unconfined, savedState)
        .also { updater -> initialRelease?.let(updater::offerAutomaticUpdate) }

    private fun snapshot(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<ArrayList<String>>(it) })

    private suspend fun ApkUpdater.beginAuthorization(): File {
        download()
        val file = await(ApkUpdatePhase.Ready).apk!!
        install()
        await(ApkUpdatePhase.PermissionRequired)
        launchPermission { }
        assertEquals(ApkUpdatePhase.AwaitingPermission, state.value.phase)
        return file
    }

    private suspend fun ApkUpdater.await(phase: ApkUpdatePhase) = withTimeout(5_000) { state.first { it.phase == phase } }
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
}
