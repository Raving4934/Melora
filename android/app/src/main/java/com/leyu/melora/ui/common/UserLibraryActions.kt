package com.leyu.melora.ui.common

import android.os.Handler
import android.os.Looper
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary

private val libraryFeedbackHandler by lazy { Handler(Looper.getMainLooper()) }

internal fun recordContainerPlayback(container: UserLibrary.PlayContainer) {
    UserLibrary.enqueueWrite { UserLibrary.markContainerPlayed(container) }
}

/** 保存由用户库负责，操作结果在主线程显示，关闭菜单不会丢掉写入。 */
internal fun <T> submitLibraryWrite(
    failureMessage: String = "无法保存，请重试",
    onCompletion: ((Result<T>) -> Unit)? = null,
    change: () -> T,
) {
    UserLibrary.enqueueWrite {
        val result = runCatchingCancellable(change)
        if (result.isFailure || onCompletion != null) {
            libraryFeedbackHandler.post {
                if (result.isFailure) PlaybackController.postMessage(null, failureMessage)
                onCompletion?.invoke(result)
            }
        }
    }
}
