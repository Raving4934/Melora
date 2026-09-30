package com.leyu.melora.playback

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.leyu.melora.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val APK_UPDATE_DIRECTORY = "apk_updates"
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal enum class ApkUpdatePhase {
    Checking, Latest, Available, Downloading, Verifying, Ready, PermissionRequired, InstallRequested, Failed,
}

internal data class ApkUpdateState(
    val phase: ApkUpdatePhase = ApkUpdatePhase.Checking,
    val release: UpdateResult? = null,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val apk: File? = null,
    val message: String? = null,
)

/** 弹层独占的 APK 链路；不借用歌曲队列。每次传输独立文件，代次只允许当前任务发布状态。 */
internal class ApkUpdater(
    private val directory: File,
    private val checkRelease: suspend () -> UpdateResult,
    private val downloadApk: suspend (String, File, (Long, Long?) -> Unit) -> Unit,
    private val validateApk: (File) -> Unit,
    private val canInstall: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val mutableState = MutableStateFlow(ApkUpdateState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var task: Job? = null
    private var closed = false
    // 已交接给安装器的文件不能随弹层销毁而删除；下次检查只清理超过一天的旧文件。
    private val handedOff = mutableSetOf<File>()

    fun checkForUpdates() = synchronized(lock) {
        if (closed || state.value.release?.hasUpdate == true) return@synchronized
        start(ApkUpdateState()) { token ->
            withContext(Dispatchers.IO) {
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1) }
                    ?.forEach { it.delete() }
            }
            val result = checkRelease()
            publish(token, ApkUpdateState(
                phase = when {
                    result.checkFailed -> ApkUpdatePhase.Failed
                    result.hasUpdate -> ApkUpdatePhase.Available
                    else -> ApkUpdatePhase.Latest
                },
                release = result.takeUnless { it.checkFailed },
                message = result.message,
            ))
        }
    }

    fun download() = synchronized(lock) {
        val current = state.value
        val release = current.release?.takeIf { it.hasUpdate } ?: return@synchronized
        if (current.phase !in setOf(ApkUpdatePhase.Available, ApkUpdatePhase.Failed)) return@synchronized
        start(ApkUpdateState(ApkUpdatePhase.Downloading, release)) { token ->
            var partial: File? = null
            var complete: File? = null
            var delivered = false
            try {
                withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建更新目录" }
                    partial = File.createTempFile("update-", ".part", directory)
                    complete = File(directory, partial!!.name.removeSuffix(".part") + ".apk")
                }
                downloadApk(release.downloadUrl, requireNotNull(partial)) { bytes, total ->
                    publish(token, ApkUpdateState(ApkUpdatePhase.Downloading, release, bytes, total))
                }
                currentCoroutineContext().ensureActive()
                publish(token, ApkUpdateState(ApkUpdatePhase.Verifying, release))
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    check(requireNotNull(partial).renameTo(requireNotNull(complete))) { "无法保存完整安装包" }
                    // 完整流才改为 APK 后缀供系统解析；校验通过前不会发布 Ready 或安装 URI。
                    validateApk(requireNotNull(complete))
                    currentCoroutineContext().ensureActive()
                }
                delivered = publish(token, ApkUpdateState(ApkUpdatePhase.Ready, release, apk = complete))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    partial?.delete()
                    if (!delivered) complete?.delete()
                }
            }
        }
    }

    fun cancel() = synchronized(lock) {
        val current = state.value
        if (closed || current.phase !in setOf(ApkUpdatePhase.Downloading, ApkUpdatePhase.Verifying)) return@synchronized
        generation++
        task?.cancel()
        mutableState.value = ApkUpdateState(
            if (current.apk == null) ApkUpdatePhase.Available else ApkUpdatePhase.Ready,
            current.release, apk = current.apk, message = "已取消，可稍后重试。",
        )
    }

    fun install() = synchronized(lock) {
        val current = state.value
        val apk = current.apk ?: return@synchronized
        if (current.phase !in setOf(ApkUpdatePhase.Ready, ApkUpdatePhase.PermissionRequired)) return@synchronized
        start(current.copy(phase = ApkUpdatePhase.Verifying, message = null)) { token ->
            try {
                // 授权往返或再次安装前重新检查，不能把过期/丢失的文件直接交给安装器。
                withContext(Dispatchers.IO) { validateApk(apk) }
                publish(token, current.copy(
                    phase = if (canInstall()) ApkUpdatePhase.InstallRequested else ApkUpdatePhase.PermissionRequired,
                    message = null,
                ))
            } catch (error: Exception) {
                if (error !is CancellationException) discard(apk)
                throw error
            }
        }
    }

    fun permissionReturned() = synchronized(lock) {
        if (closed || state.value.phase != ApkUpdatePhase.PermissionRequired) return@synchronized
        if (canInstall()) install() else {
            mutableState.value = state.value.copy(phase = ApkUpdatePhase.Ready, message = "尚未允许安装未知应用，安装包已保留，可再次授权。")
        }
    }

    fun launchPermission(launch: () -> Unit) = synchronized(lock) {
        if (closed || state.value.phase != ApkUpdatePhase.PermissionRequired) return@synchronized
        try {
            launch()
        } catch (error: Exception) {
            mutableState.value = state.value.copy(phase = ApkUpdatePhase.Ready, message = "无法打开安装授权设置：${error.message.orEmpty()}")
        }
    }

    fun launchInstaller(launch: (File) -> Unit) = synchronized(lock) {
        val current = state.value
        if (closed || current.phase != ApkUpdatePhase.InstallRequested) return@synchronized
        val apk = requireNotNull(current.apk)
        try {
            launch(apk)
            handedOff += apk
            apk.setLastModified(System.currentTimeMillis())
            mutableState.value = current.copy(phase = ApkUpdatePhase.Ready, message = "已交给系统安装器；若未完成安装，可再次安装。")
        } catch (error: Exception) {
            mutableState.value = current.copy(phase = ApkUpdatePhase.Ready, message = "无法打开系统安装器：${error.message.orEmpty()}")
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            scope.cancel()
            state.value.apk?.let(::discard)
        }
    }

    private fun discard(file: File) {
        synchronized(lock) { if (file !in handedOff) file.delete() }
    }

    private fun start(initial: ApkUpdateState, work: suspend (Long) -> Unit) {
        if (closed) return
        val token = ++generation
        task?.cancel()
        mutableState.value = initial
        val nextTask = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(token, ApkUpdateState(ApkUpdatePhase.Failed, initial.release, message = error.message ?: "更新失败，请重试。"))
            }
        }
        task = nextTask
        nextTask.start()
    }

    private fun publish(token: Long, next: ApkUpdateState): Boolean = synchronized(lock) {
        if (closed || token != generation) false else {
            mutableState.value = next
            true
        }
    }

    companion object {
        fun create(context: Context): ApkUpdater {
            val app = context.applicationContext
            return ApkUpdater(
                directory = File(app.filesDir, APK_UPDATE_DIRECTORY),
                checkRelease = { UpdateChecker.check(BuildConfig.VERSION_NAME) },
                downloadApk = ::downloadUpdateApk,
                validateApk = { validateUpdateApk(app, it) },
                canInstall = { app.packageManager.canRequestPackageInstalls() },
            )
        }
    }
}

private val apkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
}

internal suspend fun downloadUpdateApk(url: String, target: File, onProgress: (Long, Long?) -> Unit) {
    val coroutine = currentCoroutineContext()
    val request = Request.Builder().url(url).header("User-Agent", "Melora-App/${BuildConfig.VERSION_NAME}").build()
    apkHttpClient.newCall(request).readUpdateResponse { response ->
        var complete = false
        try {
            check(response.code == 200) { "下载失败 (HTTP ${response.code})" }
            val body = response.body
            coroutine.ensureActive()
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    copyUpdateApk(input, output, body.contentLength().takeIf { it >= 0 }, coroutine::ensureActive, onProgress)
                }
            }
            coroutine.ensureActive()
            complete = true
        } finally {
            // 取消时 callback 可能稍后才退出；由写入者兜底删除，不能留下迟到创建的部分文件。
            if (!complete) target.delete()
        }
    }
}

internal fun copyUpdateApk(
    input: InputStream,
    output: OutputStream,
    total: Long?,
    checkActive: () -> Unit,
    onProgress: (Long, Long?) -> Unit,
): Long {
    var bytes = 0L
    var lastReported = 0L
    val buffer = ByteArray(64 * 1024)
    onProgress(0, total)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        checkActive()
        output.write(buffer, 0, count)
        bytes += count
        check(total == null || bytes <= total) { "安装包长度异常，请重新下载。" }
        if (bytes - lastReported >= 256 * 1024) {
            onProgress(bytes, total)
            lastReported = bytes
        }
    }
    checkActive()
    check(bytes > 0 && (total == null || bytes == total)) { "安装包下载不完整，请重新下载。" }
    onProgress(bytes, total)
    return bytes
}

internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

internal fun validateApkIdentity(installed: ApkIdentity, candidate: ApkIdentity) {
    check(candidate.packageName == installed.packageName) { "安装包包名不匹配，已拒绝安装。" }
    check(candidate.versionCode > installed.versionCode) { "安装包版本码未高于当前版本，已拒绝安装。" }
    check(candidate.signers.isNotEmpty() && candidate.signers == installed.signers) { "安装包签名与当前应用不一致，已拒绝安装。" }
}

@Suppress("DEPRECATION")
internal fun validateUpdateApk(context: Context, apk: File) {
    check(apk.isFile && apk.length() > 0) { "安装包不存在或下载不完整，请重新下载。" }
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val manager = context.packageManager
    val candidate = manager.getPackageArchiveInfo(apk.absolutePath, flags)
    check(candidate != null) { "安装包损坏或无法验证，请重新下载。" }
    validateApkIdentity(manager.getPackageInfo(context.packageName, flags).apkIdentity(), candidate.apkIdentity())
}

@Suppress("DEPRECATION")
private fun PackageInfo.apkIdentity() = ApkIdentity(
    packageName = packageName,
    versionCode = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
    // 严格比较当前完整签名集合，不把任一历史签名或部分多签名当作相同签名。
    signers = (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)
        .orEmpty().map { it.toCharsString() }.toSet(),
)

class ApkUpdateFileProvider : FileProvider()

internal fun updateInstallIntent(context: Context, apk: File): Intent {
    check(apk.isFile && apk.extension == "apk" && apk.canonicalFile.parentFile == File(context.filesDir, APK_UPDATE_DIRECTORY).canonicalFile) {
        "安装包文件无效，请重新下载。"
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.apk-updates", apk)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("Melora APK", uri) }
}

internal fun updatePermissionIntent(context: Context) = Intent(
    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri(),
)
