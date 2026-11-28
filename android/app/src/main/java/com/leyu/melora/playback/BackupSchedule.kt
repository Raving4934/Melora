package com.leyu.melora.playback

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DAY_MS = 24 * 60 * 60 * 1000L

internal object BackupSchedule {
    private const val SAVE_ERROR = "无法保存备份设置"
    private const val FIRST_JOB = 0x4D4201
    private const val SECOND_JOB = 0x4D4202
    private const val PREFS = "melora-backup-schedule"
    private const val DIRECTORY = "directory"
    private const val DIRECTORY_NAME = "directoryName"
    private const val AUTOMATIC = "automatic"
    private const val KEEP_LATEST = "keepLatest"
    private const val LAST_RESULT = "lastResult"
    private const val PENDING = "pending"
    private val destinations = listOf(BackupDestination.LOCAL, BackupDestination.NETWORK)
    private val operations = Mutex()
    private val mutableSettings = MutableStateFlow(BackupPreferences())
    private val mutableLastAutomaticResult = MutableStateFlow<String?>(null)
    val settings = mutableSettings.asStateFlow()
    val lastAutomaticResult = mutableLastAutomaticResult.asStateFlow()

    suspend fun load(context: Context): BackupPreferences = locked {
        val prefs = preferences(context)
        val settings = prefs.readSettings()
        mutableSettings.value = settings
        mutableLastAutomaticResult.value = prefs.getString(LAST_RESULT, null)
        val scheduler = scheduler(context)
        if (settings.automatic != BackupDestination.OFF &&
            scheduler.getPendingJob(FIRST_JOB) == null && scheduler.getPendingJob(SECOND_JOB) == null
        ) {
            updateJob(prefs) { schedule(context, FIRST_JOB, settings.automatic, 0L) }
        }
        settings
    }

    suspend fun chooseDirectory(context: Context, tree: Uri): BackupPreferences = locked {
        val prefs = preferences(context)
        val old = prefs.readSettings()
        val resolver = context.contentResolver
        val updated = resolver.withPersistableTreeAccess(tree, "无法获得备份目录的长期读写权限，请重新选择", "请选择本地备份文件夹") {
            val (_, name) = LocalBackup.writableDirectory(resolver, tree)
            prefs.commitOrRestore(SAVE_ERROR) { putString(DIRECTORY, tree.toString()).putString(DIRECTORY_NAME, name) }
            old.copy(directory = tree, directoryName = name)
        }
        mutableSettings.value = updated
        if (old.directory != tree && updated.automatic.local) updateJob(prefs) { restartSchedule(context, updated.automatic) }
        updated
    }

    suspend fun setAutomatic(context: Context, destination: BackupDestination): BackupPreferences = locked {
        val prefs = preferences(context)
        val current = prefs.readSettings()
        if (destination.local) {
            val directory = requireNotNull(current.directory) { "请先选择本地备份目录" }
            LocalBackup.writableDirectory(context.contentResolver, directory)
        }
        if (destination.network) loadWebDav(context)
        restartSchedule(context, destination)
        try {
            prefs.commitOrRestore(SAVE_ERROR) {
                putString(AUTOMATIC, destination.name)
                if (current.automatic != destination) remove(PENDING)
            }
        } catch (failure: Exception) {
            updateJob(prefs) { restartSchedule(context, current.automatic) }
            throw failure
        }
        val updated = prefs.readSettings().also { mutableSettings.value = it }
        if (current.automatic != destination) discardPending(context, prefs)
        updated
    }

    suspend fun onWebDavConfigSaved(context: Context) = locked {
        val prefs = preferences(context)
        val automatic = prefs.readSettings().automatic
        if (automatic.network) updateJob(prefs) { restartSchedule(context, automatic) }
    }

    suspend fun setKeepLatest(context: Context, keepLatest: Boolean): BackupPreferences = locked {
        val prefs = preferences(context)
        prefs.commitOrRestore(SAVE_ERROR) { putBoolean(KEEP_LATEST, keepLatest) }
        prefs.readSettings().also { mutableSettings.value = it }
    }

    suspend fun backupNow(context: Context, destination: BackupDestination): String = locked {
        require(destination != BackupDestination.OFF) { "请选择备份位置" }
        val prefs = preferences(context)
        val settings = prefs.readSettings()
        val remote = if (destination.network) attempt { loadWebDav(context) } else null
        val snapshot = BackupManager.snapshot(context)
        val targets = destinations.filter { destination.includes(it) }
        val scopes = targetScopes(targets, settings, remote)
        writeDestinations(context, scopes.keys, settings, remote, snapshot) { target ->
            prefs.recordSuccess(target, scopes.getValue(target), snapshot.id)
        }.joinToString(" · ") { it.message }
    }

    /** 在持久化下一次任务后结束当前任务。失败由系统退避重试，两处备份各自记录进度。 */
    suspend fun runAutomatic(context: Context, jobId: Int): Boolean = locked {
        val prefs = preferences(context)
        val settings = prefs.readSettings()
        val mode = settings.automatic
        if (mode == BackupDestination.OFF) return@locked false
        try {
            val now = System.currentTimeMillis()
            val remote = if (mode.network) attempt { loadWebDav(context) } else null
            val scopes = targetScopes(destinations.filter { mode.includes(it) }, settings, remote)
            val due = scopes.filter { (target, scope) -> prefs.delayFor(target, scope, now) == 0L }
            if (due.isNotEmpty()) {
                val run = attempt { readPending(prefs) }.getOrNull()?.takeIf { saved ->
                    val bound = if (remote?.isFailure == true) saved.scopes - BackupDestination.NETWORK else saved.scopes
                    snapshotFile(context).isFile && backupDelay(saved.id.createdAt, now) > 0L &&
                        scopes.entries.containsAll(bound.entries)
                } ?: prepare(context, prefs, due)
                // 复用本次快照，独立核对每个当前到期的目的地。手动备份也满足该目的地周期。
                val remaining = due.filterNot { (target, scope) ->
                    prefs.lastSuccessScope(target) == scope &&
                        prefs.getString("done.${target.name}", null) == run.id.uuid.toString()
                }.keys
                if (remaining.isNotEmpty()) {
                    val text = attempt {
                        snapshotFile(context).inputStream().use { it.readBackupText() }.also(::parseBackupDocument)
                    }.getOrElse {
                        discardPending(context, prefs)
                        throw it
                    }
                    val outcomes = writeDestinations(context, remaining, settings, remote, BackupSnapshot(run.id, text)) { target ->
                        prefs.recordSuccess(target, scopes.getValue(target), run.id)
                    }
                    publishResult(prefs, outcomes.joinToString(" · ") { it.message })
                    if (outcomes.any { it.failed }) return@locked true
                }
            }
            discardPending(context, prefs)
            val nextDelay = scopes.minOf { (target, scope) -> prefs.delayFor(target, scope, System.currentTimeMillis()) }
            schedule(context, if (jobId == FIRST_JOB) SECOND_JOB else FIRST_JOB, mode, nextDelay)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 记录失败本身须独立于任务重试。即使存储满，也能让系统继续退避。
            attempt { publishResult(prefs, "自动备份未完成，${error.localizedMessage ?: "请检查备份设置"}") }
            true
        }
    }

    private suspend fun writeDestinations(
        context: Context,
        targets: Collection<BackupDestination>,
        settings: BackupPreferences,
        remote: Result<WebDavConfig>?,
        snapshot: BackupSnapshot,
        onSuccess: (BackupDestination) -> Unit = {},
    ): List<Outcome> = targets.map { target ->
        val local = target == BackupDestination.LOCAL
        val result = attempt {
            val message = if (local) {
                val tree = requireNotNull(settings.directory) { "请先选择本地备份目录" }
                LocalBackup.write(context, tree, snapshot, settings.keepLatest)
            } else {
                WebDavBackup.upload(requireNotNull(remote).getOrThrow(), snapshot)
                "网络已备份"
            }
            onSuccess(target)
            message
        }
        result.fold(
            onSuccess = { Outcome(it, failed = false) },
            onFailure = { Outcome("${if (local) "本地" else "网络"}备份失败 · ${it.localizedMessage ?: "请重试"}", failed = true) },
        )
    }

    private fun publishResult(prefs: SharedPreferences, status: String) {
        val recorded = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())} · $status"
        mutableLastAutomaticResult.value = recorded
        prefs.commitOrRestore(SAVE_ERROR) { putString(LAST_RESULT, recorded) }
    }

    private inline fun updateJob(prefs: SharedPreferences, update: () -> Unit) {
        attempt(update).onFailure { failure ->
            attempt { publishResult(prefs, failure.localizedMessage ?: "系统暂时无法安排自动备份") }
        }
    }

    private fun restartSchedule(context: Context, destination: BackupDestination) {
        val scheduler = scheduler(context)
        if (destination != BackupDestination.OFF) schedule(context, FIRST_JOB, destination, 0L)
        else scheduler.cancel(FIRST_JOB)
        scheduler.cancel(SECOND_JOB)
    }

    private fun schedule(context: Context, id: Int, destination: BackupDestination, delay: Long) {
        val job = JobInfo.Builder(id, ComponentName(context, BackupJobService::class.java))
            .setPersisted(true)
            .setMinimumLatency(delay)
            .setBackoffCriteria(30 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if (destination == BackupDestination.NETWORK) job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        check(scheduler(context).schedule(job.build()) == JobScheduler.RESULT_SUCCESS) { "系统暂时无法安排自动备份" }
    }

    private fun scheduler(context: Context): JobScheduler =
        requireNotNull(context.getSystemService(JobScheduler::class.java)) { "系统任务服务不可用" }
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun snapshotFile(context: Context) = File(context.noBackupFilesDir, "auto-backup-snapshot.json")
    private fun loadWebDav(context: Context) = requireNotNull(WebDavConfigStore.load(context)) { "请先保存 WebDAV 配置" }

    private fun targetScopes(
        targets: List<BackupDestination>,
        settings: BackupPreferences,
        remote: Result<WebDavConfig>?,
    ): Map<BackupDestination, String> = targets.associateWith {
        if (it == BackupDestination.LOCAL) settings.directory?.toString().orEmpty() else remote?.getOrNull()?.scopeKey.orEmpty()
    }

    private suspend fun prepare(context: Context, prefs: SharedPreferences, scopes: Map<BackupDestination, String>): Pending {
        discardPending(context, prefs)
        val snapshot = BackupManager.snapshot(context)
        val file = snapshotFile(context)
        writeTextAtomically(file, snapshot.text)
        val metadata = JSONObject().put("name", snapshot.id.localName)
        scopes.forEach { (target, scope) -> metadata.put(target.name, scope) }
        prefs.commitOrRestore(SAVE_ERROR) { putString(PENDING, metadata.toString()) }
        return Pending(snapshot.id, scopes)
    }

    private fun readPending(prefs: SharedPreferences): Pending? = prefs.getString(PENDING, null)?.let { text ->
        val metadata = JSONObject(text)
        val id = requireNotNull(BackupFileId.parseLocalName(metadata.getString("name")))
        val scopes = destinations.filter { metadata.has(it.name) }.associateWith { metadata.getString(it.name) }
        Pending(id, scopes)
    }

    private fun discardPending(context: Context, prefs: SharedPreferences) {
        if (prefs.contains(PENDING)) prefs.commitOrRestore(SAVE_ERROR) { remove(PENDING) }
        val file = snapshotFile(context)
        file.delete()
        File(file.parentFile, "${file.name}.bak").delete()
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { operations.withLock { block() } }

    private fun SharedPreferences.readSettings() = BackupPreferences(
        directory = getString(DIRECTORY, null)?.let(Uri::parse),
        directoryName = getString(DIRECTORY_NAME, null),
        automatic = BackupDestination.entries.firstOrNull { it.name == getString(AUTOMATIC, null) } ?: BackupDestination.OFF,
        keepLatest = getBoolean(KEEP_LATEST, false),
    )

    private fun SharedPreferences.delayFor(target: BackupDestination, scope: String, now: Long): Long =
        if (lastSuccessScope(target) != scope) 0L else backupDelay(getLong("success.${target.name}", 0L), now)

    private fun SharedPreferences.lastSuccessScope(target: BackupDestination) = getString("scope.${target.name}", null)

    private fun SharedPreferences.recordSuccess(target: BackupDestination, scope: String, id: BackupFileId) = commitOrRestore(SAVE_ERROR) {
        putString("done.${target.name}", id.uuid.toString())
        putString("scope.${target.name}", scope)
        putLong("success.${target.name}", System.currentTimeMillis())
    }

    private data class Pending(val id: BackupFileId, val scopes: Map<BackupDestination, String>)
    private data class Outcome(val message: String, val failed: Boolean)
}

private fun BackupDestination.includes(target: BackupDestination) = if (target == BackupDestination.LOCAL) local else network

internal fun backupDelay(lastCompleted: Long, now: Long): Long =
    if (lastCompleted <= 0L || lastCompleted > now) 0L else (DAY_MS - (now - lastCompleted)).coerceAtLeast(0L)

private inline fun <T> attempt(action: () -> T): Result<T> = try {
    Result.success(action())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
