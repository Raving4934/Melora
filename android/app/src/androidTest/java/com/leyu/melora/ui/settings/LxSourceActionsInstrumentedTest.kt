package com.leyu.melora.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.SourceAlias
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.lx.LxSourceImporter
import com.leyu.melora.ui.HostOrientationFixture
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LxSourceActionsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val hostOrientation = HostOrientationFixture(compose)

    private lateinit var files: File
    private lateinit var fixtureContext: Context
    private lateinit var store: LxScriptStore

    @Before
    fun prepareSourceStorage() {
        val activity = compose.activity
        files = Files.createTempDirectory(activity.cacheDir.toPath(), "lx-source-actions-").toFile()
        fixtureContext = FixtureContext(activity.applicationContext, files)
        store = LxScriptStore(fixtureContext)
    }

    @After
    fun removeSourceStorage() {
        store.list().forEach { store.remove(it.id) }
        fixtureContext.deleteSharedPreferences("${files.name}.${LxScriptStore.PREFS}")
        files.deleteRecursively()
    }

    @Test
    fun localSourceHasOneValidityActionAndShowsProtocolPassWithoutAPlaybackClaim() {
        store.import("lyrics.js", lyricSource("1"))
        showSourceMenu()

        compose.onAllNodesWithText("有效性测试").assertCountEquals(1)
        compose.onNodeWithText("检查脚本协议").assertDoesNotExist()
        compose.onNodeWithText("测试播放链接").assertDoesNotExist()
        compose.onNodeWithText("检查更新").assertDoesNotExist()
        compose.onNodeWithText("有效性测试").performClick()

        val color = statusColor("脚本协议通过")
        assertTrue(color.green > color.red && color.green > color.blue)
        val platform = SourceAlias.display("wy", "网易云音乐")
        compose.awaitStable(
            compose.onNode(hasText("声明平台", substring = true) and hasText(platform, substring = true)),
        )
        compose.awaitStable(compose.onNodeWithText("未声明在线平台的播放能力", substring = true))
    }

    @Test
    fun invalidProtocolShowsRedFailureAndTheCause() {
        store.import("invalid.js", "lx.send(lx.EVENT_NAMES.inited, {status:false,sources:{}});")
        showSourceMenu()
        compose.onNodeWithText("有效性测试").performClick()

        val color = statusColor("脚本协议未通过")
        assertTrue(color.red > color.green && color.red > color.blue)
        compose.awaitStable(compose.onNodeWithText("脚本报告初始化失败", substring = true))
    }

    @Test
    fun landscapeValidityRetryKeepsResultsAndActionsInPlace() = inLandscape {
        ScriptServer("ready", holdResponse = true).use { server ->
            val script = store.import(
                "five-platforms.js",
                """
                // @name 五平台测试源
                lx.on('request', () => '');
                const sources = {};
                for (const source of ['kw', 'kg', 'tx', 'wy', 'mg']) {
                    sources[source] = {actions: ['musicUrl'], qualitys: ['unavailable-fixture-quality']};
                }
                lx.send('inited', {sources});
                """.trimIndent(),
            )
            showSourceMenu()
            compose.onNodeWithText("有效性测试").performClick()

            val inSheet = hasAnyAncestor(isDialog())
            compose.awaitStable(compose.onNode(inSheet and hasText("待确认 5 项", substring = true)))
            compose.onAllNodes(
                inSheet and hasText("待确认", substring = true) and
                    hasText("当前播放音质没有兼容档位", substring = true),
            ).assertCountEquals(5)

            val lastPlatform = compose.onNode(inSheet and hasText(SourceAlias.display("mg", "咪咕音乐")))
            lastPlatform.performScrollTo().assertIsDisplayed()
            val close = compose.onNodeWithText("关闭")
            close.assertIsDisplayed().assertIsEnabled()
            val retry = compose.onNodeWithText("重新测试")
            retry.assertIsDisplayed().assertIsEnabled()
            val header = compose.onNode(inSheet and hasText("有效性测试"))
            val beforeRetry = compose.boundsInSameFrame(header, lastPlatform, close)
            val scrollOffset = validityBody().fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("先把测试结果滚到列表底部", scrollOffset > 0f)

            store.import(
                script.id,
                """
                lx.on('request', () => '');
                lx.request('${server.url}', {timeout:7500}).then(() => {
                    lx.send('inited', {sources:{wy:{actions:['lyric']}}});
                });
                """.trimIndent(),
            )
            retry.performTouchInput { click(center) }
            compose.waitUntil(5_000) { server.requests.get() == 1 }
            retry.assertIsDisplayed().assertIsNotEnabled()
            close.assertIsDisplayed().assertIsEnabled()
            lastPlatform.assertIsDisplayed()
            assertEquals(
                "等待重测时保留结果的滚动位置",
                scrollOffset,
                validityBody().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
                1f,
            )
            val duringRetry = compose.boundsInSameFrame(header, lastPlatform, close)
            for ((before, during) in beforeRetry.zip(duringRetry)) {
                assertEquals("重测时保持标题、结果和关闭按钮的纵向位置", before.top, during.top, 1f)
                assertEquals("重测时保持标题、结果和关闭按钮的高度", before.height, during.height, 1f)
            }

            server.releaseResponse()
            compose.awaitStable(compose.onNodeWithText("未声明在线平台的播放能力"))
            lastPlatform.assertDoesNotExist()
            retry.assertIsEnabled()

            close.performTouchInput { click(center) }
            compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun landscapeValidityLongErrorScrollsWithoutCoveringTheActions() = inLandscape {
        val script = store.import(
            "long-error.js",
            "throw new Error('初始化异常，请核对音源配置。'.repeat(100) + '诊断末尾');",
        )
        showSourceMenu()
        compose.onNodeWithText("有效性测试").performClick()

        val error = compose.onNode(hasAnyAncestor(isDialog()) and hasText("诊断末尾", substring = true))
        compose.awaitStable(error)
        val close = compose.onNodeWithText("关闭")
        val retry = compose.onNodeWithText("重新测试")
        close.assertIsDisplayed().assertIsEnabled()
        retry.assertIsDisplayed().assertIsEnabled()
        val actions = compose.boundsInSameFrame(retry, close)
        val body = validityBody()
        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val end = range.maxValue()
        assertTrue("长错误应有可滚动的正文", end > 0f)
        body.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertTrue(scrollBy(0f, end))
        }
        compose.waitUntil(5_000) { range.value() >= end - 1f }
        error.assertIsDisplayed()
        assertEquals("滚动错误正文时页脚保持固定", actions, compose.boundsInSameFrame(retry, close))

        store.import(script.id, lyricSource("2"))
        retry.performTouchInput { click(center) }
        compose.awaitStable(compose.onNodeWithText("未声明在线平台的播放能力"))
        error.assertDoesNotExist()
        close.performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    private fun validityBody(): SemanticsNodeInteraction = compose.onNode(
        hasAnyAncestor(isDialog()) and hasScrollAction() and SemanticsMatcher("可滚动的结果正文") {
            val range = it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            range != null && range.maxValue() > 0f
        },
    )

    private inline fun inLandscape(test: () -> Unit) {
        try {
            hostOrientation.request(Configuration.ORIENTATION_LANDSCAPE)
            test()
        } finally {
            hostOrientation.restore()
        }
    }

    @Test
    fun closeButtonLabelHasReadableContrastInDarkMode() {
        val previousMode = MeloraSettings.themeMode.value
        val previousEInk = MeloraSettings.eInkMode.value
        try {
            MeloraSettings.themeMode.value = ThemeMode.Dark
            MeloraSettings.eInkMode.value = false
            store.import("lyrics.js", lyricSource("1"))
            showSourceMenu()
            compose.onNodeWithText("有效性测试").performClick()

            val foreground = statusColor("关闭")
            val button = compose.onNodeWithText("关闭").captureToImage().toPixelMap()
            val background = button[button.width / 4, button.height / 2]
            val contrast = ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
            assertTrue("关闭按钮文字对比度为 $contrast", contrast >= 4.5)
        } finally {
            MeloraSettings.themeMode.value = previousMode
            MeloraSettings.eInkMode.value = previousEInk
        }
    }

    @Test
    fun sourceActionIconsAndFillsFollowEInkMode() {
        val previousMode = MeloraSettings.themeMode.value
        val previousEInk = MeloraSettings.eInkMode.value
        try {
            MeloraSettings.themeMode.value = ThemeMode.Light
            MeloraSettings.eInkMode.value = false
            store.import("lyrics.js", lyricSource("1"))
            showSourceScreen()

            for (eInk in listOf(false, true)) {
                compose.runOnIdle { MeloraSettings.eInkMode.value = eInk }
                compose.waitUntil(5_000) { MeloraAppearance.isEInk == eInk }

                val list = compose.onNode(hasScrollAction())
                for (label in listOf("导入音源脚本", "导出全部音源（1 个）", "重新加载音源")) {
                    list.performScrollToNode(hasText(label))
                    assertActionIconColors(label, eInk)
                }

                list.performScrollToNode(hasText("导入音源脚本"))
                val importer = compose.onNodeWithText("导入音源脚本")
                compose.awaitStable(importer)
                importer.performClick()
                for (label in listOf("选择 JS 文件", "从链接导入", "恢复音源备份")) {
                    assertActionIconColors(label, eInk)
                }
                dismissSourceSheet()

                openSourceMenu()
                assertActionIconColors("有效性测试", eInk)
                assertActionIconColors("删除此源", eInk)
                dismissSourceSheet()
            }
        } finally {
            MeloraSettings.themeMode.value = previousMode
            MeloraSettings.eInkMode.value = previousEInk
        }
    }

    @Test
    fun linkedSourceChangesOnlyWhenTheCheckedUpdateIsApplied() {
        val installed = lyricSource("1")
        val remote = lyricSource("2")
        ScriptServer(installed).use { server ->
            val source = runBlocking { LxSourceImporter(store).importUrl(server.url).single() }
            server.body.set(remote)
            showSourceMenu()

            val check = compose.onNodeWithText("检查更新")
            compose.awaitStable(check)
            check.performClick()
            val apply = compose.onNodeWithText("应用更新")
            compose.awaitStable(apply)
            assertEquals(installed, store.code(source.id))
            assertEquals(2, server.requests.get())

            apply.performClick()
            compose.awaitStable(compose.onNodeWithText("音源已更新"))
            assertEquals(remote, store.code(source.id))
            assertEquals("2", store.list().single().version)
            assertEquals(2, server.requests.get())
        }
    }

    private fun showSourceMenu() {
        showSourceScreen()
        openSourceMenu()
    }

    private fun showSourceScreen() {
        val context = FixtureContext(compose.activity, files)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MeloraTheme { LxSourceScreen() }
            }
        }
        // 音源列表在 IO 线程加载，Compose 空闲时可能仍在显示首屏占位。
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已安装音源 · 1").fetchSemanticsNodes().size == 1
        }
    }

    private fun openSourceMenu() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("更多操作"))
        val more = compose.onNodeWithContentDescription("更多操作")
        compose.awaitStable(more)
        more.performClick()
        compose.awaitStable(compose.onNodeWithText("有效性测试"))
    }

    private fun dismissSourceSheet() {
        Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    private fun assertActionIconColors(label: String, eInk: Boolean) {
        val row = compose.onNode(hasClickAction() and hasText(label))
        compose.awaitStable(row)
        val pixels = row.captureToImage().toPixelMap()
        // 中央区域同时包含图标与底色，避开卡片圆角和标题文字。
        val left = with(compose.density) { 20.dp.roundToPx() }
        val right = with(compose.density) { 48.dp.roundToPx() }
        val halfHeight = with(compose.density) { 12.dp.roundToPx() }
        var hasChromaticPixel = false
        var hasDarkPixel = false
        for (x in left until right) {
            for (y in pixels.height / 2 - halfHeight until pixels.height / 2 + halfHeight) {
                val color = pixels[x, y]
                val brightest = maxOf(color.red, color.green, color.blue)
                val darkest = minOf(color.red, color.green, color.blue)
                if (brightest - darkest > 0.02f) hasChromaticPixel = true
                if (brightest < 0.4f) hasDarkPixel = true
            }
        }
        assertEquals("$label 图标及底色的彩色状态", !eInk, hasChromaticPixel)
        if (eInk) assertTrue("$label 的图标在墨水屏上应清晰可见", hasDarkPixel)
    }

    private fun statusColor(label: String): Color {
        val status = compose.onNodeWithText(label, substring = true, useUnmergedTree = true)
        compose.awaitStable(status)
        val layouts = mutableListOf<TextLayoutResult>()
        status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    private fun lyricSource(version: String) = """
        // @name 歌词测试源
        // @version $version
        lx.on('request', () => '');
        lx.send('inited', {sources:{wy:{actions:['lyric']}}});
    """.trimIndent()

    private class FixtureContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("${directory.name}.$name", mode)
    }

    private class ScriptServer(initialCode: String, holdResponse: Boolean = false) : Closeable {
        val body = AtomicReference(initialCode)
        val requests = AtomicInteger()
        private val responseGate = CountDownLatch(if (holdResponse) 1 else 0)
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/source.js"

        private val worker = thread(name = "lx-source-actions-fixture", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                try {
                    socket.use {
                        it.soTimeout = 3_000
                        val reader = it.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* 读取请求头。 */ }
                        requests.incrementAndGet()
                        if (!responseGate.await(8, TimeUnit.SECONDS)) return@use
                        val bytes = body.get().toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        it.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                        }
                    }
                } catch (_: IOException) {
                    // 测试断言失败时客户端可能已关闭请求。
                }
            }
        }

        fun releaseResponse() = responseGate.countDown()

        override fun close() {
            listener.close()
            releaseResponse()
            worker.join(3_000)
        }
    }
}
