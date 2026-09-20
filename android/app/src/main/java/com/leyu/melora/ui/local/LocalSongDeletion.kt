package com.leyu.melora.ui.local

import android.app.RecoverableSecurityException
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import com.leyu.melora.playback.local.LocalSong
import java.io.File
import java.util.ArrayDeque

/** 删除链路只使用一份顺序状态：物理删除成功后，调用方才可以移除本地索引。 */
internal data class LocalDeletionTarget(
    val id: String,
    val uri: String,
)

internal data class LocalDeletionResult(
    val deleted: List<LocalDeletionTarget>,
    val failed: List<LocalDeletionTarget>,
    val cancelled: Boolean = false,
) {
    val deletedIds: Set<String> get() = deleted.mapTo(linkedSetOf(), LocalDeletionTarget::id)
}

internal sealed interface LocalDeletionAttempt<out Request> {
    data object Deleted : LocalDeletionAttempt<Nothing>
    data object Failed : LocalDeletionAttempt<Nothing>
    data class NeedsAuthorization<Request>(val request: Request) : LocalDeletionAttempt<Request>
}

internal sealed interface LocalDeletionRequest<out Request> {
    val targets: List<LocalDeletionTarget>
    val request: Request

    data class Recoverable<Request>(
        val target: LocalDeletionTarget,
        override val request: Request,
    ) : LocalDeletionRequest<Request> {
        override val targets: List<LocalDeletionTarget> = listOf(target)
    }

    data class System<Request>(
        override val targets: List<LocalDeletionTarget>,
        override val request: Request,
    ) : LocalDeletionRequest<Request>
}

internal sealed interface LocalDeletionStep<out Request> {
    data object Busy : LocalDeletionStep<Nothing>
    data object Ignored : LocalDeletionStep<Nothing>
    data class Awaiting<Request>(val request: LocalDeletionRequest<Request>) : LocalDeletionStep<Request>
    data class Finished(val result: LocalDeletionResult) : LocalDeletionStep<Nothing>
}

/** 物理操作由 Android 实现或 JVM fake 注入，状态推进本身不依赖 Android。 */
internal interface LocalDeletionOperations<Request> {
    val supportsSystemDeleteRequest: Boolean

    fun isMediaStore(target: LocalDeletionTarget): Boolean
    fun delete(target: LocalDeletionTarget): LocalDeletionAttempt<Request>
    fun createSystemDeleteRequest(targets: List<LocalDeletionTarget>): Request?
}

/**
 * 顺序删除状态机：
 * - Android 10 的授权只代表可以重试当前 URI，不能直接视为删除成功；
 * - Android 11+ 的系统请求确认后才把整批 URI 记为已删除；
 * - 授权取消终止本轮，未实际删除的目标全部保留在失败结果中。
 */
internal class LocalSongDeletion<Request>(
    private val operations: LocalDeletionOperations<Request>,
) {
    @Volatile
    var isBusy: Boolean = false
        private set

    private val remaining = ArrayDeque<LocalDeletionTarget>()
    private val deleted = ArrayList<LocalDeletionTarget>()
    private val failed = ArrayList<LocalDeletionTarget>()
    private var pending: LocalDeletionRequest<Request>? = null

    @Synchronized
    fun start(targets: List<LocalDeletionTarget>): LocalDeletionStep<Request> {
        if (isBusy) return LocalDeletionStep.Busy
        val unique = distinctLocalDeletionTargets(targets)
        if (unique.isEmpty()) return LocalDeletionStep.Finished(LocalDeletionResult(emptyList(), emptyList()))

        remaining.clear()
        remaining += unique
        deleted.clear()
        failed.clear()
        pending = null
        isBusy = true
        return advanceLocked()
    }

    @Synchronized
    fun onAuthorizationResult(approved: Boolean): LocalDeletionStep<Request> {
        val authorization = pending ?: return LocalDeletionStep.Ignored
        pending = null
        if (!approved) {
            failed += authorization.targets
            failed += remaining
            remaining.clear()
            return finishLocked(cancelled = true)
        }

        when (authorization) {
            is LocalDeletionRequest.Recoverable -> {
                when (operations.delete(authorization.target)) {
                    LocalDeletionAttempt.Deleted -> deleted += authorization.target
                    LocalDeletionAttempt.Failed -> failed += authorization.target
                    is LocalDeletionAttempt.NeedsAuthorization -> failed += authorization.target
                }
            }
            is LocalDeletionRequest.System -> deleted += authorization.targets
        }
        return advanceLocked()
    }

    private fun advanceLocked(): LocalDeletionStep<Request> {
        while (remaining.isNotEmpty()) {
            if (operations.supportsSystemDeleteRequest && operations.isMediaStore(remaining.first())) {
                val batch = remaining.filter(operations::isMediaStore)
                remaining.removeAll(batch.toSet())
                val request = operations.createSystemDeleteRequest(batch)
                if (request == null) {
                    failed += batch
                    continue
                }
                val systemRequest = LocalDeletionRequest.System(batch, request)
                pending = systemRequest
                return LocalDeletionStep.Awaiting(systemRequest)
            }

            val target = remaining.removeFirst()
            when (val attempt = operations.delete(target)) {
                LocalDeletionAttempt.Deleted -> deleted += target
                LocalDeletionAttempt.Failed -> failed += target
                is LocalDeletionAttempt.NeedsAuthorization -> {
                    val recoverableRequest = LocalDeletionRequest.Recoverable(target, attempt.request)
                    pending = recoverableRequest
                    return LocalDeletionStep.Awaiting(recoverableRequest)
                }
            }
        }
        return finishLocked(cancelled = false)
    }

    private fun finishLocked(cancelled: Boolean): LocalDeletionStep.Finished {
        isBusy = false
        return LocalDeletionStep.Finished(
            LocalDeletionResult(
                deleted = deleted.toList(),
                failed = failed.toList(),
                cancelled = cancelled,
            ),
        )
    }
}

internal fun LocalSong.toLocalDeletionTarget(): LocalDeletionTarget = LocalDeletionTarget(id = id, uri = uri)

internal fun distinctLocalDeletionTargets(targets: List<LocalDeletionTarget>): List<LocalDeletionTarget> {
    val ids = HashSet<String>()
    val uris = HashSet<String>()
    return targets.filter { target ->
        if (target.id in ids || target.uri in uris) {
            false
        } else {
            ids += target.id
            uris += target.uri
            true
        }
    }
}

/** Android 10/11+ 的唯一平台实现；页面只负责确认、启动授权和消费结果。 */
internal class AndroidLocalDeletionOperations(context: Context) : LocalDeletionOperations<IntentSenderRequest> {
    private val context = context.applicationContext
    private val resolver = context.contentResolver

    override val supportsSystemDeleteRequest: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun isMediaStore(target: LocalDeletionTarget): Boolean {
        val uri = target.uri.toUri()
        return uri.scheme == "content" && uri.authority?.startsWith("media") == true
    }

    override fun delete(target: LocalDeletionTarget): LocalDeletionAttempt<IntentSenderRequest> {
        val uri = target.uri.toUri()
        return try {
            val didDelete = when {
                isMediaStore(target) -> resolver.delete(uri, null, null) > 0
                uri.scheme == "file" -> uri.path?.let(::File)?.delete() == true
                uri.scheme == "content" -> DocumentFile.fromSingleUri(context, uri)?.delete() == true ||
                    resolver.delete(uri, null, null) > 0
                else -> false
            }
            if (didDelete) LocalDeletionAttempt.Deleted else LocalDeletionAttempt.Failed
        } catch (security: SecurityException) {
            val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recoverableRequest(security)
            } else {
                null
            }
            if (request != null) LocalDeletionAttempt.NeedsAuthorization(request) else LocalDeletionAttempt.Failed
        } catch (_: Throwable) {
            LocalDeletionAttempt.Failed
        }
    }

    override fun createSystemDeleteRequest(targets: List<LocalDeletionTarget>): IntentSenderRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || targets.isEmpty()) return null
        return runCatching {
            val pendingIntent = MediaStore.createDeleteRequest(
                resolver,
                targets.map { it.uri.toUri() },
            )
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recoverableRequest(error: SecurityException): IntentSenderRequest? {
        val recoverable = error as? RecoverableSecurityException ?: return null
        return IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build()
    }
}
