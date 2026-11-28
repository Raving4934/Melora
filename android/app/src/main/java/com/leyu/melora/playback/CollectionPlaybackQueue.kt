package com.leyu.melora.playback

internal data class CollectionQueueSource(
    val visibleUids: List<String>,
    val knownUids: List<String> = visibleUids,
) {
    val knownCounts: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        knownUids.groupingBy { it }.eachCount()
    }
}

/** 索引基于添加前的队列，调用者从后向前批量添加。 */
internal data class CollectionQueueInsertion(val index: Int, val sourceIndices: List<Int>)

internal data class CollectionQueueUpdate(
    val source: CollectionQueueSource,
    val targetIndex: Int,
    val insertions: List<CollectionQueueInsertion> = emptyList(),
)

/** 来源翻页或刷新时只补新曲，保留队列里的手动排序与增删。 */
internal fun collectionQueueUpdate(
    previous: CollectionQueueSource,
    sourceUids: List<String>,
    startIndex: Int,
    currentSize: Int,
    currentUidAt: (Int) -> String,
): CollectionQueueUpdate {
    val knownCounts = previous.knownCounts
    if (previous.visibleUids === sourceUids || previous.visibleUids == sourceUids) {
        // 刷新后可见列表可能只剩一项，但队列仍保留来源之前的重复曲目。
        val mayRepeat = (knownCounts[sourceUids[startIndex]] ?: 0) > 1
        val target = collectionTargetIndex(sourceUids, startIndex, currentSize, mayRepeat, currentUidAt)
        if (target >= 0) {
            val source = if (previous.visibleUids === sourceUids) previous else previous.copy(visibleUids = sourceUids)
            return CollectionQueueUpdate(source, target)
        }
    }

    val positions = mutableMapOf<String, MutableList<Int>>()
    repeat(currentSize) { index -> positions.getOrPut(currentUidAt(index), ::mutableListOf).add(index) }
    val occurrences = mutableMapOf<String, Int>()
    val newlyKnown = mutableListOf<String>()
    val groups = mutableMapOf<Int, MutableList<Int>>()
    val pending = mutableListOf<Int>()
    var existingTarget = -1
    var previousPosition = -1
    for (index in sourceUids.indices) {
        val uid = sourceUids[index]
        val occurrence = occurrences[uid] ?: 0
        occurrences[uid] = occurrence + 1
        val matches = positions[uid].orEmpty()
        val following = matches.getOrNull(occurrence) ?: -1
        val isNew = occurrence >= (knownCounts[uid] ?: 0)
        if (isNew) newlyKnown.add(uid)
        val selected = index == startIndex
        // 点回已移除的歌曲时，优先使用队列里尚存的同曲，其次只补回这一首。
        if (selected) {
            existingTarget = if (following < 0 && !isNew) matches.lastOrNull() ?: -1 else following
        }
        if (following < 0) {
            if (isNew || (selected && existingTarget < 0)) pending.add(index)
            continue
        }
        if (pending.isNotEmpty()) {
            // 两侧锚点因手动排序而交错时，追加可保留已有的队列优先顺序。
            val at = if (previousPosition < following) following else currentSize
            groups.getOrPut(at, ::mutableListOf).addAll(pending)
            pending.clear()
        }
        previousPosition = following
    }
    if (pending.isNotEmpty()) groups.getOrPut(currentSize, ::mutableListOf).addAll(pending)
    val insertions = groups.entries.sortedBy { it.key }.map { (index, items) -> CollectionQueueInsertion(index, items) }
    val target = if (existingTarget >= 0) {
        existingTarget + insertions.sumOf { if (it.index <= existingTarget) it.sourceIndices.size else 0 }
    } else {
        var inserted = 0
        var result = -1
        for (insertion in insertions) {
            val offset = insertion.sourceIndices.indexOf(startIndex)
            if (offset >= 0) {
                result = insertion.index + inserted + offset
                break
            }
            inserted += insertion.sourceIndices.size
        }
        check(result >= 0)
        result
    }

    // 刷新可能暂时回到第一页。保留已见曲目，下一次翻页时才能辨认手动移除的内容。
    val known = when {
        previous.knownUids.size + newlyKnown.size == sourceUids.size -> sourceUids
        newlyKnown.isEmpty() -> previous.knownUids
        else -> previous.knownUids + newlyKnown
    }
    return CollectionQueueUpdate(CollectionQueueSource(sourceUids, known), target, insertions)
}

private fun collectionTargetIndex(
    sourceUids: List<String>,
    startIndex: Int,
    currentSize: Int,
    mayRepeat: Boolean,
    currentUidAt: (Int) -> String,
): Int {
    val uid = sourceUids[startIndex]
    if (!mayRepeat && startIndex < currentSize && currentUidAt(startIndex) == uid) return startIndex
    var occurrence = if (mayRepeat) sourceUids.subList(0, startIndex).count { it == uid } else 0
    var lastMatch = -1
    repeat(currentSize) { index ->
        if (currentUidAt(index) == uid) {
            if (occurrence == 0) return index
            occurrence -= 1
            lastMatch = index
        }
    }
    return lastMatch
}

/** 只落盘来源中被移除的曲目，超长队列未写入恢复窗口的条目仍可从来源补回。 */
internal fun missingCollectionUids(knownUids: List<String>, activeUids: Sequence<String>): List<String> {
    if (knownUids.isEmpty()) return emptyList()
    val activeCounts = activeUids.groupingBy { it }.eachCountTo(mutableMapOf())
    return knownUids.filter { uid ->
        val count = activeCounts[uid] ?: 0
        if (count > 0) activeCounts[uid] = count - 1
        count == 0
    }
}
