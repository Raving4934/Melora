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
import com.leyu.melora.playback.MAX_EMBEDDED_COVER_BYTES
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.TrackRegistry
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.readBoundedBytes
import com.leyu.melora.playback.recoverableOrNull
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
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
 * 首次打开某首已匹配的本地文件时，按缺失字段联网补全。
 * 应用内信息立即更新；物理标签必须等当前曲目离开播放器后再写，避免与 Media3 打开同一文件竞态。
 */
object LocalTagFiller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pendingWrites = ConcurrentHashMap<String, PendingTagWrite>()
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

    fun consider(context: Context, track: UiTrack) {
        if (!MeloraSettings.localAutoFillInfo.value) {
            pendingWrites.clear()
            authorizationDeclined.clear()
            writeFailureNotified.clear()
            clearAuthorization()
            return
        }
        val app = context.applicationContext
        val activeLocal = LocalMediaStore.matchTrack(track)
        scope.launch { flushPendingWrites(app, activeLocal?.id) }
        if (OnlineSong.from(track.raw)?.isBookChapter == true) return
        val local = activeLocal ?: return
        if (!shouldFillLocalInfo(local)) return
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

    private suspend fun fill(context: Context, trackUid: String, original: LocalSong) {
        val local = LocalMediaStore.find(original.id) ?: original
        if (!shouldFillLocalInfo(local)) return
        val existingCover = LocalTagReader.bestCoverUri(context, local)
        val existingLyric = LocalTagReader.embeddedLyrics(context, local.uri, local.mimeType)
        val gaps = localInfoGaps(local, existingCover != null, !existingLyric.isNullOrBlank())
        if (!gaps.any) {
            markInfoFilled(local.id)
            return
        }
        val matched = try {
            SourceResolver.matchOnline(context, local.toOnlineSong(), background = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (matched == null) {
            retryAfter[local.id] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            // 在线匹配失败仍保留已提取的内嵌封面
            if (existingCover != null && local.coverUri.isNullOrBlank()) {
                val patched = local.copy(coverUri = existingCover)
                publishUpdatedLocal(patched)
                TrackRegistry.updateArtwork(trackUid, existingCover)
            }
            return
        }

        val coverBytes = if (gaps.cover) recoverableOrNull { fetchCover(context, matched) } else null
        val lyric = if (gaps.lyric) {
            recoverableOrNull { SourceResolver.lyric(context, matched, background = true)?.lyric }
                ?.takeIf { it.isNotBlank() }
        } else {
            existingLyric
        }
        val coverUri = when {
            existingCover != null -> existingCover
            coverBytes != null -> LocalTagReader.cacheCover(context, local, coverBytes)
            else -> null
        }
        val updated = mergeLocalInfo(local, matched, coverUri).copy(infoFilled = false)
        publishUpdatedLocal(updated)
        coverUri?.let { TrackRegistry.updateArtwork(trackUid, it) }

        val resolved = !localInfoGaps(
            song = updated,
            hasCover = coverUri != null,
            hasLyric = !lyric.isNullOrBlank(),
        ).unresolvedRequired
        val request = PendingTagWrite(updated, matched, gaps, coverBytes, lyric, resolved)
        if (!request.supported) {
            if (resolved) markInfoFilled(updated.id)
            return
        }
        if (isCurrentLocal(updated)) {
            pendingWrites[updated.id] = request
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
            if (!MeloraSettings.localAutoFillInfo.value || !granted) {
                authorizationDeclined += request.local.id
                pendingWrites.remove(request.local.id)
                PlaybackController.postMessage(app, "已保留应用内封面，未写入《${request.local.title}》文件")
                return@launch
            }
            handleWriteOutcome(app, request, writeTagsNow(app, request, allowAuthorization = false))
            flushPendingWrites(app, currentLocalId())
        }
    }

    private suspend fun flushPendingWrites(context: Context, activeLocalId: String?) {
        pendingWrites.entries.toList().forEach { (id, request) ->
            if (id == activeLocalId || isCurrentLocal(request.local)) return@forEach
            if (!pendingWrites.remove(id, request)) return@forEach
            handleWriteOutcome(context, request, writeTagsNow(context, request))
        }
    }

    private suspend fun handleWriteOutcome(context: Context, request: PendingTagWrite, outcome: TagWriteOutcome) {
        when (outcome) {
            is TagWriteOutcome.Written -> {
                authorizationDeclined.remove(request.local.id)
                writeFailureNotified.remove(request.local.id)
                publishUpdatedLocal(outcome.song)
                if (request.resolved) markInfoFilled(outcome.song.id)
                Log.i(TAG, "标签写入成功: ${request.local.id} ${request.local.title}")
            }
            TagWriteOutcome.Deferred -> pendingWrites[request.local.id] = request
            is TagWriteOutcome.NeedsAuthorization -> {
                if (!requestWriteAuthorization(context, request, outcome.error)) {
                    notifyWriteFailure(context, request, outcome.error)
                }
            }
            is TagWriteOutcome.Failed -> notifyWriteFailure(context, request, outcome.error)
        }
    }

    private suspend fun writeTagsNow(
        context: Context,
        request: PendingTagWrite,
        allowAuthorization: Boolean = true,
    ): TagWriteOutcome {
        if (isCurrentLocal(request.local)) return TagWriteOutcome.Deferred
        val extension = request.extension
            ?: return TagWriteOutcome.Failed(IllegalArgumentException("不支持的音频容器"))
        val local = LocalMediaStore.find(request.local.id) ?: request.local
        val uri = local.uri.toUri()
        // 保留数据是普通读，允许与其它读并发；真正的底层写入从排他租约开始。
        val preserveCover = if (request.gaps.cover) {
            request.coverBytes
        } else {
            LocalTagReader.embeddedPicture(context, local.uri) ?: readCachedCover(context, local.coverUri)
        }
        val preserveLyric = if (request.gaps.lyric) {
            request.lyric
        } else {
            LocalTagReader.embeddedLyrics(context, local.uri, local.mimeType)
        }
        return LocalMediaIoCoordinator.withExclusive(context, uri) {
            // 写租约本身保证起播不能与物理写入交错；此检查只用于减少无意义写入。
            if (isCurrentLocal(local)) return@withExclusive TagWriteOutcome.Deferred
            when (uri.scheme) {
                "file" -> writeFileTags(context, local, uri, extension, request, preserveCover, preserveLyric)
                "content" -> writeContentTags(
                    context,
                    local,
                    uri,
                    extension,
                    request,
                    preserveCover,
                    preserveLyric,
                    allowAuthorization,
                )
                else -> TagWriteOutcome.Failed(IllegalArgumentException("不支持的媒体 URI: ${uri.scheme}"))
            }
        }
    }

    private fun writeFileTags(
        context: Context,
        local: LocalSong,
        uri: Uri,
        extension: String,
        request: PendingTagWrite,
        cover: ByteArray?,
        lyric: String?,
    ): TagWriteOutcome = try {
        val file = uri.path?.let(::File)?.takeIf { it.isFile && it.canWrite() }
            ?: error("文件不存在或不可写")
        writeMetadata(file, extension, local, request, cover, lyric)
        TagWriteOutcome.Written(refreshStorageState(context, local))
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
        cover: ByteArray?,
        lyric: String?,
        allowAuthorization: Boolean,
    ): TagWriteOutcome {
        val temp = runCatching { File.createTempFile("melora-tag-", extension, context.cacheDir) }
            .getOrElse { return TagWriteOutcome.Failed(it) }
        try {
            val prepared = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                } ?: error("无法读取媒体文件")
                writeMetadata(temp, extension, local, request, cover, lyric)
            }
            prepared.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                return TagWriteOutcome.Failed(it)
            }
            return try {
                context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                    temp.inputStream().use { it.copyTo(output) }
                } ?: return TagWriteOutcome.Failed(IllegalStateException("无法打开媒体文件写入流"))
                TagWriteOutcome.Written(refreshStorageState(context, local))
            } catch (security: SecurityException) {
                if (allowAuthorization) TagWriteOutcome.NeedsAuthorization(security)
                else TagWriteOutcome.Failed(security)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                TagWriteOutcome.Failed(error)
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun writeMetadata(
        file: File,
        extension: String,
        local: LocalSong,
        request: PendingTagWrite,
        cover: ByteArray?,
        lyric: String?,
    ) {
        DownloadMetadataWriter.write(
            file = file,
            extension = extension,
            title = local.title.ifBlank { request.matched.name },
            artist = local.artist.ifBlank { request.matched.singer },
            album = local.album.ifBlank { request.matched.albumName },
            cover = cover,
            lyric = lyric,
            year = local.year.takeIf { it in 1900..2100 } ?: request.matched.year,
        )
    }

    private fun requestWriteAuthorization(
        context: Context,
        request: PendingTagWrite,
        error: SecurityException,
    ): Boolean {
        if (request.local.id in authorizationDeclined) return false
        var created: LocalTagWriteAuthorization? = null
        synchronized(authorizationLock) {
            if (awaitingAuthorization != null) {
                pendingWrites[request.local.id] = request
                return true
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
        Log.i(TAG, "等待媒体写入授权: ${request.local.id} ${request.local.title}")
        PlaybackController.postMessage(context, "需要系统授权才能写入《${request.local.title}》的封面和歌词")
        return created != null
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

    private fun notifyWriteFailure(context: Context, request: PendingTagWrite, error: Throwable) {
        pendingWrites.remove(request.local.id)
        Log.e(TAG, "标签写入失败: ${request.local.id} ${request.local.title}", error)
        if (writeFailureNotified.add(request.local.id)) {
            PlaybackController.postMessage(context, "《${request.local.title}》已补全显示，但未能写入音乐文件")
        }
    }

    private fun clearAuthorization() {
        synchronized(authorizationLock) {
            awaitingAuthorization = null
            _writeAuthorization.value = null
        }
    }

    private fun currentLocalId(): String? = PlaybackController.state.value.current
        ?.let(LocalMediaStore::matchTrack)
        ?.id

    private fun refreshStorageState(context: Context, song: LocalSong): LocalSong {
        val uri = song.uri.toUri()
        val file = writableFile(song.uri)
        if (file != null) {
            return song.copy(
                sizeBytes = file.length().takeIf { it > 0L } ?: song.sizeBytes,
                modifiedAt = file.lastModified().takeIf { it > 0L } ?: song.modifiedAt,
            )
        }
        val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        return song.copy(
            sizeBytes = document?.length()?.takeIf { it > 0L } ?: song.sizeBytes,
            modifiedAt = document?.lastModified()?.takeIf { it > 0L } ?: song.modifiedAt,
        )
    }

    private fun publishUpdatedLocal(song: LocalSong) {
        LocalMediaStore.updateMetadata(song)
    }

    private fun markInfoFilled(id: String) {
        val latest = LocalMediaStore.find(id) ?: return
        if (latest.infoFilled) return
        publishUpdatedLocal(latest.copy(infoFilled = true))
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
        data class Failed(val error: Throwable) : TagWriteOutcome
    }

    private data class PendingTagWrite(
        val local: LocalSong,
        val matched: OnlineSong,
        val gaps: LocalInfoGaps,
        val coverBytes: ByteArray?,
        val lyric: String?,
        val resolved: Boolean,
    ) {
        val extension = tagExtension(local.mimeType, local.uri)
        val supported = extension != null && DownloadMetadataWriter.supports(extension)
    }
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
