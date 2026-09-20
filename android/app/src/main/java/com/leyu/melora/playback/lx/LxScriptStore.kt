package com.leyu.melora.playback.lx

import android.content.Context
import androidx.core.content.edit
import com.leyu.melora.playback.writeTextAtomically
import com.leyu.melora.playback.BackupStateLock
import com.leyu.melora.playback.BackupScript
import com.leyu.melora.playback.MAX_SCRIPT_BYTES
import com.leyu.melora.playback.MAX_BACKUP_BYTES
import com.leyu.melora.playback.readBackupText
import com.leyu.melora.playback.replaceValues
import java.io.File

data class LxScript(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val enabled: Boolean,
)

class LxScriptStore(context: Context) {
    private val dir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal companion object {
        const val DIRECTORY = "lx-sources"
        const val PREFS = "lx-sources"
        private val enabledStateLock = BackupStateLock.monitor
    }

    fun list(): List<LxScript> = synchronized(enabledStateLock) { dir
        .listFiles { file -> file.isFile && file.name.endsWith(".js") }
        ?.map { file ->
            val meta = parseMeta(file.readText())
            LxScript(
                id = file.name,
                name = meta.first,
                description = meta.second,
                version = meta.third,
                enabled = prefs.getBoolean(file.name, false),
            )
        }
        ?.sortedBy { it.name }
        ?: emptyList()
    }

    fun hasEnabledScripts(): Boolean = synchronized(enabledStateLock) {
        prefs.all.any { (id, value) -> value == true && id.endsWith(".js") && dir.resolve(id).isFile }
    }

    fun code(id: String): String? = synchronized(enabledStateLock) { dir.resolve(id).takeIf { it.isFile }?.readText() }

    fun import(name: String, code: String): LxScript = synchronized(enabledStateLock) {
        val file = File(dir, backupScriptId(name))
        writeTextAtomically(file, code)
        if (!prefs.contains(file.name)) {
            prefs.edit { putBoolean(file.name, false) }
        }
        val meta = parseMeta(code)
        LxScript(file.name, meta.first, meta.second, meta.third, prefs.getBoolean(file.name, false))
    }

    fun remove(id: String) = synchronized(enabledStateLock) {
        dir.resolve(id).delete()
        dir.resolve("$id.bak").delete()
        dir.resolve("$id.tmp").delete()
        prefs.edit { remove(id) }
    }

    /**
     * 开启一个已安装源时自动关闭其它源；关闭源时不自动开启其它源，因此允许全部关闭。
     *
     * 返回值是这次操作后的启用 id 集合，供 UI 立即同步内存状态。SharedPreferences 的
     * apply 会先更新进程内状态，不需要为开关操作重建脚本池。
     */
    fun setEnabled(id: String, enabled: Boolean): Set<String> = synchronized(enabledStateLock) {
        val installedIds = dir
            .listFiles { file -> file.isFile && file.name.endsWith(".js") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        val currentEnabledIds = installedIds.filter { prefs.getBoolean(it, false) }
        if (id !in installedIds) return@synchronized currentEnabledIds.toSet()

        val nextEnabledIds = singleSelectEnabledIds(
            installedIds = installedIds,
            currentlyEnabledIds = currentEnabledIds,
            targetId = id,
            targetEnabled = enabled,
        )
        prefs.edit {
            installedIds.forEach { scriptId ->
                putBoolean(scriptId, scriptId in nextEnabledIds)
            }
        }
        nextEnabledIds
    }

    internal fun backupSnapshot(checkCancelled: () -> Unit): List<BackupScript> = synchronized(enabledStateLock) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".js") } ?: error("无法读取音源目录")
        require(files.size <= 128 && files.sumOf { it.length() } <= MAX_BACKUP_BYTES) { "音源总量超过备份限制" }
        files.map { file ->
            checkCancelled()
            require(file.length() <= MAX_SCRIPT_BYTES) { "音源${file.name}超过4 MiB备份限制" }
            BackupScript(file.name, file.inputStream().use { it.readBackupText(MAX_SCRIPT_BYTES, checkCancelled) }, prefs.getBoolean(file.name, false))
        }.sortedBy { parseMeta(it.code).first }
    }

    /** 保持历史合并语义：同名覆盖、其它已装源保留；单选启用状态最后统一commit。 */
    internal fun restoreBackup(scripts: List<BackupScript>, checkCancelled: () -> Unit) = synchronized(enabledStateLock) {
        val installed = dir.listFiles { file -> file.isFile && file.name.endsWith(".js") }?.map { it.name }?.toMutableSet()
            ?: error("无法读取音源目录")
        var enabled = installed.filter { prefs.getBoolean(it, false) }.toSet()
        val changed = linkedSetOf<String>()
        scripts.forEach { script ->
            checkCancelled()
            val file = File(dir, script.fileName)
            if (!file.isFile || file.length() != script.code.toByteArray(Charsets.UTF_8).size.toLong() || file.readText() != script.code) {
                writeTextAtomically(file, script.code)
                changed += script.fileName
            }
            installed += script.fileName
            enabled = singleSelectEnabledIds(installed, enabled, script.fileName, script.enabled)
        }
        checkCancelled()
        prefs.replaceValues(installed.associateWith { it in enabled })
        changed
    }

    private fun parseMeta(code: String): Triple<String, String, String> {
        fun value(key: String): String =
            Regex("@$key\\s+(.+)").find(code)?.groupValues?.get(1)?.trim().orEmpty()
        return Triple(
            value("name").ifEmpty { "未命名脚本" },
            value("description"),
            value("version"),
        )
    }
}

/** 单选启用策略：开启目标时只保留目标，关闭目标时不改变其它源。 */
internal fun singleSelectEnabledIds(
    installedIds: Collection<String>,
    currentlyEnabledIds: Collection<String>,
    targetId: String,
    targetEnabled: Boolean,
): Set<String> {
    val installed = installedIds.toSet()
    val currentEnabled = currentlyEnabledIds
        .asSequence()
        .filter { it in installed }
        .toCollection(linkedSetOf())
    if (targetId !in installed) return currentEnabled

    return if (targetEnabled) {
        setOf(targetId)
    } else {
        currentEnabled.filterTo(linkedSetOf()) { it != targetId }
    }
}

/** 源导入与备份校验共享文件名归一化，重复名称在写盘前判定。 */
internal fun backupScriptId(name: String): String {
    val safe = name.substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9_.\\-\\u4e00-\\u9fa5]"), "_")
        .take(60).ifEmpty { "script" }
    return if (safe.endsWith(".js")) safe else "$safe.js"
}
