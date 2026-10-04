package com.leyu.melora.playback

import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookAlbumProgressPercentTest {
    @Test
    fun computesWholeBookLocationFromOrdinalAndCurrentChapterPosition() {
        assertEquals(25, bookAlbumProgressPercent(point(ordinal = 3, total = 0, position = 5_000, duration = 10_000), total = 10))
        assertEquals(50, bookAlbumProgressPercent(point(ordinal = 2, total = 4, position = 10_000, duration = 10_000), total = 100))
        assertEquals(100, bookAlbumProgressPercent(point(ordinal = 10, total = 10, completed = true)))
        assertEquals(0, bookAlbumProgressPercent(point(ordinal = 1, total = 3, position = 0, duration = 10_000)))
    }

    @Test
    fun rejectsMissingTotalsAndInvalidOrdinalsInsteadOfUsingChapterPercent() {
        assertNull(bookAlbumProgressPercent(point(ordinal = 1, total = 0, position = 5_000, duration = 10_000)))
        assertNull(bookAlbumProgressPercent(point(ordinal = 0, total = 10)))
        assertNull(bookAlbumProgressPercent(point(ordinal = 11, total = 10)))
        assertNull(bookAlbumProgressPercent(point(ordinal = null, total = 10)))
        assertNull(bookAlbumProgressPercent(point(ordinal = 2, total = 0), total = -1))
    }

    @Test
    fun clampsChapterPositionAndPrefersStoredCatalogTotal() {
        assertEquals(50, bookAlbumProgressPercent(point(ordinal = 2, total = 4, position = 20_000, duration = 10_000), total = 8))
        assertEquals(25, bookAlbumProgressPercent(point(ordinal = 2, total = 4), total = 8))
    }

    private fun point(
        ordinal: Int?,
        total: Int,
        position: Long = 0L,
        duration: Long = 0L,
        completed: Boolean = false,
    ): BookResumePoint {
        val raw = JSONObject()
            .put("source", "kw")
            .put("songmid", "chapter-$ordinal-$total")
            .put("name", "chapter")
            .put("isBookChapter", true)
        if (ordinal != null) raw.put("bookOrdinal", ordinal)
        if (total > 0) raw.put("bookTotal", total)
        return BookResumePoint(OnlineSong(raw), position, duration, completed)
    }
}
