package com.leyu.melora.playback.local

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

internal fun sortLocalSongs(
    songs: List<LocalSong>,
    field: LocalSortField,
    ascending: Boolean,
): List<LocalSong> {
    val comparator: Comparator<LocalSong> = when (field) {
        LocalSortField.FileName -> compareBy<LocalSong> { it.title.lowercase(Locale.ROOT) }
            .thenBy { it.artist.lowercase(Locale.ROOT) }
        LocalSortField.Artist -> compareBy<LocalSong> { it.artist.lowercase(Locale.ROOT) }
            .thenBy { it.title.lowercase(Locale.ROOT) }
        LocalSortField.Year -> compareBy { it.year }
        LocalSortField.Size -> compareBy { it.sizeBytes }
        LocalSortField.ModifiedAt -> compareBy { it.modifiedAt }
        LocalSortField.AddedAt -> compareBy { it.addedAt }
    }
    val ordered = songs.sortedWith(comparator)
    return if (ascending) ordered else ordered.asReversed()
}
