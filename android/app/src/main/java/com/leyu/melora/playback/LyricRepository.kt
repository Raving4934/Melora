package com.leyu.melora.playback

import android.content.Context
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
                val lines = LyricParser.parse(embedded)
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
        val lines = LyricParser.parse(result.lyric, result.tlyric, result.rlyric)
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
        decodePlayerLyric(JSONObject(file.readText()), uid)
    }.getOrNull()

    private fun saveToDisk(context: Context, lyric: PlayerLyric) {
        runCatching {
            val file = fileFor(context, lyric.uid) ?: return
            file.writeText(encodePlayerLyric(lyric).toString())
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
