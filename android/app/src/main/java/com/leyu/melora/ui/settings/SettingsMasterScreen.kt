package com.leyu.melora.ui.settings

import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// 设置页面路由
internal enum class SettingsSubPage(val title: String) {
    None("设置"),
    Basic("基本设置"),
    CustomSource("自定义源"),
    Playback("播放设置"),
    Download("下载设置"),
    DesktopLyrics("桌面歌词"),
    Others("其他设置"),
    About("关于乐屿"),
    LocalMusic("本地歌曲"),
    Sync("同步与备份"),
}

@Composable
fun SettingsMasterScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    requestSeq: Int = 0,
) {
    var currentSubPage by rememberSaveable(requestSeq) { mutableStateOf(SettingsSubPage.None) }

    // 当处于二级设置页面时，系统返回键先返回一级设置菜单
    if (currentSubPage != SettingsSubPage.None) {
        BackHandler {
            currentSubPage = SettingsSubPage.None
        }
    }

    DetailPageHost(
        target = currentSubPage.takeUnless { it == SettingsSubPage.None },
        modifier = modifier.fillMaxSize(),
        detail = { subPage ->
            SettingsPageContent(
                subPage = subPage,
                onOpenDrawer = onOpenDrawer,
                onNavigate = { currentSubPage = it },
                onBack = { currentSubPage = SettingsSubPage.None },
            )
        },
        content = {
            SettingsMainMenu(
                onOpenDrawer = onOpenDrawer,
                onNavigate = { currentSubPage = it },
            )
        },
    )
}
