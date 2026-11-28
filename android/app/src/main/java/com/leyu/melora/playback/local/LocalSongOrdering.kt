package com.leyu.melora.playback.local

import android.icu.text.AlphabeticIndex
import android.icu.text.CollationKey
import android.icu.text.Collator
import java.util.Locale

/** 本地歌曲排序字段；storageValue 作为持久化契约，不依赖枚举名称。 */
internal enum class LocalSortField(val storageValue: String, val label: String) {
    FileName("file_name", "文件名"),
    Artist("artist", "歌手"),
    Year("year", "年份"),
    Size("size", "大小"),
    ModifiedAt("modified_at", "修改时间"),
    AddedAt("added_at", "添加时间");

    companion object {
        fun restore(value: String?): LocalSortField =
            entries.firstOrNull { it.storageValue == value } ?: FileName
    }
}

/** 本地歌曲索引条固定为：数字、其它符号、英文字母/中文拼音。 */
internal val LocalSongIndexLabels: List<String> =
    listOf("0", "#") + ('A'..'Z').map { it.toString() }

/** 歌曲、队列标识与分组首位使用同一列表快照。 */
internal data class LocalSongsContent(
    val songs: List<LocalSong> = emptyList(),
    val sections: Map<String, Int> = emptyMap(),
) {
    val songUids = songs.map(LocalSong::localUid)
}

/*
 * Android ICU 从 API 24 起可用，但 JVM 单元测试不会提供运行时 ICU 实现。
 * 只创建 Lazy 委托，不在该文件被加载时解析或初始化 ICU 对象。
 */
private val localSongAlphabeticIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
    AlphabeticIndex<Any?>(Locale.CHINA)
        .addLabels(Locale.ENGLISH)
        .buildImmutableIndex()
}

private val localSongCollator by lazy(LazyThreadSafetyMode.PUBLICATION) {
    Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }.freeze()
}

internal fun sortLocalSongs(
    songs: List<LocalSong>,
    field: LocalSortField,
    ascending: Boolean,
): LocalSongsContent {
    if (songs.isEmpty()) return LocalSongsContent()
    val ordered = when (field) {
        LocalSortField.FileName,
        LocalSortField.Artist,
        -> return sortLocalSongsByName(songs, field, ascending)

        // These comparisons are intentionally unchanged: storage values and numeric/time
        // ordering are existing persistence/UI contracts.
        LocalSortField.Year -> songs.sortedWith(compareBy { it.year })
        LocalSortField.Size -> songs.sortedWith(compareBy { it.sizeBytes })
        LocalSortField.ModifiedAt -> songs.sortedWith(compareBy { it.modifiedAt })
        LocalSortField.AddedAt -> songs.sortedWith(compareBy { it.addedAt })
    }
    return LocalSongsContent(if (ascending) ordered else ordered.asReversed())
}

private data class LocalTextSortEntry(
    val song: LocalSong,
    val sectionRank: Int,
    val primaryKey: CollationKey,
    val secondaryKey: CollationKey,
)

private fun sortLocalSongsByName(
    songs: List<LocalSong>,
    field: LocalSortField,
    ascending: Boolean,
): LocalSongsContent {
    val keys = HashMap<String, CollationKey>()
    val entries = songs.map { song ->
        val primary = if (field == LocalSortField.FileName) song.title else song.artist
        val secondary = if (field == LocalSortField.FileName) song.artist else song.title
        val primaryValue = primary.trimStart()
        val secondaryValue = secondary.trimStart()
        LocalTextSortEntry(
            song = song,
            sectionRank = localSongSectionRank(primaryValue),
            primaryKey = keys.getOrPut(primaryValue) { localSongCollator.getCollationKey(primaryValue) },
            secondaryKey = keys.getOrPut(secondaryValue) { localSongCollator.getCollationKey(secondaryValue) },
        )
    }

    val ordered = entries.sortedWith(compareBy<LocalTextSortEntry> { it.sectionRank }
        .thenBy { it.primaryKey }.thenBy { it.secondaryKey })
    val sections = linkedMapOf<String, Int>()
    val sorted = (if (ascending) ordered else ordered.asReversed()).mapIndexed { index, entry ->
        sections.putIfAbsent(LocalSongIndexLabels[entry.sectionRank], index)
        entry.song
    }
    return LocalSongsContent(sorted, sections)
}

private fun localSongSectionRank(value: String): Int {
    if (value.isEmpty()) return 1
    val firstCodePoint = value.codePointAt(0)
    if (Character.isDigit(firstCodePoint)) return 0
    if (!Character.isLetter(firstCodePoint)) return 1

    val index = localSongAlphabeticIndex.getBucketIndex(value)
    val letter = localSongAlphabeticIndex.getBucket(index).label.uppercase(Locale.ROOT).singleOrNull()
    return if (letter != null && letter in 'A'..'Z') letter - 'A' + 2 else 1
}
