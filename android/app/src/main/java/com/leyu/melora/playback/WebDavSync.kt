package com.leyu.melora.playback

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

internal object WebDavSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val operations = Mutex()
    private var started = false
    private var foregroundSync: Job? = null
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _conflicts = MutableStateFlow<List<RecordConflict>>(emptyList())
    val conflicts = _conflicts.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (started) return
        val app = context.applicationContext
        syncConfig(app).onFailure(::showFailure)
        started = true
        scope.launch {
            UserLibrary.revisions.drop(1).collect { if (enabled.value) changes.trySend(Unit) }
        }
        scope.launch {
            // 断点每几秒刷新。一个收听时段批量发布一次，手动同步与前台恢复仍立即读取最新断点。
            BookListeningProgress.updates.drop(1).collect {
                if (enabled.value) {
                    delay(120_000)
                    if (enabled.value) changes.trySend(Unit)
                }
            }
        }
        scope.launch {
            var retryDelay = 30_000L
            for (signal in changes) {
                // 持续播放每五秒产生一次断点；按批次上传，也让离线期间的收藏修改合并。
                delay(15_000)
                changes.tryReceive()
                if (attempt(app)) retryDelay = 30_000L else {
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(15 * 60_000L)
                    if (enabled.value) changes.trySend(Unit)
                }
            }
        }
    }

    @Synchronized
    fun onForeground(context: Context) {
        init(context)
        if (!enabled.value || foregroundSync?.isActive == true) return
        val app = context.applicationContext
        foregroundSync = scope.launch { if (!attempt(app)) changes.trySend(Unit) }
    }

    suspend fun saveConfig(context: Context, config: WebDavConfig): WebDavConfig = withContext(Dispatchers.IO) {
        operations.withLock {
            WebDavConfigStore.save(context, config).also { saved ->
                publishEnabled(WebDavConfigStore.enabledScope(context) == saved.scopeKey)
            }
        }.also { BackupSchedule.onWebDavConfigSaved(context) }
    }

    suspend fun setEnabled(context: Context, value: Boolean) = withContext(Dispatchers.IO) {
        init(context)
        operations.withLock {
            val key = if (value) {
                val config = requireNotNull(WebDavConfigStore.load(context)) { "请先保存 WebDAV 配置" }
                WebDavBackup.test(config)
                config.scopeKey
            } else null
            WebDavConfigStore.setEnabledScope(context, key)
            publishEnabled(value)
            if (value) {
                _status.value = "正在等待首次合并"
                onForeground(context)
            }
        }
    }

    private fun publishEnabled(value: Boolean) {
        _enabled.value = value
        if (!value) {
            _conflicts.value = emptyList()
            _status.value = ""
        }
    }

    private fun syncConfig(context: Context): Result<WebDavConfig?> {
        val scope = WebDavConfigStore.enabledScope(context)
        return runCatching {
            scope?.let {
                requireNotNull(WebDavConfigStore.load(context)) { "请重新保存 WebDAV 配置" }
                    .takeIf { config -> config.scopeKey == scope }
            }
        }.also { result ->
            // 暂时无法解密时保留开关供用户关闭，重试必须重新核对目标。
            publishEnabled(result.fold({ it != null }, { true }))
        }
    }

    suspend fun syncNow(context: Context) = withContext(Dispatchers.IO) { attempt(context) }

    suspend fun resolve(context: Context, choices: Map<String, Boolean>) = withContext(Dispatchers.IO) {
        operations.withLock {
            val config = checkNotNull(syncConfig(context).getOrThrow()) { "请先开启同步我的记录" }
            synchronize(context, config, choices)
        }
    }

    private suspend fun attempt(context: Context): Boolean = operations.withLock {
        try {
            syncConfig(context).getOrThrow()?.let { synchronize(context, it, emptyMap()) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showFailure(error)
            false
        }
    }

    private fun showFailure(error: Throwable) {
        _status.value = "同步失败 · ${error.localizedMessage ?: "请检查网络和配置"}"
    }

    private suspend fun synchronize(context: Context, config: WebDavConfig, choices: Map<String, Boolean>) {
        val client = WebDavClient(config)
        client.ensureFolder()
        // 手动同步包含已提交但尚未落盘的页面操作。
        UserLibrary.enqueueWrite { }.await()
        val ledger = WebDavSyncLedger(File(context.noBackupFilesDir, "records-${config.scopeKey}"))
        val conflicts = ledger.sync(WebDavConfigStore.deviceId(context), client,
            capture = { capture(context).portable },
            apply = { sent, agreed, prepare, commit -> applyAgreement(context, sent, agreed, prepare, commit) },
            choices = choices)
        _conflicts.value = conflicts
        _status.value = if (conflicts.isEmpty()) "上次同步 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}"
            else "等待处理同步冲突"
    }

    private data class Capture(val library: JSONObject, val portable: JSONObject, val libraryRevision: Long, val booksRevision: Long)
    private data class RawRecords(val library: String, val books: JSONArray, val libraryRevision: Long, val booksRevision: Long)

    private fun capture(context: Context): Capture {
        val raw = BackupStateLock.write {
            RawRecords(UserLibrary.exportSnapshot(), BookListeningProgress.syncSnapshot(context),
                UserLibrary.revisions.value, BookListeningProgress.updates.value)
        }
        val library = JSONObject(raw.library)
        return Capture(library, RecordSyncMerge.project(library, raw.books), raw.libraryRevision, raw.booksRevision)
    }

    private fun applyAgreement(context: Context, sent: JSONObject, agreed: JSONObject,
                               prepare: () -> Unit, commit: () -> Unit): Boolean {
        val current = capture(context)
        if (!RecordSyncMerge.contentEquals(current.portable, sent)) return false
        val library = RecordSyncMerge.applyLibrary(current.library, agreed)
        val libraryText = if (RecordSyncMerge.contentEquals(current.library, library)) null else library.toString()
        return BackupStateLock.write {
            if (UserLibrary.revisions.value != current.libraryRevision ||
                BookListeningProgress.updates.value != current.booksRevision) return@write false
            prepare()
            if (libraryText != null) UserLibrary.replaceFromBackup(libraryText)
            BookListeningProgress.applySync(context, sent.getJSONArray("books"), agreed.getJSONArray("books"))
            commit()
            true
        }
    }
}
