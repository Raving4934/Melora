package com.leyu.melora.playback.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSongOrderingInstrumentedTest {
    @Test
    fun exposesStableIndexLabelsAndMixedNameSections() {
        assertEquals(
            listOf("0", "#") + ('A'..'Z').map { it.toString() },
            LocalSongIndexLabels,
        )

        val songs = listOf(
            testSong("digit", "2 数字"),
            testSong("symbol", "# 符号"),
            testSong("empty", ""),
            testSong("accent", "Éclair"),
            testSong("english", "Apple"),
            testSong("traditional", "繁體"),
            testSong("chinese", "中文"),
        )

        val ordered = sortLocalSongs(songs, LocalSortField.FileName, ascending = true)
        assertEquals(listOf("digit", "empty", "symbol", "english", "accent", "traditional", "chinese"), ordered.songs.map { it.id })
        assertEquals(listOf("0" to 0, "#" to 1, "A" to 3, "E" to 4, "F" to 5, "Z" to 6), ordered.sections.toList())
    }

    @Test
    fun sortsArtistsWithSameArtistUsingTitleAndReversesByActualListOrder() {
        val songs = listOf(
            testSong("zoo", "Zoo", artist = "Alpha"),
            testSong("apple", "Apple", artist = "Alpha"),
            testSong("zoo-copy", "Zoo", artist = "Alpha"),
            testSong("beta", "Beta", artist = "Beta"),
            testSong("zhou", "周", artist = "周杰伦"),
        )

        val ascending = sortLocalSongs(songs, LocalSortField.Artist, ascending = true)
        val descending = sortLocalSongs(songs, LocalSortField.Artist, ascending = false)
        assertEquals(listOf("apple", "zoo", "zoo-copy", "beta", "zhou"), ascending.songs.map { it.id })
        assertEquals(ascending.songs.asReversed(), descending.songs)
        assertEquals(listOf("A" to 0, "B" to 3, "Z" to 4), ascending.sections.toList())
        assertEquals(listOf("Z" to 0, "B" to 1, "A" to 2), descending.sections.toList())
    }

    @Test
    fun preservesNonTextSortContractsAndStorageValues() {
        val songs = listOf(
            testSong("a", "A", year = 2024, sizeBytes = 300, modifiedAt = 30, addedAt = 3),
            testSong("b", "B", year = 2022, sizeBytes = 100, modifiedAt = 10, addedAt = 1),
            testSong("c", "C", year = 2023, sizeBytes = 200, modifiedAt = 20, addedAt = 2),
        )
        val expectedAscendingIds = mapOf(
            LocalSortField.Year to listOf("b", "c", "a"),
            LocalSortField.Size to listOf("b", "c", "a"),
            LocalSortField.ModifiedAt to listOf("b", "c", "a"),
            LocalSortField.AddedAt to listOf("b", "c", "a"),
        )

        expectedAscendingIds.forEach { (field, ids) ->
            val ascending = sortLocalSongs(songs, field, ascending = true)
            val descending = sortLocalSongs(songs, field, ascending = false)
            assertEquals(ids, ascending.songs.map { it.id })
            assertEquals(ascending.songs.asReversed(), descending.songs)
            assertEquals(emptyMap<String, Int>(), ascending.sections)
            assertEquals(emptyMap<String, Int>(), descending.sections)
        }

        assertEquals(
            listOf("file_name", "artist", "year", "size", "modified_at", "added_at"),
            LocalSortField.entries.map { it.storageValue },
        )
        assertEquals(LocalSortField.AddedAt, LocalSortField.restore("added_at"))
        assertEquals(LocalSortField.FileName, LocalSortField.restore("unknown"))
    }

    @Test
    fun handlesEmptyAndLargeCollectionsWithoutLosingEntries() {
        assertEquals(LocalSongsContent(), sortLocalSongs(emptyList(), LocalSortField.FileName, true))

        val songs = (0 until 1_200).map { index ->
            val title = when (index % 6) {
                0 -> "${index % 10} 数字 $index"
                1 -> "#符号 $index"
                2 -> "Alpha $index"
                3 -> "Éclair $index"
                4 -> "中文 $index"
                else -> "繁體 $index"
            }
            testSong(index.toString(), title, artist = "Artist ${index % 17}")
        }

        val ascending = sortLocalSongs(songs, LocalSortField.FileName, ascending = true)
        val descending = sortLocalSongs(songs, LocalSortField.FileName, ascending = false)
        assertEquals(songs.size, ascending.songs.size)
        assertEquals(songs.map { it.id }.toSet(), ascending.songs.map { it.id }.toSet())
        val groupLabels = listOf("0", "#", "A", "E", "F", "Z")
        assertEquals(groupLabels.mapIndexed { index, label -> label to index * 200 }, ascending.sections.toList())
        assertEquals(listOf(0, 1, 2, 3, 5, 4), ascending.songs.chunked(200).map { group ->
            group.map { it.id.toInt() % 6 }.distinct().single()
        })
        assertEquals(ascending.songs.asReversed(), descending.songs)
        assertEquals(groupLabels.reversed().mapIndexed { index, label -> label to index * 200 }, descending.sections.toList())
    }

    private fun testSong(
        id: String,
        title: String,
        artist: String = "测试歌手",
        year: Int = 2024,
        sizeBytes: Long = 1,
        modifiedAt: Long = 1,
        addedAt: Long = 1,
    ): LocalSong = LocalSong(
        id = id,
        uri = "file:///nonexistent/$id.mp3",
        title = title,
        artist = artist,
        album = "测试专辑",
        durationMs = 180_000,
        sizeBytes = sizeBytes,
        mimeType = "audio/mpeg",
        sampleRate = 44_100,
        bitrate = 320_000,
        modifiedAt = modifiedAt,
        addedAt = addedAt,
        year = year,
        folder = "/nonexistent",
    )
}
