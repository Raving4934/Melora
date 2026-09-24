package com.leyu.melora.playback

import android.content.Context
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SingleFlight
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

enum class LyricSourceMode(val storageValue: String) {
    Auto("auto"), Embedded("embedded"), Matched("matched");

    companion object {
        fun restore(value: String?): LyricSourceMode = entries.firstOrNull { it.storageValue == value } ?: Auto
    }
}

/** 唯一渐进加载链路：先显示已有歌词，匹配结果只更新缓存；物理标签写入由用户单独确认。 */
object LyricRepository {
    private val cache = LyricCacheStore()
    private val misses = mutableMapOf<String, Long>()
    private const val MISS_COOLDOWN_MS = 30 * 60 * 1000L

    private fun choices(context: Context) = LyricChoiceStore(File(context.filesDir, "lyric_choices"))

    fun sourceMode(context: Context, uid: String): LyricSourceMode = choices(context).read(uid).mode

    suspend fun chooseSource(context: Context, uid: String, mode: LyricSourceMode, selected: PlayerLyric? = null) =
        withContext(Dispatchers.IO) { choices(context.applicationContext).write(uid, mode, selected) }

    fun observe(context: Context, track: UiTrack, background: Boolean = false): Flow<PlayerLyric?> = flow {
        val app = context.applicationContext
        val local = LocalMediaStore.matchTrack(track)
        val choice = if (local == null) LyricChoice() else choices(app).read(track.uid)
        val mode = choice.mode
        val embedded = local?.let { readEmbedded(app, track, it) }
        if (mode != LyricSourceMode.Auto || embedded?.lines?.any { it.words.isNotEmpty() } == true) {
            emit(chooseLyric(track, embedded, choice.lyric, mode))
            return@flow
        }
        val version = matchedVersion(app, local)
        val cached = cache.peek(File(app.cacheDir, "lyrics"), track.uid, version)
        val initial = chooseLyric(track, embedded, cached, mode)
        if (initial != null) emit(initial)
        val matched = recoverableOrNull {
            candidate(app, track, background = background || (local != null && mode == LyricSourceMode.Auto && embedded != null))
        }
        val resolved = chooseLyric(track, embedded, matched ?: cached, mode)
        if (resolved != null || initial == null) emit(resolved)
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun embedded(context: Context, track: UiTrack): PlayerLyric? = withContext(Dispatchers.IO) {
        LocalMediaStore.matchTrack(track)?.let { readEmbedded(context.applicationContext, track, it) }
    }

    /** 手动重新查找也经过同一缓存/请求合并；失败保留旧候选，不清除音乐文件或其它曲目。 */
    suspend fun candidate(context: Context, track: UiTrack, force: Boolean = false, background: Boolean = false): PlayerLyric? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val local = LocalMediaStore.matchTrack(track)
            val version = matchedVersion(app, local)
            val key = lyricCacheKey(track.uid, version)
            val now = System.currentTimeMillis()
            val cooling = synchronized(misses) {
                misses.entries.removeAll { now >= it.value }
                if (force) misses.remove(key)
                misses.containsKey(key)
            }
            val directory = File(app.cacheDir, "lyrics")
            if (cooling) return@withContext cache.peek(directory, track.uid, version)
            val result = recoverableOrNull {
                cache.load(directory, track.uid, version, force = force) {
                    val song = local?.toOnlineSong() ?: OnlineSong.from(track.raw)
                    val remote = song?.let { SourceResolver.lyric(app, it, background, preferWordTimings = local != null, force = force) }
                    val lines = remote?.let { LyricParser.parse(it.lyric, it.tlyric, it.rlyric, wordByWord = it.lxlyric) }.orEmpty()
                    if (lines.isEmpty()) null else PlayerLyric(track.uid,
                        remote?.song?.name ?: track.title, remote?.song?.singer ?: track.artist, lines, remote!!.source)
                }
            }
            if (result == null) synchronized(misses) {
                if (misses.size >= 128) misses.remove(misses.keys.first())
                misses[key] = now + MISS_COOLDOWN_MS
            }
            result
        }

    suspend fun prefetch(context: Context, track: UiTrack) {
        recoverableOrNull { observe(context, track, background = true).collect {} }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        synchronized(misses) { misses.clear() }
        cache.clear(File(context.cacheDir, "lyrics"))
    }

    private fun readEmbedded(context: Context, track: UiTrack, local: LocalSong): PlayerLyric? {
        val lines = LocalTagReader.embeddedLyrics(context, local.uri, local.mimeType)?.parse().orEmpty()
        return lines.takeIf { it.isNotEmpty() }?.let { PlayerLyric(track.uid, track.title, track.artist, it, "本地文件") }
    }

    private fun matchedVersion(context: Context, local: LocalSong?): String {
        if (local == null) return ""
        val uri = local.uri.toUri()
        var modified = local.modifiedAt
        var size = local.sizeBytes
        if (uri.scheme == "file") {
            runCatching { File(java.net.URI(local.uri)) }.getOrNull()?.let { modified = it.lastModified(); size = it.length() }
        } else if (uri.scheme == "content") {
            val columns = if (uri.authority == MediaStore.AUTHORITY)
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
            else arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
            runCatching {
                context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) modified = cursor.getLong(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
            }
        }
        return "${local.uri}:${local.modifiedAt}:${local.sizeBytes}:$modified:$size:matched\u0000${local.title}\u0000${local.artist}\u0000${local.album}\u0000${local.durationMs}"
    }
}

/** 自动模式只叠加已确认的逐字时间；明确手选匹配歌词后才允许替换本地正文。 */
internal fun chooseLyric(track: UiTrack, embedded: PlayerLyric?, matched: PlayerLyric?, mode: LyricSourceMode): PlayerLyric? = when (mode) {
    LyricSourceMode.Embedded -> embedded
    LyricSourceMode.Matched -> matched ?: embedded
    LyricSourceMode.Auto -> when {
        embedded == null -> matched
        matched == null -> embedded
        else -> {
            val enriched = LyricParser.enrich(embedded.lines, matched.lines)
            if (enriched == embedded.lines) embedded
            else PlayerLyric(track.uid, track.title, track.artist, enriched, "本地歌词 · ${matched.source}逐字")
        }
    }
}

/** 同一把锁只在IO线程保护缓存提交/删除；网络请求在锁外，清理代次阻断旧结果回填。 */
internal class LyricCacheStore(
    private val maxEntries: Int = 64,
    private val maxMemoryBytes: Long = 4L * 1024 * 1024,
    private val maxDiskFiles: Int = 500,
    private val maxDiskBytes: Long = 16L * 1024 * 1024,
    private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val lyric: PlayerLyric, val savedAt: Long, val bytes: Long, val cacheVersion: Int)
    private data class Result(val lyric: PlayerLyric?)
    private data class DiskEntry(val file: File, val bytes: Long, val modified: Long, val obsolete: Boolean)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var memoryBytes = 0L
    @Volatile private var epoch = 0L
    private val flights = SingleFlight<String, Result>(CoroutineScope(SupervisorJob() + Dispatchers.IO), lock)

    // 调用方可在切换IO线程前取代次，不争用磁盘提交锁。
    fun generation(): Long = epoch

    suspend fun peek(directory: File, uid: String, version: String = ""): PlayerLyric? = withContext(Dispatchers.IO) {
        synchronized(lock) { cached(directory, lyricCacheKey(uid, version), uid, version)?.lyric }
    }

    suspend fun load(directory: File, uid: String, version: String = "", generation: Long = generation(),
                     force: Boolean = false, fetch: suspend () -> PlayerLyric?): PlayerLyric? {
        val key = lyricCacheKey(uid, version)
        val requestGeneration = generation
        return flights.run(key, requestGeneration, restart = force) {
            withContext(Dispatchers.IO) {
                val producer = currentCoroutineContext()
                val cached = synchronized(lock) {
                    checkGeneration(requestGeneration)
                    cached(directory, key, uid, version)
                }
                if (!force && cached != null && cached.cacheVersion == CACHE_VERSION && fresh(cached.savedAt)) {
                    return@withContext Result(cached.lyric)
                }
                val lyric = try { fetch() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (cached == null) throw error else null }
                // 到期刷新失败保留同一文件版本的旧歌词，不把断网变成歌词突然消失。
                if (lyric == null) return@withContext Result(cached?.lyric)
                require(lyric.uid == uid) { "歌词身份与请求不一致" }
                val savedAt = now()
                val text = encodePlayerLyric(lyric).put("cacheVersion", CACHE_VERSION).put("fileVersion", version)
                    .put("savedAt", savedAt).toString()
                synchronized(lock) {
                    producer.ensureActive()
                    checkGeneration(requestGeneration)
                    // 超大歌词仍可显示，但不占满缓存或挤掉所有常用歌词。
                    val bytes = text.toByteArray()
                    if (bytes.size <= MAX_FILE_BYTES) {
                        put(key, Entry(lyric, savedAt, text.length * 2L, CACHE_VERSION))
                        try { write(directory, key, bytes) } catch (_: IOException) { /* 磁盘不可用不影响歌词显示 */ }
                    }
                }
                Result(lyric)
            }
        }.lyric
    }

    fun clear(directory: File) = synchronized(lock) {
        epoch = flights.fence()
        entries.clear()
        memoryBytes = 0
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("部分歌词缓存无法删除，请重试")
    }

    /** 调用方持有lock；peek和load共用同一磁盘/内存读取与LRU路径。 */
    private fun cached(directory: File, key: String, uid: String, version: String): Entry? =
        (entries[key] ?: read(directory, key, uid, version)?.also { put(key, it) })
            ?.also { File(directory, "$key.json").setLastModified(now()) }

    private fun checkGeneration(expected: Long) {
        if (expected != generation()) throw CancellationException("歌词缓存已清理")
    }

    private fun fresh(savedAt: Long): Boolean = now() - savedAt in 0..ttlMs

    private fun put(key: String, entry: Entry) {
        entries.remove(key)?.let { memoryBytes -= it.bytes }
        if (entry.bytes > maxMemoryBytes) return
        entries[key] = entry
        memoryBytes += entry.bytes
        while (entries.size > maxEntries || memoryBytes > maxMemoryBytes) {
            val eldest = entries.entries.iterator()
            memoryBytes -= eldest.next().value.bytes
            eldest.remove()
        }
    }

    private fun read(directory: File, key: String, uid: String, version: String): Entry? {
        val file = File(directory, "$key.json")
        if (!file.isFile) return null
        val entry = try {
            if (file.length() > MAX_FILE_BYTES) null else {
                val text = file.readText()
                val obj = JSONObject(text)
                val savedAt = obj.optLong("savedAt", -1)
                val cacheVersion = obj.optInt("cacheVersion", -1)
                if ((cacheVersion != 2 && cacheVersion != CACHE_VERSION) || obj.optString("uid") != uid ||
                    obj.optString("fileVersion") != version || savedAt < 0) null
                else decodePlayerLyric(obj)?.let { Entry(it, savedAt, text.length * 2L, cacheVersion) }
            }
        } catch (_: Exception) { null }
        if (entry == null) file.delete()
        return entry
    }

    private fun write(directory: File, key: String, bytes: ByteArray) {
        writeLyricFile(directory, key, bytes).setLastModified(now())
        // 一次读取类型、大小与修改时间；坏条目不阻断其余文件的容量回收。
        val files = directory.listFiles()?.mapNotNull { file ->
            try {
                val stat = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                if (stat.isRegularFile) DiskEntry(file, stat.size(), stat.lastModifiedTime().toMillis(), !CACHE_FILE.matches(file.name)) else null
            } catch (_: IOException) { null }
        }.orEmpty()
        var diskBytes = files.sumOf { it.bytes }
        var count = files.size
        if (count <= maxDiskFiles && diskBytes <= maxDiskBytes && files.none { it.obsolete }) return
        for (entry in files.sortedBy { it.modified }) {
            // 旧命名缓存只回收，不保留双套协议；保持原有mtime淘汰次序。
            if (!entry.obsolete && count <= maxDiskFiles && diskBytes <= maxDiskBytes) continue
            if (entry.file.delete()) { count--; diskBytes -= entry.bytes }
        }
    }

    private companion object {
        const val CACHE_VERSION = 3
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        val CACHE_FILE = Regex("[a-f0-9]{64}\\.json")
    }
}

/** 用户确认的歌词是持久化选择，不随自动缓存过期、重查或清理而被替换。 */
internal data class LyricChoice(val mode: LyricSourceMode = LyricSourceMode.Auto, val lyric: PlayerLyric? = null)

internal class LyricChoiceStore(private val directory: File) {
    fun read(uid: String): LyricChoice = try {
        val file = File(directory, "${lyricCacheKey(uid, "")}.json")
        if (!file.isFile) LyricChoice() else {
            val value = JSONObject(file.readText())
            val mode = LyricSourceMode.restore(value.optString("mode"))
            val lyric = value.optJSONObject("lyric")?.let { decodePlayerLyric(it) }
                ?.takeIf { it.uid == uid && it.lines.any { line -> line.words.isNotEmpty() } }
            if (mode == LyricSourceMode.Matched && lyric == null) LyricChoice()
            else LyricChoice(mode, if (mode == LyricSourceMode.Matched) lyric else null)
        }
    } catch (_: Exception) { LyricChoice() }

    fun write(uid: String, mode: LyricSourceMode, selected: PlayerLyric? = null) {
        val key = lyricCacheKey(uid, "")
        if (mode == LyricSourceMode.Auto) {
            val file = File(directory, "$key.json")
            if (file.exists() && !file.delete()) throw IOException("无法清除歌词选择")
            return
        }
        val value = JSONObject().put("mode", mode.storageValue)
        if (mode == LyricSourceMode.Matched) {
            require(selected?.uid == uid && selected.lines.any { it.words.isNotEmpty() }) { "请选择当前曲目的有效逐字歌词" }
            value.put("lyric", encodePlayerLyric(selected))
        }
        writeLyricFile(directory, key, value.toString().toByteArray())
    }
}

private fun writeLyricFile(directory: File, key: String, bytes: ByteArray): File {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建歌词目录")
    val target = File(directory, "$key.json")
    val temp = File.createTempFile("lyric-", ".tmp", directory)
    try {
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { temp.delete() }
    return target
}

internal fun lyricCacheKey(uid: String, version: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$uid\u0000$version".toByteArray()).toHexString()

/** 磁盘缓存编码集中在边界，避免业务模型携带旧字段兼容逻辑。 */
internal fun encodePlayerLyric(lyric: PlayerLyric): JSONObject {
    val lines = JSONArray()
    lyric.lines.forEach { line ->
        val words = JSONArray()
        line.words.forEach { word ->
            words.put(
                JSONObject()
                    .put("text", word.text)
                    .put("startMs", word.startMs)
                    .put("endMs", word.endMs),
            )
        }
        lines.put(
            JSONObject()
                .put("startMs", line.startMs)
                .put("text", line.text)
                .put("translation", line.translation ?: JSONObject.NULL)
                .put("endMs", line.endMs ?: JSONObject.NULL)
                .put("words", words)
                .put("romanization", line.romanization ?: JSONObject.NULL)
                .put("alignment", line.alignment.name)
                .put("isBackground", line.isBackground),
        )
    }
    return JSONObject()
        .put("uid", lyric.uid)
        .put("title", lyric.title)
        .put("artist", lyric.artist)
        .put("source", lyric.source)
        .put("lines", lines)
}

/**
 * 读取缓存边界：新字段优先，旧 `timeMs` 仅在这里回退；翻译-only 行必须保留。
 */
internal fun decodePlayerLyric(obj: JSONObject, fallbackUid: String = ""): PlayerLyric? {
    val array = obj.optJSONArray("lines") ?: return null
    val lines = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val startMs = optionalLong(item, "startMs")
                ?: optionalLong(item, "timeMs")
                ?: continue
            val text = item.optString("text", "")
            val translation = optionalText(item, "translation")
            val endMs = optionalLong(item, "endMs")
            val words = decodeWords(item.optJSONArray("words")).takeIf { words ->
                words.all { it.endMs >= it.startMs } && words.joinToString("") { it.text } == text
            }.orEmpty()
            val romanization = optionalText(item, "romanization")
            val alignment = when (item.optString("alignment").trim().lowercase()) {
                "end" -> LyricAlignment.End
                else -> LyricAlignment.Start
            }
            val isBackground = item.optBoolean("isBackground", false)
            if (text.isBlank() && translation.isNullOrBlank() && romanization.isNullOrBlank() && words.isEmpty()) {
                continue
            }
            add(
                LyricLine(
                    startMs = startMs,
                    text = text,
                    translation = translation,
                    endMs = endMs,
                    words = words,
                    romanization = romanization,
                    alignment = alignment,
                    isBackground = isBackground,
                ),
            )
        }
    }
    if (lines.isEmpty()) return null
    return PlayerLyric(
        uid = obj.optString("uid", fallbackUid).ifBlank { fallbackUid },
        title = obj.optString("title", ""),
        artist = obj.optString("artist", ""),
        lines = lines.sortedBy { it.startMs },
        source = obj.optString("source", ""),
    )
}

private fun decodeWords(array: JSONArray?): List<LyricWord> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val startMs = optionalLong(item, "startMs") ?: continue
            val endMs = optionalLong(item, "endMs") ?: continue
            add(LyricWord(item.optString("text", ""), startMs, endMs))
        }
    }
}

private fun optionalLong(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val value = obj.opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun optionalText(obj: JSONObject, key: String): String? =
    if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key, "").takeIf { it.isNotBlank() }

internal suspend fun <T> recoverableOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
