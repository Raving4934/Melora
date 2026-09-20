package com.leyu.melora.playback.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.lx.LxScriptEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
}
