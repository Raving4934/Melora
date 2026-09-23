package com.leyu.melora.playback

import android.content.Context
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.local.LocalSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 下载中心：任务状态与已保存资源生命周期分离，供「我的 → 下载」页展示与操作。 */
object DownloadCenter {
    enum class Status { Downloading, Paused, Done, Failed }

    data class Record(
        val id: String,
        val name: String,
        val song: OnlineSong?,
        val status: Status,
        val percent: Int,
        val detail: String,
        val fileName: String?,
        val updatedAt: Long,
        val savedUri: String? = null,
        /** 与任务状态独立：失败/中断时仍可保留并读取旧资源。 */
        val hasSavedResource: Boolean = !fileName.isNullOrBlank() || !savedUri.isNullOrBlank(),
        /** 已保存文件被实际探测到的音质规格；未知时为 null。 */
        val audioSpec: AudioSpecification? = null,
        /** 升级任务重试仍以原本地文件为下限，不能悄悄退化为普通下载。 */
        val upgradeFrom: LocalSong? = null,
    ) {
        /** 封面只以歌曲快照为唯一数据源，避免记录字段与 song.img 长期不一致。 */
        val img: String? get() = song?.img
    }

    private const val MAX_FINISHED_RECORDS = 200
    private lateinit var file: File
    private val lock = Any()
    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "downloads.json")
        synchronized(lock) {
            val loaded = read()
            // 上次进程被杀时仍在下载的条目，恢复为“已中断”；暂停状态可直接继续。
            // 进行中/暂停记录不计入历史上限，避免重启时被终态历史挤掉。
            val restored = retainActiveAndRecentFinished(loaded.map(::restoreInterrupted))
            _records.value = restored
            if (restored != loaded) {
                runCatching { writeTextAtomically(file, toJson().toString()) }
            }
        }
    }

    fun start(song: OnlineSong, detail: String) = begin(song.uid, song, detail, persist = true)

    /** 任务 ID 与解析后的歌曲快照分离；本地歌曲匹配在线资源后仍保持同一条下载记录。 */
    fun start(id: String, song: OnlineSong, detail: String, upgradeFrom: LocalSong? = null) =
        begin(id, song, detail, persist = true, upgradeFrom = upgradeFrom)

    /** 由下载工作协程调用：先持久化排队状态，再等待并发许可。 */
    fun queued(id: String, song: OnlineSong, detail: String = "等待下载…") = begin(id, song, detail, persist = true)

    private fun begin(id: String, song: OnlineSong, detail: String, persist: Boolean, upgradeFrom: LocalSong? = null) = update(persist = persist) { list ->
        val previous = list.firstOrNull { it.id == id }
        val saved = previous?.takeIf { it.hasSavedResource && hasResourceAddress(it.fileName, it.savedUri) }
        listOf(
            Record(
                id = id,
                name = song.name,
                song = song,
                status = Status.Downloading,
                percent = 0,
                detail = detail,
                fileName = saved?.fileName,
                updatedAt = System.currentTimeMillis(),
                savedUri = saved?.savedUri,
                hasSavedResource = saved != null,
                audioSpec = saved?.audioSpec,
                upgradeFrom = upgradeFrom,
            ),
        ) + list.filterNot { it.id == id }
    }

    /** 返回仍有已保存资源地址的记录；任务失败/中断不影响本地资源可读性。 */
    fun saved(id: String): Record? = _records.value.firstOrNull {
        it.id == id && it.hasSavedResource && hasResourceAddress(it.fileName, it.savedUri)
    }

    /** 旧记录首次成功定位后补齐地址；后续修改下载目录仍可读取原文件。 */
    fun rememberSavedUri(id: String, uri: String, spec: AudioSpecification? = null) = update(persist = true) { list ->
        list.map {
            if (it.id == id && it.hasSavedResource) it.copy(savedUri = uri,
                audioSpec = spec ?: it.audioSpec.takeIf { _ -> it.savedUri == uri })
            else it
        }
    }

    /** 只为当前仍保存同一 URI 的资源补齐实际音质规格，避免旧地址被复用时串写规格。 */
    fun rememberAudioSpecification(id: String, uri: String, spec: AudioSpecification) = update(persist = true) { list ->
        list.map {
            if (
                it.id == id &&
                it.hasSavedResource &&
                it.savedUri == uri &&
                hasResourceAddress(it.fileName, it.savedUri)
            ) {
                it.copy(audioSpec = spec)
            } else {
                it
            }
        }
    }

    fun progress(id: String, percent: Int) = update(persist = false) { list ->
        list.map { if (it.id == id) it.copy(percent = percent, updatedAt = System.currentTimeMillis()) else it }
    }

    fun done(
        id: String,
        detail: String,
        fileName: String? = null,
        savedUri: String? = null,
        audioSpec: AudioSpecification? = null,
    ) = update(persist = true) { list ->
        list.map {
            if (it.id == id) {
                val nextFileName = fileName ?: it.fileName
                val nextSavedUri = savedUri ?: it.savedUri
                val sameSavedResource = it.hasSavedResource && sameResourceAddress(
                    it.fileName,
                    it.savedUri,
                    nextFileName,
                    nextSavedUri,
                )
                it.copy(
                    status = Status.Done,
                    percent = 100,
                    detail = detail,
                    fileName = nextFileName,
                    savedUri = nextSavedUri,
                    hasSavedResource = hasResourceAddress(nextFileName, nextSavedUri),
                    audioSpec = audioSpec ?: it.audioSpec.takeIf { sameSavedResource },
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                it
            }
        }
    }

    /** 下载失败只改变任务状态，不能抹掉仍可用的旧资源。 */
    fun failed(id: String, message: String) = update(persist = true) { list ->
        list.map {
            if (it.id == id) it.copy(status = Status.Failed, percent = 0, detail = message, updatedAt = System.currentTimeMillis())
            else it
        }
    }

    /** 任务暂停：保留当前记录，更新状态为 Paused。 */
    fun paused(id: String, detail: String = "已暂停") = update(persist = true) { list ->
        list.map {
            if (it.id == id && (it.status == Status.Downloading || it.status == Status.Paused)) {
                it.copy(status = Status.Paused, detail = detail, updatedAt = System.currentTimeMillis())
            } else it
        }
    }

    /** 删除物理文件后同步清理引用同一URI的记录；不触碰已升级到另一URI的记录。 */
    fun clearSaved(id: String, message: String = "本地文件不存在或目录权限已失效", expectedUri: String? = null) = update(persist = true) { list ->
        list.map {
            if (if (expectedUri == null) it.id == id else it.savedUri == expectedUri) {
                it.copy(
                    status = if (it.status == Status.Downloading) it.status else Status.Failed,
                    percent = if (it.status == Status.Downloading) it.percent else 0,
                    detail = if (it.status == Status.Downloading) it.detail else message,
                    fileName = null,
                    savedUri = null,
                    hasSavedResource = false,
                    audioSpec = null,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                it
            }
        }
    }

    /** 将懒加载得到的封面写回歌曲快照；历史缺图记录下次进入时可直接显示。 */
    fun updateArtwork(id: String, url: String) = update(persist = true) { list ->
        list.map { record -> if (record.id == id) withArtwork(record, url) else record }
    }

    /** 预检与完整文件规格不一致时撤销本次任务，保留此前仍有效的历史资源。 */
    internal fun restoreRecord(id: String, previous: Record?) = update(persist = true) { list ->
        list.filterNot { it.id == id }.toMutableList().apply {
            if (previous != null) add(list.indexOfFirst { it.id == id }.coerceIn(0, size), previous)
        }
    }

    fun remove(id: String) = update(persist = true) { list ->
        list.filterNot { it.id == id }
    }

    /** 清空已结束记录，保留进行中与已暂停的任务。 */
    fun clearFinished() = update(persist = true) { list ->
        list.filter { it.status == Status.Downloading || it.status == Status.Paused }
    }

    internal fun restoreInterrupted(record: Record): Record =
        if (record.status == Status.Downloading) {
            record.copy(status = Status.Paused, percent = 0, detail = "下载被中断")
        } else {
            record
        }

    private fun update(persist: Boolean, change: (List<Record>) -> List<Record>) = synchronized(lock) {
        val current = _records.value
        val next = retainActiveAndRecentFinished(change(current))
        if (next == current) return@synchronized
        _records.value = next
        if (persist && ::file.isInitialized) {
            runCatching { writeTextAtomically(file, toJson().toString()) }
        }
    }

    private fun read(): List<Record> {
        if (!::file.isInitialized || !file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::recordFromJson)
            }
        }.getOrDefault(emptyList())
    }

    private fun retainActiveAndRecentFinished(records: List<Record>): List<Record> {
        var finished = 0
        return records.filter { record ->
            if (record.status == Status.Downloading || record.status == Status.Paused) {
                true
            } else {
                finished++ < MAX_FINISHED_RECORDS
            }
        }
    }

    private fun toJson(): JSONArray = JSONArray().apply {
        _records.value.forEach { put(recordToJson(it)) }
    }

    /** 读取旧记录时把顶层 img 一次性迁入 song.img；新格式不再保存重复字段。 */
    internal fun recordFromJson(node: JSONObject): Record? {
        val id = node.optString("id")
        if (id.isBlank()) return null
        val legacyArtwork = validArtworkUrl(node.optString("img"))
        val songRaw = node.optJSONObject("song")?.let { JSONObject(it.toString()) }
        if (songRaw != null && validArtworkUrl(songRaw.optString("img")) == null && legacyArtwork != null) {
            songRaw.put("img", legacyArtwork)
        }
        val status = runCatching { Status.valueOf(node.optString("status")) }.getOrDefault(Status.Failed)
        val rawFileName = node.optString("fileName").takeIf { it.isNotBlank() }
        val rawSavedUri = node.optString("savedUri").takeIf { it.startsWith("content://") || it.startsWith("file://") }
        val audioSpec = node.optJSONObject("audioSpec")?.let(::audioSpecificationFromJson)
        // 旧格式没有资源生命周期字段：只有 Done 记录能证明地址仍属于已保存资源；
        // 旧 Failed+filename 常来自删除/失效后的残留地址，不能在新模型中当作可播放资源。
        val legacyHasSavedResource = status == Status.Done
        val hasSavedResource = if (node.has("hasSavedResource")) {
            node.optBoolean("hasSavedResource")
        } else {
            legacyHasSavedResource
        }
        val keepResource = hasSavedResource && hasResourceAddress(rawFileName, rawSavedUri)
        return Record(
            id = id,
            name = node.optString("name"),
            song = OnlineSong.from(songRaw),
            status = status,
            percent = node.optInt("percent"),
            detail = node.optString("detail"),
            fileName = rawFileName.takeIf { keepResource },
            updatedAt = node.optLong("updatedAt"),
            savedUri = rawSavedUri.takeIf { keepResource },
            hasSavedResource = keepResource,
            audioSpec = audioSpec,
            upgradeFrom = node.optJSONObject("upgradeFrom")?.let(LocalSong::fromJson),
        )
    }

    internal fun recordToJson(record: Record): JSONObject {
        val keepResource = record.hasSavedResource && hasResourceAddress(record.fileName, record.savedUri)
        return JSONObject().apply {
            put("id", record.id)
            put("name", record.name)
            put("song", record.song?.raw ?: JSONObject())
            put("status", record.status.name)
            put("percent", record.percent)
            put("detail", record.detail)
            put("fileName", if (keepResource) record.fileName ?: "" else "")
            put("updatedAt", record.updatedAt)
            put("savedUri", if (keepResource) record.savedUri ?: "" else "")
            put("hasSavedResource", keepResource)
            record.upgradeFrom?.let { put("upgradeFrom", it.toJson()) }
            if (keepResource) record.audioSpec?.let { put("audioSpec", audioSpecificationToJson(it)) }
        }
    }

    internal fun withArtwork(record: Record, url: String): Record {
        val artwork = validArtworkUrl(url) ?: return record
        val song = record.song ?: return record
        if (song.img != null) return record
        val enrichedRaw = JSONObject(song.raw.toString()).put("img", artwork)
        return record.copy(song = OnlineSong(enrichedRaw))
    }

    private fun hasResourceAddress(fileName: String?, savedUri: String?): Boolean =
        !fileName.isNullOrBlank() || !savedUri.isNullOrBlank()

    private fun sameResourceAddress(
        firstFileName: String?,
        firstSavedUri: String?,
        secondFileName: String?,
        secondSavedUri: String?,
    ): Boolean = hasResourceAddress(firstFileName, firstSavedUri) &&
        hasResourceAddress(secondFileName, secondSavedUri) &&
        firstFileName == secondFileName &&
        firstSavedUri == secondSavedUri

    private fun audioSpecificationFromJson(node: JSONObject): AudioSpecification {
        val mimeType = (node.opt("mimeType") as? String)?.takeIf { it.isNotBlank() }
        return AudioSpecification(
            mimeType = mimeType,
            sampleRate = node.optInt("sampleRate", -1),
            bitrate = node.optInt("bitrate", -1),
            bitDepth = node.optInt("bitDepth", -1),
        )
    }

    private fun audioSpecificationToJson(spec: AudioSpecification): JSONObject = JSONObject()
        .put("mimeType", spec.mimeType ?: JSONObject.NULL)
        .put("sampleRate", spec.sampleRate)
        .put("bitrate", spec.bitrate)
        .put("bitDepth", spec.bitDepth)

    private fun validArtworkUrl(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
