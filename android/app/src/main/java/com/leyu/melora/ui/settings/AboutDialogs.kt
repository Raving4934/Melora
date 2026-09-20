package com.leyu.melora.ui.settings

import com.leyu.melora.ui.theme.SystemBarsVisibility
import com.leyu.melora.ui.common.runCatchingCancellable
import android.content.Intent
import java.net.URLEncoder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.leyu.melora.BuildConfig
import com.leyu.melora.playback.CrashLogger
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UpdateChecker
import com.leyu.melora.playback.UpdateResult
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AboutModal(val title: String) {
    Update("检测更新"),
    Privacy("用户隐私协议"),
    Licenses("开源许可"),
    Disclaimer("免责声明"),
    CrashLog("崩溃日志"),
    Faq("常见问题"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutModalSheet(
    modal: AboutModal,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SettingsCardBg,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        SystemBarsVisibility()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // 顶栏：标题 + 右侧关闭圆钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = modal.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsTextMain,
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MeloraAppearance.softFill)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = SettingsTextSub,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            HorizontalDivider(color = SettingsDividerSoft, thickness = 0.6.dp)

            // 内容区
            when (modal) {
                AboutModal.Update -> UpdateContent()
                AboutModal.Privacy -> PrivacyPolicyContent()
                AboutModal.Licenses -> OpenSourceLicensesContent()
                AboutModal.Disclaimer -> DisclaimerContent()
                AboutModal.CrashLog -> CrashLogContent()
                AboutModal.Faq -> FaqContent()
            }
        }
    }
}

// ---------------- 1. 检测更新 ----------------
@Composable
private fun UpdateContent() {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }

    LaunchedEffect(Unit) {
        result = UpdateChecker.check(BuildConfig.VERSION_NAME)
        checking = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (checking) {
            Spacer(Modifier.height(30.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = SettingsBrandBlue,
                strokeWidth = 3.5.dp,
            )
            Spacer(Modifier.height(18.dp))
            Text("正在连接 GitHub 检查最新发布…", fontSize = 14.sp, color = SettingsTextSub)
            Spacer(Modifier.height(30.dp))
        } else {
            val res = result
            if (res != null && res.hasUpdate) {
                // 发现新版本
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(SettingsBrandBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Update, contentDescription = null, tint = SettingsBrandBlue, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(14.dp))
                val displayVersion = res.latestVersion.removePrefix("android-").ifBlank { res.latestVersion }
                Text("发现新版本 $displayVersion", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SettingsTextMain)
                Text("当前安装版本: v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 4.dp))

                if (res.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MeloraAppearance.softFill,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text("更新日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SettingsTextMain)
                            Spacer(Modifier.height(6.dp))
                            Text(res.releaseNotes, fontSize = 12.sp, color = SettingsTextSub, lineHeight = 18.sp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val targetUrl = res.downloadUrl.ifBlank { res.pageUrl }
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, targetUrl.toUri()))
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsBrandBlue),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("下载 Android APK", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // 已是当前版本或尚未找到公开 Android 发布
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (res?.message == "尚未找到Android发布版本") {
                        "尚未找到 Android 发布版本"
                    } else {
                        "当前无需更新"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SettingsTextMain,
                )
                Text(
                    text = res?.message ?: "当前版本 v${BuildConfig.VERSION_NAME} 已是最新稳定版，暂无更新发布。",
                    fontSize = 13.sp,
                    color = SettingsTextSub,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Raving4934/Melora/releases".toUri()))
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("查看 GitHub Releases 仓库页面", fontSize = 13.sp, color = SettingsTextMain)
                }
            }
        }
    }
}

// ---------------- 2. 用户隐私协议 ----------------
@Composable
private fun PrivacyPolicyContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PolicySection(
            title = "1. 本地数据与账号",
            content = "乐屿不要求注册或登录；收藏、播放记录与设置等内容主要保存在设备本地。使用在线音源、听书服务或检测更新时，应用会按相应用途直接访问相关第三方接口。",
        )
        PolicySection(
            title = "2. 设备信息与网络数据",
            content = "应用不主动读取通讯录、短信、位置等与功能无关的信息，也不内置广告或统计 SDK。在线音源、播放与更新功能会触发对相应第三方接口的请求；第三方服务可能按其自身政策记录必要的请求信息，请以相应服务的说明为准。",
        )
        PolicySection(
            title = "3. 网络访问范围",
            content = "网络权限（INTERNET）主要用于以下功能：\n• 按您选择的音源或听书服务请求歌曲、章节、封面与同步歌词；\n• 播放在线内容时直连相应内容接口；\n• 您主动点击检测更新时，向 GitHub API 查询 Android Release 与 APK 信息。",
        )
        PolicySection(
            title = "4. 设备权限的透明使用",
            content = "• 前台服务与媒体通知（FOREGROUND_SERVICE）：仅用于保证后台切歌播放不断流与通知栏控制器响应；\n• 本地存储写入（WRITE_EXTERNAL_STORAGE，针对低版本系统）：仅在您主动发起音频离线下载时，用于保存音频文件至公共下载目录；\n• 系统通知（POST_NOTIFICATIONS）：仅用于展示当前正在播放的曲目状态与下载任务进度。",
        )
        PolicySection(
            title = "5. 本地数据管理",
            content = "收藏歌单、最近播放、自建歌单与自定义设置主要保存在应用私有沙盒文件（user-library.json）中。您可以在「其他设置」中清理缓存或管理本地数据；应用卸载后，应用私有沙盒中的记录会由系统一并清除。",
        )
    }
}

// ---------------- 3. 开源许可 ----------------
@Composable
private fun OpenSourceLicensesContent() {
    val context = LocalContext.current
    var manifest by remember { mutableStateOf<LicenseManifest?>(null) }
    var loadError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatchingCancellable {
            withContext(Dispatchers.IO) { LicenseManifest.load(context) }
        }.onSuccess { loaded ->
            manifest = loaded
        }.onFailure {
            loadError = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "乐屿 · Melora 遵循自由开源精神开发，特此致敬并列举项目所依赖的核心开源基础库：",
            fontSize = 12.sp,
            color = SettingsTextSub,
            lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        when {
            manifest != null -> {
                val loaded = requireNotNull(manifest)
                loaded.aboutEntries.forEach { entry ->
                    LicenseEntryCard(entry = entry, manifest = loaded)
                }
            }
            loadError -> {
                Text(
                    text = "离线许可清单暂时无法读取，请稍后重试或反馈此问题。",
                    fontSize = 13.sp,
                    color = SettingsTextSub,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(vertical = 28.dp),
                )
            }
            else -> Unit // 预留固定高度，不短暂闪现加载卡片或改变抽屉尺寸。
        }
    }
}

@Composable
private fun LicenseEntryCard(
    entry: LicenseEntry,
    manifest: LicenseManifest,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MeloraAppearance.softFill,
        border = MeloraAppearance.chipBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SettingsTextMain,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SettingsBrandBlue.copy(alpha = 0.12f),
                ) {
                    Text(
                        entry.licenseIds.joinToString(" / "),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsBrandBlue,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(entry.description, fontSize = 12.sp, color = SettingsTextSub, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (expanded) "收起完整声明" else "查看完整声明", fontSize = 12.sp)
            }
            if (expanded) {
                HorizontalDivider(color = SettingsDividerSoft, thickness = 0.6.dp)
                entry.source?.let { source ->
                    Text(
                        text = "核对来源：$source",
                        fontSize = 10.sp,
                        color = SettingsTextSub,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                entry.sourceUrl?.let { sourceUrl ->
                    Text(
                        text = sourceUrl,
                        fontSize = 10.sp,
                        color = SettingsTextSub,
                        lineHeight = 14.sp,
                    )
                }
                entry.notices.forEach { notice ->
                    Text(
                        text = notice,
                        fontSize = 11.sp,
                        color = SettingsTextMain,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                entry.licenseIds.forEach { licenseId ->
                    val license = manifest.textFor(licenseId)
                    Text(
                        text = license.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SettingsTextMain,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = license.text,
                        fontSize = 10.sp,
                        color = SettingsTextSub,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ---------------- 4. 免责声明 ----------------
@Composable
private fun DisclaimerContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PolicySection(
            title = "1. 开源技术研究与学习定位",
            content = "乐屿 · Melora 是一款纯粹出于个人技术研究、跨源音源架构探索（Media3 + QuickJS 动态调度）而构建的非商业开源项目。项目代码仅供技术交流与个人本地媒体管理学习使用。",
        )
        PolicySection(
            title = "2. 无版权音视频托管与分发说明",
            content = "本软件本身不运营、不存储、不分发、不上载任何具有知识产权保护的音频或视频文件。应用内的检索试听、歌词渲染与封面抓取功能，完全由第三方开放接口或用户自行配置导入的外部脚本实时执行。",
        )
        PolicySection(
            title = "3. 合规使用倡议与法律责任归属",
            content = "用户使用本应用检索或获取任何网络公开内容时，应遵守所在国家或地区的相关版权法规与第三方平台用户协议。若因非个人合理使用、商业转售或未授权二次分发引发任何版权争议，由使用者自行承担全部责任。",
        )
        PolicySection(
            title = "4. 尊重创作者与正版音乐支持",
            content = "我们坚决倡导并尊重数字音乐与音频创作者的合法权益。如果您喜爱某位创作者的单曲或某部有声书作品，建议前往相关官方版权服务商开通付费订阅或购买正版专辑，以支持优质创作生态。",
        )
        PolicySection(
            title = "5. 侵权联系与处理",
            content = "如相关内容侵犯了您的合法权益，请到 GitHub Issue 联系我们，我们会尽快核实并处理。",
        )
        PolicySection(
            title = "6. 禁止商业用途",
            content = "本项目不接受任何商业合作、广告植入或赞助。任何个人或组织不得将本软件及其源码用于商业用途，包括但不限于收费分发、广告变现、捆绑销售或以本软件提供付费服务。因商业使用引发的一切法律纠纷与赔偿责任，由使用者自行承担。",
        )
    }
}

// ---------------- 5. 崩溃日志 ----------------
@Composable
private fun CrashLogContent() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf<String?>(null) }
    var logLoaded by remember { mutableStateOf(false) }
    val deviceInfo = remember { CrashLogger.getDeviceInfo() }

    LaunchedEffect(Unit) {
        logText = withContext(Dispatchers.IO) { CrashLogger.readLog() }
        logLoaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        val currentLog = logText
        if (!logLoaded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = SettingsBrandBlue,
                    strokeWidth = 2.5.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text("正在读取崩溃记录…", fontSize = 13.sp, color = SettingsTextSub)
            }
        } else if (!currentLog.isNullOrBlank()) {
            Text(
                "检测到应用异常记录，您可以复制后向开发者反馈，或清空诊断缓存：",
                fontSize = 12.sp,
                color = SettingsTextSub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MeloraAppearance.softFill,
                border = MeloraAppearance.chipBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = currentLog,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = SettingsTextMain,
                        lineHeight = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        CrashLogger.clearLog()
                        logText = null
                        PlaybackController.postMessage(context, "已清空崩溃日志")
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("清空日志", fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Melora", currentLog)))
                            PlaybackController.postMessage(context, "已复制崩溃日志到剪贴板")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsBrandBlue),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制日志", fontSize = 13.sp)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("暂无崩溃记录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SettingsTextMain)
                Text("系统与 Media3 播放引擎运行正常，未捕获到未处理异常。", fontSize = 12.sp, color = SettingsTextSub, modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MeloraAppearance.softFill,
                    border = MeloraAppearance.chipBorder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("当前运行环境诊断", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SettingsTextMain)
                        Spacer(Modifier.height(4.dp))
                        Text(deviceInfo, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SettingsTextSub, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Melora", deviceInfo)))
                            PlaybackController.postMessage(context, "已复制设备信息到剪贴板")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制设备环境信息", fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------- 6. 常见问题 ----------------
@Composable
private fun FaqContent() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        logText = withContext(Dispatchers.IO) { CrashLogger.readLog() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PolicySection(
            title = "1. 如何确认下载的是官方版本？",
            content = "本项目只通过 GitHub 发布，其他渠道均为第三方转载发布，请自行鉴别可信度。本项目没有任何媒体账号，谨防被骗！",
        )
        PolicySection(
            title = "2. 遇到广告、引流、异常权限申请或签名不一致提示？",
            content = "使用过程中如遇到广告 · 引流或特殊权限申请，或升级提示签名不一致，表明你当前版本为第三方修改版。为了你的安全，请即刻卸载！",
        )
        PolicySection(
            title = "3. 遇到软件 Bug 如何反馈？",
            content = "如遇软件 bug，可复制崩溃日志到 GitHub 提交 Issue（下方直达链接能快速填充日志）。",
        )

        Button(
            onClick = {
                val body = buildIssueBody(logText)
                val url = "https://github.com/Raving4934/Melora/issues/new?body=" +
                    URLEncoder.encode(body, "UTF-8")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SettingsBrandBlue),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("前往 GitHub 提交 Issue", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun buildIssueBody(log: String?): String {
    val logSection = log?.takeIf { it.isNotBlank() }?.take(4000) ?: "（暂无崩溃日志）"
    return """
        ### 问题描述
        <!-- 请补充复现步骤、截图或其他说明 -->

        ### 设备信息
        ${CrashLogger.getDeviceInfo()}

        ### 崩溃日志
        $logSection
    """.trimIndent()
}

@Composable
private fun PolicySection(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = SettingsTextMain)
        Text(content, fontSize = 12.5.sp, color = SettingsTextSub, lineHeight = 18.sp)
    }
}
