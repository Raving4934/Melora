package com.leyu.melora.playback

import android.content.Context
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** LRC 解析：支持多时间标签、翻译合并与 offset 调整。 */
object LrcParser {
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:([+-]?\\d+)]")

    fun parse(lyric: String, translation: String = ""): List<LyricLine> {
        val offset = offsetTag.find(lyric)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val primary = extract(lyric, offset)
        val secondary = extract(translation, offset)
        val times = (primary.keys + secondary.keys).sorted()
        return times.map { time ->
            LyricLine(
                timeMs = time,
                text = primary[time].orEmpty(),
                translation = secondary[time]?.takeIf { it.isNotBlank() && it != primary[time] },
            )
        }.filter { it.text.isNotBlank() || !it.translation.isNullOrBlank() }
    }

    private fun extract(raw: String, offset: Long): Map<Long, String> {
        val result = linkedMapOf<Long, MutableList<String>>()
        raw.lineSequence().forEach { line ->
            val matches = timeTag.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.replace(timeTag, "").trim()
            if (text.isBlank()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLongOrNull()?.times(100) ?: 0L
                    2 -> fractionRaw.toLongOrNull()?.times(10) ?: 0L
                    else -> fractionRaw.take(3).toLongOrNull() ?: 0L
                }
                val time = (minutes * 60 + seconds) * 1000 + millis - offset
                result.getOrPut(time.coerceAtLeast(0)) { mutableListOf() }.add(text)
            }
        }
        return result.mapValues { (_, texts) -> texts.distinct().joinToString("\n") }
    }
}

/** 歌词仓库：内存 → 磁盘 → 在线目录；支持下一首预取，切歌歌词秒现。 */
object LyricRepository {
    private val cache = ConcurrentHashMap<String, PlayerLyric>()
    private val prefetching = ConcurrentHashMap.newKeySet<String>()
    private const val MAX_DISK_FILES = 500

    suspend fun load(context: Context, track: UiTrack, background: Boolean = false): PlayerLyric? {
        cache[track.uid]?.let { return it }
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) { readFromDisk(appContext, track.uid) }?.let {
            cache[track.uid] = it
            return it
        }
        // 本地文件内嵌歌词优先：命中即返回，不再联网
        val localMatch = LocalMediaStore.matchTrack(track)
        if (localMatch != null) {
            val embedded = withContext(Dispatchers.IO) {
                LocalTagReader.embeddedLyrics(appContext, localMatch.uri, localMatch.mimeType)
            }
            if (!embedded.isNullOrBlank()) {
                val lines = LrcParser.parse(embedded)
                if (lines.isNotEmpty()) {
                    val lyric = PlayerLyric(
                        uid = track.uid,
                        title = track.title,
                        artist = track.artist,
                        lines = lines,
                        source = "本地文件",
                    )
                    cache[track.uid] = lyric
                    withContext(Dispatchers.IO) { saveToDisk(appContext, lyric) }
                    return lyric
                }
            }
        }
        val song = OnlineSong.from(track.raw) ?: return null
        val result = SourceResolver.lyric(appContext, song, background) ?: return null
        val lines = LrcParser.parse(result.lyric, result.tlyric)
        if (lines.isEmpty()) return null
        val lyric = PlayerLyric(
            uid = track.uid,
            title = track.title,
            artist = track.artist,
            lines = lines,
            source = result.source,
        )
        cache[track.uid] = lyric
        withContext(Dispatchers.IO) { saveToDisk(appContext, lyric) }
        return lyric
    }

    /** 预取队列曲目的歌词：后台低优执行，不阻塞当前播放。 */
    suspend fun prefetch(context: Context, track: UiTrack) {
        if (cache.containsKey(track.uid)) return
        if (!prefetching.add(track.uid)) return
        try {
            recoverableOrNull { load(context, track, background = true) }
        } finally {
            prefetching.remove(track.uid)
        }
    }

    fun cached(uid: String): PlayerLyric? = cache[uid]

    fun clear() {
        cache.clear()
    }

    // ---------- 磁盘缓存（cacheDir/lyrics，随“清除缓存”一起清掉） ----------

    private fun diskDir(context: Context): File? = runCatching {
        File(context.cacheDir, "lyrics").apply { if (!exists()) mkdirs() }
    }.getOrNull()

    private fun fileFor(context: Context, uid: String): File? =
        diskDir(context)?.let { File(it, "${sanitize(uid)}.json") }

    private fun sanitize(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)

    private fun readFromDisk(context: Context, uid: String): PlayerLyric? = runCatching {
        val file = fileFor(context, uid) ?: return@runCatching null
        if (!file.isFile) return@runCatching null
        val obj = JSONObject(file.readText())
        val array = obj.optJSONArray("lines") ?: return@runCatching null
        val lines = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text")
                if (text.isBlank()) continue
                add(
                    LyricLine(
                        timeMs = item.optLong("timeMs"),
                        text = text,
                        translation = item.optString("translation").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        if (lines.isEmpty()) return@runCatching null
        PlayerLyric(
            uid = obj.optString("uid", uid),
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            lines = lines,
            source = obj.optString("source"),
        )
    }.getOrNull()

    private fun saveToDisk(context: Context, lyric: PlayerLyric) {
        runCatching {
            val file = fileFor(context, lyric.uid) ?: return
            val array = JSONArray()
            lyric.lines.forEach { line ->
                array.put(
                    JSONObject().apply {
                        put("timeMs", line.timeMs)
                        put("text", line.text)
                        if (!line.translation.isNullOrBlank()) put("translation", line.translation)
                    },
                )
            }
            val payload = JSONObject()
                .put("uid", lyric.uid)
                .put("title", lyric.title)
                .put("artist", lyric.artist)
                .put("source", lyric.source)
                .put("lines", array)
            file.writeText(payload.toString())
            prune(diskDir(context))
        }
    }

    private fun prune(dir: File?) {
        val files = dir?.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_FILES)
            .forEach { runCatching { it.delete() } }
    }
}


internal suspend fun <T> recoverableOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
