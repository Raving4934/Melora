package com.leyu.melora.playback.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** file/content 读读并发、写独占；租约不绑定线程，支持 DataSource 跨线程 close。 */
internal object LocalMediaIoCoordinator {
    private const val WAIT_SLICE_MS = 25L
    private class Entry(var readers: Int = 0, var writer: Boolean = false, var refs: Int = 0)
    internal interface Lease { fun close() }
    private object NoOpLease : Lease { override fun close() = Unit }
    private val state = ReentrantLock()
    private val changed = state.newCondition()
    private val entries = mutableMapOf<String, Entry>()

    internal fun <T> withRead(context: Context, uri: Uri, block: () -> T): T {
        val lease = acquireRead(context, uri)
        return try { block() } finally { lease.close() }
    }

    /** 等待写入可取消且不占 IO 线程；真实写盘固定在 IO dispatcher。 */
    internal suspend fun <T> withExclusive(context: Context, uri: Uri, block: () -> T): T =
        withContext(Dispatchers.IO) {
            val key = resourceKey(context, uri) ?: return@withContext block()
            val entry = retain(key)
            var acquired = false
            try {
                while (!acquireWriter(entry)) {
                    currentCoroutineContext().ensureActive()
                    delay(WAIT_SLICE_MS)
                }
                acquired = true
                try { currentCoroutineContext().ensureActive(); block() } finally {
                    state.withLock { entry.writer = false; changed.signalAll() }
                    release(key, entry)
                }
            } catch (error: Throwable) {
                if (!acquired) release(key, entry)
                throw error
            }
        }

    /** 同步 DataSource.open 只在 Media3 加载线程等待，closeRequested 可中止等待。 */
    internal fun acquireRead(
        context: Context,
        uri: Uri,
        closeRequested: () -> Boolean = { false },
    ): Lease {
        val key = resourceKey(context, uri) ?: return NoOpLease
        val entry = retain(key)
        try {
            state.withLock {
                while (entry.writer) {
                    if (closeRequested()) throw InterruptedIOException("本地媒体读取已取消")
                    try { changed.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS) } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("等待本地媒体读取协调被中断").also { it.initCause(interrupted) }
                    }
                }
                entry.readers++
            }
            return ReadLease(key, entry)
        } catch (error: Throwable) {
            release(key, entry)
            throw error
        }
    }

    internal fun wrap(dataSource: DataSource, context: Context): DataSource =
        CoordinatedDataSource(dataSource, context)

    internal fun entryCount(): Int = state.withLock { entries.size }

    private fun acquireWriter(entry: Entry): Boolean = state.withLock {
        if (entry.writer || entry.readers != 0) return@withLock false
        entry.writer = true
        true
    }

    private class ReadLease(private val key: String, private val entry: Entry) : Lease {
        private var closed = false
        override fun close() {
            state.withLock {
                if (closed) return
                closed = true
                entry.readers--
                changed.signalAll()
            }
            release(key, entry)
        }
    }

    private fun retain(key: String): Entry = state.withLock {
        entries.getOrPut(key) { Entry() }.also { it.refs++ }
    }

    private fun release(key: String, entry: Entry) {
        state.withLock {
            entry.refs--
            if (entry.refs == 0 && entry.readers == 0 && !entry.writer) entries.remove(key)
        }
    }

    private fun resourceKey(context: Context, uri: Uri): String? = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> uri.path?.let(::fileKey)
        "content" -> mediaPath(context, uri)?.let(::fileKey)
            ?: documentKey(context, uri)
            ?: "content:${uri.normalizeScheme()}"
        else -> null
    }

    private fun fileKey(path: String): String {
        val normalized = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        return "file:$normalized"
    }

    private fun mediaPath(context: Context, uri: Uri): String? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex("_data")
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun documentKey(context: Context, uri: Uri): String? {
        val id = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) -> "document:${DocumentsContract.getDocumentId(uri)}"
                DocumentsContract.isTreeUri(uri) -> "tree:${DocumentsContract.getTreeDocumentId(uri)}"
                else -> null
            }
        }.getOrNull() ?: return null
        return "content:${uri.authority}:$id"
    }
}

/** 只协调可写本地媒体；网络URI完全透传，不把文件写入锁带进音源链路。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class CoordinatedDataSource(
    private val upstream: DataSource,
    private val context: Context,
) : DataSource {
    private class Session {
        val cancel = AtomicBoolean(false)
        var lease: LocalMediaIoCoordinator.Lease? = null
        var closed = false
    }

    private val lifecycle = Any()
    private var active: Session? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme !in setOf("file", "content")) return upstream.open(dataSpec)
        val session = Session()
        synchronized(lifecycle) {
            check(active == null) { "DataSource 已打开" }
            active = session
        }
        try {
            val lease = LocalMediaIoCoordinator.acquireRead(context, dataSpec.uri, session.cancel::get)
            return synchronized(session) {
                if (session.closed) {
                    lease.close()
                    throw InterruptedIOException("本地媒体打开已取消")
                }
                session.lease = lease
                if (session.cancel.get()) throw InterruptedIOException("本地媒体打开已取消")
                val length = upstream.open(dataSpec)
                if (session.cancel.get()) throw InterruptedIOException("本地媒体打开已取消")
                length
            }
        } catch (error: Throwable) {
            runCatching { closeSession(session) }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        val session = synchronized(lifecycle) { active }
        if (session == null) upstream.close() else {
            session.cancel.set(true)
            closeSession(session)
        }
    }

    /** 同一次open/close按session串行；上游关闭真正返回后才释放租约，重复close不触碰下一次open。 */
    private fun closeSession(session: Session) = synchronized(session) {
        if (session.closed) return@synchronized
        session.closed = true
        try {
            upstream.close()
        } finally {
            session.lease?.close()
            session.lease = null
            synchronized(lifecycle) { if (active === session) active = null }
        }
    }
}
