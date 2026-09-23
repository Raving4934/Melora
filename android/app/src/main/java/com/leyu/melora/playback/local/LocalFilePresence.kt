package com.leyu.melora.playback.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal enum class LocalFilePresence { Present, Missing, Unknown }

/** 仅在IO线程查单个队列文件，不扫描媒体库；读租约避免标签写入的临时替换被当成删除。 */
internal fun localFilePresence(context: Context, value: String, folderHint: String? = null): LocalFilePresence = try {
    val uri = value.toUri()
    LocalMediaIoCoordinator.withRead(context, uri) {
        when (uri.scheme) {
            "file" -> uri.path?.let { filePresence(context, File(it)) } ?: LocalFilePresence.Unknown
            "content" -> contentPresence(context, uri, folderHint)
            else -> LocalFilePresence.Unknown
        }
    }
} catch (_: Exception) { LocalFilePresence.Unknown }

// 仅识别旧队列中的外部存储URI前缀以拒绝误删，不以硬编码目录读写媒体。
@SuppressLint("SdCardPath")
private fun filePresence(context: Context, file: File): LocalFilePresence {
    val volume = context.getSystemService(StorageManager::class.java)?.getStorageVolume(file)
    if (volume == null && (file.path.startsWith("/storage/") || file.path.startsWith("/sdcard/"))) return LocalFilePresence.Unknown
    if (volume != null && volume.state !in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
        return LocalFilePresence.Unknown
    }
    if (volume != null && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return LocalFilePresence.Unknown
    }
    return try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        LocalFilePresence.Present
    } catch (_: NoSuchFileException) {
        // 父目录本身不可访问时也可能返回ENOENT，不能因此清掉断开的目录/卷。
        if (file.parentFile?.let { it.isDirectory && it.canRead() } == true) LocalFilePresence.Missing
        else LocalFilePresence.Unknown
    } catch (_: Exception) { LocalFilePresence.Unknown }
}

private fun contentPresence(context: Context, uri: Uri, folderHint: String?): LocalFilePresence {
    val resolver = context.contentResolver
    if (uri.authority == MediaStore.AUTHORITY) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return LocalFilePresence.Unknown
        val volume = uri.pathSegments.firstOrNull() ?: return LocalFilePresence.Unknown
        if (volume != "internal") {
            if (Environment.getExternalStorageState() !in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) return LocalFilePresence.Unknown
            if (Build.VERSION.SDK_INT >= 29 && volume != "external" && volume !in MediaStore.getExternalVolumeNames(context)) return LocalFilePresence.Unknown
        }
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
            if (it.moveToFirst()) LocalFilePresence.Present else if (volume == "external") {
                // external是多卷聚合视图：一首SD卡歌曲消失不代表删除，必须确认原卷仍挂载。
                val folder = folderHint?.takeIf(String::isNotBlank) ?: LocalMediaStore.findByUri(uri.toString())?.folder
                val storage = folder?.let { path -> context.getSystemService(StorageManager::class.java)?.getStorageVolume(File(path)) }
                if (storage?.state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) LocalFilePresence.Missing
                else LocalFilePresence.Unknown
            } else LocalFilePresence.Missing
        } ?: LocalFilePresence.Unknown
    }
    if (!DocumentsContract.isTreeUri(uri)) return LocalFilePresence.Unknown
    // SAF根不可读/授权已丢失：保留队列。树根仍可查询时才采信子文件的明确空结果。
    val rootId = DocumentsContract.getTreeDocumentId(uri)
    if (uri.authority == "com.android.externalstorage.documents") {
        val volumeId = rootId.substringBefore(':')
        val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
        val volume = volumes.firstOrNull { if (volumeId == "primary") it.isPrimary else it.uuid.equals(volumeId, true) }
            ?: return LocalFilePresence.Unknown
        if (volume.state !in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) return LocalFilePresence.Unknown
    }
    val root = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
    val rootAvailable = resolver.query(root, projection, null, null, null)?.use { it.moveToFirst() } == true
    if (!rootAvailable) return LocalFilePresence.Unknown
    return resolver.query(uri, projection, null, null, null)?.use {
        if (it.moveToFirst()) LocalFilePresence.Present else LocalFilePresence.Missing
    } ?: LocalFilePresence.Unknown
}
