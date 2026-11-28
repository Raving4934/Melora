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
import androidx.core.net.toUri
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

// 两个历史 URI 卷名同样适用于 API 26–28，命名外卷的枚举接口从 API 29 才可用。
internal const val AGGREGATE_MEDIA_VOLUME = "external"
internal const val INTERNAL_MEDIA_VOLUME = "internal"

internal enum class LocalFilePresence { Present, Missing, Unknown }

internal val sharedAudioReadPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun Context.canReadSharedAudio(): Boolean =
    checkSelfPermission(sharedAudioReadPermission) == PackageManager.PERMISSION_GRANTED

private fun String?.isReadableMediaVolumeState(): Boolean =
    this == Environment.MEDIA_MOUNTED || this == Environment.MEDIA_MOUNTED_READ_ONLY

internal fun StorageManager?.hasReadableMediaFolder(path: String): Boolean =
    runCatching { this?.getStorageVolume(File(path))?.state.isReadableMediaVolumeState() }.getOrDefault(false)

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
    if (volume != null) {
        if (!volume.state.isReadableMediaVolumeState()) return LocalFilePresence.Unknown
        val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        if (!hasAllFilesAccess && !context.canReadSharedAudio()) return LocalFilePresence.Unknown
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
        if (!context.canReadSharedAudio()) return LocalFilePresence.Unknown
        val volume = uri.pathSegments.firstOrNull() ?: return LocalFilePresence.Unknown
        fun viewAvailable(): Boolean = volume == INTERNAL_MEDIA_VOLUME ||
            Environment.getExternalStorageState().isReadableMediaVolumeState() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || volume == AGGREGATE_MEDIA_VOLUME || volume in MediaStore.getExternalVolumeNames(context))
        if (!viewAvailable()) return LocalFilePresence.Unknown
        // external 聚合多个卷，只有前后均可读的原卷能证明空结果代表缺失。
        val folder = if (volume == AGGREGATE_MEDIA_VOLUME) {
            folderHint?.takeIf(String::isNotBlank) ?: LocalMediaStore.findByUri(uri.toString())?.folder
        } else null
        fun originalVolumeAvailable(): Boolean = volume != AGGREGATE_MEDIA_VOLUME || folder?.let { path ->
            context.getSystemService(StorageManager::class.java).hasReadableMediaFolder(path)
        } == true
        val readableBefore = originalVolumeAvailable()
        val found = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
        return when {
            found == true -> LocalFilePresence.Present
            found == false && readableBefore && context.canReadSharedAudio() && viewAvailable() && originalVolumeAvailable() ->
                LocalFilePresence.Missing
            else -> LocalFilePresence.Unknown
        }
    }
    if (!DocumentsContract.isTreeUri(uri)) return LocalFilePresence.Unknown
    // SAF根不可读/授权已丢失：保留队列。树根仍可查询时才采信子文件的明确空结果。
    val rootId = DocumentsContract.getTreeDocumentId(uri)
    if (uri.authority == "com.android.externalstorage.documents") {
        val volumeId = rootId.substringBefore(':')
        val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
        val volume = volumes.firstOrNull { if (volumeId == "primary") it.isPrimary else it.uuid.equals(volumeId, true) }
            ?: return LocalFilePresence.Unknown
        if (!volume.state.isReadableMediaVolumeState()) return LocalFilePresence.Unknown
    }
    val root = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
    val rootAvailable = resolver.query(root, projection, null, null, null)?.use { it.moveToFirst() } == true
    if (!rootAvailable) return LocalFilePresence.Unknown
    return resolver.query(uri, projection, null, null, null)?.use {
        if (it.moveToFirst()) LocalFilePresence.Present else LocalFilePresence.Missing
    } ?: LocalFilePresence.Unknown
}
