package com.leyu.melora.playback

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

private const val LEGACY_FILE = "melora-records-v1.json"
// 安装 ID 使用随机 v4 UUID。保留此前缀给只读的旧格式快照。
private const val LEGACY_PREFIX = "00000000-0000-0000-"
private const val CHECKOUT = "checkout.json"

internal interface RecordFiles {
    suspend fun list(): List<String>
    suspend fun read(name: String): String?
    suspend fun write(name: String, text: String)
    suspend fun delete(name: String)
}

/** prepare 和 commit 均在同一次本机记录写锁内执行；本机已变更时整个操作返回 false。 */
internal typealias ApplyRecords = (JSONObject, JSONObject, prepare: () -> Unit, commit: () -> Unit) -> Boolean

private data class Checkout(val view: String, val files: Map<String, String>, val choices: Map<String, Boolean>) {
    fun json(): JSONObject = JSONObject().put("view", view).put("files", JSONObject(files)).put("choices", JSONObject(choices))
    companion object {
        fun read(root: JSONObject): Checkout {
            val files = root.getJSONObject("files")
            val choices = root.getJSONObject("choices")
            return Checkout(root.getString("view"), files.keys().asSequence().associateWith(files::getString),
                choices.keys().asSequence().associateWith(choices::getBoolean))
        }
    }
}

internal class WebDavSyncLedger(private val directory: File) {
    private val cache = File(directory, "replicas")
    init { check(cache.isDirectory || cache.mkdirs()) { "无法创建同步目录" } }

    private fun json(name: String) = File(directory, name).takeIf { it.isFile }?.let { file ->
        file.inputStream().use { backupObject(it.readBackupText()) }
    }
    private fun save(name: String, value: JSONObject) = writeTextAtomically(File(directory, name), boundedBackupJson(value))
    private fun cached(name: String): File {
        require(RecordReplica.filename.matches(name) || name.matches(Regex("view-[0-9a-f]{64}"))) { "同步缓存文件名无效" }
        return File(cache, name)
    }
    private fun store(name: String, text: String) {
        if (!cached(name).isFile) writeTextAtomically(cached(name), text)
    }
    private fun cachedText(name: String): String = cached(name).inputStream().use { it.readBackupText() }
    private fun view(name: String) = RecordSyncMerge.parse(cachedText(name))
    private fun snapshot(records: JSONObject): String {
        val text = boundedBackupJson(records)
        return "view-${RecordReplica.hash(text)}".also { store(it, text) }
    }
    private fun stage(replica: RecordReplica): String {
        val text = replica.encode()
        val name = "melora-records-v2-${replica.device}-${replica.sequence}-${RecordReplica.hash(text)}.json"
        // 本机与远端遵循相同的文件验证规则，上传前即发现大小、内容和版本错误。
        RecordReplica.decode(name, text)
        store(name, text)
        return name
    }

    private suspend fun replica(name: String, remote: RecordFiles): RecordReplica {
        val file = cached(name)
        if (file.isFile) {
            try { return RecordReplica.decode(name, cachedText(name)) } catch (_: Exception) {
                // 磁盘副本损坏时用服务端的同一哈希修复，保留原件直到校验新副本成功。
            }
        }
        val text = requireNotNull(remote.read(name)) { "无法读回设备记录，目录可能刚刚更新，请重试" }
        return RecordReplica.decode(name, text).also { writeTextAtomically(file, text) }
    }

    private suspend fun publish(name: String, remote: RecordFiles) {
        val text = cachedText(name)
        // 文件名包含内容哈希。回包丢失或留下不完整的 UTF-8 时，原样重传同一文件。
        remote.write(name, text)
        check(remote.read(name) == text) { "服务器未完整保存设备记录，请重试" }
    }

    private fun applyView(sent: JSONObject, after: JSONObject, state: Checkout, apply: ApplyRecords) {
        val sentName = snapshot(sent)
        val afterName = snapshot(after)
        check(apply(sent, after, {
            save("applying", JSONObject().put("sent", sentName).put("after", afterName).put("next", state.json()))
        }, {
            save(CHECKOUT, state.json())
            check(File(directory, "applying").delete())
        })) { "本机记录已更新，稍后重新合并" }
    }

    private fun recover(capture: () -> JSONObject, apply: ApplyRecords, choices: Map<String, Boolean>): List<RecordConflict> {
        val pending = json("applying") ?: return emptyList()
        val state = Checkout.read(pending.getJSONObject("next"))
        val before = view(pending.getString("sent"))
        val after = view(pending.getString("after"))
        val current = capture()
        // 崩溃可能发生在写入用户库和听书断点之间。重放不覆盖重启后新增的编辑。
        val saved = pending.optJSONObject("choices")
        val decisions = saved?.keys()?.asSequence()?.associateWith(saved::getBoolean).orEmpty() + choices
        val recovered = RecordSyncMerge.review(before, current, after) { decisions[it.key] }
        if (recovered.conflicts.isNotEmpty()) {
            save("applying", pending.put("choices", JSONObject(decisions)))
            return recovered.conflicts
        }
        check(apply(current, recovered.records, {}, {
            save(CHECKOUT, state.json())
            check(File(directory, "applying").delete())
        })) { "本机记录正在更新，请重试" }
        return emptyList()
    }

    private fun latest(names: Collection<String>): Map<String, String> {
        val versions = names.distinct().mapNotNull { name ->
            RecordReplica.filename.matchEntire(name)?.let { Triple(it.groupValues[1], it.groupValues[2].toLong(), name) }
        }
        return versions.groupBy { it.first }.mapValues { (_, items) ->
            val seq = items.maxOf { it.second }
            items.filter { it.second == seq }.singleOrNull()?.third ?: error("同一设备存在互斥的同步版本，请检查目录")
        }
    }

    private suspend fun migrate(
        device: String, listed: List<String>, remote: RecordFiles, capture: () -> JSONObject, apply: ApplyRecords,
        choices: Map<String, Boolean>,
    ): List<RecordConflict> {
        val heads = latest(listed)
        // v1 已把 agreed 移到 base.json 表示本地事务完成，即使旧 journal 还没清理。
        val oldTransaction = if (File(directory, "pending/agreed").isFile) File(directory, "pending/id").readText() else null
        val legacy = if (heads.isEmpty() || oldTransaction != null) remote.read(LEGACY_FILE)?.let(::backupObject) else null
        val receipt = legacy?.optJSONObject("writeReceipts")?.optString(device)
        legacy?.remove("writeReceipts")
        val seeds = heads.filterKeys { it.startsWith(LEGACY_PREFIX) }.toMutableMap()
        if (legacy != null && heads.isEmpty()) {
            val doc = RecordSyncMerge.validateRemote(legacy)
            val digest = RecordReplica.hash(boundedBackupJson(doc))
            val actor = LEGACY_PREFIX + digest.take(4) + "-" + digest.substring(4, 16)
            val origin = RecordHeads(emptyList()).edit(RecordReplica(actor), recordDocument(), doc)
            val name = stage(origin.copy(sequence = 1L))
            publish(name, remote)
            seeds[actor] = name
        }
        val ancestors = seeds.values.map { replica(it, remote) }
        val seeded = RecordHeads(ancestors).view(RecordReplica(device), emptyMap()).records
        val sent = capture()
        val base = if (oldTransaction != null && (oldTransaction.isEmpty() || oldTransaction == receipt))
            json("pending/sent") else json("base.json")
        base?.let(RecordSyncMerge::validateRemote)
        val agreed = RecordSyncMerge.review(base, sent, if (seeds.isEmpty()) null else seeded) { choices[it.key] }
        if (agreed.conflicts.isNotEmpty()) {
            save("migration-choices", JSONObject(choices))
            return agreed.conflicts
        }
        // 本地基线先指向旧云端种子。迁移后的净本机修改在下一步发布，离线重启也不会丢。
        applyView(sent, agreed.records, Checkout(snapshot(seeded), seeds, emptyMap()),
            apply)
        File(directory, "migration-choices").delete()
        return emptyList()
    }

    suspend fun sync(device: String, remote: RecordFiles, capture: () -> JSONObject, apply: ApplyRecords,
                     choices: Map<String, Boolean> = emptyMap()): List<RecordConflict> {
        require(RecordReplica.actor.matches(device) && !device.startsWith(LEGACY_PREFIX))
        val interrupted = recover(capture, apply, choices)
        if (interrupted.isNotEmpty()) return interrupted
        val listed = remote.list()
        if (json(CHECKOUT) == null) {
            val saved = json("migration-choices")
            val pending = saved?.keys()?.asSequence()?.associateWith(saved::getBoolean).orEmpty() + choices
            val conflicts = migrate(device, listed, remote, capture, apply, pending)
            if (conflicts.isNotEmpty()) return conflicts
        }
        var state = Checkout.read(requireNotNull(json(CHECKOUT)))
        val prior = state.files.mapValues { (_, name) -> replica(name, remote) }
        val local = capture()
        val mine = prior[device] ?: RecordReplica(device)
        val edited = RecordHeads(prior.values).edit(mine, view(state.view), local)
        if (edited !== mine) {
            val file = stage(edited)
            state = state.copy(view = snapshot(local), files = state.files + (device to file))
            save(CHECKOUT, state.json()) // 持久发件箱，网络请求之前落盘
        }
        var files = latest(listed + state.files.values)
        check(files[device] == state.files[device]) { "本机同步版本已变更，请检查设备标识" }
        val replicas = files.mapValues { (id, name) -> if (id == device) edited else prior[id]?.takeIf { state.files[id] == name } ?: replica(name, remote) }
        val merged = RecordHeads(replicas.values).view(edited, state.choices + choices)
        val ownFile = if (merged.decided !== edited) stage(merged.decided) else files[device]
        if (ownFile != null) files = files + (device to ownFile)
        val target = Checkout(snapshot(merged.records), files, merged.pending)
        if (target != state || !RecordSyncMerge.contentEquals(local, merged.records)) {
            applyView(local, merged.records, target, apply)
        }
        val confirmed = File(directory, "confirmed")
        if (ownFile != null && (ownFile !in listed || !confirmed.isFile || confirmed.readText() != ownFile)) {
            publish(ownFile, remote)
            writeTextAtomically(confirmed, ownFile)
        }
        // 仅回收本机旧的完整批次。无 DELETE 权限也能同步，读者始终取各安装的最高版本。
        val owned = listed.filter { name -> name != ownFile && RecordReplica.filename.matchEntire(name)?.groupValues?.get(1) == device }
        for (name in owned.takeIf { ownFile in listed }.orEmpty().take(8)) {
            try { remote.delete(name) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { break }
        }
        val keep = files.values.toSet() + target.view
        cache.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        return merged.conflicts
    }
}
