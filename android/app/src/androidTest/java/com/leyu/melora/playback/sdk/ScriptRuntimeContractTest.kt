package com.leyu.melora.playback.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.leyu.melora.playback.lx.LxScriptEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 只执行无网络的通用脚本，验证真实QuickJS桥接；不加载用户源/设置/播放队列。 */
@RunWith(AndroidJUnit4::class)
class ScriptRuntimeContractTest {
    @Test fun structuredResultKeepsActualQualityAndMatchedMetadataThroughQuickJs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LxScriptEngine(context).use { engine ->
            engine.load("""
                lx.send(lx.EVENT_NAMES.inited, {status: true, sources: {
                  wy: {name: 'fixture', type: 'music', actions: ['musicUrl'], qualitys: ['flac24bit']}
                }});
                lx.on(lx.EVENT_NAMES.request, ({info}) => Promise.resolve({
                  url: 'https://example.test/audio.flac', type: 'flac', source: 'kw', resourceId: 'file-one',
                  musicInfo: {...info.musicInfo, source: 'kw', songmid: 'matched'}
                }));
            """.trimIndent(), "fixture.js")
            assertNotNull(engine.inited()?.optJSONObject("sources")?.optJSONObject("wy"))
            val song = OnlineSong(JSONObject().put("source", "wy").put("songmid", "original")
                .put("name", "Test Song").put("singer", "Test Artist").put("albumName", "Test Album").put("interval", "03:00"))
            val result = engine.request("wy", "musicUrl", JSONObject().put("type", "flac24bit").put("musicInfo", song.raw), 2000)
            val resolved = requireNotNull(SourceResolver.scriptResolution(song, LxScriptPool.ScriptResult("flac24bit", "fixture.js", requireNotNull(result))))
            assertEquals("flac", resolved.quality)
            assertEquals("kw_matched", resolved.song.uid)
            assertTrue(resolved.switched)
            assertTrue(resolved.resourceId.startsWith("lx:fixture.js:"))
        }
    }

    @Test fun ordinaryUrlOnlySourcesRemainCompatible() {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            engine.load("""
                lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{}});
                lx.on(lx.EVENT_NAMES.request, () => Promise.resolve('https://example.test/audio.mp3'));
            """.trimIndent(), "plain.js")
            assertEquals("https://example.test/audio.mp3", engine.request("kw", "musicUrl", JSONObject(), 2000))
        }
    }

    @Test(timeout = 10_000)
    fun cancellingSynchronousRequestKeepsQuickJsRuntimeUsable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LxScriptEngine(context).use { engine ->
            engine.load("""
                lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{
                  kw: {name:'fixture', type:'music', actions:['musicUrl'], qualitys:['320k']}
                }});
                lx.on(lx.EVENT_NAMES.request, ({info}) => {
                  if (info.busy) {
                    globalThis.__lxInited.busyStarted = true;
                    const deadline = Date.now() + 2000;
                    while (Date.now() < deadline) {}
                  }
                  return Promise.resolve(info.busy
                    ? 'https://example.test/after-busy-loop.mp3'
                    : 'https://example.test/follow-up.mp3');
                });
            """.trimIndent(), "cancel-runtime.js")

            val requestEntered = CompletableDeferred<Unit>()
            val busyRequest = launch(Dispatchers.IO) {
                engine.withCancellation { token ->
                    requestEntered.complete(Unit)
                    engine.request(
                        token,
                        "kw",
                        "musicUrl",
                        JSONObject().put("busy", true),
                        timeoutMs = 5_000,
                    )
                }
            }

            requestEntered.await()
            delay(100)
            val cancelStartedAt = System.nanoTime()
            busyRequest.cancel()
            withTimeout(5_000) { busyRequest.join() }
            val cancelWaitMs = (System.nanoTime() - cancelStartedAt) / 1_000_000L
            Log.i(TAG, "native cancellation elapsed=${cancelWaitMs}ms; red baseline=1897ms; JS loop=2000ms")

            // 即使后续断言失败，也先验证同一个 runtime 能否继续处理请求。
            val followUpResult = engine.request(
                "kw",
                "musicUrl",
                JSONObject().put("busy", false),
                timeoutMs = 2_000,
            )
            assertEquals(
                "https://example.test/follow-up.mp3",
                followUpResult,
            )
            // 读回脚本状态，确认取消确实发生在同步忙循环执行期间，而非仅取消了排队请求。
            assertTrue(engine.inited()?.optBoolean("busyStarted") == true)
            assertTrue(
                "cancel waited ${cancelWaitMs}ms for the finite 2s JS loop",
                cancelWaitMs < 1_500,
            )
        }
    }

    @Test(timeout = 5_000)
    fun initializationTimeoutInterruptsTopLevelJavaScriptAndRuntimeRecovers() = runBlocking {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            val startedAt = System.nanoTime()
            val initialized = engine.initialize(
                """
                const stopAt = Date.now() + 1200;
                while (Date.now() < stopAt) {}
                lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{}});
                """.trimIndent(),
                "init-timeout.js",
                timeoutMs = 200,
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Log.i(TAG, "native initialize timeout elapsed=${elapsedMs}ms; budget=200ms; finite JS loop=1200ms")
            assertNull(initialized)
            assertTrue("initialization timeout took ${elapsedMs}ms", elapsedMs < 900)

            engine.load("lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{}});", "after-init-timeout.js")
            assertNotNull(engine.inited())
        }
    }

    @Test(timeout = 5_000)
    fun inspectSourceHandlerCheckSharesBudgetAndRuntimeRecovers() = runBlocking {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            val busySource = """
                lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{
                  kw:{name:'fixture', type:'music', actions:['musicUrl']}
                }});
                lx.on(lx.EVENT_NAMES.request, () => Promise.resolve('https://example.test/audio.mp3'));
                globalThis.__lxHasRequestHandler = () => {
                  const stopAt = Date.now() + 1200;
                  while (Date.now() < stopAt) {}
                  return true;
                };
            """.trimIndent()

            val startedAt = System.nanoTime()
            val failure = runCatching {
                engine.inspectSource(busySource, "inspect-handler-timeout.js", timeoutMs = 200)
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Log.i(TAG, "native inspect timeout elapsed=${elapsedMs}ms; budget=200ms; finite JS loop=1200ms")
            assertTrue("expected inspect timeout, got $failure", failure is IllegalStateException)
            assertEquals("脚本检查超时", failure?.message)
            assertTrue("inspect timeout took ${elapsedMs}ms", elapsedMs < 900)

            val sources = engine.inspectSource(
                """
                    lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{
                      kw:{name:'fixture', type:'music', actions:['musicUrl']}
                    }});
                    lx.on(lx.EVENT_NAMES.request, () => Promise.resolve('https://example.test/audio.mp3'));
                    globalThis.__lxHasRequestHandler = () => true;
                """.trimIndent(),
                "inspect-after-timeout.js",
                timeoutMs = 1_000,
            )
            assertTrue(sources.has("kw"))
        }
    }

    @Test(timeout = 5_000)
    fun initedSerializationTimeoutInterruptsCustomToJsonAndRuntimeRecovers() {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            engine.load("""
                globalThis.__lxInited = {
                  status: true,
                  sources: {},
                  toJSON() {
                    const stopAt = Date.now() + 1200;
                    while (Date.now() < stopAt) {}
                    return {status:true, sources:{}};
                  }
                };
            """.trimIndent(), "inited-to-json-timeout.js")

            val startedAt = System.nanoTime()
            val failure = runCatching { engine.inited(timeoutMs = 200) }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Log.i(TAG, "native inited serialization timeout elapsed=${elapsedMs}ms; budget=200ms; finite JS loop=1200ms")
            assertTrue("expected inited serialization timeout, got $failure", failure is IllegalStateException)
            assertEquals("脚本状态读取超时", failure?.message)
            assertTrue("inited serialization timeout took ${elapsedMs}ms", elapsedMs < 900)

            engine.load(
                "globalThis.__lxInited.toJSON = () => ({status:true, sources:{}});",
                "inited-after-timeout.js",
            )
            assertTrue(engine.inited(timeoutMs = 1_000)?.optBoolean("status") == true)
        }
    }

    @Test(timeout = 5_000)
    fun synchronousRequestTimeoutInterruptsAndKeepsRuntimeUsable() {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            engine.load("""
                lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{}});
                lx.on(lx.EVENT_NAMES.request, ({info}) => {
                  if (info.busy) {
                    globalThis.__lxInited.timeoutStarted = true;
                    const stopAt = Date.now() + 1200;
                    while (Date.now() < stopAt) {}
                  }
                  return Promise.resolve('https://example.test/after-timeout.mp3');
                });
            """.trimIndent(), "request-timeout.js")

            val startedAt = System.nanoTime()
            val failure = runCatching {
                engine.request("kw", "musicUrl", JSONObject().put("busy", true), timeoutMs = 200)
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Log.i(TAG, "native request timeout elapsed=${elapsedMs}ms; budget=200ms; finite JS loop=1200ms")
            assertTrue("expected request timeout, got $failure", failure is IllegalStateException)
            assertEquals("脚本解析超时", failure?.message)
            assertTrue("request timeout took ${elapsedMs}ms", elapsedMs < 900)

            assertEquals(
                "https://example.test/after-timeout.mp3",
                engine.request("kw", "musicUrl", JSONObject().put("busy", false), timeoutMs = 1_000),
            )
            assertTrue(engine.inited()?.optBoolean("timeoutStarted") == true)
        }
    }

    @Test(timeout = 5_000)
    fun pendingPromiseTimeoutInterruptsFiniteBusyJobAndKeepsRuntimeUsable() {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            engine.load("""
                lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{}});
                lx.on(lx.EVENT_NAMES.request, ({info}) => Promise.resolve().then(() => {
                  if (info.busy) {
                    globalThis.__lxInited.pendingStarted = true;
                    const stopAt = Date.now() + 1200;
                    while (Date.now() < stopAt) {}
                  }
                  return 'https://example.test/after-pending-timeout.mp3';
                }));
            """.trimIndent(), "pending-timeout.js")

            val startedAt = System.nanoTime()
            val failure = runCatching {
                engine.request("kw", "musicUrl", JSONObject().put("busy", true), timeoutMs = 200)
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Log.i(TAG, "native Promise.then timeout elapsed=${elapsedMs}ms; budget=200ms; finite JS loop=1200ms")
            assertTrue("expected Promise.then timeout, got $failure", failure is IllegalStateException)
            assertEquals("脚本解析超时", failure?.message)
            assertTrue("Promise.then timeout took ${elapsedMs}ms", elapsedMs < 900)

            assertEquals(
                "https://example.test/after-pending-timeout.mp3",
                engine.request("kw", "musicUrl", JSONObject().put("busy", false), timeoutMs = 1_000),
            )
            assertTrue(engine.inited()?.optBoolean("pendingStarted") == true)
        }
    }

    @Test(timeout = 5_000)
    fun sdkTimeoutInterruptsSynchronousJavaScriptAndKeepsRuntimeUsable() {
        LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
            engine.load("""
                globalThis.__meloraInvoke = (payloadJson) => {
                  const payload = JSON.parse(payloadJson);
                  if (payload.action === 'busy') {
                    globalThis.sdkBusyStarted = true;
                    const stopAt = Date.now() + 1200;
                    while (Date.now() < stopAt) {}
                  }
                  globalThis.sdkResult = JSON.stringify({ok:true, data:{action:payload.action}});
                };
                globalThis.__meloraTake = () => globalThis.sdkResult || '';
            """.trimIndent(), "sdk-timeout.js")

            val startedAt = System.nanoTime()
            val failure = runCatching {
                engine.sdkCall("busy", "kw", JSONObject(), timeoutMs = 200)
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Log.i(TAG, "native SDK timeout elapsed=${elapsedMs}ms; budget=200ms; finite JS loop=1200ms")
            assertTrue("expected SDK timeout, got $failure", failure is IllegalStateException)
            assertEquals("目录请求超时", failure?.message)
            assertTrue("SDK timeout took ${elapsedMs}ms", elapsedMs < 900)

            assertEquals("ok", engine.sdkCall("ok", "kw", JSONObject(), timeoutMs = 1_000).getString("action"))
        }
    }


    @Test(timeout = 6_000)
    fun hostHttpDeadlineCrossesQuickJsEventThread() {
        DelayedHttpServer(responseDelayMs = 1_500).use { server ->
            LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
                loadLoopbackRequestScript(engine)
                val startedAt = System.nanoTime()
                val failure = runCatching {
                    engine.request(
                        "kw",
                        "musicUrl",
                        JSONObject().put("loopback", true).put("url", server.url),
                        timeoutMs = 300,
                    )
                }.exceptionOrNull()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                assertTrue("loopback request did not reach server", server.awaitRequest())
                Log.i(TAG, "loopback host HTTP deadline elapsed=${elapsedMs}ms; budget=300ms; response delay=1500ms")
                assertTrue("expected bounded host HTTP failure, got $failure", failure is IllegalStateException)
                assertTrue("host HTTP ignored runtime deadline (${elapsedMs}ms)", elapsedMs < 1_000)
                assertEquals(
                    "https://example.test/follow-up.mp3",
                    engine.request("kw", "musicUrl", JSONObject(), timeoutMs = 1_000),
                )
            }
        }
    }

    @Test(timeout = 6_000)
    fun cancellationCancelsHostHttpAcrossQuickJsEventThread() = runBlocking {
        DelayedHttpServer(responseDelayMs = 1_500).use { server ->
            LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext).use { engine ->
                loadLoopbackRequestScript(engine)
                val requestEntered = CompletableDeferred<Unit>()
                val requestJob = launch(Dispatchers.IO) {
                    engine.withCancellation { token ->
                        requestEntered.complete(Unit)
                        engine.request(
                            token,
                            "kw",
                            "musicUrl",
                            JSONObject().put("loopback", true).put("url", server.url),
                            timeoutMs = 5_000,
                        )
                    }
                }
                requestEntered.await()
                assertTrue("loopback request did not reach server", server.awaitRequest())

                val cancelStartedAt = System.nanoTime()
                requestJob.cancel()
                withTimeout(3_000) { requestJob.join() }
                val cancelElapsedMs = (System.nanoTime() - cancelStartedAt) / 1_000_000L
                Log.i(TAG, "loopback host HTTP cancellation elapsed=${cancelElapsedMs}ms; response delay=1500ms")
                assertTrue("host HTTP call was not cancelled promptly (${cancelElapsedMs}ms)", cancelElapsedMs < 1_000)
                assertEquals(
                    "https://example.test/follow-up.mp3",
                    engine.request("kw", "musicUrl", JSONObject(), timeoutMs = 1_000),
                )
            }
        }
    }

    private fun loadLoopbackRequestScript(engine: LxScriptEngine) {
        // HTTP callback executes on QuickJS's event thread while its call context is established on IO.
        val script = """
            lx.send(lx.EVENT_NAMES.inited, {status:true, sources:{}});
            lx.on(lx.EVENT_NAMES.request, ({info}) => info.loopback
              ? lx.request(info.url, {timeout:5000, responseType:'text'}).then((response) => response.body)
              : Promise.resolve('https://example.test/follow-up.mp3'));
        """.trimIndent()
        engine.load(script, "loopback-http.js")
    }

    private class DelayedHttpServer(private val responseDelayMs: Long) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestReceived = CountDownLatch(1)
        @Volatile private var client: Socket? = null
        val url = "http://127.0.0.1:${server.localPort}/delayed"
        private val worker = thread(name = "lx-loopback-http", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    client = socket
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    requestReceived.countDown()
                    Thread.sleep(responseDelayMs)
                    val body = "delayed".toByteArray(StandardCharsets.UTF_8)
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(StandardCharsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // Client-side timeout/cancel closes the socket before this finite delayed write.
            } finally {
                client = null
            }
        }

        fun awaitRequest(): Boolean = requestReceived.await(2, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            runCatching { client?.close() }
            worker.interrupt()
            worker.join(1_000)
        }
    }

    private companion object {
        const val TAG = "ScriptRuntimeContractTest"
    }
}
