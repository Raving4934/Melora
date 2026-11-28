package com.leyu.melora.playback

import org.json.JSONArray
import org.json.JSONObject

/** 一个安装只发布自己的累积修改。同一记录的版本向量区分先后编辑与离线分叉。 */
internal typealias RecordClock = Map<String, Long>
internal data class RecordKey(val field: String, val id: String)
internal data class RecordStamp(val device: String, val sequence: Long)
internal class IncompleteRecordReplica : IllegalArgumentException("设备记录上传未完成")

internal data class RecordEdit(
    val clock: RecordClock,
    val ancestor: RecordClock,
    val before: JSONObject?,
    val value: JSONObject?,
    val decision: Boolean = false,
    val rewind: RecordStamp? = null,
    val deletedPlaybackTime: Long = 0L,
)
internal data class RecordReplica(
    val device: String, val sequence: Long = 0, val clearedAt: Long = 0,
    val edits: Map<RecordKey, RecordEdit> = emptyMap(),
    val historyFloor: Map<String, Long> = emptyMap(),
) {
    fun encode(): String = boundedBackupJson(JSONObject().put("version", 2).put("device", device)
        .put("sequence", sequence).put("clearedAt", clearedAt).put("historyFloor", JSONObject(historyFloor))
        .put("edits", JSONArray().apply {
            for ((key, edit) in edits) put(JSONArray().put(key.field).put(key.id).put(JSONObject(edit.clock))
                .put(JSONObject(edit.ancestor)).put(edit.before ?: JSONObject.NULL).put(edit.value ?: JSONObject.NULL)
                .put(edit.decision).put(edit.rewind?.let { JSONArray().put(it.device).put(it.sequence) } ?: JSONObject.NULL)
                .put(edit.deletedPlaybackTime))
        }))

    companion object {
        val actor = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val filename = Regex("""melora-records-v2-(${actor.pattern})-([1-9][0-9]{0,18})-([0-9a-f]{64})\.json""")
        fun decode(name: String, text: String): RecordReplica {
            val (device, sequence, digest) = requireNotNull(filename.matchEntire(name)).destructured
            if (text.sha256Hex() != digest) {
                if (runCatching { backupObject(text) }.isFailure) throw IncompleteRecordReplica()
                throw IllegalArgumentException("同步文件内容与文件名校验值不一致，请重试")
            }
            val json = backupObject(text)
            require(json.getInt("version") == 2 && json.getString("device") == device &&
                json.strictLong("sequence") == sequence.toLong()) { "设备记录版本不匹配" }
            val cleared = json.strictLong("clearedAt")
            require(cleared in 0..MAX_LIBRARY_TIMESTAMP)
            val floors = json.getJSONObject("historyFloor")
            val historyFloor = floors.keys().asSequence().associateWith { field ->
                require(field in historyFields)
                floors.strictLong(field).also { require(it in 1..MAX_LIBRARY_TIMESTAMP) }
            }
            val rows = json.getJSONArray("edits")
            val edits = linkedMapOf<RecordKey, RecordEdit>()
            val original = mutableMapOf<RecordKey, JSONObject>()
            val latest = mutableMapOf<RecordKey, JSONObject>()
            repeat(rows.length()) { i ->
                val row = rows.getJSONArray(i)
                require(row.length() == 9 && row.get(6) is Boolean)
                val key = RecordKey(row.getString(0), row.getString(1))
                require(key.field in RecordSyncMerge.libraryFields || key.field == "books")
                val clock = row.getJSONObject(2).clock()
                val ancestor = row.getJSONObject(3).clock()
                require(clock.getValue(device) in 1..sequence.toLong() && clock.covers(ancestor) &&
                    clock.getValue(device) > (ancestor[device] ?: 0L)) { "同步记录版本无效" }
                fun value(index: Int) = if (row.isNull(index)) null else row.getJSONObject(index).also {
                    require(RecordSyncMerge.rowKey(key.field, it) == key.id) { "同步记录编号不匹配" }
                }
                val before = value(4)
                val after = value(5)
                before?.let { original[key] = it }
                after?.let { latest[key] = it }
                val rewind = if (row.isNull(7)) null else row.getJSONArray(7).let { stamp ->
                    require(stamp.length() == 2 && key.field == "books" && after != null)
                    val origin = stamp.getString(0)
                    val revision = stamp.get(1).strictLong()
                    require(revision in 1..(clock[origin] ?: 0L))
                    RecordStamp(origin, revision)
                }
                val deletedPlayback = row.get(8).strictLong()
                require(deletedPlayback in 0..MAX_LIBRARY_TIMESTAMP &&
                    (deletedPlayback == 0L || key.field in historyFields && after == null))
                val edit = RecordEdit(clock, ancestor, before, after, row.getBoolean(6), rewind, deletedPlayback)
                require(edits.put(key, edit) == null) { "同步记录编号重复" }
            }
            // 验证所有候选（含未选中的祖先），使无效的数据永远无法投票或删除本机记录。
            RecordSyncMerge.validateRemote(recordDocument(original))
            RecordSyncMerge.validateRemote(recordDocument(latest))
            return RecordReplica(device, sequence.toLong(), cleared, edits, historyFloor)
        }
    }
}

private fun Any.strictLong(): Long {
    require(this is Int || this is Long) { "同步文件含无效数字" }
    return (this as Number).toLong()
}
private fun JSONObject.strictLong(key: String): Long = get(key).strictLong()
private fun JSONObject.clock(): RecordClock = keys().asSequence().associateWith {
    require(RecordReplica.actor.matches(it))
    strictLong(it).also { n -> require(n > 0) }
}
private fun RecordClock.covers(other: RecordClock) = other.all { (id, t) -> (this[id] ?: 0L) >= t }
private fun Iterable<RecordEdit>.joined(): RecordClock = buildMap {
    for (edit in this@joined) for ((id, n) in edit.clock) put(id, maxOf(n, get(id) ?: 0L))
}
private fun Iterable<RecordEdit>.common(): RecordClock {
    val clocks = map { it.clock }
    return clocks.firstOrNull().orEmpty().mapValues { (id, _) -> clocks.minOf { it[id] ?: 0L } }.filterValues { it > 0L }
}

internal fun recordRows(doc: JSONObject): Map<RecordKey, JSONObject> = buildMap {
    for (field in RecordSyncMerge.libraryFields + "books") {
        val rows = (if (field == "books") doc else doc.getJSONObject("library")).getJSONArray(field)
        repeat(rows.length()) {
            val row = rows.getJSONObject(it)
            put(RecordKey(field, RecordSyncMerge.rowKey(field, row)), row)
        }
    }
}
internal fun recordDocument(rows: Map<RecordKey, JSONObject> = emptyMap(), cleared: Long = 0): JSONObject {
    val library = JSONObject()
    for (field in RecordSyncMerge.libraryFields) library.put(field, JSONArray())
    for ((key, row) in rows) if (key.field != "books") library.getJSONArray(key.field).put(row)
    val books = JSONArray(rows.entries.filter { it.key.field == "books" }.sortedBy { it.key.id }.map { it.value })
    if (cleared > 0) library.put("historyClearedAt", cleared)
    return JSONObject().put("version", 1).put("library", library).put("books", books)
}

private data class RowReview(val value: JSONObject?, val conflicts: List<RecordConflict>, val chosen: Set<String>)

internal data class HistoryField(val timestamp: String, val limit: Int) {
    fun time(row: JSONObject?): Long = row?.optLong(timestamp) ?: 0L
    fun time(edit: RecordEdit): Long = edit.value?.let(::time) ?: edit.deletedPlaybackTime

    fun floor(rows: JSONArray): Long = if (rows.length() < limit) 0L else
        List(rows.length()) { time(rows.getJSONObject(it)) }.sortedDescending()[limit - 1]
}

internal val historyFields = mapOf(
    "recents" to HistoryField("playedAt", UserLibrary.MAX_RECENTS),
    "recentContainers" to HistoryField("updatedAt", UserLibrary.MAX_RECENT_CONTAINERS),
)

private fun inHistoryWindow(time: Long, clearedAt: Long, floor: Long): Boolean =
    time >= floor && (clearedAt == 0L || time > clearedAt)

/** 目录顺序因 DAV 实现而异。所有客户端按同一设备序归并，使同一组候选和决议具有相同含义。 */
internal class RecordHeads(replicas: Collection<RecordReplica>) {
    private val clearedAt = replicas.maxOfOrNull { it.clearedAt } ?: 0L
    private val historyFloor = historyFields.keys.associateWith { field ->
        replicas.maxOfOrNull { it.historyFloor[field] ?: 0L } ?: 0L
    }
    private val candidates = buildMap<RecordKey, MutableList<RecordEdit>> {
        for (replica in replicas.sortedBy { it.device }) for ((key, edit) in replica.edits) {
            val history = historyFields[key.field]
            if (history != null && !inHistoryWindow(history.time(edit), clearedAt, historyFloor.getValue(key.field))) continue
            getOrPut(key) { mutableListOf() }.add(edit)
        }
    }.mapValues { (_, edits) -> edits.filter { e -> edits.none { it !== e && it.clock.covers(e.clock) } } }

    private fun review(key: RecordKey, heads: List<RecordEdit>, choices: Map<String, Boolean>): RowReview {
        if (heads.size == 1) return RowReview(heads.single().value, emptyList(), emptySet())
        val conflicts = mutableListOf<RecordConflict>()
        val chosen = mutableSetOf<String>()
        val select = { c: RecordConflict -> choices[c.key]?.also { chosen.add(c.key) } }
        val common = heads.common()
        val anchors = heads.filter { !it.decision && it.ancestor == common }
        val anchor = anchors.firstOrNull()?.takeIf { first ->
            anchors.all { RecordSyncMerge.contentEquals(first.before, it.before) }
        }
        val variants = mutableListOf<Pair<JSONObject?, Boolean>>()
        for (edit in heads) {
            val concurrentRewind = edit.rewind?.let { it.sequence > (common[it.device] ?: 0L) } == true
            val index = variants.indexOfFirst { RecordSyncMerge.contentEquals(it.first, edit.value) }
            if (index < 0) variants.add(edit.value to concurrentRewind)
            else if (concurrentRewind) variants[index] = edit.value to true
        }
        var (value, rewind) = variants.first()
        for ((other, otherRewound) in variants.drop(1)) {
            rewind = rewind || otherRewound
            if (RecordSyncMerge.contentEquals(value, other)) continue
            val before = anchor?.before
            val competingPlayback = rewind && (before == null ||
                (value?.let { RecordSyncMerge.sameBookPoint(before, it) } != true &&
                    other?.let { RecordSyncMerge.sameBookPoint(before, it) } != true))
            val pair = RecordSyncMerge.reviewRow(key, before, value, other, anchor != null && !competingPlayback, select)
            value = pair.first
            conflicts += pair.second
        }
        return RowReview(value, conflicts.distinctBy { it.key }, chosen)
    }

    /** 对比上次真正呈现给用户的状态，只记录本机有变化的行。 */
    fun edit(own: RecordReplica, baseline: JSONObject, current: JSONObject): RecordReplica {
        val old = recordRows(baseline)
        val fresh = recordRows(current)
        val rows = LinkedHashMap(own.edits)
        val sequence = Math.addExact(own.sequence, 1L)
        val library = current.getJSONObject("library")
        val clear = maxOf(own.clearedAt, library.optLong("historyClearedAt"))
        val ownFloor = own.historyFloor.toMutableMap()
        for ((field, history) in historyFields) {
            val floor = history.floor(library.getJSONArray(field))
            if (floor > maxOf(ownFloor[field] ?: 0L, historyFloor.getValue(field))) ownFloor[field] = floor
        }
        val effectiveClear = maxOf(clear, clearedAt)
        fun contains(field: String, time: Long): Boolean =
            inHistoryWindow(time, effectiveClear, maxOf(ownFloor[field] ?: 0L, historyFloor.getValue(field)))
        // 已超出历史窗口的条目及删除标记可丢弃。相同时间戳不能可靠排序，保留后再裁剪显示。
        rows.entries.removeAll { (key, edit) -> historyFields[key.field]?.let { !contains(key.field, it.time(edit)) } == true }
        for (key in fresh.keys + old.keys) {
            val oldValue = old[key]
            val newValue = fresh[key]
            if (RecordSyncMerge.contentEquals(oldValue, newValue)) continue
            val history = historyFields[key.field]
            val playback = history?.time(newValue ?: oldValue) ?: 0L
            if (history != null && !contains(key.field, playback)) continue
            val heads = candidates[key].orEmpty()
            val previous = own.edits[key]
            val unresolved = heads.size > 1 && review(key, heads, emptyMap()).conflicts.isNotEmpty()
            // 本机没有活动候选时，沿用其中一个现存分支的祖先；只推进这个分支，其余继续等待选择。
            val branch = if (unresolved) heads.firstOrNull { it.clock == previous?.clock } ?: heads.first() else previous
            val context = if (unresolved) requireNotNull(branch).clock else heads.joined()
            val sameBranch = branch != null && branch.clock == context
            val ancestor = if (sameBranch) branch.ancestor else context
            val before = if (sameBranch) branch.before else oldValue
            val value = if (unresolved && branch != null) {
                RecordSyncMerge.reviewRow(key, oldValue, branch.value, newValue, based = true) { false }.first
            } else newValue
            val rewind = if (key.field == "books" && value != null) {
                if (oldValue != null && RecordSyncMerge.bookChapter(oldValue) == RecordSyncMerge.bookChapter(value) &&
                    RecordSyncMerge.bookPosition(value) < RecordSyncMerge.bookPosition(oldValue)) RecordStamp(own.device, sequence)
                else branch?.rewind?.takeIf {
                    branch.value?.let { RecordSyncMerge.bookChapter(it) == RecordSyncMerge.bookChapter(value) } == true
                }
            } else null
            rows[key] = RecordEdit(context + (own.device to sequence), ancestor, before, value,
                decision = sameBranch && branch.decision, rewind = rewind,
                deletedPlaybackTime = if (value == null) playback else 0L)
        }
        if (rows == own.edits && clear == own.clearedAt && ownFloor == own.historyFloor) return own
        // 保留本机原有列表中最新加入条目的相对位置。
        val ordered = (fresh.keys + rows.keys).mapNotNull { k -> rows[k]?.let { k to it } }.toMap()
        return RecordReplica(own.device, sequence, clear, ordered, ownFloor)
    }

    data class View(val records: JSONObject, val conflicts: List<RecordConflict>, val decided: RecordReplica, val pending: Map<String, Boolean>)

    fun view(own: RecordReplica, choices: Map<String, Boolean>): View {
        val visible = linkedMapOf<RecordKey, JSONObject>()
        val problems = mutableListOf<RecordConflict>()
        val decisions = LinkedHashMap(own.edits)
        val pending = mutableMapOf<String, Boolean>()
        for ((key, heads) in candidates) {
            val result = review(key, heads, choices)
            result.value?.let { visible[key] = it }
            problems += result.conflicts
            if (result.chosen.isEmpty()) continue
            if (result.conflicts.isNotEmpty()) {
                result.chosen.forEach { pending[it] = choices.getValue(it) }
            } else {
                val context = heads.joined()
                val deletionTime = if (result.value == null) historyFields[key.field]?.let { h -> heads.maxOf(h::time) } else null
                decisions[key] = RecordEdit(context + (own.device to Math.addExact(own.sequence, 1L)), context,
                    result.value, result.value, decision = true, deletedPlaybackTime = deletionTime ?: 0L)
            }
        }
        // 清空历史是跨行的操作。晚于清空的重播仍需保留，历史容量沿用本机业务限制。
        val records = recordDocument(visible, clearedAt)
        val lib = records.getJSONObject("library")
        for ((field, history) in historyFields) {
            val rows = lib.getJSONArray(field)
            lib.put(field, JSONArray((0 until rows.length()).map(rows::getJSONObject)
                .filter { inHistoryWindow(history.time(it), clearedAt, historyFloor.getValue(field)) }
                .sortedByDescending { history.time(it) }.take(history.limit)))
        }
        RecordSyncMerge.validateRemote(records)
        val decided = if (decisions == own.edits) own else own.copy(sequence = Math.addExact(own.sequence, 1L), edits = decisions)
        return View(records, problems, decided, pending)
    }
}
