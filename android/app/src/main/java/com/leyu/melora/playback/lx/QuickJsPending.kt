package com.leyu.melora.playback.lx

import com.quickjs.QuickJS

object QuickJsPending {
    init {
        System.loadLibrary("quickjs-pending")
    }

    external fun executePendingNative(runtimePtr: Long, contextPtr: Long): Int

    /** 等待runtime线程上的Promise任务全部退出，再允许调用方归还本次执行budget。 */
    internal fun executePending(runtime: QuickJS, runtimePtr: Long, contextPtr: Long): Boolean {
        var executed = 0
        runtime.runOnEventQueue { executed = executePendingNative(runtimePtr, contextPtr) }
        // 原生泵每批最多执行 1000 个任务；满批时先继续排空再等待外部事件。
        return executed >= 1000
    }
}
