package com.leyu.melora.ui.leaderboard

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.lx.LxScriptEngine
import com.leyu.melora.playback.sdk.BoardItem
import com.leyu.melora.playback.sdk.MusicSdkEngine
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.SlotLoadBalancer
import com.leyu.melora.ui.theme.MeloraTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 真实 LeaderboardScreen 的平台切换竞态验证。
 *
 * 只替换 MusicSdkEngine 读取的 JS 协议脚本；平台 StateFlow、下拉刷新、页面缓存代次、
 * OnlineRepository 和 MusicSdkEngine 的 slot 调度全部走生产实现。A 的下拉 boards
 * 请求被本地 HTTP 服务按门闩挂起，B 首载由脚本确定性失败；切源后先验证取消清理，
 * 再释放 A 的迟到回包，并验证 B 的错误不会被它清除。
 */
@RunWith(AndroidJUnit4::class)
class LeaderboardSwitchRaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val previousPlatform = MeloraSettings.leaderboardPlatform.value
    private val previousPull = MeloraSettings.pullToRefresh.value
    private val previousBoardsA = OnlineCache.peek<List<BoardItem>>(A_CACHE_KEY)
    private val previousBoardsB = OnlineCache.peek<List<BoardItem>>(B_CACHE_KEY)
    private val visible = mutableStateOf(true)

    @After
    fun cleanup() {
        if (visible.value) {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        }
        restoreCache(A_CACHE_KEY, previousBoardsA)
        restoreCache(B_CACHE_KEY, previousBoardsB)
        MeloraSettings.pullToRefresh.value = previousPull
        MeloraSettings.leaderboardPlatform.value = previousPlatform
    }

    @Test
    fun staleARefreshCannotClearBFailureAcrossPlatformSwitch() {
        runBlocking {
            val sdkCodeField = sdkField("sdkCode")
            val appContextField = sdkField("appContext")
            val slotsField = sdkField("slots")
            @Suppress("UNCHECKED_CAST")
            val slots = slotsField.get(MusicSdkEngine) as MutableList<Any>
            val loadBalancer = sdkField("loadBalancer").get(MusicSdkEngine) as SlotLoadBalancer
            val snapshot = loadBalancer.snapshot()
            assertTrue("fixture requires an idle SDK pool: $snapshot", snapshot.all { it == 0 })

            val localFixture = Fixture()
            val installed = localFixture.install(
                context = context,
                sdkCodeField = sdkCodeField,
                slotsField = slotsField,
                appContextField = appContextField,
            )
            try {
                // 切源前确认两端都没有缓存内容，B 的首载失败来源才是真实脚本错误。
                MeloraSettings.pullToRefresh.value = true
                MeloraSettings.leaderboardPlatform.value = A_SOURCE
                OnlineCache.clear(A_CACHE_KEY)
                OnlineCache.clear(B_CACHE_KEY)
                OnlineCache.clearDisk(context)
                // A 已成功首载的真实页面快照。网络首载被此快照短路，不依赖 JS 的局部调用次数。
                OnlineCache.put(
                    A_CACHE_KEY,
                    listOf(BoardItem(id = "A1", name = A_TEXT, bangid = "A1")),
                )

                compose.setContent {
                    MeloraTheme {
                        if (visible.value) LeaderboardScreen()
                    }
                }

                compose.onNodeWithTag("leaderboard-official-header").assertExists()
                // 实际生产 PullRefreshContainer：下拉后才发起 A 的 boards 网络请求。
                compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
                // 新请求由重组后的LaunchedEffect启动，等待时必须推进Compose测试时钟。
                compose.waitUntil(5_000) { localFixture.refreshedAReached.count == 0L }
                assertTrue("A refresh must remain pending before platform switch", !localFixture.releaseA.await(0, TimeUnit.MILLISECONDS))

                compose.runOnIdle { MeloraSettings.leaderboardPlatform.value = B_SOURCE }
                awaitText(B_FAILURE_MESSAGE)

                // 切源必须取消 A 的旧刷新；先在真实 SDK 槽位上等到取消/清理完成。
                compose.waitUntil(3_000) {
                    loadBalancer.snapshot().all { it == 0 }
                }
                compose.waitForIdle()

                // 释放已取消请求的迟到回包；它没有资格再写 B 的 UI 或缓存。
                localFixture.releaseAResponse()
                compose.waitForIdle()
                compose.waitUntil(3_000) {
                    loadBalancer.snapshot().all { it == 0 }
                }
                compose.waitForIdle()

                compose.onNodeWithText(B_FAILURE_MESSAGE).assertExists()
                assertTrue(
                    "A late boards must never render inside B",
                    compose.onAllNodesWithText(A_CACHE_TEXT).fetchSemanticsNodes().isEmpty(),
                )
                assertNull("A late response must not write B cache", OnlineCache.peek<List<BoardItem>>(B_CACHE_KEY))
            } finally {
                localFixture.releaseAResponse()
                withContext(NonCancellable) {
                    localFixture.restore(installed)
                }
            }
        }
    }

    private suspend fun awaitText(text: String) {
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun sdkField(name: String) =
        MusicSdkEngine.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun field(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun restoreCache(key: String, value: List<BoardItem>?) {
        if (value == null) OnlineCache.clear(key) else OnlineCache.put(key, value)
    }

    private class Fixture {
        private val aServer = DelayedBoardServer()
        val refreshedAReached = aServer.requestReceived
        val releaseA = aServer.releaseGate
        private val released = AtomicBoolean(false)

        fun install(
            context: android.content.Context,
            sdkCodeField: java.lang.reflect.Field,
            slotsField: java.lang.reflect.Field,
            appContextField: java.lang.reflect.Field,
        ): InstalledSdk {
            @Suppress("UNCHECKED_CAST")
            val slots = slotsField.get(MusicSdkEngine) as MutableList<Any>
            val originalSlots = slots.toList()
            val constructor = originalSlots.first().javaClass
                .getDeclaredConstructor(Int::class.javaPrimitiveType!!).apply { isAccessible = true }
            val injected = slots.indices.map { constructor.newInstance(it) }

            val installed = InstalledSdk()
            installed.sdkCodeField = sdkCodeField
            installed.appContextField = appContextField
            installed.slotsField = slotsField
            installed.originalSdkCode = sdkCodeField.get(MusicSdkEngine)
            installed.originalAppContext = appContextField.get(MusicSdkEngine)
            installed.originalSlots = originalSlots
            installed.injectedSlots = injected

            slots.indices.forEach { slots[it] = injected[it] }
            sdkCodeField.set(MusicSdkEngine, fakeSdkCode(aServer.url))
            appContextField.set(MusicSdkEngine, context.applicationContext)
            return installed
        }

        fun releaseAResponse() {
            if (released.compareAndSet(false, true)) aServer.release()
        }

        suspend fun restore(installed: InstalledSdk) {
            @Suppress("UNCHECKED_CAST")
            val slots = installed.slotsField.get(MusicSdkEngine) as MutableList<Any>
            val restoredSdk = installed.originalSdkCode
            val restoredContext = installed.originalAppContext
            try {
                closeInjectedSlots(installed.injectedSlots)
            } finally {
                slots.indices.forEach { slots[it] = installed.originalSlots[it] }
                installed.sdkCodeField.set(MusicSdkEngine, restoredSdk)
                installed.appContextField.set(MusicSdkEngine, restoredContext)
                aServer.close()
            }
        }

        private suspend fun closeInjectedSlots(slots: List<Any>) {
            slots.forEach { slot ->
                    val worker = field(slot, "worker").get(slot) as? Job
                    worker?.cancel()
                    worker?.join()
                    (field(slot, "high").get(slot) as Channel<*>).cancel()
                    (field(slot, "low").get(slot) as Channel<*>).cancel()
                    // LxScriptEngine 是 QuickJS 线程亲和的；关闭必须在 slot dispatcher 上执行。
                    val dispatcher = field(slot, "dispatcher").get(slot) as kotlinx.coroutines.CoroutineDispatcher
                    withContext(dispatcher) {
                        (field(slot, "engine").get(slot) as? LxScriptEngine)?.close()
                        field(slot, "engine").set(slot, null)
                    }
                    (dispatcher as Closeable).close()
            }
        }

        private fun field(target: Any, name: String) =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }

        private fun fakeSdkCode(url: String) = """
            const result = (ok, data, error) => JSON.stringify(
              ok ? {ok: true, data} : {ok: false, error}
            );
            globalThis.__meloraInvoke = (payloadJson) => {
              const {action, source, params} = JSON.parse(payloadJson);
              globalThis.sdkResult = '';
              if (source === 'kg' && action === 'boards') {
                globalThis.sdkResult = result(false, null, ${JSONObject.quote(B_FAILURE_MESSAGE)});
                return;
              }
              if (source === 'kw' && action === 'boards') {
                globalThis.sdkResult = '';
                lx.request(${JSONObject.quote(url)}, {timeout: 5000, responseType: 'text'}).then(
                  ({body}) => {
                    globalThis.sdkResult = result(true, JSON.parse(body));
                  },
                  error => {
                    globalThis.sdkResult = result(false, null, String(error));
                  }
                );
                return;
              }
              if (action === 'boardSongs') {
                globalThis.sdkResult = result(true, {list: [], total: 0, page: 1, allPage: 0});
                return;
              }
              globalThis.sdkResult = result(true, {});
            };
            globalThis.__meloraTake = () => globalThis.sdkResult;
        """.trimIndent()

        class InstalledSdk {
            lateinit var sdkCodeField: java.lang.reflect.Field
            lateinit var appContextField: java.lang.reflect.Field
            lateinit var slotsField: java.lang.reflect.Field
            var originalSdkCode: Any? = null
            var originalAppContext: Any? = null
            lateinit var originalSlots: List<Any>
            lateinit var injectedSlots: List<Any>
        }
    }

    private class DelayedBoardServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requestReceived = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        private val worker = thread(name = "leaderboard-a-refresh", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requestReceived.countDown()
                    releaseGate.await()
                    val body = """
                        {"list":[{"id":"A1","name":${JSONObject.quote(A_CACHE_TEXT)},"bangid":"A1"}]}
                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                        write(body)
                        flush()
                    }
                }
            } catch (_: IOException) {
                // A 已被切换取消时，释放门闩后的迟到回包会被 OkHttp 主动断开。
            }
        }

        val url = "http://127.0.0.1:${server.localPort}/held"

        fun release() = releaseGate.countDown()

        override fun close() {
            releaseGate.countDown()
            runCatching { server.close() }
            worker.join(1_000)
        }
    }

    private companion object {
        const val A_SOURCE = "kw"
        const val B_SOURCE = "kg"
        const val A_CACHE_KEY = "boards.kw"
        const val B_CACHE_KEY = "boards.kg"
        const val A_TEXT = "A首载榜单"
        const val A_CACHE_TEXT = "A迟到榜单"
        const val B_FAILURE_MESSAGE = "B平台首载失败"
    }
}
