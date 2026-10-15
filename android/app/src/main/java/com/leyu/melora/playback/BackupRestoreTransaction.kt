package com.leyu.melora.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.leyu.melora.playback.lx.LxScriptStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设置、用户库与源文件的短暂持久化临界区；常规更新和恢复共用，不跨挂起点持锁。 */
internal object BackupStateLock {
    val monitor = Any()

    /** 未完成的回滚必须先解决，再读取候选状态和接受下一次保存。 */
    inline fun <T> write(block: () -> T): T = synchronized(monitor) {
        BackupRestoreTransaction.ensureWritable()
        block()
    }
}

/** 可恢复提交：先落盘原始快照，异常回滚；进程中断由启动屏障先回滚，再初始化播放器。 */
// 仅保存 applicationContext，绝不持有 Activity；未完成恢复期间必须强引用，恢复/保留成功即清空。
@SuppressLint("StaticFieldLeak")
internal object BackupRestoreTransaction {
    private var pendingRecovery: Context? = null
    private val _failure = MutableStateFlow<Exception?>(null)
    val failure = _failure.asStateFlow()

    internal fun ensureWritable() {
        check(Thread.holdsLock(BackupStateLock.monitor))
        pendingRecovery?.let(::recover)
    }

    private fun directory(context: Context) = File(context.filesDir, "backup-restore")
    private val libraryFiles = listOf("user-library.json", "user-library.json.bak", "user-library.json.tmp")
    private val preferenceNames = listOf(MeloraSettings.PREFS, LxScriptStore.PREFS)

    fun recover(context: Context) = synchronized(BackupStateLock.monitor) {
        try {
            val journal = directory(context)
            if (journal.exists()) {
                val committed = File(journal, "committed")
                if (File(journal, "ready").isFile && !(committed.isFile && committed.readText() == "1")) {
                    rollback(context, journal)
                    MeloraSettings.reloadAfterRestore()
                    UserLibrary.reloadAfterRestore()
                }
                // 先退出活动路径再删除：清理中断不能遗留可回滚已接受新业务的旧 ready 日志。
                check(retireJournal(context, journal)) { "无法清理恢复日志" }
            }
            directory(context).parentFile?.listFiles { file -> file.name.startsWith("backup-restore-cleanup-") }
                ?.forEach { runCatching { it.deleteRecursively() } }
            pendingRecovery = null
            _failure.value = null
        } catch (error: Exception) {
            // 在释放共用写锁之前关闸，运行中导入失败与冷启动恢复使用同一状态。
            pendingRecovery = context.applicationContext
            _failure.value = error
            throw error
        }
    }

    /** 单独暴露准备阶段给故障恢复测试；正式入口始终通过run提交。 */
    fun prepare(context: Context, checkCancelled: () -> Unit = {}) {
        recover(context)
        val journal = directory(context)
        check(journal.mkdirs()) { "无法创建恢复暂存目录" }
        val preferences = JSONObject()
        preferenceNames.forEach { name ->
            preferences.put(name, encodePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        }
        writeDurable(File(journal, "preferences.json"), preferences.toString())
        libraryFiles.forEach { name ->
            val source = File(context.filesDir, name)
            if (source.isFile) copyFile(source, File(journal, name), checkCancelled)
        }
        val sourceDir = File(context.filesDir, LxScriptStore.DIRECTORY)
        if (sourceDir.exists()) copyTree(sourceDir, File(journal, "sources"), checkCancelled)
        checkCancelled()
        writeDurable(File(journal, "ready"), "1")
    }

    fun <T> run(context: Context, checkCancelled: () -> Unit, write: () -> T): T = synchronized(BackupStateLock.monitor) {
        try {
            prepare(context, checkCancelled)
            checkCancelled()
            val result = write()
            checkCancelled()
            writeDurable(File(directory(context), "committed"), "1")
            // 提交已完成。清理失败只留下可幂等清理的committed日志，不把成功谎报为失败。
            runCatching { retireJournal(context, directory(context)) }
            result
        } catch (failure: Throwable) {
            try {
                recover(context)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
                // 未清掉的ready日志保留给下次启动，不能带着半恢复状态继续下一次导入。
                throw IllegalStateException("恢复失败且回滚未完成，请释放存储空间后重启应用", failure)
            }
            throw failure
        }
    }

    /** 先确认当前偏好落盘，再原子改名保留现场；失败仍由原日志保护。 */
    fun retain(context: Context): File = synchronized(BackupStateLock.monitor) {
        try {
            val journal = directory(context)
            check(journal.isDirectory) { "无法保留恢复快照，请释放存储空间后重试" }
            // commit=false 也可能已更新 SharedPreferences 缓存，必须确认两组当前值均落盘。
            preferenceNames.forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                prefs.replaceValues(prefs.all, clear = true)
            }
            MeloraSettings.reloadAfterRestore()
            UserLibrary.reloadAfterRestore()
            val retained = File(context.filesDir, "backup-restore-retained-${UUID.randomUUID()}")
            check(journal.renameTo(retained)) { "无法保留恢复快照，请释放存储空间后重试" }
            pendingRecovery = null
            _failure.value = null
            retained
        } catch (error: Exception) {
            pendingRecovery = context.applicationContext
            _failure.value = error
            throw error
        }
    }

    private fun rollback(context: Context, journal: File) {
        // 必须一次解析全部偏好，再动用户库/音源。后一个偏好损坏也不能造成半回滚。
        val preferences = decodePreferences(journal)
        libraryFiles.forEach { name ->
            val saved = File(journal, name)
            val target = File(context.filesDir, name)
            if (saved.isFile) copyFile(saved, target) else check(!target.exists() || target.delete()) { "无法回滚用户库" }
        }
        val sourceDir = File(context.filesDir, LxScriptStore.DIRECTORY)
        check(!sourceDir.exists() || sourceDir.deleteRecursively()) { "无法回滚音源目录" }
        val sources = File(journal, "sources")
        if (sources.exists()) copyTree(sources, sourceDir)
        preferences.forEach { (name, values) ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).replaceValues(values, clear = true)
        }
    }

    private fun decodePreferences(journal: File): Map<String, Map<String, Any>> {
        val preferences = JSONObject(File(journal, "preferences.json").inputStream().use { it.readBackupText() })
        return preferenceNames.associateWith { name ->
            val saved = preferences.getJSONObject(name)
            saved.keys().asSequence().associateWith { key ->
                val field = saved.getJSONArray(key)
                when (field.getString(0)) {
                    "boolean" -> field.getBoolean(1)
                    "int" -> field.getInt(1)
                    "long" -> field.getLong(1)
                    "float" -> field.getDouble(1).toFloat()
                    "string" -> field.getString(1)
                    "set" -> field.getJSONArray(1).let { array -> List(array.length()) { array.getString(it) }.toSet() }
                    else -> error("无效恢复日志")
                }
            }
        }
    }

    private fun encodePreferences(prefs: SharedPreferences): JSONObject = JSONObject().apply {
        prefs.all.forEach { (key, value) ->
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is String -> "string"
                is Set<*> -> "set"
                else -> error("不支持的原始偏好类型: $key")
            }
            put(key, JSONArray().put(type).put(if (value is Set<*>) JSONArray(value) else value))
        }
    }

    private fun copyTree(from: File, to: File, checkCancelled: () -> Unit = {}) {
        check(to.isDirectory || to.mkdirs()) { "无法创建恢复目录" }
        val files = from.listFiles() ?: error("无法读取音源目录")
        files.forEach { source ->
            checkCancelled()
            if (source.isDirectory) copyTree(source, File(to, source.name), checkCancelled)
            else copyFile(source, File(to, source.name), checkCancelled)
        }
    }
    private fun copyFile(from: File, to: File, checkCancelled: () -> Unit = {}) {
        from.inputStream().use { input -> to.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
    }
    private fun retireJournal(context: Context, journal: File): Boolean {
        val retired = File(context.filesDir, "backup-restore-cleanup-${UUID.randomUUID()}")
        if (!journal.renameTo(retired)) return false
        runCatching { retired.deleteRecursively() }
        return true
    }

    private fun writeDurable(file: File, text: String) {
        val staged = File(file.parentFile, "${file.name}.tmp")
        try {
            staged.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
    }
}
