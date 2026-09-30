package com.leyu.melora.ui.my

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.PlaylistSyncPreview
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.PlaylistImportProgress
import com.leyu.melora.playback.sdk.PlaylistImportResult
import com.leyu.melora.playback.sdk.PlaylistImporter
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.rememberSheetDismiss
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistUpdateSheet(
    playlist: UserLibrary.UserPlaylist,
    onDismiss: () -> Unit,
    onUpdated: (UserLibrary.UserPlaylist) -> Unit,
    currentPlaylist: () -> UserLibrary.UserPlaylist? = { UserLibrary.playlists.value.firstOrNull { it.id == playlist.id } },
    readPlaylist: suspend (Context, String, (PlaylistImportProgress) -> Unit) -> PlaylistImportResult = PlaylistImporter::load,
    commit: suspend (PlaylistSyncPreview) -> UserLibrary.UserPlaylist = UserLibrary::commitPlaylistSync,
) {
    val context = LocalContext.current
    val currentProvider by rememberUpdatedState(currentPlaylist)
    val reader by rememberUpdatedState(readPlaylist)
    val committer by rememberUpdatedState(commit)
    val controller = remember(playlist.id) {
        PlaylistUpdateController({ currentProvider() }, { input, progress -> reader(context, input, progress) }, { committer(it) })
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !controller.state.value.saving })
    val closeSheet = rememberSheetDismiss(sheet)
    MeloraBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MeloraAppearance.canvas,
        dragHandle = { PlaylistSheetHandle() }) {
        PlaylistUpdateContent(playlist, controller,
            onDismiss = { closeSheet(onDismiss) },
            onUpdated = { updated -> closeSheet { onUpdated(updated) } })
    }
}

/** 独立更新入口和导入后的更新共用正文，窗体与退场由各自宿主统一管理。 */
@Composable
internal fun PlaylistUpdateContent(
    playlist: UserLibrary.UserPlaylist,
    controller: PlaylistUpdateController,
    onDismiss: () -> Unit,
    onUpdated: (UserLibrary.UserPlaylist) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    var text by rememberSaveable(playlist.id) { mutableStateOf("") }
    var sourceExpanded by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val source = playlist.importSource
    val bound = source != null
    val dismiss = { if (!controller.state.value.saving) { job?.cancel(); onDismiss() } }
    fun read() {
        if (job?.isActive == true) return
        job = scope.launch { controller.read(text) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val actions = playlistUpdateActions(state, canRead = bound || text.isNotBlank())
    val preview = state.preview
    val contentState = rememberLazyListState()
    LaunchedEffect(state.loading, state.saving, preview, state.error) {
        // A retry/save can finish while the user is at the end of a long diff.
        // Bring the new status (especially a failure) back into view.
        contentState.scrollToItem(0)
    }
    fun act(action: PlaylistUpdateAction) {
        keyboard?.hide()
        when (action) {
            PlaylistUpdateAction.DISMISS -> dismiss()
            PlaylistUpdateAction.READ -> read()
            PlaylistUpdateAction.CONFIRM -> if (job?.isActive != true) {
                job = scope.launch { controller.confirm()?.let(onUpdated) }
            }
        }
    }
    // 固定标题与操作区，正文独立滚动；状态卡保留最小高度，避免读取/完成时跳位。
    Column(Modifier.fillMaxWidth().imePadding().heightIn(max = 600.dp)
        .padding(horizontal = 20.dp).testTag("playlist-sync-sheet")) {
        PlaylistSheetHeader(Icons.Outlined.Refresh, if (bound) "从原歌单更新" else "绑定来源并更新",
            "先预览变化，再确认保存")
        Spacer(Modifier.height(20.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("playlist-sync-content"),
            state = contentState,
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
            item("playlist") {
                Row(Modifier.fillMaxWidth().testTag("playlist-sync-summary"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SongArtwork(playlist.songs.firstOrNull()?.img, seed = playlist.name,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(playlist.name, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${source?.platformName ?: "本地歌单"} · ${playlist.songs.size} 首",
                            color = TextSub, fontSize = 12.sp)
                    }
                }
            }
            item("status") {
                Surface(shape = RoundedCornerShape(16.dp),
                    color = if (state.error != null) MeloraAppearance.tintRed else MeloraAppearance.card,
                    modifier = Modifier.fillMaxWidth().then(if (preview != null) Modifier.testTag("playlist-sync-preview") else Modifier)) {
                    Column(Modifier.heightIn(min = 100.dp).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        when {
                            state.busy -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = BrandBlue, strokeWidth = 2.dp)
                                    Text(if (state.saving) "正在保存更新" else "正在对比原歌单", color = TextMain,
                                        fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                }
                                Text(if (state.saving) "请稍候，保存完成后自动关闭。" else state.progress?.let {
                                    "第 ${it.page} 页 · 已读取 ${it.loaded}${it.total?.let { total -> " / $total" }.orEmpty()} 首"
                                } ?: "读取完成后会展示变化，不会直接修改本地歌单。", color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            state.error != null -> {
                                Text("暂未完成更新", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(state.error!!, color = MeloraAppearance.accent, fontSize = 12.sp, lineHeight = 18.sp,
                                    modifier = Modifier.testTag("playlist-sync-error"))
                            }
                            preview == null -> {
                                Text("查看这次有哪些变化", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("读取原歌单，预览新增和移除的歌曲，再决定是否更新。", color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            !preview.hasChanges -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = BrandBlue, modifier = Modifier.size(22.dp))
                                    Text("歌单已是最新", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text("与原歌单一致，无需更新。", color = TextSub, fontSize = 12.sp)
                            }
                            else -> {
                                Text("更新预览 · 更新后 ${preview.updated.songs.size} 首", color = TextMain,
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Text("新增 ${preview.added.size} 首", color = BrandBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("移除 ${preview.removed.size} 首", color = if (preview.removed.isEmpty()) TextSub else MeloraAppearance.accent,
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (preview.added.isEmpty() && preview.removed.isEmpty()) {
                                    Text("歌曲数量未变，将更新顺序、歌曲信息或来源基线。", color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (preview?.firstBinding == true) {
                item("binding-note") {
                    Text("首次绑定保留全部现有歌曲，仅合并远端内容；不会猜测旧歌单中哪些歌曲已被原平台删除。",
                        color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            if (!bound) {
                item("link-input") {
                    Column {
                        if (preview == null) Text("此歌单尚未绑定来源，请粘贴原歌单的公开分享链接。", color = TextSub, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("歌单分享链接", color = TextSub, modifier = Modifier.weight(1f), fontSize = 12.sp)
                            TextButton(enabled = !state.busy, onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (!pasted.isNullOrBlank()) { text = pasted; controller.editLink() }
                            }) { Text("粘贴") }
                        }
                        Surface(shape = RoundedCornerShape(14.dp), color = MeloraAppearance.card, border = MeloraAppearance.cardBorder) {
                            BasicTextField(value = text, onValueChange = { text = it; controller.editLink() }, enabled = !state.busy,
                                textStyle = TextStyle(color = TextMain, fontSize = 14.sp), cursorBrush = SolidColor(BrandBlue),
                                modifier = Modifier.fillMaxWidth().height(76.dp).padding(12.dp).testTag("playlist-sync-link"),
                                decorationBox = { field -> Box { if (text.isBlank()) Text("粘贴链接或分享文字", color = TextSub); field() } })
                        }
                    }
                }
            }
            item("rules") {
                Text(PLAYLIST_UPDATE_RULES, color = TextSub, fontSize = 12.sp, lineHeight = 20.sp,
                    modifier = Modifier.testTag("playlist-sync-rules-detail"))
            }
            if (source != null) {
                item("source") {
                    UpdateDisclosure("来源详情", sourceExpanded, { sourceExpanded = !sourceExpanded }) {
                        SelectionContainer { Text(source.value, color = TextSub, fontSize = 12.sp, lineHeight = 18.sp) }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("歌单来源", source.value))
                        }) { Text("复制链接") }
                    }
                }
            }
            if (preview != null) {
                if (preview.added.isNotEmpty()) {
                    item("added-heading") { UpdateGroupHeading("新增 · ${preview.added.size} 首", BrandBlue) }
                    items(preview.added, key = { "add:${it.uid}" }) { song ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("+ ${song.name}", color = TextMain, fontSize = 13.sp)
                            Text("${song.singer} · ${song.source}", color = TextSub, fontSize = 11.sp)
                        }
                    }
                }
                if (preview.removed.isNotEmpty()) {
                    item("removed-heading") { UpdateGroupHeading("移除 · ${preview.removed.size} 首", MeloraAppearance.accent) }
                    items(preview.removed, key = { "remove:${it.uid}" }) { song ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("− ${song.name}", color = TextMain, fontSize = 13.sp)
                            Text("${song.singer} · ${song.source}", color = TextSub, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        PlaylistSheetActions(
            onSecondary = { act(actions.secondary) }, onConfirm = { act(actions.primary) },
            confirmText = actions.primaryLabel, confirmEnabled = actions.primaryEnabled,
            secondaryEnabled = actions.secondaryEnabled, secondaryText = actions.secondaryLabel,
            submitTag = "playlist-sync-submit",
            modifier = Modifier.padding(top = 16.dp, bottom = 20.dp).testTag("playlist-sync-actions"),
        )
    }
}

@Composable
private fun UpdateDisclosure(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .clickable(role = Role.Button, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextSub, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                tint = TextSub, modifier = Modifier.size(18.dp))
        }
        if (expanded) Surface(color = MeloraAppearance.card, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), content = content)
        }
    }
}

@Composable
private fun UpdateGroupHeading(title: String, color: Color) {
    Surface(color = MeloraAppearance.softFill, shape = RoundedCornerShape(10.dp)) {
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
