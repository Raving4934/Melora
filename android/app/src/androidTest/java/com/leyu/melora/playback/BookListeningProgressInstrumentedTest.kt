package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookListeningProgressInstrumentedTest {
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
    fun switchingBooksKeepsEachBookLastChapterAndUidPosition() {
        val firstA = chapter("kw", "resume-a-1", "kw:book_album_book-a")
        val lastA = chapter("kw", "resume-a-2", "book-a")
        val onlyB = chapter("kw", "resume-b-1", "book-b")

        BookListeningProgress.persist(prefs, firstA, 15_000L, 90_000L, completed = false)
        BookListeningProgress.persist(prefs, onlyB, 35_000L, 80_000L, completed = false)
        BookListeningProgress.persist(prefs, lastA, 42_000L, 90_000L, completed = false)

        val resumedA = BookListeningProgress.read(context, firstA)
        val resumedB = BookListeningProgress.read(context, onlyB)
        assertEquals(lastA.uid, resumedA.song.uid)
        assertEquals(42_000L, resumedA.positionMs)
        assertEquals(90_000L, resumedA.durationMs)
        assertEquals(onlyB.uid, resumedB.song.uid)
        assertEquals(35_000L, resumedB.positionMs)
        assertEquals(15_000L, prefs.getLong(firstA.uid, 0L))
        assertEquals(35_000L, prefs.getLong(onlyB.uid, 0L))
        assertEquals(42_000L, prefs.getLong(lastA.uid, 0L))

        val pointer = prefs.all.values.filterIsInstance<String>().first { value ->
            runCatching { JSONObject(value).optString("uid") == lastA.uid }.getOrDefault(false)
        }
        val pointerJson = JSONObject(pointer)
        assertEquals(lastA.uid, pointerJson.optString("uid"))
        assertTrue(pointerJson.has("raw"))
        assertFalse("章节位置仍只放在 UID Long 键", pointerJson.has("positionMs"))
    }

    @Test
    fun sameSourceAndBookSharePointerButDifferentSourceOrBookNeverCrosses() {
        val first = chapter("kw", "same-first", "book-shared")
        val last = chapter("kw", "same-last", "book-shared")
        val otherSource = chapter("wy", "same-id", "book-shared")
        val otherBook = chapter("kw", "other-book", "different-book")

        BookListeningProgress.persist(prefs, first, 10_000L, 70_000L, completed = false)
        BookListeningProgress.persist(prefs, last, 20_000L, 70_000L, completed = false)
        BookListeningProgress.persist(prefs, otherSource, 30_000L, 70_000L, completed = false)

        assertEquals(last.uid, BookListeningProgress.read(context, first).song.uid)
        assertEquals(20_000L, BookListeningProgress.read(context, first).positionMs)
        assertEquals(otherSource.uid, BookListeningProgress.read(context, otherSource).song.uid)
        assertEquals(30_000L, BookListeningProgress.read(context, otherSource).positionMs)
        assertEquals(otherBook.uid, BookListeningProgress.read(context, otherBook).song.uid)
        assertNotEquals(last.uid, BookListeningProgress.read(context, otherSource).song.uid)
    }

    @Test
    fun legacyUidBookmarkWithoutBookPointerUsesFallbackSong() {
        val legacy = chapter("kw", "legacy-no-book-pointer", "legacy-book")
        prefs.edit()
            .putLong(legacy.uid, 27_500L)
            .putLong("duration:${legacy.uid}", 80_000L)
            .commit()

        val point = BookListeningProgress.read(context, legacy)
        assertEquals(legacy.uid, point.song.uid)
        assertEquals(27_500L, point.positionMs)
        assertEquals(80_000L, point.durationMs)
        assertFalse(point.completed)
    }

    @Test
    fun restartPointsBookAtTargetAndClearsOnlyItsPositionAndCompletion() {
        val target = chapter("kw", "restart-target", "restart-book")
        val otherChapter = chapter("kw", "restart-other", "restart-book")
        BookListeningProgress.persist(prefs, target, 50_000L, 80_000L, completed = true)
        BookListeningProgress.persist(prefs, otherChapter, 31_000L, 90_000L, completed = false)
        val before = BookListeningProgress.updates.value

        BookListeningProgress.restart(context, target)

        val point = BookListeningProgress.read(context, otherChapter)
        assertEquals(target.uid, point.song.uid)
        assertEquals(0L, point.positionMs)
        assertFalse(point.completed)
        assertFalse(prefs.contains(target.uid))
        assertFalse(prefs.contains("completed:${target.uid}"))
        assertEquals(31_000L, prefs.getLong(otherChapter.uid, 0L))
        assertNotEquals(before, BookListeningProgress.updates.value)
    }

    @Test
    fun readWhenRememberProgressOffIgnoresOldChapterAndPosition() {
        val previous = chapter("kw", "disabled-previous", "disabled-book")
        val fallback = chapter("kw", "disabled-fallback", "disabled-book")
        BookListeningProgress.persist(prefs, previous, 40_000L, 90_000L, completed = true)
        MeloraSettings.rememberProgress.value = false

        val point = BookListeningProgress.read(context, fallback)
        assertEquals(fallback.uid, point.song.uid)
        assertEquals(0L, point.positionMs)
        assertFalse(point.completed)
    }

    @Test
    fun updatesOnlyEmitsWhenProgressPreferencesActuallyChange() {
        val song = chapter("kw", "updates-noop", "updates-book")
        val before = BookListeningProgress.updates.value
        BookListeningProgress.persist(prefs, song, 15_000L, 90_000L, completed = false)
        val changed = BookListeningProgress.updates.value
        BookListeningProgress.persist(prefs, song, 15_000L, 90_000L, completed = false)

        assertEquals(before + 1L, changed)
        assertEquals("same checkpoint must not emit or rewrite", changed, BookListeningProgress.updates.value)
    }

    private fun chapter(source: String, uid: String, bookId: String): OnlineSong = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", uid)
            .put("name", uid)
            .put("albumId", bookId)
            .put("isBookChapter", true)
            .put("interval", "01:30"),
    )
}
