package com.leyu.melora.playback

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object WebDavBackup {
    private const val PROBE_LIMIT = 1024

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

    suspend fun list(config: WebDavConfig): List<BackupFileId> = try {
        WebDavClient(config).listNames().mapNotNull(BackupFileId::parseNetworkName).sortedByDescending(BackupFileId::createdAt)
    } catch (error: WebDavException) {
        if (error.status != 404) throw error
        emptyList()
    }

    suspend fun readOrigin(config: WebDavConfig, entry: BackupFileId): BackupOrigin? =
        WebDavClient(config).readPrefix(entry.networkName, 4096)?.let(::readBackupOrigin)

    suspend fun upload(config: WebDavConfig, snapshot: BackupSnapshot) = WebDavClient(config).run {
        ensureFolder()
        saveBackup(snapshot.id.networkName, snapshot.bytes)
    }

    suspend fun restore(context: Context, config: WebDavConfig, entry: BackupFileId): String {
        val backup = WebDavClient(config).read(entry.networkName, MAX_BACKUP_BYTES)
            ?: throw WebDavException("WebDAV 备份文件已不存在")
        return BackupManager.restoreText(context, backup)
    }

}
