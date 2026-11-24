package com.leyu.melora.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object LocalBackup {
    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun selectDirectory(context: Context, tree: Uri): String {
        require(DocumentsContract.isTreeUri(tree) && tree.scheme == ContentResolver.SCHEME_CONTENT) { "请选择本地备份文件夹" }
        val resolver = context.contentResolver
        val previous = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        try {
            resolver.takePersistableUriPermission(tree, GRANT_FLAGS)
        } catch (_: SecurityException) {
            error("无法获得备份目录的长期读写权限，请重新选择")
        }
        try {
            val root = directory(resolver, tree)
            return resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { names ->
                if (names.moveToFirst()) names.getString(0) else null
            }?.takeIf(String::isNotBlank) ?: DocumentsContract.getTreeDocumentId(tree)
        } catch (failure: Exception) {
            val read = if (previous?.isReadPermission == true) 0 else Intent.FLAG_GRANT_READ_URI_PERMISSION
            val write = if (previous?.isWritePermission == true) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if ((read or write) != 0) runCatching { resolver.releasePersistableUriPermission(tree, read or write) }
            throw failure
        }
    }

    /** 先核对新备份，再清理本应用生成的旧备份。保留同一目录中的用户文件及其他设备的备份。 */
    suspend fun write(context: Context, tree: Uri, snapshot: BackupSnapshot, keepLatest: Boolean): String {
        val job = currentCoroutineContext()
        val resolver = context.contentResolver
        val root = directory(resolver, tree)
        val previous = managedDocuments(resolver, tree, snapshot.id.device)
        val existing = previous.firstOrNull { it.first == snapshot.id }?.second
        val saved = if (existing != null) {
            val original = resolver.openInputStream(existing)?.use { it.readBackupText { job.ensureActive() } }
                ?: error("无法核对之前写入的备份")
            // 相同 ID 的写入可能已完成但回调丢失。残缺文件由同一备份任务续写。
            if (original != snapshot.text) {
                require(runCatching { parseBackupDocument(original) }.isFailure) { "同名备份已存在" }
                writeAndVerify(resolver, existing, snapshot.text) { job.ensureActive() }
            }
            existing
        } else {
            val created = DocumentsContract.createDocument(resolver, root, "application/json", snapshot.id.localName)
                ?: error("无法在所选目录创建备份文件")
            try {
                writeAndVerify(resolver, created, snapshot.text) { job.ensureActive() }
            } catch (failure: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
                throw failure
            }
            created
        }
        job.ensureActive()
        val old = if (keepLatest) previous.filter { (_, uri) -> uri != saved } else emptyList()
        var cleanupFailed = false
        for ((_, uri) in old) {
            job.ensureActive()
            if (!runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) cleanupFailed = true
        }
        return if (cleanupFailed) "本地已备份，部分旧文件无法删除" else "本地已备份"
    }

    private fun directory(resolver: ContentResolver, tree: Uri): Uri {
        val hasGrant = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
        check(hasGrant) { "备份目录授权已失效，请重新选择" }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val writable = resolver.query(root, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) and Document.FLAG_DIR_SUPPORTS_CREATE != 0
        }
        check(writable == true) { "目录不可写，请重新选择备份目录" }
        return root
    }

    private fun managedDocuments(resolver: ContentResolver, tree: Uri, device: String): List<Pair<BackupFileId, Uri>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(children, columns, null, null, null)?.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    if (rows.getString(2) == Document.MIME_TYPE_DIR) continue
                    val id = rows.getString(1)?.let(BackupFileId::parseLocalName)?.takeIf { it.device == device } ?: continue
                    add(id to DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0)))
                }
            }
        } ?: error("无法读取所选备份目录")
    }

    private fun writeAndVerify(resolver: ContentResolver, uri: Uri, text: String, checkCancelled: () -> Unit) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            for (offset in text.indices step 16_384) {
                checkCancelled()
                writer.write(text, offset, minOf(16_384, text.length - offset))
            }
        } ?: error("无法写入本地备份")
        val readBack = resolver.openInputStream(uri)?.use { it.readBackupText(checkCancelled = checkCancelled) }
        check(readBack == text) { "本地备份读回校验失败" }
    }
}
