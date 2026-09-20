package com.leyu.melora.playback.lx

import android.content.Context
import com.quickjs.JSContext
import com.quickjs.JavaCallback
import com.quickjs.JSObject
import com.quickjs.QuickJS
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// 每个实例对应一个音源脚本的沙箱上下文；所有调用串行执行，Promise 由原生任务泵驱动。
class LxScriptEngine(private val context: Context) : Closeable {
    private val quickJs: QuickJS = QuickJS.createRuntimeWithEventQueue()
    private val jsContext: JSContext = quickJs.createContext()
    private val runtimePtr: Long
    private val contextPtr: Long
    private val host = LxHost()

    init {
        val shim = context.assets.open("lx-shim.js").use { it.readBytes().decodeToString() }
        val native = JSObject(jsContext)
        native.registerJavaMethod(JavaCallback { _, args -> host.http(args.getString(0)) }, "http")
        native.registerJavaMethod(JavaCallback { _, args -> host.hash(args.getString(0), args.getString(1)) }, "hash")
        native.registerJavaMethod(JavaCallback { _, args -> host.zlibInflate(args.getString(0)) }, "zlibInflate")
        native.registerJavaMethod(JavaCallback { _, args -> host.zlibDeflate(args.getString(0)) }, "zlibDeflate")
        native.registerJavaMethod(JavaCallback { _, args -> host.randomBase64(args.getInteger(0)) }, "randomBase64")
        native.registerJavaMethod(JavaCallback { _, args ->
            host.aes(args.getString(0), args.getString(1), args.getString(2), args.getString(3), args.getString(4))
        }, "aes")
        native.registerJavaMethod(JavaCallback { _, args -> host.log(args.getString(0)) }, "log")
        native.registerJavaMethod(JavaCallback { _, args ->
            val padding = if (args.length() > 2) args.getString(2) else "RSA/ECB/NoPadding"
            host.rsaEncrypt(args.getString(0), args.getString(1), padding)
        }, "rsaEncrypt")
        jsContext.set("__lxNative", native)
        jsContext.executeVoidScript(shim, "lx-shim.js")
        runtimePtr = readLong(quickJs, "runtimePtr")
        contextPtr = readLong(jsContext, "contextPtr")
    }

    fun load(code: String, fileName: String) {
        // 部分脚本在顶层读取 lx.currentScriptInfo.version，先注入脚本真实元信息再执行
        jsContext.executeVoidScript("globalThis.lx.currentScriptInfo = ${JSONObject(parseLxScriptMetadata(code))}", null)
        jsContext.executeVoidScript(code, fileName)
    }

    fun inited(): JSONObject? {
        val raw = jsContext.executeStringScript("JSON.stringify(globalThis.__lxInited || null)", null)
        return raw?.takeIf { it.isNotEmpty() && it != "null" }?.let { JSONObject(it) }
    }

    /** 初始化与媒体请求共用同一取消边界，不在胜出源返回后等待慢源的定时器或 HTTP。 */
    internal suspend fun <T> withCancellation(block: suspend (LxRequestToken) -> T): T = coroutineScope {
        val token = host.newRequestToken()
        val task = async(Dispatchers.IO) { block(token) }
        try {
            task.await()
        } finally {
            // 只取消宿主 Call；由执行线程退出 JNI，再由 coroutineScope 等待释放运行时。
            host.cancelRequest(token)
        }
    }

    suspend fun initialize(code: String, fileName: String, timeoutMs: Long = 3_000): JSONObject? =
        withCancellation { token ->
            host.withinRequestTimeout(token, timeoutMs) {
                ensureActive(token)
                val started = System.nanoTime()
                load(code, fileName)
                val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000
                pumpUntil("globalThis.__lxInited && JSON.stringify(globalThis.__lxInited)", remaining, token)
                    ?.let(::JSONObject)
            }
        }

    // 解析一次 musicUrl/lyric/pic 请求；同步等待 Promise 链完成。
    // 非池化调用保留原 API，但仍走同一条可取消请求链路。
    fun request(source: String, action: String, info: JSONObject, timeoutMs: Long = 20_000): Any =
        request(host.newRequestToken(), source, action, info, timeoutMs)

    internal fun request(
        token: LxRequestToken,
        source: String,
        action: String,
        info: JSONObject,
        timeoutMs: Long = 20_000,
    ): Any {
        val payload = JSONObject()
            .put("source", source)
            .put("action", action)
            .put("info", info)
            .toString()
        return host.withinRequestTimeout(token, timeoutMs) {
            ensureActive(token)
            jsContext.executeVoidScript("globalThis.__lxInvoke(${JSONObject.quote(payload)})", null)
            ensureActive(token)
            val raw = pumpUntil("globalThis.__lxTakeResult()", timeoutMs, token)
                ?: throw IllegalStateException("脚本解析超时")
            ensureActive(token)
            val parsed = JSONObject(raw)
            if (!parsed.optBoolean("ok", false)) {
                throw IllegalStateException(parsed.optString("error", "脚本执行失败"))
            }
            parsed.opt("data") ?: ""
        }
    }

    // 调用内置 musicSdk 协议：__meloraInvoke / __meloraTake。
    fun sdkCall(action: String, source: String, params: JSONObject, timeoutMs: Long = 25_000): JSONObject {
        val token = host.newRequestToken()
        val payload = JSONObject()
            .put("action", action)
            .put("source", source)
            .put("params", params)
            .toString()
        return host.withinRequestTimeout(token, timeoutMs) {
            ensureActive(token)
            jsContext.executeVoidScript("globalThis.__meloraInvoke(${JSONObject.quote(payload)})", null)
            ensureActive(token)
            val raw = pumpUntil("globalThis.__meloraTake()", timeoutMs, token)
                ?: throw IllegalStateException("目录请求超时")
            ensureActive(token)
            val parsed = JSONObject(raw)
            if (!parsed.optBoolean("ok", false)) {
                throw IllegalStateException(parsed.optString("error", "目录请求失败"))
            }
            parsed.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun ensureActive(token: LxRequestToken) {
        if (token.cancelled.get()) throw CancellationException("脚本请求已取消")
    }

    // 驱动 Promise 微任务与 JS 定时器，直到表达式返回非空结果。
    private fun pumpUntil(expression: String, timeoutMs: Long, token: LxRequestToken): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ensureActive(token)
            QuickJsPending.executePending(quickJs, runtimePtr, contextPtr)
            runCatching { jsContext.executeVoidScript("globalThis.__lxTick && globalThis.__lxTick()", null) }
            ensureActive(token)
            val raw = jsContext.executeStringScript(expression, null)
            if (!raw.isNullOrEmpty()) return raw
            Thread.sleep(4)
        }
        ensureActive(token)
        return null
    }

    override fun close() {
        runCatching { jsContext.close() }
        runCatching { quickJs.close() }
    }

    private fun readLong(target: Any, field: String): Long =
        target.javaClass.getDeclaredField(field).apply { isAccessible = true }.getLong(target)
}
