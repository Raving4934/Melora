package com.leyu.melora.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DownloaderStreamingTest {
    @Test
    fun cancelledSuspendingOwnerReleasesTargetLock() = kotlinx.coroutines.runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val owner = launch {
            DownloadTargetLocks.withLock("cancelled-owner") {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        entered.await()
        owner.cancel()
        owner.join()
        assertEquals("next", DownloadTargetLocks.withLock("cancelled-owner") { "next" })
        assertEquals(0, DownloadTargetLocks.size())
    }

    @Test
    fun failedWriteKeepsOldFileAndCleansTemporaryFiles() {
        val directory = Files.createTempDirectory("melora-download-publish").toFile()
        val target = directory.resolve("same.mp3").apply { writeText("old") }
        try {
            val failed = runCatching {
                publishFileAtomically(target, object : java.io.InputStream() {
                    private var emitted = false

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (emitted) throw IOException("模拟下载流故障")
                        emitted = true
                        buffer[offset] = 'n'.code.toByte()
                        return 1
                    }

                    override fun read(): Int = if (emitted) {
                        throw IOException("模拟下载流故障")
                    } else {
                        emitted = true
                        'n'.code
                    }
                })
            }
            assertTrue(failed.isFailure)
            assertEquals("old", target.readText())
            assertEquals(
                emptyList<String>(),
                directory.listFiles()
                    ?.filter { it.name != target.name }
                    ?.map { it.name }
                    .orEmpty(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun automaticTransferRetryRequiresNetworkAndStopsAfterThreeDistinctResources() {
        assertTrue(shouldRetryDownloadTransfer(true, true, 1, 1))
        assertTrue(shouldRetryDownloadTransfer(true, true, 2, 1))
        assertFalse(shouldRetryDownloadTransfer(true, true, 3, 1))
        assertFalse(shouldRetryDownloadTransfer(false, true, 1, 1))
        assertFalse(shouldRetryDownloadTransfer(true, false, 1, 1))
        assertFalse(shouldRetryDownloadTransfer(true, true, 1, 0))
    }

    @Test
    fun onlyExplicitlyTaggedHttpFailuresAreEligibleForResourceRetry() {
        val transport = DownloadHttpTransferFailure("lx:fixture:resource-a", IOException("connection reset"))
        val wrapped = IOException("cache source open failed", transport)

        assertSame(transport, findDownloadHttpTransferFailure(wrapped))
        assertNull(findDownloadHttpTransferFailure(IOException("destination permission denied")))
    }

    @Test
    fun sameTargetPublishesAreSerializedAndLockEntriesAreReleased() {
        val directory = Files.createTempDirectory("melora-download-concurrent").toFile()
        val target = directory.resolve("same.mp3")
        val start = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("first", "second").map { value ->
                executor.submit {
                    start.await()
                    kotlinx.coroutines.runBlocking { DownloadTargetLocks.withLock(target.absolutePath) {
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { maxOf(it, now) }
                        try {
                            Thread.sleep(20)
                            publishFileAtomically(target, ByteArrayInputStream(value.toByteArray()))
                        } finally {
                            active.decrementAndGet()
                        }
                    } }
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, maxActive.get())
            assertTrue(setOf("first", "second").contains(target.readText()))
            assertEquals(0, DownloadTargetLocks.size())
            assertEquals(1, directory.listFiles()?.size ?: 0)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun streamsLargeInputWithoutRequiringAWholeFileByteArray() {
        val size = 12 * 1024 * 1024L
        val input = object : java.io.InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 1 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                return minOf(length.toLong(), remaining).toInt().also { remaining -= it }
            }
        }
        assertEquals(size, copyNonEmpty(input, OutputStream.nullOutputStream()))
    }

    @Test
    fun fileCopiesUseSharedLargeTransferBuffer() {
        var largestRequestedRead = 0
        var remaining = AUDIO_TRANSFER_BUFFER_BYTES * 3
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("不应退化为单字节读取")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequestedRead = maxOf(largestRequestedRead, length)
                if (remaining == 0) return -1
                return minOf(length, remaining).also { remaining -= it }
            }
        }

        assertEquals(
            (AUDIO_TRANSFER_BUFFER_BYTES * 3).toLong(),
            copyNonEmpty(input, OutputStream.nullOutputStream()),
        )
        assertEquals(AUDIO_TRANSFER_BUFFER_BYTES, largestRequestedRead)
    }

    @Test
    fun reportsKnownLengthProgressWhileStreaming() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()

        val copied = copyWithProgress(
            input = ByteArrayInputStream(ByteArray(256 * 1024)),
            output = output,
            totalBytes = 256L * 1024L,
            onProgress = progress::add,
        )

        assertEquals(256L * 1024L, copied)
        assertEquals(100, progress.last())
        assertEquals(copied.toInt(), output.size())
    }

    @Test
    fun streamingStopsWhenDownloadTaskIsCancelled() {
        val output = ByteArrayOutputStream()
        var checks = 0

        val result = runCatching {
            copyWithProgress(
                input = ByteArrayInputStream(ByteArray(AUDIO_TRANSFER_BUFFER_BYTES * 4)),
                output = output,
                totalBytes = (AUDIO_TRANSFER_BUFFER_BYTES * 4).toLong(),
                checkActive = {
                    checks++
                    if (checks >= 4) throw CancellationException("cancelled")
                },
                onProgress = {},
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(output.size() < AUDIO_TRANSFER_BUFFER_BYTES * 4)
    }

    @Test
    fun unknownLengthStillStreamsWithoutFakeProgress() {
        val progress = mutableListOf<Int>()
        assertEquals(
            32L,
            copyWithProgress(
                input = ByteArrayInputStream(ByteArray(32)),
                output = OutputStream.nullOutputStream(),
                totalBytes = null,
                onProgress = progress::add,
            ),
        )
        assertEquals(emptyList<Int>(), progress)
    }

    @Test
    fun downloadQualityFallbackIsReportedInsteadOfSilent() {
        assertEquals("", downloadQualityNote("flac", "flac"))
        assertEquals("（flac24bit 不可用，已使用 flac）", downloadQualityNote("flac24bit", "flac"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyResponseBeforePublishingAFile() {
        copyNonEmpty(ByteArrayInputStream(ByteArray(0)), OutputStream.nullOutputStream())
    }

    @Test
    fun queuedDownloadAcquiresAfterActiveDownloadReleases() {
        runBlocking {
            val gate = DynamicDownloadGate(limit = { 1 }, retryDelayMs = 1)
            gate.acquire()
            var acquired = false
            val queued = launch {
                gate.acquire()
                acquired = true
                gate.release()
            }

            kotlinx.coroutines.delay(10)
            assertFalse(acquired)
            gate.release()
            withTimeout(1_000) { queued.join() }
        }
    }

    @Test
    fun cancellingQueuedDownloadDoesNotConsumePermit() {
        runBlocking {
            val gate = DynamicDownloadGate(limit = { 1 }, retryDelayMs = 1)
            gate.acquire()
            val cancelled = launch { gate.acquire() }
            kotlinx.coroutines.delay(10)
            cancelled.cancelAndJoin()

            gate.release()
            withTimeout(1_000) {
                gate.acquire()
                gate.release()
            }
        }
    }
}
