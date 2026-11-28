package com.leyu.melora.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.BackupDestination
import com.leyu.melora.playback.BackupManager
import com.leyu.melora.playback.BackupSchedule
import com.leyu.melora.playback.WebDavBackup
import com.leyu.melora.playback.WebDavConfig
import com.leyu.melora.playback.WebDavConfigStore
import com.leyu.melora.playback.WebDavSync
import com.leyu.melora.ui.common.MeloraAlertDialog
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FeedbackArea { BACKUPS, PREFERENCES, RECORDS }
private enum class BackupMenu { BACKUP, RESTORE }

private val BackupDestination.label get() = when (this) {
    BackupDestination.OFF -> "关闭"
    BackupDestination.LOCAL -> "本地"
    BackupDestination.NETWORK -> "网络"
    BackupDestination.BOTH -> "本地和网络"
}
private val automaticDestinations = listOf(
    BackupDestination.BOTH, BackupDestination.LOCAL, BackupDestination.NETWORK, BackupDestination.OFF,
)

private fun automaticLabel(target: BackupDestination) = when (target) {
    BackupDestination.BOTH -> "备份至本地与网络"
    BackupDestination.LOCAL -> "备份至本地"
    BackupDestination.NETWORK -> "备份至网络"
    BackupDestination.OFF -> "关闭"
}

/** 恢复时从已授权的目录打开，也允许选择旧版导出的其它文件。 */
private class OpenBackupDocument(private val tree: Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            tree?.let {
                val root = DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
            }
        }
}

@Composable
internal fun SyncSettingsSubPage(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val preferences by BackupSchedule.settings.collectAsStateWithLifecycle()
    val lastAutomatic by BackupSchedule.lastAutomaticResult.collectAsStateWithLifecycle()
    val recoveryAvailable by BackupManager.hasExportRecovery.collectAsStateWithLifecycle()
    val syncEnabled by WebDavSync.enabled.collectAsStateWithLifecycle()
    val syncStatus by WebDavSync.status.collectAsStateWithLifecycle()
    val syncConflicts by WebDavSync.conflicts.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf<WebDavConfig?>(null) }
    var ready by remember { mutableStateOf(false) }
    var busyAreas by remember { mutableStateOf(emptySet<FeedbackArea>()) }
    var notice by remember { mutableStateOf("") }
    var preferenceNotice by remember { mutableStateOf("") }
    var recordNotice by remember { mutableStateOf("") }
    var configNotice by remember { mutableStateOf("") }
    var showConflicts by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf<BackupMenu?>(null) }
    var pendingDirectoryTarget by rememberSaveable { mutableStateOf<BackupDestination?>(null) }
    var pendingAutomatic by rememberSaveable { mutableStateOf(false) }
    var localRestore by rememberSaveable { mutableStateOf<Uri?>(null) }
    var remoteRestore by remember { mutableStateOf<NetworkBackupListing?>(null) }

    fun runAction(message: String, area: FeedbackArea = FeedbackArea.BACKUPS, action: suspend () -> String) {
        if (area in busyAreas) return
        fun report(text: String) {
            when (area) {
                FeedbackArea.BACKUPS -> notice = text
                FeedbackArea.PREFERENCES -> preferenceNotice = text
                FeedbackArea.RECORDS -> recordNotice = text
            }
        }
        busyAreas = busyAreas + area
        if (message.isNotBlank()) report(message)
        scope.launch {
            try {
                report(runCatchingCancellable { action() }.getOrElse { it.localizedMessage ?: "操作失败，请重试" })
            } finally {
                busyAreas = busyAreas - area
            }
        }
    }

    LaunchedEffect(context) {
        runCatchingCancellable { BackupSchedule.load(context) }
            .onFailure { notice = it.localizedMessage ?: "无法读取备份设置" }
        runCatchingCancellable { withContext(Dispatchers.IO) { WebDavConfigStore.load(context) } }
            .onSuccess { saved = it }.onFailure { configNotice = "无法读取已保存的 WebDAV 配置，请重新输入" }
        ready = true
    }

    suspend fun applyTarget(target: BackupDestination, automatic: Boolean): String {
        if (!automatic) return BackupSchedule.backupNow(context, target)
        BackupSchedule.setAutomatic(context, target)
        return if (target == BackupDestination.OFF) "自动备份已关闭" else "已开启每日备份至${target.label}"
    }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = pendingDirectoryTarget
        val automatic = pendingAutomatic
        pendingDirectoryTarget = null
        pendingAutomatic = false
        if (uri != null) runAction("正在设置备份目录…", if (automatic) FeedbackArea.PREFERENCES else FeedbackArea.BACKUPS) {
            BackupSchedule.chooseDirectory(context, uri)
            if (target == null) "已设置本地备份目录" else applyTarget(target, automatic)
        }
    }
    val restorePicker = rememberLauncherForActivityResult(remember(preferences.directory) {
        OpenBackupDocument(preferences.directory)
    }) { uri -> localRestore = uri }
    val recoveryPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runAction("正在导出副本…") { BackupManager.saveExportRecovery(context, uri).getOrThrow() }
    }

    fun openSystemPicker(open: () -> Unit) {
        context.launchSystemFilePicker(onUnavailable = {
            pendingDirectoryTarget = null
            pendingAutomatic = false
        }, launch = open)
    }

    fun selectTarget(target: BackupDestination, automatic: Boolean) {
        menu = null
        if (target.local && preferences.directory == null) {
            pendingDirectoryTarget = target
            pendingAutomatic = automatic
            openSystemPicker { directoryPicker.launch(null) }
        } else {
            runAction(if (automatic) "正在更新自动备份…" else "正在备份至${target.label}…",
                if (automatic) FeedbackArea.PREFERENCES else FeedbackArea.BACKUPS) {
                applyTarget(target, automatic)
            }
        }
    }

    val directoryLabel = remember(preferences.directory, preferences.directoryName) {
        val tree = preferences.directory
        if (tree?.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(tree)) {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val volume = id.substringBefore(':').let { if (it == "primary") "内部存储" else it }
            listOf(volume, id.substringAfter(':', "")).filter(String::isNotBlank).joinToString(" / ")
        } else preferences.directoryName
    }
    val recordsBusy = FeedbackArea.RECORDS in busyAreas
    val recordsAvailable = ready && !recordsBusy
    val preferencesAvailable = ready && FeedbackArea.PREFERENCES !in busyAreas
    val backupsAvailable = ready && FeedbackArea.BACKUPS !in busyAreas
    SettingsSubPageScaffold(title = "同步与备份", onBack = onBack) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionCaption("WebDav设置", Modifier.weight(1f))
                Box(Modifier.padding(end = 6.dp).size(14.dp)) {
                    if (recordsBusy) {
                        val label = "正在处理 WebDAV 同步"
                        if (MeloraAppearance.isEInk) {
                            Icon(Icons.Outlined.HourglassEmpty, contentDescription = label, tint = SettingsTextMain)
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics { contentDescription = label },
                                color = SettingsBrandBlue,
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsCard(contentPadding = PaddingValues(0.dp)) {
                WebDavConnectionEntry(config = saved, ready = ready, enabled = busyAreas.isEmpty()) {
                    saved = it
                    configNotice = ""
                    recordNotice = ""
                }
                OperationNotice(configNotice)
                SettingsDivider()
                SwitchItem("同步我的记录", "跨设备同步收藏，歌单，最近播放，听书断点",
                    syncEnabled, enabled = recordsAvailable && (syncEnabled || saved != null)) { value ->
                    runAction("", FeedbackArea.RECORDS) {
                        WebDavSync.setEnabled(context, value)
                        ""
                    }
                }
                if (syncEnabled) {
                    SettingsDivider()
                    SettingsEntryItem(Icons.Outlined.Sync, SettingsBrandBlue, "立即同步",
                        syncStatus.ifBlank { "等待同步" },
                        enabled = recordsAvailable && saved != null) {
                        runAction("", FeedbackArea.RECORDS) {
                            WebDavSync.syncNow(context)
                            ""
                        }
                    }
                    if (syncConflicts.isNotEmpty()) {
                        SettingsDivider()
                        SettingsEntryItem(
                            icon = Icons.Outlined.SyncProblem,
                            iconTint = SettingsBrandBlue,
                            title = "处理同步冲突",
                            desc = "${syncConflicts.size} 项记录需要你选择",
                            enabled = recordsAvailable && saved != null,
                            onClick = { showConflicts = true },
                        )
                    }
                }
                OperationNotice(recordNotice)
            }
        }
        item { SectionCaption("备份设置") }
        item {
            SettingsCard(contentPadding = PaddingValues(0.dp)) {
                SettingsChoiceMenu(
                    title = "自动备份",
                    subtitle = "选择每日备份的保存位置",
                    value = preferences.automatic,
                    options = automaticDestinations,
                    label = ::automaticLabel,
                    onSelect = { selectTarget(it, automatic = true) },
                    enabled = preferencesAvailable,
                    optionEnabled = { !it.network || saved != null },
                )
                if (preferences.automatic != BackupDestination.OFF) OperationNotice(lastAutomatic.orEmpty())
                SettingsDivider()
                SwitchItem("仅保留最新本地备份", checked = preferences.keepLatest, enabled = preferencesAvailable) {
                    runAction("", FeedbackArea.PREFERENCES) {
                        BackupSchedule.setKeepLatest(context, it)
                        ""
                    }
                }
                OperationNotice(preferenceNotice)
            }
        }
        item { SectionCaption("备份与恢复") }
        item {
            SettingsCard(contentPadding = PaddingValues(0.dp)) {
                SettingsEntryItem(Icons.Outlined.FolderOpen, SettingsBrandBlue, "本地备份目录",
                    directoryLabel ?: "选择保存备份的文件夹", enabled = backupsAvailable) {
                    openSystemPicker { directoryPicker.launch(preferences.directory) }
                }
                SettingsDivider()
                SettingsEntryItem(Icons.Outlined.Backup, SettingsBrandBlue, "备份", "保存设置、用户库、播放断点与音源脚本", enabled = backupsAvailable) { menu = BackupMenu.BACKUP }
                SettingsDivider()
                SettingsEntryItem(Icons.Outlined.Restore, SettingsBrandBlue, "恢复", "从本地文件或网络备份恢复", enabled = backupsAvailable) { menu = BackupMenu.RESTORE }
                if (recoveryAvailable) {
                    SettingsDivider()
                    SettingsEntryItem(Icons.Outlined.FolderOpen, SettingsBrandBlue, "另存上次保留的原文件副本", enabled = backupsAvailable) {
                        openSystemPicker { recoveryPicker.launch("melora-recovered-backup.json") }
                    }
                }
                OperationNotice(notice)
            }
        }
    }

    if (showConflicts && syncEnabled) {
        RecordConflictSheet(syncConflicts, onDismiss = { showConflicts = false }) { choices ->
            WebDavSync.resolve(context, choices)
        }
    }
    menu?.let { action ->
        val restoring = action == BackupMenu.RESTORE
        BackupDestinationSheet(
            restore = restoring,
            networkReady = saved != null,
            onDismiss = { menu = null },
            networkRestoreConfig = saved.takeIf { restoring },
            onSelectNetworkBackup = {
                remoteRestore = it
                menu = null
            },
        ) { target ->
            menu = null
            if (restoring) openSystemPicker { restorePicker.launch(arrayOf("*/*")) }
            else selectTarget(target, automatic = false)
        }
    }
    val listing = remoteRestore
    val entry = listing?.selected
    if (localRestore != null || entry != null) {
        val uri = localRestore
        var localName by remember(uri) { mutableStateOf<String?>(null) }
        LaunchedEffect(uri) {
            if (uri != null) localName = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
            }
        }
        LaunchedEffect(listing, entry) {
            if (entry != null) requireNotNull(listing).loadOrigin(entry)
        }
        val name = localName ?: uri?.lastPathSegment?.substringAfterLast('/')
        fun cancel() {
            remoteRestore = null
            localRestore = null
        }
        MeloraAlertDialog(onDismissRequest = ::cancel, containerColor = SettingsCardBg,
            title = { Text("恢复这份备份？", color = SettingsTextMain) },
            text = {
                Column {
                    if (entry != null) {
                        Text(entry.timestamp(), color = SettingsTextSub)
                        Text(
                            requireNotNull(listing).deviceLabel(entry),
                            modifier = Modifier.padding(bottom = 12.dp),
                            color = SettingsTextSub,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    Text(listOfNotNull(name, "备份所含的设置、用户库与播放断点将恢复到本机，音源脚本将合并。",
                        if (syncEnabled) "已开启多设备同步，记录变更也会同步到其他设备。" else null)
                        .joinToString("\n\n"), color = SettingsTextSub)
                }
            },
            confirmButton = { TextButton(onClick = {
                cancel()
                runAction("正在恢复备份…") {
                    if (entry != null) WebDavBackup.restore(context, requireNotNull(listing).config, entry)
                    else BackupManager.import(context, requireNotNull(uri)).getOrThrow()
                }
            }) { Text("恢复", color = SettingsBrandBlue) } },
            dismissButton = { TextButton(onClick = ::cancel) { Text("取消", color = SettingsTextSub) } },
        )
    }
}

@Composable
private fun OperationNotice(text: String) {
    if (text.isNotBlank()) Text(text, color = SettingsTextSub, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
}
