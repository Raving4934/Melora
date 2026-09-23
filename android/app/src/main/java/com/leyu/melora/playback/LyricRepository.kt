package com.leyu.melora.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SingleFlight
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 歌词唯一缓存入口：按正在访问的歌曲验证版本，不扫描媒体库、不阻塞音频播放。 */
object LyricRepository {
    private val cache = LyricCacheStore()

    suspend fun load(context: Context, track: UiTrack, background: Boolean = false): PlayerLyric? {
        val generation = cache.generation()
        return withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val local = LocalMediaStore.matchTrack(track)
            val file = local?.uri?.takeIf { it.startsWith("file:") }?.let {
                runCatching { File(java.net.URI(it)) }.getOrNull()
            }
            val version = local?.let {
                var modified = it.modifiedAt
                var size = it.sizeBytes
                val uri = Uri.parse(it.uri)
                if (file != null) { modified = file.lastModified(); size = file.length() }
                else if (uri.scheme == "content") {
                    val columns = if (uri.authority == MediaStore.AUTHORITY)
                        arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
                    else arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
                    runCatching {
                        app.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                if (!cursor.isNull(0)) modified = cursor.getLong(0)
                                if (!cursor.isNull(1)) size = cursor.getLong(1)
                            }
                        }
                    }
                }
                "${it.uri}:${it.modifiedAt}:${it.sizeBytes}:$modified:$size"
            }.orEmpty()
            cache.load(File(app.cacheDir, "lyrics"), track.uid, version, generation) {
                val embedded = local?.let { LocalTagReader.embeddedLyrics(app, it.uri, it.mimeType) }
                val embeddedLines = embedded?.takeIf(String::isNotBlank)?.let(LyricParser::parse).orEmpty()
                if (embeddedLines.isNotEmpty()) {
                    PlayerLyric(track.uid, track.title, track.artist, embeddedLines, "本地文件")
                } else {
                    val song = OnlineSong.from(track.raw)
                    val result = song?.let { SourceResolver.lyric(app, it, background) }
                    val lines = result?.let { LyricParser.parse(it.lyric, it.tlyric, it.rlyric) }.orEmpty()
                    if (lines.isEmpty()) null else PlayerLyric(track.uid, track.title, track.artist, lines, result!!.source)
                }
            }
        }
    }

    /** 前台读取与预取共享同一请求；没有歌词的失败结果不会永久缓存。 */
    suspend fun prefetch(context: Context, track: UiTrack) {
        recoverableOrNull { load(context, track, background = true) }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        cache.clear(File(context.cacheDir, "lyrics"))
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
    private data class Entry(val lyric: PlayerLyric, val savedAt: Long, val bytes: Long)
    private data class Result(val lyric: PlayerLyric?)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var memoryBytes = 0L
    private val flights = SingleFlight<String, Result>(CoroutineScope(SupervisorJob() + Dispatchers.IO), lock)

    fun generation(): Long = flights.currentGeneration()

    suspend fun load(directory: File, uid: String, version: String = "", generation: Long = generation(),
                     fetch: suspend () -> PlayerLyric?): PlayerLyric? {
        val key = lyricCacheKey(uid, version)
        return flights.run(key, generation) {
            withContext(Dispatchers.IO) {
                val cached = synchronized(lock) {
                    checkGeneration(generation)
                    (entries[key] ?: read(directory, key, uid, version)?.also { put(key, it) })
                        ?.also { File(directory, "$key.json").setLastModified(now()) }
                }
                if (cached != null && fresh(cached.savedAt)) return@withContext Result(cached.lyric)
                val lyric = try { fetch() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (cached == null) throw error else null }
                // 到期刷新失败保留同一文件版本的旧歌词，不把断网变成歌词突然消失。
                if (lyric == null) return@withContext Result(cached?.lyric)
                require(lyric.uid == uid) { "歌词身份与请求不一致" }
                val savedAt = now()
                val text = encodePlayerLyric(lyric).put("cacheVersion", 2).put("fileVersion", version)
                    .put("savedAt", savedAt).toString()
                synchronized(lock) {
                    checkGeneration(generation)
                    // 超大歌词仍可显示，但不占满缓存或挤掉所有常用歌词。
                    val bytes = text.toByteArray()
                    if (bytes.size <= MAX_FILE_BYTES) {
                        put(key, Entry(lyric, savedAt, text.length * 2L))
                        try { write(directory, key, bytes) } catch (_: IOException) { /* 磁盘不可用不影响歌词显示 */ }
                    }
                }
                Result(lyric)
            }
        }.lyric
    }

    fun clear(directory: File) = synchronized(lock) {
        flights.fence()
        entries.clear()
        memoryBytes = 0
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("部分歌词缓存无法删除，请重试")
    }

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
                if (obj.optInt("cacheVersion") != 2 || obj.optString("uid") != uid ||
                    obj.optString("fileVersion") != version || savedAt < 0) null
                else decodePlayerLyric(obj)?.let { Entry(it, savedAt, text.length * 2L) }
            }
        } catch (_: Exception) { null }
        if (entry == null) file.delete() else file.setLastModified(now())
        return entry
    }

    private fun write(directory: File, key: String, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建歌词缓存目录")
        val temp = File.createTempFile("lyric-", ".tmp", directory)
        try {
            temp.writeBytes(bytes)
            val target = File(directory, "$key.json").toPath()
            try {
                Files.move(temp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
            target.toFile().setLastModified(now())
        } finally { temp.delete() }
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var diskBytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            // 旧命名缓存不再读取，首次维护时直接回收；不保留双套缓存协议。
            val obsolete = !file.name.matches(Regex("[a-f0-9]{64}\\.json"))
            if (!obsolete && count <= maxDiskFiles && diskBytes <= maxDiskBytes) continue
            val size = file.length()
            if (file.delete()) { count--; diskBytes -= size }
        }
    }

    private companion object { const val MAX_FILE_BYTES = 2L * 1024 * 1024 }
}

internal fun lyricCacheKey(uid: String, version: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$uid\u0000$version".toByteArray()).joinToString("") { "%02x".format(it) }

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
