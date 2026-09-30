package com.leyu.melora.playback

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
            assertEquals(ApkUpdatePhase.Ready, updater.state.value.phase)
            assertTrue(file.exists())
            updater.install()
            updater.await(ApkUpdatePhase.PermissionRequired)
            allowed = true
            updater.permissionReturned()
            updater.await(ApkUpdatePhase.InstallRequested)
            assertEquals(4, validations.get())
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

    private fun updater(
        directory: File,
        download: suspend (String, File, (Long, Long?) -> Unit) -> Unit = { _, file, _ -> file.writeText("complete") },
        validate: (File) -> Unit = { check(it.readText() == "complete") },
        canInstall: () -> Boolean = { true },
        checkRelease: suspend () -> UpdateResult = { release },
    ) = ApkUpdater(directory, checkRelease, download, validate, canInstall, Dispatchers.Unconfined)

    private suspend fun ApkUpdater.await(phase: ApkUpdatePhase) = withTimeout(5_000) { state.first { it.phase == phase } }
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
}
