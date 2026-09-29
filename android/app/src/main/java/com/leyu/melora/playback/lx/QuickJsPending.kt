package com.leyu.melora.playback.lx

import com.quickjs.QuickJS

object QuickJsPending {
    init {
        System.loadLibrary("quickjs-pending")
    }

    external fun executePendingNative(runtimePtr: Long, contextPtr: Long): Int

    /** 等待runtime线程上的Promise任务全部退出，再允许调用方归还本次执行budget。 */
    fun executePending(runtime: QuickJS, runtimePtr: Long, contextPtr: Long) {
        runtime.runOnEventQueue { executePendingNative(runtimePtr, contextPtr) }
    }
}
