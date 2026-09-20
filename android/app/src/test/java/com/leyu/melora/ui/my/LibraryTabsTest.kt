package com.leyu.melora.ui.my

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.countedTabLabel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryTabsTest {
    @Test
    fun multiDigitCountsStayInTheSameLabelWithoutDroppingDigits() {
        for (label in listOf("全部", "单曲", "歌单", "专辑", "歌单专辑", "听书", "节目")) {
            for (count in listOf(1, 13, 99, 100, 9999, Int.MAX_VALUE)) {
                val text = countedTabLabel(label, count, Color.Blue)
                assertEquals("$label $count", text.text)
                assertFalse(text.text.contains('\n'))
                assertEquals(1, text.spanStyles.size)
                val countSpan = text.spanStyles.single()
                assertEquals(label.length, countSpan.start)
                assertEquals(text.length, countSpan.end)
                assertEquals(10.sp, countSpan.item.fontSize)
                assertEquals(FontWeight.Bold, countSpan.item.fontWeight)
            }
        }
    }

    @Test
    fun absentOrZeroCountsKeepTheOriginalNoBadgePresentation() {
        for (count in listOf(null, 0, -1)) {
            val text = countedTabLabel("听书", count, Color.Blue)
            assertEquals("听书", text.text)
            assertTrue(text.spanStyles.isEmpty())
        }
    }

    @Test
    fun countColorChangesWithoutChangingLabelOrItsSpanBounds() {
        val selected = countedTabLabel("歌单专辑", 1234, Color.Blue)
        val unselected = countedTabLabel("歌单专辑", 1234, Color.Gray)
        assertEquals(selected.text, unselected.text)
        assertEquals(selected.spanStyles.single().start, unselected.spanStyles.single().start)
        assertEquals(selected.spanStyles.single().end, unselected.spanStyles.single().end)
        assertEquals(Color.Blue, selected.spanStyles.single().item.color)
        assertEquals(Color.Gray, unselected.spanStyles.single().item.color)
    }

    @Test
    fun recentAllCountIncludesBookContainersAsWellAsOtherCategories() {
        // 截图场景：16首单曲 + 13个歌单/专辑容器 + 1本听书，不能仍显示29。
        assertEquals(listOf(30, 16, 13, 1, 0), recentTabCounts(16, 13, 1, 0))
        assertEquals(listOf(1, 0, 0, 1, 0), recentTabCounts(0, 0, 1, 0))
        assertEquals(listOf(0, 0, 0, 0, 0), recentTabCounts(0, 0, 0, 0))
        val counts = recentTabCounts(83, 21, 7, 17)
        assertEquals(counts.drop(1).sum(), counts.first())
    }

    @Test
    fun homeRecentCountMatchesAllTabIncludingGroupedPrograms() {
        fun track(id: String, book: Boolean = false, album: String = "") = OnlineSong(
            JSONObject().put("source", "kw").put("songmid", id)
                .put("isBookChapter", book).put("albumId", album),
        )
        val songs = listOf(track("1"), track("2"), track("c1", true, "a"), track("c2", true, "a"), track("c3", true))
        val containers = listOf(
            UserLibrary.RecentContainer("playlist", "1", "歌单", null, "kw", "p", 1L),
            UserLibrary.RecentContainer("book", "2", "听书", null, "kw", "b", 2L),
        )
        assertEquals(recentTabCounts(2, 1, 1, 2).first(), recentEntryCount(songs, containers))
        assertEquals(0, recentEntryCount(emptyList(), emptyList()))
        assertEquals(2, recentEntryCount(emptyList(), containers))
    }

    @Test
    fun bookAlbumsUseExplicitIdentifiersOrLegacyKindNotTheirDisplayName() {
        fun playlist(id: String, name: String = "示例", kind: String? = null) = OnlinePlaylist(
            JSONObject().put("id", id).put("name", name).put("source", "kw").put("kind", kind),
        )
        assertTrue(playlist("book_album_42").isBookAlbum)
        assertTrue(playlist("42", kind = "book").isBookAlbum)
        assertFalse(playlist("ordinary-playlist", name = "听书专辑精选歌单").isBookAlbum)
    }

    @Test
    fun programsAreChapterTracksAndCanNavigateToTheirContainingBook() {
        val chapter = OnlineSong(JSONObject()
            .put("source", "kw").put("songmid", "chapter-3")
            .put("name", "示例 第三集").put("singer", "主播")
            .put("albumName", "示例").put("albumId", "42")
            .put("isBookChapter", true))
        assertTrue(chapter.isBookChapter)
        val book = requireNotNull(bookAlbumOf(chapter))
        assertEquals("book_album_42", book.id)
        assertEquals("示例", book.name)
        assertTrue(book.isBookAlbum)
        assertFalse(OnlineSong(JSONObject().put("name", "第三集").put("albumId", "42")).isBookChapter)
    }

    @Test
    fun musicAlbumIdentityRemainsSeparateByPlatformAndArtist() {
        val album = UserLibrary.FavoriteAlbum("叶惠美", "周杰伦", "kw", null)
        assertNotEquals(album.key, album.copy(source = "tx").key)
        assertNotEquals(album.key, album.copy(artist = "其他歌手").key)
        assertEquals(album.key, album.copy(img = "https://cover.test/album.png").key)
    }
}
