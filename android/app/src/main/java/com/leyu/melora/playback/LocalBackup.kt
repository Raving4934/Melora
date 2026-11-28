package com.leyu.melora.playback

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.ByteString

internal object LocalBackup {
    /** 先核对新备份，再清理本应用生成的旧备份。保留同一目录中的用户文件及其他设备的备份。 */
    suspend fun write(context: Context, tree: Uri, snapshot: BackupSnapshot, keepLatest: Boolean): String {
        val job = currentCoroutineContext()
        val resolver = context.contentResolver
        val (root) = writableDirectory(resolver, tree)
        val previous = managedDocuments(resolver, tree, snapshot.id.device)
        val existing = previous.firstOrNull { it.first == snapshot.id }?.second
        val prior = existing?.let {
            resolver.openInputStream(it)?.use { input -> input.readBackupBytes { job.ensureActive() } }
                ?: error("无法核对之前写入的备份")
        }
        check(prior == null || snapshot.bytes.startsWith(prior)) { "同名备份已存在" }
        val saved = existing ?: DocumentsContract.createDocument(resolver, root, "application/json", snapshot.id.localName)
            ?: error("无法在所选目录创建备份文件")
        try {
            if (prior != snapshot.bytes) resolver.writeVerifiedBackup(saved, snapshot.bytes) { job.ensureActive() }
        } catch (failure: Exception) {
            if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, saved) }
            throw failure
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

    internal fun writableDirectory(resolver: ContentResolver, tree: Uri): Pair<Uri, String> {
        val hasGrant = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
        check(hasGrant) { "备份目录授权已失效，请重新选择" }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(Document.COLUMN_FLAGS, Document.COLUMN_DISPLAY_NAME)
        return resolver.query(root, columns, null, null, null)?.use { row ->
            if (!row.moveToFirst() || row.getInt(0) and Document.FLAG_DIR_SUPPORTS_CREATE == 0) return@use null
            val name = row.getColumnIndex(Document.COLUMN_DISPLAY_NAME).takeIf { it >= 0 }?.let(row::getString)
            root to (name?.takeIf(String::isNotBlank) ?: DocumentsContract.getTreeDocumentId(tree))
        } ?: error("目录不可写，请重新选择备份目录")
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

}

internal inline fun <T> ContentResolver.withPersistableTreeAccess(
    tree: Uri,
    permissionError: String,
    invalidTree: String = "请选择文件夹",
    verify: (alreadyAuthorized: Boolean) -> T,
): T {
    require(tree.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(tree)) { invalidTree }
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    var acquired = 0
    return try {
        val previous = persistedUriPermissions.firstOrNull { it.uri == tree }
        val added = (if (previous?.isReadPermission == true) 0 else read) or
            (if (previous?.isWritePermission == true) 0 else write)
        if (added != 0) {
            takePersistableUriPermission(tree, added)
            acquired = added
        }
        verify(added == 0)
    } catch (failure: Exception) {
        if (acquired != 0) runCatching { releasePersistableUriPermission(tree, acquired) }
        if (failure is SecurityException) throw IllegalStateException(permissionError, failure)
        throw failure
    }
}

internal fun ContentResolver.openPickedDocument(uri: Uri, failureMessage: String): InputStream =
    try {
        openInputStream(uri) ?: error(failureMessage)
    } catch (failure: Exception) {
        if (failure !is SecurityException && failure !is FileNotFoundException) throw failure
        throw IllegalStateException(failureMessage, failure)
    }

internal fun ContentResolver.writeVerifiedBackup(uri: Uri, bytes: ByteString, checkCancelled: () -> Unit) {
    openOutputStream(uri, "wt")?.use { output ->
        for (offset in 0 until bytes.size step 16_384) {
            checkCancelled()
            bytes.substring(offset, minOf(bytes.size, offset + 16_384)).write(output)
        }
    } ?: error("无法写入本地备份")
    check(openInputStream(uri)?.use { it.readBackupBytes(checkCancelled = checkCancelled) } == bytes) { "本地备份读回校验失败" }
}
