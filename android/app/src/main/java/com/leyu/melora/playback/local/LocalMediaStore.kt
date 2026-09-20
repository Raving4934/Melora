package com.leyu.melora.playback.local

import android.content.Context
import android.net.Uri
import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地媒体内存索引：
 * - 播放时按标题/歌手/时长匹配（纯内存快照查询，不影响网络源解析速度）
 * - source=local 曲目按 id 反查真实文件
 * - 索引原子持久化到 filesDir/local_media.json
 */
object LocalMediaStore {
    private const val FILE_NAME = "local_media.json"
    private const val TEMP_FILE_NAME = "$FILE_NAME.tmp"
    private const val DURATION_TOLERANCE_MS = 5_000L
    private val lock = Any()
    private val audioSpecificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _songs = MutableStateFlow<List<LocalSong>>(emptyList())
    val songs: StateFlow<List<LocalSong>> = _songs.asStateFlow()

    private val scanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = scanning.asStateFlow()

    @Volatile private var byUri: Map<String, LocalSong> = emptyMap()
    @Volatile private var byId: Map<String, LocalSong> = emptyMap()
    @Volatile private var byMatch: Map<String, LocalSong> = emptyMap()
    @Volatile private var byTitle: Map<String, List<LocalSong>> = emptyMap()
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val loaded = readIndex(File(app.filesDir, FILE_NAME))
        synchronized(lock) {
            applyIndex(loaded)
            // 旧版本已落盘的重复ID在首屏发布前修复，只改索引，不删除媒体文件。
            if (_songs.value != loaded) persist(_songs.value)
        }
        UserLibrary.refreshLocalSongs()
    }

    fun setScanning(value: Boolean) { scanning.value = value }

    internal suspend fun <T> withScanning(block: suspend () -> T): T {
        setScanning(true)
        return try {
            block()
        } finally {
            setScanning(false)
        }
    }

    /** 扫描结果整体替换索引并持久化。 */
    fun replaceAll(list: List<LocalSong>) { mutate(pruneMissing = true) { list } }

    fun removeIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        mutate(pruneMissing = true) { current -> current.filterNot { it.id in ids } }
    }

    /**
     * 下载完成且真实 URI 已通过访问校验后，将这一个文件增量写入本地索引。
     * 不扫描目录，也不替换其它本地文件；同 URI 条目沿用原有 ID 与用户补全信息。
     * modifiedAt 由调用方传入真实目录时间；没有真实来源时保持为 0，不能用入库时间伪造。
     */
    fun registerDownloaded(
        song: OnlineSong,
        uri: String,
        spec: AudioSpecification,
        durationMs: Long,
        sizeBytes: Long,
        displayName: String,
        modifiedAt: Long = 0L,
    ): LocalSong = synchronized(lock) {
        require(uri.isNotBlank()) { "下载文件 URI 不能为空" }
        val current = _songs.value
        val existing = findByUri(uri)
        val registered = buildDownloadedSong(
            song = song,
            uri = uri,
            spec = spec,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            displayName = displayName,
            modifiedAt = modifiedAt,
            existing = existing,
        )
        val updated = if (existing == null) {
            current + registered
        } else {
            current.map { if (it.id == existing.id) registered else it }
        }
        commitLocked(updated, pruneMissing = false)
        registered
    }

    /** 标签补全只更新描述/文件时间戳，不覆盖播放期间已确认的物理音频规格。 */
    fun updateMetadata(song: LocalSong) {
        mutate { current ->
            val index = current.indexOfFirst { it.id == song.id }
            if (index < 0 || current[index].uri != song.uri) return@mutate current
            val latest = current[index]
            val updated = song.copy(mimeType = latest.mimeType, sampleRate = latest.sampleRate,
                bitrate = latest.bitrate, bitDepth = latest.bitDepth)
            if (latest == updated) current else current.toMutableList().also { it[index] = updated }
        }
    }

    /**
     * 由播放层在 resourceId=localmedia 且已识别实际音轨后调用。
     * observed 是数据源实际选中的不可变本地文件快照，不重新按标题/歌手匹配。
     * 索引更新与 JSON 持久化全部在 IO 协程中执行，不阻塞点播或主线程。
     * 返回的 Job 仅用于需要等待索引刷新（例如测试）的调用方，普通播放调用可直接忽略。
     */
    fun recordAudioSpecification(observed: LocalSong, spec: AudioSpecification): Job {
        val observedId = observed.id
        val observedUri = observed.uri
        val observedModifiedAt = observed.modifiedAt
        val observedSizeBytes = observed.sizeBytes
        return audioSpecificationScope.launch {
            mutate { current ->
                val index = current.indexOfFirst { it.id == observedId }
                if (index < 0) return@mutate current
                val currentSong = current[index]
                // 只允许把这次播放识别到的规格写回同一个文件；删除/替换后的条目不接受旧播放结果。
                if (currentSong.uri != observedUri ||
                    currentSong.modifiedAt != observedModifiedAt ||
                    currentSong.sizeBytes != observedSizeBytes
                ) return@mutate current

                val measuredMime = spec.mimeType?.trim()?.takeIf { it.isNotEmpty() }
                val updated = currentSong.copy(
                    // 真实输入格式纠正过时索引；raw仅保留已识别无损容器，不能保留错标的MP3。
                    mimeType = if (measuredMime == "audio/raw" && currentSong.isLossless) {
                        currentSong.mimeType
                    } else measuredMime ?: currentSong.mimeType,
                    sampleRate = spec.sampleRate.takeIf { it > 0 } ?: currentSong.sampleRate,
                    bitrate = spec.bitrate.takeIf { it > 0 } ?: currentSong.bitrate,
                    bitDepth = spec.bitDepth.takeIf { it > 0 } ?: currentSong.bitDepth,
                )
                if (updated == currentSong) current else current.toMutableList().also { it[index] = updated }
            }
        }
    }

    fun clear() { mutate(pruneMissing = true) { emptyList() } }

    /** 在索引锁内读取当前完整快照，集合刷新不能使用调用方提前捕获的旧 map。 */
    internal fun <T> withIndexLock(block: (List<LocalSong>) -> T): T = synchronized(lock) {
        block(_songs.value)
    }

    /** 捕获一次扫描/补全使用的索引基线；提交时会在同一锁内与当前索引三方合并。 */
    internal fun snapshot(): List<LocalSong> = synchronized(lock) { _songs.value }

    /**
     * 在索引锁内合并 baseline、单次 IO 得到的 scanned 与当前索引：
     * - baseline 中存在、当前已删除的条目不重新加入；
     * - 当前相对 baseline 的更新优先保留；
     * - 扫描开始后新增的当前条目和本次扫描新发现的条目都保留。
     * 读取失败的来源由扫描器回填其 baseline 条目，因此不会被误判为空来源而清除。
     */
    internal fun commitScanned(
        baseline: List<LocalSong>,
        scanned: List<LocalSong>,
    ): Int = synchronized(lock) {
        val baselineById = baseline.associateBy(LocalSong::id)
        val scannedById = scanned.associateBy(LocalSong::id)
        val current = _songs.value
        val currentById = current.associateBy(LocalSong::id)
        val merged = buildList(scanned.size + current.size) {
            scanned.forEach { scannedSong ->
                val baselineSong = baselineById[scannedSong.id]
                val currentSong = currentById[scannedSong.id]
                if (currentSong == null && baselineSong != null) return@forEach
                add(mergeScannedSong(scannedSong, baselineSong, currentSong))
            }
            current.forEach { currentSong ->
                if (scannedById.containsKey(currentSong.id)) return@forEach
                val baselineSong = baselineById[currentSong.id]
                if (baselineSong == null || currentSong != baselineSong) add(currentSong)
            }
        }
        commitLocked(merged, pruneMissing = true)
        scanned.size
    }

    /** 清除缓存目录后同步移除只指向该目录的封面地址，避免持久化失效 file URI。 */
    fun invalidateCachedCovers(directory: File): Boolean {
        val root = directory.absolutePath.trimEnd(File.separatorChar) + File.separator
        var changed = false
        mutate { current ->
            current.map { song ->
                val path = song.coverUri?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path
                if (path != null && File(path).absolutePath.startsWith(root)) {
                    changed = true
                    song.copy(coverUri = null, infoFilled = false)
                } else {
                    song
                }
            }
        }
        return changed
    }

    val count: Int get() = _songs.value.size

    fun find(id: String): LocalSong? = byId[id]

    // MediaStore的external和external_primary可指向同一媒体ID，不能按地址字符串拆成两首。
    fun findByUri(uri: String): LocalSong? = byUri[uri]
        ?: byId[downloadedId(uri)]?.takeIf { it.id.startsWith("ms_") }

    fun match(title: String, artist: String): LocalSong? = byMatch[LocalSong.matchKeyOf(title, artist)]

    /**
     * 只在两边的音频规格都能给出明确结论时返回 true。
     * 未知规格、不同压缩编码或同档位不作猜测，下载记录继续保持优先。
     */
    internal fun isHigherQuality(local: LocalSong, downloaded: AudioSpecification): Boolean =
        compareAudioSpecifications(local.audioSpecification, downloaded) > 0

    /** 网络曲目 → 本地文件：标题/歌手候选还需通过时长校验，避免 Live/Remix 被归一化后误播。 */
    fun matchSong(song: OnlineSong): LocalSong? {
        if (song.source == LocalSong.SOURCE) return byId[song.songmid]
        val candidates = byTitle[LocalSong.titleKeyOf(song.name)].orEmpty()
            .filter { SourceResolver.sameRecordingVersion(song, it.toOnlineSong()) }
        if (candidates.isEmpty()) return null
        val durationMs = song.intervalSeconds.takeIf { it > 0 }?.times(1_000L)
        val artistKey = LocalSong.artistKeyOf(song.singer)
        val exactArtist = candidates.filter { local ->
            val localArtist = LocalSong.artistKeyOf(local.artist)
            artistKey.isNotBlank() && localArtist.isNotBlank() && localArtist == artistKey
        }
        bestCandidate(exactArtist, durationMs)?.let { return it }
        if (durationMs == null) return null
        return bestCandidate(
            candidates.filter { local ->
                val localArtist = LocalSong.artistKeyOf(local.artist)
                (localArtist.isBlank() || artistKey.isBlank()) && local.durationMs > 0
            },
            durationMs,
            allowUnknownDuration = false,
        )
    }

    private fun bestCandidate(
        candidates: List<LocalSong>,
        durationMs: Long?,
        allowUnknownDuration: Boolean = true,
    ): LocalSong? {
        val viable = candidates.filter { local ->
            durationMs == null ||
                (allowUnknownDuration && local.durationMs <= 0L) ||
                (local.durationMs > 0L && abs(local.durationMs - durationMs) <= DURATION_TOLERANCE_MS)
        }
        val durationComparator = compareBy<LocalSong> { local ->
            when {
                durationMs == null -> 0L
                local.durationMs <= 0L -> -(DURATION_TOLERANCE_MS + 1L)
                else -> -abs(local.durationMs - durationMs)
            }
        }
        // 身份/版本和时长已过门槛：不能因编码尾部相差几十毫秒就让128K压过HR。
        // 已知且匹配的时长仍优先于完全未知的文件，然后按真实规格选优。
        return viable.maxWithOrNull(compareBy<LocalSong> { durationMs == null || it.durationMs > 0 }
            .then(localPreferenceComparator).then(durationComparator))
    }

    fun matchTrack(track: UiTrack): LocalSong? {
        if (track.source == LocalSong.SOURCE) return byId[track.uid.removePrefix("${LocalSong.SOURCE}_")]
        return OnlineSong.from(track.raw)?.let(::matchSong) ?: match(track.title, track.artist)
    }

    /** 歌曲是否已有本地文件：与实际播放使用完全相同的匹配规则。 */
    fun hasLocalFile(song: OnlineSong): Boolean =
        song.source == LocalSong.SOURCE || matchSong(song) != null

    private fun mutate(
        pruneMissing: Boolean = false,
        transform: (List<LocalSong>) -> List<LocalSong>,
    ): Boolean = synchronized(lock) {
        commitLocked(transform(_songs.value), pruneMissing)
    }

    private fun commitLocked(updated: List<LocalSong>, pruneMissing: Boolean): Boolean {
        val current = _songs.value
        if (updated === current || updated == current) return false
        applyIndex(updated)
        persist(_songs.value)
        UserLibrary.refreshLocalSongs(pruneMissing)
        return true
    }

    /** 先完整构造新映射再切换引用，播放线程不会撞见 clear/add 中间态。 */
    private fun applyIndex(list: List<LocalSong>) {
        val idIndex = LinkedHashMap<String, LocalSong>(list.size)
        list.forEach { song ->
            idIndex[song.id] = preserveLocalEnrichment(song, idIndex[song.id])
        }
        val unique = if (idIndex.size == list.size) list else idIndex.values.toList()
        val matchIndex = LinkedHashMap<String, LocalSong>(list.size)
        val titleIndex = LinkedHashMap<String, MutableList<LocalSong>>()
        unique.forEach { song ->
            val matchKey = song.matchKey()
            val existing = matchIndex[matchKey]
            if (existing == null || localPreferenceComparator.compare(song, existing) > 0) {
                matchIndex[matchKey] = song
            }
            titleIndex.getOrPut(LocalSong.titleKeyOf(song.title)) { mutableListOf() }.add(song)
        }
        byId = idIndex
        byUri = unique.associateBy(LocalSong::uri)
        byMatch = matchIndex
        byTitle = titleIndex
        _songs.value = unique
    }

    private fun readIndex(file: File): List<LocalSong> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("songs") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    LocalSong.fromJson(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun buildDownloadedSong(
        song: OnlineSong,
        uri: String,
        spec: AudioSpecification,
        durationMs: Long,
        sizeBytes: Long,
        displayName: String,
        modifiedAt: Long,
        existing: LocalSong?,
    ): LocalSong {
        val measuredMime = spec.mimeType?.trim()?.takeIf { it.isNotEmpty() }
        val displayMime = mimeTypeFromDisplayName(displayName)
        val mimeType = when {
            measuredMime == "audio/raw" -> displayMime ?: existing?.mimeType ?: measuredMime
            else -> measuredMime ?: displayMime ?: existing?.mimeType.orEmpty()
        }
        val title = song.name.trim().ifBlank {
            existing?.title?.takeIf { it.isNotBlank() }
                ?: displayName.substringBeforeLast('.', displayName).trim().ifBlank { "未知歌曲" }
        }
        val downloaded = LocalSong(
            id = existing?.id ?: downloadedId(uri),
            uri = uri,
            title = title,
            artist = song.singer.trim(),
            album = song.albumName.trim(),
            durationMs = durationMs.coerceAtLeast(0L),
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            mimeType = mimeType,
            sampleRate = spec.sampleRate.coerceAtLeast(0),
            bitrate = spec.bitrate.coerceAtLeast(0),
            modifiedAt = modifiedAt.coerceAtLeast(0L),
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            year = song.year ?: 0,
            folder = existing?.folder?.takeIf { it.isNotBlank() } ?: downloadedFolder(uri, displayName),
            coverUri = existing?.coverUri ?: song.img,
            infoFilled = existing?.infoFilled ?: false,
            bitDepth = spec.bitDepth.takeIf { it > 0 } ?: -1,
        )
        if (existing == null) return downloaded

        // 下载注册只刷新这个 URI 的物理文件信息；已有联网补全、年份和封面不能被新快照抹掉。
        return downloaded.copy(
            artist = existing.artist.ifBlank { downloaded.artist },
            album = existing.album.ifBlank { downloaded.album },
            year = existing.year.takeIf { it > 0 } ?: downloaded.year,
            coverUri = existing.coverUri,
            infoFilled = existing.infoFilled,
        )
    }

    private fun downloadedId(uri: String): String {
        val parsed = runCatching { java.net.URI(uri) }.getOrNull()
        val mediaId = parsed?.takeIf {
            it.authority == "media" && it.path?.substringBeforeLast('/')?.endsWith("/audio/media") == true
        }?.path?.substringAfterLast('/')?.toLongOrNull()
        return if (mediaId != null) "ms_$mediaId" else "doc_${uri.hashCode().toUInt().toString(16)}"
    }

    private fun mimeTypeFromDisplayName(displayName: String): String? = when (
        displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav", "wave" -> "audio/wav"
        "ape" -> "audio/ape"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "dsf", "dff", "dsd" -> "audio/dsd"
        else -> null
    }

    private fun downloadedFolder(uri: String, displayName: String): String {
        val parsed = runCatching { java.net.URI(uri) }.getOrNull()
        if (parsed?.authority == "media") return "Melora"
        val path = parsed?.path.orEmpty()
        return path.substringBeforeLast('/', "").substringAfterLast('/').substringAfter(':')
            .ifBlank { displayName.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "Melora" } }
    }

    private fun persist(snapshot: List<LocalSong>) {
        val context = appContext ?: return
        runCatching {
            val array = JSONArray()
            snapshot.forEach { array.put(it.toJson()) }
            val payload = JSONObject().apply {
                put("version", 1)
                put("savedAt", System.currentTimeMillis())
                put("songs", array)
            }.toString()
            val target = File(context.filesDir, FILE_NAME)
            val temp = File(context.filesDir, TEMP_FILE_NAME)
            temp.writeText(payload)
            if (!temp.renameTo(target)) {
                target.writeText(payload)
                temp.delete()
            }
        }
    }

    private enum class AudioKind { Compressed, Lossless, Dsd }

    private fun compareAudioSpecifications(left: AudioSpecification, right: AudioSpecification): Int {
        val leftKind = knownAudioKind(left) ?: return 0
        val rightKind = knownAudioKind(right) ?: return 0
        if (leftKind != rightKind) return leftKind.ordinal.compareTo(rightKind.ordinal)

        return when (leftKind) {
            AudioKind.Compressed -> {
                if (normalizedCodec(left.mimeType) != normalizedCodec(right.mimeType)) 0
                else compareKnownPositive(left.bitrate, right.bitrate)
            }
            AudioKind.Lossless -> {
                // 位深是无损音质的第一判据；缺任一值时不拿采样率猜测高低。
                if (left.bitDepth <= 0 || right.bitDepth <= 0) 0
                else compareKnownPositive(left.bitDepth, right.bitDepth).let { depth ->
                    if (depth != 0) depth else compareKnownPositive(left.sampleRate, right.sampleRate)
                }
            }
            AudioKind.Dsd -> compareKnownPositive(left.sampleRate, right.sampleRate)
        }
    }

    private fun knownAudioKind(spec: AudioSpecification): AudioKind? = when {
        spec.isDsd -> AudioKind.Dsd
        spec.isLossless -> AudioKind.Lossless
        spec.verifiedQuality != null -> AudioKind.Compressed
        else -> null
    }

    private fun normalizedCodec(mimeType: String?): String? = mimeType
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
        ?.let { mime ->
            when (mime) {
                "audio/mp3" -> "audio/mpeg"
                "audio/aac" -> "audio/mp4a-latm"
                else -> mime
            }
        }

    private fun compareKnownPositive(left: Int, right: Int): Int = when {
        left <= 0 || right <= 0 -> 0
        else -> left.compareTo(right)
    }

    private val localPreferenceComparator = compareBy<LocalSong>(
        { it.isMaster },
        { it.isLossless },
        // 同为无损时，实测位深优先于码率/采样率，避免 16bit/96k 压过 24bit/48k。
        { song -> if (song.isLossless) song.bitDepth.takeIf { it > 0 } ?: 0 else 0 },
        { it.bitrate },
        { it.sampleRate },
        { it.sizeBytes },
    )
}
