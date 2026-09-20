package com.leyu.melora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.BuildConfig
import com.leyu.melora.ui.theme.MeloraAppearance
import java.util.Locale

internal val SettingsTextMain: Color get() = MeloraAppearance.textMain
internal val SettingsTextSub: Color get() = MeloraAppearance.textSub
internal val SettingsCardBg: Color get() = MeloraAppearance.card
internal val SettingsBrandBlue: Color get() = MeloraAppearance.brand
internal val SettingsDividerSoft: Color get() = MeloraAppearance.divider

internal val QualityOptions = listOf(
    "128k" to "128k 标清",
    "320k" to "320k 高品",
    "flac" to "FLAC 无损",
    "flac24bit" to "Hi-Res 24bit",
)

@Composable
internal fun SettingsPageContent(
    subPage: SettingsSubPage,
    onOpenDrawer: () -> Unit,
    onNavigate: (SettingsSubPage) -> Unit,
    onBack: () -> Unit,
) {
    when (subPage) {
        SettingsSubPage.None -> SettingsMainMenu(onOpenDrawer = onOpenDrawer, onNavigate = onNavigate)
        SettingsSubPage.Basic -> BasicSettingsSubPage(onBack = onBack)
        SettingsSubPage.CustomSource -> ChromeScaffold(topBar = { SubPageTopBar(title = "自定义源管理", onBack = onBack) }) {
            LxSourceScreen()
        }
        SettingsSubPage.Playback -> PlaybackSettingsSubPage(onBack = onBack)
        SettingsSubPage.Download -> DownloadSettingsSubPage(onBack = onBack)
        SettingsSubPage.DesktopLyrics -> DesktopLyricsSubPage(onBack = onBack)
        SettingsSubPage.Others -> OtherSettingsSubPage(onBack = onBack)
        SettingsSubPage.About -> AboutSubPage(onBack = onBack)
        SettingsSubPage.LocalMusic -> LocalMusicSettingsSubPage(onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    ChromeScaffold(topBar = { SubPageTopBar(title = title, onBack = onBack) }) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = chromeContentPadding(PaddingValues(top = 8.dp, bottom = 32.dp)),
            content = content,
        )
    }
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SettingsCardBg,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
internal fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(color = SettingsDividerSoft, thickness = 0.6.dp, modifier = modifier)
}

@Composable
internal fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = SettingsTextSub,
        modifier = modifier.padding(start = 6.dp, top = 4.dp),
    )
}

private data class SettingsMenuEntry(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val description: String,
    val destination: SettingsSubPage,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsMainMenu(
    onOpenDrawer: () -> Unit,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    val primaryEntries = listOf(
        SettingsMenuEntry(Icons.Outlined.Tune, Color(0xFF2563EB), "基本设置", "系统外观、列表显示、下拉刷新", SettingsSubPage.Basic),
        SettingsMenuEntry(Icons.Outlined.Code, Color(0xFF059669), "自定义源", "管理音源脚本、离线源导入与导出", SettingsSubPage.CustomSource),
        SettingsMenuEntry(Icons.Outlined.PlayCircle, Color(0xFFD97706), "播放设置", "启动播放、播放进度、音质偏好", SettingsSubPage.Playback),
        SettingsMenuEntry(Icons.Outlined.Download, Color(0xFF0D9488), "下载设置", "下载路径、音质、命名与嵌入标签", SettingsSubPage.Download),
        SettingsMenuEntry(Icons.Outlined.Subtitles, Color(0xFF7C3AED), "桌面歌词", "悬浮窗歌词、字体、透明度与排版", SettingsSubPage.DesktopLyrics),
    )
    val secondaryEntries = listOf(
        SettingsMenuEntry(Icons.Outlined.MusicNote, Color(0xFF16A34A), "本地歌曲", "媒体库扫描、自定义文件夹与排除规则", SettingsSubPage.LocalMusic),
        SettingsMenuEntry(Icons.Outlined.CleaningServices, Color(0xFF0284C7), "其他设置", "缓存清理、备份与配置恢复", SettingsSubPage.Others),
        SettingsMenuEntry(Icons.Outlined.Info, Color(0xFF4B5563), "关于乐屿", "版本 v${BuildConfig.VERSION_NAME} · Media3 播放引擎", SettingsSubPage.About),
    )

    ChromeScaffold(topBar = {
        TopAppBar(
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Rounded.Menu, contentDescription = "打开侧栏", tint = SettingsTextMain)
                }
            },
            title = {
                Text(
                    text = "设置",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = SettingsTextMain,
                )
            },
        )
    }) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = chromeContentPadding(PaddingValues(top = 0.dp, bottom = 24.dp)),
        ) {
            item { SettingsMenuGroup(primaryEntries, onNavigate) }
            item { SettingsMenuGroup(secondaryEntries, onNavigate) }
        }
    }
}

@Composable
private fun SettingsMenuGroup(
    entries: List<SettingsMenuEntry>,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    SettingsCard(contentPadding = PaddingValues(0.dp)) {
        entries.forEachIndexed { index, entry ->
            SettingsEntryItem(
                icon = entry.icon,
                iconTint = entry.iconTint,
                title = entry.title,
                desc = entry.description,
                onClick = { onNavigate(entry.destination) },
            )
            if (index < entries.lastIndex) {
                SettingsDivider(Modifier.padding(start = 56.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubPageTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = SettingsTextMain)
            }
        },
        title = { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain) },
    )
}

@Composable
internal fun SettingsEntryItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    desc: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
            if (!desc.isNullOrBlank()) {
                Text(desc, fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = SettingsTextSub, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsTextMain)
            Text(subtitle, fontSize = 11.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SettingsBrandBlue,
                uncheckedTrackColor = MeloraAppearance.softFill,
            ),
        )
    }
}

@Composable
internal fun SettingsChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) SettingsBrandBlue else MeloraAppearance.softFill,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) Color.White else SettingsTextMain,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CacheClearRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    sizeText: String,
    enabled: Boolean = true,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = SettingsTextMain,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sizeText,
            fontSize = 12.sp,
            color = SettingsTextSub,
            modifier = Modifier.padding(end = 10.dp),
        )
        Surface(
            onClick = onClear,
            shape = RoundedCornerShape(8.dp),
            color = MeloraAppearance.softFill,
            enabled = enabled,
        ) {
            Text(
                text = "清除",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) SettingsBrandBlue else SettingsTextSub.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 13.sp, color = SettingsTextMain)
            Text(valueText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SettingsBrandBlue)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
            thumb = {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(SettingsBrandBlue),
                )
            },
            colors = SliderDefaults.colors(
                activeTrackColor = SettingsBrandBlue,
                inactiveTrackColor = MeloraAppearance.segmentTrack,
            ),
        )
    }
}

internal fun formatCacheSize(mb: Int): String =
    if (mb >= 1024) String.format(Locale.ROOT, "%.1f GB", mb / 1024f) else "$mb MB"
