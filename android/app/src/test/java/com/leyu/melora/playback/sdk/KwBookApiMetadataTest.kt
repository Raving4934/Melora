package com.leyu.melora.playback.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwBookApiMetadataTest {
    @Test
    fun detailMetadataReadsIntroHeatAndCatalogFacts() {
        val metadata = KwBookApi.bookMetadataFromDetail(JSONObject("""
            {
              "albuminfo": "<b>真实节目简介</b>",
              "playCnt": "309997779",
              "total": "1250",
              "artist": "演播者",
              "pic": "https://img1.kuwo.cn/cover.jpg",
              "releaseDate": "2024-03-05",
              "lang": "国语"
            }
        """))

        assertEquals("真实节目简介", metadata.description)
        assertEquals(309_997_779L, metadata.playCount)
        assertEquals(1250, metadata.total)
        assertEquals("演播者", metadata.author)
        assertEquals("https://img1.kuwo.cn/cover.jpg", metadata.artwork)
        assertEquals("2024-03-05", metadata.releaseDate)
        assertEquals("国语", metadata.language)
    }

    @Test
    fun detailMetadataFallsBackFromZeroTotalToMusicnum() {
        val metadata = KwBookApi.bookMetadataFromDetail(JSONObject("""
            {"total": "0", "musicnum": "125", "songnum": "250"}
        """))

        assertEquals(125, metadata.total)
    }

    @Test
    fun detailMetadataFallsBackFromInvalidAliasesToSongnum() {
        val metadata = KwBookApi.bookMetadataFromDetail(JSONObject("""
            {"total": "unknown", "musicnum": "invalid", "songnum": "250"}
        """))

        assertEquals(250, metadata.total)
    }

    @Test
    fun detailMetadataPrefersValidTotalOverFallbackAliases() {
        val metadata = KwBookApi.bookMetadataFromDetail(JSONObject("""
            {"total": "300", "musicnum": "125", "songnum": "250"}
        """))

        assertEquals(300, metadata.total)
    }

    @Test
    fun liveGhostCatalogKeepsPromosAndEpisode100AtItsSourceOrdinal() {
        val firstResponse = catalogResponse(page = 1)
        val firstRows = firstResponse.getJSONObject("data").getJSONArray("musicList")
        assertEquals(listOf(1, 2, 8), (0 until firstRows.length()).map { firstRows.getJSONObject(it).getInt("track") })

        val firstPage = KwBookApi.albumPageFromResponse("70703385", 1, firstResponse)
        assertEquals(
            listOf("484763691", "481362179", "481592686"),
            firstPage.items.map { it.raw.optString("rid") },
        )
        assertEquals(
            listOf(
                "【鬼吹灯主题曲】磷火（剧情版) - 郑希&景向谁依合唱",
                "全新《鬼吹灯》有声剧-悬疑概念先行预告",
                "全新《鬼吹灯》有声剧-剧情预告，精彩抢先听",
            ),
            firstPage.items.map { it.name },
        )
        assertTrue(firstPage.items.all { !Regex("第\\d+集").containsMatchIn(it.name) })
        assertEquals(listOf(1, 2, 3), firstPage.items.map { it.raw.getInt("bookOrdinal") })
        assertEquals(listOf(1, 1, 1), firstPage.items.map { it.raw.getInt("bookPage") })
        assertTrue(firstPage.hasMore)

        val secondResponse = catalogResponse(page = 2)
        val secondRows = secondResponse.getJSONObject("data").getJSONArray("musicList")
        assertEquals(listOf(106, 107, 108), (0 until secondRows.length()).map { secondRows.getJSONObject(it).getInt("track") })

        val secondPage = KwBookApi.albumPageFromResponse("70703385", 2, secondResponse)
        assertEquals(
            listOf("482611833", "482612023", "482611838"),
            secondPage.items.map { it.raw.optString("rid") },
        )
        assertEquals(listOf(101, 102, 103), secondPage.items.map { it.raw.getInt("bookOrdinal") })
        assertEquals(listOf(2, 2, 2), secondPage.items.map { it.raw.getInt("bookPage") })
        assertEquals("第100集 龙岭迷窟 41 藏宝洞", secondPage.items[2].name)
        assertEquals(297, secondPage.total)
        assertTrue(secondPage.hasMore)
    }

    @Test
    fun chapterCarriesOnlyReliableBookTotalAndKeepsPerformerMetadata() {
        val row = JSONObject()
            .put("rid", "chapter-id")
            .put("name", "章节")
            .put("artist", "章节主播")

        val chapter = checkNotNull(KwBookApi.chapter(row, "专辑", "album-id", KwBookApi.BookMetadata(total = 125, author = "专辑作者")))
        assertEquals(125, chapter.raw.optInt("bookTotal"))
        assertEquals("章节主播", chapter.singer)

        row.remove("artist")
        val fallbackAuthor = checkNotNull(KwBookApi.chapter(row, "专辑", "album-id", KwBookApi.BookMetadata(total = 0, author = "专辑作者")))
        assertFalse(fallbackAuthor.raw.has("bookTotal"))
        assertEquals("专辑作者", fallbackAuthor.singer)
    }

    @Test
    fun bookPageHasMoreUsesTotalToStopOnAnExactLastPage() {
        assertFalse(KwBookApi.bookPageHasMore(page = 1, total = 100, itemCount = 100))
        assertTrue(KwBookApi.bookPageHasMore(page = 1, total = 101, itemCount = 100))
        assertTrue(KwBookApi.bookPageHasMore(page = 2, total = 201, itemCount = 100))
        assertFalse(KwBookApi.bookPageHasMore(page = 3, total = 201, itemCount = 1))
        assertFalse(KwBookApi.bookPageHasMore(page = 2, total = 0, itemCount = 0))
    }

    @Test
    fun bookChapterSnapshotKeepsCursorTotalAndStopsDuplicatePages() {
        val metadata = KwBookApi.BookMetadata(total = 200, author = "演播者")
        val firstPage = KwBookApi.BookChapters(
            items = List(100) { chapter("first-$it") },
            hasMore = true,
            metadata = metadata,
            page = 1,
        )
        val secondPage = KwBookApi.BookChapters(
            items = List(100) { chapter("second-$it") },
            hasMore = false,
            metadata = metadata,
            page = 2,
        )

        val merged = firstPage.append(secondPage, requestedPage = 2)
        assertEquals(2, merged.page)
        assertEquals(200, merged.total)
        assertEquals(200, merged.items.size)
        assertFalse(merged.hasMore)

        val duplicate = merged.append(secondPage, requestedPage = 3)
        assertEquals(3, duplicate.page)
        assertEquals(200, duplicate.items.size)
        assertFalse(duplicate.hasMore)
    }

    private fun catalogResponse(page: Int): JSONObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/book-catalog-70703385.json"))
        val fixture = stream.bufferedReader().use { JSONObject(it.readText()) }
        return fixture.getJSONObject("pages").getJSONObject(page.toString())
    }

    private fun chapter(id: String) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", id)
            .put("name", id)
            .put("isBookChapter", true),
    )
}
