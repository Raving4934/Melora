package com.leyu.melora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// 设置页面路由：一级主菜单与 6 个二级子菜单
sealed class SettingsSubPage(val title: String) {
    data object None : SettingsSubPage("设置")
    data object Basic : SettingsSubPage("基本设置")
    data object CustomSource : SettingsSubPage("自定义源")
    data object Playback : SettingsSubPage("播放设置")
    data object Download : SettingsSubPage("下载设置")
    data object DesktopLyrics : SettingsSubPage("桌面歌词")
    data object Others : SettingsSubPage("其他设置")
    data object About : SettingsSubPage("关于乐屿")
    data object LocalMusic : SettingsSubPage("本地歌曲")
}

/** 侧栏深链首帧直接落在目标页，不能先画设置主页再由LaunchedEffect纠正。 */
internal fun initialSettingsSubPage(requested: SettingsSubPage?, requestSeq: Int): SettingsSubPage =
    if (requestSeq > 0) requested ?: SettingsSubPage.None else SettingsSubPage.None

@Composable
fun SettingsMasterScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    requestedSubPage: SettingsSubPage? = null,
    requestSeq: Int = 0,
) {
    var currentSubPage by remember {
        mutableStateOf(initialSettingsSubPage(requestedSubPage, requestSeq))
    }

    // 侧栏入口请求：每次点击（seq 递增）直接定位到目标子页，空值回到设置主菜单
    LaunchedEffect(requestSeq) {
        if (requestSeq > 0) currentSubPage = requestedSubPage ?: SettingsSubPage.None
    }

    // 当处于二级设置页面时，系统返回键先返回一级设置菜单
    if (currentSubPage != SettingsSubPage.None) {
        BackHandler {
            currentSubPage = SettingsSubPage.None
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentSubPage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "SettingsPageTransition",
        ) { subPage ->
            SettingsPageContent(
                subPage = subPage,
                onOpenDrawer = onOpenDrawer,
                onNavigate = { currentSubPage = it },
                onBack = { currentSubPage = SettingsSubPage.None },
            )
        }
    }
}
