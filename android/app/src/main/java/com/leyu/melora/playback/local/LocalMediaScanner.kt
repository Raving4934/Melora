package com.leyu.melora.playback.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.leyu.melora.playback.MeloraSettings
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地媒体扫描：
 * - 安卓媒体库（MediaStore，音乐类条目）
 * - 自定义文件夹（SAF 目录树递归）
 * 自定义文件夹启用时媒体库自动让位；短音频/小音频按开关过滤。
 */
object LocalMediaScanner {
    private const val SHORT_THRESHOLD_MS = 60_000L
    private const val SMALL_THRESHOLD_BYTES = 1_048_576L
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "ape", "aac", "m4a", "ogg", "opus", "wma", "dsf", "dff", "mp4")
    private val scanMutex = Mutex()

    internal data class SourceScan(
        val songs: List<LocalSong>,
        val successful: Boolean,
    )

    fun requiresAudioPermission(): Boolean = MeloraSettings.localUseMediaStore.value

    data class ScanResult(val count: Int, val failedSources: Int) {
        val message: String get() = "扫描完成，本地库共 $count 首歌曲" +
            if (failedSources > 0) "；$failedSources 个来源暂无法读取，已保留旧记录，请检查目录授权" else ""
    }

    suspend fun scan(context: Context): ScanResult {
        return scanMutex.withLock {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                LocalMediaStore.withScanning {
                    val baseline = LocalMediaStore.snapshot()
                    val folders = MeloraSettings.localFolders.value
                    val useMediaStore = requiresAudioPermission()
                    val successful = mutableListOf<LocalSong>()
                    val failed = mutableListOf<LocalSong>()
                    var failedSources = 0

                    if (useMediaStore) {
                        val mediaStore = scanSource { scanMediaStore(appContext) }
                        if (mediaStore.successful) {
                            successful += mediaStore.songs
                        } else {
                            failedSources++
                            failed += baselineSongs(baseline, ::isMediaStoreSong)
                        }
                    }

                    for (tree in folders) {
                        currentCoroutineContext().ensureActive()
                        val folder = scanSource { scanFolderTree(appContext, tree) }
                        if (folder.successful) {
                            successful += folder.songs
                        } else {
                            // 失败目录不能伪装成空目录；仅保留该 SAF 树的旧索引，其他树仍正常替换。
                            failedSources++
                            failed += baselineSongs(baseline) { isInTree(it.uri, tree) }
                        }
                    }

                    val scannedById = LinkedHashMap<String, LocalSong>(successful.size + failed.size)
                    successful.forEach { song ->
                        currentCoroutineContext().ensureActive()
                        if (keep(song)) scannedById.putIfAbsent(song.id, song)
                    }
                    // 成功来源优先，避免重叠配置时失败来源回填旧快照覆盖成功扫描结果。
                    failed.forEach { song ->
                        currentCoroutineContext().ensureActive()
                        scannedById.putIfAbsent(song.id, song)
                    }
                    val scanned = scannedById.values.toList()

                    currentCoroutineContext().ensureActive()
                    // 提交时在 LocalMediaStore 锁内合并 baseline/scanned/current，冲突不重做 IO。
                    LocalMediaStore.commitScanned(baseline, scanned)
                    com.leyu.melora.playback.PlaybackController.checkLocalQueue(appContext)
                    ScanResult(LocalMediaStore.count, failedSources)
                }
            }
        }
    }

    /** 后台补全媒体库条目的采样率/码率（用于 HR/SQ 徽标），不影响列表展示。 */
    suspend fun enrich(context: Context) = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val baseline = LocalMediaStore.snapshot()
            val pending = mutableListOf<LocalSong>()
            for (song in baseline) {
                currentCoroutineContext().ensureActive()
                if (song.sampleRate == 0 || song.bitrate == 0) pending += song
            }
            if (pending.isEmpty()) return@withContext

            val updated = HashMap<String, LocalSong>(pending.size)
            for (song in pending) {
                currentCoroutineContext().ensureActive()
                val tag = LocalTagReader.read(appContext, song.uri) ?: continue
                if (tag.sampleRate == 0 && tag.bitrate == 0) continue
                updated[song.id] = song.copy(
                    sampleRate = if (tag.sampleRate > 0) tag.sampleRate else song.sampleRate,
                    bitrate = if (tag.bitrate > 0) tag.bitrate else song.bitrate,
                    bitDepth = if (tag.bitDepth > 0) tag.bitDepth else song.bitDepth,
                )
                currentCoroutineContext().ensureActive()
            }
            if (updated.isEmpty()) return@withContext

            val scanned = ArrayList<LocalSong>(baseline.size)
            for (song in baseline) {
                currentCoroutineContext().ensureActive()
                scanned += updated[song.id] ?: song
            }
            // enrich 也只读一次文件；并发删除/更新由同一三方合并保留。
            currentCoroutineContext().ensureActive()
            LocalMediaStore.commitScanned(baseline, scanned)
        }
    }

    internal suspend fun scanSource(scan: suspend () -> List<LocalSong>): SourceScan {
        return try {
            currentCoroutineContext().ensureActive()
            SourceScan(scan(), successful = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            SourceScan(emptyList(), successful = false)
        }
    }

    private suspend fun baselineSongs(
        baseline: List<LocalSong>,
        predicate: (LocalSong) -> Boolean,
    ): List<LocalSong> {
        val retained = ArrayList<LocalSong>()
        for (song in baseline) {
            currentCoroutineContext().ensureActive()
            if (predicate(song)) retained += song
        }
        return retained
    }

    private fun isMediaStoreSong(song: LocalSong): Boolean =
        song.id.startsWith("ms_") ||
            song.uri.startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())

    private fun isInTree(songUri: String, treeUri: String): Boolean {
        val song = songUri.toUri()
        val tree = treeUri.toUri()
        if (song.scheme != "content" || tree.scheme != "content" || song.authority != tree.authority) {
            return false
        }
        return try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(tree)
            // SAF provider 的 documentId 可能是不透明ID，不能只按路径前缀判断归属。
            val sameTree = DocumentsContract.isTreeUri(song) && DocumentsContract.getTreeDocumentId(song) == treeDocumentId
            val documentId = DocumentsContract.getDocumentId(song)
            sameTree || documentId == treeDocumentId || documentId.startsWith("$treeDocumentId/")
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun keep(song: LocalSong): Boolean {
        if (MeloraSettings.localExcludeShort.value && song.durationMs in 1 until SHORT_THRESHOLD_MS) return false
        if (MeloraSettings.localExcludeSmall.value && song.sizeBytes in 1 until SMALL_THRESHOLD_BYTES) return false
        return true
    }

    private suspend fun scanMediaStore(context: Context): List<LocalSong> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
        )
        val result = mutableListOf<LocalSong>()
        val cursor = query(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0")
        cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val modifiedIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val addedIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val yearIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val pathIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val mediaId = it.getLong(idIndex)
                    val path = it.getString(pathIndex).orEmpty()
                    val folder = path.substringBeforeLast('/', "")
                    val stem = path.substringAfterLast('/').substringBeforeLast('.')
                    val rawTitle = it.getString(titleIndex).orEmpty().ifBlank { stem }
                    val (title, artist) = restoreFromFileName(
                        rawTitle,
                        cleanUnknown(it.getString(artistIndex)),
                        stem,
                    )
                    result += LocalSong(
                        id = "ms_$mediaId",
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString(),
                        title = title,
                        artist = artist,
                        album = cleanFolderAlbum(
                            cleanUnknown(it.getString(albumIndex)),
                            folder.substringAfterLast('/'),
                        ),
                        durationMs = it.getLong(durationIndex),
                        sizeBytes = it.getLong(sizeIndex),
                        mimeType = it.getString(mimeIndex).orEmpty(),
                        sampleRate = 0,
                        bitrate = 0,
                        modifiedAt = it.getLong(modifiedIndex) * 1000L,
                        addedAt = it.getLong(addedIndex) * 1000L,
                        year = it.getInt(yearIndex),
                        folder = folder,
                    )
                }
        }
        return result
    }

    /** 把扫描协程取消传给provider，信号生命周期覆盖查询和游标遍历。 */
    private suspend fun query(context: Context, uri: Uri, projection: Array<String>, selection: String? = null): Cursor {
        val cancellation = Job(currentCoroutineContext()[Job])
        val signal = CancellationSignal()
        cancellation.invokeOnCompletion { cause -> if (cause != null) signal.cancel() }
        try {
            val cursor = context.contentResolver.query(uri, projection, selection, null, null, signal)
                ?: error("媒体目录暂时不可用")
            return object : CursorWrapper(cursor) {
                override fun close() {
                    try { super.close() } finally { cancellation.complete() }
                }
            }
        } catch (error: Throwable) {
            cancellation.complete()
            throw error
        }
    }

    private suspend fun scanFolderTree(context: Context, treeUri: String): List<LocalSong> {
        val root = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: error("无法读取自定义目录")
        require(root.exists() && root.isDirectory && root.canRead()) { "自定义目录权限已失效" }
        val result = mutableListOf<LocalSong>()
        val queue = ArrayDeque<Pair<android.net.Uri, String>>().apply { add(root.uri to root.name.orEmpty()) }
        val visited = hashSetOf<String>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, folderName) = queue.removeFirst()
            val directoryId = DocumentsContract.getDocumentId(directory)
            if (!visited.add(directoryId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, directoryId)
            // DocumentFile.listFiles会吞查询异常并返回空数组；直接查询才能区别“空目录”和“扫描失败”。
            val cursor = query(context, children, projection)
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.getString(0) ?: error("目录条目缺少ID")
                    val name = it.getString(1).orEmpty()
                    val mime = it.getString(2).orEmpty()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(root.uri, id)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(uri to name)
                        continue
                    }
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (!mime.startsWith("audio") && extension !in AUDIO_EXTENSIONS) continue
                    val tag = LocalTagReader.read(context, uri.toString())
                    currentCoroutineContext().ensureActive()
                    val stem = name.substringBeforeLast('.')
                    val (title, artist) = restoreFromFileName(tag?.title?.ifBlank { null } ?: stem, tag?.artist.orEmpty(), stem)
                    val modifiedAt = it.getLong(4)
                    result += LocalSong(
                        id = "doc_${uri.toString().hashCode().toUInt().toString(16)}",
                        uri = uri.toString(), title = title, artist = artist,
                        album = cleanFolderAlbum(tag?.album.orEmpty(), folderName),
                        durationMs = tag?.durationMs ?: 0L, sizeBytes = it.getLong(3),
                        mimeType = mime.ifBlank { "audio/${extension.ifBlank { "unknown" }}" },
                        sampleRate = tag?.sampleRate ?: 0, bitrate = tag?.bitrate ?: 0,
                        bitDepth = tag?.bitDepth ?: -1,
                        modifiedAt = modifiedAt, addedAt = modifiedAt, year = tag?.year ?: 0,
                        folder = folderName,
                    )
                }
            }
        }
        return result
    }

    private fun cleanUnknown(value: String?): String {
        val text = value?.trim().orEmpty()
        return if (text.equals("<unknown>", true) || text == "null") "" else text
    }

    /**
     * 旧版下载器按「标题 - 歌手」命名、却未写入标签：媒体库会把整段文件名当标题、歌手为空。
     * 无歌手时从文件名还原，让列表展示与联网匹配（封面/歌词）都能用上真实歌手。
     */
    private fun restoreFromFileName(title: String, artist: String, fileNameStem: String): Pair<String, String> {
        if (artist.isNotBlank()) return title to artist
        val index = fileNameStem.lastIndexOf(" - ")
        if (index <= 0 || index + 3 >= fileNameStem.length) return title to artist
        val stemArtist = fileNameStem.substring(index + 3).trim()
        if (stemArtist.isEmpty()) return title to artist
        val stemTitle = fileNameStem.take(index).trim()
        return when {
            title.isBlank() || title == fileNameStem -> stemTitle to stemArtist
            else -> title to stemArtist
        }
    }

    /** 无专辑标签的文件，媒体库会拿所在文件夹名充当专辑（如「Melora」）：同名即清空，不显示假专辑。 */
    private fun cleanFolderAlbum(album: String, folderName: String): String =
        if (album.isNotBlank() && album == folderName) "" else album
}

/** 仅在底层文件未变化时沿用联网补全和探测结果，避免替换文件后展示旧封面/旧标签。 */
internal fun preserveLocalEnrichment(scanned: LocalSong, previous: LocalSong?): LocalSong {
    if (previous == null || previous.modifiedAt != scanned.modifiedAt || previous.sizeBytes != scanned.sizeBytes) {
        return scanned
    }
    return scanned.copy(
        artist = scanned.artist.ifBlank { previous.artist },
        album = scanned.album.ifBlank { previous.album },
        sampleRate = if (scanned.sampleRate > 0) scanned.sampleRate else previous.sampleRate,
        bitrate = if (scanned.bitrate > 0) scanned.bitrate else previous.bitrate,
        bitDepth = if (scanned.bitDepth > 0) scanned.bitDepth else previous.bitDepth,
        year = if (scanned.year > 0) scanned.year else previous.year,
        coverUri = scanned.coverUri ?: previous.coverUri,
        infoFilled = previous.infoFilled,
    )
}

/** baseline 未变化时沿用已有补全；baseline 之后的当前索引更新始终优先。 */
internal fun mergeScannedSong(
    scanned: LocalSong,
    baseline: LocalSong?,
    current: LocalSong?,
): LocalSong {
    if (current == null) return preserveLocalEnrichment(scanned, baseline)
    if (baseline == null) return current
    if (current == baseline) return preserveLocalEnrichment(scanned, baseline)
    // 当前条目已经被并发更新；即使 stamp 相同，也不能让扫描值复写 artist/cover 的删除。
    return current
}
