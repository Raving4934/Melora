package com.leyu.melora.playback.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LocalTagReaderTest {
    private lateinit var context: Context
    private lateinit var cacheRoot: File
    private lateinit var filesRoot: File

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = "${System.currentTimeMillis()}-${Thread.currentThread().id}"
        cacheRoot = File(target.cacheDir, "local-tag-reader-test-$suffix")
        filesRoot = File(target.filesDir, "local-tag-reader-test-$suffix")
        cacheRoot.mkdirs()
        filesRoot.mkdirs()
        context = IsolatedContext(target, cacheRoot, filesRoot)
        resetIndex()
        LocalTagReader.clearCoverCache(context)
    }

    @After
    fun tearDown() {
        runCatching { LocalTagReader.clearCoverCache(context) }
        resetIndex()
        cacheRoot.deleteRecursively()
        filesRoot.deleteRecursively()
    }

    @Test
    fun memoryCoverCacheIsBoundedAndUsesLeastRecentlyUsedEviction() {
        val songs = (0..256).map { testSong("song-$it") }
        songs.dropLast(1).forEach { song ->
            assertNotNull(LocalTagReader.cacheCover(context, song, byteArrayOf(1)))
        }

        assertNotNull(LocalTagReader.coverUri(context, songs.first()))
        assertNotNull(LocalTagReader.cacheCover(context, songs.last(), byteArrayOf(2)))

        val cache = coverCache()
        assertEquals(256, cache.size)
        assertTrue(cache.containsKey("song-0-1"))
        assertFalse(cache.containsKey("song-1-1"))
    }

    @Test
    fun diskCacheIsBoundedByCoverCount() {
        repeat(513) { index ->
            assertNotNull(LocalTagReader.cacheCover(context, testSong("disk-$index"), byteArrayOf(1)))
        }

        val files = File(cacheRoot, LOCAL_COVER_CACHE_DIR).listFiles().orEmpty()
        assertTrue(files.size <= 512)
    }

    @Test
    fun staleEmbeddedReadCannotCommitAfterClear() {
        val generationField = LocalTagReader::class.java.getDeclaredField("coverGeneration").apply {
            isAccessible = true
        }
        val oldGeneration = generationField.getLong(LocalTagReader)
        val commit = LocalTagReader::class.java.getDeclaredMethod(
            "commitCover",
            Context::class.java,
            LocalSong::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
            ByteArray::class.java,
        ).apply { isAccessible = true }

        LocalTagReader.clearCoverCache(context)

        val result = commit.invoke(
            LocalTagReader,
            context,
            testSong("stale"),
            "stale-1",
            oldGeneration,
            byteArrayOf(1, 2, 3),
        ) as String?

        assertNull(result)
        assertFalse(File(cacheRoot, LOCAL_COVER_CACHE_DIR).exists())
        assertTrue(coverCache().isEmpty())
    }

    @Test
    fun clearingCacheRemovesFilesAndInvalidatesIndexedCover() {
        val song = testSong("indexed").copy(infoFilled = true)
        val cached = LocalTagReader.cacheCover(context, song, byteArrayOf(1, 2, 3))
        assertNotNull(cached)
        seedIndex(song.copy(coverUri = cached))

        LocalTagReader.clearCoverCache(context)

        assertFalse(File(cacheRoot, LOCAL_COVER_CACHE_DIR).exists())
        assertNull(LocalMediaStore.find(song.id)?.coverUri)
        assertFalse(LocalMediaStore.find(song.id)?.infoFilled == true)
    }

    private fun resetIndex() {
        File(filesRoot, "local_media.json").delete()
        File(filesRoot, "local_media.json.tmp").delete()
        LocalMediaStore.init(context)
    }

    private fun seedIndex(song: LocalSong) {
        File(filesRoot, "local_media.json").writeText(
            JSONObject()
                .put("version", 1)
                .put("savedAt", 0)
                .put("songs", JSONArray().put(song.toJson()))
                .toString(),
        )
        LocalMediaStore.init(context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun coverCache(): Map<String, String> =
        LocalTagReader::class.java.getDeclaredField("coverCache").let { field ->
            field.isAccessible = true
            field.get(LocalTagReader) as Map<String, String>
        }

    private fun testSong(id: String): LocalSong = LocalSong(
        id = id,
        uri = "file:///nonexistent/$id.mp3",
        title = id,
        artist = "测试歌手",
        album = "测试专辑",
        durationMs = 180_000,
        sizeBytes = 1,
        mimeType = "audio/mpeg",
        sampleRate = 44_100,
        bitrate = 320_000,
        modifiedAt = 1,
        addedAt = 1,
        folder = "/nonexistent",
    )
}

private class IsolatedContext(
    base: Context,
    private val isolatedCacheDir: File,
    private val isolatedFilesDir: File,
) : ContextWrapper(base) {
    override fun getCacheDir(): File = isolatedCacheDir
    override fun getFilesDir(): File = isolatedFilesDir
    override fun getApplicationContext(): Context = this
}
