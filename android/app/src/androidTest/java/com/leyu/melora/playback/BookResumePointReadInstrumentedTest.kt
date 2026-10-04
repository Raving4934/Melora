package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookResumePointReadInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("melora-progress", Context.MODE_PRIVATE)
    private var rememberProgress = true

    @Before
    fun setUp() {
        rememberProgress = MeloraSettings.rememberProgress.value
        MeloraSettings.rememberProgress.value = true
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        MeloraSettings.rememberProgress.value = rememberProgress
    }

    @Test
    fun readsOnlyExistingValidPointForCanonicalPlatformAndAlbum() {
        assertNull(BookListeningProgress.readAlbum(context, "kw", "book_album_read-a"))
        prefs.edit().putString("book:2:kw:broken", "not-json").commit()
        assertNull(BookListeningProgress.readAlbum(prefs, "kw", "broken"))

        val chapter = chapter("kw", "read-a-1", "kw:book_album_read-a")
        BookListeningProgress.select(prefs, chapter)
        assertNull("pointer without a saved position is not a resume point", BookListeningProgress.readAlbum(prefs, "kw", "read-a"))

        BookListeningProgress.persist(prefs, chapter, 12_500L, 80_000L, completed = false)
        val point = BookListeningProgress.readAlbum(context, "kw", "kw:book_album_read-a")
        assertEquals(chapter.uid, point?.song?.uid)
        assertEquals(12_500L, point?.positionMs ?: -1L)
        assertEquals("read-a", point?.song?.let { canonicalBookId(it.source, it.albumId) })
        assertNull(BookListeningProgress.readAlbum(prefs, "kw", "different-album"))
    }

    @Test
    fun rejectsPointerFromDifferentPlatformOrAlbumAndDisabledPreference() {
        val kwSong = chapter("kw", "read-kw", "same-album")
        val wySong = chapter("wy", "read-wy", "same-album")
        BookListeningProgress.persist(prefs, kwSong, 10_000L, 60_000L, completed = false)
        BookListeningProgress.persist(prefs, wySong, 20_000L, 60_000L, completed = false)

        assertEquals("read-kw", BookListeningProgress.readAlbum(prefs, "kw", "same-album")?.song?.songmid)
        assertEquals("read-wy", BookListeningProgress.readAlbum(prefs, "wy", "same-album")?.song?.songmid)

        val wyPointer = prefs.getString("book:2:wy:same-album", null)
        assertTrue(wyPointer != null)
        prefs.edit().putString("book:2:kw:same-album", wyPointer).commit()
        assertNull("pointer payload must match the requested source and canonical album", BookListeningProgress.readAlbum(prefs, "kw", "same-album"))

        MeloraSettings.rememberProgress.value = false
        assertNull(BookListeningProgress.readAlbum(context, "wy", "same-album"))
    }

    @Test
    fun completedZeroPositionRemainsAValidResumePoint() {
        val song = chapter("kw", "read-completed", "completed-book")
        BookListeningProgress.persist(prefs, song, 50_000L, 60_000L, completed = true)

        val point = BookListeningProgress.readAlbum(context, "kw", "completed-book")
        assertTrue(point?.completed == true)
        assertEquals(0L, point?.positionMs ?: -1L)
    }

    private fun chapter(source: String, uid: String, albumId: String) = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", uid)
            .put("name", uid)
            .put("albumId", albumId)
            .put("isBookChapter", true)
            .put("bookOrdinal", 2)
            .put("bookTotal", 10)
            .put("interval", "01:00"),
    )
}
