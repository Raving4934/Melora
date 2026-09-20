package com.leyu.melora.playback.lx

import com.quickjs.QuickJS

object QuickJsPending {
    init {
        System.loadLibrary("quickjs-pending")
    }

    external fun executePendingNative(runtimePtr: Long, contextPtr: Long): Int

    /** Promise任务必须与JS求值/关闭处于同一runtime线程；下一条同步JS调用充当队列屏障。 */
    fun executePending(runtime: QuickJS, runtimePtr: Long, contextPtr: Long) {
        runtime.postEventQueue { executePendingNative(runtimePtr, contextPtr) }
    }
}
