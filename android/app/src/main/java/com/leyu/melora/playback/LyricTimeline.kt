package com.leyu.melora.playback

/** 只在播放器采样锚点之间插值；暂停/缓冲/切歌由播放器事实重锚，不发射全局逐帧状态。 */
internal fun lyricPositionAt(state: PlayerUiState, nowMs: Long): Long {
    val elapsed = if (state.positionAdvancing && !state.buffering && !state.resolving) {
        (nowMs - state.positionSampleRealtimeMs).coerceAtLeast(0)
    } else 0L
    val position = state.positionMs + (elapsed * state.speed).toLong()
    return position.coerceIn(0L, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
}

internal fun lyricIndexAt(lines: List<LyricLine>, positionMs: Long, includeBackground: Boolean = false): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lines[middle].startMs <= positionMs) low = middle + 1 else high = middle
    }
    var index = low - 1
    if (!includeBackground) while (index > 0 && lines[index].isBackground) index--
    return index
}

internal data class LyricFrame(val focusIndex: Int = -1, val activeIndices: Set<Int> = emptySet())

/** 预建事件表，正常播放仅越过边界时更新；支持重叠/背景行，倒退或 Seek 时二分重建。 */
internal class LyricTimeline(val lines: List<LyricLine>) {
    private data class Event(val time: Long, val index: Int, val start: Boolean)
    private val ends = LongArray(lines.size).also { ends ->
        var nextStart = Long.MAX_VALUE
        for (index in lines.indices.reversed()) {
            if (index < lines.lastIndex && lines[index + 1].startMs > lines[index].startMs) nextStart = lines[index + 1].startMs
            ends[index] = lines[index].endMs ?: nextStart
        }
    }
    private val prefixEnd = LongArray(lines.size).also { prefix ->
        for (index in lines.indices) prefix[index] = maxOf(ends[index], prefix.getOrElse(index - 1) { Long.MIN_VALUE })
    }
    private val events = lines.indices.flatMap { index ->
        if (ends[index] <= lines[index].startMs) listOf(Event(lines[index].startMs, index, true))
        else listOf(Event(lines[index].startMs, index, true), Event(ends[index], index, false))
    }.sortedWith(compareBy<Event> { it.time }.thenBy { it.start })
    private val active = linkedSetOf<Int>()
    private var cursor = 0
    private var lastTime = Long.MIN_VALUE
    private var frame = LyricFrame()

    fun at(timeMs: Long): LyricFrame {
        if (timeMs < lastTime || lastTime == Long.MIN_VALUE || timeMs - lastTime > 1_000) {
            active.clear()
            val last = lyricIndexAt(lines, timeMs, includeBackground = true)
            var index = last
            val matches = mutableListOf<Int>()
            while (index >= 0 && prefixEnd[index] > timeMs) {
                if (timeMs < ends[index]) matches.add(index)
                index--
            }
            matches.asReversed().forEach(active::add)
            var low = 0
            var high = events.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (events[middle].time <= timeMs) low = middle + 1 else high = middle
            }
            cursor = low
            frame = snapshot(timeMs)
        } else if (cursor < events.size && events[cursor].time <= timeMs) {
            while (cursor < events.size && events[cursor].time <= timeMs) {
                val event = events[cursor++]
                if (event.start && timeMs < ends[event.index]) active.add(event.index) else active.remove(event.index)
            }
            frame = snapshot(timeMs)
        }
        lastTime = timeMs
        return frame
    }

    private fun snapshot(timeMs: Long): LyricFrame {
        val focus = lyricIndexAt(lines, timeMs)
        return LyricFrame(focus, active.toSet())
    }
}

internal fun lyricWordProgress(word: LyricWord, positionMs: Long): Float =
    if (word.endMs <= word.startMs) { if (positionMs >= word.startMs) 1f else 0f }
    else ((positionMs - word.startMs).toDouble() / (word.endMs - word.startMs)).toFloat().coerceIn(0f, 1f)
