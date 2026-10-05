package com.leyu.melora.playback.local

import android.content.ContextWrapper
import com.leyu.melora.playback.EmbeddedLyrics
import com.leyu.melora.playback.LyricLine
import com.leyu.melora.playback.LyricWord
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.UiTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricWriteTest {
    @After
    fun tearDown() {
        LocalMediaStore.clear()
        LocalMediaStore.setScanning(false)
    }

    @Test
    fun requestRejectsLyricForDifferentTrackUid() {
        LocalMediaStore.replaceAll(listOf(localSong()))

        val message = LocalTagFiller.requestLyricWrite(
            context = ContextWrapper(null),
            track = localTrack(),
            lyric = lyric(uid = "different-uid"),
        )

        assertEquals("歌词与当前歌曲不匹配，未写入", message)
    }

    @Test
    fun requestRejectsLyricWithNoContent() {
        LocalMediaStore.replaceAll(listOf(localSong()))

        val message = LocalTagFiller.requestLyricWrite(
            context = ContextWrapper(null),
            track = localTrack(),
            lyric = lyric(lines = emptyList()),
        )

        assertEquals("没有可写入的歌词", message)
    }

    @Test
    fun requestRejectsNonMp3FlacLocalFile() {
        LocalMediaStore.replaceAll(listOf(localSong(mimeType = "audio/aac", uri = "file:///Music/test.m4a")))

        val message = LocalTagFiller.requestLyricWrite(
            context = ContextWrapper(null),
            track = localTrack(),
            lyric = lyric(uid = "local_ms_1"),
        )

        assertEquals("仅支持写入本地 MP3/FLAC 文件", message)
    }

    @Test
    fun authorizationRetryDefersToANewerQueuedManualLyric() {
        assertTrue(
            shouldDeferTagWriteForQueuedManual(
                requestIsManual = false,
                queuedIsManual = true,
                sameRequest = false,
            ),
        )
        assertTrue(
            shouldDeferTagWriteForQueuedManual(
                requestIsManual = true,
                queuedIsManual = true,
                sameRequest = false,
            ),
        )
        assertFalse(
            shouldDeferTagWriteForQueuedManual(
                requestIsManual = true,
                queuedIsManual = true,
                sameRequest = true,
            ),
        )
    }

    @Test
    fun explicitLyricEncodingPreservesWordTimingAndTranslation() {
        val sourceLines = listOf(
            LyricLine(
                startMs = 1_000,
                text = "Hello",
                translation = "你好",
                endMs = 2_000,
                words = listOf(LyricWord("Hel", 1_000, 1_400), LyricWord("lo", 1_400, 2_000)),
            ),
        )

        val embedded = EmbeddedLyrics.fromLines(sourceLines)

        assertNotNull(embedded)
        assertTrue(requireNotNull(embedded).ttml.isNotBlank())
        assertEquals(sourceLines, embedded.parse())
    }

    @Test fun killedWriterRestoresOriginalOnNextProcessAndRecoveryIsIdempotent() {
        val root = java.nio.file.Files.createTempDirectory("tag-process-death").toFile()
        try {
            val target = java.io.File(root, "audio").apply { writeText("original audio bytes") }
            val journal = java.io.File(root, "journal").apply { mkdir() }
            val classpath = listOf(ContentTagCrashProcess::class.java, ContentTagWrite::class.java, Unit::class.java)
                .map { java.io.File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
                .distinct().joinToString(java.io.File.pathSeparator)
            fun child(mode: String): String {
                val process = ProcessBuilder(
                    "${System.getProperty("java.home")}/bin/java", "-cp", classpath,
                    ContentTagCrashProcess::class.java.name, mode, journal.path, target.path,
                ).redirectErrorStream(true).start()
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw AssertionError("bounded child must exit")
                }
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue())
                return output
            }
            child("interrupt")
            assertEquals("new", target.readText())
            assertTrue(java.io.File(journal, "pending").isFile)
            child("recover")
            assertEquals("original audio bytes", target.readText())
            assertFalse(journal.exists())
            child("recover")
            assertEquals("original audio bytes", target.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun completedWriteIsNotRolledBackWhenProcessEndsBeforeCleanup() {
        val directory = java.nio.file.Files.createTempDirectory("tag-committed").toFile()
        try {
            val transaction = ContentTagWrite(directory)
            transaction.original.writeText("old audio")
            transaction.rewritten.writeText("new tags")
            val target = java.io.ByteArrayOutputStream()
            transaction.overwrite("content://fixture/audio") { target }
            assertFalse(transaction.pending)
            // 故意不close，模拟成功提交后、清理事务目录前进程退出。
            ContentTagWrite(directory).use { it.recover { error("committed write must not be restored") } }
            assertEquals("new tags", target.toString("UTF-8"))
            assertFalse(directory.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unavailableRecoveryKeepsJournalAndOriginalUntilPermissionReturns() {
        val root = java.nio.file.Files.createTempDirectory("tag-recovery").toFile()
        try {
            val transaction = ContentTagWrite(root)
            transaction.original.writeText("original audio")
            transaction.rewritten.writeText("updated")
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                transaction.overwrite("content://fixture/audio") { throw java.io.IOException("provider died") }
            }
            transaction.close()
            assertTrue(transaction.pending)
            org.junit.Assert.assertThrows(SecurityException::class.java) {
                ContentTagWrite(root).use { it.recover { throw SecurityException("grant expired") } }
            }
            assertTrue(transaction.original.exists())
            assertTrue(transaction.pending)
            val output = java.io.ByteArrayOutputStream()
            ContentTagWrite(root).use { it.recover { uri ->
                assertEquals("content://fixture/audio", uri)
                output
            } }
            assertEquals("original audio", output.toString("UTF-8"))
            assertFalse(root.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingOriginalNeverTruncatesTargetDuringRecovery() {
        val directory = java.nio.file.Files.createTempDirectory("tag-missing-original").toFile()
        try {
            java.io.File(directory, "pending").writeText("content://fixture/audio")
            var opened = false
            org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                ContentTagWrite(directory).use { it.recover { opened = true; java.io.ByteArrayOutputStream() } }
            }
            assertFalse(opened)
            assertTrue(java.io.File(directory, "pending").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun contentWriteFailureAndCancellationRestoreOriginalBytes() {
        for (failure in listOf(java.io.IOException("write failed"), kotlinx.coroutines.CancellationException("cancelled"))) {
            val directory = java.nio.file.Files.createTempDirectory("lyric-rollback").toFile()
            try {
                val transaction = ContentTagWrite(directory)
            val original = transaction.original.apply { writeText("original audio bytes") }
                val rewritten = transaction.rewritten.apply { writeText("new tags and original audio bytes") }
                var bytes = byteArrayOf()
                var opens = 0
                val thrown = org.junit.Assert.assertThrows(failure.javaClass) {
                    transaction.overwrite("content://fixture/audio") {
                        val attempt = ++opens
                        bytes = byteArrayOf()
                        object : java.io.OutputStream() {
                            override fun write(value: Int) {
                                bytes += value.toByte()
                                if (attempt == 1 && bytes.size == 5) throw failure
                            }
                        }
                    }
                }
                org.junit.Assert.assertSame(failure, thrown)
                assertEquals(2, opens)
                org.junit.Assert.assertArrayEquals(original.readBytes(), bytes)
                assertTrue(original.exists())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun refusedInitialAccessDoesNotTryToWriteAgainAndFailedRestoreKeepsBackupReference() {
        val directory = java.nio.file.Files.createTempDirectory("lyric-restore-failure").toFile()
        try {
            val transaction = ContentTagWrite(directory)
            val original = transaction.original.apply { writeText("original audio") }
            val rewritten = transaction.rewritten.apply { writeText("new") }
            var opens = 0
            org.junit.Assert.assertThrows(SecurityException::class.java) {
                transaction.overwrite("content://fixture/audio") { opens++; throw SecurityException("denied") }
            }
            assertEquals(1, opens)
            opens = 0
            val thrown = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                transaction.overwrite("content://fixture/audio") {
                    opens++
                    object : java.io.OutputStream() {
                        override fun write(value: Int) { throw java.io.IOException("provider unavailable") }
                    }
                }
            }
            assertEquals(2, opens)
            val retained = thrown.suppressed.filterIsInstance<ContentUriRestoreFailure>().single()
            assertEquals(original, retained.backup)
            assertEquals("original audio", retained.backup.readText())
        } finally { directory.deleteRecursively() }
    }

    private fun lyric(
        uid: String = "local_ms_1",
        lines: List<LyricLine> = listOf(LyricLine(startMs = 0, text = "歌词")),
    ) = PlayerLyric(uid, "歌曲", "歌手", lines, "test")

    private fun localTrack() = UiTrack(
        uid = "local_ms_1",
        title = "歌曲",
        artist = "歌手",
        album = "专辑",
        source = LocalSong.SOURCE,
    )

    private fun localSong(
        mimeType: String = "audio/mpeg",
        uri: String = "file:///Music/test.mp3",
    ) = LocalSong(
        id = "ms_1",
        uri = uri,
        title = "歌曲",
        artist = "歌手",
        album = "专辑",
        durationMs = 180_000,
        sizeBytes = 1_000_000,
        mimeType = mimeType,
        sampleRate = 44_100,
        bitrate = 320_000,
        modifiedAt = 1,
        addedAt = 1,
        folder = "Music",
    )
}

/** 真正终止独立 JVM，跳过 catch/finally；只操作测试创建的临时文件。 */
internal object ContentTagCrashProcess {
    @JvmStatic fun main(args: Array<String>) {
        val directory = java.io.File(args[1])
        val target = java.io.File(args[2])
        if (args[0] == "recover") {
            ContentTagWrite(directory).use { it.recover { target.outputStream() } }
            return
        }
        ContentTagWrite(directory).use { transaction ->
            target.copyTo(transaction.original)
            transaction.rewritten.writeText("new audio tags")
            transaction.overwrite(target.toURI().toString()) {
                val output = target.outputStream()
                object : java.io.OutputStream() {
                    private var written = 0
                    override fun write(value: Int) {
                        output.write(value)
                        if (++written == 3) {
                            output.fd.sync()
                            Runtime.getRuntime().halt(0)
                        }
                    }
                    override fun close() = output.close()
                }
            }
        }
    }
}
