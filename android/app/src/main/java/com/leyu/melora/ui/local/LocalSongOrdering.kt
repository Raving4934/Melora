package com.leyu.melora.ui.local

import com.leyu.melora.playback.local.LocalSong
import java.util.Locale

/** 本地歌曲排序字段（升降序由调用方切换）。 */
internal enum class LocalSortField(val label: String) {
    FileName("文件名"),
    Artist("歌手"),
    Year("年份"),
    Size("大小"),
    ModifiedAt("修改时间"),
    AddedAt("添加时间"),
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
