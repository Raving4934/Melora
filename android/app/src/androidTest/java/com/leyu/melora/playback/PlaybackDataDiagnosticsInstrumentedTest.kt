package com.leyu.melora.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackDataDiagnosticsInstrumentedTest {
    @Test fun earlyEofLogsActualBytesBeforeSourceClosesAndKeepsOriginalItemIdentity() {
        val messages = mutableListOf<String>()
        val uid = "diagnostics-header-only"
        val source = FixtureSource(onOpen = {
            // 网络open返回前，同uid的另一次解析已完成。
            TrackRegistry.notifyResolved(uid, "192k", "lx:other:good", "320k", "kg")
        })
        lateinit var diagnostics: PlaybackDataDiagnostics
        diagnostics = PlaybackDataDiagnostics(ResolvingDataSource(source) { spec -> spec.also(diagnostics::resolved) }, messages::add)
        val spec = DataSpec.Builder().setUri("melora://song/$uid").setPosition(30).build()
        TrackRegistry.notifyResolved(uid, "320k", "lx:fixture:bad", "320k", "kw", fromCompleteCache = true)
        try {
            assertEquals(100L, diagnostics.open(spec))
            assertSame(spec, source.openedSpec)
            assertEquals(7, diagnostics.read(ByteArray(20), 0, 20))
            // 模拟预读另一项更新来源；本次日志必须保持原open的资源与规格。
            TrackRegistry.notifyResolved(uid, "192k", "lx:other:good", "320k", "kg")
            assertEquals(C.RESULT_END_OF_INPUT, diagnostics.read(ByteArray(20), 0, 20))
            val eof = messages.single { it.contains("event=eof ") }
            assertTrue(eof.contains("uid=$uid "))
            assertTrue(eof.contains("quality=320k "))
            assertTrue(eof.contains("platform=kw "))
            assertTrue(eof.contains("completeCache=true "))
            assertTrue(eof.contains("position=30 "))
            assertTrue(eof.contains("openedLength=100 "))
            assertTrue(eof.contains("readBytes=7 "))
            assertTrue(eof.contains("eof=true "))
            assertFalse(source.closed)
        } finally {
            diagnostics.close()
            TrackRegistry.clearResolved(uid)
        }
        assertTrue(source.closed)
    }

    @Test fun transfersDistinguishCacheAndNetworkWithoutPrintingSignedUrls() {
        val messages = mutableListOf<String>()
        val source = FixtureSource(mixed = true)
        val diagnostics = PlaybackDataDiagnostics(source, messages::add)
        diagnostics.open(DataSpec.Builder().setUri("https://cdn.example/audio?token=secret-token").build())
        diagnostics.read(ByteArray(20), 0, 20)
        diagnostics.close()
        val close = messages.single { it.contains("event=close ") }
        assertTrue(close.contains("source=cache+network "))
        assertTrue(close.contains("cacheBytes=3 "))
        assertTrue(close.contains("networkBytes=4 "))
        assertTrue(close.contains("readBytes=7 "))
        assertTrue(messages.none { "https://" in it || "secret-token" in it || "cache-file.exo" in it })
    }

    @Test fun loadErrorsKeepOriginalExceptionAndDoNotExposeItsMessage() {
        for (failOpen in listOf(false, true)) {
            val messages = mutableListOf<String>()
            val failure = IOException("https://cdn.example/audio?token=secret-token")
            val source = FixtureSource(failure = failure, failOpen = failOpen)
            val diagnostics = PlaybackDataDiagnostics(source, messages::add)
            try {
                diagnostics.open(DataSpec.Builder().setUri("melora://song/fixture").build())
                diagnostics.read(ByteArray(20), 0, 20)
                fail("必须原样抛出读取错误")
            } catch (actual: IOException) {
                assertSame(failure, actual)
            } finally {
                diagnostics.close()
            }
            assertTrue(messages.any { it.contains(if (failOpen) "event=open_error " else "event=read_error ") })
            assertTrue(messages.none { "https://" in it || "secret-token" in it })
            assertTrue(source.closed)
        }
    }

    @Test fun reopeningForSeekResetsCountersAndIdentifiesNewReadAttempt() {
        val messages = mutableListOf<String>()
        val diagnostics = PlaybackDataDiagnostics(FixtureSource(), messages::add)
        val spec = DataSpec.Builder().setUri("melora://song/fixture").build()
        diagnostics.open(spec)
        diagnostics.read(ByteArray(20), 0, 20)
        diagnostics.close()
        diagnostics.open(spec.buildUpon().setPosition(110).build())
        diagnostics.close()
        val opens = messages.filter { it.contains("event=open ") }
        assertEquals(2, opens.size)
        assertTrue(opens[1].contains("position=110 "))
        assertTrue(opens[1].contains("readBytes=0 "))
        assertNotEquals(opens[0].substringAfter("attempt=").substringBefore(' '), opens[1].substringAfter("attempt=").substringBefore(' '))
    }

    private class FixtureSource(
        private val mixed: Boolean = false,
        private val failure: IOException? = null,
        private val failOpen: Boolean = false,
        private val onOpen: () -> Unit = {},
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private val cache = DataSpec.Builder().setUri("file:///private/cache-file.exo").setKey("melora://audio/fixture").build()
        private val network = DataSpec.Builder().setUri("https://cdn.example/audio?token=secret-token").build()
        var openedSpec: DataSpec? = null
        var closed = false
        private var read = false
        override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
        override fun getUri(): Uri = network.uri
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Length" to listOf("100"))
        override fun open(dataSpec: DataSpec): Long {
            openedSpec = dataSpec
            read = false
            closed = false
            if (failOpen) throw requireNotNull(failure)
            listeners.forEach {
                it.onTransferInitializing(this, cache, false)
                it.onTransferStart(this, cache, false)
            }
            onOpen()
            return 100
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            failure?.let { throw it }
            if (read) return C.RESULT_END_OF_INPUT
            read = true
            listeners.forEach { it.onBytesTransferred(this, cache, false, if (mixed) 3 else 7) }
            if (mixed) listeners.forEach {
                it.onTransferInitializing(this, network, true)
                it.onTransferStart(this, network, true)
                it.onBytesTransferred(this, network, true, 4)
            }
            return 7
        }
        override fun close() { closed = true }
    }
}
