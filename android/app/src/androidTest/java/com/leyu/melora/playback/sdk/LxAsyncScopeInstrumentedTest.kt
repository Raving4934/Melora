package com.leyu.melora.playback.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.lx.LxScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 真实 QuickJS 的原生 await/job 作用域回归；只访问 loopback，不读取用户数据。 */
@RunWith(AndroidJUnit4::class)
class LxAsyncScopeInstrumentedTest {
    @Test(timeout = 8_000)
    fun successfulRequestRetiresFireAndForgetAwaitBeforeAnySideEffect() = runBlocking {
        assertRetiredContinuation(mode = "success")
    }

    @Test(timeout = 8_000)
    fun failedRequestRetiresAwaitBeforeAnySideEffect() = runBlocking {
        assertRetiredContinuation(mode = "failure")
    }

    @Test(timeout = 8_000)
    fun timedOutRequestRetiresItsPendingNativeAwait() = runBlocking {
        assertRetiredContinuation(mode = "timeout")
    }

    @Test(timeout = 10_000)
    fun cancelledRequestRetiresAwaitRegisteredBeforeHttpHandshake() = runBlocking {
        assertCancelledContinuation(sdk = false)
    }

    @Test(timeout = 12_000)
    fun sdkSuccessFailureAndTimeoutRetireAwaitAndDirectNativeHttp() = runBlocking {
        for (mode in listOf("success", "failure", "timeout")) {
            assertRetiredContinuation(mode = mode, sdk = true)
        }
    }

    @Test(timeout = 10_000)
    fun cancelledSdkCallRetiresAwaitRegisteredBeforeHttpHandshake() = runBlocking {
        assertCancelledContinuation(sdk = true)
    }

    @Test(timeout = 12_000)
    fun nestedAwaitAndThenableJobsCannotResumeUnderTheNextOwner() = runBlocking {
        for (kind in listOf("nested", "thenable")) {
            assertRetiredContinuation(mode = "success", kind = kind)
        }
    }

    @Test(timeout = 8_000)
    fun initializationAndLiveRequestAsyncWorkSurviveBothEngineReuses() = runBlocking {
        LoopbackServer().use { server ->
            newEngine().use { engine ->
                engine.load(scopeScript(), "async-scope-initialization.js")
                // 没有 A；初始化异步任务必须仍可在后续请求的任务泵中继续。
                assertReusable(engine, server, sdk = false, registered = 0)
            }
        }
    }

    @Test(timeout = 8_000)
    fun tokensMayExecuteInTheReverseOfTheirAllocationOrder() = runBlocking {
        newEngine().use { engine ->
            engine.load("lx.on('request', ({info}) => Promise.resolve(info.owner));", "token-order.js")
            engine.withCancellation { older ->
                engine.withCancellation { newer ->
                    assertEquals("newer", engine.request(newer, "kw", "musicUrl", JSONObject().put("owner", "newer")))
                    assertEquals("older", engine.request(older, "kw", "musicUrl", JSONObject().put("owner", "older")))
                }
            }
        }
    }

    @Test(timeout = 12_000)
    fun sharedInitializationAdoptionSurvivesCancellationBeforeTheFirstPump() = runBlocking {
        for (kind in listOf("promise", "thenable")) {
            LoopbackServer(holdResponse = true).use { handshake ->
                newEngine().use { engine ->
                    engine.load("""
                        let settle, initialized;
                        const shared = new Promise(resolve => { settle = resolve; });
                        (async () => { initialized = await shared; })();
                        lx.on('request', ({info}) => {
                          if (info.blocked) {
                            settle(info.kind === 'promise' ? Promise.resolve('ready') :
                              {then(resolve) { resolve('ready'); }});
                            __lxNative.http(JSON.stringify({url:info.url, timeout:5000}));
                            return 'cancelled';
                          }
                          return (async () => ({value:await shared, initialized}))();
                        });
                    """.trimIndent(), "initialization-adoption.js")
                    kotlinx.coroutines.coroutineScope {
                        val job = launch(Dispatchers.IO) {
                            engine.withCancellation { token ->
                                engine.request(token, "kw", "musicUrl", JSONObject()
                                    .put("blocked", true).put("kind", kind)
                                    .put("url", handshake.url("adoption-queued")), 5_000)
                            }
                        }
                        try {
                            assertTrue("adoption handshake missing", handshake.awaitRequest())
                        } finally {
                            job.cancel()
                            withTimeout(3_000) { job.join() }
                        }
                        assertTrue(job.isCancelled)
                    }
                    val result = JSONObject(engine.request("kw", "musicUrl", JSONObject(), 1_000).toString())
                    assertEquals("ready", result.getString("value"))
                    assertEquals("ready", result.getString("initialized"))
                }
            }
        }
    }

    private suspend fun assertRetiredContinuation(
        mode: String,
        sdk: Boolean = false,
        kind: String = "plain",
    ) {
        LoopbackServer().use { server ->
            newEngine().use { engine ->
                engine.load(scopeScript(), "async-scope-$mode-$kind.js")
                val params = JSONObject().put("mode", mode).put("kind", kind)
                    .put("leakUrl", server.url("old-A"))
                val result = runCatching { invoke(engine, sdk, params, 500) }
                when (mode) {
                    "success" -> {
                        assertEquals("A", result.getOrThrow().getString("owner"))
                        assertEquals(1, result.getOrThrow().getInt("registered"))
                    }
                    "failure" -> assertTrue(
                        "expected fixture failure, got ${result.exceptionOrNull()}",
                        result.exceptionOrNull()?.message?.contains("async scope fixture failure") == true,
                    )
                    "timeout" -> assertEquals(
                        if (sdk) "目录请求超时" else "脚本解析超时",
                        result.exceptionOrNull()?.message,
                    )
                    else -> error("unsupported fixture mode: $mode")
                }
                assertReusable(engine, server, sdk, registered = 1, kind = kind)
            }
        }
    }

    private suspend fun assertCancelledContinuation(sdk: Boolean) {
        LoopbackServer().use { effects ->
            LoopbackServer(holdResponse = true).use { handshake ->
                newEngine().use { engine ->
                    engine.load(scopeScript(), "async-scope-cancel.js")
                    val params = JSONObject().put("mode", "blocked").put("kind", "plain")
                        .put("leakUrl", effects.url("old-A"))
                        .put("handshakeUrl", handshake.url("await-registered"))
                    val call = kotlinx.coroutines.coroutineScope {
                        val job = launch(Dispatchers.IO) {
                            if (sdk) engine.sdkCall("fixture", "kw", params, 5_000)
                            else engine.withCancellation { token ->
                                engine.request(token, "kw", "musicUrl", params, 5_000)
                            }
                        }
                        try {
                            // staleContinuation 已同步运行到 await，之后才进入阻塞 HTTP。
                            assertTrue("await registration handshake missing", handshake.awaitRequest())
                            assertEquals(listOf("/await-registered"), handshake.paths())
                        } finally {
                            job.cancel()
                            withTimeout(3_000) { job.join() }
                        }
                        job
                    }
                    assertTrue("request did not observe cancellation", call.isCancelled)
                    assertReusable(engine, effects, sdk, registered = 1)
                }
            }
        }
    }

    private suspend fun invoke(
        engine: LxScriptEngine,
        sdk: Boolean,
        params: JSONObject,
        timeoutMs: Long = 2_000,
    ): JSONObject = if (sdk) engine.sdkCall("fixture", "kw", params, timeoutMs)
    else JSONObject(engine.request("kw", "musicUrl", params, timeoutMs).toString())

    private suspend fun assertReusable(
        engine: LxScriptEngine,
        server: LoopbackServer,
        sdk: Boolean,
        registered: Int,
        kind: String = "plain",
    ) {
        for ((owner, now) in listOf("B" to 100, "C" to 300)) {
            val summary = invoke(engine, sdk, JSONObject().put("mode", "advance")
                .put("owner", owner).put("now", now).put("liveUrl", server.url(owner)))
            assertEquals(owner, summary.getString("owner"))
            assertEquals("await must have been registered before A ended", registered, summary.getInt("registered"))
            assertEquals("retired native await mutated state in $owner", 0, summary.getInt("resumed"))
            assertEquals("retired nested await mutated state in $owner", 0, summary.getInt("nestedResumed"))
            assertEquals("retired continuation created a runnable timer", 0, summary.getInt("oldTimers"))
            assertEquals("retired continuation completed HTTP", 0, summary.getInt("afterHttp"))
            assertEquals(if (kind == "thenable") 1 else 0, summary.getInt("thenableRegistered"))
            assertEquals(1, summary.getInt("initializationContinued"))
            assertEquals(1, summary.getInt("initializationNested"))
            assertEquals(if (owner == "B") 0 else 1, summary.getInt("initializationTimer"))
            assertEquals(if (owner == "B") "B" else "B,C", summary.getString("liveOwners"))
            assertEquals(200, summary.getInt("liveStatus"))
            assertEquals(
                "only the live B/C requests may reach native HTTP",
                if (owner == "B") listOf("/B") else listOf("/B", "/C"),
                server.paths(),
            )
        }
    }

    private fun newEngine() = LxScriptEngine(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun scopeScript() = """
        let clock = 0;
        Date.now = () => clock;
        const state = {
          registered: 0, resumed: 0, nestedResumed: 0, oldTimers: 0, afterHttp: 0,
          thenableRegistered: 0, initializationContinued: 0,
          initializationNested: 0, initializationTimer: 0
        };
        const liveOwners = [];
        const thenableResolvers = [];
        const initialized = new Promise(resolve => setTimeout(() => {
          resolve('initialized');
          for (const complete of thenableResolvers) complete('initialized');
        }, 100));
        const initializedThenable = {then(resolve) {
          state.thenableRegistered++;
          thenableResolvers.push(resolve);
        }};
        (async () => {
          await initialized;
          state.initializationContinued++;
          await {then(resolve) { resolve('initialization nested await'); }};
          state.initializationNested++;
          setTimeout(() => state.initializationTimer++, 20);
        })();
        function directHttp(url) {
          const response = JSON.parse(__lxNative.http(JSON.stringify({
            url, method:'GET', timeout:2000, responseType:'text'
          })));
          if (response.error) throw new Error(response.error);
          return response;
        }
        async function staleContinuation(info, sdk) {
          state.registered++;
          if (info.kind === 'nested') {
            await (async () => {
              await initialized;
              state.nestedResumed++;
              return await {then(resolve) { resolve('nested'); }};
            })();
          } else if (info.kind === 'thenable') {
            await Promise.resolve(initializedThenable);
          } else {
            await initialized;
          }
          // 这些状态修改必须全部被阻止，不能只在 native.http 边界拒绝旧请求。
          state.resumed++;
          setTimeout(() => state.oldTimers++, 0);
          if (sdk) directHttp(info.leakUrl);
          else await lx.request(info.leakUrl, {timeout:2000});
          state.afterHttp++;
          return {owner:'A', registered:state.registered};
        }
        async function advance(info, sdk) {
          clock = info.now;
          // 两次 timer checkpoint 分隔完整的微任务排空，不依赖真实时间或 job 数猜测。
          await new Promise(resolve => setTimeout(resolve, 0));
          await new Promise(resolve => setTimeout(resolve, 0));
          let response;
          if (sdk) response = directHttp(info.liveUrl);
          else response = await lx.request(info.liveUrl, {timeout:2000});
          await {then(resolve) { resolve('live request thenable'); }};
          liveOwners.push(info.owner);
          return Object.assign({}, state, {
            owner:info.owner, liveOwners:liveOwners.join(','), liveStatus:response.statusCode
          });
        }
        function run(info, sdk) {
          if (info.mode === 'advance') return advance(info, sdk);
          const background = staleContinuation(info, sdk);
          if (info.mode === 'failure') throw new Error('async scope fixture failure');
          if (info.mode === 'timeout') return background;
          if (info.mode === 'blocked') {
            // async 函数调用已登记 await；这里的同步宿主 HTTP 才允许测试线程取消。
            directHttp(info.handshakeUrl);
            return background;
          }
          return {owner:'A', registered:state.registered};
        }
        lx.on(lx.EVENT_NAMES.request, ({info}) => run(info, false));
        let sdkResult = '';
        let sdkGeneration = 0;
        globalThis.__meloraInvoke = payloadJson => {
          const generation = ++sdkGeneration;
          const {params} = JSON.parse(payloadJson);
          sdkResult = '';
          try {
            Promise.resolve(run(params, true)).then(
              data => {
                if (generation === sdkGeneration) sdkResult = JSON.stringify({ok:true, data});
              },
              error => {
                if (generation === sdkGeneration) sdkResult = JSON.stringify({ok:false, error:String(error)});
              }
            );
          } catch (error) {
            sdkResult = JSON.stringify({ok:false, error:String(error)});
          }
        };
        globalThis.__meloraTake = () => {
          const result = sdkResult;
          sdkResult = '';
          return result;
        };
    """.trimIndent()

    private class LoopbackServer(private val holdResponse: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val requests = Collections.synchronizedList(mutableListOf<String>())
        @Volatile private var active: Socket? = null
        private val worker = thread(name = "lx-async-scope-loopback", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                active = socket
                try {
                    socket.use { client ->
                        client.soTimeout = 3_000
                        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val first = reader.readLine() ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) { /* 读完整个 HTTP 请求头。 */ }
                        requests += first.split(' ')[1]
                        entered.countDown()
                        if (holdResponse) release.await(5, TimeUnit.SECONDS)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                .toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) {
                    // 取消会主动关闭握手 socket；下一次 loopback 请求仍可继续。
                } finally {
                    active = null
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"
        fun awaitRequest(): Boolean = entered.await(3, TimeUnit.SECONDS)
        fun paths(): List<String> = synchronized(requests) { requests.toList() }

        override fun close() {
            release.countDown()
            server.close()
            active?.close()
            worker.join(1_000)
        }
    }
}
