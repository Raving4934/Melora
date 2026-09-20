package com.leyu.melora.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.BuildConfig
import com.leyu.melora.R
import com.leyu.melora.playback.AudioCacheStore
import com.leyu.melora.playback.BackupManager
import com.leyu.melora.playback.CacheManager
import com.leyu.melora.playback.CacheStats
import com.leyu.melora.playback.formatCacheBytes
import com.leyu.melora.playback.DesktopLyricService
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.common.MeloraAboutMark
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.launch
import androidx.core.net.toUri

// 1. 二级菜单：基本设置
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BasicSettingsSubPage(onBack: () -> Unit) {
    val themeMode by MeloraSettings.themeMode.collectAsStateWithLifecycle()
    val hideStatusBar by MeloraSettings.hideStatusBar.collectAsStateWithLifecycle()
    val blurTopBar by MeloraSettings.blurTopBar.collectAsStateWithLifecycle()
    val showSongCovers by MeloraSettings.showSongCovers.collectAsStateWithLifecycle()
    val showExitButton by MeloraSettings.showExitButton.collectAsStateWithLifecycle()
    val pullToRefresh by MeloraSettings.pullToRefresh.collectAsStateWithLifecycle()
    val playerThemeMode by MeloraSettings.playerThemeMode.collectAsStateWithLifecycle()
    val keepScreenAwake by MeloraSettings.keepScreenAwake.collectAsStateWithLifecycle()
    val miniLyricsEnabled by MeloraSettings.miniLyricsEnabled.collectAsStateWithLifecycle()
    val playerCoverStyle by MeloraSettings.playerCoverStyle.collectAsStateWithLifecycle()

    val blurTopBarSubtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "滚动内容在顶栏内渐变模糊"
    } else {
        "当前系统使用渐变材质兼容显示"
    }

    SettingsSubPageScaffold(title = "基本设置", onBack = onBack) {
        item {
            SectionCaption("用户界面")
        }
        item {
            SettingsCard {
                Text("主题模式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text(
                    "跟随系统自动切换或固定浅色/深色主题",
                    fontSize = 12.sp,
                    color = SettingsTextSub,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                ThemeModeVisualSelector(
                    selected = themeMode,
                    onSelect = { MeloraSettings.updateThemeMode(it) },
                )
            }
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("隐藏状态栏", "播放页隐藏系统状态栏", hideStatusBar) {
                    MeloraSettings.updateHideStatusBar(it)
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("模糊标题栏", blurTopBarSubtitle, blurTopBar) {
                    MeloraSettings.updateBlurTopBar(it)
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("显示单曲封面", "在歌曲列表中加载单曲封面", showSongCovers) {
                    MeloraSettings.updateShowSongCovers(it)
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("显示退出按钮", "在侧栏底部显示退出按钮", showExitButton) {
                    MeloraSettings.updateShowExit(it)
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("下拉刷新", "允许列表下拉刷新", pullToRefresh) {
                    MeloraSettings.updatePullToRefresh(it)
                }
            }
        }

        item {
            SectionCaption("播放界面")
        }
        item {
            SettingsCard {
                Text("页面主题", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text(
                    "全屏播放页遵循全局或使用独立色彩模式",
                    fontSize = 12.sp,
                    color = SettingsTextSub,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                ThemeModeVisualSelector(
                    selected = playerThemeMode,
                    onSelect = { MeloraSettings.updatePlayerThemeMode(it) },
                )
            }
        }
        item {
            SettingsCard {
                Text("封面样式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text(
                    "全屏播放页专辑封面与黑胶展示形态",
                    fontSize = 12.sp,
                    color = SettingsTextSub,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                CoverStyleVisualSelector(
                    selected = playerCoverStyle,
                    onSelect = { MeloraSettings.updatePlayerCoverStyle(it) },
                )
            }
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("保持屏幕唤醒", "播放时保持屏幕常亮", keepScreenAwake) {
                    MeloraSettings.updateKeepScreenAwake(it)
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("迷你歌词", "播放页显示迷你歌词", miniLyricsEnabled) {
                    MeloraSettings.updateMiniLyricsEnabled(it)
                }
            }
        }
    }
}
// 下载设置：路径/音质/跳过同名/自动换源/并发数/命名格式/嵌入标签
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSettingsSubPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val downloadPath by MeloraSettings.downloadPath.collectAsStateWithLifecycle()
    val downloadQuality by MeloraSettings.downloadQuality.collectAsStateWithLifecycle()
    val skipSameName by MeloraSettings.downloadSkipSameName.collectAsStateWithLifecycle()
    val autoSwitch by MeloraSettings.downloadAutoSwitchSource.collectAsStateWithLifecycle()
    val concurrentTasks by MeloraSettings.downloadConcurrentTasks.collectAsStateWithLifecycle()
    val nameFormat by MeloraSettings.downloadFileNameFormat.collectAsStateWithLifecycle()
    val embedCover by MeloraSettings.downloadEmbedCover.collectAsStateWithLifecycle()
    val embedLyric by MeloraSettings.downloadEmbedLyric.collectAsStateWithLifecycle()

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatchingCancellable {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                require(DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) { "所选目录不可写" }
            }.onSuccess {
                MeloraSettings.updateDownloadPath(uri.toString())
                PlaybackController.postMessage(context, "下载目录已更新")
            }.onFailure {
                PlaybackController.postMessage(context, "无法保存下载目录权限，请重新选择")
            }
        }
    }

    SettingsSubPageScaffold(title = "下载设置", onBack = onBack) {
        item {
            SectionCaption("存储位置")
        }
        item {
            SettingsCard {
                Text("离线下载存储路径", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text(
                    if (downloadPath.startsWith("content://")) "自定义目录（系统文件框架）" else downloadPath,
                    fontSize = 12.sp,
                    color = SettingsTextSub,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    if (downloadPath.startsWith("content://")) downloadPath else "默认写入系统媒体库 Music/Melora",
                    fontSize = 10.sp,
                    color = SettingsTextSub.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = { treePicker.launch(null) },
                    shape = RoundedCornerShape(10.dp),
                    color = MeloraAppearance.tintBlue,
                ) {
                    Text("更改路径", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SettingsBrandBlue, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        item {
            SectionCaption("音质偏好")
        }
        item {
            SettingsCard {
                Text("下载音质", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text("优先使用所选音质；不可用时逐级降至 FLAC、320k 或 128k", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                SettingsChoiceRow(QualityOptions, downloadQuality, { MeloraSettings.updateDownloadQuality(it) })
            }
        }

        item {
            SectionCaption("下载行为")
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("同曲同音质自动跳过", "仅跳过目标目录中同版本、同音质的文件", skipSameName) { MeloraSettings.updateDownloadSkipSameName(it) }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("自动换源下载", "当前音源下载失败时自动尝试其他已安装音源", autoSwitch) { MeloraSettings.updateDownloadAutoSwitchSource(it) }
            }
        }
        item {
            SettingsCard {
                Text("同时下载任务数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text("批量下载时允许同时进行的任务数量", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                SettingsChoiceRow(
                    options = listOf("1" to "1 个", "2" to "2 个", "3" to "3 个", "4" to "4 个"),
                    selected = concurrentTasks.coerceIn(1, 4).toString(),
                    onSelect = { MeloraSettings.updateDownloadConcurrentTasks(it.toIntOrNull() ?: 2) },
                )
            }
        }

        item {
            SectionCaption("文件命名")
        }
        item {
            SettingsCard {
                Text("文件命名方式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text("下载文件的命名规则", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                SettingsChoiceRow(
                    options = listOf(
                        "song-artist" to "歌曲名-艺术家",
                        "artist-song" to "艺术家-歌曲名",
                        "song" to "歌曲名",
                    ),
                    selected = nameFormat,
                    onSelect = { MeloraSettings.updateDownloadFileNameFormat(it) },
                )
            }
        }

        item {
            SectionCaption("文件标签")
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("嵌入封面", "把歌曲封面写入音频文件标签", embedCover) { MeloraSettings.updateDownloadEmbedCover(it) }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("嵌入歌词", "把歌词写入音频文件标签", embedLyric) { MeloraSettings.updateDownloadEmbedLyric(it) }
            }
        }
    }
}
// 3. 二级菜单：播放设置
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettingsSubPage(onBack: () -> Unit) {
    val autoPlayOnStart by MeloraSettings.autoPlayOnStart.collectAsStateWithLifecycle()
    val rememberProgress by MeloraSettings.rememberProgress.collectAsStateWithLifecycle()
    val autoClearPlayed by MeloraSettings.autoClearPlayed.collectAsStateWithLifecycle()
    val pauseOnOtherAudio by MeloraSettings.pauseOnOtherAudio.collectAsStateWithLifecycle()
    val showLockscreenCover by MeloraSettings.showNotificationCover.collectAsStateWithLifecycle()
    val notificationLyrics by MeloraSettings.notificationLyrics.collectAsStateWithLifecycle()
    val wifiQuality by MeloraSettings.playQualityWifi.collectAsStateWithLifecycle()
    val mobileQuality by MeloraSettings.playQualityMobile.collectAsStateWithLifecycle()
    val maxCacheMb by MeloraSettings.maxCacheMb.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var audioCacheBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { audioCacheBytes = CacheManager.audioCacheBytes(context) }

    SettingsSubPageScaffold(title = "播放设置", onBack = onBack) {
        item {
            SectionCaption("播放行为")
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("启动后自动播放", "打开应用时自动恢复上次播放", autoPlayOnStart) { MeloraSettings.updateAutoPlay(it) }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("记住播放进度", "下次打开记忆有声书与长音乐位置", rememberProgress) { MeloraSettings.updateRememberProgress(it) }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("自动清空已播放列表", "单曲播完后从待播队列移除", autoClearPlayed) { MeloraSettings.updateAutoClearPlayed(it) }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("其他应用发声时自动暂停", "音频焦点冲突时主动避让暂停", pauseOnOtherAudio) {
                    MeloraSettings.updatePauseOnOtherAudio(it)
                    PlaybackController.applyAudioFocus(it)
                }
            }
        }

        item {
            SectionCaption("通知栏")
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("在通知栏显示歌曲封面", "Media3 系统控制中心展现大图", showLockscreenCover) {
                    MeloraSettings.updateNotificationCover(it)
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("通知栏歌词", "通知栏/锁屏实时显示当前歌词行", notificationLyrics) {
                    MeloraSettings.updateNotificationLyrics(it)
                }
            }
        }

        item {
            SectionCaption("音质偏好")
        }
        item {
            SettingsCard {
                Text("WiFi 网络音质", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text("WiFi/以太网下的首选音质，不可用时自动降级", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                SettingsChoiceRow(QualityOptions, wifiQuality, { MeloraSettings.updatePlayQualityWifi(it) })

                SettingsDivider(Modifier.padding(vertical = 14.dp))

                Text("移动网络音质", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text("使用流量时优先选择的音质，建议 128k/320k 节省流量", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                SettingsChoiceRow(QualityOptions, mobileQuality, { MeloraSettings.updatePlayQualityMobile(it) })
            }
        }

        item {
            SectionCaption("缓存管理")
        }
        item {
            SettingsCard {
                Text("播放缓存", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text(
                    "播放过的歌曲自动缓存，重复播放更省流更快；超出上限按最久未用自动清理。",
                    fontSize = 12.sp,
                    color = SettingsTextSub,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                )
                SettingSliderRow(
                    title = "最大缓存大小",
                    valueText = formatCacheSize(maxCacheMb),
                    value = maxCacheMb.toFloat(),
                    range = 256f..4096f,
                    steps = 14,
                    onValueChangeFinished = AudioCacheStore::trimNow,
                ) {
                    MeloraSettings.updateMaxCacheMb(it.toInt())
                }
                Text(
                    "当前音频缓存占用 ${formatCacheSize((audioCacheBytes / 1024 / 1024).toInt())}",
                    fontSize = 11.sp,
                    color = SettingsTextSub,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
// 4. 二级菜单：桌面歌词
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopLyricsSubPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val showDesktopLyrics by MeloraSettings.showDesktopLyrics.collectAsStateWithLifecycle()
    val lockLyrics by MeloraSettings.lockLyrics.collectAsStateWithLifecycle()
    val lyricAnimEnabled by MeloraSettings.lyricAnimEnabled.collectAsStateWithLifecycle()
    val singleLineLyric by MeloraSettings.singleLineLyric.collectAsStateWithLifecycle()
    val lyricBackground by MeloraSettings.lyricBackground.collectAsStateWithLifecycle()

    val lyricFontSize by MeloraSettings.lyricFontSize.collectAsStateWithLifecycle()
    val windowPercent by MeloraSettings.lyricWindowPercent.collectAsStateWithLifecycle()
    val maxLines by MeloraSettings.lyricMaxLines.collectAsStateWithLifecycle()
    val lyricAlpha by MeloraSettings.lyricAlpha.collectAsStateWithLifecycle()

    val hAlign by MeloraSettings.lyricHAlign.collectAsStateWithLifecycle()
    val vAlign by MeloraSettings.lyricVAlign.collectAsStateWithLifecycle()
    val selectedColorIndex by MeloraSettings.lyricColorIndex.collectAsStateWithLifecycle()

    val themeColors = listOf(Color.White, Color(0xFF38BDF8), Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFFB7185))
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (MeloraSettings.showDesktopLyrics.value && AndroidSettings.canDrawOverlays(context)) {
            DesktopLyricService.start(context)
        } else if (MeloraSettings.showDesktopLyrics.value) {
            MeloraSettings.updateShowDesktopLyrics(false)
            PlaybackController.postMessage(context, "未获得悬浮窗权限，桌面歌词未开启")
        }
    }

    fun toggleDesktopLyrics(enabled: Boolean) {
        MeloraSettings.updateShowDesktopLyrics(enabled)
        if (enabled) {
            if (AndroidSettings.canDrawOverlays(context)) {
                DesktopLyricService.start(context)
            } else {
                runCatchingCancellable {
                    overlayPermissionLauncher.launch(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                }.onFailure {
                    MeloraSettings.updateShowDesktopLyrics(false)
                    PlaybackController.postMessage(context, "无法打开悬浮窗权限设置")
                }
            }
        } else {
            DesktopLyricService.stop(context)
        }
    }

    fun refreshOverlay() {
        if (showDesktopLyrics) DesktopLyricService.refresh(context)
    }

    SettingsSubPageScaffold(title = "桌面歌词", onBack = onBack) {
        item {
            SectionCaption("窗口与行为")
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem("显示桌面歌词", "开启悬浮窗全屏浮动歌词（需悬浮窗权限）", showDesktopLyrics) { toggleDesktopLyrics(it) }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("锁定歌词位置", "锁定后不可拖拽穿透触摸", lockLyrics) {
                    MeloraSettings.updateLockLyrics(it)
                    refreshOverlay()
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("显示歌词切换动画", "行与行平滑淡入淡出", lyricAnimEnabled) {
                    MeloraSettings.updateLyricAnim(it)
                    refreshOverlay()
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("使用单行歌词", "极简模式只展示当前唱段", singleLineLyric) {
                    MeloraSettings.updateSingleLine(it)
                    refreshOverlay()
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))

                SwitchItem("显示歌词底板", "开启为浅色磨砂胶囊；关闭为纯净文字", lyricBackground) {
                    MeloraSettings.updateLyricBackground(it)
                    refreshOverlay()
                }
            }
        }

        item {
            SectionCaption("外观")
        }
        item {
            SettingsCard {
                Text("歌词主题色", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    themeColors.forEachIndexed { index, color ->
                        val isSelected = selectedColorIndex == index
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable {
                                    MeloraSettings.updateLyricColorIndex(index)
                                    refreshOverlay()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF111827)),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCaption("字体与排版")
        }
        item {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingSliderRow("歌词字体大小", "${lyricFontSize.toInt()} sp", lyricFontSize, 12f..36f) {
                        MeloraSettings.updateLyricFontSize(it)
                        refreshOverlay()
                    }
                    SettingSliderRow("窗口宽度占比", "${windowPercent.toInt()} %", windowPercent, 50f..100f) {
                        MeloraSettings.updateLyricWindowPercent(it)
                        refreshOverlay()
                    }
                    SettingSliderRow("最大行数", "${maxLines.toInt()} 行", maxLines, 1f..8f, steps = 6) {
                        MeloraSettings.updateLyricMaxLines(it)
                        refreshOverlay()
                    }
                    SettingSliderRow("歌词不透明度", "${lyricAlpha.toInt()} %", lyricAlpha, 20f..100f) {
                        MeloraSettings.updateLyricAlpha(it)
                        refreshOverlay()
                    }
                }
            }
        }

        item {
            SectionCaption("对齐方式")
        }
        item {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingSliderRow(
                        title = "歌词水平对齐",
                        valueText = listOf("居左", "居中", "居右")[hAlign.coerceIn(0, 2)],
                        value = hAlign.coerceIn(0, 2).toFloat(),
                        range = 0f..2f,
                        steps = 1,
                    ) {
                        MeloraSettings.updateLyricHAlign(it.toInt().coerceIn(0, 2))
                        refreshOverlay()
                    }
                    SettingSliderRow(
                        title = "歌词垂直对齐",
                        valueText = listOf("居顶", "居中", "居底")[vAlign.coerceIn(0, 2)],
                        value = vAlign.coerceIn(0, 2).toFloat(),
                        range = 0f..2f,
                        steps = 1,
                    ) {
                        MeloraSettings.updateLyricVAlign(it.toInt().coerceIn(0, 2))
                        refreshOverlay()
                    }
                }
            }
        }
    }
}
// 5. 二级菜单：其他设置
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OtherSettingsSubPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheStats by remember { mutableStateOf(CacheManager.cachedStats ?: CacheStats(0L, 0L, 0L, 0L)) }
    var cacheBusy by remember { mutableStateOf(true) }
    var cacheError by remember { mutableStateOf<String?>(null) }

    suspend fun refreshCacheStats() {
        runCatchingCancellable { CacheManager.stats(context) }
            .onSuccess {
                cacheStats = it
                cacheError = null
            }
            .onFailure {
                cacheError = "读取缓存失败：${it.message ?: it.javaClass.simpleName}"
            }
    }

    fun clearCache(label: String, action: suspend () -> Unit) {
        if (cacheBusy) return
        cacheBusy = true
        cacheError = null
        scope.launch {
            try {
                runCatchingCancellable {
                    action()
                    CacheManager.stats(context)
                }.onSuccess {
                    cacheStats = it
                    PlaybackController.postMessage(context, "${label}清理完成")
                }.onFailure {
                    val message = "清理${label}失败：${it.message ?: it.javaClass.simpleName}"
                    cacheError = message
                    PlaybackController.postMessage(context, message)
                }
            } finally {
                cacheBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            refreshCacheStats()
        } finally {
            cacheBusy = false
        }
    }

    val hasExportRecovery by BackupManager.hasExportRecovery.collectAsStateWithLifecycle()
    val recoveryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            BackupManager.saveExportRecovery(context, uri)
                .onSuccess { PlaybackController.postMessage(context, it) }
                .onFailure { PlaybackController.postMessage(context, "副本导出失败：${it.message}") }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                BackupManager.export(context, uri)
                    .onSuccess { PlaybackController.postMessage(context, it) }
                    .onFailure { PlaybackController.postMessage(context, "备份失败：${it.message}") }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                BackupManager.import(context, uri)
                    .onSuccess { PlaybackController.postMessage(context, it) }
                    .onFailure { PlaybackController.postMessage(context, "恢复失败：${it.message}") }
            }
        }
    }

    SettingsSubPageScaffold(title = "其他设置", onBack = onBack) {
        item {
            SectionCaption("存储空间")
        }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("缓存管理", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                        Text(
                            text = cacheError ?: if (cacheStats.totalBytes > 0L) "已占用 ${cacheStats.display()}" else "无待清理缓存",
                            fontSize = 12.sp,
                            color = SettingsTextSub,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                        )
                    }
                    val canClearAll = cacheStats.totalBytes > 0L
                    Surface(
                        onClick = { clearCache("所有缓存") { CacheManager.clearAll(context) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (canClearAll) MeloraAppearance.tintRed else MeloraAppearance.softFill,
                        enabled = canClearAll && !cacheBusy,
                    ) {
                        Text(
                            text = "一键清空",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canClearAll) Color(0xFFDC2626) else SettingsTextSub.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                SettingsDivider(Modifier.padding(vertical = 10.dp))

                CacheClearRow(
                    icon = Icons.Outlined.MusicNote,
                    iconTint = Color(0xFFD97706),
                    label = "音频缓存",
                    sizeText = formatCacheBytes(cacheStats.audioBytes),
                    enabled = cacheStats.audioBytes > 0L && !cacheBusy,
                    onClear = { clearCache("音频缓存") { CacheManager.clearAudio(context) } },
                )

                SettingsDivider(Modifier.padding(horizontal = 4.dp))

                CacheClearRow(
                    icon = Icons.Outlined.Image,
                    iconTint = Color(0xFF059669),
                    label = "封面缓存",
                    sizeText = formatCacheBytes(cacheStats.imageBytes),
                    enabled = cacheStats.imageBytes > 0L && !cacheBusy,
                    onClear = { clearCache("封面缓存") { CacheManager.clearImages(context) } },
                )

                SettingsDivider(Modifier.padding(horizontal = 4.dp))

                CacheClearRow(
                    icon = Icons.Outlined.Subtitles,
                    iconTint = Color(0xFF7C3AED),
                    label = "歌词缓存",
                    sizeText = formatCacheBytes(cacheStats.lyricBytes),
                    enabled = cacheStats.lyricBytes > 0L && !cacheBusy,
                    onClear = { clearCache("歌词缓存") { CacheManager.clearLyrics(context) } },
                )
            }
        }

        item {
            SectionCaption("备份与恢复")
        }
        item {
            SettingsCard {
                Text("配置备份与恢复", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
                Text("备份设置、收藏/最近、自建歌单与音源脚本；音源合并恢复，不删除已有的其它源。音乐文件、缓存、播放队列与收听进度不包含在内，目录权限需重新授权。", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = { backupLauncher.launch("melora-backup.json") },
                        shape = RoundedCornerShape(10.dp),
                        color = MeloraAppearance.tintBlue,
                    ) {
                        Text("备份到本地", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SettingsBrandBlue, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Surface(
                        onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                        shape = RoundedCornerShape(10.dp),
                        color = MeloraAppearance.softFill,
                    ) {
                        Text("从备份恢复", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                if (hasExportRecovery) {
                    Surface(
                        onClick = { recoveryLauncher.launch("melora-recovered-backup.json") },
                        shape = RoundedCornerShape(10.dp),
                        color = MeloraAppearance.tintBlue,
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Text("另存上次保留的原文件副本", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = SettingsBrandBlue, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
        }
    }
}
// 6. 二级菜单：关于乐屿
@Composable
internal fun AboutSubPage(onBack: () -> Unit) {
    var modal by remember { mutableStateOf<AboutModal?>(null) }

    ChromeScaffold(topBar = { SubPageTopBar(title = "关于乐屿", onBack = onBack) }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = chromeContentPadding(PaddingValues(horizontal = 16.dp, vertical = 10.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 应用核心标识与版本
            item {
                Spacer(Modifier.height(8.dp))
                Image(
                    imageVector = MeloraAboutMark,
                    contentDescription = null,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "乐屿 · Melora",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = SettingsTextMain,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "本地 v${BuildConfig.VERSION_NAME}（Media3 播放引擎）",
                    fontSize = 12.sp,
                    color = SettingsTextSub,
                )
                Spacer(Modifier.height(4.dp))
            }

            // 分组 1: 软件更新与系统诊断
            item {
                SettingsCard {
                    SettingsEntryItem(
                        icon = Icons.Outlined.SystemUpdate,
                        iconTint = Color(0xFF2563EB),
                        title = "检测更新",
                        onClick = { modal = AboutModal.Update },
                    )
                    SettingsDivider(Modifier.padding(start = 56.dp))
                    SettingsEntryItem(
                        icon = Icons.Outlined.BugReport,
                        iconTint = Color(0xFFEA580C),
                        title = "崩溃日志",
                        onClick = { modal = AboutModal.CrashLog },
                    )
                    SettingsDivider(Modifier.padding(start = 56.dp))
                    SettingsEntryItem(
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        iconTint = Color(0xFF0284C7),
                        title = "常见问题",
                        onClick = { modal = AboutModal.Faq },
                    )
                }
            }

            // 分组 2: 法律、隐私与合规说明
            item {
                SettingsCard {
                    SettingsEntryItem(
                        icon = Icons.Outlined.Security,
                        iconTint = Color(0xFF0D9488),
                        title = "用户隐私协议",
                        onClick = { modal = AboutModal.Privacy },
                    )
                    SettingsDivider(Modifier.padding(start = 56.dp))
                    SettingsEntryItem(
                        icon = Icons.Outlined.Code,
                        iconTint = Color(0xFF7C3AED),
                        title = "开源许可",
                        onClick = { modal = AboutModal.Licenses },
                    )
                    SettingsDivider(Modifier.padding(start = 56.dp))
                    SettingsEntryItem(
                        icon = Icons.Outlined.Gavel,
                        iconTint = Color(0xFFE11D48),
                        title = "免责声明",
                        onClick = { modal = AboutModal.Disclaimer },
                    )
                }
            }
        }
    }

    // 弹窗展示
    modal?.let { currentModal ->
        AboutModalSheet(
            modal = currentModal,
            onDismiss = { modal = null },
        )
    }
}
