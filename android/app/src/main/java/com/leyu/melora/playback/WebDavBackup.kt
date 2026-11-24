package com.leyu.melora.playback

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class WebDavBackupEntry(val name: String, val createdAt: Long, val device: String)

internal object WebDavBackup {
    private const val PROBE_LIMIT = 1024
    private val backupName = Regex("melora-backup-([0-9]{1,19})-([0-9a-f]{8})-[0-9a-f-]{36}\\.json")

    /** 返回清理提示。写入、回读或列举失败会抛出对应的错误。 */
    suspend fun test(config: WebDavConfig): String? {
        val client = WebDavClient(config)
        client.ensureFolder()
        val id = UUID.randomUUID()
        val name = "melora-test-$id.json"
        val document = """{"test":"$id"}"""
        var created = false
        val cleanupWarning: String?
        try {
            client.writeFile(name, document)
            created = true
            check(client.read(name, PROBE_LIMIT) == document) { "WebDAV 无法完整读回测试文件" }
            check(name in client.listNames()) { "WebDAV 目录列表缺少已写入的测试文件" }
        } finally {
            cleanupWarning = withContext(NonCancellable) {
                try {
                    val cleaned = withTimeoutOrNull(5_000) {
                        // 写入响应可能丢失，此时完整读回才能确认文件由本次测试创建。
                        if (created || client.read(name, PROBE_LIMIT) == document) client.deleteFile(name)
                        true
                    }
                    if (cleaned == null) "测试文件未清理 · 删除请求超时" else null
                } catch (error: WebDavException) {
                    "测试文件未清理 · ${error.message}"
                }
            }
        }
        return cleanupWarning
    }

    suspend fun list(config: WebDavConfig): List<WebDavBackupEntry> = try {
        WebDavClient(config).listNames().mapNotNull(::parseEntry).sortedByDescending(WebDavBackupEntry::createdAt)
    } catch (error: WebDavException) {
        if (error.status != 404) throw error
        emptyList()
    }

    suspend fun readOrigin(config: WebDavConfig, entry: WebDavBackupEntry): BackupOrigin? {
        require(parseEntry(entry.name) == entry) { "WebDAV 备份文件名称无效" }
        return WebDavClient(config).readPrefix(entry.name, 4096)?.let(::readBackupOrigin)
    }

    suspend fun upload(config: WebDavConfig, snapshot: BackupSnapshot) {
        val client = WebDavClient(config)
        val name = snapshot.id.networkName
        client.ensureFolder()
        // 文件名包含备份 UUID。写入响应丢失后按相同 ID 重试，保护已有内容。
        val existing = client.read(name, MAX_BACKUP_BYTES)
        if (existing == null) client.writeFile(name, snapshot.text)
        else check(existing == snapshot.text) { "网络同名备份已存在" }
    }

    suspend fun restore(context: Context, config: WebDavConfig, entry: WebDavBackupEntry): String {
        require(parseEntry(entry.name) == entry) { "WebDAV 备份文件名称无效" }
        val backup = WebDavClient(config).read(entry.name, MAX_BACKUP_BYTES)
            ?: throw WebDavException("WebDAV 备份文件已不存在")
        return BackupManager.restoreText(context, backup)
    }

    private fun parseEntry(name: String): WebDavBackupEntry? {
        val match = backupName.matchEntire(name) ?: return null
        val time = match.groupValues[1].toLongOrNull() ?: return null
        return WebDavBackupEntry(name, time, match.groupValues[2])
    }
}
