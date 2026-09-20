package com.leyu.melora.ui.common

import com.leyu.melora.ui.theme.SystemBarsVisibility
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.FavoriteBorder

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.leyu.melora.playback.Downloader
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.player.ArtworkPlaceholder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import com.leyu.melora.ui.theme.MeloraAppearance

val CanvasBackground: Color get() = MeloraAppearance.canvas
val CardWhite: Color get() = MeloraAppearance.card
val TextMain: Color get() = MeloraAppearance.textMain
val TextSub: Color get() = MeloraAppearance.textSub
val TextMuted: Color get() = MeloraAppearance.textMuted
val BrandBlue: Color get() = MeloraAppearance.brand
val DividerSoft: Color get() = MeloraAppearance.divider
val AccentRed: Color get() = MeloraAppearance.accent

fun List<OnlineSong>.toUiTracks(): List<UiTrack> = map(UiTrack::fromOnline)

/** Coil 可加载的 scheme：http(s)、file、content、android.resource。 */
internal fun isLoadableArtworkUri(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https" ||
        scheme == "file" || scheme == "content" ||
        scheme == "android.resource"
}

/** 封面：有地址用 Coil，无地址回退自绘占位（根据样式自动适配方形/圆形/黑胶）。 */
@Composable
fun SongArtwork(
    url: String?,
    seed: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 10,
    retryOnError: Boolean = false,
    crossfade: Boolean = true,
    style: PlayerCoverStyle = PlayerCoverStyle.Default,
    onResult: ((Painter?) -> Unit)? = null,
) {
    if (url.isNullOrBlank() || !isLoadableArtworkUri(url)) {
        ArtworkPlaceholder(seed = seed, modifier = modifier, cornerRadius = cornerRadius, style = style)
        return
    }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }
    val retryDelay = artworkRetryDelayMs(attempt)
    if (retryOnError) {
        LaunchedEffect(url, seed) {
            // 常驻的全屏播放页重新展开时，仅失败且已用完预算的请求重新开始。
            if (failed && artworkRetryDelayMs(attempt) == null) {
                failed = false
                attempt = 0
            }
        }
    }
    if (retryOnError && failed && retryDelay != null) {
        LaunchedEffect(url, attempt) {
            delay(retryDelay)
            failed = false
            attempt++
        }
    }
    // 全屏封面自管解码后的交接动画，关闭该请求的二次淡入；默认调用完全沿用原模型。
    val context = LocalContext.current
    val model = remember(url, crossfade, context) {
        if (crossfade) url else ImageRequest.Builder(context).data(url).crossfade(false).build()
    }
    // 只重建失败的图片请求，沿用 AsyncImage 的约束采样和 URL 缓存键；封面布局不变。
    key(url, attempt) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = {
                failed = true
                onResult?.invoke(null)
            },
            onSuccess = {
                failed = false
                onResult?.invoke(it.painter)
            },
            modifier = modifier.clip(RoundedCornerShape(cornerRadius.dp)),
        )
    }
}

/** 首次请求失败后仅补两次，避免坏链接无限请求；重新进入播放页可重新尝试。 */
internal fun artworkRetryDelayMs(attempt: Int): Long? = when (attempt) {
    0 -> 600L
    1 -> 1_800L
    else -> null
}

internal fun preferredLocalArtwork(indexedCover: String?, playbackCover: String?): String? =
    indexedCover?.takeIf { it.isNotBlank() } ?: playbackCover?.takeIf { it.isNotBlank() }

/** 仅订阅当前歌曲的封面状态，避免一张图片回填导致整屏歌曲行重组。 */
@Composable
fun rememberOnlineSongCover(song: OnlineSong?, enabled: Boolean): String? {
    if (song == null || !enabled) return null
    val context = LocalContext.current
    val cover by remember(song.uid, song.img) { CoverLoader.observe(song) }
        .collectAsStateWithLifecycle(initialValue = CoverLoader.cachedUrl(song))
    LaunchedEffect(song.uid, song.img) { CoverLoader.request(context, song) }
    return cover
}

/** 跨页面列表只订阅一份公共状态；State 的读取留在消费行，不让播放进度广播重组。 */
internal class SongListState(
    val localSongs: State<List<LocalSong>>,
    val showCovers: State<Boolean>,
    val currentTrack: State<UiTrack?>,
)

internal val LocalSongListState = staticCompositionLocalOf<SongListState> {
    error("SongListStateProvider is required")
}

@Composable
internal fun SongListStateProvider(content: @Composable () -> Unit) {
    val songs = LocalMediaStore.songs.collectAsStateWithLifecycle()
    val covers = MeloraSettings.showSongCovers.collectAsStateWithLifecycle()
    val initialTrack = remember { PlaybackController.state.value.current }
    val currentTrack = remember {
        PlaybackController.state.map { it.current }.distinctUntilChanged { old, new ->
            old?.uid == new?.uid && old?.artwork == new?.artwork
        }
    }.collectAsStateWithLifecycle(initialValue = initialTrack)
    val state = remember(songs, covers, currentTrack) { SongListState(songs, covers, currentTrack) }
    CompositionLocalProvider(LocalSongListState provides state, content = content)
}

/** 通用在线歌曲行。 */
@Composable
fun OnlineSongRow(
    song: OnlineSong,
    showAlbum: Boolean = false,
    subtitlePrefix: String? = null,
    isFavorite: Boolean = false,
    // 传 null（默认）时自动读取当前播放曲目，所有列表统一获得正在播放强调色
    isCurrent: Boolean? = null,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
    // 批量选择模式：右侧 ⋯ 平滑切换为圆形勾选框（行点击由调用方改为切换选中）
    selectionMode: Boolean = false,
    selected: Boolean = false,
    localBadge: Boolean = false,
    platformDotColor: Color? = null,
) {
    val shared = LocalSongListState.current
    val showCovers by shared.showCovers
    val localSongs by shared.localSongs
    val matchedLocal = remember(song.uid, song.name, song.singer, song.intervalSeconds, localSongs) {
        LocalMediaStore.matchSong(song)
    }
    val showLocalBadge = localBadge || matchedLocal != null
    // 本地命中后只显示本地实测徽标；位深未知时保持空白，不能拿网络目录能力冒充实测。
    val qualityBadge = if (matchedLocal != null) matchedLocal.audioSpecification.qualityBadge else song.bestQualityBadge
    val artwork = rememberOnlineSongCover(song, enabled = showCovers)
    val highlighted by remember(isCurrent, song.uid, shared.currentTrack) {
        derivedStateOf { isCurrent ?: (shared.currentTrack.value?.uid == song.uid) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.5.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 关闭单曲封面时整个封面位移除，文字左移，左边缘与表头严格对齐
        if (showCovers) {
            SongArtwork(artwork, song.uid, Modifier.size(42.dp), cornerRadius = 10)
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (highlighted) BrandBlue.copy(alpha = 0.75f) else TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                platformDotColor?.let { color ->
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.72f)),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (showLocalBadge) {
                    LocalBadgePill()
                    Spacer(Modifier.width(6.dp))
                }
                qualityBadge?.let { quality ->
                    BadgePill(quality)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = buildString {
                        subtitlePrefix?.let { append(it).append(" · ") }
                        append(song.singer)
                        if (showAlbum && song.albumName.isNotBlank()) append(" · ").append(song.albumName)
                    },
                    fontSize = 12.sp,
                    color = if (highlighted) BrandBlue.copy(alpha = 0.75f) else TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isFavorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(16.dp),
            )
        }
        if (onMore != null || selectionMode) {
            val moreAlpha by animateFloatAsState(
                targetValue = if (selectionMode) 0f else 1f,
                animationSpec = tween(durationMillis = 180),
                label = "rowMoreAlpha",
            )
            val checkAlpha by animateFloatAsState(
                targetValue = if (selectionMode) 1f else 0f,
                animationSpec = tween(durationMillis = 180),
                label = "rowCheckAlpha",
            )
            // 同一 36dp 触控位：⋯ 淡出、勾选框淡入，布局零跳动
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (onMore != null) {
                    // 批量模式下禁用点击：透明但仍可点和透传，点勾选框不能弹出单曲更多操作
                    IconButton(
                        onClick = onMore,
                        enabled = !selectionMode,
                        modifier = Modifier.size(36.dp).alpha(moreAlpha),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
                if (checkAlpha > 0.01f) {
                    Box(
                        modifier = Modifier
                            .alpha(checkAlpha)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (selected) BrandBlue else Color.Transparent)
                            .border(
                                width = 1.5.dp,
                                color = if (selected) BrandBlue else TextMuted.copy(alpha = 0.55f),
                                shape = CircleShape,
                            )
                            // 勾选圈自身可点：彻底解决“点圈穿透到 ⋯ 弹出更多菜单”
                            .clickable(
                                enabled = selectionMode,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onClick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 多选歌曲加入歌单（单曲调用方保持原签名）。 */
@Composable
fun AddToPlaylistSheet(song: OnlineSong, onDismiss: () -> Unit) =
    AddToPlaylistSheet(listOf(song), onDismiss)

@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String = "加载中…") {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = BrandBlue, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        Text(text, fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = TextMuted)
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, fontSize = 13.sp, color = TextSub)
        if (onRetry != null) {
            Text(
                "点击重试",
                fontSize = 13.sp,
                color = BrandBlue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onRetry,
                    ),
            )
        }
    }
}

/** 歌曲更多操作面板：收藏 / 下一首播放 / 添加到歌单 / 下载；歌单内额外提供"从歌单移除"。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMoreSheet(
    song: OnlineSong,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    // 本地歌曲复用同一抽屉：可下载所选音质，并追加「永久删除」
    allowDownload: Boolean = true,
    onDeleteLocal: (() -> Unit)? = null,
    localCoverSong: com.leyu.melora.playback.local.LocalSong? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
    val isFavorite = song.uid in favoriteUids
    // 本地索引、播放器与抽屉只使用一条实时链路：当前歌曲先复用播放器已解析封面，
    // 文件内嵌封面/本地缓存就绪后再无闪烁地接管，避免抽屉保留点击瞬间的陈旧 LocalSong 快照。
    val shared = LocalSongListState.current
    val localSongs by shared.localSongs
    val currentTrack by shared.currentTrack
    val explicitLocalId = localCoverSong?.id
    val matchedLocal = remember(song.uid, explicitLocalId, localSongs) {
        explicitLocalId?.let { id -> localSongs.firstOrNull { it.id == id } }
            ?: LocalMediaStore.matchSong(song)
    }
    val currentLocalId = remember(currentTrack?.uid, currentTrack?.artwork, localSongs) {
        currentTrack?.let(LocalMediaStore::matchTrack)?.id
    }
    val playbackCover = currentTrack?.artwork?.takeIf {
        currentTrack?.uid == song.uid || (matchedLocal != null && currentLocalId == matchedLocal.id)
    }
    var localCover by remember(matchedLocal?.id, matchedLocal?.coverUri, playbackCover) {
        mutableStateOf(preferredLocalArtwork(matchedLocal?.coverUri, playbackCover))
    }
    LaunchedEffect(matchedLocal?.id, matchedLocal?.coverUri, playbackCover) {
        val target = matchedLocal ?: return@LaunchedEffect
        localCover = withContext(Dispatchers.IO) {
            com.leyu.melora.playback.local.LocalTagReader.bestCoverUri(context, target)
        } ?: playbackCover
    }
    val onlineCover = rememberOnlineSongCover(song, enabled = matchedLocal == null)
    val cover = localCover ?: playbackCover ?: onlineCover
    // 已有低音质本地文件不能挡住升级入口，是否重复由下载器依据真实规格判断。
    val downloadAllowed = allowDownload
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val startDownload = {
        PlaybackController.postMessage(context, "开始下载：${song.name}")
        scope.launch {
            val result = Downloader.download(context, song)
            result.onSuccess { PlaybackController.postMessage(context, it) }
                .onFailure { PlaybackController.postMessage(context, it.message ?: "下载失败") }
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startDownload() else PlaybackController.postMessage(context, "未获得存储权限，无法下载")
        onDismiss()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 无论是否授予通知权限都继续下载（未授予时只是不显示通知栏进度）
        startDownload()
        onDismiss()
    }

    if (showPlaylistPicker) {
        AddToPlaylistSheet(song) { showPlaylistPicker = false; onDismiss() }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MeloraAppearance.canvas,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MeloraAppearance.divider),
                )
            }
        },
    ) {
        SystemBarsVisibility()
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            // 头部：48dp 封面缩略图 + 歌名/歌手与专辑
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(
                    cover ?: song.img,
                    "more_song_${song.uid}",
                    Modifier.size(48.dp),
                    cornerRadius = 12,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = listOfNotNull(
                            song.singer.takeIf { it.isNotBlank() },
                            song.albumName.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { "未知歌手" },
                        fontSize = 12.sp,
                        color = TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MeloraAppearance.divider, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SheetAction(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    tint = if (isFavorite) AccentRed else BrandBlue,
                    label = if (isFavorite) "取消收藏" else "收藏到我的列表",
                    subtitle = if (isFavorite) "从「我的列表」收藏夹中移除" else "保存到「我的列表」收藏夹",
                ) {
                    UserLibrary.toggleFavorite(song)
                    onDismiss()
                }
                SheetAction(
                    icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    tint = Color(0xFF0284C7),
                    label = "下一首播放",
                    subtitle = "加入当前播放队列的下一顺位",
                ) {
                    PlaybackController.addToQueueNext(context, UiTrack.fromOnline(song))
                    onDismiss()
                }
                SheetAction(
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    tint = Color(0xFF7C3AED),
                    label = "添加到歌单",
                    subtitle = "收录到自建歌单中分类管理",
                ) {
                    showPlaylistPicker = true
                }
                if (onRemoveFromPlaylist != null) {
                    SheetAction(
                        icon = Icons.Rounded.RemoveCircleOutline,
                        tint = AccentRed,
                        label = "从歌单移除",
                        subtitle = "仅从此歌单列表中移除该歌曲",
                    ) {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                }
                if (downloadAllowed) {
                SheetAction(
                    icon = Icons.Outlined.Download,
                    tint = Color(0xFF1E88E5),
                    label = if (matchedLocal != null) "下载所选音质" else "下载音频",
                    subtitle = "按下载设置保存，可另存更高音质",
                ) {
                    val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED
                    val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    when {
                        needsStorage -> storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        needsNotification -> {
                            // 下载前申请通知权限，用于通知栏展示进度；拒绝也不阻断下载
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        else -> {
                            startDownload()
                            onDismiss()
                        }
                    }
                }
                }
                if (onDeleteLocal != null) {
                    SheetAction(
                        icon = Icons.Outlined.DeleteOutline,
                        tint = AccentRed,
                        label = "永久删除",
                        subtitle = "从设备中删除该音频文件，无法恢复",
                    ) {
                        onDeleteLocal()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
internal fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    subtitle: String? = null,
    iconBg: Color? = null,
    onClick: () -> Unit,
) {
    val effectiveBg = iconBg ?: if (tint == AccentRed) Color(0xFFFFECEF) else tint.copy(alpha = 0.10f)
    val effectiveTint = if (tint == AccentRed) AccentRed else tint
    val labelColor = if (tint == AccentRed) AccentRed else TextMain

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(effectiveBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = effectiveTint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = if (tint == AccentRed) AccentRed.copy(alpha = 0.75f) else TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSub.copy(alpha = 0.40f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 选择/新建歌单（支持多选批量加入）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(songs: List<OnlineSong>, onDismiss: () -> Unit) {
    if (songs.isEmpty()) return
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var newName by remember { mutableStateOf("") }
    val playlists by UserLibrary.playlists.collectAsStateWithLifecycle()
    val createAndAdd = {
        if (newName.isNotBlank()) {
            val name = newName.trim()
            val playlist = UserLibrary.createPlaylist(name)
            songs.forEach { UserLibrary.addToPlaylist(playlist.id, it) }
            PlaybackController.postMessage(
                context,
                if (songs.size == 1) "已创建歌单「$name」并添加" else "已创建歌单「$name」并添加 ${songs.size} 首",
            )
            newName = ""
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MeloraAppearance.canvas,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MeloraAppearance.divider),
                )
            }
        },
    ) {
        SystemBarsVisibility()
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            Text(
                "添加到歌单",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TextMain,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            // 精致新建栏：浅色圆角胶囊底 + 嵌入式 BasicTextField + 创建按钮
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MeloraAppearance.softFill,
                border = MeloraAppearance.chipBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(44.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.AddCircleOutline,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = TextMain,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp,
                        ),
                        cursorBrush = SolidColor(BrandBlue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { createAndAdd() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (newName.isEmpty()) {
                                    Text(
                                        "新建歌单名称…",
                                        fontSize = 13.sp,
                                        color = TextMuted,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (newName.isNotBlank()) {
                        Surface(
                            onClick = { createAndAdd() },
                            shape = RoundedCornerShape(14.dp),
                            color = BrandBlue,
                        ) {
                            Text(
                                "创建并添加",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                Text(
                    text = "我的歌单 (${playlists.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSub,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newCount = songs.count { s -> playlist.songs.none { it.uid == s.uid } }
                                    songs.forEach { UserLibrary.addToPlaylist(playlist.id, it) }
                                    PlaybackController.postMessage(
                                        context,
                                        when {
                                            newCount == 0 -> "已在歌单「${playlist.name}」中"
                                            songs.size == 1 -> "已添加到歌单「${playlist.name}」"
                                            else -> "已添加 $newCount 首到歌单「${playlist.name}」"
                                        },
                                    )
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val cover = playlist.songs.firstOrNull()?.img
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MeloraAppearance.tintBlue),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!cover.isNullOrBlank()) {
                                    SongArtwork(
                                        cover,
                                        "pl_cover_${playlist.id}",
                                        Modifier.fillMaxSize(),
                                        cornerRadius = 10,
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = null,
                                        tint = BrandBlue,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${playlist.songs.size} 首歌曲",
                                    fontSize = 11.sp,
                                    color = TextSub,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "添加到歌单",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
                EmptyState("还没有歌单，在上方输入名称创建吧")
            }
        }
    }
}
