package com.leyu.melora.startup

/**
 * 明确导航始终优先；关闭恢复仅改变无保存状态的新启动默认页。
 * 重建时由 rememberSaveable 恢复当前页面，上次 Tab 只作为未保存页面状态的兜底。
 * 仅在 Activity.onCreate 使用，不监听设置变化或前后台切换。
 */
internal fun resolveStartupTab(
    explicitTab: Int?,
    lastTab: Int,
    restoreLastPage: Boolean,
    hasSavedInstanceState: Boolean,
): Int = explicitTab ?: if (hasSavedInstanceState || restoreLastPage) lastTab else 5
