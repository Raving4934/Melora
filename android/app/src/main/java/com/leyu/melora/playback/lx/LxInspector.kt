package com.leyu.melora.playback.lx

import android.content.Context
import org.json.JSONObject

// 脚本自检：加载 → 读取声明音源 → 可选解析一条演示请求。
object LxInspector {
    data class Result(val status: String, val url: String? = null, val script: LxScript? = null)

    fun inspect(context: Context, store: LxScriptStore, script: LxScript, resolveDemo: Boolean): Result {
        return runCatching {
            LxScriptEngine(context).use { engine ->
                engine.load(store.code(script.id) ?: error("脚本文件不存在"), script.id)
                val inited = engine.inited()
                val sources = inited?.optJSONObject("sources")
                val names = sources?.keys()?.asSequence()?.toList().orEmpty()
                if (names.isEmpty()) error("脚本未声明音源")
                if (!resolveDemo) {
                    return@use Result("已加载「${script.name}」\n声明音源：${names.joinToString("、")}", null, script)
                }
                val url = engine.request(
                    names.first(),
                    "musicUrl",
                    JSONObject().put("musicId", "tone_b").put("quality", "128k"),
                ).toString()
                Result("音源：${names.joinToString("、")}\n解析成功（${names.first()}）：$url", url, script)
            }
        }.getOrElse {
            Result("「${script.name}」检查失败：${it.message ?: it.javaClass.simpleName}", null, script)
        }
    }
}
