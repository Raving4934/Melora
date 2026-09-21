package com.leyu.melora.ui.local

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.local.LocalMediaScanner
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.playback.local.localSongSectionStarts
import com.leyu.melora.playback.local.sortLocalSongs
import com.leyu.melora.playback.local.LocalTagReader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.AddToPlaylistSheet
import com.leyu.melora.ui.common.BadgePill
import com.leyu.melora.ui.common.LocalBadgePill
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.rememberFastScrollToTop
import com.leyu.melora.ui.common.titleScrollToTop
import com.leyu.melora.ui.common.DividerSoft
import com.leyu.melora.ui.common.MusicShuffleIcon
import com.leyu.melora.ui.common.PullRefreshContainer
import com.leyu.melora.ui.common.LocalSongListState
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.common.SongSelectionButton
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** 每次打开搜索都创建新实例；退出中的实例继续持有 query 直到退场完成。 */
internal class LocalSearchPageTarget {
    var query by mutableStateOf("")
}

/**
 * 本地歌曲页：列表 + 批量管理 + 搜索页（连续平移）+ 排序抽屉。
 * 取消搜索时保留离场内容，列表页从左侧平移恢复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalSongsPage(
    onOpenDrawer: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by LocalMediaStore.songs.collectAsStateWithLifecycle()

    val sortField by MeloraSettings.localSortField.collectAsStateWithLifecycle()
    val ascending by MeloraSettings.localSortAscending.collectAsStateWithLifecycle()
    var searchPage by remember { mutableStateOf<LocalSearchPageTarget?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var moreSong by remember { mutableStateOf<LocalSong?>(null) }
    var pendingDelete by remember { mutableStateOf<List<LocalDeletionTarget>?>(null) }
    var pendingDeleteRequest by remember { mutableStateOf<IntentSenderRequest?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf<List<OnlineSong>?>(null) }
    val selection = remember { SongSelectionState() }
    val listState = rememberLazyListState()
    val pullEnabled by MeloraSettings.pullToRefresh.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    val deletion = remember(context) { LocalSongDeletion(AndroidLocalDeletionOperations(context)) }

    fun finishDeletion(result: LocalDeletionResult) {
        if (result.deletedIds.isNotEmpty()) {
            val deletedUris = result.deleted.mapTo(hashSetOf()) { it.uri }
            val deletedIds = LocalMediaStore.songs.value.filter { it.uri in deletedUris }
                .mapTo(result.deletedIds.toMutableSet()) { it.id }
            LocalMediaStore.removeIds(deletedIds)
        }
        deleting = false
        if (!result.cancelled) selection.finish()
        val message = when {
            result.cancelled && result.deleted.isEmpty() -> "已取消删除"
            result.cancelled -> "已删除 ${result.deleted.size} 个，其余已取消"
            result.failed.isEmpty() -> "已删除本地文件 ${result.deleted.size} 个"
            result.deleted.isEmpty() -> "删除失败，请检查文件权限"
            else -> "已删除 ${result.deleted.size} 个，${result.failed.size} 个失败"
        }
        PlaybackController.postMessage(context, message)
    }

    fun handleDeletionStep(step: LocalDeletionStep<IntentSenderRequest>) {
        when (step) {
            is LocalDeletionStep.Awaiting -> pendingDeleteRequest = step.request.request
            is LocalDeletionStep.Finished -> finishDeletion(step.result)
            LocalDeletionStep.Busy -> Unit
            LocalDeletionStep.Ignored -> Unit
        }
    }

    val deleteConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            val step = withContext(Dispatchers.IO) {
                deletion.onAuthorizationResult(result.resultCode == Activity.RESULT_OK)
            }
            handleDeletionStep(step)
        }
    }
    LaunchedEffect(pendingDeleteRequest) {
        val request = pendingDeleteRequest ?: return@LaunchedEffect
        pendingDeleteRequest = null
        deleteConsentLauncher.launch(request)
    }

    fun requestDeleteConfirmation(songsToDelete: List<LocalSong>) {
        if (deleting || pendingDelete != null) return
        val uniqueTargets = distinctLocalDeletionTargets(songsToDelete.map(LocalSong::toLocalDeletionTarget))
        if (uniqueTargets.isNotEmpty()) pendingDelete = uniqueTargets
    }

    val launchScan: () -> Unit = {
        scope.launch {
            try {
                val result = LocalMediaScanner.scan(context)
                if (result.failedSources > 0) PlaybackController.postMessage(context, result.message)
                try {
                    LocalMediaScanner.enrich(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // 扫描成功后补全失败不影响已提交的本地索引。
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                PlaybackController.postMessage(context, "扫描失败：${error.message ?: "未知错误"}")
            } finally {
                refreshing = false
            }
        }
    }

    // 下拉刷新 = 重新扫描本地媒体（含权限申请与元数据补全），免去进设置手动扫描
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchScan()
        } else {
            PlaybackController.postMessage(context, "未获得音频读取权限，无法扫描本地歌曲")
            refreshing = false
        }
    }
    fun triggerScan() {
        if (!LocalMediaScanner.requiresAudioPermission()) {
            launchScan()
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) launchScan() else permissionLauncher.launch(permission)
    }

    val ordered = remember(songs, sortField, ascending) { sortLocalSongs(songs, sortField, ascending) }

    BackHandler(enabled = searchPage != null) { searchPage = null }
    // 非搜索态：返回先退出批量管理，有父级回调则回退
    BackHandler(enabled = searchPage == null && (selection.active || onBack != null)) {
        if (selection.active) selection.finish() else onBack?.invoke()
    }

    DetailPageHost(
        target = searchPage,
        modifier = Modifier.fillMaxSize(),
        detail = { page ->
            LocalSearchPage(
                songs = ordered,
                query = page.query,
                onQueryChange = { page.query = it },
                onCancel = { searchPage = null },
                selection = selection,
                onOpenSortSheet = { showSortSheet = true },
                onMore = { moreSong = it },
                onDeleteSelection = { requestDeleteConfirmation(it) },
                onAddToPlaylist = { addToPlaylist = it },
            )
        },
        content = {
            LocalSongsListContent(
                songs = ordered,
                sortField = sortField,
                ascending = ascending,
                selection = selection,
                listState = listState,
                onOpenDrawer = onOpenDrawer,
                onBack = onBack,
                onOpenSearch = { searchPage = LocalSearchPageTarget() },
                onOpenSortSheet = { showSortSheet = true },
                onMore = { moreSong = it },
                onDeleteSelection = { requestDeleteConfirmation(it) },
                onAddToPlaylist = { addToPlaylist = it },
                pullEnabled = pullEnabled,
                refreshing = refreshing,
                onRefresh = {
                    if (!refreshing) {
                        refreshing = true
                        triggerScan()
                    }
                },
            )
        },
    )

    if (showSortSheet) {
        LocalSortSheet(
            field = sortField,
            ascending = ascending,
            onFieldChange = MeloraSettings::updateLocalSortField,
            onDirectionChange = MeloraSettings::updateLocalSortAscending,
            onDismiss = { showSortSheet = false },
        )
    }

    moreSong?.let { song ->
        SongMoreSheet(
            song = song.toOnlineSong(),
            localCoverSong = song,
            onDeleteLocal = {
                requestDeleteConfirmation(listOf(song))
                moreSong = null
            },
            onDismiss = { moreSong = null },
        )
    }

    addToPlaylist?.let { selected ->
        AddToPlaylistSheet(selected) {
            addToPlaylist = null
            if (selection.active) selection.finish()
        }
    }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = { Text("永久删除", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextMain) },
            text = { Text("将从设备中删除 ${targets.size} 个音频文件，且无法恢复。", fontSize = 13.sp, color = TextSub) },
            confirmButton = {
                TextButton(onClick = {
                    if (!deleting) {
                        deleting = true
                        pendingDelete = null
                        scope.launch {
                            val step = withContext(Dispatchers.IO) { deletion.start(targets) }
                            handleDeletionStep(step)
                        }
                    }
                }) {
                    Text("删除", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!deleting) pendingDelete = null }) { Text("取消", color = TextSub) }
            },
            containerColor = MeloraAppearance.card,
        )
    }
}
@Composable
internal fun LocalSongsListContent(
    songs: List<LocalSong>,
    sortField: LocalSortField,
    ascending: Boolean,
    selection: SongSelectionState,
    listState: LazyListState,
    onOpenDrawer: () -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenSearch: () -> Unit,
    onOpenSortSheet: () -> Unit,
    onMore: (LocalSong) -> Unit,
    onDeleteSelection: (List<LocalSong>) -> Unit,
    onAddToPlaylist: (List<OnlineSong>) -> Unit,
    pullEnabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scrollToTop = rememberFastScrollToTop(listState)
    val playingLocalId = rememberPlayingLocalId()
    val sections = remember(songs, sortField) { localSongSectionStarts(songs, sortField) }
    val currentSection by remember(sections, listState) {
        derivedStateOf {
            sections.entries.lastOrNull { it.value <= listState.firstVisibleItemIndex }?.key
        }
    }

    ChromeScaffold(
        expectedTopBarHeight = 110.dp,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(chromeHeaderColor()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(start = 0.5.dp, end = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = TextMain)
                        }
                    } else {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Rounded.Menu, contentDescription = "打开侧栏", tint = TextMain)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .titleScrollToTop(scrollToTop),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "本地歌曲",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMain,
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索本地歌曲", tint = TextMain)
                    }
                }
                Box(Modifier.fillMaxWidth().height(46.dp)) {
                    if (songs.isNotEmpty()) {
                        LocalListHeader(
                            count = songs.size,
                            songs = songs,
                            selection = selection,
                            onPlayShuffle = {
                                PlaybackController.playQueue(
                                    context,
                                    songs.shuffled().map { it.toOnlineSong() }.toUiTracks(),
                                    0,
                                    "local.songs",
                                )
                            },
                            onOpenSortSheet = onOpenSortSheet,
                            onStartSelection = { selection.start() },
                        )
                    }
                }
            }
        },
        bottomBar = {
            LocalBatchActionsBar(
                selection = selection,
                songs = songs,
                onDelete = onDeleteSelection,
                onAddToPlaylist = onAddToPlaylist,
            )
        },
    ) {
        if (songs.isEmpty()) {
            // 空态也放进 LazyColumn：保证空列表同样可以下拉刷新
            PullRefreshContainer(
                enabled = pullEnabled,
                refreshing = refreshing,
                onRefresh = onRefresh,
                canPull = true,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        EmptyLocalState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 140.dp, bottom = 60.dp),
                        )
                    }
                }
            }
            return@ChromeScaffold
        }

        PullRefreshContainer(
            enabled = pullEnabled,
            refreshing = refreshing,
            onRefresh = onRefresh,
            canPull = !listState.canScrollBackward,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
            ) {
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    LocalSongRow(
                        song = song,
                        selectionMode = selection.active,
                        selected = song.localUid() in selection.selectedUids,
                        isCurrent = song.id == playingLocalId,
                        onClick = {
                            if (selection.active) {
                                selection.toggle(song.localUid())
                            } else {
                                PlaybackController.playTrack(context, UiTrack.fromOnline(song.toOnlineSong()))
                            }
                        },
                        onMore = { onMore(song) },
                    )
                }
            }
            if (sections.isNotEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(
                        top = chromeContentPadding().calculateTopPadding(),
                        bottom = 76.dp,
                    ),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    LocalSongIndex(
                        sections = sections,
                        currentSection = currentSection,
                        // 直接定位而非排队播放滚动动画；快速滑过多个字母只采用最新目标。
                        onSelect = { listState.requestScrollToItem(it) },
                    )
                }
            }
            LocatePlayingButton(
                songs = songs,
                listState = listState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.5.dp, bottom = 26.dp),
            )
        }
    }
}

@Composable
internal fun EmptyLocalState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(10.dp))
        Text("还没有本地歌曲", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
        Text(
            "在 设置 → 本地歌曲 中开始扫描设备音频",
            fontSize = 12.sp,
            color = TextSub,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun LocalListHeader(
    count: Int,
    songs: List<LocalSong>,
    selection: SongSelectionState,
    onPlayShuffle: () -> Unit,
    onOpenSortSheet: () -> Unit,
    onStartSelection: () -> Unit,
) {
    // 批量态与普通态在同一条表头内切换：全选 / 已选中 N 项 / 取消
    if (selection.active) {
        val online = remember(songs) { songs.map { it.toOnlineSong() } }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(start = 16.dp, end = 11.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selection.allSelected(online)) "取消全选" else "全选",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandBlue,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { selection.toggleAll(online) }
                    .padding(vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "已选中 ${selection.selectedUids.size} 项",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "取消",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandBlue,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { selection.finish() }
                    .padding(vertical = 6.dp),
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(start = 9.dp, end = 11.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onPlayShuffle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MusicShuffleIcon,
                contentDescription = "随机播放全部",
                tint = TextSub,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextSub,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onOpenSortSheet,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                Icons.Rounded.SortByAlpha,
                contentDescription = "排序方式",
                tint = TextSub,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        SongSelectionButton(onClick = onStartSelection)
    }
}

@Composable
internal fun LocalSongRow(
    song: LocalSong,
    selectionMode: Boolean,
    selected: Boolean,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val shared = LocalSongListState.current
    val showCovers by shared.showCovers
    val playbackCover by remember(isCurrent, shared.currentTrack) {
        derivedStateOf { shared.currentTrack.value?.artwork?.takeIf { isCurrent } }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.5.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 与在线列表一致：关闭「显示单曲封面」时整块封面移除，文字左移，左边缘与表头严格对齐
        if (showCovers) {
            LocalSongArtwork(song, Modifier.size(42.dp), playbackCover)
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrent) BrandBlue.copy(alpha = 0.75f) else TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                song.qualityBadge?.let {
                    BadgePill(it)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = buildString {
                        if (song.artist.isNotBlank()) append(song.artist)
                        if (song.album.isNotBlank()) {
                            if (isNotEmpty()) append(" - ")
                            append(song.album)
                        }
                        if (isEmpty()) append(LocalSong.formatDuration(song.durationMs / 1000))
                    },
                    fontSize = 12.sp,
                    color = if (isCurrent) BrandBlue.copy(alpha = 0.75f) else TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val actionAlpha by animateFloatAsState(if (selectionMode) 0f else 1f, tween(180), label = "localRowAction")
        val checkAlpha by animateFloatAsState(if (selectionMode) 1f else 0f, tween(180), label = "localRowCheck")
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMore != null) {
                    IconButton(onClick = onMore, enabled = !selectionMode, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "更多",
                            tint = TextMuted,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { alpha = actionAlpha },
                        )
                    }
                }
            }
            if (checkAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        // 与 ⋮ 图标同一中心：选中后圆圈原地出现，不右移
                        .align(Alignment.Center)
                        .graphicsLayer { alpha = checkAlpha }
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable(
                            enabled = selectionMode,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onClick() }
                        .background(if (selected) BrandBlue else Color.Transparent)
                        .border(
                            width = 1.5.dp,
                            color = if (selected) BrandBlue else TextMuted.copy(alpha = 0.55f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalSongArtwork(
    song: LocalSong,
    modifier: Modifier,
    playbackCover: String? = null,
) {
    val context = LocalContext.current
    var cover by remember(song.id, song.coverUri, song.modifiedAt, playbackCover) {
        mutableStateOf(com.leyu.melora.ui.common.preferredLocalArtwork(song.coverUri, playbackCover))
    }
    // 当前曲目先与迷你播放器共用已解析封面；文件封面/本地缓存就绪后再接管。
    LaunchedEffect(song.id, song.coverUri, song.modifiedAt, playbackCover) {
        cover = withContext(Dispatchers.IO) { LocalTagReader.bestCoverUri(context, song) } ?: playbackCover
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MeloraAppearance.softFill),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LocatePlayingButton(
    songs: List<LocalSong>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState by PlaybackController.state.collectAsStateWithLifecycle()
    Surface(
        shape = CircleShape,
        color = MeloraAppearance.card.copy(alpha = 0.82f),
        border = MeloraAppearance.cardBorder,
        shadowElevation = 0.dp,
        modifier = modifier.size(44.dp),
        onClick = {
            // 快速定位当前播放的本地歌曲：本地曲目直接按 id，网络曲目按标题/歌手匹配本地文件
            val target = playerState.current?.let { LocalMediaStore.matchTrack(it) }
            val index = target?.let { song -> songs.indexOfFirst { it.id == song.id } } ?: -1
            if (index >= 0) {
                scope.launch { listState.animateScrollToItem(index) }
            } else {
                PlaybackController.postMessage(context, "当前没有正在播放的本地歌曲")
            }
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MyLocation, contentDescription = "定位当前播放", tint = TextSub, modifier = Modifier.size(19.dp))
        }
    }
}

/** 批量操作条：收藏 / 永久删除 / 添加到歌单 / 播放选中队列。列表页与搜索页共用。 */
@Composable
internal fun LocalBatchActionsBar(
    selection: SongSelectionState,
    songs: List<LocalSong>,
    onDelete: (List<LocalSong>) -> Unit,
    onAddToPlaylist: (List<OnlineSong>) -> Unit,
) {
    if (!selection.active) return
    val context = LocalContext.current
    val selected = remember(selection.selectedUids, songs) {
        songs.filter { it.localUid() in selection.selectedUids }
    }
    val enabled = selected.isNotEmpty()
    Column(Modifier.fillMaxWidth().background(chromeHeaderColor())) {
        HorizontalDivider(color = DividerSoft, thickness = 0.6.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            LocalBatchAction(Icons.Rounded.FavoriteBorder, "收藏", enabled) {
                val changed = UserLibrary.setFavorites(selected.map { it.toOnlineSong() }, favorite = true)
                PlaybackController.postMessage(context, if (changed == 0) "所选歌曲已在收藏中" else "已收藏 $changed 首")
                selection.finish()
            }
            LocalBatchAction(Icons.Outlined.DeleteOutline, "永久删除", enabled) { onDelete(selected) }
            LocalBatchAction(Icons.AutoMirrored.Outlined.PlaylistAdd, "添加到歌单", enabled) {
                onAddToPlaylist(selected.map { it.toOnlineSong() })
            }
            LocalBatchAction(Icons.AutoMirrored.Rounded.QueueMusic, "播放选中队列", enabled) {
                PlaybackController.playQueue(context, selected.map { it.toOnlineSong() }.toUiTracks(), 0, "local.songs")
                selection.finish()
            }
        }
    }
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.LocalBatchAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) BrandBlue else TextMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) TextMain else TextMuted.copy(alpha = 0.55f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 当前播放曲目对应的本地歌曲 id（网络曲目命中本地文件时同样高亮）。 */
@Composable
internal fun rememberPlayingLocalId(): String? {
    val track by remember {
        PlaybackController.state.map { it.current }.distinctUntilChanged { a, b -> a?.uid == b?.uid }
    }.collectAsStateWithLifecycle(initialValue = null)
    return remember(track?.uid) { track?.let { LocalMediaStore.matchTrack(it)?.id } }
}

internal fun LocalSong.localUid(): String = "${LocalSong.SOURCE}_$id"
