package com.leyu.melora.playback.lx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quickjs.QuickJS
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsRuntimeInstrumentedTest {
    @Test
    fun evaluatesJavaScriptWithPackagedNativeRuntime() {
        QuickJS.createRuntimeWithEventQueue().use { runtime ->
            runtime.createContext().use { context ->
                assertEquals(42, context.executeIntegerScript("6 * 7", "native-smoke.js"))
            }
        }
    }

    @Test(timeout = 15_000)
    fun packagedMusicSdkLoadsAndResolvesSuccessiveCallsWithoutNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val code = context.assets.open("sdk/music-sdk.js").bufferedReader().use { it.readText() }
        LxScriptEngine(context).use { engine ->
            // 加载真正随 APK 分发的完整 bundle，覆盖依赖导出与新版 QuickJS 的集成。
            engine.load(code, "music-sdk.js")
            val cover = engine.sdkCall(
                "pic", "tx", JSONObject().put("song", JSONObject().put("albumMid", "album-fixture")),
                timeoutMs = 3_000,
            )
            assertEquals("pic", cover.getString("kind"))
            assertEquals("tx", cover.getString("source"))
            assertEquals(
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000album-fixture.jpg",
                cover.getString("url"),
            )
            val missing = engine.sdkCall(
                "pic", "tx", JSONObject().put("song", JSONObject()), timeoutMs = 3_000,
            )
            assertEquals("pic", missing.getString("kind"))
            assertTrue(missing.isNull("url"))
        }
    }
}
