package com.leyu.melora.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.BackupDestination
import com.leyu.melora.playback.BackupFileId
import com.leyu.melora.playback.BackupOrigin
import com.leyu.melora.playback.WebDavBackup
import com.leyu.melora.playback.WebDavConfig
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.theme.MeloraAppearance
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class NetworkBackupListing(val config: WebDavConfig, val entries: List<BackupFileId>) {
    var selected: BackupFileId? = null
    private val origins = mutableStateMapOf<String, BackupOrigin?>()
    private val readSlots = Semaphore(3)

    fun deviceLabel(entry: BackupFileId): String =
        origins[entry.networkName]?.let { "${it.model} · 乐屿 v${it.appVersion}" } ?: "设备 ${entry.device}"

    suspend fun loadOrigin(entry: BackupFileId) = readSlots.withPermit {
        if (entry.networkName !in origins) {
            origins[entry.networkName] = runCatchingCancellable { WebDavBackup.readOrigin(config, entry) }.getOrNull()
        }
    }
}

internal fun BackupFileId.timestamp() =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(createdAt))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupDestinationSheet(
    restore: Boolean,
    networkReady: Boolean,
    onDismiss: () -> Unit,
    networkRestoreConfig: WebDavConfig? = null,
    onSelectNetworkBackup: (NetworkBackupListing) -> Unit = {},
    onChoose: (BackupDestination) -> Unit,
) {
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val chooseDestination by rememberUpdatedState(onChoose)
    val chooseNetworkBackup by rememberUpdatedState(onSelectNetworkBackup)
    var closing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var reading by remember(networkRestoreConfig) { mutableStateOf(false) }
    var showProgress by remember(networkRestoreConfig) { mutableStateOf(false) }
    var networkResult by remember(networkRestoreConfig) { mutableStateOf<Result<NetworkBackupListing>?>(null) }

    val canRead = reading && !closing && !finished && sheetState.targetValue != SheetValue.Hidden
    LaunchedEffect(networkRestoreConfig, canRead) {
        if (!canRead || networkRestoreConfig == null) return@LaunchedEffect
        coroutineScope {
            val indicator = launch {
                delay(350)
                showProgress = true
                delay(300)
            }
            try {
                val result = runCatchingCancellable {
                    NetworkBackupListing(networkRestoreConfig, WebDavBackup.list(networkRestoreConfig))
                }
                if (showProgress) indicator.join() else indicator.cancel()
                currentCoroutineContext().ensureActive()
                if (!closing && !finished && sheetState.targetValue != SheetValue.Hidden) {
                    networkResult = result
                    reading = false
                }
            } finally {
                indicator.cancel()
                showProgress = false
            }
        }
    }

    fun closeThen(action: () -> Unit) {
        if (closing || finished || sheetState.targetValue == SheetValue.Hidden) return
        closing = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible && !finished) {
                    finished = true
                    action()
                }
            } finally {
                closing = false
            }
        }
    }

    fun select(destination: BackupDestination) {
        if (restore && destination == BackupDestination.NETWORK && networkRestoreConfig != null) {
            if (!closing && !finished && sheetState.targetValue != SheetValue.Hidden) reading = true
        } else {
            closeThen { chooseDestination(destination) }
        }
    }

    MeloraBottomSheet(
        onDismissRequest = {
            if (!finished) {
                finished = true
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MeloraAppearance.sheetCard,
    ) {
        val canChoose = !closing && !finished
        val result = networkResult
        val listing = result?.getOrNull()
        val title = when {
            result != null -> "选择网络备份"
            restore -> "恢复"
            else -> "备份"
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .animateContentSize(tween(180))
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                .semantics { paneTitle = title },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
                color = SettingsTextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                listing != null && listing.entries.isNotEmpty() -> NetworkBackupOptions(listing, canChoose) { entry ->
                    closeThen {
                        listing.selected = entry
                        chooseNetworkBackup(listing)
                    }
                }
                result != null -> NetworkRestoreNotice(
                    message = result.fold(
                        onSuccess = { "该目录还没有备份" },
                        onFailure = { it.localizedMessage ?: "无法读取网络备份，请重试。" },
                    ),
                    canRetry = canChoose && !reading,
                    showProgress = showProgress,
                    onRetry = { reading = true },
                    onClose = { closeThen(onDismiss) },
                )
                else -> DestinationOptions(restore, networkReady, canChoose, reading, showProgress, ::select)
            }
        }
    }
}

@Composable
private fun DestinationOptions(
    restore: Boolean,
    networkReady: Boolean,
    canChoose: Boolean,
    reading: Boolean,
    showProgress: Boolean,
    onChoose: (BackupDestination) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DestinationCard(
                label = if (restore) "本地恢复" else "备份至本地",
                icon = Icons.Outlined.PhoneAndroid,
                available = true,
                canChoose = canChoose,
                modifier = Modifier.weight(1f),
                onClick = { onChoose(BackupDestination.LOCAL) },
            )
            DestinationCard(
                label = if (restore) "网络恢复" else "备份至网络",
                icon = Icons.Outlined.Cloud,
                available = networkReady,
                canChoose = canChoose && !reading,
                modifier = Modifier.weight(1f),
                showProgress = showProgress,
                onClick = { onChoose(BackupDestination.NETWORK) },
            )
        }
        if (!restore) {
            DestinationCard(
                label = "备份至本地与网络",
                icon = Icons.Outlined.DeviceHub,
                available = networkReady,
                canChoose = canChoose,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onChoose(BackupDestination.BOTH) },
            )
        }
        if (!networkReady) {
            Text(
                "配置 WebDAV 服务器后可使用网络备份和恢复。",
                color = SettingsTextSub,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NetworkBackupOptions(
    listing: NetworkBackupListing,
    canChoose: Boolean,
    onSelect: (BackupFileId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(listing.entries, key = BackupFileId::networkName) { entry ->
            LaunchedEffect(listing, entry) { listing.loadOrigin(entry) }
            Column(
                Modifier.fillMaxWidth().clickable(enabled = canChoose) { onSelect(entry) }.heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Text(entry.timestamp(), color = SettingsTextMain, fontSize = 16.sp)
                Text(listing.deviceLabel(entry), color = SettingsTextSub, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
    }
}

@Composable
private fun NetworkRestoreNotice(
    message: String,
    canRetry: Boolean,
    showProgress: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, color = SettingsTextSub, fontSize = 14.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onClose) { Text("关闭", color = SettingsTextSub) }
            TextButton(onClick = onRetry, enabled = canRetry) {
                Box(contentAlignment = Alignment.Center) {
                    Text("重试", modifier = Modifier.padding(horizontal = 6.dp),
                        color = if (showProgress) androidx.compose.ui.graphics.Color.Transparent else SettingsBrandBlue)
                    if (showProgress) NetworkBackupProgress(Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NetworkBackupProgress(modifier: Modifier) {
    CircularProgressIndicator(
        modifier = modifier.semantics { contentDescription = "正在读取网络备份" },
        color = SettingsBrandBlue,
        strokeWidth = 2.dp,
    )
}

@Composable
private fun DestinationCard(
    label: String,
    icon: ImageVector,
    available: Boolean,
    canChoose: Boolean,
    modifier: Modifier,
    showProgress: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = available && canChoose,
        shape = RoundedCornerShape(16.dp),
        color = MeloraAppearance.softFill,
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (showProgress) {
                    NetworkBackupProgress(Modifier.size(23.dp))
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (available) SettingsBrandBlue else SettingsBrandBlue.copy(alpha = 0.45f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                label,
                color = if (available) SettingsTextMain else SettingsTextSub,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
