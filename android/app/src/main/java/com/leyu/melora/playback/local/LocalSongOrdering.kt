package com.leyu.melora.playback.local

import android.icu.text.AlphabeticIndex
import android.icu.text.CollationKey
import android.icu.text.Collator
import java.util.LinkedHashMap
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

/** 返回标题/歌手在固定索引条中的分组；非文本排序字段不提供分组。 */
internal fun localSongSection(song: LocalSong, field: LocalSortField): String {
    val value = when (field) {
        LocalSortField.FileName -> song.title
        LocalSortField.Artist -> song.artist
        else -> return ""
    }
    return localSongSectionForValue(value)
}

/**
 * 从当前实际列表中提取每个分组的首项位置。
 * LinkedHashMap 保留 songs 中首次遇到各分组的顺序，反序列表也因此按实际位置工作。
 */
internal fun localSongSectionStarts(
    songs: List<LocalSong>,
    field: LocalSortField,
): Map<String, Int> {
    if (songs.isEmpty()) return emptyMap()
    when (field) {
        LocalSortField.FileName, LocalSortField.Artist -> Unit
        else -> return emptyMap()
    }

    val starts = LinkedHashMap<String, Int>()
    songs.forEachIndexed { index, song ->
        val section = localSongSection(song, field)
        if (section !in starts) starts[section] = index
    }
    return starts
}

internal fun sortLocalSongs(
    songs: List<LocalSong>,
    field: LocalSortField,
    ascending: Boolean,
): List<LocalSong> {
    val ordered = when (field) {
        LocalSortField.FileName,
        LocalSortField.Artist,
        -> sortLocalSongsByName(songs, field)

        // These comparisons are intentionally unchanged: storage values and numeric/time
        // ordering are existing persistence/UI contracts.
        LocalSortField.Year -> songs.sortedWith(compareBy { it.year })
        LocalSortField.Size -> songs.sortedWith(compareBy { it.sizeBytes })
        LocalSortField.ModifiedAt -> songs.sortedWith(compareBy { it.modifiedAt })
        LocalSortField.AddedAt -> songs.sortedWith(compareBy { it.addedAt })
    }
    return if (ascending) ordered else ordered.asReversed()
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
): List<LocalSong> {
    val entries = songs.map { song ->
        val primary = if (field == LocalSortField.FileName) song.title else song.artist
        val secondary = if (field == LocalSortField.FileName) song.artist else song.title
        val primaryValue = primary.trimStart()
        val secondaryValue = secondary.trimStart()
        LocalTextSortEntry(
            song = song,
            sectionRank = localSongSectionRank(localSongSectionForValue(primaryValue)),
            primaryKey = localSongCollator.getCollationKey(primaryValue),
            secondaryKey = localSongCollator.getCollationKey(secondaryValue),
        )
    }

    return entries.sortedWith(Comparator { left, right ->
        val sectionComparison = left.sectionRank - right.sectionRank
        if (sectionComparison != 0) {
            sectionComparison
        } else {
            val primaryComparison = left.primaryKey.compareTo(right.primaryKey)
            if (primaryComparison != 0) primaryComparison
            else left.secondaryKey.compareTo(right.secondaryKey)
        }
    }).map { it.song }
}

private fun localSongSectionForValue(value: String): String {
    val trimmed = value.trimStart()
    if (trimmed.isEmpty()) return "#"

    val firstCodePoint = trimmed.codePointAt(0)
    if (Character.isDigit(firstCodePoint)) return "0"
    if (!Character.isLetter(firstCodePoint)) return "#"

    val bucketIndex = localSongAlphabeticIndex.getBucketIndex(trimmed)
    val label = localSongAlphabeticIndex.getBucket(bucketIndex).label
    return label
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 1 && it[0] in 'A'..'Z' }
        ?: "#"
}

private fun localSongSectionRank(section: String): Int = when {
    section == "0" -> 0
    section == "#" -> 1
    section.length == 1 && section[0] in 'A'..'Z' -> section[0] - 'A' + 2
    else -> 1
}
