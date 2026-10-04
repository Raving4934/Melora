package com.leyu.melora.playback

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserLibraryRecentProgramInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val progressPrefs get() = context.getSharedPreferences("melora-progress", Context.MODE_PRIVATE)
    private val fileField = UserLibrary::class.java.getDeclaredField("file").apply { isAccessible = true }
    private var previousFile: Any? = null
    private var librarySnapshot: String? = null
    private var isolatedDir: File? = null
    private var progressKeys: Set<String>? = null

    @Before
    fun setUp() {
        progressKeys = progressPrefs.all.keys.toSet()
        UserLibrary.init(context)
        librarySnapshot = UserLibrary.exportSnapshot()
        previousFile = fileField.get(UserLibrary)
        val directory = Files.createTempDirectory("melora-recent-program").toFile()
        isolatedDir = directory
        fileField.set(UserLibrary, File(directory, "user-library.json"))
        UserLibrary.replaceFromBackup("{}")
    }

    @After
    fun tearDown() {
        try {
            progressKeys?.let { originalKeys ->
                val editor = progressPrefs.edit()
                (progressPrefs.all.keys - originalKeys).forEach(editor::remove)
                editor.commit()
            }
        } finally {
            try {
                librarySnapshot?.let(UserLibrary::replaceFromBackup)
            } finally {
                previousFile?.let { fileField.set(UserLibrary, it) }
                isolatedDir?.deleteRecursively()
            }
        }
    }

    @Test
    fun removingBookProgramOnlyRemovesSameBookRecentSongsAndBookContainer() {
        val target = chapter("kw", "remove-target", "kw:book_album_fixture")
        val sameBookChapter = chapter("kw", "remove-same-book", "fixture")
        val sameIdOtherSource = chapter("wy", "remove-other-source", "fixture")
        val otherBook = chapter("kw", "remove-other-book", "other")
        val music = OnlineSong(JSONObject()
            .put("source", "kw").put("songmid", "remove-music").put("name", "音乐")
            .put("albumId", "fixture"))

        listOf(target, sameBookChapter, sameIdOtherSource, otherBook, music).forEach(UserLibrary::markPlayed)
        UserLibrary.markContainerPlayed(bookContainer("book_album_fixture", "kw"))
        UserLibrary.markContainerPlayed(bookContainer("fixture", "wy"))
        UserLibrary.markContainerPlayed(bookContainer("book_album_other", "kw"))
        UserLibrary.markContainerPlayed(
            UserLibrary.PlayContainer("playlist", "book_album_fixture", "同ID歌单", null, "kw", "queue"),
        )

        progressPrefs.edit()
            .putLong(target.uid, 45_000L)
            .putLong("duration:${target.uid}", 90_000L)
            .putBoolean("completed:${target.uid}", true)
            .commit()
        val existingProgressKeys = checkNotNull(progressKeys)
        val progressSnapshot = progressPrefs.all.filterKeys { it !in existingProgressKeys }
        UserLibrary.setFavorites(listOf(target), favorite = true)

        UserLibrary.removeRecentProgram(target)

        assertEquals(
            setOf(sameIdOtherSource.uid, otherBook.uid, music.uid),
            UserLibrary.recents.value.map { it.uid }.toSet(),
        )
        assertEquals(
            setOf(
                Triple("book", "fixture", "wy"),
                Triple("book", "book_album_other", "kw"),
                Triple("playlist", "book_album_fixture", "kw"),
            ),
            UserLibrary.recentContainers.value.map { Triple(it.kind, it.id, it.source) }.toSet(),
        )
        assertEquals(45_000L, progressPrefs.getLong(target.uid, 0L))
        assertEquals(90_000L, progressPrefs.getLong("duration:${target.uid}", 0L))
        assertTrue(progressPrefs.getBoolean("completed:${target.uid}", false))
        assertEquals(progressSnapshot, progressPrefs.all.filterKeys { it !in existingProgressKeys })
        assertTrue("移除历史不能改变收藏", UserLibrary.isFavorite(target.uid))
    }

    @Test fun legacyProgramWithoutAlbumCanBeRemovedWithoutRemovingOtherPrograms() {
        val missingAlbum = chapter("kw", "missing-album", "")
        val other = chapter("kw", "other-missing-album", "")
        UserLibrary.markPlayed(missingAlbum)
        UserLibrary.markPlayed(other)
        UserLibrary.removeRecentProgram(missingAlbum)
        assertEquals(listOf(other.uid), UserLibrary.recents.value.map { it.uid })
    }

    private fun chapter(source: String, uid: String, bookId: String) = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", uid)
            .put("name", uid)
            .put("albumId", bookId)
            .put("isBookChapter", true),
    )

    private fun bookContainer(id: String, source: String) = UserLibrary.PlayContainer(
        kind = "book",
        id = id,
        name = id,
        img = null,
        source = source,
        queueId = "queue:$source:$id",
    )
}
