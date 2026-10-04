package com.leyu.melora.playback

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun recentContainerIdentityIncludesSourceAndSameSourceReplayMovesToFront() {
        // kw/tx 的榜单配置都存在原始 bangid=16，但属于不同平台命名空间。
        val kw = boardContainer("16", "kw", "酷我热歌榜")
        val tx = boardContainer("16", "tx", "韩国榜")
        UserLibrary.markContainerPlayed(kw)
        UserLibrary.markContainerPlayed(tx)
        UserLibrary.markContainerPlayed(boardContainer("17", "kw", "酷我新歌榜"))
        UserLibrary.markContainerPlayed(kw)

        val recent = UserLibrary.recentContainers.value
        assertEquals(
            listOf(
                Triple("kw", "16", "酷我热歌榜"),
                Triple("kw", "17", "酷我新歌榜"),
                Triple("tx", "16", "韩国榜"),
            ),
            recent.map { Triple(it.source, it.id, it.name) },
        )
        assertNotEquals(recent[0].key, recent[2].key)
    }

    @Test
    fun updatingRecentContainerCoverDoesNotCrossSources() {
        val legacy = JSONObject().put("recentContainers", JSONArray()
            .put(recentContainerJson("16", "kw", "酷我热歌榜"))
            .put(recentContainerJson("16", "tx", "韩国榜")))
        UserLibrary.replaceFromBackup(legacy.toString())

        val kw = UserLibrary.recentContainers.value.first { it.source == "kw" }
        UserLibrary.updateContainerCover(kw.key, "file:///kw-cover.jpg")

        assertEquals("file:///kw-cover.jpg", UserLibrary.recentContainers.value.first { it.source == "kw" }.img)
        assertNull(UserLibrary.recentContainers.value.first { it.source == "tx" }.img)
    }

    @Test
    fun sourceQualifiedRecentContainersSurviveDiskSaveAndReload() {
        UserLibrary.markContainerPlayed(boardContainer("16", "kw", "酷我热歌榜"))
        UserLibrary.markContainerPlayed(boardContainer("16", "tx", "韩国榜"))
        val file = fileField.get(UserLibrary) as File
        val persisted = file.readText()
        val savedRows = JSONObject(persisted).getJSONArray("recentContainers")
        assertEquals(2, savedRows.length())
        assertFalse(savedRows.getJSONObject(0).has("key"))

        UserLibrary.clearRecents()
        file.writeText(persisted)
        UserLibrary.reloadAfterRestore()

        assertEquals(setOf("kw", "tx"), UserLibrary.recentContainers.value.map { it.source }.toSet())
        assertEquals(setOf("16"), UserLibrary.recentContainers.value.map { it.id }.toSet())
    }

    @Test
    fun oldBackupWithoutDerivedKeysKeepsCrossSourceRowsAndDeduplicatesSameSource() {
        val legacy = JSONObject().put("recentContainers", JSONArray()
            .put(recentContainerJson("16", "kw", "酷我最新", updatedAt = 30L))
            .put(recentContainerJson("16", "tx", "韩国榜", updatedAt = 20L))
            .put(recentContainerJson("16", "kw", "酷我旧记录", updatedAt = 10L)))

        UserLibrary.replaceFromBackup(legacy.toString())

        val restored = UserLibrary.recentContainers.value
        assertEquals(listOf("kw", "tx"), restored.map { it.source })
        assertEquals(listOf("酷我最新", "韩国榜"), restored.map { it.name })
        assertTrue(restored.all { it.key.isNotBlank() })
        val backupRows = JSONObject(UserLibrary.exportSnapshot()).getJSONArray("recentContainers")
        assertEquals(2, backupRows.length())
        assertFalse(backupRows.getJSONObject(0).has("key"))
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

    private fun boardContainer(id: String, source: String, name: String) = UserLibrary.PlayContainer(
        kind = "board",
        id = id,
        name = name,
        img = null,
        source = source,
        queueId = "board.$source.$id",
    )

    private fun recentContainerJson(
        id: String,
        source: String,
        name: String,
        updatedAt: Long = 1L,
        kind: String = "board",
    ) = JSONObject()
        .put("kind", kind)
        .put("id", id)
        .put("name", name)
        .put("img", "")
        .put("source", source)
        .put("queueId", "queue:$source:$id")
        .put("updatedAt", updatedAt)
        .put("artist", "")
}
