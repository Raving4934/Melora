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

    private class ScriptRevision(val number: Long, var metadata: Map<String, String>? = null)

    internal companion object {
        const val DIRECTORY = "lx-sources"
        const val PREFS = "lx-sources"
        private const val ORIGIN_PREFIX = "origin:"
        private val enabledStateLock = BackupStateLock.monitor
        // 引擎与展示元数据共用文件版本，开关仍读取当前偏好。
        private val contentRevisions = mutableMapOf<File, ScriptRevision>()
        private var nextRevision = 0L

        private fun originKey(id: String) = "$ORIGIN_PREFIX$id"

        internal fun invalidateDirectoryRevisions(directory: File) = synchronized(enabledStateLock) {
            contentRevisions.keys.removeAll { it.parentFile == directory.absoluteFile }
        }
    }

    private fun scriptFiles() = dir.listFiles { it.isFile && it.name.endsWith(".js") }

    fun list(): List<LxScript> = synchronized(enabledStateLock) {
        scriptFiles().orEmpty().map(::script).sortedBy(LxScript::name)
    }

    fun hasEnabledScripts(): Boolean = synchronized(enabledStateLock) {
        prefs.all.any { (id, value) -> value == true && id.endsWith(".js") && dir.resolve(id).isFile }
    }

    fun code(id: String): String? = content(id)?.first

    /** 查询不读脚本正文；删除后重导入也不能复用旧revision。 */
    internal fun revision(id: String): Long? = synchronized(enabledStateLock) {
        dir.resolve(id).takeIf { it.isFile }?.let { revisionOf(it).number }
    }

    /** 加载代码与其revision必须取自同一次存储快照。 */
    internal fun content(id: String): Pair<String, Long>? = synchronized(enabledStateLock) {
        dir.resolve(id).takeIf { it.isFile }?.let { it.readText() to revisionOf(it).number }
    }

    private fun revisionOf(file: File): ScriptRevision =
        contentRevisions.getOrPut(file.absoluteFile) { ScriptRevision(++nextRevision) }

    private fun contentChanged(file: File, code: String? = null) {
        contentRevisions[file.absoluteFile] = ScriptRevision(++nextRevision, code?.let(::parseLxScriptMetadata))
    }

    fun import(name: String, code: String, originUrl: String? = null, expectedCode: String? = null): LxScript =
        BackupStateLock.write {
            validateImportedScriptCode(code)
            val normalizedOriginUrl = originUrl?.let { LxSourceUrlPolicy.validate(it).toString() }
            val file = File(dir, backupScriptId(name))
            val currentCode = file.takeIf { it.isFile }?.readText()
            check(expectedCode == null || (currentCode == expectedCode &&
                prefs.getString(originKey(file.name), null) == normalizedOriginUrl)) {
                "音源在更新期间已更改或删除，请重新检查后重试"
            }
            if (currentCode != code) {
                writeTextAtomically(file, code)
                contentChanged(file, code)
            }
            prefs.edit {
                if (!prefs.contains(file.name)) putBoolean(file.name, false)
                if (normalizedOriginUrl == null) remove(originKey(file.name)) else putString(originKey(file.name), normalizedOriginUrl)
            }
            script(file)
        }

    private fun script(file: File): LxScript {
        val id = file.name
        val revision = revisionOf(file)
        val meta = revision.metadata ?: parseLxScriptMetadata(file.readText()).also { revision.metadata = it }
        return LxScript(
            id = id,
            name = meta["name"].orEmpty().ifEmpty { "未命名脚本" },
            description = meta["description"].orEmpty(),
            version = meta["version"].orEmpty(),
            enabled = prefs.getBoolean(id, false),
            originUrl = prefs.getString(originKey(id), null),
        )
    }

    fun remove(id: String) = BackupStateLock.write {
        dir.resolve(id).delete()
        if (!dir.resolve(id).exists()) contentRevisions.remove(dir.resolve(id).absoluteFile)
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
    fun setEnabled(id: String, enabled: Boolean): Set<String> = BackupStateLock.write {
        val installedIds = scriptFiles().orEmpty().mapTo(linkedSetOf()) { it.name }
        val currentEnabledIds = installedIds.filter { prefs.getBoolean(it, false) }
        if (id !in installedIds) return@write currentEnabledIds.toSet()

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
        val files = scriptFiles() ?: error("无法读取音源目录")
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
    internal fun restoreBackup(scripts: List<BackupScript>, checkCancelled: () -> Unit) = BackupStateLock.write {
        val installed = (scriptFiles() ?: error("无法读取音源目录")).mapTo(linkedSetOf()) { it.name }
        var enabled = installed.filter { prefs.getBoolean(it, false) }.toSet()
        val originUrls = linkedMapOf<String, String>()
        installed.forEach { id -> prefs.getString(originKey(id), null)?.let { originUrls[id] = it } }
        val changed = linkedSetOf<String>()
        scripts.forEach { script ->
            checkCancelled()
            val file = File(dir, script.fileName)
            if (!file.isFile || file.length() != script.code.toByteArray(Charsets.UTF_8).size.toLong() || file.readText() != script.code) {
                writeTextAtomically(file, script.code)
                contentChanged(file)
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
