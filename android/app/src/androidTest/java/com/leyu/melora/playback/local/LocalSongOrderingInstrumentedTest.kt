package com.leyu.melora.playback.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        assertEquals("0", localSongSection(songs[0], LocalSortField.FileName))
        assertEquals("#", localSongSection(songs[1], LocalSortField.FileName))
        assertEquals("#", localSongSection(songs[2], LocalSortField.FileName))
        assertEquals("E", localSongSection(songs[3], LocalSortField.FileName))
        assertEquals("A", localSongSection(songs[4], LocalSortField.FileName))
        assertEquals("F", localSongSection(songs[5], LocalSortField.FileName))
        assertEquals("Z", localSongSection(songs[6], LocalSortField.FileName))

        val ordered = sortLocalSongs(songs, LocalSortField.FileName, ascending = true)
        val sections = ordered.map { localSongSection(it, LocalSortField.FileName) }
        assertEquals(listOf("0", "#", "#", "A", "E", "F", "Z"), sections)
        assertTrue(sections.zipWithNext().all { (left, right) ->
            LocalSongIndexLabels.indexOf(left) <= LocalSongIndexLabels.indexOf(right)
        })

        val starts = localSongSectionStarts(ordered, LocalSortField.FileName)
        assertEquals(listOf("0", "#", "A", "E", "F", "Z"), starts.keys.toList())
        starts.forEach { (label, index) ->
            assertEquals(label, localSongSection(ordered[index], LocalSortField.FileName))
        }
    }

    @Test
    fun sortsArtistsWithSameArtistUsingTitleAndReversesByActualListOrder() {
        val songs = listOf(
            testSong("zoo", "Zoo", artist = "Alpha"),
            testSong("apple", "Apple", artist = "Alpha"),
            testSong("beta", "Beta", artist = "Beta"),
            testSong("zhou", "周", artist = "周杰伦"),
        )

        val ascending = sortLocalSongs(songs, LocalSortField.Artist, ascending = true)
        assertEquals(listOf("apple", "zoo", "beta", "zhou"), ascending.map { it.id })
        assertEquals(
            ascending.asReversed().map { it.id },
            sortLocalSongs(songs, LocalSortField.Artist, ascending = false).map { it.id },
        )

        val starts = localSongSectionStarts(ascending, LocalSortField.Artist)
        assertEquals(listOf("A", "B", "Z"), starts.keys.toList())
        assertEquals(emptyMap<String, Int>(), localSongSectionStarts(ascending, LocalSortField.Year))
        assertEquals("", localSongSection(songs.first(), LocalSortField.Year))
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
            assertEquals(ids, ascending.map { it.id })
            assertEquals(
                ascending.asReversed().map { it.id },
                sortLocalSongs(songs, field, ascending = false).map { it.id },
            )
            assertEquals(emptyMap<String, Int>(), localSongSectionStarts(ascending, field))
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
        assertTrue(sortLocalSongs(emptyList(), LocalSortField.FileName, true).isEmpty())
        assertTrue(localSongSectionStarts(emptyList(), LocalSortField.FileName).isEmpty())

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
        assertEquals(songs.size, ascending.size)
        assertEquals(songs.map { it.id }.toSet(), ascending.map { it.id }.toSet())
        assertFalse(ascending.zipWithNext().any { (left, right) ->
            LocalSongIndexLabels.indexOf(localSongSection(left, LocalSortField.FileName)) >
                LocalSongIndexLabels.indexOf(localSongSection(right, LocalSortField.FileName))
        })
        assertEquals(
            ascending.asReversed().map { it.id },
            sortLocalSongs(songs, LocalSortField.FileName, ascending = false).map { it.id },
        )
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
