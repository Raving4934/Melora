package com.leyu.melora.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.local.LocalMediaScanner
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.launch

/**
 * 本地歌曲设置页：
 * - 本地媒体库索引状态与一键全盘扫描
 * - 扫描过滤规则（安卓媒体库、排除短音频/小音频）
 * - 自定义目录树管理（添加后自动关闭系统媒体库）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalMusicSettingsSubPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useMediaStore by MeloraSettings.localUseMediaStore.collectAsStateWithLifecycle()
    val folders by MeloraSettings.localFolders.collectAsStateWithLifecycle()
    val excludeShort by MeloraSettings.localExcludeShort.collectAsStateWithLifecycle()
    val excludeSmall by MeloraSettings.localExcludeSmall.collectAsStateWithLifecycle()
    val autoFillInfo by MeloraSettings.localAutoFillInfo.collectAsStateWithLifecycle()
    val scanning by LocalMediaStore.isScanning.collectAsStateWithLifecycle()
    val songs by LocalMediaStore.songs.collectAsStateWithLifecycle()
    var hasAllFilesAccess by remember { mutableStateOf(isAllFilesAccessGranted()) }

    fun startScan() {
        PlaybackController.postMessage(context, "正在扫描本地音频…")
        scope.launch {
            runCatchingCancellable { LocalMediaScanner.scan(context) }
                .onSuccess { result ->
                    PlaybackController.postMessage(context, result.message)
                    runCatchingCancellable { LocalMediaScanner.enrich(context) }
                }
                .onFailure { PlaybackController.postMessage(context, "扫描失败：${it.message ?: "未知错误"}") }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startScan()
        } else {
            PlaybackController.postMessage(context, "未获得音频读取权限，无法扫描本地歌曲")
        }
    }

    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        hasAllFilesAccess = isAllFilesAccessGranted()
        PlaybackController.postMessage(
            context,
            if (hasAllFilesAccess) "已获得所有文件访问权限" else "未获得所有文件访问权限",
        )
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val current = folders
            if (uri.toString() !in current) {
                MeloraSettings.updateLocalFolders(current + uri.toString())
            }
            // 使用自定义文件夹后自动关闭安卓媒体库
            MeloraSettings.updateLocalUseMediaStore(false)
            PlaybackController.postMessage(context, "已添加自定义文件夹，安卓媒体库已关闭")
            startScan()
        }
    }

    fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appSettings = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
        runCatching { allFilesAccessLauncher.launch(appSettings) }
            .recoverCatching {
                allFilesAccessLauncher.launch(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            .onFailure { PlaybackController.postMessage(context, "无法打开所有文件访问权限设置") }
    }

    fun requestScan() {
        if (!LocalMediaScanner.requiresAudioPermission()) {
            startScan()
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) startScan() else permissionLauncher.launch(permission)
    }

    SettingsSubPageScaffold(title = "本地歌曲", onBack = onBack) {
        item {
            SectionCaption("本地媒体库")
        }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF16A34A).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "本地音乐索引",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = SettingsTextMain,
                        )
                        Text(
                            text = if (songs.isNotEmpty()) "已收录 ${songs.size} 首设备音频" else "尚未收录本地音频",
                            fontSize = 12.sp,
                            color = SettingsTextSub,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Surface(
                        onClick = { if (!scanning) requestScan() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (scanning) MeloraAppearance.softFill else MeloraAppearance.tintBlue,
                    ) {
                        Text(
                            text = if (scanning) "扫描中…" else "开始扫描",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (scanning) SettingsTextSub else SettingsBrandBlue,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item {
                SectionCaption("文件访问权限")
            }
            item {
                SettingsCard(contentPadding = PaddingValues(0.dp)) {
                    SettingsEntryItem(
                        icon = Icons.Outlined.FolderOpen,
                        iconTint = MeloraAppearance.brand,
                        title = "管理所有文件",
                        desc = if (hasAllFilesAccess) {
                            "已授权；便于删除本地媒体及写入歌曲信息"
                        } else {
                            "未授权；开启后可直接管理共享存储中的本地媒体文件"
                        },
                        onClick = ::openAllFilesAccessSettings,
                    )
                }
            }
        }

        item {
            SectionCaption("扫描过滤规则")
        }
        item {
            SettingsCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SwitchItem(
                    "使用安卓媒体库",
                    "自动索引系统媒体库中已收录的音乐；添加自定义目录后会自动关闭",
                    useMediaStore,
                ) {
                    MeloraSettings.updateLocalUseMediaStore(it)
                    if (!scanning) requestScan()
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))
                SwitchItem(
                    "排除短音频",
                    "跳过时长小于 60 秒的音频（如通知提示音、语音消息）",
                    excludeShort,
                ) {
                    MeloraSettings.updateLocalExcludeShort(it)
                    if (!scanning) requestScan()
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))
                SwitchItem(
                    "排除小音频",
                    "跳过体积小于 1 MB 的音频（如缓存片段、短铃声）",
                    excludeSmall,
                ) {
                    MeloraSettings.updateLocalExcludeSmall(it)
                    if (!scanning) requestScan()
                }
                SettingsDivider(Modifier.padding(horizontal = 14.dp))
                SwitchItem(
                    "播放时补全缺失信息",
                    "首次播放时补全缺失的歌手、专辑、年份、封面和歌词；MP3/FLAC 在权限允许时写入文件",
                    autoFillInfo,
                ) {
                    MeloraSettings.updateLocalAutoFillInfo(it)
                }
            }
        }

        item {
            SectionCaption("自定义扫描目录")
        }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自定义目录",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = SettingsTextMain,
                        )
                        Text(
                            text = when {
                                folders.isNotEmpty() -> "扫描以下文件夹（共 ${folders.size} 个）"
                                useMediaStore -> "尚未添加自定义目录，当前使用系统媒体库"
                                else -> "尚未启用任何本地音乐目录"
                            },
                            fontSize = 12.sp,
                            color = SettingsTextSub,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Surface(
                        onClick = { folderPicker.launch(null) },
                        shape = RoundedCornerShape(10.dp),
                        color = MeloraAppearance.tintBlue,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.CreateNewFolder,
                                contentDescription = null,
                                tint = SettingsBrandBlue,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "添加",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = SettingsBrandBlue,
                            )
                        }
                    }
                }

                if (folders.isNotEmpty()) {
                    SettingsDivider(Modifier.padding(vertical = 10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        folders.forEach { tree ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MeloraAppearance.softFill)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SettingsBrandBlue.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = SettingsBrandBlue,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = displayTreeName(tree),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = SettingsTextMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = decodedPath(tree),
                                        fontSize = 11.sp,
                                        color = SettingsTextSub,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    onClick = {
                                        MeloraSettings.updateLocalFolders(folders - tree)
                                        if (!scanning) startScan()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MeloraAppearance.tintRed,
                                ) {
                                    Text(
                                        text = "移除",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFDC2626),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isAllFilesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun displayTreeName(tree: String): String {
    val decoded = decodedPath(tree)
    return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { "自定义目录" }
}

private fun decodedPath(tree: String): String {
    val decoded = runCatching { android.net.Uri.decode(tree) }.getOrDefault(tree)
    return decoded.substringAfterLast("tree/").substringBefore("/document")
}
