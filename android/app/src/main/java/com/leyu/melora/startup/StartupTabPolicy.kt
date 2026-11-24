package com.leyu.melora.startup

internal enum class StartupPage(val tab: Int, val label: String) {
    Search(0, "搜索"),
    Rankings(1, "排行榜"),
    Playlists(3, "歌单"),
    Audiobooks(4, "听书"),
    LocalSongs(5, "本地歌曲"),
    Library(6, "我的列表");

    companion object {
        fun fromTab(tab: Int): StartupPage? = entries.firstOrNull { it.tab == tab }
    }
}

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
    startupPage: StartupPage = StartupPage.LocalSongs,
): Int = explicitTab ?: if (hasSavedInstanceState || restoreLastPage) lastTab else startupPage.tab
