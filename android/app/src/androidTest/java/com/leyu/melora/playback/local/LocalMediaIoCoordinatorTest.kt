package com.leyu.melora.playback.local

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class LocalMediaIoCoordinatorTest {
    private val context get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    @Test
    fun repeatedReadsMayOverlapButNeverOverlapWithWriter() {
        val uri = Uri.parse("file:///tmp/melora-io-${System.nanoTime()}.mp3")
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val readers = AtomicInteger(0)
        val writers = AtomicInteger(0)
        val violation = AtomicBoolean(false)
        try {
            val futures = (0 until 80).map { index ->
                executor.submit {
                    start.await()
                    if (index % 5 == 0) {
                        runBlocking {
                            LocalMediaIoCoordinator.withExclusive(context, uri) {
                                if (readers.get() != 0 || writers.incrementAndGet() != 1) violation.set(true)
                                Thread.sleep(2)
                                writers.decrementAndGet()
                            }
                        }
                    } else {
                        LocalMediaIoCoordinator.withRead(context, uri) {
                            if (writers.get() != 0) violation.set(true)
                            readers.incrementAndGet()
                            Thread.sleep(2)
                            readers.decrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertFalse(violation.get())
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledWriterLeavesReadersAndFutureWritersUsable() = runBlocking {
        val uri = Uri.parse("file:///tmp/melora-cancel-${System.nanoTime()}.mp3")
        val reader = LocalMediaIoCoordinator.acquireRead(context, uri)
        val writer = async(Dispatchers.Default) {
            LocalMediaIoCoordinator.withExclusive(context, uri) { error("取消前不应拿到写租约") }
        }
        delay(60)
        writer.cancelAndJoin()
        reader.close()
        LocalMediaIoCoordinator.withExclusive(context, uri) { Unit }
        assertEquals(0, LocalMediaIoCoordinator.entryCount())
    }

    @Test
    fun waitingWriterDoesNotBlockAnotherReader() = runBlocking {
        val uri = Uri.parse("file:///tmp/melora-read-priority-${System.nanoTime()}.mp3")
        val first = LocalMediaIoCoordinator.acquireRead(context, uri)
        val writerEntered = CountDownLatch(1)
        val writer = async(Dispatchers.Default) {
            LocalMediaIoCoordinator.withExclusive(context, uri) {
                writerEntered.countDown()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            delay(60)
            val second = executor.submit<LocalMediaIoCoordinator.Lease> {
                LocalMediaIoCoordinator.acquireRead(context, uri)
            }.get(1, TimeUnit.SECONDS)
            second.close()
            first.close()
            assertTrue(writerEntered.await(1, TimeUnit.SECONDS))
            writer.await()
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            first.close()
            writer.cancelAndJoin()
            executor.shutdownNow()
        }
    }

    @Test
    fun writerExceptionReleasesExclusiveState() = runBlocking {
        val uri = Uri.parse("file:///tmp/melora-error-${System.nanoTime()}.mp3")
        val failed = runCatching {
            LocalMediaIoCoordinator.withExclusive(context, uri) { error("模拟标签写入异常") }
        }
        assertTrue(failed.isFailure)
        LocalMediaIoCoordinator.withRead(context, uri) { Unit }
        assertEquals(0, LocalMediaIoCoordinator.entryCount())
    }

    @Test
    fun dataSourceLeaseSpansOpenToCloseAndCloseMayRunOnAnotherThread() {
        val uri = Uri.parse("file:///tmp/melora-open-close-${System.nanoTime()}.mp3")
        val upstream = RecordingDataSource(uri)
        val source = LocalMediaIoCoordinator.wrap(upstream, context)
        val executor = Executors.newFixedThreadPool(2)
        try {
            source.open(DataSpec(uri))
            val writer = executor.submit {
                runBlocking {
                    LocalMediaIoCoordinator.withExclusive(context, uri) { Unit }
                }
            }
            Thread.sleep(60)
            assertFalse(writer.isDone)
            executor.submit { source.close() }.get(5, TimeUnit.SECONDS)
            writer.get(5, TimeUnit.SECONDS)
            assertTrue(upstream.closed)
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            runCatching { source.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun closeWaitsForInFlightOpenBeforeReleasingReadLease() {
        val uri = Uri.parse("file:///tmp/melora-open-close-race-${System.nanoTime()}.mp3")
        val upstream = BlockingDataSource(uri)
        val source = LocalMediaIoCoordinator.wrap(upstream, context)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val opening = executor.submit { source.open(DataSpec(uri)) }
            assertTrue(upstream.openEntered.await(5, TimeUnit.SECONDS))
            val closing = executor.submit { source.close() }
            Thread.sleep(60)
            assertFalse(closing.isDone)
            val writer = executor.submit {
                runBlocking { LocalMediaIoCoordinator.withExclusive(context, uri) { Unit } }
            }
            Thread.sleep(60)
            assertFalse(writer.isDone)
            upstream.allowOpen.countDown()
            assertTrue(runCatching { opening.get(5, TimeUnit.SECONDS) }.isFailure)
            closing.get(5, TimeUnit.SECONDS)
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(1, upstream.closeCalls.get())
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            upstream.allowOpen.countDown()
            runCatching { source.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun readOpenWaitsForWriterButProceedsAfterWriteCompletes() {
        val uri = Uri.parse("file:///tmp/melora-open-writer-${System.nanoTime()}.mp3")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = LocalMediaIoCoordinator.wrap(RecordingDataSource(uri), context)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                runBlocking {
                    LocalMediaIoCoordinator.withExclusive(context, uri) {
                        entered.countDown()
                        release.await()
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val opened = executor.submit { source.open(DataSpec(uri)) }
            Thread.sleep(60)
            assertFalse(opened.isDone)
            release.countDown()
            opened.get(5, TimeUnit.SECONDS)
            source.close()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            release.countDown()
            runCatching { source.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun actualFileReadKeepsWriterOutUntilCloseAndOpenFailureDoesNotLeak() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File.createTempFile("melora-io-", ".bin", context.cacheDir)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val uri = Uri.fromFile(file)
        val source = LocalMediaIoCoordinator.wrap(androidx.media3.datasource.FileDataSource(), context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            source.open(DataSpec(uri))
            val writer = executor.submit {
                runBlocking { LocalMediaIoCoordinator.withExclusive(context, uri) { file.writeBytes(byteArrayOf(4, 5, 6)) } }
            }
            Thread.sleep(60)
            assertFalse(writer.isDone)
            val bytes = ByteArray(3)
            assertEquals(3, source.read(bytes, 0, bytes.size))
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
            source.close()
            writer.get(5, TimeUnit.SECONDS)
            org.junit.Assert.assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
            assertTrue(file.delete())
            assertTrue(runCatching { source.open(DataSpec(uri)) }.isFailure)
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            runCatching { source.close() }
            executor.shutdownNow()
            file.delete()
        }
    }

    @Test
    fun treeAndDocumentUrisForTheSameSafDocumentShareTheLock() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "com.android.externalstorage.documents"
        val documentId = "primary:Music/melora-lock-test.flac"
        val plain = android.provider.DocumentsContract.buildDocumentUri(authority, documentId)
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "primary:Music")
        val child = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val reader = LocalMediaIoCoordinator.acquireRead(context, plain)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                runBlocking { LocalMediaIoCoordinator.withExclusive(context, child) { Unit } }
            }
            Thread.sleep(60)
            assertFalse(writer.isDone)
            reader.close()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(0, LocalMediaIoCoordinator.entryCount())
        } finally {
            reader.close()
            executor.shutdownNow()
        }
    }

    private class RecordingDataSource(
        private val uri: Uri,
    ) : DataSource {
        var closed = false

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = uri
        override fun close() {
            closed = true
        }
    }

    private class BlockingDataSource(
        private val uri: Uri,
    ) : DataSource {
        val openEntered = CountDownLatch(1)
        val allowOpen = CountDownLatch(1)
        val closeCalls = AtomicInteger(0)

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            openEntered.countDown()
            allowOpen.await()
            return 0L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = uri
        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
