package com.leyu.melora.playback.local

import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.UiTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
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

    @Test fun queuedLyricWriteRejectsReplacedFileWithOrWithoutRescan() = runBlocking {
        for (rescan in listOf(false, true)) for (empty in listOf(false, true)) withTagWriteFixture { file, song, player ->
            val track = requireNotNull(player.value.current)
            LocalTagFiller.requestLyricWrite(context, track, PlayerLyric(track.uid, song.title, song.artist,
                listOf(LyricLine(0, "旧曲歌词")), "local"))
            val replacement = if (empty) byteArrayOf() else InstrumentationRegistry.getInstrumentation().context.assets
                .open("audio/fixture-320.mp3").use { it.readBytes() }
            file.writeBytes(replacement)
            assertTrue(file.setLastModified(song.modifiedAt)) // 同mtime下仍须检测变长或被截断的文件。
            if (rescan) LocalMediaStore.replaceAll(listOf(song.copy(title = "新文件",
                sizeBytes = file.length(), modifiedAt = file.lastModified())))
            val latest = LocalMediaStore.find(song.id)
            player.value = PlayerUiState()
            LocalTagFiller.consider(context, null)
            awaitTagJobs()
            org.junit.Assert.assertArrayEquals(replacement, file.readBytes())
            assertEquals(latest, LocalMediaStore.find(song.id))
        }
    }

    @Test fun legacyUnknownFileVersionCanStillWriteAndAcquireRealMetadata() = runBlocking {
        withTagWriteFixture { file, song, player ->
            LocalMediaStore.replaceAll(listOf(song.copy(sizeBytes = 0L, modifiedAt = 0L)))
            val track = requireNotNull(player.value.current)
            LocalTagFiller.requestLyricWrite(context, track, PlayerLyric(track.uid, song.title, song.artist,
                listOf(LyricLine(0, "旧索引仍可写入")), "local"))
            player.value = PlayerUiState()
            LocalTagFiller.consider(context, null)
            awaitTagJobs()
            assertTrue(requireNotNull(LocalTagReader.embeddedLyrics(context, song.uri, song.mimeType)).plain.contains("旧索引仍可写入"))
            assertEquals(file.length(), LocalMediaStore.find(song.id)?.sizeBytes)
            assertEquals(file.lastModified(), LocalMediaStore.find(song.id)?.modifiedAt)
        }
    }

    @Test fun restoredProviderTimestampDoesNotBlockANewLyricWrite() = runBlocking {
        withTagWriteFixture { file, song, player ->
            val uri = android.net.Uri.parse("content://${InstrumentationRegistry.getInstrumentation().context.packageName}.backup-fixture/document")
            val resolver = context.contentResolver
            resolver.call(uri, "reset", null, null)
            try {
                val original = file.readBytes()
                requireNotNull(resolver.openOutputStream(uri, "rwt")).use { it.write(original) }
                val previousTime = 1_600_000_000_000L
                resolver.call(uri, "setModified", previousTime.toString(), null)
                val local = song.copy(uri = uri.toString(), sizeBytes = original.size.toLong(), modifiedAt = previousTime)
                LocalMediaStore.replaceAll(listOf(local))
                player.value = PlayerUiState(current = UiTrack.fromOnline(local.toOnlineSong()))
                val root = File(filesRoot, "local-tag-writes/restore-retry").apply { mkdirs() }
                ContentTagWrite(root).use { transaction ->
                    transaction.original.writeBytes(original)
                    transaction.rewritten.writeText("interrupted replacement")
                    resolver.call(uri, "failNextWrite", null, null)
                    org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                        transaction.overwrite(uri.toString()) { requireNotNull(resolver.openOutputStream(uri, "rwt")) }
                    }
                }
                LocalTagFiller.recoverInterruptedWrites(context)
                assertFalse(root.exists())
                val document = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)!!
                assertTrue(document.lastModified() != previousTime)
                assertEquals(local.sizeBytes, document.length())
                val track = requireNotNull(player.value.current)
                LocalTagFiller.requestLyricWrite(context, track, PlayerLyric(track.uid, song.title, song.artist,
                    listOf(LyricLine(0, "恢复后仍可写入")), "local"))
                player.value = PlayerUiState()
                LocalTagFiller.consider(context, null)
                awaitTagJobs()
                val written = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
                assertTrue(requireNotNull(LocalTagReader.embeddedLyricsFromBytes(written, "audio/mpeg")).plain.contains("恢复后仍可写入"))
            } finally { resolver.call(uri, "reset", null, null) }
        }
    }

    @Test fun declinedAutomaticWriteDoesNotRestartButManualRetryStillWrites() = runBlocking {
        withTagWriteFixture { file, song, player ->
            @Suppress("UNCHECKED_CAST")
            val declined = LocalTagFiller.javaClass.getDeclaredField("authorizationDeclined")
                .apply { isAccessible = true }.get(LocalTagFiller) as MutableSet<String>
            @Suppress("UNCHECKED_CAST")
            val inFlight = LocalTagFiller.javaClass.getDeclaredField("inFlight")
                .apply { isAccessible = true }.get(LocalTagFiller) as Set<String>
            MeloraSettings.localAutoFillInfo.value = true
            declined += song.id
            try {
                LocalMediaIoCoordinator.withExclusive(context, android.net.Uri.fromFile(file)) {
                    LocalTagFiller.consider(context, requireNotNull(player.value.current))
                    assertFalse("已拒绝授权的自动请求不能再次进入标签读取/网络匹配", song.id in inFlight)
                }
                val track = requireNotNull(player.value.current)
                LocalTagFiller.requestLyricWrite(context, track, PlayerLyric(track.uid, song.title, song.artist,
                    listOf(LyricLine(0, "明确手动重试")), "local"))
                assertFalse(song.id in declined)
                player.value = PlayerUiState()
                LocalTagFiller.consider(context, null)
                awaitTagJobs()
                assertTrue(requireNotNull(LocalTagReader.embeddedLyrics(context, song.uri, song.mimeType))
                    .plain.contains("明确手动重试"))
                assertEquals(file.length(), LocalMediaStore.find(song.id)?.sizeBytes)
            } finally { declined.remove(song.id) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun withTagWriteFixture(block: suspend (File, LocalSong, MutableStateFlow<PlayerUiState>) -> Unit) {
        val player = PlaybackController.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            .get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val before = player.value
        val auto = MeloraSettings.localAutoFillInfo.value
        val file = File(filesRoot, "tag-boundary.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/fixture-128.mp3")
            .use { input -> file.outputStream().use(input::copyTo) }
        val song = testSong("boundary").copy(uri = file.toURI().toString(), sizeBytes = file.length(),
            modifiedAt = file.lastModified(), folder = file.parent.orEmpty())
        LocalMediaStore.replaceAll(listOf(song))
        MeloraSettings.localAutoFillInfo.value = false
        player.value = PlayerUiState(current = UiTrack.fromOnline(song.toOnlineSong()))
        try { block(file, song, player) } finally {
            MeloraSettings.localAutoFillInfo.value = false
            val jobs = tagScope().coroutineContext[Job]!!.children.toList()
            jobs.forEach { it.cancel() }
            withTimeout(5_000) { jobs.forEach { it.join() } }
            player.value = before
            MeloraSettings.localAutoFillInfo.value = auto
        }
    }

    private fun tagScope() = LocalTagFiller.javaClass.getDeclaredField("scope").apply { isAccessible = true }
        .get(LocalTagFiller) as CoroutineScope

    private suspend fun awaitTagJobs() {
        withTimeout(5_000) { tagScope().coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    }

    @Test
    fun interruptedProviderOverwriteRetriesStartupRecoveryWithoutLosingLyrics() {
        val provider = android.net.Uri.parse(
            "content://${InstrumentationRegistry.getInstrumentation().context.packageName}.backup-fixture/document",
        )
        val resolver = context.contentResolver
        val root = File(filesRoot, "local-tag-writes/write-fixture").apply { mkdirs() }
        val rich = "<tt><body><div><p begin=\"1s\">rich</p></div></body></tt>"
        val original = id3Tag(
            id3Frame("USLT", usltBody("plain lyrics")),
            id3Frame("TXXX", txxxBody("LYRICS_TTML", rich)),
        )
        resolver.call(provider, "reset", null, null)
        try {
            requireNotNull(resolver.openOutputStream(provider, "rwt")).use { it.write(original) }
            ContentTagWrite(root).use { transaction ->
                transaction.original.writeBytes(original)
                transaction.rewritten.writeText("replacement")
                resolver.call(provider, "failNextWrite", null, null)
                org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                    transaction.overwrite(provider.toString()) {
                        requireNotNull(resolver.openOutputStream(provider, "rwt"))
                    }
                }
            }
            assertTrue(File(root, "pending").isFile)
            val fromOriginal = requireNotNull(LocalTagReader.embeddedLyrics(
                context, File(root, "original").toURI().toString(), "audio/mpeg",
            ))
            assertEquals("plain lyrics", fromOriginal.plain)
            assertEquals(rich, fromOriginal.ttml)
            resolver.call(provider, "failNextWrite", null, null)
            LocalTagFiller.recoverInterruptedWrites(context)
            assertTrue("提供器暂不可用时不能删除原件", File(root, "original").isFile)
            assertTrue(File(root, "pending").isFile)
            LocalTagFiller.recoverInterruptedWrites(context)
            LocalTagFiller.recoverInterruptedWrites(context)
            val restored = requireNotNull(resolver.openInputStream(provider)).use { it.readBytes() }
            org.junit.Assert.assertArrayEquals(original, restored)
            assertFalse(root.exists())
            val lyrics = requireNotNull(LocalTagReader.embeddedLyricsFromBytes(restored, "audio/mpeg"))
            assertEquals("plain lyrics", lyrics.plain)
            assertEquals(rich, lyrics.ttml)
        } finally { resolver.call(provider, "reset", null, null) }
    }

    @Test
    fun id3ScansUsltAndTheNamedTxxxFrameWithoutEarlyReturn() {
        val rich = "<tt><body><div><p begin=\"1s\">rich</p></div></body></tt>"
        val bytes = id3Tag(
            id3Frame("PRIV", byteArrayOf(1, 2, 3)),
            id3Frame("USLT", usltBody("plain lyrics")),
            id3Frame("TXXX", txxxBody("LYRICS_TTML", rich)),
        )

        val lyrics = requireNotNull(LocalTagReader.embeddedLyricsFromBytes(bytes, "audio/mpeg"))

        assertEquals("plain lyrics", lyrics.plain)
        assertEquals(rich, lyrics.ttml)
    }

    @Test
    fun flacScansAllCommentFieldsAndKeepsOrdinaryLyricsAfterTtml() {
        val rich = "<tt><body><div><p begin=\"1s\">rich</p></div></body></tt>"
        val bytes = flacCommentBlock(
            "LYRICS_TTML=$rich",
            "X_UNKNOWN=untouched",
            "UNSYNCED LYRICS=plain lyrics",
        )

        val lyrics = requireNotNull(LocalTagReader.embeddedLyricsFromBytes(bytes, "audio/flac"))

        assertEquals("plain lyrics", lyrics.plain)
        assertEquals(rich, lyrics.ttml)
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
    fun replacingCoverVersionRemovesStaleMemoryUri() {
        val oldSong = testSong("versioned")
        val newSong = oldSong.copy(modifiedAt = 2)
        val unrelated = File(cacheRoot, "$LOCAL_COVER_CACHE_DIR/unrelated.img")

        assertNotNull(LocalTagReader.cacheCover(context, oldSong, byteArrayOf(1)))
        assertNotNull(coverCache()["versioned-1"])
        unrelated.writeBytes(byteArrayOf(9))

        assertNotNull(LocalTagReader.cacheCover(context, newSong, byteArrayOf(2)))

        assertNull(coverCache()["versioned-1"])
        assertNotNull(coverCache()["versioned-2"])
        assertFalse(File(cacheRoot, "$LOCAL_COVER_CACHE_DIR/versioned-1.img").exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun diskCacheEvictsTheOldestCoverByModificationTime() {
        val directory = File(cacheRoot, LOCAL_COVER_CACHE_DIR).apply { mkdirs() }
        val baseTime = System.currentTimeMillis() - 3_600_000L
        val oldest = File(directory, "oldest.img").apply {
            writeBytes(byteArrayOf(1))
            assertTrue(setLastModified(baseTime))
        }
        repeat(511) { index ->
            File(directory, "newer-$index.img").apply {
                writeBytes(byteArrayOf(1))
                assertTrue(setLastModified(baseTime + (index + 1L) * 2_000L))
            }
        }

        assertNotNull(LocalTagReader.cacheCover(context, testSong("mtime"), byteArrayOf(2)))

        assertFalse(oldest.exists())
        assertTrue(File(directory, "newer-510.img").exists())
    }

    @Test
    fun diskCacheIsBoundedByByteSize() {
        val directory = File(cacheRoot, LOCAL_COVER_CACHE_DIR).apply { mkdirs() }
        val oversized = File(directory, "oversized.img")
        RandomAccessFile(oversized, "rw").use { it.setLength(64L * 1024L * 1024L) }
        assertTrue(oversized.setLastModified(System.currentTimeMillis() - 3_600_000L))

        assertNotNull(LocalTagReader.cacheCover(context, testSong("bytes"), byteArrayOf(1)))

        val files = directory.listFiles { file -> file.isFile && file.extension == "img" }.orEmpty()
        assertTrue(files.sumOf(File::length) <= 64L * 1024L * 1024L)
        assertFalse(oversized.exists())
    }

    @Test
    fun diskCacheSkipsDirectoriesAndDanglingLinksWhenTrimming() {
        val directory = File(cacheRoot, LOCAL_COVER_CACHE_DIR).apply { mkdirs() }
        val blockedDirectory = File(directory, "blocked.img").apply { mkdirs() }
        val danglingLink = File(directory, "dangling.img").toPath()
        Files.createSymbolicLink(danglingLink, File(directory, "missing.img").toPath())
        val oversized = File(directory, "oversized.img")
        RandomAccessFile(oversized, "rw").use { it.setLength(64L * 1024L * 1024L) }
        assertTrue(oversized.setLastModified(System.currentTimeMillis() - 3_600_000L))

        assertNotNull(LocalTagReader.cacheCover(context, testSong("bad-entries"), byteArrayOf(1)))

        assertFalse(oversized.exists())
        assertTrue(blockedDirectory.isDirectory)
        assertTrue(Files.isSymbolicLink(danglingLink))
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

    private fun id3Tag(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArrayOutputStream()) { output, frame -> output.apply { write(frame) } }.toByteArray()
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
            synchsafe(body.size) + body
    }

    private fun id3Frame(id: String, body: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + synchsafe(body.size) + byteArrayOf(0, 0) + body

    private fun usltBody(text: String): ByteArray =
        byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0) + text.toByteArray(Charsets.UTF_8)

    private fun txxxBody(description: String, text: String): ByteArray =
        byteArrayOf(3) + description.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun flacCommentBlock(vararg comments: String): ByteArray {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
            write(vendor)
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comments.size).array())
            comments.forEach { comment ->
                val value = comment.toByteArray(Charsets.UTF_8)
                write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.size).array())
                write(value)
            }
        }.toByteArray()
        require(payload.size <= 0xFFFFFF)
        return "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(
            0x84.toByte(),
            ((payload.size shr 16) and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        ) + payload
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
