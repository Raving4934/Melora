package com.leyu.melora.playback.lx

import android.content.Context
import com.quickjs.JSContext
import com.quickjs.JavaCallback
import com.quickjs.JSObject
import com.quickjs.QuickJS
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class LxScriptTimeoutException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

// 每个实例对应一个音源脚本的沙箱上下文；所有调用串行执行，Promise 由原生任务泵驱动。
class LxScriptEngine(private val context: Context) : Closeable {
    private val quickJs: QuickJS
    private val jsContext: JSContext
    private val runtimePtr: Long
    private val contextPtr: Long
    private val host = LxHost()

    init {
        val runtime = QuickJS.createRuntimeWithEventQueue()
        try {
            quickJs = runtime
            jsContext = runtime.createContext()
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
        } catch (failure: Throwable) {
            try {
                runtime.close()
            } catch (cleanupError: Throwable) {
                if (cleanupError !== failure) failure.addSuppressed(cleanupError)
            }
            throw failure
        }
    }

    fun load(code: String, fileName: String, timeoutMs: Long = 3_000) {
        executeWithBudget(host.newRequestToken(), timeoutMs, "脚本加载超时") {
            evaluateScript(code, fileName)
        }
    }

    private fun evaluateScript(code: String, fileName: String) {
        // 部分脚本在顶层读取 lx.currentScriptInfo.version，先注入脚本真实元信息再执行。
        jsContext.executeVoidScript("globalThis.lx.currentScriptInfo = ${JSONObject(parseLxScriptMetadata(code))}", null)
        jsContext.executeVoidScript(code, fileName)
    }

    fun inited(timeoutMs: Long = 3_000): JSONObject? =
        executeWithBudget(host.newRequestToken(), timeoutMs, "脚本状态读取超时") {
            val raw = jsContext.executeStringScript("JSON.stringify(globalThis.__lxInited || null)", null)
            raw?.takeIf { it.isNotEmpty() && it != "null" }?.let { JSONObject(it) }
        }

    /** 初始化与媒体请求共用同一取消边界，不在胜出源返回后等待慢源的定时器或 HTTP。 */
    internal suspend fun <T> withCancellation(block: suspend (LxRequestToken) -> T): T = coroutineScope {
        val token = host.newRequestToken()
        val task = async(Dispatchers.IO) { block(token) }
        try {
            task.await()
        } finally {
            // 取消只写原子中断标志；实际JS执行与runtime关闭仍由QuickJS线程串行处理。
            host.cancelRequest(token)
            token.runtimeTicket.get().takeIf { it != 0L }?.let(quickJs::interruptExecution)
        }
    }

    suspend fun initialize(code: String, fileName: String, timeoutMs: Long = 3_000): JSONObject? =
        withCancellation { token ->
            try {
                executeWithBudget(token, timeoutMs, "脚本初始化超时") { deadlineNanos ->
                    initializeScript(code, fileName, deadlineNanos, token)
                }
            } catch (_: LxScriptTimeoutException) {
                null
            }
        }

    private fun initializeScript(
        code: String,
        fileName: String,
        deadlineNanos: Long,
        token: LxRequestToken,
    ): JSONObject {
        ensureActive(token)
        evaluateScript(code, fileName)
        return JSONObject(pumpUntil(
            "globalThis.__lxInited && JSON.stringify(globalThis.__lxInited)",
            deadlineNanos,
            token,
            "脚本初始化超时",
        ))
    }

    /** 手动“检查脚本”时执行并检查音源协议；初始化及协议检查共享一个总budget。 */
    internal suspend fun inspectSource(code: String, fileName: String, timeoutMs: Long = 8_000): JSONObject =
        withCancellation { token ->
            var initializationComplete = false
            try {
                executeWithBudget(token, timeoutMs, "脚本检查超时") { deadlineNanos ->
                    val initialized = initializeScript(code, fileName, deadlineNanos, token)
                    initializationComplete = true
                    check(initialized.opt("status") != false) { "脚本报告初始化失败" }
                    val sources = initialized.optJSONObject("sources") ?: error("脚本未声明音源")
                    val platforms = setOf("kw", "kg", "tx", "wy", "mg", "local")
                    val actions = setOf("musicUrl", "lyric", "pic")
                    check(sources.keys().asSequence().any { id ->
                        val declared = sources.optJSONObject(id)?.optJSONArray("actions")
                        id in platforms && declared != null && (0 until declared.length()).any { declared.opt(it) in actions }
                    }) { "脚本未声明有效的平台和请求动作" }
                    ensureActive(token)
                    check(jsContext.executeBooleanScript("globalThis.__lxHasRequestHandler()", null)) {
                        "脚本未注册 request 处理器"
                    }
                    sources
                }
            } catch (timeout: LxScriptTimeoutException) {
                if (!initializationComplete) {
                    throw IllegalStateException("脚本未按音源协议完成初始化", timeout)
                }
                throw timeout
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
    ): Any = executeWithBudget(token, timeoutMs, "脚本解析超时") { deadlineNanos ->
        val payload = JSONObject()
            .put("source", source)
            .put("action", action)
            .put("info", info)
            .toString()
        ensureActive(token)
        jsContext.executeVoidScript("globalThis.__lxInvoke(${JSONObject.quote(payload)})", null)
        ensureActive(token)
        val parsed = JSONObject(pumpUntil(
            "globalThis.__lxTakeResult()",
            deadlineNanos,
            token,
            "脚本解析超时",
        ))
        ensureActive(token)
        if (!parsed.optBoolean("ok", false)) {
            throw IllegalStateException(parsed.optString("error", "脚本执行失败"))
        }
        parsed.opt("data") ?: ""
    }

    // 调用内置 musicSdk 协议：__meloraInvoke / __meloraTake。
    suspend fun sdkCall(action: String, source: String, params: JSONObject, timeoutMs: Long = 25_000): JSONObject = withCancellation { token ->
        executeWithBudget(token, timeoutMs, "目录请求超时") { deadlineNanos ->
            val payload = JSONObject()
                .put("action", action)
                .put("source", source)
                .put("params", params)
                .toString()
            ensureActive(token)
            jsContext.executeVoidScript("globalThis.__meloraInvoke(${JSONObject.quote(payload)})", null)
            ensureActive(token)
            val parsed = JSONObject(pumpUntil(
                "globalThis.__meloraTake()",
                deadlineNanos,
                token,
                "目录请求超时",
            ))
            ensureActive(token)
            if (!parsed.optBoolean("ok", false)) {
                throw IllegalStateException(parsed.optString("error", "目录请求失败"))
            }
            parsed.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun <T> executeWithBudget(
        token: LxRequestToken,
        timeoutMs: Long,
        timeoutMessage: String,
        block: (Long) -> T,
    ): T {
        val deadlineNanos = monotonicDeadlineAfter(timeoutMs)
        val ticket = quickJs.beginInterruptibleExecution(deadlineNanos)
        token.runtimeTicket.set(ticket)
        try {
            if (token.cancelled.get()) quickJs.interruptExecution(ticket)
            return host.withinRequestDeadline(token, deadlineNanos) {
                try {
                    ensureActive(token)
                    val result = block(deadlineNanos)
                    ensureActive(token)
                    if (System.nanoTime() >= deadlineNanos) throw LxScriptTimeoutException(timeoutMessage)
                    result
                } catch (failure: Throwable) {
                    if (token.cancelled.get()) {
                        throw CancellationException("脚本请求已取消").also { it.initCause(failure) }
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        throw LxScriptTimeoutException(timeoutMessage, failure)
                    }
                    throw failure
                }
            }
        } finally {
            token.runtimeTicket.compareAndSet(ticket, 0)
            quickJs.endInterruptibleExecution(ticket)
        }
    }

    private fun ensureActive(token: LxRequestToken) {
        if (token.cancelled.get()) throw CancellationException("脚本请求已取消")
    }

    // 驱动Promise微任务与JS定时器；同步JS及整个泵共享同一个单调deadline。
    private fun pumpUntil(
        expression: String,
        deadlineNanos: Long,
        token: LxRequestToken,
        timeoutMessage: String,
    ): String {
        while (true) {
            ensureActive(token)
            if (System.nanoTime() >= deadlineNanos) throw LxScriptTimeoutException(timeoutMessage)
            QuickJsPending.executePending(quickJs, runtimePtr, contextPtr)
            ensureActive(token)
            if (System.nanoTime() >= deadlineNanos) throw LxScriptTimeoutException(timeoutMessage)
            runCatching { jsContext.executeVoidScript("globalThis.__lxTick && globalThis.__lxTick()", null) }
            ensureActive(token)
            if (System.nanoTime() >= deadlineNanos) throw LxScriptTimeoutException(timeoutMessage)
            val raw = jsContext.executeStringScript(expression, null)
            ensureActive(token)
            if (!raw.isNullOrEmpty()) return raw
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw LxScriptTimeoutException(timeoutMessage)
            Thread.sleep(minOf(4L, TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)))
        }
    }

    override fun close() {
        runCatching { jsContext.close() }
        runCatching { quickJs.close() }
    }

    private fun readLong(target: Any, field: String): Long =
        target.javaClass.getDeclaredField(field).apply { isAccessible = true }.getLong(target)
}
