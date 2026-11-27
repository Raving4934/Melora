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
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import java.io.File
import java.nio.file.Files
import org.junit.After
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
}
