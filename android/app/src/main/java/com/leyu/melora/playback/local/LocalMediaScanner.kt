package com.leyu.melora.playback.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.UserLibrary
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LocalMediaReference(val id: String, val uri: String, val folder: String) {
    val mediaStoreVolume = uri.takeIf {
        it.startsWith("content://media/") && it.substringBeforeLast('/').endsWith("/audio/media")
    }?.substringAfter("content://media/")?.substringBefore('/')
}

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

    private data class SourceIndex(
        val songs: List<LocalSong>,
        val observed: Set<String>,
    )

    private data class MediaStoreCoverage(val folders: Set<String>, val volumes: Set<String>) {
        fun covers(reference: LocalMediaReference): Boolean = when (val volume = reference.mediaStoreVolume) {
            AGGREGATE_MEDIA_VOLUME -> reference.folder in folders
            else -> volume in volumes
        }
    }

    internal fun missingAudioPermission(context: Context): String? =
        sharedAudioReadPermission.takeIf { MeloraSettings.localUseMediaStore.value && !context.canReadSharedAudio() }

    data class ScanResult(val count: Int, val failedSources: Int) {
        val message: String get() = "扫描完成，本地库共 $count 首歌曲" +
            if (failedSources > 0) "；$failedSources 个来源暂无法读取，已保留旧记录，请检查目录授权" else ""
    }

    suspend fun scan(context: Context): ScanResult =
        scanMutex.withLock {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                LocalMediaStore.withScanning {
                    val baseline = LocalMediaStore.snapshot()
                    val baselineByUri = baseline.associateBy(LocalSong::uri)
                    val references = UserLibrary.localMediaReferences()
                    val priorFiles = (baseline.asSequence().map { LocalMediaReference(it.id, it.uri, it.folder) } + references)
                        .distinctBy { it.id to it.uri }.toList()
                    val successful = mutableListOf<LocalSong>()
                    val failed = mutableListOf<LocalSong>()
                    val observed = hashSetOf<String>()
                    val missing = mutableListOf<LocalMediaReference>()
                    var failedSources = 0
                    fun collect(
                        index: SourceIndex?,
                        previous: List<LocalMediaReference>,
                        canRemove: (LocalMediaReference) -> Boolean,
                    ) {
                        if (index != null) {
                            successful += index.songs
                            observed += index.observed
                        }
                        var incomplete = index == null
                        previous.forEach { song ->
                            if (index?.observed?.contains(song.observationKey()) == true) return@forEach
                            if (index != null && canRemove(song)) {
                                missing += song
                            } else {
                                baselineByUri[song.uri]?.let(failed::add)
                                // 旧聚合 URI 没有原卷信息，单条引用无法确认不代表来源读取失败。
                                incomplete = incomplete || when (song.mediaStoreVolume) {
                                    AGGREGATE_MEDIA_VOLUME -> song.folder.isNotBlank()
                                    INTERNAL_MEDIA_VOLUME -> false // 系统扫描使用外部集合。
                                    else -> true
                                }
                            }
                        }
                        if (incomplete) failedSources++
                    }
                    if (MeloraSettings.localUseMediaStore.value) {
                        val libraryFolders by lazy(LazyThreadSafetyMode.NONE) {
                            references.asSequence().filter {
                                it.folder.isNotBlank() && it.mediaStoreVolume == AGGREGATE_MEDIA_VOLUME
                            }.groupingBy(LocalMediaReference::uri).fold(
                                initialValueSelector = { _, first -> first.folder },
                                operation = { _, agreed, next -> agreed.takeIf { it == next.folder }.orEmpty() },
                            )
                        }
                        val previous = priorFiles.mapNotNullCancellable { reference ->
                            val volume = reference.mediaStoreVolume ?: return@mapNotNullCancellable null
                            if (volume != AGGREGATE_MEDIA_VOLUME || reference.folder.isNotBlank()) return@mapNotNullCancellable reference
                            val folder = baselineByUri[reference.uri]?.folder.orEmpty()
                                .ifBlank { libraryFolders[reference.uri].orEmpty() }
                            if (folder.isBlank()) reference else reference.copy(folder = folder)
                        }
                        val folders = previous.asSequence().filter { it.mediaStoreVolume == AGGREGATE_MEDIA_VOLUME }
                            .map(LocalMediaReference::folder).filter(String::isNotBlank).toSet()
                        val hasNamedVolumes = previous.any {
                            it.mediaStoreVolume != AGGREGATE_MEDIA_VOLUME && it.mediaStoreVolume != INTERNAL_MEDIA_VOLUME
                        }
                        val readableBefore = mediaStoreCoverage(appContext, folders, hasNamedVolumes)
                        val mediaStore = readableBefore?.let { scanSource { scanMediaStore(appContext) } }
                        // 无权限时查询可能只返回自有文件。只有查询前后均有权限且已挂载的卷能确认缺失。
                        val readableAfter = readableBefore?.let { mediaStoreCoverage(appContext, folders, hasNamedVolumes) }
                        collect(mediaStore?.takeIf { readableAfter != null }, previous) {
                            readableBefore?.covers(it) == true && readableAfter?.covers(it) == true
                        }
                    }
                    val folders = MeloraSettings.localFolders.value
                    val idsByUri by lazy(LazyThreadSafetyMode.NONE) { documentIds(baseline, references) }
                    for (folderUri in folders) {
                        currentCoroutineContext().ensureActive()
                        val tree = folderUri.toUri()
                        val folder = scanSource { scanFolderTree(appContext, tree, idsByUri, baselineByUri) }
                        val previous = priorFiles.mapNotNullCancellable {
                            it.takeIf { matchesTree(it.uri, tree, includeAliases = folder == null) }
                        }
                        collect(folder, previous) { true }
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
                    val deleted = missing.filterNot { it.observationKey() in observed }
                    currentCoroutineContext().ensureActive()
                    // 提交时在 LocalMediaStore 锁内合并 baseline/scanned/current，冲突不重做 IO。
                    LocalMediaStore.commitScanned(baseline, scannedById.values.toList(), deleted)
                    com.leyu.melora.playback.PlaybackController.checkLocalQueue(appContext)
                    ScanResult(LocalMediaStore.count, failedSources)
                }
            }
        }

    private fun documentIds(baseline: List<LocalSong>, references: List<LocalMediaReference>): Map<String, String> {
        val usedIds = baseline.mapTo(hashSetOf(), LocalSong::id)
        val saved = references.filterNot { it.id in usedIds }.distinctBy { it.id to it.uri }
        // 旧哈希 ID 仅在本机记录能唯一确定地址和身份时复用。
        val retained = saved.groupBy(LocalMediaReference::id).values.mapNotNull { it.singleOrNull() }
            .groupBy(LocalMediaReference::uri).values.mapNotNull { it.singleOrNull() }
        return retained.associate { it.uri to it.id } + baseline.associate { it.uri to it.id }
    }

    /** 后台补全媒体库条目的实测采样率、码率与位深。 */
    suspend fun enrich(context: Context) = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val baseline = LocalMediaStore.snapshot()
            enrichLocalSpecifications(
                baseline, supportsSampleRate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                readTag = { LocalTagReader.read(appContext, it.uri) },
            ) { scanned ->
                LocalMediaStore.commitScanned(baseline, scanned)
            }
        }
    }

    internal suspend fun <T> scanSource(scan: suspend () -> T): T? {
        return try {
            currentCoroutineContext().ensureActive()
            scan()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private suspend fun <T, R : Any> List<T>.mapNotNullCancellable(transform: (T) -> R?): List<R> {
        val caller = currentCoroutineContext()
        return mapNotNull { song ->
            caller.ensureActive()
            transform(song)
        }
    }

    private fun LocalMediaReference.observationKey(): String = if (mediaStoreVolume != null) "ms_${uri.substringAfterLast('/')}" else uri

    private fun mediaStoreCoverage(context: Context, folders: Set<String>, hasNamedVolumes: Boolean): MediaStoreCoverage? {
        if (!context.canReadSharedAudio()) return null
        val storage = context.getSystemService(StorageManager::class.java) ?: return MediaStoreCoverage(emptySet(), emptySet())
        val volumes = if (hasNamedVolumes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        } else emptySet()
        return MediaStoreCoverage(folders.filterTo(hashSetOf(), storage::hasReadableMediaFolder), volumes)
    }

    private fun matchesTree(songUri: String, tree: Uri, includeAliases: Boolean): Boolean {
        val song = songUri.toUri()
        if (song.scheme != tree.scheme || song.authority != tree.authority) return false
        return runCatching {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(tree)
            val sameTree = DocumentsContract.isTreeUri(song) && DocumentsContract.getTreeDocumentId(song) == treeDocumentId
            // 无法扫描时允许按旧路径保留；只有同一个授权根能确认删除。
            sameTree || includeAliases && DocumentsContract.getDocumentId(song).let {
                it == treeDocumentId || it.startsWith("$treeDocumentId/")
            }
        }.getOrDefault(false)
    }

    private fun keep(song: LocalSong): Boolean {
        if (MeloraSettings.localExcludeShort.value && song.durationMs in 1 until SHORT_THRESHOLD_MS) return false
        if (MeloraSettings.localExcludeSmall.value && song.sizeBytes in 1 until SMALL_THRESHOLD_BYTES) return false
        return true
    }

    private suspend fun scanMediaStore(context: Context): SourceIndex {
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
            MediaStore.Audio.Media.IS_MUSIC,
        )
        val result = mutableListOf<LocalSong>()
        val observed = hashSetOf<String>()
        val cursor = query(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection)
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
                val musicIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val mediaId = it.getLong(idIndex)
                    val id = "ms_$mediaId"
                    observed += id
                    if (it.getInt(musicIndex) == 0) continue
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
                        id = id,
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
        return SourceIndex(result, observed)
    }

    /** 把扫描协程取消传给provider，信号生命周期覆盖查询和游标遍历。 */
    private suspend fun query(context: Context, uri: Uri, projection: Array<String>): Cursor {
        val cancellation = Job(currentCoroutineContext()[Job])
        val signal = CancellationSignal()
        cancellation.invokeOnCompletion { cause -> if (cause != null) signal.cancel() }
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null, signal)
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

    private suspend fun scanFolderTree(
        context: Context,
        treeUri: Uri,
        idsByUri: Map<String, String>,
        previousByUri: Map<String, LocalSong>,
    ): SourceIndex {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法读取自定义目录")
        require(root.exists() && root.isDirectory && root.canRead()) { "自定义目录权限已失效" }
        val result = mutableListOf<LocalSong>()
        val observed = hashSetOf<String>()
        val queue = ArrayDeque(listOf(DocumentsContract.getDocumentId(root.uri) to root.name.orEmpty()))
        val visited = hashSetOf<String>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directoryId, folderName) = queue.removeFirst()
            if (!visited.add(directoryId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root.uri, directoryId)
            // DocumentFile.listFiles会吞查询异常并返回空数组；直接查询才能区别“空目录”和“扫描失败”。
            query(context, children, projection).use {
                val status = it.extras
                // 任一目录返回部分结果或错误时，整棵树保留扫描前的索引。
                check(!status.getBoolean(DocumentsContract.EXTRA_LOADING) &&
                    !status.containsKey(DocumentsContract.EXTRA_ERROR)) { "自定义目录未完整读取" }
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.getString(0) ?: error("目录条目缺少ID")
                    val name = it.getString(1).orEmpty()
                    val mime = it.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(id to name)
                        continue
                    }
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val address = DocumentsContract.buildDocumentUriUsingTree(root.uri, id).toString()
                    observed += address
                    if (!mime.startsWith("audio") && extension !in AUDIO_EXTENSIONS) continue
                    val modifiedAt = it.getLong(4)
                    val sizeBytes = it.getLong(3)
                    val contentType = mime.ifBlank { "audio/${extension.ifBlank { "unknown" }}" }
                    val previous = previousByUri[address]
                    if (previous != null && modifiedAt > 0 && sizeBytes > 0 &&
                        previous.modifiedAt == modifiedAt && previous.sizeBytes == sizeBytes &&
                        previous.probedDocumentName == name && previous.folder == folderName
                    ) {
                        result += if (previous.mimeType == contentType) previous else previous.copy(mimeType = contentType)
                        continue
                    }
                    val tag = LocalTagReader.read(context, address)
                    currentCoroutineContext().ensureActive()
                    val stem = name.substringBeforeLast('.')
                    val (title, artist) = restoreFromFileName(tag?.title?.ifBlank { null } ?: stem, tag?.artist.orEmpty(), stem)
                    result += LocalSong(
                        id = LocalMediaStore.idForUri(address, idsByUri[address]),
                        uri = address, title = title, artist = artist,
                        album = cleanFolderAlbum(tag?.album.orEmpty(), folderName),
                        durationMs = tag?.durationMs ?: 0L, sizeBytes = sizeBytes,
                        mimeType = contentType,
                        sampleRate = tag?.sampleRate ?: 0, bitrate = tag?.bitrate ?: 0,
                        bitDepth = tag?.bitDepth ?: -1,
                        modifiedAt = modifiedAt, addedAt = modifiedAt, year = tag?.year ?: 0,
                        folder = folderName,
                        probedDocumentName = name.takeIf { tag != null && it.isNotBlank() },
                    )
                }
            }
        }
        return SourceIndex(result, observed)
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

internal suspend fun enrichLocalSpecifications(
    baseline: List<LocalSong>,
    supportsSampleRate: Boolean,
    readTag: (LocalSong) -> LocalTagReader.Tag?,
    commit: (List<LocalSong>) -> Unit,
) {
    val caller = currentCoroutineContext()
    var scanned: MutableList<LocalSong>? = null
    try {
        baseline.forEachIndexed { index, song ->
            caller.ensureActive()
            if (song.bitrate > 0 && (!supportsSampleRate || song.sampleRate > 0)) return@forEachIndexed
            val tag = readTag(song)
            caller.ensureActive()
            if (tag == null || tag.sampleRate <= 0 && tag.bitrate <= 0) return@forEachIndexed
            val updated = song.copy(
                sampleRate = tag.sampleRate.takeIf { it > 0 } ?: song.sampleRate,
                bitrate = tag.bitrate.takeIf { it > 0 } ?: song.bitrate,
                bitDepth = tag.bitDepth.takeIf { it > 0 } ?: song.bitDepth,
            )
            if (updated != song) {
                val rows = scanned ?: baseline.toMutableList().also { scanned = it }
                rows[index] = updated
            }
        }
    } finally {
        // 已完成的规格补全可安全提交，扫描前的文件身份仍由索引层做三方校验。
        scanned?.let(commit)
    }
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
        channelCount = if (scanned.channelCount > 0 || !scanned.sameFileVersion(previous)) {
            scanned.channelCount
        } else previous.channelCount,
        year = if (scanned.year > 0) scanned.year else previous.year,
        coverUri = scanned.coverUri ?: previous.coverUri,
        infoFilled = previous.infoFilled,
    )
}

/** 新物理版本替换旧版本补全；同版本并发字段修改及已提交的新物理版本优先。 */
internal fun mergeScannedSong(
    scanned: LocalSong,
    baseline: LocalSong?,
    current: LocalSong?,
): LocalSong {
    if (current == null) return preserveLocalEnrichment(scanned, baseline)
    if (baseline == null) return current
    if (baseline.sameFileVersion(current) && !baseline.sameFileVersion(scanned)) return scanned
    if (current == baseline) return preserveLocalEnrichment(scanned, baseline)
    // 当前条目已经被并发更新；即使 stamp 相同，也不能让扫描值复写 artist/cover 的删除。
    return current
}
