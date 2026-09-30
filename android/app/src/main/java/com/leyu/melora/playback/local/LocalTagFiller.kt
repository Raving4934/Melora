package com.leyu.melora.playback.local

import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.leyu.melora.playback.DownloadMetadataWriter
import com.leyu.melora.playback.EmbeddedLyrics
import com.leyu.melora.playback.LyricParser
import com.leyu.melora.playback.MAX_EMBEDDED_COVER_BYTES
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerLyric
import com.leyu.melora.playback.TrackRegistry
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.readBoundedBytes
import com.leyu.melora.playback.recoverableOrNull
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/** 待系统确认的媒体文件标签写入。只暴露启动授权所需数据，写入内容继续封装在本地补全器中。 */
internal data class LocalTagWriteAuthorization(
    val id: Long,
    val songTitle: String,
    val request: IntentSenderRequest,
)

/**
 * 自动补全本地文件缺失信息，并承接显式歌词写入。
 * 应用内信息立即更新；物理标签必须等当前曲目离开播放器后再写，避免与 Media3 打开同一文件竞态。
 */
object LocalTagFiller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pendingWrites = ConcurrentHashMap<String, PendingTagWrite>()
    private val manualWriteIds = ConcurrentHashMap.newKeySet<String>()
    private val authorizationDeclined = ConcurrentHashMap.newKeySet<String>()
    private val writeFailureNotified = ConcurrentHashMap.newKeySet<String>()
    private val authorizationLock = Any()
    private val _writeAuthorization = MutableStateFlow<LocalTagWriteAuthorization?>(null)
    internal val writeAuthorization: StateFlow<LocalTagWriteAuthorization?> = _writeAuthorization.asStateFlow()
    private var authorizationSequence = 0L
    private var awaitingAuthorization: PendingTagWrite? = null
    /** 在线匹配失败后的冷却，避免每次播放都联网重试。 */
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private const val TAG = "LocalTagFiller"
    private const val RETRY_COOLDOWN_MS = 30 * 60 * 1000L  // 30 min
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun consider(context: Context, track: UiTrack?) {
        val app = context.applicationContext
        val activeLocal = track?.let(LocalMediaStore::matchTrack)
        if (!MeloraSettings.localAutoFillInfo.value) {
            clearAutomaticRequests()
            scope.launch { flushPendingWrites(app, activeLocal?.id, manualOnly = true) }
            return
        }
        scope.launch { flushPendingWrites(app, activeLocal?.id) }
        if (track == null) return
        if (OnlineSong.from(track.raw)?.isBookChapter == true) return
        val local = activeLocal ?: return
        if (!shouldFillLocalInfo(local) || !canFill(local.id)) return
        val cooldown = retryAfter[local.id]
        if (cooldown != null && System.currentTimeMillis() < cooldown) return
        if (pendingWrites.containsKey(local.id) || !inFlight.add(local.id)) return
        scope.launch {
            try {
                fill(app, track.uid, local)
            } finally {
                inFlight.remove(local.id)
            }
        }
    }

    /** 显式歌词写入不查网络、不依赖自动补全开关；实际写盘仍复用授权与排他队列。 */
    fun requestLyricWrite(context: Context, track: UiTrack, lyric: PlayerLyric): String {
        val embedded = EmbeddedLyrics.fromLines(lyric.lines)
        val song = LocalMediaStore.matchTrack(track)
        manualLyricWriteRejection(track.uid, lyric.uid, embedded, song)?.let { return it }
        val acceptedLyrics = checkNotNull(embedded)
        val acceptedSong = checkNotNull(song)
        val request = PendingTagWrite(
            local = acceptedSong,
            matched = null,
            gaps = LocalInfoGaps(artist = false, album = false, cover = false, lyric = true, year = false),
            coverBytes = null,
            lyric = acceptedLyrics,
            resolved = false,
            manual = true,
        )
        synchronized(authorizationLock) {
            manualWriteIds += acceptedSong.id
            pendingWrites[acceptedSong.id] = request
            authorizationDeclined.remove(acceptedSong.id)
            writeFailureNotified.remove(acceptedSong.id)
        }
        if (!isCurrentLocal(acceptedSong)) {
            val app = context.applicationContext
            scope.launch { flushPendingWrites(app, currentLocalId()) }
        }
        return if (isCurrentLocal(acceptedSong)) {
            "歌词写入已排队；切换曲目或清空播放队列后写入文件"
        } else {
            "歌词写入已排队；完成后会提示结果"
        }
    }

    private fun clearAutomaticRequests() {
        pendingWrites.entries.removeIf { !it.value.manual }
        authorizationDeclined.removeIf { !hasManualWrite(it) }
        writeFailureNotified.removeIf { !hasManualWrite(it) }
        synchronized(authorizationLock) {
            if (awaitingAuthorization?.manual != true) {
                awaitingAuthorization = null
                _writeAuthorization.value = null
            }
        }
    }

    private suspend fun fill(context: Context, trackUid: String, original: LocalSong) {
        val local = LocalMediaStore.find(original.id) ?: original
        if (!canFill(local.id) || !shouldFillLocalInfo(local)) return
        val existingCover = LocalTagReader.bestCoverUri(context, local)
        val existingLyric = LocalTagReader.embeddedLyrics(context, local.uri, local.mimeType)
        val gaps = localInfoGaps(local, existingCover != null, existingLyric?.isBlank == false)
        if (!gaps.any) {
            LocalMediaStore.updateMetadata(local, local.copy(infoFilled = true))
            return
        }
        val matched = try {
            SourceResolver.matchOnline(context, local.toOnlineSong(), background = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (!canFill(local.id)) return
        if (matched == null) {
            retryAfter[local.id] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            // 在线匹配失败仍保留已提取的内嵌封面
            if (existingCover != null && local.coverUri.isNullOrBlank()) {
                val patched = local.copy(coverUri = existingCover)
                if (LocalMediaStore.updateMetadata(local, patched) != null) TrackRegistry.updateArtwork(trackUid, existingCover)
            }
            return
        }

        val coverBytes = if (gaps.cover) recoverableOrNull { fetchCover(context, matched) } else null
        val lyric = if (gaps.lyric) {
            recoverableOrNull {
                SourceResolver.lyric(context, matched, background = true)?.let { source ->
                    EmbeddedLyrics.fromLines(
                        LyricParser.parse(
                            raw = source.lyric,
                            translation = source.tlyric,
                            romanization = source.rlyric,
                            wordByWord = source.lxlyric,
                        ),
                    )
                }
            }?.takeUnless { it.isBlank }
        } else {
            existingLyric
        }
        if (!canFill(local.id)) return
        val coverUri = when {
            existingCover != null -> existingCover
            coverBytes != null -> LocalTagReader.cacheCover(context, local, coverBytes)
            else -> null
        }
        val updated = LocalMediaStore.updateMetadata(local,
            mergeLocalInfo(local, matched, coverUri).copy(infoFilled = false)) ?: return
        coverUri?.let { TrackRegistry.updateArtwork(trackUid, it) }

        val resolved = !localInfoGaps(
            song = updated,
            hasCover = coverUri != null,
            hasLyric = lyric?.isBlank == false,
        ).unresolvedRequired
        val request = PendingTagWrite(updated, matched, gaps, coverBytes, lyric, resolved)
        if (!request.supported) {
            if (resolved) LocalMediaStore.updateMetadata(updated, updated.copy(infoFilled = true))
            return
        }
        if (isCurrentLocal(updated)) {
            enqueuePendingWrite(request)
            return
        }
        handleWriteOutcome(context, request, writeTagsNow(context, request))
    }

    /** Activity 的系统授权回调：授权只代表可以重试，只有第二次物理写入成功才更新索引。 */
    internal fun onWriteAuthorizationResult(context: Context, granted: Boolean) {
        val request = synchronized(authorizationLock) {
            val current = awaitingAuthorization
            awaitingAuthorization = null
            _writeAuthorization.value = null
            current
        } ?: return
        val app = context.applicationContext
        scope.launch {
            if (!granted) {
                val newerManual = synchronized(authorizationLock) {
                    val newer = pendingWrites[request.local.id]?.let { it.manual && it !== request } == true
                    if (newer) authorizationDeclined.remove(request.local.id)
                    else authorizationDeclined += request.local.id
                    pendingWrites.remove(request.local.id, request)
                    newer
                }
                clearManualWriteIdIfIdle(request.local.id)
                val message = when {
                    newerManual -> "系统未授权此前的写入；《${request.local.title}》较新的歌词请求仍排队，需单独授权"
                    request.manual -> "系统未授权，未写入《${request.local.title}》歌词"
                    else -> "系统未授权，未写入《${request.local.title}》音乐文件"
                }
                PlaybackController.postMessage(app, message)
                flushPendingWrites(app, currentLocalId())
                return@launch
            }
            if (!request.manual && !MeloraSettings.localAutoFillInfo.value) {
                pendingWrites.remove(request.local.id, request)
                PlaybackController.postMessage(app, "自动补全已关闭，未写入《${request.local.title}》文件")
                flushPendingWrites(app, currentLocalId())
                return@launch
            }
            handleWriteOutcome(app, request, writeTagsNow(app, request, allowAuthorization = false))
            flushPendingWrites(app, currentLocalId())
        }
    }

    private suspend fun flushPendingWrites(
        context: Context,
        activeLocalId: String?,
        manualOnly: Boolean = false,
    ) {
        pendingWrites.entries.toList().sortedBy { !it.value.manual }.forEach { (id, request) ->
            if ((manualOnly && !request.manual) || id == activeLocalId || isCurrentLocal(request.local)) return@forEach
            if (!pendingWrites.remove(id, request)) return@forEach
            try {
                handleWriteOutcome(context, request, writeTagsNow(context, request))
            } catch (cancelled: CancellationException) {
                if (cancelled.suppressed.none { it is ContentUriRestoreFailure }) {
                    enqueuePendingWrite(request)
                } else if (request.manual) {
                    clearManualWriteIdIfIdle(request.local.id)
                }
                throw cancelled
            }
        }
    }

    private suspend fun handleWriteOutcome(context: Context, request: PendingTagWrite, outcome: TagWriteOutcome) {
        when (outcome) {
            is TagWriteOutcome.Written -> {
                authorizationDeclined.remove(request.local.id)
                writeFailureNotified.remove(request.local.id)
                pendingWrites.remove(request.local.id, request)
                LocalMediaStore.updateMetadata(request.local,
                    outcome.song.copy(infoFilled = request.resolved || outcome.song.infoFilled))
                if (request.manual) {
                    clearManualWriteIdIfIdle(request.local.id)
                    PlaybackController.postMessage(context, "《${request.local.title}》歌词已写入本地文件")
                }
                Log.i(TAG, "标签写入成功: ${request.local.id} ${request.local.title}")
            }
            TagWriteOutcome.Deferred -> enqueuePendingWrite(request)
            is TagWriteOutcome.NeedsAuthorization -> {
                if (!requestWriteAuthorization(context, request, outcome.error)) {
                    notifyWriteFailure(context, request, outcome.error)
                }
            }
            is TagWriteOutcome.Failed -> notifyWriteFailure(context, request, outcome.error, outcome.backup)
        }
    }

    private fun canFill(id: String): Boolean = MeloraSettings.localAutoFillInfo.value &&
        !hasManualWrite(id) && id !in authorizationDeclined

    private fun shouldDeferWrite(request: PendingTagWrite): Boolean {
        val queued = pendingWrites[request.local.id]
        return isCurrentLocal(request.local) || shouldDeferTagWriteForQueuedManual(
            request.manual, queued?.manual == true, queued === request,
        ) || hasOtherAuthorization(request.local.id, request) || (!request.manual && !canFill(request.local.id))
    }

    private suspend fun writeTagsNow(
        context: Context,
        request: PendingTagWrite,
        allowAuthorization: Boolean = true,
    ): TagWriteOutcome = try {
        if (shouldDeferWrite(request)) TagWriteOutcome.Deferred else {
            val local = LocalMediaStore.find(request.local.id)
            check(request.local.sameFileVersion(local)) { "本地文件已删除或发生变化，请重新读取后再写入" }
            requireNotNull(local)
            val extension = requireNotNull(request.extension) { "不支持的音频容器" }
            val uri = local.uri.toUri()
            // file读取仍在写租约前进行，避免嵌套读锁；拿到写租约后重新核对真实文件版本。
            val prepared = if (uri.scheme == "file") request.preservingContent(context, local, local.uri) else request
            LocalMediaIoCoordinator.withExclusive(context, uri) {
                if (shouldDeferWrite(request)) TagWriteOutcome.Deferred else {
                    check(request.local.sameFileVersion(LocalMediaStore.find(local.id))) {
                        "本地文件已删除或发生变化，请重新读取后再写入"
                    }
                    when (uri.scheme) {
                        "file" -> {
                            checkStorageState(context, local)
                            val file = uri.path?.let(::File)?.takeIf { it.isFile && it.canWrite() }
                                ?: error("文件不存在或不可写")
                            writeMetadata(file, extension, local, prepared)
                            TagWriteOutcome.Written(refreshStorageState(context, local))
                        }
                        "content" -> writeContentTags(context, local, uri, extension, request, allowAuthorization)
                        else -> TagWriteOutcome.Failed(IllegalArgumentException("不支持的媒体 URI: ${uri.scheme}"))
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        TagWriteOutcome.Failed(error)
    }

    private fun writeContentTags(
        context: Context,
        local: LocalSong,
        uri: Uri,
        extension: String,
        request: PendingTagWrite,
        allowAuthorization: Boolean,
    ): TagWriteOutcome {
        val root = File(context.filesDir, "local-tag-writes")
        val transaction = try {
            // 上次恢复未获授权时保留原件；同一 URI 再次写入前必须先恢复，不能备份损坏文件。
            ContentTagWrite.recover(root, uri.toString()) { target ->
                context.contentResolver.openOutputStream(target.toUri(), "rwt") ?: error("无法恢复媒体文件")
            }
            checkStorageState(context, local)
            root.mkdirs()
            ContentTagWrite(Files.createTempDirectory(root.toPath(), "write-").toFile())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is SecurityException && allowAuthorization) return TagWriteOutcome.NeedsAuthorization(error)
            return TagWriteOutcome.Failed(error)
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                transaction.original.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: error("无法读取媒体文件")
            transaction.original.copyTo(transaction.rewritten)
            writeMetadata(transaction.rewritten, extension, local,
                request.preservingContent(context, local, transaction.original.toURI().toString()))
            transaction.overwrite(uri.toString()) {
                context.contentResolver.openOutputStream(uri, "rwt") ?: error("无法打开媒体文件写入流")
            }
            return TagWriteOutcome.Written(refreshStorageState(context, local))
        } catch (error: Throwable) {
            if (error is CancellationException) {
                if (transaction.pending) PlaybackController.postMessage(context,
                    "本地标签写入取消且原文件恢复失败；原始备份保留在 ${transaction.original.absolutePath}")
                throw error
            }
            if (transaction.pending) return TagWriteOutcome.Failed(error, transaction.original)
            if (error is SecurityException && allowAuthorization) return TagWriteOutcome.NeedsAuthorization(error)
            return TagWriteOutcome.Failed(error)
        } finally {
            transaction.close()
        }
    }

    /** 只在 IO 启动屏障内执行；无未完成事务时不读取任何音频。 */
    internal fun recoverInterruptedWrites(context: Context) {
        val root = File(context.filesDir, "local-tag-writes")
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            try {
                ContentTagWrite(directory).use { transaction ->
                    transaction.recover { target ->
                        context.contentResolver.openOutputStream(target.toUri(), "rwt")
                            ?: error("无法恢复媒体文件")
                    }
                }
            } catch (error: Exception) {
                // 失效的 SAF 授权不能封锁整个应用，更不能删除唯一原件。
                Log.e(TAG, "标签恢复待重试，原始文件保留在 ${directory.absolutePath}", error)
            }
        }
    }

    private fun PendingTagWrite.preservingContent(context: Context, local: LocalSong, readUri: String) = copy(
        coverBytes = when {
            manual -> null
            gaps.cover -> coverBytes
            else -> LocalTagReader.embeddedPicture(context, readUri) ?: readCachedCover(context, local.coverUri)
        },
        lyric = if (gaps.lyric) lyric else LocalTagReader.embeddedLyrics(context, readUri, local.mimeType),
    )

    private fun writeMetadata(
        file: File,
        extension: String,
        local: LocalSong,
        request: PendingTagWrite,
    ) {
        DownloadMetadataWriter.write(
            file = file,
            extension = extension,
            title = if (request.manual) "" else local.title.ifBlank { request.matched?.name.orEmpty() },
            artist = if (request.manual) "" else local.artist.ifBlank { request.matched?.singer.orEmpty() },
            album = if (request.manual) "" else local.album.ifBlank { request.matched?.albumName.orEmpty() },
            cover = request.coverBytes,
            lyric = request.lyric,
            year = if (request.manual) null else local.year.takeIf { it in 1900..2100 } ?: request.matched?.year,
        )
    }

    private fun requestWriteAuthorization(
        context: Context,
        request: PendingTagWrite,
        error: SecurityException,
    ): Boolean {
        if (!request.manual && hasManualWrite(request.local.id)) {
            enqueuePendingWrite(request)
            return true
        }
        val queuedManual = pendingWrites[request.local.id]?.takeIf { it.manual }
        if (shouldDeferTagWriteForQueuedManual(
                requestIsManual = request.manual,
                queuedIsManual = queuedManual?.manual == true,
                sameRequest = queuedManual === request,
            )
        ) {
            enqueuePendingWrite(request)
            return true
        }
        var created: LocalTagWriteAuthorization? = null
        synchronized(authorizationLock) {
            if (request.local.id in authorizationDeclined) return false
            if (awaitingAuthorization != null) {
                enqueuePendingWrite(request)
                return@synchronized
            }
            val intent = createWriteAuthorization(context, request.local.uri, error) ?: return false
            awaitingAuthorization = request
            created = LocalTagWriteAuthorization(
                id = ++authorizationSequence,
                songTitle = request.local.title,
                request = intent,
            )
            _writeAuthorization.value = created
        }
        if (created == null) return true // 已有系统授权流程，当前请求留在队列中。
        Log.i(TAG, "等待媒体写入授权: ${request.local.id} ${request.local.title}")
        val target = if (request.manual) "歌词" else "封面和歌词"
        PlaybackController.postMessage(context, "需要系统授权才能写入《${request.local.title}》的$target")
        return true
    }

    private fun createWriteAuthorization(
        context: Context,
        uriValue: String,
        error: SecurityException,
    ): IntentSenderRequest? {
        val uri = uriValue.toUri().takeIf { it.scheme == "content" } ?: return null
        val sender = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> MediaStore.createWriteRequest(
                    context.contentResolver,
                    listOf(uri),
                ).intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                else -> null
            }
        }.getOrNull() ?: return null
        return IntentSenderRequest.Builder(sender).build()
    }

    private fun notifyWriteFailure(
        context: Context,
        request: PendingTagWrite,
        error: Throwable,
        backup: File? = null,
    ) {
        pendingWrites.remove(request.local.id, request)
        if (request.manual) clearManualWriteIdIfIdle(request.local.id)
        Log.e(TAG, "标签写入失败: ${request.local.id} ${request.local.title}", error)
        if (writeFailureNotified.add(request.local.id)) {
            val message = when {
                backup != null -> "《${request.local.title}》写入失败且原文件恢复失败；备份保留在 ${backup.absolutePath}"
                request.manual -> "《${request.local.title}》歌词写入失败，未确认写入"
                else -> "《${request.local.title}》已补全显示，但未能写入音乐文件"
            }
            PlaybackController.postMessage(context, message)
        }
    }

    private fun hasManualWrite(id: String): Boolean =
        id in manualWriteIds || pendingWrites[id]?.manual == true

    private fun hasOtherAuthorization(id: String, request: PendingTagWrite): Boolean =
        synchronized(authorizationLock) {
            awaitingAuthorization?.let { it.local.id == id && it !== request } == true
        }

    private fun clearManualWriteIdIfIdle(id: String) {
        synchronized(authorizationLock) {
            val authorizationPending = awaitingAuthorization?.let { it.local.id == id && it.manual } == true
            if (!authorizationPending && pendingWrites[id]?.manual != true) manualWriteIds.remove(id)
        }
    }

    private fun currentLocalId(): String? = PlaybackController.state.value.current
        ?.let(LocalMediaStore::matchTrack)
        ?.id

    private fun checkStorageState(context: Context, song: LocalSong) {
        val actual = refreshStorageState(context, song)
        // 旧索引的0表示未知；Provider恢复相同原件也会改变mtime，不能将其当作媒体版本号。
        val sizeChanged = song.sizeBytes > 0L && song.sizeBytes != actual.sizeBytes
        val timeChanged = song.uri.toUri().scheme == "file" && song.modifiedAt > 0L && song.modifiedAt != actual.modifiedAt
        check(!sizeChanged && !timeChanged) { "本地文件已变化，请重新读取后再写入" }
    }

    private fun refreshStorageState(context: Context, song: LocalSong): LocalSong {
        val uri = song.uri.toUri()
        val file = writableFile(song.uri)
        if (file != null) {
            return song.copy(
                sizeBytes = file.length(),
                modifiedAt = file.lastModified().takeIf { it > 0L } ?: song.modifiedAt,
            )
        }
        if (uri.authority == MediaStore.AUTHORITY) {
            val columns = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use song
                song.copy(sizeBytes = cursor.getLong(0).takeIf { it > 0L } ?: song.sizeBytes,
                    modifiedAt = (cursor.getLong(1) * 1000L).takeIf { it > 0L } ?: song.modifiedAt)
            } ?: song
        }
        val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        return song.copy(
            sizeBytes = document?.length()?.takeIf { it > 0L } ?: song.sizeBytes,
            modifiedAt = document?.lastModified()?.takeIf { it > 0L } ?: song.modifiedAt,
        )
    }

    private suspend fun fetchCover(context: Context, song: OnlineSong): ByteArray? {
        val url = CoverLoader.resolve(context, song) ?: return null
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

    private fun readCachedCover(context: Context, value: String?): ByteArray? {
        val uri = value?.toUri() ?: return null
        return LocalMediaIoCoordinator.withRead(context, uri) {
            val input = when (uri.scheme) {
                "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
                "content" -> context.contentResolver.openInputStream(uri)
                else -> null
            } ?: return@withRead null
            input.use { it.readBoundedBytes(MAX_EMBEDDED_COVER_BYTES) }
        }
    }

    private fun isCurrentLocal(local: LocalSong): Boolean {
        val current = PlaybackController.state.value.current ?: return false
        return LocalMediaStore.matchTrack(current)?.id == local.id
    }

    private fun writableFile(uri: String): File? {
        val parsed = uri.toUri()
        return parsed.takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
            ?.takeIf { it.isFile && it.canWrite() }
    }

    private sealed interface TagWriteOutcome {
        data class Written(val song: LocalSong) : TagWriteOutcome
        data object Deferred : TagWriteOutcome
        data class NeedsAuthorization(val error: SecurityException) : TagWriteOutcome
        data class Failed(val error: Throwable, val backup: File? = null) : TagWriteOutcome
    }

    private fun enqueuePendingWrite(request: PendingTagWrite) {
        val id = request.local.id
        if (!request.manual && !canFill(id)) return
        pendingWrites.compute(id) { _, current ->
            when {
                request.manual && current?.manual == true && current !== request -> current
                request.manual -> request
                current?.manual == true || id in manualWriteIds -> current
                else -> request
            }
        }
    }

    private data class PendingTagWrite(
        val local: LocalSong,
        val matched: OnlineSong?,
        val gaps: LocalInfoGaps,
        val coverBytes: ByteArray?,
        val lyric: EmbeddedLyrics?,
        val resolved: Boolean,
        val manual: Boolean = false,
    ) {
        val extension = tagExtension(local.mimeType, local.uri)
        val supported = extension != null && DownloadMetadataWriter.supports(extension)
    }
}


internal fun shouldDeferTagWriteForQueuedManual(
    requestIsManual: Boolean,
    queuedIsManual: Boolean,
    sameRequest: Boolean,
): Boolean = queuedIsManual && (!requestIsManual || !sameRequest)

/** 一个原件、一份持久化 URI 日志；普通异常和进程中断走同一恢复路径。 */
internal class ContentTagWrite(private val directory: File) : Closeable {
    val original = File(directory, "original")
    val rewritten = File(directory, "rewritten")
    private val journal = File(directory, "pending")
    val pending: Boolean get() = journal.exists()

    fun overwrite(uri: String, openOutput: () -> OutputStream) {
        val ready = File(directory, "ready")
        ready.outputStream().use { output ->
            output.write(uri.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(ready.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val output = try {
            openOutput()
        } catch (error: Throwable) {
            // 尚未拿到写流，不立即尝试第二次写入；系统授权仍由原入口处理。
            if (error is SecurityException) {
                try { Files.delete(journal.toPath()) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
        try {
            output.use { target -> rewritten.inputStream().use { it.copyTo(target) } }
            Files.delete(journal.toPath())
        } catch (error: Throwable) {
            try {
                recover { openOutput() }
            } catch (restore: Throwable) {
                error.addSuppressed(ContentUriRestoreFailure(original, restore))
            }
            throw error
        }
    }

    fun recover(openOutput: (String) -> OutputStream) {
        if (!pending) return
        // 先打开并确认原件可读，再申请可能截断目标的写流。
        original.inputStream().use { input ->
            openOutput(journal.readText()).use { target -> input.copyTo(target) }
        }
        Files.delete(journal.toPath())
    }

    override fun close() {
        if (!pending) directory.deleteRecursively()
    }

    companion object {
        fun recover(root: File, uri: String, openOutput: (String) -> OutputStream) {
            root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
                val transaction = ContentTagWrite(directory)
                if (transaction.pending && transaction.journal.readText() == uri) {
                    transaction.use { it.recover(openOutput) }
                }
            }
        }
    }
}

internal class ContentUriRestoreFailure(val backup: File, cause: Throwable) :
    IOException("Unable to restore content URI; original backup retained at ${backup.absolutePath}", cause)

internal fun manualLyricWriteRejection(
    trackUid: String,
    lyricUid: String,
    lyric: EmbeddedLyrics?,
    local: LocalSong?,
): String? = when {
    trackUid != lyricUid -> "歌词与当前歌曲不匹配，未写入"
    lyric == null || lyric.isBlank -> "没有可写入的歌词"
    local == null -> "仅支持写入本地 MP3/FLAC 文件"
    tagExtension(local.mimeType, local.uri)?.let { DownloadMetadataWriter.supports(it) } != true ->
        "仅支持写入本地 MP3/FLAC 文件"
    else -> null
}

internal fun shouldFillLocalInfo(song: LocalSong): Boolean =
    !song.infoFilled || song.coverUri.isNullOrBlank()

internal data class LocalInfoGaps(
    val artist: Boolean,
    val album: Boolean,
    val cover: Boolean,
    val lyric: Boolean,
    val year: Boolean,
) {
    val any: Boolean get() = artist || album || cover || lyric || year
    val unresolvedRequired: Boolean get() = artist || album || cover || lyric
}

internal fun localInfoGaps(song: LocalSong, hasCover: Boolean, hasLyric: Boolean) = LocalInfoGaps(
    artist = song.artist.isBlank(),
    album = song.album.isBlank(),
    cover = !hasCover,
    lyric = !hasLyric,
    year = song.year !in 1900..2100,
)

internal fun mergeLocalInfo(local: LocalSong, online: OnlineSong, coverUri: String?): LocalSong = local.copy(
    artist = local.artist.ifBlank { online.singer },
    album = local.album.ifBlank { online.albumName },
    year = local.year.takeIf { it in 1900..2100 } ?: online.year ?: 0,
    coverUri = local.coverUri ?: coverUri,
)

internal fun tagExtension(mimeType: String, uri: String): String? {
    val mime = mimeType.lowercase(Locale.ROOT)
    val path = uri.lowercase(Locale.ROOT)
    return when {
        mime.contains("flac") || path.endsWith(".flac") -> ".flac"
        mime.contains("mpeg") || mime.contains("mp3") || path.endsWith(".mp3") -> ".mp3"
        else -> null
    }
}
