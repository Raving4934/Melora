package com.leyu.melora.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.lx.LxSourceImporter
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
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

    private lateinit var files: File
    private lateinit var fixtureContext: Context
    private lateinit var store: LxScriptStore

    @Before
    fun prepareSourceStorage() {
        val activity = compose.activity
        files = Files.createTempDirectory(activity.cacheDir.toPath(), "lx-source-actions-").toFile()
        fixtureContext = object : ContextWrapper(activity) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${files.name}.$name", mode)
        }
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
        compose.setContent {
            CompositionLocalProvider(LocalContext provides fixtureContext) {
                MeloraTheme { LxSourceScreen() }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("更多操作"))
        val more = compose.onNodeWithContentDescription("更多操作")
        compose.awaitStable(more)
        more.performClick()
        compose.awaitStable(compose.onNodeWithText("有效性测试"))
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

    private class ScriptServer(initialCode: String) : Closeable {
        val body = AtomicReference(initialCode)
        val requests = AtomicInteger()
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/source.js"

        private val worker = thread(name = "lx-source-actions-fixture", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                socket.use {
                    it.soTimeout = 3_000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* 读取请求头。 */ }
                    requests.incrementAndGet()
                    val bytes = body.get().toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            worker.join(3_000)
        }
    }
}
