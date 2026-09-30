package com.leyu.melora.playback

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkInstallInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.filesDir, APK_UPDATE_DIRECTORY).apply { mkdirs() }

    @Test fun cancellingAStalledHttpBodyClosesTheCallAndRemovesPartialBytes() = runBlocking {
        val file = File.createTempFile("cancel-http-test-", ".part", directory)
        val releaseServer = CountDownLatch(1)
        val bodyStarted = CompletableDeferred<Unit>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val serving = thread(isDaemon = true, name = "apk-cancel-fixture") {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n".toByteArray())
                            write(ByteArray(128))
                            flush()
                        }
                        releaseServer.await(5, TimeUnit.SECONDS)
                    }
                }
            }
            val downloading = launch(Dispatchers.IO) {
                downloadUpdateApk("http://127.0.0.1:${server.localPort}/update.apk", file) { _, _ ->
                    bodyStarted.complete(Unit)
                }
            }
            try {
                withTimeout(5_000) { bodyStarted.await(); while (file.length() == 0L) delay(20) }
                // 不能等60秒socket读超时：取消必须直接到达真实的OkHttp Call。
                withTimeout(2_000) { downloading.cancelAndJoin() }
                withTimeout(5_000) { while (file.exists()) delay(20) }
                assertFalse(file.exists())
            } finally {
                downloading.cancelAndJoin()
                releaseServer.countDown()
                serving.join(5_000)
                file.delete()
            }
        }
    }

    @Test fun installationIntentUsesScopedReadableContentUriAndNoWriteGrant() {
        val file = File.createTempFile("install-test-", ".apk", directory)
        try {
            file.writeText("fixture bytes")
            val intent = updateInstallIntent(context, file)
            val uri = requireNotNull(intent.data)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(APK_MIME_TYPE, intent.type)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.apk-updates", uri.authority)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals("fixture bytes", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            @Suppress("DEPRECATION")
            val provider = requireNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
        } finally { file.delete() }
    }

    @Test fun partialAndOutOfScopeFilesCannotBeUsedForInstallation() {
        val partial = File.createTempFile("install-test-", ".part", directory)
        val outside = File.createTempFile("install-test-", ".apk", context.filesDir)
        try {
            partial.writeText("unfinished")
            outside.writeText("outside")
            assertTrue(runCatching { updateInstallIntent(context, partial) }.isFailure)
            assertTrue(runCatching { updateInstallIntent(context, outside) }.isFailure)
            assertTrue(runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.apk-updates", outside)
            }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            partial.delete()
            outside.delete()
        }
    }

    @Test fun unknownSourceSettingsTargetsThisAppAndPermissionIsDeclared() {
        val intent = updatePermissionIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
    }

    @Test fun missingGarbageAndTruncatedApksAreRejectedByPackageParser() {
        val file = File.createTempFile("install-test-", ".part", directory)
        try {
            file.writeText("not an apk")
            assertTrue(runCatching { validateUpdateApk(context, file) }.isFailure)
            File(context.applicationInfo.sourceDir).inputStream().use { source ->
                val prefix = ByteArray(128)
                val count = source.read(prefix)
                file.outputStream().use { it.write(prefix, 0, count) }
            }
            assertTrue(runCatching { validateUpdateApk(context, file) }.isFailure)
            file.delete()
            assertTrue(runCatching { validateUpdateApk(context, file) }.isFailure)
        } finally { file.delete() }
    }

    @Test fun currentInstalledPackageIsNotAcceptedAsNewerUpdate() {
        val file = File.createTempFile("install-test-", ".apk", directory)
        try {
            // 与真实下载链路一样，从应用私有目录解析完整、真实签名的 APK，而非伪造 PackageInfo。
            File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            val failure = runCatching { validateUpdateApk(context, file) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("版本码"))
        } finally { file.delete() }
    }

    @Test fun actualInstrumentationApkWithDifferentPackageNameIsRejected() {
        val testApk = File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        val failure = runCatching { validateUpdateApk(context, testApk) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("包名"))
    }
}
