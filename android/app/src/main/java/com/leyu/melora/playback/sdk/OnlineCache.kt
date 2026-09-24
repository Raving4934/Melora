package com.leyu.melora.playback.sdk

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 有界进程缓存；过期数据只作即时展示/播放快照，刷新结果供下次读取。 */
internal const val SNAPSHOT_DIRECTORY = "online-cache-v1"

object OnlineCache {
    const val CATALOG_TTL_MS = 15 * 60 * 1000L
    internal const val MAX_ENTRIES = 256

    private data class Entry(val value: Any, val time: Long)
    private data class DiskFile(
        val file: File,
        val metadata: OnlineCacheSnapshotMetadata,
        val size: Long,
    )

    private val lock = Any()
    private val store = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = SingleFlight<String, Any>(scope, lock)

    /** 只覆盖本切片的两个页面族，不为其它 namespace 提供持久化通道。 */
    private var boardSnapshotGeneration = 0L
    private var playlistSnapshotGeneration = 0L
    private val diskMutex = Mutex()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, ttlMs: Long): T? = synchronized(lock) {
        store[key]?.takeIf { System.currentTimeMillis() - it.time <= ttlMs }?.value as? T
    }

    /** 仅取旧快照，不代表仍然新鲜；使用方须通过 get / refresh 检查有效期。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(key: String): T? = synchronized(lock) { store[key]?.value as? T }

    /** 写入进程缓存并返回本次写入时间，供对应磁盘快照沿用网络结果时间。 */
    fun put(key: String, value: Any): Long = synchronized(lock) {
        val writtenAtMs = System.currentTimeMillis()
        store[key] = Entry(value, writtenAtMs)
        trimMemoryLocked()
        writtenAtMs
    }

    /** 在页面网络请求发起前捕获代次；clear 后旧回包不得重新回填页面缓存。 */
    internal fun capturePageSnapshot(key: String): PageSnapshotToken? = synchronized(lock) {
        pageSnapshotFamily(key)?.let { family ->
            PageSnapshotToken(family, generationLocked(family))
        }
    }

    internal fun isPageSnapshotCurrent(token: PageSnapshotToken, key: String): Boolean =
        synchronized(lock) { isPageSnapshotCurrentLocked(token, key) }

    /** 代次检查与内存写入在同一临界区，避免 clear 与旧网络回包交错回填。 */
    internal fun putPageIfCurrent(token: PageSnapshotToken, key: String, value: Any): Long? =
        synchronized(lock) {
            if (!isPageSnapshotCurrentLocked(token, key)) return@synchronized null
            val writtenAtMs = System.currentTimeMillis()
            store[key] = Entry(value, writtenAtMs)
            trimMemoryLocked()
            writtenAtMs
        }

    /** 仅从磁盘恢复榜单目录；按 key 懒读，不扫描整个快照目录。 */
    internal suspend fun hydrateBoardList(context: Context, key: String): List<BoardItem>? =
        readDiskSnapshot(context, key) { bytes, nowMs ->
            OnlineCacheSnapshotCodec.decodeBoardList(key, bytes, nowMs)
        }

    /** 仅从磁盘恢复歌单广场第一页；恢复值保留落盘时的 page/hasMore 时间语义。 */
    internal suspend fun hydratePlaylistFirstPage(context: Context, key: String): CachedPlaylistPage? =
        readDiskSnapshot(context, key) { bytes, nowMs ->
            OnlineCacheSnapshotCodec.decodePlaylistFirstPage(key, bytes, nowMs)
        }

    /** 只允许把榜单目录白名单写入磁盘，失败时不影响内存缓存或页面刷新。 */
    internal suspend fun persistBoardList(
        context: Context,
        key: String,
        value: List<BoardItem>,
        token: PageSnapshotToken,
        writtenAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pageSnapshotFamily(key) != PageSnapshotFamily.BOARDS) return false
        return writeDiskSnapshot(context, key, token, writtenAtMs) {
            OnlineCacheSnapshotCodec.encodeBoardList(key, value, writtenAtMs)
        }
    }

    /** 只允许持久化歌单第一页，page != 1 的聚合结果直接拒绝。 */
    internal suspend fun persistPlaylistFirstPage(
        context: Context,
        key: String,
        value: CachedPlaylistPage,
        token: PageSnapshotToken,
        writtenAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pageSnapshotFamily(key) != PageSnapshotFamily.PLAYLISTS ||
            value.page != 1 || value.list.isEmpty()
        ) return false
        return writeDiskSnapshot(context, key, token, writtenAtMs) {
            OnlineCacheSnapshotCodec.encodePlaylistFirstPage(key, value, writtenAtMs)
        }
    }

    /** 清理本切片的全部磁盘快照与页面内存；删除失败必须向 CacheManager 抛 IOException。 */
    suspend fun clearDisk(context: Context) {
        // 直接复用全量内存清理；CacheManager 先 clear() 再调用本方法也是幂等的。
        clear()
        try {
            withContext(Dispatchers.IO) {
                diskMutex.withLock { deleteSnapshotDirectory(snapshotDirectory(context)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("页面缓存清理失败", failure)
        }
    }

    /** 在派生刷新任务调度前捕获在途请求；即使它随后完成，也可复用同一次结果。 */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> pendingRefresh(key: String): Deferred<T>? = refreshing.pending(key) as Deferred<T>?

    /**
     * 同 key 网络请求合并。默认允许无人等待的刷新继续回填缓存；按需可在最后一个等待者离开时取消。
     * 强刷保留旧读取者，但撤销旧flight的回填资格；失败、空结果和clear均不污染缓存。
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> refresh(
        key: String,
        ttlMs: Long,
        force: Boolean = false,
        cancelWhenUnobserved: Boolean = false,
        load: suspend () -> T,
    ): T {
        var previous: Entry? = null
        return refreshing.run(
            key = key,
            restart = force,
            cancelReplaced = false,
            cancelWhenUnobserved = cancelWhenUnobserved,
            cacheHit = { previous = store[key]; if (force) null else get<T>(key, ttlMs) },
        ) {
            val value = load()
            val task = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (refreshing.pending(key) === task && store[key] === previous &&
                    (value !is Collection<*> || value.isNotEmpty())) {
                    put(key, value)
                }
            }
            value
        } as T
    }

    /** 可按命名空间失效；解析器清理不会连带清空本切片的页面目录缓存。 */
    fun clear(prefix: String = "") = synchronized(lock) {
        invalidatePageSnapshotsLocked(prefix)
        store.keys.removeAll { it.startsWith(prefix) }
        refreshing.cancelWhere { it.startsWith(prefix) }
    }

    private fun trimMemoryLocked() {
        while (store.size > MAX_ENTRIES) store.remove(store.keys.first())
    }

    private suspend fun <T : Any> readDiskSnapshot(
        context: Context,
        key: String,
        decode: (ByteArray, Long) -> OnlineCacheDecodedSnapshot<T>?,
    ): T? {
        val token = capturePageSnapshot(key) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                diskMutex.withLock {
                    if (!isPageSnapshotCurrent(token, key)) return@withLock null
                    val file = snapshotFile(context, key)
                    if (!file.isFile) return@withLock null
                    if (file.length() > ONLINE_CACHE_SNAPSHOT_MAX_BYTES) {
                        if (isPageSnapshotCurrent(token, key)) file.delete()
                        return@withLock null
                    }
                    val decoded = runCatching {
                        decode(file.readBytes(), System.currentTimeMillis())
                    }.getOrNull()
                    if (!isPageSnapshotCurrent(token, key)) return@withLock null
                    if (decoded == null) {
                        file.delete()
                        return@withLock null
                    }
                    if (installStoredValue(token, key, decoded.value, decoded.writtenAtMs)) {
                        decoded.value
                    } else {
                        null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun writeDiskSnapshot(
        context: Context,
        key: String,
        token: PageSnapshotToken,
        writtenAtMs: Long,
        encode: () -> ByteArray,
    ): Boolean {
        if (writtenAtMs <= 0L || isExpired(writtenAtMs, System.currentTimeMillis())) return false
        if (!isPageSnapshotCurrent(token, key)) return false
        return try {
            withContext(Dispatchers.IO) {
                diskMutex.withLock {
                    if (!isPageSnapshotCurrent(token, key)) return@withLock false
                    val bytes = runCatching { encode() }.getOrNull() ?: return@withLock false
                    if (bytes.isEmpty() || bytes.size.toLong() > ONLINE_CACHE_SNAPSHOT_MAX_BYTES) {
                        return@withLock false
                    }
                    if (!isPageSnapshotCurrent(token, key)) return@withLock false
                    val directory = snapshotDirectory(context)
                    if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
                        return@withLock false
                    }
                    val target = snapshotFile(context, key)
                    val previous = metadataOf(target)
                    if (previous?.key == key && previous.writtenAtMs > writtenAtMs) {
                        return@withLock false
                    }
                    atomicWrite(target, bytes)
                    if (!isPageSnapshotCurrent(token, key)) {
                        deleteIfMatches(target, key, writtenAtMs)
                        return@withLock false
                    }
                    pruneDiskSnapshots(directory, target)
                    isPageSnapshotCurrent(token, key)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private fun snapshotDirectory(context: Context): File =
        (context.applicationContext ?: context).cacheDir.resolve(SNAPSHOT_DIRECTORY)

    private fun deleteSnapshotDirectory(directory: File) {
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            if (file.exists() && !file.delete() && file.exists()) {
                throw IOException("无法删除页面缓存文件： ${file.absolutePath}")
            }
        }
        if (directory.exists()) {
            throw IOException("无法删除页面缓存目录： ${directory.absolutePath}")
        }
    }

    private fun snapshotFile(context: Context, key: String): File =
        snapshotDirectory(context).resolve("${sha256(key)}.json")

    private fun metadataOf(file: File): OnlineCacheSnapshotMetadata? {
        if (!file.isFile || file.length() > ONLINE_CACHE_SNAPSHOT_MAX_BYTES) return null
        return runCatching { OnlineCacheSnapshotCodec.readMetadata(file.readBytes()) }.getOrNull()
    }

    private fun pruneDiskSnapshots(directory: File, protectedFile: File) {
        val nowMs = System.currentTimeMillis()
        val valid = mutableListOf<DiskFile>()
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.forEach { file ->
                if (file.length() > ONLINE_CACHE_SNAPSHOT_MAX_BYTES) {
                    file.delete()
                    return@forEach
                }
                val metadata = metadataOf(file)
                if (metadata == null || isExpired(metadata.writtenAtMs, nowMs)) {
                    file.delete()
                    return@forEach
                }
                valid += DiskFile(file, metadata, file.length())
            }

        val protected = valid.firstOrNull { it.file == protectedFile }
        val keep = mutableListOf<DiskFile>()
        protected?.let(keep::add)
        valid.asSequence()
            .filterNot { it.file == protectedFile }
            .sortedByDescending { it.metadata.writtenAtMs }
            .take((ONLINE_CACHE_SNAPSHOT_MAX_ENTRIES - keep.size).coerceAtLeast(0))
            .forEach(keep::add)

        val keepPaths = keep.map { it.file }.toSet()
        valid.filterNot { it.file in keepPaths }.forEach { it.file.delete() }

        var totalBytes = keep.sumOf(DiskFile::size)
        if (totalBytes > ONLINE_CACHE_SNAPSHOT_MAX_BYTES) {
            keep.asSequence()
                .filterNot { it.file == protectedFile }
                .sortedBy { it.metadata.writtenAtMs }
                .forEach { entry ->
                    if (totalBytes > ONLINE_CACHE_SNAPSHOT_MAX_BYTES) {
                        if (entry.file.delete()) totalBytes -= entry.size
                    }
                }
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = requireNotNull(target.parentFile)
        val temporary = File(parent, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                check(temporary.renameTo(target)) { "Unable to atomically replace ${target.name}" }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun deleteIfMatches(file: File, key: String, writtenAtMs: Long) {
        val metadata = metadataOf(file)
        if (metadata?.key == key && metadata.writtenAtMs == writtenAtMs) file.delete()
    }

    private fun isExpired(writtenAtMs: Long, nowMs: Long): Boolean =
        nowMs >= writtenAtMs && nowMs - writtenAtMs > ONLINE_CACHE_SNAPSHOT_MAX_AGE_MS

    private fun <T : Any> installStoredValue(
        token: PageSnapshotToken,
        key: String,
        value: T,
        writtenAtMs: Long,
    ): Boolean = synchronized(lock) {
        // 代次校验与 put 必须在同一把锁内，避免 clear 恰好夹在两者之间又回填旧磁盘值。
        if (!isPageSnapshotCurrentLocked(token, key)) return@synchronized false
        val current = store[key]
        if (current != null && current.time > writtenAtMs) return@synchronized false
        store[key] = Entry(value, writtenAtMs)
        trimMemoryLocked()
        true
    }

    private fun pageSnapshotFamily(key: String): PageSnapshotFamily? = when {
        key.startsWith("boards.") -> PageSnapshotFamily.BOARDS
        key.startsWith("playlists.") -> PageSnapshotFamily.PLAYLISTS
        else -> null
    }

    private fun generationLocked(family: PageSnapshotFamily): Long = when (family) {
        PageSnapshotFamily.BOARDS -> boardSnapshotGeneration
        PageSnapshotFamily.PLAYLISTS -> playlistSnapshotGeneration
    }

    private fun isPageSnapshotCurrentLocked(token: PageSnapshotToken, key: String): Boolean =
        pageSnapshotFamily(key) == token.family && generationLocked(token.family) == token.generation

    private fun invalidatePageSnapshotsLocked(prefix: String) {
        when {
            prefix.isEmpty() -> {
                boardSnapshotGeneration++
                playlistSnapshotGeneration++
            }
            prefix == "boards" || prefix.startsWith("boards.") -> boardSnapshotGeneration++
            prefix == "playlists" || prefix.startsWith("playlists.") -> playlistSnapshotGeneration++
        }
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal enum class PageSnapshotFamily {
    BOARDS,
    PLAYLISTS,
}

internal data class PageSnapshotToken(
    val family: PageSnapshotFamily,
    val generation: Long,
)
