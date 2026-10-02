package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.sdk.OnlineSong
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistImportStorageTest {
    companion object {
        private val context by lazy {
            PlaylistLibraryContext(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        private val libraryFileField = UserLibrary.javaClass.getDeclaredField("file").apply { isAccessible = true }
        private val previousFlows = mutableListOf<Pair<MutableStateFlow<Any?>, Any?>>()
        private var previousFile: Any? = null

        @BeforeClass @JvmStatic fun isolateLibraryFixture() = LocalMediaStore.withIndexLock { _ ->
            synchronized(BackupStateLock.monitor) {
                context.reset()
                previousFile = libraryFileField.get(UserLibrary)
                for (field in UserLibrary.javaClass.declaredFields) {
                    if (!MutableStateFlow::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val flow = field.get(UserLibrary) as MutableStateFlow<Any?>
                    previousFlows += flow to flow.value
                }
                libraryFileField.set(UserLibrary, null)
                UserLibrary.init(context)
            }
        }

        @AfterClass @JvmStatic fun restoreLibraryFixture() = LocalMediaStore.withIndexLock { _ ->
            synchronized(BackupStateLock.monitor) {
                libraryFileField.set(UserLibrary, previousFile)
                previousFlows.forEach { (flow, value) -> flow.value = value }
                previousFlows.clear()
                context.cleanup()
            }
        }
    }

    private val libraryFile get() = File(context.filesDir, "user-library.json")

    @Before
    fun resetLibrary() = LocalMediaStore.withIndexLock { _ ->
        synchronized(BackupStateLock.monitor) {
            val filesDir = context.filesDir
            check(filesDir.deleteRecursively()) { "无法清理歌单存储测试目录" }
            check(filesDir.mkdirs()) { "无法创建歌单存储测试目录" }
            UserLibrary.init(context)
            UserLibrary.replaceFromBackup("{}")
        }
    }

    @Test
    fun createsLargeOrderedBatchAndKeepsSameNameImportsIndependentAfterReload() {
        val firstPlatformSong = song("qq", "shared", "首次保留")
        val otherPlatformSong = song("kw", "shared", "跨平台歌曲")
        val batch = listOf(firstPlatformSong, otherPlatformSong) +
            (0 until 249).map { song("qq", "batch-$it") } +
            listOf(song("qq", "shared", "重复项"), song("kw", "shared", "重复项"))
        assertTrue(batch.size > 200)

        val first = UserLibrary.createPlaylist("跨平台同名", batch)
        val secondPlatformSongCopy = song("kw", "shared", "另一次导入")
        val second = UserLibrary.createPlaylist("跨平台同名", listOf(secondPlatformSongCopy))
        val third = UserLibrary.createPlaylist("连续创建")

        assertNotEquals(first.id, second.id)
        assertNotEquals(second.id, third.id)
        assertEquals("跨平台同名", first.name)
        assertEquals("跨平台同名", second.name)
        val expectedUids = (listOf(firstPlatformSong, otherPlatformSong) +
            (0 until 249).map { song("qq", "batch-$it") }).map { it.uid }
        assertEquals(expectedUids, first.songs.map { it.uid })
        assertEquals("首次保留", first.songs.first().name)
        assertEquals("跨平台歌曲", first.songs[1].name)
        assertEquals(listOf(secondPlatformSongCopy.uid), second.songs.map { it.uid })

        val storedPlaylists = JSONObject(libraryFile.readText()).getJSONArray("playlists")
        assertEquals(3, storedPlaylists.length())
        assertEquals(expectedUids.size, storedPlaylists.getJSONObject(0).getJSONArray("songs").length())

        UserLibrary.reloadAfterRestore()
        assertEquals(listOf(first.id, second.id, third.id), UserLibrary.playlists.value.map { it.id })
        assertEquals(expectedUids, UserLibrary.playlists.value.first().songs.map { it.uid })
        assertEquals(listOf(secondPlatformSongCopy.uid), UserLibrary.playlists.value[1].songs.map { it.uid })
    }

    @Test
    fun failedCreateDoesNotPublishOrChangePrimaryOrBackupStorage() {
        val existing = UserLibrary.createPlaylist("已有歌单", listOf(song("qq", "existing")))
        val beforeSnapshot = UserLibrary.exportSnapshot()
        val beforePlaylists = UserLibrary.playlists.value.map { playlist ->
            playlist.id to playlist.songs.map { it.uid }
        }
        val beforePrimary = libraryFile.readBytes()
        val backupFile = File(context.filesDir, "user-library.json.bak")
        val beforeBackup = backupFile.readBytes()
        val tempDirectory = File(context.filesDir, "user-library.json.tmp")
        assertTrue(tempDirectory.mkdir())

        var failed = false
        try {
            UserLibrary.createPlaylist("写入失败", listOf(song("kw", "not-persisted")))
        } catch (_: IOException) {
            failed = true
        } finally {
            tempDirectory.deleteRecursively()
        }

        assertTrue(failed)
        assertEquals(beforeSnapshot, UserLibrary.exportSnapshot())
        assertEquals(beforePlaylists, UserLibrary.playlists.value.map { playlist ->
            playlist.id to playlist.songs.map { it.uid }
        })
        assertArrayEquals(beforePrimary, libraryFile.readBytes())
        assertArrayEquals(beforeBackup, backupFile.readBytes())

        UserLibrary.reloadAfterRestore()
        assertEquals(listOf(existing.id), UserLibrary.playlists.value.map { it.id })
        assertEquals(listOf("qq_existing"), UserLibrary.playlists.value.single().songs.map { it.uid })
    }

    private fun song(source: String, id: String, name: String = id) = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", id)
            .put("name", name)
            .put("singer", "合成测试歌手"),
    )

    /** 独立生命周期的用户库夹具，不与备份往返测试或真实应用数据共用目录。 */
    private class PlaylistLibraryContext(base: Context) : ContextWrapper(base) {
        private val root = File(base.cacheDir, "isolated-playlist-import-tests")

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        fun reset() {
            check(!root.exists() || root.deleteRecursively()) { "无法清理歌单存储测试夹具" }
            check(root.mkdirs()) { "无法创建歌单存储测试夹具" }
        }
        fun cleanup() {
            check(!root.exists() || root.deleteRecursively()) { "无法删除歌单存储测试夹具" }
        }
    }
}
