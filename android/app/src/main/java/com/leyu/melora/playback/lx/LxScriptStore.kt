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
    val originUrl: String? = null,
)

class LxScriptStore(context: Context) {
    private val dir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal companion object {
        const val DIRECTORY = "lx-sources"
        const val PREFS = "lx-sources"
        private const val ORIGIN_PREFIX = "origin:"
        private val enabledStateLock = BackupStateLock.monitor

        private fun originKey(id: String) = "$ORIGIN_PREFIX$id"
    }

    fun list(): List<LxScript> = synchronized(enabledStateLock) { dir
        .listFiles { file -> file.isFile && file.name.endsWith(".js") }
        ?.map { file ->
            val meta = parseLxScriptMetadata(file.readText())
            LxScript(
                id = file.name,
                name = meta["name"].orEmpty().ifEmpty { "未命名脚本" },
                description = meta["description"].orEmpty(),
                version = meta["version"].orEmpty(),
                enabled = prefs.getBoolean(file.name, false),
                originUrl = prefs.getString(originKey(file.name), null),
            )
        }
        ?.sortedBy { it.name }
        ?: emptyList()
    }

    fun hasEnabledScripts(): Boolean = synchronized(enabledStateLock) {
        prefs.all.any { (id, value) -> value == true && id.endsWith(".js") && dir.resolve(id).isFile }
    }

    fun code(id: String): String? = synchronized(enabledStateLock) { dir.resolve(id).takeIf { it.isFile }?.readText() }

    fun import(name: String, code: String, originUrl: String? = null): LxScript = synchronized(enabledStateLock) {
        validateImportedScriptCode(code)
        val normalizedOriginUrl = originUrl?.let { LxSourceUrlPolicy.validate(it).toString() }
        val file = File(dir, backupScriptId(name))
        writeTextAtomically(file, code)
        prefs.edit {
            if (!prefs.contains(file.name)) putBoolean(file.name, false)
            if (normalizedOriginUrl == null) remove(originKey(file.name))
            else putString(originKey(file.name), normalizedOriginUrl)
        }
        val meta = parseLxScriptMetadata(code)
        LxScript(
            id = file.name,
            name = meta["name"].orEmpty().ifEmpty { "未命名脚本" },
            description = meta["description"].orEmpty(),
            version = meta["version"].orEmpty(),
            enabled = prefs.getBoolean(file.name, false),
            originUrl = normalizedOriginUrl,
        )
    }

    fun remove(id: String) = synchronized(enabledStateLock) {
        dir.resolve(id).delete()
        dir.resolve("$id.bak").delete()
        dir.resolve("$id.tmp").delete()
        prefs.edit {
            remove(id)
            remove(originKey(id))
        }
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
            BackupScript(
                fileName = file.name,
                code = file.inputStream().use { it.readBackupText(MAX_SCRIPT_BYTES, checkCancelled) },
                enabled = prefs.getBoolean(file.name, false),
                originUrl = prefs.getString(originKey(file.name), null),
            )
        }.sortedBy { parseLxScriptMetadata(it.code)["name"].orEmpty() }
    }

    /** 保持历史合并语义：同名覆盖、其它已装源保留；单选启用状态最后统一commit。 */
    internal fun restoreBackup(scripts: List<BackupScript>, checkCancelled: () -> Unit) = synchronized(enabledStateLock) {
        val installed = dir.listFiles { file -> file.isFile && file.name.endsWith(".js") }?.map { it.name }?.toMutableSet()
            ?: error("无法读取音源目录")
        var enabled = installed.filter { prefs.getBoolean(it, false) }.toSet()
        val originUrls = linkedMapOf<String, String>()
        installed.forEach { id -> prefs.getString(originKey(id), null)?.let { originUrls[id] = it } }
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
            if (script.originUrl == null) originUrls.remove(script.fileName)
            else originUrls[script.fileName] = script.originUrl
        }
        checkCancelled()
        val values = buildMap<String, Any> {
            installed.forEach { id -> put(id, id in enabled) }
            originUrls.forEach { (id, url) -> if (id in installed) put(originKey(id), url) }
        }
        prefs.replaceValues(values, clear = true)
        changed
    }
}

private val LX_SCRIPT_METADATA_PATTERN = Regex("@(name|description|version|author|homepage)\\s+(.+)")

/** Store 与 QuickJS 注入共用一次扫描；重复标签保持历史的首项优先语义。 */
internal fun parseLxScriptMetadata(code: String): Map<String, String> =
    LX_SCRIPT_METADATA_PATTERN.findAll(code).toList().asReversed()
        .associate { it.groupValues[1] to it.groupValues[2].trim() }

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
