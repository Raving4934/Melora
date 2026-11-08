@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.leyu.melora.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** 每个 open 独立归属原始曲目；预读下一项不会覆盖本次读取的来源与计数。 */
internal class PlaybackDataDiagnostics(
    private val upstream: DataSource,
    private val report: (String) -> Unit = { Log.i("PlaybackData", it) },
) : DataSource by upstream {
    private class Attempt(val id: Long, val spec: DataSpec) {
        val startedMs = SystemClock.elapsedRealtime()
        val uid = spec.uri.takeIf { it.scheme == "melora" }?.lastPathSegment
        var resolution: TrackRegistry.Resolution? = null
        var key: String? = null
        var uriId = "unknown"
        var openedLength = C.LENGTH_UNSET.toLong()
        var declaredLength: Long? = null
        var resourceLength: Long? = null
        var readBytes = 0L
        var networkBytes = 0L
        var cacheBytes = 0L
        var localBytes = 0L
        var eof = false
        val sources = linkedSetOf<String>()
    }

    private var attempt: Attempt? = null

    /** 在解析结果提交后、真正打开文件/网络前调用，不能等阻塞的 open 返回后再读全局注册表。 */
    fun resolved(spec: DataSpec) {
        val current = attempt ?: return
        current.resolution = current.uid?.let(TrackRegistry::resolved)
        current.key = spec.key
        current.uriId = playbackDiagnosticId(spec.uri.toString())
    }

    init {
        upstream.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                if (dataSpec.key != null) attempt?.key = dataSpec.key
            }

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                attempt?.sources?.add(transferSource(dataSpec, isNetwork))
            }

            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                val current = attempt ?: return
                when (transferSource(dataSpec, isNetwork)) {
                    "network" -> current.networkBytes += bytesTransferred
                    "cache" -> current.cacheBytes += bytesTransferred
                    else -> current.localBytes += bytesTransferred
                }
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        })
    }

    override fun open(dataSpec: DataSpec): Long {
        val current = Attempt(ids.incrementAndGet(), dataSpec)
        attempt = current
        return try {
            upstream.open(dataSpec).also { length ->
                current.openedLength = length
                captureResponse(current)
                log("open", current)
            }
        } catch (error: Exception) {
            captureResponse(current)
            log("open_error", current, error)
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        upstream.read(buffer, offset, length).also { count ->
            val current = attempt
            if (current != null) {
                if (count > 0) current.readBytes += count
                if (count == C.RESULT_END_OF_INPUT && !current.eof) {
                    current.eof = true
                    log("eof", current)
                }
            }
        }
    } catch (error: Exception) {
        attempt?.let { log("read_error", it, error) }
        throw error
    }

    override fun close() {
        val current = attempt
        try {
            upstream.close()
            if (current != null) log("close", current)
        } catch (error: Exception) {
            if (current != null) log("close_error", current, error)
            throw error
        } finally {
            attempt = null
        }
    }

    private fun captureResponse(current: Attempt) {
        upstream.uri?.toString()?.let { current.uriId = playbackDiagnosticId(it) }
        fun headerLength(name: String): Long? = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
            ?.substringAfterLast('/')?.toLongOrNull()
        current.declaredLength = headerLength("Content-Length")
        current.resourceLength = headerLength("Content-Range")
    }

    private fun log(event: String, current: Attempt, error: Exception? = null) {
        val resolution = current.resolution
        val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val resolver = resolution?.resourceId?.takeIf { it.startsWith("lx:") }
            ?.removePrefix("lx:")?.substringBeforeLast(':')
        report("event=$event attempt=${current.id} uid=${playbackDiagnosticToken(current.uid)} " +
            "resource=${playbackDiagnosticId(resolution?.resourceId)} key=${playbackDiagnosticId(current.key)} " +
            "uri=${current.uriId} resolver=${playbackDiagnosticToken(resolver)} platform=${playbackDiagnosticToken(resolution?.platform)} " +
            "requested=${playbackDiagnosticToken(resolution?.requestedQuality)} quality=${playbackDiagnosticToken(resolution?.quality)} " +
            "completeCache=${resolution?.fromCompleteCache == true} source=${current.sources.joinToString("+").ifEmpty { "unknown" }} " +
            "position=${current.spec.position} requestedLength=${current.spec.length} openedLength=${current.openedLength} " +
            "contentLength=${current.declaredLength} resourceLength=${current.resourceLength} " +
            "readBytes=${current.readBytes} networkBytes=${current.networkBytes} cacheBytes=${current.cacheBytes} localBytes=${current.localBytes} " +
            "eof=${current.eof} elapsedMs=${SystemClock.elapsedRealtime() - current.startedMs} " +
            "error=${error?.javaClass?.simpleName ?: "none"} httpStatus=$status")
    }

    private fun transferSource(spec: DataSpec, network: Boolean): String = when {
        network -> "network"
        spec.uri.scheme == "file" && spec.key?.startsWith("melora://") == true -> "cache"
        else -> "local"
    }

    private companion object {
        val ids = AtomicLong()
    }
}

/** 不记录直链、查询参数或本地路径；用于跨读取/输出日志关联同一物理资源。 */
internal fun playbackDiagnosticId(value: String?): String = value?.let {
    MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
} ?: "unknown"

private fun playbackDiagnosticToken(value: String?): String = value
    ?.takeIf { it.length in 1..96 && it.all { char -> char.isLetterOrDigit() || char in "_-." } }
    ?: playbackDiagnosticId(value)
