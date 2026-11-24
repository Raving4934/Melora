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
import kotlinx.coroutines.flow.combine
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
    private const val FILE_NAME = "melora-records-v1.json"
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

    private fun preferences(context: Context) = context.getSharedPreferences("webdav-sync", Context.MODE_PRIVATE)

    @Synchronized
    fun init(context: Context) {
        if (started) return
        val app = context.applicationContext
        _enabled.value = preferences(app).contains(SCOPE)
        started = true
        scope.launch {
            combine(UserLibrary.revisions, BookListeningProgress.updates) { library, books -> library to books }
                .drop(1).collect { if (enabled.value) changes.trySend(Unit) }
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
                check(WebDavBackup.test(config).canSync) { "服务器支持备份，但无法保护多设备同时写入" }
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
    }

    suspend fun syncNow(context: Context) = withContext(Dispatchers.IO) { attempt(context) }

    private suspend fun attempt(context: Context): Boolean = operations.withLock {
        if (!enabled.value) return@withLock true
        try {
            _status.value = "正在同步…"
            val config = requireNotNull(WebDavConfigStore.load(context)) { "请重新保存 WebDAV 配置" }
            check(config.scopeKey == preferences(context).getString(SCOPE, null)) { "目标目录已变更，请重新开启同步" }
            val client = WebDavClient(config)
            client.ensureFolder()
            val ledger = WebDavSyncLedger(File(context.noBackupFilesDir, "records-${config.scopeKey}"))
            syncRecords(ledger, WebDavConfigStore.deviceId(context),
                capture = { capture(context).portable },
                apply = { sent, agreed, commit -> applyAgreement(context, sent, agreed, commit) },
                read = { client.read(FILE_NAME, MAX_BACKUP_BYTES) },
                write = { text, etag -> client.put(FILE_NAME, text, etag) })
            _status.value = "上次同步 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}"
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _status.value = "同步失败 · ${error.localizedMessage ?: "请检查网络和配置"}"
            false
        }
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

    /** 网络请求期间的拖动、收藏与取消收藏仍保留为尚未上传的本地修改。 */
    private fun applyAgreement(context: Context, sent: JSONObject, agreed: JSONObject, commit: () -> Unit) {
        if (RecordSyncMerge.contentEquals(sent, agreed)) {
            commit()
            return
        }
        repeat(4) {
            val current = capture(context)
            val merged = if (RecordSyncMerge.contentEquals(current.portable, sent)) agreed
                else RecordSyncMerge.merge(sent, agreed, current.portable)
            val library = RecordSyncMerge.applyLibrary(current.library, merged)
            val libraryText = if (RecordSyncMerge.contentEquals(current.library, library)) null else library.toString()
            val before = current.portable.getJSONArray("books")
            val after = merged.getJSONArray("books")
            val applied = BackupStateLock.write {
                if (UserLibrary.revisions.value != current.libraryRevision ||
                    BookListeningProgress.updates.value != current.booksRevision) return@write false
                if (libraryText != null) UserLibrary.replaceFromBackup(libraryText)
                BookListeningProgress.applySync(context, before, after)
                commit()
                true
            }
            if (applied) return
        }
        error("本机记录正在变更，稍后将重新同步")
    }
}
