package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import com.leyu.melora.playback.lx.LxScriptStore
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 设置、用户库与源文件的短暂持久化临界区；常规更新和恢复共用，不跨挂起点持锁。 */
internal object BackupStateLock { val monitor = Any() }

/** 可恢复提交：先落盘原始快照，异常回滚；进程中断由启动屏障先回滚，再初始化播放器。 */
internal object BackupRestoreTransaction {
    private fun directory(context: Context) = File(context.filesDir, "backup-restore")
    private val libraryFiles = listOf("user-library.json", "user-library.json.bak", "user-library.json.tmp")
    private val preferenceNames = listOf(MeloraSettings.PREFS, LxScriptStore.PREFS)

    fun recover(context: Context) = synchronized(BackupStateLock.monitor) {
        val journal = directory(context)
        if (!journal.exists()) return@synchronized
        if (File(journal, "ready").isFile && !File(journal, "committed").isFile) {
            rollback(context, journal)
            MeloraSettings.reloadAfterRestore()
            UserLibrary.reloadAfterRestore()
        }
        check(journal.deleteRecursively()) { "无法清理恢复日志" }
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
            directory(context).deleteRecursively()
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

    /** 仅在用户明确选择继续时保留整个现场；原子改名，不删除、不覆盖现有数据。 */
    fun retain(context: Context): File = synchronized(BackupStateLock.monitor) {
        val journal = directory(context)
        val retained = File(context.filesDir, "backup-restore-retained-${UUID.randomUUID()}")
        check(journal.isDirectory && journal.renameTo(retained)) { "无法保留恢复快照，请释放存储空间后重试" }
        retained
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
    private fun writeDurable(file: File, text: String) {
        file.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }
}
