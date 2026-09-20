package com.leyu.melora.playback

import android.content.Context
import android.os.Build
import com.leyu.melora.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集器：
 * 捕获未捕获异常并落盘到应用私有目录 files/crash_log.txt，供关于页面查看与导出。
 */
object CrashLogger {
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_LOG_BYTES = 512 * 1024L // 最多保留 512KB
    private val lock = Any()
    private lateinit var logFile: File
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        synchronized(lock) {
            logFile = File(context.applicationContext.filesDir, FILE_NAME)
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (installedHandler != null && currentHandler === installedHandler) return

            val previousHandler = currentHandler
            val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
                recordCrash(thread, throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
            installedHandler = handler
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val entry = runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            buildString {
                append("================ 崩溃记录 ================\n")
                append("发生时间: $time\n")
                append("应用版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                append("设备型号: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
                append("触发线程: ${thread.name}\n")
                append("异常类型: ${throwable.javaClass.name}\n")
                append("异常信息: ${throwable.message ?: "无详细描述"}\n")
                append("异常堆栈:\n$sw\n\n")
            }
        }.getOrNull() ?: return

        synchronized(lock) {
            if (!::logFile.isInitialized) return
            runCatching {
                val existing = (readTailBytes(logFile) ?: ByteArray(0))
                val entryBytes = entry.toByteArray(StandardCharsets.UTF_8)
                val maxBytes = MAX_LOG_BYTES.toInt()
                val retained = if (entryBytes.size >= maxBytes) {
                    entryBytes.copyOfRange(entryBytes.size - maxBytes, entryBytes.size)
                } else {
                    val existingBytes = minOf(existing.size, maxBytes - entryBytes.size)
                    val existingStart = existing.size - existingBytes
                    ByteArray(existingBytes + entryBytes.size).also { output ->
                        existing.copyInto(output, destinationOffset = 0, startIndex = existingStart)
                        entryBytes.copyInto(output, destinationOffset = existingBytes)
                    }
                }
                logFile.writeBytes(retained)
            }
        }
    }

    fun readLog(): String? = synchronized(lock) {
        runCatching {
            if (!::logFile.isInitialized) return@runCatching null
            readTailBytes(logFile)?.let { String(it, StandardCharsets.UTF_8) }
        }.getOrNull()
    }

    /** 只读取日志尾部，兼容历史上可能已经超过上限的文件。 */
    private fun readTailBytes(file: File): ByteArray? {
        if (!file.exists()) return null
        val length = file.length()
        if (length <= 0L) return null
        val start = (length - MAX_LOG_BYTES).coerceAtLeast(0L)
        val size = (length - start).toInt()
        return RandomAccessFile(file, "r").use { randomAccessFile ->
            randomAccessFile.seek(start)
            ByteArray(size).also(randomAccessFile::readFully)
        }
    }

    fun clearLog(): Boolean = synchronized(lock) {
        runCatching {
            if (::logFile.isInitialized && logFile.exists()) logFile.delete() else true
        }.getOrDefault(false)
    }

    fun getDeviceInfo(): String = buildString {
        appendLine("应用版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("设备厂商: ${Build.MANUFACTURER}")
        appendLine("设备型号: ${Build.MODEL}")
        appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("处理器架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
    }
}
