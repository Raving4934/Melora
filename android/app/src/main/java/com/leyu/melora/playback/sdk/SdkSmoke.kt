package com.leyu.melora.playback.sdk

import android.content.Context
import android.util.Log
import com.quickjs.QuickJS
import org.json.JSONObject

// 开发自检：adb shell am start -n com.leyu.melora/.MainActivity --es sdkSmoke kw
object SdkSmoke {
    private const val TAG = "SdkSmoke"

    fun run(context: Context, source: String) {
        Thread {
            if (source == "quickjs") {
                quickJsRuntime()
                return@Thread
            }
            if (source == "diag") {
                diagMusicUrl(context)
                return@Thread
            }
            val target = source.ifBlank { "kw" }
            step(context, target, "search", JSONObject().put("text", "周杰伦").put("page", 1).put("limit", 5))
            step(context, target, "boards", JSONObject())
            step(context, target, "hotSearch", JSONObject())
            step(context, target, "playlistTags", JSONObject())
            step(context, target, "playlists", JSONObject().put("sortId", "hot").put("page", 1))
            step(context, target, "boardSongs", JSONObject().put("bangId", "93").put("page", 1))
            stepLyric(context, target)
        }.start()
    }

    private fun quickJsRuntime() {
        runCatching {
            QuickJS.createRuntimeWithEventQueue().use { runtime ->
                runtime.createContext().use { jsContext ->
                    check(jsContext.executeIntegerScript("6 * 7", "native-smoke.js") == 42)
                }
            }
        }.onSuccess {
            Log.i(TAG, "[quickjs] native runtime ok")
        }.onFailure { error ->
            Log.e(TAG, "[quickjs] native runtime failed: ${error.message}", error)
        }
    }

    // 逐脚本探测 kw musicUrl：章节 rid 与普通歌曲各一发，记录每个脚本的成功/失败/耗时
    private fun diagMusicUrl(context: Context) {
        val chapter = JSONObject()
            .put("source", "kw").put("songmid", "4681185").put("rid", "4681185")
            .put("musicId", "4681185").put("MUSICRID", "MUSIC_4681185").put("musicrid", "MUSIC_4681185")
            .put("name", "别乱照镜子").put("singer", "小北[主播]").put("duration", 1479).put("interval", "24:39")
        val song = try {
            kotlinx.coroutines.runBlocking {
                MusicSdkEngine.call(context, "search", "kw", JSONObject().put("text", "晴天 周杰伦").put("page", 1).put("limit", 1))
            }.optJSONArray("list")?.optJSONObject(0)
        } catch (error: Throwable) {
            Log.e(TAG, "[diag] kw search failed: ${error.message}")
            null
        }
        Log.i(TAG, "[diag] song sample=${song?.toString()?.take(260)}")
        try {
            val scripts = kotlinx.coroutines.runBlocking { LxScriptPool.scriptsFor(context, "kw", "musicUrl", LxScriptPool.ScriptScope.ALL) }
            Log.i(TAG, "[diag] enabled=${scripts.filter { it.first.enabled }.joinToString { it.first.id }}")
            scripts.forEach { (script, support) -> Log.i(TAG, "[diag] ${script.id} enabled=${script.enabled} qualitys=${support.qualitys}") }
        } catch (_: Throwable) {
        }
        // 走 App 真实解析链路（SourceResolver + 并发竞速）
        try {
            val song = OnlineSong.from(chapter)!!
            val started = System.currentTimeMillis()
            val resolved = kotlinx.coroutines.runBlocking {
                SourceResolver.resolve(context, song, "128k", allowSwitch = true)
            }
            Log.i(TAG, "[diag/apppath] ok ${System.currentTimeMillis() - started}ms q=${resolved.quality} switched=${resolved.switched} url=${resolved.url.take(90)}")
        } catch (error: Throwable) {
            Log.w(TAG, "[diag/apppath] failed: ${error.message}")
        }
        // 发现页听书专区数据源（KwBookApi homeSections）解析验证
        try {
            val sections = kotlinx.coroutines.runBlocking { KwBookApi.homeSections() }
            Log.i(TAG, "[diag/books] sections=${sections.size} ${sections.joinToString { "${it.title}(${it.items.size})" }}")
            sections.firstOrNull()?.items?.take(3)?.forEach { item ->
                Log.i(TAG, "[diag/books] id=${item.id} name=${item.name} author=${item.author} total=${item.total}")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "[diag/books] failed: ${error.message}")
        }
        // 歌词链路计时：脚本支持情况 + 内置 + 完整链路
        try {
            val scripts = kotlinx.coroutines.runBlocking {
                LxScriptPool.scriptsFor(context, "kw", "lyric", LxScriptPool.ScriptScope.ALL)
            }
            Log.i(TAG, "[diag/lyric] scripts=${scripts.size} ${scripts.joinToString { it.first.id }}")
            val chapterSong = OnlineSong.from(chapter)!!
            val builtinStart = System.currentTimeMillis()
            val builtin = kotlinx.coroutines.runBlocking { OnlineRepository.lyric(context, "kw", chapterSong) }
            Log.i(TAG, "[diag/lyric] builtin ${System.currentTimeMillis() - builtinStart}ms len=${builtin.lyric.length} tlen=${builtin.tlyric.length}")
            val fullStart = System.currentTimeMillis()
            val full = kotlinx.coroutines.runBlocking { SourceResolver.lyric(context, chapterSong) }
            Log.i(TAG, "[diag/lyric] resolver ${System.currentTimeMillis() - fullStart}ms len=${full?.lyric?.length ?: -1} tlen=${full?.tlyric?.length ?: -1}")
        } catch (error: Throwable) {
            Log.w(TAG, "[diag/lyric] failed: ${error.message}")
        }
        probe(context, "chapter", chapter)
        if (song != null) probe(context, "song", song)
        probeQuality(context, "chapter320", chapter, "320k")
        probeQuality(context, "chapterflac", chapter, "flac")
        probeQuality(context, "chapterflac24", chapter, "flac24bit")
    }

    // 拿解析结果后立刻用 OkHttp 拉首字节，验证 URL 是否可播（含明文 http 策略）
    private fun probeQuality(context: Context, label: String, musicInfo: JSONObject, quality: String) {
        val scripts = try {
            kotlinx.coroutines.runBlocking { LxScriptPool.scriptsFor(context, "kw", "musicUrl", LxScriptPool.ScriptScope.ALL) }
        } catch (error: Throwable) {
            return
        }
        val candidates = scripts.filter { quality in it.second.qualitys }.ifEmpty { scripts }
        candidates.forEach { (script, _) ->
            val info = JSONObject().put("type", quality).put("musicInfo", musicInfo)
            val started = System.currentTimeMillis()
            val result = try {
                kotlinx.coroutines.runBlocking { LxScriptPool.resolve(context, script.id, "kw", "musicUrl", info, 15_000) }
            } catch (error: Throwable) {
                Log.w(TAG, "[diag/$label] ${script.id} threw: ${error.message}")
                null
            }
            val cost = System.currentTimeMillis() - started
            val url = when (result) {
                is String -> result
                is JSONObject -> result.optString("url")
                else -> null
            }
            if (url.isNullOrBlank() || !url.startsWith("http")) {
                Log.w(TAG, "[diag/$label] ${script.id} no url ${cost}ms raw=${result?.toString()?.take(100)}")
            } else {
                Log.i(TAG, "[diag/$label] ${script.id} ok ${cost}ms url=${url.take(90)}")
                checkUrl("$label-${script.id}", url)
            }
        }
    }

    private fun checkUrl(label: String, url: String) {
        try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-2047").build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body.bytes().size
                Log.i(TAG, "[diag/$label] fetch code=${response.code} bytes=$bytes ct=${response.header("Content-Type")}")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "[diag/$label] fetch failed: ${error.message}")
        }
    }

    private fun probe(context: Context, label: String, musicInfo: JSONObject) {
        val scripts = try {
            kotlinx.coroutines.runBlocking { LxScriptPool.scriptsFor(context, "kw", "musicUrl", LxScriptPool.ScriptScope.ALL) }
        } catch (error: Throwable) {
            Log.e(TAG, "[diag/$label] scriptsFor failed: ${error.message}")
            return
        }
        Log.i(TAG, "[diag/$label] scripts=${scripts.size} ${scripts.joinToString { it.first.id }}")
        scripts.forEach { (script, support) ->
            val quality = support.qualitys.firstOrNull { it == "128k" || it == "320k" } ?: "128k"
            val started = System.currentTimeMillis()
            val info = JSONObject().put("type", quality).put("musicInfo", musicInfo)
            val result = try {
                kotlinx.coroutines.runBlocking { LxScriptPool.resolve(context, script.id, "kw", "musicUrl", info, 20_000) }
            } catch (error: Throwable) {
                Log.w(TAG, "[diag/$label] ${script.id} threw: ${error.message}")
                null
            }
            val cost = System.currentTimeMillis() - started
            val url = when (result) {
                is String -> result
                is JSONObject -> result.optString("url")
                else -> null
            }
            if (url != null && url.startsWith("http")) {
                Log.i(TAG, "[diag/$label] ${script.id} ok ${cost}ms q=$quality url=${url.take(90)}")
                checkUrl("$label-${script.id}", url)
            } else {
                Log.w(TAG, "[diag/$label] ${script.id} fail ${cost}ms q=$quality raw=${result?.toString()?.take(160)}")
            }
        }
    }


    private fun stepLyric(context: Context, source: String) {
        try {
            val song = kotlinx.coroutines.runBlocking {
                MusicSdkEngine.call(context, "search", source, JSONObject().put("text", "晴天 周杰伦").put("page", 1).put("limit", 3))
            }.optJSONArray("list")?.optJSONObject(0)
            if (song == null) {
                Log.e(TAG, "[$source/lyric] 无法获取测试歌曲")
                return
            }
            val lyric = kotlinx.coroutines.runBlocking {
                MusicSdkEngine.call(context, "lyric", source, JSONObject().put("song", song))
            }
            val text = lyric.optString("lyric")
            Log.i(TAG, "[$source/lyric] len=${text.length} head=${text.take(60)}")
        } catch (error: Throwable) {
            Log.e(TAG, "[$source/lyric] failed: ${error.message}", error)
        }
    }

    private fun step(context: Context, source: String, action: String, params: JSONObject) {
        try {
            val result = kotlinx.coroutines.runBlocking {
                MusicSdkEngine.call(context, action, source, params)
            }
            val list = result.optJSONArray("list")
            val sample = list?.optJSONObject(0)?.toString()?.take(220)
            Log.i(TAG, "[$source/$action] ok total=${result.optInt("total", -1)} list=${list?.length() ?: 0} sample=$sample")
        } catch (error: Throwable) {
            Log.e(TAG, "[$source/$action] failed: ${error.message}", error)
        }
    }
}
