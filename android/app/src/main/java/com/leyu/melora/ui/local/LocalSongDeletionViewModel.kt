package com.leyu.melora.ui.local

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.local.LocalMediaScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalDeletionStep<*>.inProgress: Boolean
    get() = this != LocalDeletionStep.Ignored && this !is LocalDeletionStep.Finished

/** 系统授权批次保留在 Activity 的 ViewModelStore 中，URI 列表不写入保存状态。 */
internal class LocalSongDeletionViewModel(
    private val appContext: Context,
    private val savedState: SavedStateHandle,
    operations: LocalDeletionOperations<IntentSenderRequest> = AndroidLocalDeletionOperations(appContext),
) : ViewModel() {
    private val deletion = LocalSongDeletion(operations)
    private val mutableStep = MutableStateFlow<LocalDeletionStep<IntentSenderRequest>>(
        if (savedState.contains(AuthorizationKey)) LocalDeletionStep.RestoredAuthorization else LocalDeletionStep.Ignored,
    )
    val step = mutableStep.asStateFlow()

    fun start(targets: List<LocalDeletionTarget>) {
        if (!step.value.inProgress) advance { deletion.start(targets) }
    }

    fun takeAuthorizationRequest(): IntentSenderRequest? {
        val authorization = (step.value as? LocalDeletionStep.Awaiting)?.request ?: return null
        savedState[AuthorizationKey] = authorization is LocalDeletionRequest.System
        mutableStep.value = LocalDeletionStep.WaitingForSystem
        return authorization.request
    }

    fun onAuthorizationResult(approved: Boolean) {
        val systemDelete = savedState.remove<Boolean>(AuthorizationKey) ?: return
        when {
            step.value == LocalDeletionStep.WaitingForSystem -> advance { deletion.onAuthorizationResult(approved) }
            !approved -> mutableStep.value = LocalDeletionStep.Ignored
            // 进程重建只保留是否发过授权，扫描依据实际文件状态对账。
            systemDelete -> advance {
                try {
                    PlaybackController.postMessage(appContext, LocalMediaScanner.scan(appContext).message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PlaybackController.postMessage(appContext, "系统删除后未能扫描本地文件，请手动刷新")
                }
                LocalDeletionStep.Ignored
            }
            else -> {
                PlaybackController.postMessage(appContext, "授权已确认，请重新选择要删除的文件")
                mutableStep.value = LocalDeletionStep.Ignored
            }
        }
    }

    private fun advance(operation: suspend () -> LocalDeletionStep<IntentSenderRequest>) {
        mutableStep.value = LocalDeletionStep.Busy
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { operation() }
            if (next is LocalDeletionStep.Finished) finish(next.result)
            mutableStep.value = next
        }
    }

    private suspend fun finish(result: LocalDeletionResult) {
        if (result.deleted.isNotEmpty()) {
            PlaybackController.onLocalFilesDeleted(appContext, result.deletedIds, result.deleted.mapTo(hashSetOf()) { it.uri }).join()
        }
        val count = result.deleted.size
        val message = when {
            result.cancelled -> if (count == 0) "已取消删除" else "已删除 $count 个，其余已取消"
            result.failed.isEmpty() -> "已删除本地文件 $count 个"
            count == 0 -> "删除失败，请检查文件权限"
            else -> "已删除 $count 个，${result.failed.size} 个失败"
        }
        PlaybackController.postMessage(appContext, message)
    }

    private companion object {
        const val AuthorizationKey = "localDeletionSystemRequest"
    }
}

@Composable
internal fun rememberLocalSongDeletion(): LocalSongDeletionViewModel {
    val appContext = LocalContext.current.applicationContext
    val deletion = viewModel { LocalSongDeletionViewModel(appContext, createSavedStateHandle()) }
    val step by deletion.step.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        deletion.onAuthorizationResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(step) {
        deletion.takeAuthorizationRequest()?.let { consent.launch(it) }
    }
    return deletion
}
