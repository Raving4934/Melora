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
        assertEquals(sourceLines, embedded?.parse())
    }

    @Test fun contentWriteFailureAndCancellationRestoreOriginalBytes() {
        for (failure in listOf(java.io.IOException("write failed"), kotlinx.coroutines.CancellationException("cancelled"))) {
            val directory = java.nio.file.Files.createTempDirectory("lyric-rollback").toFile()
            try {
                val original = java.io.File(directory, "original").apply { writeText("original audio bytes") }
                val rewritten = java.io.File(directory, "edited").apply { writeText("new tags and original audio bytes") }
                var bytes = byteArrayOf()
                var opens = 0
                val thrown = org.junit.Assert.assertThrows(failure.javaClass) {
                    overwriteContentWithRollback(original, rewritten) {
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
            val original = java.io.File(directory, "original").apply { writeText("original audio") }
            val rewritten = java.io.File(directory, "edited").apply { writeText("new") }
            var opens = 0
            org.junit.Assert.assertThrows(SecurityException::class.java) {
                overwriteContentWithRollback(original, rewritten) { opens++; throw SecurityException("denied") }
            }
            assertEquals(1, opens)
            opens = 0
            val thrown = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                overwriteContentWithRollback(original, rewritten) {
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
