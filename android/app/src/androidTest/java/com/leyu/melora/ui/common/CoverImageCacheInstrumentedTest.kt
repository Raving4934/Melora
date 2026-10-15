package com.leyu.melora.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.leyu.melora.MeloraApplication
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用实际生产加载器/列表封面与真实 HTTP 计数区分下载、磁盘解码和内存命中。 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class CoverImageCacheInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun memoryDiskAndReopenedLoaderDoNotDownloadStableUrlsAgain(): Unit = runBlocking {
        Fixture(context).use { fixture ->
            val urls = (0 until 16).map { fixture.server.url(it) }
            suspend fun load(url: String): DataSource {
                val result = fixture.loader.execute(ImageRequest.Builder(context).data(url).size(84).build())
                assertTrue("图片必须加载成功：$result", result is SuccessResult)
                return (result as SuccessResult).dataSource
            }
            for (url in urls) assertEquals(DataSource.NETWORK, load(url))
            assertEquals(16, fixture.server.requests.get())
            Log.i(TAG, "first-pass requests=16 bytes=${fixture.server.bytes.get()}")
            for (url in urls) assertEquals(DataSource.MEMORY_CACHE, load(url))
            assertEquals(16, fixture.server.requests.get())
            fixture.loader.memoryCache!!.clear()
            for (url in urls) assertEquals(DataSource.DISK, load(url))
            assertEquals(16, fixture.server.requests.get())
            fixture.reopen()
            for (url in urls) assertEquals(DataSource.DISK, load(url))
            assertEquals(16, fixture.server.requests.get())
            Log.i(TAG, "repeat=MEMORY_CACHE; memory-cleared=DISK; reopened=DISK; extra-http=0")
            assertEquals(DataSource.NETWORK, load(urls.first() + "?token=changed"))
            assertEquals(17, fixture.server.requests.get())
            Log.i(TAG, "changed-url-same-bytes=NETWORK; extra-http=1 (URL cache key)")
        }
    }

    @Test fun scrollingRealArtworkRowsBackAndForthReusesCachedImages() {
        Fixture(context).use { fixture ->
            val previous = SingletonImageLoader.get(context)
            val visible = mutableStateOf(true)
            try {
                SingletonImageLoader.setUnsafe(fixture.loader)
                compose.setContent {
                    MeloraTheme {
                        if (visible.value) LazyColumn(Modifier.height(260.dp).testTag("covers")) {
                            items((0 until 24).toList(), key = { it }) { index ->
                                Row(Modifier.height(60.dp)) {
                                    SongArtwork(fixture.server.url(index), "song-$index", Modifier.size(42.dp))
                                    Text("测试歌曲 $index")
                                }
                            }
                        }
                    }
                }
                fun visit(indices: IntProgression) {
                    for (index in indices) {
                        compose.onNodeWithTag("covers").performScrollToIndex(index)
                        compose.waitUntil(10_000) { fixture.loaded.contains(fixture.server.url(index)) }
                    }
                    compose.waitForIdle()
                }
                visit(0 until 24)
                val before = fixture.server.requests.get()
                fixture.results.clear()
                visit(23 downTo 0)
                assertEquals("回滚已加载列表不应重新下载", before, fixture.server.requests.get())
                compose.waitUntil(10_000) { fixture.results.any { it.second == DataSource.MEMORY_CACHE } }
                val memory = fixture.results.count { it.second == DataSource.MEMORY_CACHE }
                assertTrue("必须实际观察到列表内存命中", memory > 0)
                // 重走列表并强制释放位图缓存，检验重新解码不等于重新下载。
                fixture.loader.memoryCache!!.clear()
                fixture.results.clear()
                visit(0 until 24)
                assertEquals("释放位图后仍应命中磁盘", before, fixture.server.requests.get())
                compose.waitUntil(10_000) { fixture.results.any { it.second == DataSource.DISK } }
                Log.i(TAG, "scroll first-http=$before; reverse-extra=0; cleared-memory-extra=0; memory-hits=$memory disk-hits=${fixture.results.count { it.second == DataSource.DISK }}")
            } finally {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                SingletonImageLoader.setUnsafe(previous)
            }
        }
    }

    private class Fixture(private val context: Context) : Closeable {
        val server = ImageServer()
        private val directory = File(context.cacheDir, "cover-test-${System.nanoTime()}").apply { mkdirs() }
        val results = CopyOnWriteArrayList<Pair<String, DataSource>>()
        val loaded = ConcurrentHashMap.newKeySet<String>()
        var loader = createLoader()
            private set
        private fun createLoader(): ImageLoader = MeloraApplication().newImageLoader(context).newBuilder()
            // 仅隔离测试存储位置；网络组件、缓存策略和淡入均来自生产工厂。
            .diskCache(DiskCache.Builder().directory(directory.toOkioPath()).build())
            .eventListener(object : EventListener() {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    results.add(request.data.toString() to result.dataSource)
                    loaded.add(request.data.toString())
                }
            }).build()
        fun reopen() {
            loader.shutdown()
            loader.diskCache!!.shutdown()
            loader = createLoader()
        }
        override fun close() {
            loader.shutdown()
            loader.diskCache!!.shutdown()
            server.close()
            check(directory.deleteRecursively())
        }
    }

    private class ImageServer : Closeable {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val bytes = AtomicInteger()
        private val png = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff4679ab.toInt())
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        private val worker = thread(isDaemon = true, name = "cover-cache-http") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        reader.readLine() ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(png)
                        output.flush()
                        bytes.addAndGet(png.size)
                    }
                } catch (error: java.io.IOException) {
                    if (!server.isClosed) Log.w(TAG, "fixture connection ended", error)
                }
            }
        }
        fun url(index: Int): String = "http://127.0.0.1:${server.localPort}/cover-$index.png"
        override fun close() { server.close(); worker.join(6_000) }
    }
    private companion object { const val TAG = "CoverCacheProbe" }
}
