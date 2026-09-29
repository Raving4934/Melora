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
    val scope = rememberCoroutineScope()
    val currentProvider by rememberUpdatedState(currentPlaylist)
    val reader by rememberUpdatedState(readPlaylist)
    val committer by rememberUpdatedState(commit)
    val controller = remember(playlist.id) {
        PlaylistUpdateController({ currentProvider() }, { input, progress -> reader(context, input, progress) }, { committer(it) })
    }
    val state by controller.state.collectAsStateWithLifecycle()
    var text by rememberSaveable(playlist.id) { mutableStateOf("") }
    var sourceExpanded by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val source = playlist.importSource
    val bound = source != null
    val dismiss = { if (!controller.state.value.saving) { job?.cancel(); onDismiss() } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !controller.state.value.saving })
    fun read() {
        if (job?.isActive == true) return
        job = scope.launch { controller.read(text) }
    }
    MeloraBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = MeloraAppearance.canvas,
        dragHandle = { PlaylistSheetHandle() }) {
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
        // Only the body scrolls. IME insets reduce its available height, not the action bar.
        // fill=false lets short/no-change content wrap instead of reserving a tall empty panel.
        Column(Modifier.fillMaxWidth().imePadding().heightIn(max = 640.dp)
            .padding(horizontal = 20.dp).testTag("playlist-sync-sheet")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Refresh, null, tint = BrandBlue, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (bound) "从原歌单更新" else "绑定来源并更新", color = TextMain,
                        fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(playlist.name, color = TextSub, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        source?.let { Text(it.platformName, color = BrandBlue, fontSize = 12.sp, maxLines = 1) }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("playlist-sync-content"),
                state = contentState,
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                state.error?.let { error ->
                    item("error") {
                        Surface(color = MeloraAppearance.tintRed, shape = RoundedCornerShape(12.dp)) {
                            Text(error, color = MeloraAppearance.accent, fontSize = 13.sp, lineHeight = 19.sp,
                                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("playlist-sync-error"))
                        }
                    }
                }
                if (state.busy) {
                    item("progress") {
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = BrandBlue, strokeWidth = 2.dp)
                            Text(if (state.saving) "正在保存更新，请稍候…" else state.progress?.let {
                                "第 ${it.page} 页 · 已读取 ${it.loaded}${it.total?.let { total -> " / $total" }.orEmpty()} 首"
                            } ?: "正在读取完整歌单…", color = TextSub, fontSize = 13.sp)
                        }
                    }
                }
                if (preview != null) {
                    item("preview") {
                        Surface(shape = RoundedCornerShape(14.dp), color = MeloraAppearance.card,
                            border = MeloraAppearance.cardBorder, modifier = Modifier.testTag("playlist-sync-preview")) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!preview.hasChanges) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Outlined.CheckCircle, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                                        Text("歌单已是最新", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Text("无需更新，可直接完成。", color = TextSub, fontSize = 12.sp)
                                } else {
                                    Text("更新预览", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("新增 ${preview.added.size} 首", color = BrandBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text("移除 ${preview.removed.size} 首", color = MeloraAppearance.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Text("更新后 ${preview.updated.songs.size} 首", color = TextSub, fontSize = 12.sp)
                                    if (preview.added.isEmpty() && preview.removed.isEmpty()) {
                                        Text("歌曲数量未变，将更新顺序、歌曲信息或来源基线。", color = TextSub, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (preview.firstBinding) {
                        item("binding-note") {
                            Text("首次绑定保留全部现有歌曲，仅合并远端内容；不会猜测旧歌单中哪些歌曲已被原平台删除。",
                                color = TextSub, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                } else if (!state.busy && state.error == null && bound) {
                    item("ready") { Text("读取原歌单，对比后再确认更新。", color = TextSub, fontSize = 13.sp) }
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
                    Text(PLAYLIST_UPDATE_RULES, color = TextSub, fontSize = 12.sp, lineHeight = 18.sp,
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
            // Local actions deliberately leave the import sheet's shared actions unchanged.
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 20.dp).height(48.dp)
                .testTag("playlist-sync-actions"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = { act(actions.secondary) }, enabled = actions.secondaryEnabled,
                    shape = RoundedCornerShape(24.dp), color = MeloraAppearance.softFill,
                    border = MeloraAppearance.chipBorder, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(actions.secondaryLabel, color = if (actions.secondaryEnabled) TextSub else MeloraAppearance.textMuted, fontSize = 14.sp)
                    }
                }
                Surface(onClick = { act(actions.primary) }, enabled = actions.primaryEnabled,
                    shape = RoundedCornerShape(24.dp), color = BrandBlue.copy(alpha = if (actions.primaryEnabled) 1f else 0.35f),
                    modifier = Modifier.weight(1.6f).fillMaxHeight().testTag("playlist-sync-submit")) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(actions.primaryLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
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
        if (expanded) Column(Modifier.padding(bottom = 8.dp), content = content)
    }
}

@Composable
private fun UpdateGroupHeading(title: String, color: Color) {
    Surface(color = MeloraAppearance.softFill, shape = RoundedCornerShape(10.dp)) {
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
