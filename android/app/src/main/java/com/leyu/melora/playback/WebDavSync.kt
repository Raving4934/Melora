package com.leyu.melora.playback

import android.annotation.SuppressLint
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
    private const val SCOPE = "enabled-scope"
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

    private fun preferences(context: Context) = context.getSharedPreferences("webdav-sync", Context.MODE_PRIVATE)

    @Synchronized
    fun init(context: Context) {
        if (started) return
        val app = context.applicationContext
        _enabled.value = preferences(app).contains(SCOPE)
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
            val saved = WebDavConfigStore.save(context, config)
            if (preferences(context).getString(SCOPE, null) != saved.scopeKey) {
                setEnabledScope(context, null)
                _status.value = ""
            }
            saved
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
            setEnabledScope(context, key)
            _status.value = if (value) "正在等待首次合并" else ""
        }
        if (value) onForeground(context)
    }

    @SuppressLint("UseKtx") // 开启同步前必须确认选定账号已持久化，KTX.edit 不返回 commit 的结果。
    private fun setEnabledScope(context: Context, key: String?) {
        check(preferences(context).edit().putString(SCOPE, key).commit()) { "无法保存同步开关" }
        _enabled.value = key != null
        if (key == null) _conflicts.value = emptyList()
    }

    suspend fun syncNow(context: Context) = withContext(Dispatchers.IO) { attempt(context) }

    suspend fun resolve(context: Context, choices: Map<String, Boolean>) = withContext(Dispatchers.IO) {
        operations.withLock {
            check(enabled.value) { "请先开启同步我的记录" }
            synchronize(context, choices)
        }
    }

    private suspend fun attempt(context: Context): Boolean = operations.withLock {
        if (!enabled.value) return@withLock true
        try {
            synchronize(context, emptyMap())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _status.value = "同步失败 · ${error.localizedMessage ?: "请检查网络和配置"}"
            false
        }
    }

    private suspend fun synchronize(context: Context, choices: Map<String, Boolean>) {
        val config = requireNotNull(WebDavConfigStore.load(context)) { "请重新保存 WebDAV 配置" }
        check(config.scopeKey == preferences(context).getString(SCOPE, null)) { "目标目录已变更，请重新开启同步" }
        val client = WebDavClient(config)
        client.ensureFolder()
        val ledger = WebDavSyncLedger(File(context.noBackupFilesDir, "records-${config.scopeKey}"))
        val conflicts = ledger.sync(WebDavConfigStore.deviceId(context), client.recordFiles(),
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

internal fun WebDavClient.recordFiles(): RecordFiles = object : RecordFiles {
    override suspend fun list() = listNames()
    override suspend fun read(name: String) = this@recordFiles.read(name, MAX_BACKUP_BYTES)
    override suspend fun write(name: String, text: String) = writeFile(name, text)
    override suspend fun delete(name: String) = deleteFile(name)
}
