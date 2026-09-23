package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真正读写媒体文件和应用缓存，不连接任何音源，不触碰日常应用数据。 */
@RunWith(AndroidJUnit4::class)
class LyricCacheInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = FixtureContext(instrumentation.targetContext)

    @Before fun setup() = runBlocking {
        LyricRepository.clear(context)
        LocalMediaStore.init(context)
        LocalMediaStore.clear()
    }

    @After fun cleanup() = runBlocking {
        LyricRepository.clear(context)
        LocalMediaStore.clear()
        context.root.deleteRecursively()
        Unit
    }

    @Test fun physicalMp3TagChangeIsSeenWithoutRescanningLibrary() = checkPhysicalTags("fixture-128.mp3", ".mp3")
    @Test fun physicalFlacTagChangeIsSeenWithoutRescanningLibrary() = checkPhysicalTags("fixture-16.flac", ".flac")

    private fun checkPhysicalTags(asset: String, extension: String) = runBlocking {
        val file = File(context.filesDir, "song$extension")
        instrumentation.context.assets.open("audio/$asset").use { input -> file.outputStream().use(input::copyTo) }
        fun write(text: String) = DownloadMetadataWriter.write(file, extension, "测试歌曲", "测试歌手", "测试专辑", null, "[00:00.00]$text")
        write("旧歌词")
        val indexed = LocalSong("lyric-local", Uri.fromFile(file).toString(), "测试歌曲", "测试歌手", "测试专辑",
            1_000, file.length(), if (extension == ".mp3") "audio/mpeg" else "audio/flac",
            44_100, 128_000, file.lastModified(), 0, folder = context.filesDir.path)
        LocalMediaStore.replaceAll(listOf(indexed))
        val track = UiTrack.fromOnline(indexed.toOnlineSong())
        assertEquals("旧歌词", LyricRepository.load(context, track)!!.lines.single().text)
        write("新的正确歌词内容")
        assertTrue(file.setLastModified(indexed.modifiedAt + 2_000))
        // 故意不更新LocalMediaStore：验证读取正在访问的文件版本，而不是只信旧索引。
        assertEquals("新的正确歌词内容", LyricRepository.load(context, track)!!.lines.single().text)
        val audioBeforeClear = file.readBytes()
        LyricRepository.clear(context)
        assertFalse(File(context.cacheDir, "lyrics").exists())
        assertArrayEquals(audioBeforeClear, file.readBytes())
        assertEquals("新的正确歌词内容", LyricRepository.load(context, track)!!.lines.single().text)
    }

    @Test fun failedFilesystemCleanupIsNotReportedAsSuccess() {
        val dir = File(context.root, "readonly-cache").apply { mkdirs() }
        File(dir, "keep.json").writeText("cache")
        try {
            assertTrue(dir.setWritable(false, false))
            assertThrows(java.io.IOException::class.java) { LyricCacheStore().clear(dir) }
        } finally { dir.setWritable(true, true) }
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "lyric-lifecycle-tests").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
}
