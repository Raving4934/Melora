package com.leyu.melora.playback

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class WebDavTestResult(val canSync: Boolean)

internal data class WebDavBackupEntry(val name: String, val createdAt: Long, val device: String)

internal object WebDavBackup {
    private const val PROBE_LIMIT = 1024
    private val backupName = Regex("melora-backup-([0-9]{1,19})-([0-9a-f]{8})-[0-9a-f-]{36}\\.json")

    suspend fun test(config: WebDavConfig): WebDavTestResult {
        val client = WebDavClient(config)
        client.ensureFolder()
        val id = UUID.randomUUID()
        val name = "melora-test-$id.json"
        val original = """{"test":"$id","revision":1}"""
        val changed = """{"test":"$id","revision":2}"""
        var created: Boolean? = null
        var failure: Throwable? = null
        try {
            created = client.put(name, original, expectedEtag = null)
            check(created) { "WebDAV 测试文件已存在，请重试" }
            val copy = client.read(name, PROBE_LIMIT)
            check(copy?.text == original && name in client.listNames()) { "WebDAV 测试文件无法读回或列出" }
            return WebDavTestResult(
                copy.etag?.let { etag -> supportsConditionalWrites(client, name, etag, changed) } == true,
            )
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // 即使写入响应丢失，也尝试清理。所有权不明确时先核对随机探测内容。
            if (created != false) try {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000L) {
                        if (created == true || client.read(name, PROBE_LIMIT)?.text == original) client.deleteProbe(name)
                    } ?: throw WebDavException("清理 WebDAV 测试文件超时")
                }
            } catch (cleanupError: Exception) {
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    private suspend fun supportsConditionalWrites(
        client: WebDavClient,
        name: String,
        etag: String,
        changed: String,
    ): Boolean = try {
        val wrongEtag = "\"test-${UUID.randomUUID()}\""
        if (client.put(name, changed, expectedEtag = null) ||
            client.put(name, changed, wrongEtag) ||
            !client.put(name, changed, etag)) {
            false
        } else {
            val updated = client.read(name, PROBE_LIMIT)
            updated?.text == changed && updated.etag != null && updated.etag != etag
        }
    } catch (error: WebDavException) {
        if (error.status !in setOf(400, 403, 405, 409, 501)) throw error
        false
    }

    suspend fun list(config: WebDavConfig): List<WebDavBackupEntry> =
        WebDavClient(config).listNames().mapNotNull(::parseEntry).sortedByDescending(WebDavBackupEntry::createdAt)

    suspend fun readOrigin(config: WebDavConfig, entry: WebDavBackupEntry): BackupOrigin? {
        require(parseEntry(entry.name) == entry) { "WebDAV 备份文件名称无效" }
        return WebDavClient(config).readPrefix(entry.name, 4096)?.let(::readBackupOrigin)
    }

    suspend fun upload(config: WebDavConfig, snapshot: BackupSnapshot) {
        val client = WebDavClient(config)
        val name = snapshot.id.networkName
        client.ensureFolder()
        if (!client.put(name, snapshot.text, expectedEtag = null)) {
            // 请求响应丢失后可用同一 ID 重试，只承认内容完全相同的已存在文件。
            check(client.read(name, MAX_BACKUP_BYTES)?.text == snapshot.text) { "网络同名备份已存在" }
        }
    }

    suspend fun restore(context: Context, config: WebDavConfig, entry: WebDavBackupEntry): String {
        require(parseEntry(entry.name) == entry) { "WebDAV 备份文件名称无效" }
        val backup = WebDavClient(config).read(entry.name, MAX_BACKUP_BYTES)
            ?: throw WebDavException("WebDAV 备份文件已不存在")
        return BackupManager.restoreText(context, backup.text)
    }

    private fun parseEntry(name: String): WebDavBackupEntry? {
        val match = backupName.matchEntire(name) ?: return null
        val time = match.groupValues[1].toLongOrNull() ?: return null
        return WebDavBackupEntry(name, time, match.groupValues[2])
    }
}
