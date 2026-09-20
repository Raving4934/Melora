package com.leyu.melora.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** v1配置备份：字段预验证→暂存原始快照→可恢复提交；不复制媒体文件或迁移系统授权。 */
object BackupManager {
    private val operations = Mutex()
    private const val RECOVERY_PREFIX = "backup-export-recovery-"
    val hasExportRecovery = MutableStateFlow(false)

    private fun retainedExports(context: Context): List<File> = context.filesDir.listFiles {
        file -> file.isFile && file.name.startsWith(RECOVERY_PREFIX) && file.length() > 0L
    }?.sortedBy(File::lastModified).orEmpty()

    /** 外部存储拒绝回写时，用户可把保留的原文档另存到可用位置；取消/失败不删除副本。 */
    suspend fun saveExportRecovery(context: Context, uri: Uri): Result<String> = ioResult {
        operations.withLock {
            val saved = retainedExports(context).firstOrNull() ?: error("没有待取回的原文档副本")
            require(uri.scheme != "file" || File(requireNotNull(uri.path)).canonicalFile != saved.canonicalFile) { "请选择其它保存位置" }
            val job = currentCoroutineContext()
            val text = saved.inputStream().use { it.readBackupText { job.ensureActive() } }
            writeExport(context, uri, text) { job.ensureActive() }
            val removed = saved.delete()
            hasExportRecovery.value = retainedExports(context).isNotEmpty()
            if (removed) "原文件副本已导出" else "原文件副本已导出，本地保留副本暂未清理"
        }
    }

    suspend fun export(context: Context, uri: Uri): Result<String> = ioResult {
        operations.withLock {
            val job = currentCoroutineContext()
            val (text, count) = synchronized(BackupStateLock.monitor) {
                val scripts = LxScriptStore(context).backupSnapshot { job.ensureActive() }
                val root = JSONObject().put("version", 1).put("exportedAt", System.currentTimeMillis())
                    .put("settings", BackupSettings.collect()).put("library", UserLibrary.backupSnapshot())
                    .put("scripts", JSONArray().apply { scripts.forEach { script ->
                        put(JSONObject().put("fileName", script.fileName).put("code", script.code).put("enabled", script.enabled))
                    } })
                boundedBackupJson(root).also(::parseBackupDocument) to scripts.size
            }
            job.ensureActive()
            writeExport(context, uri, text) { job.ensureActive() }
            "备份完成（含设置、用户库与 $count 个音源脚本；不含音乐文件、缓存及播放进度）"
        }
    }

    suspend fun import(context: Context, uri: Uri): Result<String> = ioResult {
        operations.withLock {
            val job = currentCoroutineContext()
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBackupText { job.ensureActive() } }
                ?: error("无法读取备份文件")
            val backup = parseBackupDocument(text)
            val grants = context.contentResolver.persistedUriPermissions
            val readable = grants.filter { it.isReadPermission }.mapTo(hashSetOf()) { it.uri.toString() }
            val writable = grants.filter { it.isReadPermission && it.isWritePermission }.mapTo(hashSetOf()) { it.uri.toString() }
            var changedScripts: Set<String> = emptySet()
            val notice = BackupRestoreTransaction.run(context, { job.ensureActive() }) {
                val settings = backup.settings?.let { BackupSettings.prepare(it, readable, writable) }
                settings?.first?.let(BackupSettings::apply)
                job.ensureActive()
                backup.library?.let(UserLibrary::replaceFromBackup)
                job.ensureActive()
                backup.scripts?.let { changedScripts = LxScriptStore(context).restoreBackup(it) { job.ensureActive() } }
                settings?.second.orEmpty()
            }
            // 一旦commit完成，取消不能跳过运行时同步；源初始化失败是运行状态提示，不误报数据恢复失败。
            val runtimeNotice = withContext(NonCancellable) {
                SourceResolver.clearCache()
                var warning = ""
                if (backup.scripts != null) runCatching { LxScriptPool.refresh(context, changedScripts) }.onFailure {
                    warning += "音源已保存，初始化未完成，请到音源设置重试。"
                }
                withContext(Dispatchers.Main.immediate) {
                    runCatching { applyRuntimeSettings(context) }.onFailure {
                        warning += "设置已恢复，部分运行状态需重启应用后生效。"
                    }
                }
                warning
            }
            "恢复完成：设置 ${backup.settings?.length() ?: 0} 项、脚本 ${backup.scripts?.size ?: 0} 个、用户库${if (backup.library == null) "未包含" else "已恢复"}。" + notice + runtimeNotice
        }
    }

    /** Application初始化任何存储/播放模块之前调用，清理已提交日志或回滚中断事务。 */
    internal fun recoverInterruptedRestore(context: Context) {
        BackupRestoreTransaction.recover(context)
        hasExportRecovery.value = retainedExports(context).isNotEmpty()
    }

    private fun applyRuntimeSettings(context: Context) {
        PlaybackController.applyAudioFocus(MeloraSettings.pauseOnOtherAudio.value)
        AudioCacheStore.cancelPrefetch()
        AudioCacheStore.trimNow()
        if (MeloraSettings.showDesktopLyrics.value && AndroidSettings.canDrawOverlays(context)) {
            DesktopLyricService.start(context)
        } else {
            DesktopLyricService.stop(context)
            if (!AndroidSettings.canDrawOverlays(context)) MeloraSettings.updateShowDesktopLyrics(false)
        }
    }

    /** 任意SAF不保证rename原子性：先保留目标原文，写入/关闭失败回写；新建空文档失败则尽力删除。 */
    private fun writeExport(context: Context, uri: Uri, text: String, checkCancelled: () -> Unit) {
        val previous = File.createTempFile(RECOVERY_PREFIX, ".json", context.filesDir)
        var retainOriginal = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                previous.writeText(input.readBackupText(checkCancelled = checkCancelled))
            } ?: error("无法读取备份目标以保护原文件，请新建备份文档")
            checkCancelled()
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { output ->
                    var offset = 0
                    while (offset < text.length) {
                        checkCancelled()
                        val count = minOf(16 * 1024, text.length - offset)
                        output.write(text, offset, count)
                        offset += count
                    }
                } ?: error("无法写入备份文件")
                checkCancelled()
            } catch (failure: Throwable) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        previous.inputStream().use { it.copyTo(output) }
                    } ?: error("无法还原备份目标")
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                    retainOriginal = previous.length() > 0L
                    if (retainOriginal) hasExportRecovery.value = true
                    if (previous.length() == 0L) runCatching {
                        if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
                        else DocumentsContract.deleteDocument(context.contentResolver, uri)
                    }
                    throw IllegalStateException(if (retainOriginal) {
                        "备份写入失败且目标不可还原。原文件副本已保留，请在备份设置中另存副本，取回前勿清除应用数据。"
                    } else "备份写入失败，请检查存储权限后新建备份文档。", failure)
                }
                throw failure
            }
        } finally { if (!retainOriginal) previous.delete() }
    }

    private suspend fun <T> ioResult(block: suspend () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
