package com.leyu.melora.playback

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 服务器保留每台设备最近一次成功写入的回执，断线或重启后可判定 PUT 是否已经生效。 */
private const val RECEIPTS = "writeReceipts"
private val receiptId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

private class RemoteRecords(val records: JSONObject, val receipts: JSONObject) {
    companion object {
        fun parse(text: String): RemoteRecords {
            val root = backupObject(text)
            val receipts = if (root.has(RECEIPTS)) root.getJSONObject(RECEIPTS) else JSONObject()
            require(receipts.length() <= 1024 && receipts.keys().asSequence().all {
                receiptId.matches(it) && receiptId.matches(receipts.optString(it))
            }) { "记录同步回执格式无效" }
            root.remove(RECEIPTS)
            return RemoteRecords(RecordSyncMerge.validateRemote(root), receipts)
        }
    }
}

/** 先持久化本次发送与期望的合并结果，再发 PUT。仅在本机应用成功并保存共同基线后清理。 */
internal class WebDavSyncLedger(private val directory: File) {
    private val journal = File(directory, "pending")
    private val baseline = File(directory, "base.json")
    init { check(directory.isDirectory || directory.mkdirs()) { "无法创建同步目录" } }

    fun base(): JSONObject? = if (baseline.exists()) read(baseline) else null

    fun pending(): String? = if (journal.isDirectory) File(journal, "id").readText() else null

    fun prepare(sent: JSONObject, agreed: JSONObject, id: String) {
        require(!journal.exists())
        val temp = File(directory, "preparing")
        check(temp.isDirectory || temp.mkdir())
        File(temp, "sent").writeText(boundedBackupJson(sent))
        File(temp, "agreed").writeText(boundedBackupJson(agreed))
        File(temp, "id").writeText(id)
        check(temp.renameTo(journal)) { "无法保存同步事务" }
    }

    fun finish(apply: (JSONObject, JSONObject, () -> Unit) -> Unit) {
        val staged = File(journal, "agreed")
        // 文件已移动表示本地与基线都已提交，仅剩上次中断的日志清理。
        if (staged.exists()) apply(read(File(journal, "sent")), read(staged)) {
            Files.move(staged.toPath(), baseline.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        discard()
    }

    fun discard() {
        // 先移出活动路径，再清理，进程被杀不会留下缺少半份快照的活动事务。
        val retired = File(directory, "finished")
        if (retired.exists()) check(retired.deleteRecursively())
        check(journal.renameTo(retired)) { "无法完成同步事务" }
        retired.deleteRecursively()
    }

    private fun read(file: File): JSONObject = file.inputStream().use { RecordSyncMerge.parse(it.readBackupText()) }
}

internal suspend fun syncRecords(
    ledger: WebDavSyncLedger,
    device: String,
    capture: () -> JSONObject,
    apply: (JSONObject, JSONObject, () -> Unit) -> Unit,
    read: suspend () -> WebDavDocument?,
    write: suspend (String, String?) -> Boolean,
) {
    repeat(4) {
        val file = read()
        if (file != null) requireNotNull(file.etag) { "服务器未提供强 ETag，无法安全同步" }
        val remote = file?.let { RemoteRecords.parse(it.text) }
        ledger.pending()?.let { id ->
            // 空 ID 表示只下载。已确认写入时先把离线期间的新编辑叠在旧结果上。
            if (id.isEmpty() || remote?.receipts?.optString(device) == id) ledger.finish(apply)
            else ledger.discard()
        }
        val base = ledger.base()
        val local = capture()
        val agreed = RecordSyncMerge.merge(base, local, remote?.records)
        val upload = !RecordSyncMerge.contentEquals(remote?.records, agreed)
        if (!upload && RecordSyncMerge.contentEquals(base, agreed) && RecordSyncMerge.contentEquals(local, agreed)) return
        val id = if (upload) UUID.randomUUID().toString() else ""
        ledger.prepare(local, agreed, id)
        if (upload) {
            val receipts = remote?.receipts ?: JSONObject()
            check(receipts.has(device) || receipts.length() < 1024) { "同步目录已达到设备数量上限" }
            val payload = JSONObject(agreed.toString()).put(RECEIPTS, receipts.put(device, id))
            if (!write(boundedBackupJson(payload), file?.etag)) {
                ledger.discard()
                return@repeat
            }
        }
        ledger.finish(apply)
        return
    }
    error("其他设备正在修改记录，稍后将重新同步")
}
