package com.leyu.melora.ui.common

import kotlinx.coroutines.CancellationException

/** 与 runCatching 语义一致，但协程取消必须继续向上传播，禁止旧页面请求回写新状态。 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
