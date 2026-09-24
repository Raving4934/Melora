package com.leyu.melora.ui.common

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.leyu.melora.playback.DesktopLyricService
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController

/** 设置页与播放页共用授权、持久化及服务启停；放在工具条展开区域外，避免收起时丢失授权回调。 */
@Composable
internal fun rememberDesktopLyricsToggle(): (Boolean) -> Unit {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (MeloraSettings.showDesktopLyrics.value) {
            if (Settings.canDrawOverlays(context)) {
                DesktopLyricService.start(context)
            } else {
                MeloraSettings.updateShowDesktopLyrics(false)
                PlaybackController.postMessage(context, "未获得悬浮窗权限，桌面歌词未开启")
            }
        }
    }
    return remember(context, permissionLauncher) {
        { enabled ->
            MeloraSettings.updateShowDesktopLyrics(enabled)
            if (!enabled) {
                DesktopLyricService.stop(context)
            } else if (Settings.canDrawOverlays(context)) {
                DesktopLyricService.start(context)
            } else {
                runCatchingCancellable {
                    permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri()))
                }.onFailure {
                    MeloraSettings.updateShowDesktopLyrics(false)
                    PlaybackController.postMessage(context, "无法打开悬浮窗权限设置")
                }
            }
        }
    }
}
