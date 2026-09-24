package com.leyu.melora.playback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineLyric
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val MAX_DOWNLOAD_TRANSFER_RESOURCES = 3
/** 首次 HTTP 传输失败后启动的墙钟重试窗口，覆盖后续解析及传输；不限制首次下载或正在进行中的传输。 */
internal const val DOWNLOAD_RETRY_WINDOW_MS = 20_000L

internal fun shouldRetryDownloadTransfer(
    autoSwitch: Boolean,
    networkAvailable: Boolean,
    attemptedResources: Int,
    remainingRetryWindowMs: Long,
): Boolean = autoSwitch && networkAvailable &&
    attemptedResources < MAX_DOWNLOAD_TRANSFER_RESOURCES && remainingRetryWindowMs > 0L

/** 下载标签必须对应实际音频；缺少身份时宁可不写，也不把另一版本降级成普通歌词混入。 */
internal fun embeddedLyricsForDownload(lyric: OnlineLyric, audioSongUid: String): EmbeddedLyrics? {
    if (lyric.song?.uid != audioSongUid) return null
    return EmbeddedLyrics.fromLines(
        LyricParser.parse(lyric.lyric, lyric.tlyric, lyric.rlyric, wordByWord = lyric.lxlyric),
    )?.takeUnless { it.isBlank }
}

internal fun findDownloadHttpTransferFailure(failure: Throwable): DownloadHttpTransferFailure? {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = failure
    while (current != null && visited.add(current)) {
        if (current is DownloadHttpTransferFailure) return current
        current = current.cause
    }
    return null
}

/** 下载器：解析直链后流式写入系统媒体库或用户选择的 SAF 目录，支持跳过同名、嵌入封面/歌词。 */
object Downloader {
    internal const val TEMP_DIRECTORY = "download_tmp"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val slots = DynamicDownloadGate(limit = {
        MeloraSettings.downloadConcurrentTasks.value.coerceIn(1, 4)
    })
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskLock = Any()
    private data class DownloadRequest(
        val quality: String, val path: String, val nameFormat: String,
        val skipExisting: Boolean, val autoSwitch: Boolean, val embedCover: Boolean, val embedLyric: Boolean,
        val upgradeFrom: LocalSong? = null,
    )
    private data class ActiveDownload(
        val token: Long,
        val task: Deferred<Result<String>>,
        val request: DownloadRequest,
        var recordStarted: Boolean,
        val previousRecord: DownloadCenter.Record?,
    )
    private val tasks = mutableMapOf<String, ActiveDownload>()
    private var nextTaskToken = 0L

    private const val CONFLICT_MESSAGE = "已有任务在进行，请先暂停后重试"

    /** 与提交任务使用同一把锁，避免“一键清空”删除正在写入/尚待导出的临时音频。 */
    internal fun clearTemporaryFiles(directory: File): Boolean = synchronized(taskLock) {
        if (tasks.values.any { !it.task.isCompleted }) false else {
            if (directory.exists() && !directory.deleteRecursively()) throw IOException("下载临时文件无法清理")
            true
        }
    }

    /** 下载归应用级作用域所有；关闭操作面板或离开页面只停止等待，不会中断实际任务。 */
    suspend fun download(context: Context, song: OnlineSong): Result<String> =
        submit(context, song).await()

    /** 升级检查与下载共用任务去重/并发/缓存，检查期间不创建下载记录。 */
    fun upgrade(context: Context, song: OnlineSong, local: LocalSong): Deferred<Result<String>> =
        submit(context, song, local) {
            PlaybackController.postMessage(context.applicationContext, CONFLICT_MESSAGE)
        }

    /** 继续/重试保留原任务ID与升级下限，不因本地歌曲已匹配到在线ID而另建记录。 */
    fun retry(context: Context, record: DownloadCenter.Record): Deferred<Result<String>> =
        submit(context, requireNotNull(record.song), record.upgradeFrom, record.id,
            onConflict = if (record.upgradeFrom != null) {
                { PlaybackController.postMessage(context.applicationContext, CONFLICT_MESSAGE) }
            } else null,
        )

    /** 取消指定歌曲的唯一在途任务；终态由调用方明确写入，旧任务不得覆盖新状态。 */
    private fun cancel(uid: String): Boolean {
        val active = synchronized(taskLock) { tasks.remove(uid) } ?: return false
        active.task.cancel(CancellationException("下载已取消"))
        return true
    }

    /** 暂停下载：终止当前传输并保留记录；继续时会重新建立下载流。 */
    fun pause(context: Context, uid: String) {
        cancel(uid)
        DownloadCenter.paused(uid, "已暂停，继续后将重新建立下载")
        DownloadNotifications.cancel(context, uid.hashCode())
    }

    /** 彻底删除：取消任务，并在物理文件删除成功后移除记录。删除失败时保留记录便于重试。 */
    suspend fun deletePermanently(context: Context, record: DownloadCenter.Record): Result<String> {
        val cancelled = cancel(record.id)
        DownloadNotifications.cancel(context, record.id.hashCode())
        // 用户点下删除到取得任务锁之间可能刚好完成发布。未持有文件快照的在途记录，
        // 必须读取取消同步后的最终地址；已有文件快照仍绑定原URI，不误删后续升级文件。
        val target = if (record.hasSavedResource) record
            else DownloadCenter.records.value.firstOrNull { it.id == record.id } ?: record
        val result = if (target.hasSavedResource && (target.fileName != null || target.savedUri != null)) {
            deleteSaved(context.applicationContext, target)
        } else {
            Result.success(if (cancelled || record.status == DownloadCenter.Status.Paused) "下载任务已删除" else "下载记录已删除")
        }
        result.onSuccess { DownloadCenter.remove(record.id) }
            .onFailure {
                if (cancelled || record.status == DownloadCenter.Status.Paused) {
                    DownloadCenter.paused(record.id, "删除失败，任务已暂停")
                }
            }
        return result
    }

    /** 仅移除已结束记录，绝不停止任务、绝不删除本地文件。 */
    fun removeRecordOnly(context: Context, id: String): Boolean {
        val record = DownloadCenter.records.value.firstOrNull { it.id == id } ?: return false
        if (record.status == DownloadCenter.Status.Downloading || record.status == DownloadCenter.Status.Paused) return false
        DownloadNotifications.cancel(context, id.hashCode())
        DownloadCenter.remove(id)
        return true
    }

    /**
     * 立即提交批量下载；只按 uid 去重，返回的是提交请求数，不承诺实际网络传输数。
     * 任务始终挂在应用级 taskScope 上，不受多选底栏或页面协程生命周期影响。
     */
    fun enqueue(context: Context, songs: List<OnlineSong>): Int {
        val uniqueSongs = distinctDownloadSongs(songs)
        uniqueSongs.forEach {
            submit(context, it) {
                PlaybackController.postMessage(context.applicationContext, CONFLICT_MESSAGE)
            }
        }
        return uniqueSongs.size
    }

    private fun submit(
        context: Context,
        song: OnlineSong,
        upgradeFrom: LocalSong? = null,
        taskId: String = song.uid,
        onConflict: (() -> Unit)? = null,
    ): Deferred<Result<String>> {
        val appContext = context.applicationContext
        val request = DownloadRequest(MeloraSettings.downloadQuality.value, MeloraSettings.downloadPath.value,
            MeloraSettings.downloadFileNameFormat.value, MeloraSettings.downloadSkipSameName.value,
            MeloraSettings.downloadAutoSwitchSource.value, MeloraSettings.downloadEmbedCover.value, MeloraSettings.downloadEmbedLyric.value, upgradeFrom)
        var created = false
        var conflicted = false
        val task = synchronized(taskLock) {
            val active = tasks[taskId]?.takeUnless { it.task.isCompleted }
            when {
                active == null -> {
                    val token = ++nextTaskToken
                    val deferred = taskScope.async(start = CoroutineStart.LAZY) {
                        performDownload(appContext, song, request, token, taskId)
                    }
                    tasks[taskId] = ActiveDownload(token, deferred, request, upgradeFrom == null,
                        DownloadCenter.records.value.firstOrNull { it.id == taskId })
                    deferred.invokeOnCompletion {
                        synchronized(taskLock) {
                            if (tasks[taskId]?.token == token) tasks.remove(taskId)
                        }
                    }
                    created = true
                    deferred
                }
                active.request == request -> active.task
                else -> {
                    conflicted = true
                    CompletableDeferred<Result<String>>().apply {
                        complete(Result.failure(IllegalStateException(CONFLICT_MESSAGE)))
                    }
                }
            }
        }
        if (conflicted) onConflict?.invoke()
        if (created) {
            if (upgradeFrom != null) PlaybackController.postMessage(appContext, "正在检查更高音质…")
            task.start()
        }
        return task
    }

    private suspend fun performDownload(
        context: Context,
        original: OnlineSong,
        request: DownloadRequest,
        token: Long,
        taskId: String,
    ): Result<String> = runCatching {
        if (request.upgradeFrom == null) {
            val queued = updateCurrent(taskId, token) {
                DownloadCenter.queued(taskId, original)
            }
            if (!queued) throw CancellationException("下载已取消")
        }
        val localSpec = request.upgradeFrom?.let { local ->
            val spec = local.audioSpecification.takeIf { it.verifiedQuality != null }
                ?: inspectDownloadAudio(context, local.uri.toUri())?.spec
                ?: error("无法识别本地歌曲音质，请重新扫描后再试")
            val quality = spec.verifiedQuality ?: error("本地歌曲音质未知，未开始升级")
            if (SourceResolver.qualityRank(quality) >= SourceResolver.qualityRank(SourceResolver.normalizedQuality(request.quality))) {
                return@runCatching "本地已达到所选音质，无需升级"
            }
            spec
        }
        slots.acquire()
        try {
            if (localSpec == null) updateCurrent(taskId, token) { DownloadCenter.start(taskId, original, "准备下载…") }
            val song = try {
                if (original.source == LocalSong.SOURCE) check(com.leyu.melora.playback.sdk.LxScriptPool.hasEnabledScripts(context)) {
                    SourceResolver.NO_SOURCE_MESSAGE
                }
                val resolved = SourceResolver.localAsOnline(context, original) ?: error("没有找到可下载的同版本在线歌曲")
                check(original.source != LocalSong.SOURCE || SourceResolver.alternativeScore(original, resolved) != null) {
                    "本地歌曲的歌手或版本信息不足，请补全后再下载"
                }
                resolved
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failCurrent(context, taskId, token, failure)
                throw failure
            }
            ensureCurrent(taskId, token)
            if (localSpec == null) updateCurrent(taskId, token) { DownloadCenter.start(taskId, song, "准备下载…") }
            try {
                downloadToTarget(context, song, taskId, request, token, localSpec)
            } catch (failure: Exception) {
                if (failure !is CancellationException) failCurrent(context, taskId, token, failure)
                throw failure
            }
        } finally {
            slots.release()
        }
    }.onSuccess {
        if (request.upgradeFrom != null) updateCurrent(taskId, token) { PlaybackController.postMessage(context, it) }
    }.onFailure {
        if (it is CancellationException) throw it
        if (request.upgradeFrom != null) updateCurrent(taskId, token) {
            PlaybackController.postMessage(context, it.message ?: "音质检查失败，请稍后重试")
        }
    }

    private fun ensureCurrent(uid: String, token: Long) {
        val current = synchronized(taskLock) { tasks[uid]?.token == token }
        if (!current) throw CancellationException("下载已取消")
    }

    private inline fun updateCurrent(uid: String, token: Long, update: () -> Unit): Boolean = synchronized(taskLock) {
        if (tasks[uid]?.token != token) return@synchronized false
        update()
        true
    }

    private fun failCurrent(context: Context, uid: String, token: Long, failure: Exception) {
        val message = failure.message ?: "下载失败"
        updateCurrent(uid, token) {
            if (tasks[uid]?.recordStarted == true) {
                DownloadCenter.failed(uid, message)
                DownloadNotifications.failed(context, uid.hashCode(), message)
            }
        }
    }

    private data class Target(val name: String, val uri: Uri, val size: Long, val modifiedAt: Long)
    private data class VerifiedTarget(val target: Target, val audio: DownloadAudio)
    private data class DownloadTransfer(val temporaryFile: File, val input: CachedAudioInput)

    private suspend fun downloadToTarget(
        context: Context,
        song: OnlineSong,
        recordId: String,
        request: DownloadRequest,
        token: Long,
        localSpec: AudioSpecification?,
    ): String {
        val baseName = buildBaseName(song, request.nameFormat)
        val previous = DownloadCenter.saved(recordId)
        val notificationId = recordId.hashCode()
        fun inspect(target: Target): VerifiedTarget? {
            val audio = inspectDownloadAudio(context, target.uri) ?: return null
            val trusted = previous?.savedUri == target.uri.toString()
            if (!audio.matches(song, trusted)) return null
            return VerifiedTarget(target, audio)
        }
        fun register(verified: VerifiedTarget) {
            val (target, audio) = verified
            LocalMediaStore.registerDownloaded(song, target.uri.toString(), audio.spec, audio.durationMs,
                target.size, target.name, target.modifiedAt)
        }
        fun finish(verified: VerifiedTarget, detail: String): String {
            // IO线程在同一令牌边界提交索引与完成记录，取消不能夹在两者之间留下悬空URI。
            if (!updateCurrent(recordId, token) {
                    register(verified)
                    DownloadCenter.done(recordId, detail, verified.target.name, verified.target.uri.toString(), audioSpec = verified.audio.spec)
                    DownloadNotifications.done(context, notificationId, verified.target.name)
                }
            ) {
                throw CancellationException("下载任务已结束")
            }
            return "$detail：${verified.target.name}"
        }
        // 目标目录内只查当前命名规则的候选，不把其它目录已有文件当作本次导出完成。
        val initial = downloadTargets(context, request.path, baseName).mapNotNull(::inspect)
        val initialByTarget = initial.associateBy(VerifiedTarget::target)
        initial.forEach(::register) // 保留旧高版在本地候选中，随后下载低码率副本也不会抢占播放。
        val upgradeBaseline = localSpec?.let { baseline ->
            (listOf(baseline) + initial.map { it.audio.spec }).maxBy { SourceResolver.qualityRank(it.verifiedQuality.orEmpty()) }
        }
        if (upgradeBaseline != null && SourceResolver.qualityRank(upgradeBaseline.verifiedQuality.orEmpty()) >=
            SourceResolver.qualityRank(SourceResolver.normalizedQuality(request.quality))) return "本地已达到所选音质，无需升级"
        if (request.skipExisting && upgradeBaseline == null) {
            initial.firstOrNull { downloadQualityMatches(it.audio.spec, request.quality) }?.let {
                return finish(it, "已跳过（已有同版本、同音质文件）")
            }
        }
        val tempDir = File(context.cacheDir, TEMP_DIRECTORY).apply { mkdirs() }
        val transfer = downloadAudioTransfer(
            context, song, recordId, request, token, upgradeBaseline, tempDir, baseName, notificationId,
        ) ?: return "当前可用音源暂无更优音质版本"
        val temp = transfer.temporaryFile
        val input = transfer.input
        try {
            ensureCurrent(recordId, token)
            val audio = inspectDownloadAudio(context, Uri.fromFile(temp)) ?: error("下载内容不是可识别的音频，原文件未修改")
            check(audio.durationMs > 0 && (song.intervalSeconds <= 0 || audio.durationMs >= song.intervalSeconds * 650L)) {
                "下载音频时长异常，可能是试听片段；原文件未修改"
            }
            if (upgradeBaseline != null && isBetterDownloadQuality(audio.spec, upgradeBaseline) != true) {
                updateCurrent(recordId, token) {
                    val active = tasks.getValue(recordId)
                    DownloadCenter.restoreRecord(recordId, active.previousRecord)
                    active.recordStarted = false
                    DownloadNotifications.cancel(context, notificationId)
                }
                return if (audio.spec.verifiedQuality == null) "无法确认实际音质提升，原文件未修改" else "当前可用音源暂无更优音质版本"
            }
            val extension = detectAudioExtension(temp) ?: audioExtensionFromContentType(audio.spec.mimeType)
                ?: error("无法识别实际音频格式，原文件未修改")
            AudioCacheStore.rememberExtension(context, input.resourceKey, extension)
            audio.spec.verifiedQuality?.let { quality ->
                audioResourceId(input.resourceKey)?.let { resource ->
                    SourceResolver.confirmQuality(resource, quality)
                    AudioCacheStore.recordObservedQuality(context, song.uid, resource, quality)
                }
            }
            val note = audio.spec.verifiedQuality?.let { downloadQualityNote(request.quality, it) } ?: "（实际音质尚未识别）"
            if (DownloadMetadataWriter.supports(extension)) {
                val fallback = if (shouldLookupDownloadMetadata(input.completeCacheHit, song)) {
                    recoverableOrNull { SourceResolver.matchOnline(context, song) }
                } else null
                val cover = if (request.embedCover) recoverableOrNull { fetchCover(context, recordId, song) } else null
                val lyric = if (request.embedLyric) recoverableOrNull {
                    val lyricSong = if (input.sourceIdentity == song.uid) song else {
                        recoverableOrNull {
                            SourceResolver.findAlternatives(context, song)
                                .firstOrNull { it.uid == input.sourceIdentity }
                        }
                    }
                    val exactLyrics = lyricSong?.let { matchedSong ->
                        recoverableOrNull {
                            OnlineRepository.lyric(context, matchedSong.source, matchedSong)
                        }?.takeIf { it.hasLyrics }
                    }
                    exactLyrics?.let { matched ->
                        embeddedLyricsForDownload(matched, input.sourceIdentity)
                    }
                } else null
                recoverableOrNull {
                    DownloadMetadataWriter.write(temp, extension, song.name,
                        song.singer.ifBlank { fallback?.singer.orEmpty() }, song.albumName.ifBlank { fallback?.albumName.orEmpty() },
                        cover, lyric, year = song.year ?: fallback?.year)
                }
            }
            // 同一目录共用发布锁，避免截短文件名或跨平台并发冲突；网络下载仍并行。
            return DownloadTargetLocks.withLock(targetLockKey(request.path, "")) {
                val current = downloadTargets(context, request.path, baseName)
                if (request.skipExisting) {
                    current.asSequence().mapNotNull { initialByTarget[it] ?: inspect(it) }
                        .firstOrNull { sameDownloadedQuality(it.audio.spec, audio.spec) }?.let {
                        return@withLock finish(it, "已跳过（实际下载音质已有同版本文件）$note")
                    }
                }
                val names = current.map { it.name }.toSet()
                val displayName = generateSequence(0) { it + 1 }.map { downloadVariantName(baseName, extension, audio.spec, it) }
                    .first { it !in names }
                ensureCurrent(recordId, token)
                val uri = temp.inputStream().use { stream ->
                    when {
                        request.path.startsWith("content://") -> saveToTree(context, request.path, displayName, stream)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> saveToMediaStore(context, displayName, stream)
                        else -> saveToPublicDir(displayName, stream)
                    }
                }
                try {
                    ensureCurrent(recordId, token)
                    val saved = VerifiedTarget(Target(displayName, uri, temp.length(), 0L), audio)
                    val label = if (input.completeCacheHit) "已从缓存导出" else "已下载"
                    finish(saved, "$label$note")
                } catch (failure: CancellationException) {
                    runCatching { deleteUri(context, uri) }
                    throw failure
                }
            }
        } finally { runCatching { temp.delete() } }
    }

    /** 每个资源独立落入临时文件；仅 HTTP upstream open/read 故障可排除资源并尝试换源。 */
    private suspend fun downloadAudioTransfer(
        context: Context,
        song: OnlineSong,
        recordId: String,
        request: DownloadRequest,
        token: Long,
        baseline: AudioSpecification?,
        tempDir: File,
        baseName: String,
        notificationId: Int,
    ): DownloadTransfer? {
        val excludedResources = linkedSetOf<String>()
        var retryDeadlineNanos: Long? = null
        while (true) {
            ensureCurrent(recordId, token)
            val temp = File.createTempFile("melora-", ".part", tempDir)
            var input: CachedAudioInput? = null
            var keepTemporaryFile = false
            try {
                val opened = openDownloadInput(
                    context, song, request, baseline, recordId, token,
                    excludedResources, retryDeadlineNanos,
                ) ?: return null
                val (downloadInput, prefix) = opened
                input = downloadInput
                val coroutine = currentCoroutineContext()
                downloadInput.stream.use { stream ->
                    ensureCurrent(recordId, token)
                    if (baseline != null) updateCurrent(recordId, token) {
                        tasks.getValue(recordId).recordStarted = true
                        DownloadCenter.start(recordId, song, "已确认更高音质，开始下载…", request.upgradeFrom)
                    }
                    updateCurrent(recordId, token) {
                        DownloadNotifications.progress(context, notificationId, baseName, null)
                    }
                    temp.outputStream().use { output ->
                        copyWithProgress(
                            input = if (prefix.isEmpty()) stream else SequenceInputStream(ByteArrayInputStream(prefix), stream),
                            output = output,
                            totalBytes = downloadInput.contentLength,
                            checkActive = {
                                coroutine.ensureActive()
                                ensureCurrent(recordId, token)
                            },
                        ) { percent ->
                            updateCurrent(recordId, token) {
                                DownloadNotifications.progress(context, notificationId, baseName, percent)
                                DownloadCenter.progress(recordId, percent)
                            }
                        }
                    }
                }
                ensureCurrent(recordId, token)
                check(temp.length() > 0 &&
                    (downloadInput.contentLength == null || downloadInput.contentLength <= 0 || temp.length() == downloadInput.contentLength)) {
                    "音频下载不完整，原文件未修改"
                }
                keepTemporaryFile = true
                return DownloadTransfer(temp, downloadInput)
            } catch (failure: Throwable) {
                val transferFailure = findDownloadHttpTransferFailure(failure) ?: throw failure
                currentCoroutineContext().ensureActive()
                ensureCurrent(recordId, token)
                val now = System.nanoTime()
                val deadline = retryDeadlineNanos ?: (now + TimeUnit.MILLISECONDS.toNanos(DOWNLOAD_RETRY_WINDOW_MS))
                    .also { retryDeadlineNanos = it }
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - now).coerceAtLeast(0L)
                val isNewResource = transferFailure.resourceId !in excludedResources
                val attemptedResources = excludedResources.size + if (isNewResource) 1 else 0
                if (!isNewResource || !shouldRetryDownloadTransfer(
                        request.autoSwitch,
                        NetworkState.isConnected(context),
                        attemptedResources,
                        remaining,
                    )) {
                    throw failure
                }
                excludedResources += transferFailure.resourceId
                updateCurrent(recordId, token) {
                    // 升级尚在探测阶段时，不能把旧的已完成记录进度改成0或提前显示下载通知。
                    if (tasks.getValue(recordId).recordStarted) {
                        DownloadCenter.progress(recordId, 0)
                        DownloadNotifications.progress(context, notificationId, baseName, null)
                    }
                }
            } finally {
                if (!keepTemporaryFile) {
                    runCatching { input?.stream?.close() }
                    runCatching { temp.delete() }
                }
            }
        }
    }

    /** 一次查询当前下载目录的文件名/统计信息，不递归扫描本地库，不反复listFiles。 */
    private fun downloadTargets(context: Context, path: String, baseName: String): List<Target> {
        fun matches(name: String) = matchesCurrentDownloadFileName(name, baseName)
        if (path.startsWith("content://")) {
            val tree = path.toUri()
            val root = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            return context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1).orEmpty()
                        if (matches(name)) add(Target(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                            cursor.getLong(2), cursor.getLong(3)))
                    }
                }
            } ?: error("无法读取下载目录")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            return context.contentResolver.query(collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED),
                "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.IS_PENDING}=0",
                arrayOf("${Environment.DIRECTORY_MUSIC}/Melora/"), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1).orEmpty()
                        if (matches(name)) add(Target(name, ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getLong(2), cursor.getLong(3) * 1000))
                    }
                }
            }.orEmpty()
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melora")
        return directory.listFiles()?.filter { it.isFile && matches(it.name) }
            ?.map { Target(it.name, Uri.fromFile(it), it.length(), it.lastModified()) }.orEmpty()
    }

    /** 真实下载文件优先于缓存/网络；旧记录只在定位成功后补齐URI。必须在IO线程调用。 */
    internal fun downloadedUri(context: Context, uid: String): Uri? {
        val record = DownloadCenter.saved(uid) ?: return null
        val saved = record.savedUri?.toUri()
        if (saved != null && isReadable(context, saved)) return saved

        // URI 可能因用户改目录而失效，仍先按历史文件名寻找；两者都不存在才确认资源已失效。
        val name = record.fileName
        val path = MeloraSettings.downloadPath.value
        val found = name?.let {
            findSavedUri(context, it, path)
                ?: if (path.startsWith("content://")) findSavedUri(context, it, "") else null
        }
        // 找不到不等于资源已删除：SAF 授权暂失或外置存储未挂载时必须保留旧地址，等待恢复后重试。
        if (found == null) return null
        val identity = record.song ?: return null
        val measured = inspectDownloadAudio(context, found) ?: return null
        // 旧地址失效后，同名不再足以证明是原歌曲；绝不把另一首文件接到旧记录上。
        if (!measured.matches(identity, trustedIdentity = false)) return null
        DownloadCenter.rememberSavedUri(uid, found.toString(), measured.spec)
        return found
    }

    /** 只读有界前缀；命中后复用同一条流与已读字节，不二次解析或重新下载。 */
    private suspend fun openDownloadInput(
        context: Context, song: OnlineSong, request: DownloadRequest, baseline: AudioSpecification?,
        recordId: String, token: Long, excludedResources: Set<String>, resolveDeadlineNanos: Long?,
    ): Pair<CachedAudioInput, ByteArray>? {
        repeat(minOf(if (baseline == null) 1 else 3, MAX_DOWNLOAD_TRANSFER_RESOURCES - excludedResources.size)) {
            val resolveTimeoutMs = resolveDeadlineNanos?.let { deadline ->
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                check(remaining > 0L) { "自动换源解析超时，已停止重试" }
                remaining
            }
            val input = AudioCacheStore.openForDownload(
                context, song, request.quality, request.autoSwitch,
                excludedResources = excludedResources,
                resolveTimeoutMs = resolveTimeoutMs,
            )
            try {
                ensureCurrent(recordId, token)
                if (baseline == null) return input to byteArrayOf()
                val probe = probeDownloadUpgrade(input.stream)
                currentCoroutineContext().ensureActive()
                ensureCurrent(recordId, token)
                val actual = probe.spec ?: error("无法确认音源的实际音质，未开始下载")
                actual.verifiedQuality?.let { quality ->
                    audioResourceId(input.resourceKey)?.let { resource ->
                        SourceResolver.confirmQuality(resource, quality)
                        AudioCacheStore.recordObservedQuality(context, song.uid, resource, quality)
                    }
                }
                when (isBetterDownloadQuality(actual, baseline)) {
                    true -> return input to probe.prefix
                    null -> error("无法确认音源的实际音质，未开始下载")
                    false -> {
                        input.stream.close()
                        // 标称HR却返回低档时，纠正该资源后再让原解析器尝试同档其它资源；不污染全局失败名单。
                        if (SourceResolver.qualityRank(input.actualQuality) <= SourceResolver.qualityRank(baseline.verifiedQuality.orEmpty())) return null
                    }
                }
            } catch (failure: Exception) {
                runCatching { input.stream.close() }
                throw failure
            }
        }
        error("音源标注与实际音质不一致，暂未确认更优版本")
    }

    /** 以已保存记录的真实地址删除，不受用户后来更改下载目录影响。 */
    suspend fun deleteSaved(context: Context, record: DownloadCenter.Record): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            DownloadTargetLocks.withLock("song:${record.id}") {
                // 绑定用户点选的文件快照，不能在升级完成后误删同ID刚保存的新文件。
                val uri = record.savedUri?.toUri() ?: record.fileName?.let { name ->
                    findSavedUri(context, name)?.takeIf { candidate ->
                        val identity = record.song ?: return@takeIf false
                        inspectDownloadAudio(context, candidate)?.matches(identity, trustedIdentity = false) == true
                    }
                } ?: error("无法确认待删除的本地文件，原文件未修改")
                val deleted = deleteUri(context, uri)
                check(deleted) { "删除失败，请检查文件或目录权限" }
                // 旧格式没有URI时，仅在当前记录仍是同一快照时清理；新记录严格按URI绑定。
                if (record.savedUri != null || DownloadCenter.saved(record.id) == record) {
                    DownloadCenter.clearSaved(record.id, "本地文件已删除", expectedUri = record.savedUri)
                }
                PlaybackController.onLocalFilesDeleted(context, emptySet(), setOf(uri.toString())).join()
                "已从本地删除：${record.fileName ?: record.name}"
            }
        }
    }

    private fun deleteUri(context: Context, uri: Uri): Boolean = when {
        uri.scheme == "file" -> File(requireNotNull(uri.path)).delete()
        uri.authority == MediaStore.AUTHORITY -> context.contentResolver.delete(uri, null, null) > 0
        else -> DocumentFile.fromSingleUri(context, uri)?.delete() == true
    }

    private fun buildBaseName(song: OnlineSong, nameFormat: String): String {
        val raw = when (nameFormat) {
            "artist-song" -> "${song.singer} - ${song.name}"
            "song" -> song.name
            else -> "${song.name} - ${song.singer}"
        }
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "未知歌曲" }
    }

    /** 查找已发布且可读取的真实文件；与播放、去重和旧记录迁移共用。 */
    internal fun findSavedUri(
        context: Context,
        displayName: String,
        customPath: String = MeloraSettings.downloadPath.value,
    ): Uri? {
        if (displayName.isBlank() || displayName != File(displayName).name) return null
        return runCatching {
            when {
                customPath.startsWith("content://") -> {
                    val target = DocumentFile.fromTreeUri(context, customPath.toUri())?.findFile(displayName)
                    target?.takeIf { it.isFile }?.uri?.takeIf { isReadable(context, it) }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.Audio.Media._ID),
                        "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.IS_PENDING}=0",
                        arrayOf(displayName, "${Environment.DIRECTORY_MUSIC}/Melora/"),
                        null,
                    )?.use { cursor ->
                        var found: Uri? = null
                        while (found == null && cursor.moveToNext()) {
                            val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                            if (isReadable(context, uri)) found = uri
                        }
                        found
                    }
                }
                else -> {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melora")
                    File(dir, displayName).takeIf(::isNonEmptyRegularFile)?.let(Uri::fromFile)
                }
            }
        }.getOrNull()
    }

    private fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
    }.getOrDefault(false)

    private suspend fun fetchCover(context: Context, recordId: String, song: OnlineSong): ByteArray? {
        val url = CoverLoader.resolve(context, song) ?: return null
        DownloadCenter.updateArtwork(recordId, url)
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            if (body.contentLength() > MAX_EMBEDDED_COVER_BYTES) return@use null
            val bytes = body.byteStream().use { it.readBoundedBytes(MAX_EMBEDDED_COVER_BYTES) } ?: return@use null
            if (bytes.size < 200) null else bytes
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, displayName: String, input: InputStream): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(displayName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Melora")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("无法写入系统媒体库")
        try {
            resolver.openOutputStream(uri, "w")?.use { copyNonEmpty(input, it) }
                ?: error("无法写入系统媒体库")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "无法发布系统媒体库文件" }
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    // 下载只发布新名字，旧音频不参与替换/删除；provider不支持rename时保留原文件并报错。
    private fun saveToTree(context: Context, treeUri: String, displayName: String, input: InputStream): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: error("无法访问下载目录")
        check(tree.findFile(displayName) == null) { "目标名称已被占用，原文件未修改，请重试" }
        val staged = tree.createFile(mimeOf(displayName), ".melora-${UUID.randomUUID()}.part") ?: error("无法创建下载临时文件")
        var published = false
        try {
            context.contentResolver.openOutputStream(staged.uri, "w")?.use { copyNonEmpty(input, it) } ?: error("无法写入下载目录")
            check(tree.findFile(displayName) == null && staged.renameTo(displayName)) { "无法安全发布，原文件未修改" }
            published = true
            return staged.uri
        } finally { if (!published) runCatching { staged.delete() } }
    }

    private fun saveToPublicDir(displayName: String, input: InputStream): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melora")
        check(dir.isDirectory || dir.mkdirs()) { "无法创建下载目录" }
        val target = File(dir, displayName)
        check(!target.exists()) { "目标名称已被占用，原文件未修改，请重试" }
        publishFileAtomically(target, input)
        return Uri.fromFile(target)
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".flac") -> "audio/flac"
        name.endsWith(".m4a") -> "audio/mp4"
        name.endsWith(".wav") -> "audio/wav"
        name.endsWith(".ape") -> "audio/ape"
        name.endsWith(".ogg") -> "audio/ogg"
        name.endsWith(".aac") -> "audio/aac"
        else -> "audio/mpeg"
    }
}

internal object DownloadTargetLocks {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }
}

private fun targetLockKey(customPath: String, displayName: String): String =
    "$customPath\u0000$displayName"

/** 在目标同目录完整写入后用原子 move 发布；失败时原文件保持不变。 */
internal fun publishFileAtomically(
    target: File,
    input: InputStream,
) {
    val parent = target.parentFile ?: error("无法确定下载目录")
    check(parent.isDirectory || parent.mkdirs()) { "无法创建下载目录" }
    val staged = File.createTempFile(".melora-", ".part", parent)
    try {
        staged.outputStream().use { copyNonEmpty(input, it) }
        moveFileAtomically(staged, target)
    } finally {
        runCatching { if (staged.exists()) staged.delete() }
    }
}

private fun moveFileAtomically(staged: File, target: File) {
    try {
        Files.move(
            staged.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (error: IOException) {
        throw IllegalStateException("无法安全发布下载文件，原文件未修改", error)
    }
}

/** 下载系统通知：进度常驻通知 + 完成/失败终态。通知权限未授予时静默跳过。 */
private object DownloadNotifications {
    private const val CHANNEL_ID = "melora-downloads"

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "歌曲下载", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "下载进度与完成提醒"
                    setShowBadge(false)
                },
            )
        }
    }

    fun progress(context: Context, id: Int, displayName: String, percent: Int?) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(displayName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        notify(context, id, builder.build())
    }

    fun done(context: Context, id: Int, displayName: String) {
        ensureChannel(context)
        notify(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成")
                .setContentText(displayName)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .build(),
        )
    }

    fun failed(context: Context, id: Int, message: String) {
        ensureChannel(context)
        notify(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("下载失败")
                .setContentText(message)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(id)
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}

/** MP3 标签写入：保留未管理的既有帧，仅更新标题/艺术家/专辑/年份/封面/歌词。 */
internal object Id3Writer {
    private const val MAX_TAG_SIZE = 32 * 1024 * 1024
    private val frameIdPattern = Regex("[A-Z0-9]{4}")

    private data class Frame(val id: String, val flags: ByteArray, val payload: ByteArray)
    private data class ExistingTag(val major: Int, val audioOffset: Long, val frames: List<Frame>)

    fun write(
        file: File,
        title: String,
        artist: String,
        album: String,
        cover: ByteArray?,
        lyric: EmbeddedLyrics?,
        year: Int? = null,
    ) {
        val existing = readExistingTag(file)
        val validYear = year?.takeIf { it in 1900..2100 }
        val usableCover = cover?.takeIf { it.isNotEmpty() }
        val usableLyric = lyric?.plain?.takeIf { it.isNotBlank() }
        val usableTtml = lyric?.ttml?.takeIf { it.isNotBlank() }
        val replacedFrameIds = buildSet {
            if (title.isNotBlank()) add("TIT2")
            if (artist.isNotBlank()) add("TPE1")
            if (album.isNotBlank()) add("TALB")
            if (validYear != null) addAll(listOf("TYER", "TDRC"))
            if (usableCover != null) add("APIC")
            if (lyric != null) add("USLT")
        }
        val frames = existing.frames.filterNot { frame ->
            frame.id in replacedFrameIds ||
                (lyric != null && frame.id == "TXXX" && txxxDescription(frame.payload)?.equals(EmbeddedLyrics.TTML_FIELD, ignoreCase = true) == true)
        }.toMutableList()
        addText(frames, "TIT2", title)
        addText(frames, "TPE1", artist)
        addText(frames, "TALB", album)
        validYear?.let { addText(frames, if (existing.major >= 4) "TDRC" else "TYER", it.toString()) }
        usableCover?.let { frames += Frame("APIC", ZERO_FLAGS, apicPayload(it)) }
        usableLyric?.let { frames += Frame("USLT", ZERO_FLAGS, usltPayload(it)) }
        usableTtml?.let { frames += Frame("TXXX", ZERO_FLAGS, txxxPayload(EmbeddedLyrics.TTML_FIELD, it)) }
        if (frames.isEmpty()) return

        val tag = buildTag(frames, existing.major)
        val temp = File(file.parentFile, file.name + ".tag")
        try {
            file.inputStream().use { input ->
                check(existing.audioOffset <= file.length()) { "原有 ID3 标签长度无效，音频文件未修改" }
                input.channel.position(existing.audioOffset)
                temp.outputStream().use { output ->
                    output.write(tag)
                    input.copyTo(output, AUDIO_TRANSFER_BUFFER_BYTES)
                }
            }
            moveFileAtomically(temp, file)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    private fun readExistingTag(file: File): ExistingTag {
        if (file.length() < 10L) return ExistingTag(3, 0L, emptyList())
        return file.inputStream().buffered().use { input ->
            val header = input.readNBytesCompat(10)
            if (header.size < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                return@use ExistingTag(3, 0L, emptyList())
            }
            val major = header[3].toInt() and 0xFF
            require(major == 3 || major == 4) { "暂不支持 ID3v2.$major，音频文件未修改" }
            val headerFlags = header[5].toInt() and 0xFF
            require(headerFlags and 0x80 == 0) { "暂不支持 ID3 全局去同步，音频文件未修改" }
            val size = syncSafeSize(header)
            require(size in 0..MAX_TAG_SIZE && 10L + size <= file.length()) { "原有 ID3 标签长度无效，音频文件未修改" }
            val body = input.readNBytesCompat(size)
            require(body.size == size) { "原有 ID3 标签不完整，音频文件未修改" }
            val start = extendedHeaderSize(body, major, headerFlags)
            val frames = parseFrames(body, start, major)
            val footer = if (major == 4 && headerFlags and 0x10 != 0) 10L else 0L
            val audioOffset = 10L + size + footer
            require(audioOffset <= file.length()) { "原有 ID3 标签长度无效，音频文件未修改" }
            ExistingTag(major, audioOffset, frames)
        }
    }

    private fun extendedHeaderSize(body: ByteArray, major: Int, headerFlags: Int): Int {
        if (headerFlags and 0x40 == 0) return 0
        require(body.size >= 4) { "ID3 扩展头损坏，音频文件未修改" }
        val declared = if (major >= 4) syncSafe(body, 0) else beInt(body, 0)
        val total = if (major >= 4) declared else 4 + declared
        require(total in 4..body.size) { "ID3 扩展头长度无效，音频文件未修改" }
        return total
    }

    private fun parseFrames(body: ByteArray, start: Int, major: Int): List<Frame> {
        val result = mutableListOf<Frame>()
        var offset = start
        while (offset + 10 <= body.size) {
            val idBytes = body.copyOfRange(offset, offset + 4)
            if (idBytes.all { it == 0.toByte() }) break
            val id = String(idBytes, Charsets.US_ASCII)
            require(frameIdPattern.matches(id)) { "ID3 帧标识无效，音频文件未修改" }
            val size = if (major >= 4) syncSafe(body, offset + 4) else beInt(body, offset + 4)
            require(size >= 0 && offset + 10L + size <= body.size) { "ID3 帧长度无效，音频文件未修改" }
            result += Frame(
                id = id,
                flags = body.copyOfRange(offset + 8, offset + 10),
                payload = body.copyOfRange(offset + 10, offset + 10 + size),
            )
            offset += 10 + size
        }
        return result
    }

    private fun addText(frames: MutableList<Frame>, id: String, value: String) {
        if (value.isBlank()) return
        frames += Frame(
            id = id,
            flags = ZERO_FLAGS,
            payload = byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE),
        )
    }

    private fun txxxDescription(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val descriptionStart = when (encoding) {
            0, 3 -> 1
            1 -> 3 // UTF-16 with BOM
            2 -> 1 // UTF-16BE
            else -> return null
        }
        val delimiter = when (encoding) {
            0, 3 -> (descriptionStart until payload.size).firstOrNull { payload[it] == 0.toByte() }
            else -> (descriptionStart until payload.size - 1 step 2).firstOrNull {
                payload[it] == 0.toByte() && payload[it + 1] == 0.toByte()
            }
        } ?: return null
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (payload[1] == 0xFF.toByte() && payload[2] == 0xFE.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(payload, descriptionStart, delimiter - descriptionStart, charset)
    }

    private fun txxxPayload(description: String, value: String): ByteArray =
        java.io.ByteArrayOutputStream().apply {
            write(1) // UTF-16 with BOM
            write(0xFF)
            write(0xFE)
            write(description.toByteArray(Charsets.UTF_16LE))
            write(0)
            write(0)
            write(0xFF)
            write(0xFE)
            write(value.toByteArray(Charsets.UTF_16LE))
        }.toByteArray()

    private fun apicPayload(cover: ByteArray): ByteArray {
        val isPng = cover.size > 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()
        val mime = if (isPng) "image/png" else "image/jpeg"
        val out = java.io.ByteArrayOutputStream()
        out.write(0) // ISO-8859-1 编码（mime/描述均 ASCII）
        out.write(mime.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(3) // front cover
        out.write(0) // 空描述
        out.write(cover)
        return out.toByteArray()
    }

    private fun usltPayload(lyric: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(1) // UTF-16 带 BOM
        out.write("xxx".toByteArray(Charsets.US_ASCII)) // 语言
        out.write(0xFF)
        out.write(0xFE) // 内容描述：空
        out.write(0x00)
        out.write(0x00)
        out.write(0xFF)
        out.write(0xFE)
        out.write(lyric.toByteArray(Charsets.UTF_16LE))
        return out.toByteArray()
    }

    private fun buildTag(frames: List<Frame>, major: Int): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        frames.forEach { frame ->
            body.write(frame.id.toByteArray(Charsets.US_ASCII))
            body.write(if (major >= 4) syncSafe(frame.payload.size) else be32(frame.payload.size))
            body.write(frame.flags)
            body.write(frame.payload)
        }
        val content = body.toByteArray()
        require(content.size <= MAX_TAG_SIZE) { "ID3 标签过大，音频文件未修改" }
        return java.io.ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(major)
            write(0)
            write(0)
            write(syncSafe(content.size))
            write(content)
        }.toByteArray()
    }

    private fun InputStream.readNBytesCompat(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream(size)
        val buffer = ByteArray(minOf(DEFAULT_BUFFER_SIZE, size))
        var remaining = size
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun syncSafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun syncSafeSize(header: ByteArray): Int = syncSafe(header, 6)

    private val ZERO_FLAGS = byteArrayOf(0, 0)
}

internal const val MAX_EMBEDDED_COVER_BYTES = 8 * 1024 * 1024

internal fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray? {
    if (maxBytes <= 0) return null
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private val DOWNLOAD_FILE_EXTENSIONS = setOf(
    ".flac",
    ".m4a",
    ".wav",
    ".ape",
    ".ogg",
    ".aac",
    ".mp3",
)

/** 批量入口只按 OnlineSong.uid 去重；同 uid 的请求仍走 Downloader 的 singleflight。 */
internal fun distinctDownloadSongs(songs: List<OnlineSong>): List<OnlineSong> =
    songs.distinctBy(OnlineSong::uid)

/** 仅接受当前命名规则生成的单文件名，避免历史记录的其它命名格式误判。 */
internal fun matchesCurrentDownloadFileName(fileName: String?, baseName: String): Boolean {
    val name = fileName?.takeIf { it.isNotBlank() } ?: return false
    if (name.contains('/') || name.contains('\\')) return false
    val extensionStart = name.lastIndexOf('.')
    if (extensionStart <= 0 || name.substring(extensionStart).lowercase() !in DOWNLOAD_FILE_EXTENSIONS) return false
    val stem = name.substring(0, extensionStart)
    if (stem == baseName) return true
    val prefix = Regex.escape(baseName.take(70))
    return Regex("""$prefix \[(?:HR|SQ|HQ|MASTER|[0-9]+K|音质待识别)\](?: \([0-9]+\))?""").matches(stem)
}

internal fun isNonEmptyRegularFile(file: File): Boolean = file.isFile && file.length() > 0L

internal fun downloadQualityNote(preferred: String, actual: String): String = when {
    preferred == actual -> ""
    SourceResolver.qualitySatisfies(preferred, actual) -> "（实际音质：$actual）"
    else -> "（$preferred 不可用，已使用 $actual）"
}

internal fun shouldLookupDownloadMetadata(completeCacheHit: Boolean, song: OnlineSong): Boolean =
    !completeCacheHit && (song.albumName.isBlank() || song.singer.isBlank())

internal class DynamicDownloadGate(
    private val limit: () -> Int,
    private val retryDelayMs: Long = 25L,
) {
    private val mutex = Mutex()
    private var active = 0

    suspend fun acquire() {
        while (true) {
            val acquired = mutex.withLock {
                if (active >= limit().coerceAtLeast(1)) false else {
                    active++
                    true
                }
            }
            if (acquired) return
            delay(retryDelayMs)
        }
    }

    suspend fun release() = mutex.withLock {
        check(active > 0) { "下载并发许可重复释放" }
        active--
    }
}

internal fun detectAudioExtension(file: File): String? {
    val header = ByteArray(12)
    val count = file.inputStream().use { it.read(header) }
    if (count < 3) return null
    fun ascii(start: Int, end: Int): String = header.copyOfRange(start, end).toString(Charsets.US_ASCII)
    return when {
        count >= 4 && ascii(0, 4) == "fLaC" -> ".flac"
        ascii(0, 3) == "ID3" -> ".mp3"
        (header[0].toInt() and 0xff) == 0xff &&
            (header[1].toInt() and 0xe0) == 0xe0 &&
            (header[1].toInt() and 0x06) == 0 -> ".aac"
        (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0 -> ".mp3"
        count >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 12) == "WAVE" -> ".wav"
        count >= 4 && ascii(0, 4) == "OggS" -> ".ogg"
        count >= 8 && ascii(4, 8) == "ftyp" -> ".m4a"
        count >= 4 && ascii(0, 4) == "MAC " -> ".ape"
        else -> null
    }
}

internal fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long?,
    checkActive: () -> Unit = {},
    onProgress: (Int) -> Unit,
): Long {
    var copied = 0L
    var lastPercent = -1
    val buffer = ByteArray(AUDIO_TRANSFER_BUFFER_BYTES)
    while (true) {
        checkActive()
        val read = input.read(buffer)
        if (read < 0) break
        checkActive()
        output.write(buffer, 0, read)
        copied += read
        val total = totalBytes?.takeIf { it > 0L } ?: continue
        val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
        if (percent != lastPercent && percent % 2 == 0) {
            lastPercent = percent
            onProgress(percent)
        }
    }
    require(copied > 0L) { "下载失败：文件为空" }
    return copied
}

internal fun copyNonEmpty(input: InputStream, output: OutputStream): Long =
    input.copyTo(output, AUDIO_TRANSFER_BUFFER_BYTES).also { require(it > 0) { "下载失败：文件为空" } }
