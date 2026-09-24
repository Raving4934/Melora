package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistImporterTest {
    private val link = PlaylistImportLink("wy", "123")
    private fun song(id: Int, source: String = "wy") = OnlineSong(JSONObject()
        .put("source", source).put("songmid", id.toString()).put("name", "相同歌名"))
    private fun page(ids: IntRange, total: Int = 0, page: Int = 1, all: Int = 0, limit: Int = 100) =
        SongPage(ids.map { song(it) }, total, page, all, pageSize = limit)

    @Test fun readsEveryPageBeyondTwoHundredSongsAndKeepsOrder() = runBlocking {
        val requested = mutableListOf<Int>()
        val progress = mutableListOf<PlaylistImportProgress>()
        val result = PlaylistImporter.read(link, progress::add) { number ->
            requested += number
            page(when (number) { 1 -> 1..100; 2 -> 101..200; else -> 201..250 }, 250, number, 3)
                .copy(playlistName = "个人歌单")
        }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals((1..250).map(Int::toString), result.songs.map { it.songmid })
        assertEquals("个人歌单", result.name)
        assertNull(result.warning)
        assertEquals(250, progress.last().loaded)
    }

    @Test fun unknownTotalUsesActualPageSizeInsteadOfAssumingThirty() = runBlocking {
        var calls = 0
        val result = PlaylistImporter.read(link) { n ->
            calls++
            if (n == 1) page(1..20, limit = 20) else page(21..37, page = 2, limit = 20)
        }
        assertEquals(2, calls)
        assertEquals(37, result.songs.size)
        assertNull(result.warning)
    }

    @Test fun deduplicatesByIdentityNotTitleAndDoesNotMergePlatforms() = runBlocking {
        val first = song(1)
        val result = PlaylistImporter.read(link) {
            SongPage(listOf(first, song(2), first, song(1, "tx")), 4, 1, 1)
        }
        assertEquals(listOf("wy_1", "wy_2", "tx_1"), result.songs.map { it.uid })
        assertEquals(1, result.duplicates)
        assertNull(result.warning)
    }

    @Test fun repeatedPagesStopWithoutPretendingToBeComplete() = runBlocking {
        var calls = 0
        val result = PlaylistImporter.read(link) { n -> calls++; page(1..100, 1000, n, 10) }
        assertEquals(2, calls)
        assertEquals(100, result.songs.size)
        assertTrue(result.warning.orEmpty().contains("重复页面"))
    }

    @Test fun laterPageFailurePreservesExplicitPartialResultForUserConfirmation() = runBlocking {
        val result = PlaylistImporter.read(link) { n ->
            if (n == 2) error("当前无法访问")
            page(1..100, 300, n, 3)
        }
        assertEquals(100, result.songs.size)
        assertTrue(result.warning.orEmpty().contains("第 2 页"))
    }

    @Test fun cancellationNeverBecomesAnImportablePartialResult() = runBlocking {
        val error = runCatching {
            PlaylistImporter.read(link) { n ->
                if (n == 2) throw CancellationException("用户取消")
                page(1..100, 300, n, 3)
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun emptyOrPrivatePlaylistIsNotSuccess() = runBlocking {
        assertTrue(runCatching { PlaylistImporter.read(link) { page(IntRange.EMPTY) } }.isFailure)
        val error = runCatching { PlaylistImporter.read(link) { error("私密歌单") } }.exceptionOrNull()
        assertEquals("私密歌单", error?.message)
    }

    @Test fun filteredRecordsAreClearlyReported() = runBlocking {
        val result = PlaylistImporter.read(link) { page(1..3, 5, all = 1).copy(rawCount = 5) }
        assertEquals(3, result.songs.size)
        assertTrue(result.warning.orEmpty().contains("2 条歌曲信息不可用"))
    }

    @Test fun truncatedLastPageOrPrematureEmptyPageCannotReportComplete() = runBlocking {
        val truncated = PlaylistImporter.read(link) { page(1..3, 10, all = 1) }
        assertTrue(truncated.warning.orEmpty().contains("只返回了 3"))
        val empty = PlaylistImporter.read(link) { n ->
            if (n == 1) page(1..3, 10, all = 2) else page(IntRange.EMPTY, 10, page = 2, all = 2)
        }
        assertTrue(empty.warning.orEmpty().contains("提前返回空页"))
    }

    @Test fun importLimitIsExplicitAndNeverReportedAsFullSuccess() = runBlocking {
        val result = PlaylistImporter.read(link) {
            page(1..PlaylistImporter.MAX_SONGS + 1, PlaylistImporter.MAX_SONGS + 1, all = 1)
        }
        assertEquals(PlaylistImporter.MAX_SONGS, result.songs.size)
        assertTrue(result.warning.orEmpty().contains("尚未获取完整"))
    }
    @Test fun entirelyUnavailablePageDoesNotHidePlayableSongsOnFollowingPages() = runBlocking {
        var calls = 0
        val result = PlaylistImporter.read(link) { n ->
            calls++
            when (n) {
                1 -> page(1..3, 9, n, 3)
                2 -> page(IntRange.EMPTY, 9, n, 3).copy(rawCount = 3)
                else -> page(7..9, 9, n, 3)
            }
        }
        assertEquals(3, calls)
        assertEquals(listOf("1", "2", "3", "7", "8", "9"), result.songs.map { it.songmid })
        assertTrue(result.warning.orEmpty().contains("3 条歌曲信息不可用"))
        assertFalse(result.warning.orEmpty().contains("重复页面"))
    }

}
