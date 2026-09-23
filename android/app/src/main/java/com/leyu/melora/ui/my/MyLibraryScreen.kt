package com.leyu.melora.ui.my

import com.leyu.melora.ui.common.MeloraBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Delete
import kotlin.math.roundToInt
import androidx.compose.material.icons.outlined.Person
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import com.leyu.melora.ui.common.DetailPageHost
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.leyu.melora.playback.DownloadCenter
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.Downloader
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.BoardItem
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.Recommender
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.common.SongBatchActionsBar
import com.leyu.melora.ui.common.SongSelectionButton
import com.leyu.melora.ui.common.SongSelectionTopBar
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.common.AddToPlaylistSheet
import com.leyu.melora.ui.common.ChromeActionSurface
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.rememberFastScrollToTop
import com.leyu.melora.ui.common.titleScrollToTop
import com.leyu.melora.ui.common.LocalChromeTopInset
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.AccentRed
import com.leyu.melora.ui.common.CardWhite
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.OnlineSongsPage
import com.leyu.melora.ui.common.OnlineSongRow
import com.leyu.melora.ui.common.PlaylistDetailContent
import com.leyu.melora.ui.common.SheetAction
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.leaderboard.BoardDetailContent
import com.leyu.melora.ui.leaderboard.boardPlatforms
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextTabs
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.rememberOnlineSongCover
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.player.SongsCollection
import com.leyu.melora.ui.player.platformLabel
import com.leyu.melora.ui.player.SongsCollectionPage
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

private sealed interface LibraryDetail {
    data class Playlist(val value: OnlinePlaylist) : LibraryDetail
    data class UserPlaylist(val value: UserLibrary.UserPlaylist) : LibraryDetail
    data class Collection(val value: SongsCollection) : LibraryDetail
    data class Board(val value: BoardItem) : LibraryDetail
    data class Container(val value: UserLibrary.RecentContainer) : LibraryDetail
}

// 我的页子页面：目录页点入口后进入的专用页
private enum class MyPage {
    Favorites,
    Recents,
    UserPlaylists,
    Downloads,
}

@Composable
fun MyLibraryScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    val hubListState = rememberLazyListState()
    val favoriteSongs by UserLibrary.favorites.collectAsStateWithLifecycle()
    val recentSongs by UserLibrary.recents.collectAsStateWithLifecycle()
    val favoritePlaylists by UserLibrary.favoritePlaylists.collectAsStateWithLifecycle()
    val userPlaylists by UserLibrary.playlists.collectAsStateWithLifecycle()
    val favoriteAlbums by UserLibrary.favoriteAlbums.collectAsStateWithLifecycle()
    val favoriteArtists by UserLibrary.favoriteArtists.collectAsStateWithLifecycle()
    val recentContainers by UserLibrary.recentContainers.collectAsStateWithLifecycle()
    // 收藏集按类型分流：有声专辑进"听书"，其余歌单进"歌单"；音乐专辑单独存储。
    val favoriteBooks = favoritePlaylists.filter { it.isBookAlbum }
    val favoriteCollections = favoritePlaylists.filterNot { it.isBookAlbum }
    // 收藏的歌曲按类型分流：普通单曲 / 听书节目（章节）
    val singleSongs = favoriteSongs.filterNot { it.isBookChapter }
    val favoriteChapters = favoriteSongs.filter { it.isBookChapter }

    var page by remember { mutableStateOf<MyPage?>(null) }
    var favoritesTab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<LibraryDetail?>(null) }
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<UserLibrary.UserPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<UserLibrary.UserPlaylist?>(null) }

    val openContainer: (UserLibrary.RecentContainer) -> Unit = { container ->
        when (container.kind) {
            "board" -> {
                detail = LibraryDetail.Board(BoardItem(
                    id = container.id,
                    name = container.name,
                    bangid = container.id,
                    img = container.img,
                ))
            }
            "daily", "guess", "new" -> detail = LibraryDetail.Container(container)
            "album" -> detail = LibraryDetail.Collection(SongsCollection(
                title = container.name,
                subtitle = "专辑 · 收录歌曲",
                keyword = container.name,
                source = container.source.ifBlank { "kw" },
                albumName = container.name,
                artistName = container.artist.takeIf { it.isNotBlank() },
                artwork = container.img,
            ))
            "artist" -> detail = LibraryDetail.Collection(SongsCollection(
                title = container.name,
                subtitle = "歌手 · 全部歌曲",
                keyword = container.name,
                source = container.source.ifBlank { "kw" },
                artistName = container.name,
            ))
            else -> detail = LibraryDetail.Playlist(playlistFromContainer(container))
        }
    }

    suspend fun fetchContainerSongs(kind: String): List<OnlineSong> = when (kind) {
        "daily" -> Recommender.daily(context)
        "guess" -> Recommender.guess(context)
        else -> Recommender.newSongs(context)
    }

    if (showCreateSheet) {
        PlaylistEditSheet(
            isRename = false,
            onDismiss = { showCreateSheet = false },
            onConfirm = { name ->
                UserLibrary.createPlaylist(name)
                PlaybackController.postMessage(context, "已创建歌单「$name」")
            },
        )
    }

    playlistToRename?.let { target ->
        PlaylistEditSheet(
            initialName = target.name,
            isRename = true,
            onDismiss = { playlistToRename = null },
            onConfirm = { name ->
                UserLibrary.renamePlaylist(target.id, name)
                PlaybackController.postMessage(context, "已重命名为「$name」")
            },
        )
    }

    playlistToDelete?.let { target ->
        PlaylistDeleteConfirmSheet(
            playlist = target,
            onDismiss = { playlistToDelete = null },
            onConfirm = {
                UserLibrary.deletePlaylist(target.id)
                PlaybackController.postMessage(context, "已删除歌单「${target.name}」")
                playlistToDelete = null
            },
        )
    }

    moreSong?.let { song -> SongMoreSheet(song, onDismiss = { moreSong = null }) }

    DetailPageHost(target = detail, modifier = modifier, detail = { target ->
        when (target) {
            is LibraryDetail.Playlist -> PlaylistDetailContent(target.value, onBack = { detail = null })
            is LibraryDetail.UserPlaylist -> UserPlaylistDetail(
                playlist = target.value, onBack = { detail = null }, onRename = { playlistToRename = it },
                onDelete = { playlistToDelete = it; detail = null },
            )
            is LibraryDetail.Collection -> SongsCollectionPage(target.value, onBack = { detail = null })
            is LibraryDetail.Board -> {
                val source = recentContainers.firstOrNull { it.kind == "board" && it.id == target.value.bangid }?.source ?: "kw"
                val platform = boardPlatforms.firstOrNull { it.id == source } ?: boardPlatforms.first()
                BoardDetailContent(platform = platform, board = target.value, onBack = { detail = null })
            }
            is LibraryDetail.Container -> {
                val container = target.value
                OnlineSongsPage(
                    title = container.name, subtitle = "最近播放 · 为你精选", queueId = container.queueId,
                    cacheKey = "discover.${container.kind}.list", onBack = { detail = null },
                    container = UserLibrary.PlayContainer(
                        kind = container.kind, id = container.id, name = container.name, img = container.img,
                        source = container.source, queueId = container.queueId,
                    ),
                    fetchSongs = { fetchContainerSongs(container.kind) },
                )
            }
        }
    }) {
        DetailPageHost(target = page, detail = { visiblePage ->
            when (visiblePage) {
                MyPage.Favorites -> FavoritesPage(
                    singleSongs = singleSongs,
                    favoriteCollections = favoriteCollections,
                    favoriteAlbums = favoriteAlbums,
                    favoriteArtists = favoriteArtists,
                    favoriteBooks = favoriteBooks,
                    favoriteChapters = favoriteChapters,
                    tab = favoritesTab,
                    onTabChange = { favoritesTab = it },
                    onBack = { page = null },
                    onOpenPlaylist = { detail = LibraryDetail.Playlist(it) },
                    onOpenAlbum = { album ->
                        detail = LibraryDetail.Collection(SongsCollection(
                            title = album.name,
                            subtitle = "专辑 · 收录歌曲",
                            keyword = album.name,
                            source = album.source.ifBlank { "kw" },
                            albumName = album.name,
                            artistName = album.artist.takeIf { it.isNotBlank() },
                            artwork = album.img,
                        ))
                    },
                    onOpenArtist = { artist ->
                        detail = LibraryDetail.Collection(SongsCollection(
                            title = artist.name, subtitle = "歌手 · 全部歌曲", keyword = artist.name,
                            source = artist.source.ifBlank { "kw" }, artistName = artist.name, artwork = artist.img,
                        ))
                    },
                    onMoreSong = { moreSong = it },
                )
                MyPage.Recents -> RecentsPage(
                    recentSongs = recentSongs,
                    recentContainers = recentContainers,
                    onBack = { page = null },
                    onOpenContainer = openContainer,
                    onOpenBookAlbum = { detail = LibraryDetail.Playlist(it) },
                    onMoreSong = { moreSong = it },
                )
                MyPage.UserPlaylists -> UserPlaylistsPage(
                    playlists = userPlaylists,
                    onBack = { page = null },
                    onOpen = { detail = LibraryDetail.UserPlaylist(it) },
                    onCreate = { showCreateSheet = true },
                    onRename = { playlistToRename = it },
                    onDelete = { playlistToDelete = it },
                )
                MyPage.Downloads -> DownloadsPage(onBack = { page = null })
            }
        }) {
            ChromeScaffold(
                expectedTopBarHeight = 64.dp,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Rounded.Menu, "打开侧栏", tint = TextMain)
                        }
                        Box(
                            Modifier.weight(1f).height(64.dp).padding(start = 4.dp)
                                .titleScrollToTop(rememberFastScrollToTop(hubListState)),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text("我的列表", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = TextMain)
                        }
                    }
                },
            ) {
                MyHubPage(
                    modifier = Modifier,
                    favoriteSongs = favoriteSongs,
                    favoriteTotal = favoriteSongs.size + favoritePlaylists.size + favoriteAlbums.size + favoriteArtists.size,
                    recentSongs = recentSongs,
                    recentContainers = recentContainers,
                    favoriteCollections = favoriteCollections,
                    favoriteBooks = favoriteBooks,
                    userPlaylists = userPlaylists,
                    onOpenPage = { page = it },
                    onOpenContainer = openContainer,
                    onOpenBookProgram = { detail = LibraryDetail.Playlist(it) },
                    onOpenUserPlaylist = { detail = LibraryDetail.UserPlaylist(it) },
                    onOpenPlaylist = { detail = LibraryDetail.Playlist(it) },
                    onOpenFavoritesTab = { favoritesTab = it; page = MyPage.Favorites },
                    onCreatePlaylist = { showCreateSheet = true },
                    listState = hubListState,
                )
            }
        }
    }
}

// ---------- 目录页 ----------

@Composable
private fun MyHubPage(
    modifier: Modifier,
    favoriteSongs: List<OnlineSong>,
    favoriteTotal: Int,
    recentSongs: List<OnlineSong>,
    recentContainers: List<UserLibrary.RecentContainer>,
    favoriteCollections: List<OnlinePlaylist>,
    favoriteBooks: List<OnlinePlaylist>,
    userPlaylists: List<UserLibrary.UserPlaylist>,
    onOpenPage: (MyPage) -> Unit,
    onOpenContainer: (UserLibrary.RecentContainer) -> Unit,
    onOpenBookProgram: (OnlinePlaylist) -> Unit,
    onOpenUserPlaylist: (UserLibrary.UserPlaylist) -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenFavoritesTab: (Int) -> Unit,
    onCreatePlaylist: () -> Unit,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val downloads by DownloadCenter.records.collectAsStateWithLifecycle()
    // 聚合卡封面：最近容器封面 → 最近单曲封面（缺图时 CoverLoader 异步补拉）
    val latestSong = recentSongs.firstOrNull()
    val latestSongCover = rememberOnlineSongCover(latestSong, enabled = true)
    val aggregateCover = recentContainers.firstOrNull()?.img?.takeIf(String::isNotBlank)
        ?: latestSongCover
        ?: latestSong?.img
    // 最近节目卡：按听书专辑去重，点击进对应专辑
    val recentPrograms = recentSongs
        .filter { it.isBookChapter }
        .distinctBy { it.albumId.ifBlank { it.uid } }
        .take(3)
    val recentTotal = recentEntryCount(recentSongs, recentContainers)
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = chromeContentPadding(PaddingValues(top = 6.dp, bottom = 24.dp)),
    ) {
        item {
            // 五个入口
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CardWhite,
                shadowElevation = 0.dp,
                border = MeloraAppearance.cardBorder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(vertical = 12.dp)) {
                    // 「收藏」数字 = 收藏页五类合计（单曲/歌单/专辑/听书专辑/听书节目），
                    // 只算单曲会与「收藏歌单」等区块对不上
                    QuickEntry(
                        Icons.Rounded.Favorite,
                        "收藏",
                        favoriteTotal,
                    ) { onOpenFavoritesTab(0) }
                    QuickEntry(Icons.Outlined.History, "最近", recentTotal) { onOpenPage(MyPage.Recents) }
                    QuickEntry(Icons.AutoMirrored.Rounded.QueueMusic, "自建歌单", userPlaylists.size) { onOpenPage(MyPage.UserPlaylists) }
                    QuickEntry(Icons.Outlined.Download, "下载", downloads.size) { onOpenPage(MyPage.Downloads) }
                }
            }
        }

        if (recentSongs.isNotEmpty() || recentContainers.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "最近播放",
                        count = recentTotal,
                        onMore = { onOpenPage(MyPage.Recents) },
                        trailing = {
                            val pool = favoriteSongs.ifEmpty { recentSongs }
                            if (pool.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        PlaybackController.playQueue(context, pool.shuffled().toUiTracks(), 0)
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Shuffle,
                                        contentDescription = "全随机播放",
                                        tint = BrandBlue,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            // 聚合卡：点封面进最近播放页，右下角按钮直接播放
                            RecentAggregateCard(
                                songCount = recentSongs.size,
                                cover = aggregateCover,
                                onOpenRecents = { onOpenPage(MyPage.Recents) },
                                onPlay = {
                                    if (recentSongs.isNotEmpty()) {
                                        PlaybackController.playQueue(context, recentSongs.toUiTracks(), 0)
                                    }
                                },
                            )
                        }
                        itemsIndexed(recentContainers.take(12), key = { _, item -> item.key }) { _, container ->
                            // 推荐/榜单类容器本身没有封面或只有品牌色图：优先用容器第一首歌的封面，并回写持久化
                            val derivedSong = remember(container.key, container.img) { containerCoverSong(container) }
                            val derivedCover = rememberOnlineSongCover(derivedSong, enabled = true)
                            val derived = derivedCover ?: derivedSong?.img
                            val preferDerived = container.kind == "daily" || container.kind == "guess" || container.kind == "board"
                            val cover = if (preferDerived) (derived ?: container.img) else (container.img ?: derived)
                            LaunchedEffect(cover) {
                                if (!cover.isNullOrBlank() && cover != container.img) {
                                    UserLibrary.updateContainerCover(container.key, cover)
                                }
                            }
                            RecentContainerCard(
                                name = container.name,
                                label = containerLabel(container.kind),
                                cover = cover,
                                seed = container.key,
                                onClick = { onOpenContainer(container) },
                            )
                        }
                        itemsIndexed(recentPrograms, key = { _, song -> "p:${song.uid}" }) { _, song ->
                            val cover = rememberOnlineSongCover(song, enabled = true)
                            RecentContainerCard(
                                name = chapterTitle(song),
                                label = "节目",
                                cover = cover ?: song.img,
                                seed = "program:${song.uid}",
                                onClick = { bookAlbumOf(song)?.let(onOpenBookProgram) },
                            )
                        }
                    }
                }
            }
        }

        item {
            // 自建歌单：预览 3 行，更多进子页
            Column {
                SectionHeader(
                    title = "自建歌单",
                    count = userPlaylists.size,
                    onMore = { onOpenPage(MyPage.UserPlaylists) },
                    trailing = {
                        IconButton(onClick = onCreatePlaylist) {
                            Icon(Icons.Outlined.Add, contentDescription = "新建歌单", tint = BrandBlue, modifier = Modifier.size(20.dp))
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (userPlaylists.isEmpty()) {
                    Surface(
                        onClick = onCreatePlaylist,
                        shape = RoundedCornerShape(16.dp),
                        color = CardWhite,
                        shadowElevation = 0.dp,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MeloraAppearance.tintBlue),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("新建歌单", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextMain)
                                Text("创建专属歌单，收藏心动旋律", fontSize = 11.5.sp, color = TextSub, modifier = Modifier.padding(top = 1.dp))
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = CardWhite,
                        shadowElevation = 0.dp,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            userPlaylists.take(3).forEachIndexed { index, playlist ->
                                val first = playlist.songs.firstOrNull()
                                val cover = rememberOnlineSongCover(first, enabled = true)
                                PlaylistListRow(
                                    name = playlist.name,
                                    meta = "${playlist.songs.size} 首歌曲",
                                    img = cover ?: first?.img,
                                    seed = playlist.id,
                                    fallback = Icons.AutoMirrored.Rounded.QueueMusic,
                                    onClick = { onOpenUserPlaylist(playlist) },
                                )
                                if (index < minOf(userPlaylists.size, 3) - 1) {
                                    HorizontalDivider(
                                        color = MeloraAppearance.divider,
                                        thickness = 0.6.dp,
                                        modifier = Modifier.padding(start = 68.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (favoriteCollections.isNotEmpty() || favoriteBooks.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(
                        title = "收藏歌单",
                        count = favoriteCollections.size + favoriteBooks.size,
                        onMore = { onOpenFavoritesTab(1) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = CardWhite,
                        shadowElevation = 0.dp,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            val previews: List<OnlinePlaylist> = (favoriteCollections + favoriteBooks).take(3)
                            previews.forEachIndexed { index, playlist ->
                                PlaylistListRow(
                                    name = playlist.name,
                                    meta = listOfNotNull(
                                        playlist.author.takeIf { it.isNotBlank() },
                                        playlist.total.takeIf { it > 0 }?.let { "$it 首" },
                                    ).joinToString(" · ").ifBlank { "歌单" },
                                    img = playlist.img,
                                    seed = "${playlist.source}_${playlist.id}",
                                    fallback = if (playlist.isBookAlbum) Icons.Outlined.Headphones else Icons.AutoMirrored.Rounded.QueueMusic,
                                    onClick = { onOpenPlaylist(playlist) },
                                )
                                if (index < previews.lastIndex) {
                                    HorizontalDivider(
                                        color = MeloraAppearance.divider,
                                        thickness = 0.6.dp,
                                        modifier = Modifier.padding(start = 68.dp),
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

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickEntry(
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MeloraAppearance.tintBlue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
        Text("$count", fontSize = 10.sp, color = TextMuted, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    onMore: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextMain)
        Spacer(Modifier.width(5.dp))
        Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
        if (onMore != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("更多", fontSize = 12.sp, color = TextSub)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextSub,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentAggregateCard(
    songCount: Int,
    cover: String?,
    onOpenRecents: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenRecents),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1F242E)),
        ) {
            if (!cover.isNullOrBlank()) {
                SongArtwork(cover, "recent-aggregate", Modifier.fillMaxSize(), cornerRadius = 14)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(34.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "播放全部",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Text(
            text = "已播歌曲",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "$songCount 首",
            fontSize = 9.5.sp,
            color = TextMuted,
            maxLines = 1,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** 最近收听容器卡：封面点击进入对应的歌单/专辑/榜单/推荐页。 */
@Composable
private fun RecentContainerCard(
    name: String,
    label: String,
    cover: String?,
    seed: String,
    onClick: () -> Unit,
) {
    val fallbackIcon = when (label) {
        "听书", "节目" -> Icons.Outlined.Headphones
        "专辑" -> Icons.Outlined.Album
        else -> Icons.AutoMirrored.Rounded.QueueMusic
    }
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MeloraAppearance.tintBlue),
            contentAlignment = Alignment.Center,
        ) {
            if (!cover.isNullOrBlank()) {
                SongArtwork(cover, seed, Modifier.fillMaxSize(), cornerRadius = 14)
            } else {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** 最近收听容器类型标签。 */
private fun containerLabel(kind: String): String = when (kind) {
    "book" -> "听书"
    "board" -> "榜单"
    "album" -> "专辑"
    "artist" -> "歌手"
    "daily" -> "每日推荐"
    "guess" -> "猜你喜欢"
    "new" -> "新歌推荐"
    else -> "歌单"
}

/** 推荐位/榜单类容器的封面兜底：取该容器缓存列表的第一首歌（在线缓存未命中则返回 null）。 */
private fun containerCoverSong(container: UserLibrary.RecentContainer): OnlineSong? = when (container.kind) {
    "daily" -> OnlineCache.peek<List<OnlineSong>>("discover.daily.list")?.firstOrNull()
    "guess" -> OnlineCache.peek<List<OnlineSong>>("discover.guess.list")?.firstOrNull()
    "new" -> OnlineCache.peek<List<OnlineSong>>("discover.new.list")?.firstOrNull()
    "board" -> OnlineCache.peek<SongPage>("boardSongsPage.${container.source}.${container.id}")?.list?.firstOrNull()
    else -> null
}

/** 由最近收听容器反推歌单实体（有声专辑 id 已带 book_album_ 前缀）。 */
private fun playlistFromContainer(container: UserLibrary.RecentContainer): OnlinePlaylist {
    val raw = JSONObject().apply {
        put("id", container.id)
        put("name", container.name)
        put("source", container.source)
        put("img", container.img ?: "")
    }
    return OnlinePlaylist.from(raw)!!
}

@Composable
private fun PlaylistListRow(
    name: String,
    meta: String,
    img: String?,
    seed: String,
    fallback: ImageVector,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MeloraAppearance.tintBlue),
            contentAlignment = Alignment.Center,
        ) {
            if (!img.isNullOrBlank() && img.startsWith("http")) {
                SongArtwork(img, seed, Modifier.fillMaxSize(), cornerRadius = 10)
            } else {
                Icon(fallback, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta,
                fontSize = 11.sp,
                color = TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing?.invoke()
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------- 子页框架 ----------

@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit = {},
    expectedTopBarHeight: Dp? = null,
    actions: @Composable () -> Unit = {},
    header: @Composable (@Composable () -> Unit) -> Unit = { it() },
    topBarContent: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    ChromeScaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(chromeHeaderColor()),
            ) {
                header {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(start = 0.5.dp, end = 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = TextMain)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .titleScrollToTop(onTitleClick),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            actions()
                        }
                        topBarContent()
                    }
                }
            }
        },
        expectedTopBarHeight = expectedTopBarHeight,
        bottomBar = bottomBar,
    ) {
        content()
    }
}

// ---------- 我的收藏子页 ----------

@Composable
private fun FavoritesPage(
    singleSongs: List<OnlineSong>,
    favoriteCollections: List<OnlinePlaylist>,
    favoriteAlbums: List<UserLibrary.FavoriteAlbum>,
    favoriteArtists: List<UserLibrary.FavoriteArtist>,
    favoriteBooks: List<OnlinePlaylist>,
    favoriteChapters: List<OnlineSong>,
    onBack: () -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (UserLibrary.FavoriteAlbum) -> Unit,
    onOpenArtist: (UserLibrary.FavoriteArtist) -> Unit,
    onMoreSong: (OnlineSong) -> Unit,
    tab: Int,
    onTabChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val selection = remember { SongSelectionState() }
    // 每个收藏分类独立保存滚动位置，切换 Tab 不丢失上下文。
    val listStates = listOf(
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
    )
    val listState = listStates[tab]
    val scrollToTop = rememberFastScrollToTop(listState)
    SubPageScaffold(
        title = "我的收藏",
        onBack = { if (selection.active) selection.finish() else onBack() },
        onTitleClick = scrollToTop,
        header = { normal -> SongSelectionTopBar(selection, singleSongs, normal) },
        topBarContent = {
            TextTabs(
                labels = listOf("单曲", "歌单", "专辑", "听书", "节目", "歌手"),
                counts = listOf(
                    singleSongs.size,
                    favoriteCollections.size,
                    favoriteAlbums.size,
                    favoriteBooks.size,
                    favoriteChapters.size,
                    favoriteArtists.size,
                ),
                selected = tab,
                onSelect = { selection.finish(); onTabChange(it) },
            )
            if (tab == 0 && singleSongs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 11.5.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayAllPill { PlaybackController.playQueue(context, singleSongs.toUiTracks(), 0) }
                    Spacer(Modifier.weight(1f))
                    SongSelectionButton(onClick = selection::start)
                }
            }
        },
        bottomBar = { SongBatchActionsBar(selection, singleSongs, removeFavorites = true) },
    ) {
        Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> if (singleSongs.isEmpty()) {
                        EmptyState(
                            "还没有收藏的歌曲\n在歌曲更多菜单中点击收藏",
                            Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                        ) {
                            itemsIndexed(singleSongs, key = { index, song -> "${song.uid}:$index" }) { index, song ->
                                OnlineSongRow(
                                    song = song,
                                    showAlbum = true,
                                    isFavorite = true,
                                    selectionMode = selection.active,
                                    selected = song.uid in selection.selectedUids,
                                    onMore = { onMoreSong(song) },
                                    onClick = {
                                        if (selection.active) selection.toggle(song.uid)
                                        else PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                                    },
                                )
                            }
                        }
                    }

                    1 -> if (favoriteCollections.isEmpty()) {
                        EmptyState(
                            "还没有收藏的歌单\n在歌单页点击红心收藏",
                            Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                        )
                    } else {
                        PlaylistListColumn(
                            playlists = favoriteCollections,
                            onOpen = onOpenPlaylist,
                            listState = listState,
                        )
                    }

                    2 -> if (favoriteAlbums.isEmpty()) {
                        EmptyState(
                            "还没有收藏的专辑\n在播放页「出自专辑」里点红心收藏",
                            Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = chromeContentPadding(PaddingValues(horizontal = 16.dp, vertical = 6.dp)),
                        ) {
                            items(favoriteAlbums, key = { it.key }) { album ->
                                PlaylistListRow(
                                    name = album.name,
                                    meta = album.artist.ifBlank { "专辑" },
                                    img = album.img,
                                    seed = album.key,
                                    fallback = Icons.Outlined.Album,
                                    onClick = { onOpenAlbum(album) },
                                )
                            }
                        }
                    }

                    3 -> if (favoriteBooks.isEmpty()) {
                        EmptyState(
                            "还没有收藏的听书\n在听书页点击红心收藏",
                            Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                        )
                    } else {
                        PlaylistListColumn(
                            playlists = favoriteBooks,
                            onOpen = onOpenPlaylist,
                            listState = listState,
                        )
                    }

                    5 -> if (favoriteArtists.isEmpty()) {
                        EmptyState("还没有收藏的歌手\n在艺术家详情页点击红心收藏", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = chromeContentPadding(PaddingValues(horizontal = 16.dp, vertical = 6.dp)),
                        ) {
                            items(favoriteArtists, key = { it.key }) { artist ->
                                PlaylistListRow(
                                    name = artist.name, meta = "歌手 · ${platformLabel(artist.source)}", img = artist.img,
                                    seed = artist.key, fallback = Icons.Outlined.Person,
                                    onClick = { onOpenArtist(artist) },
                                )
                            }
                        }
                    }

                    else -> if (favoriteChapters.isEmpty()) {
                        EmptyState(
                            "还没有收藏的节目\n在章节更多菜单中点击收藏",
                            Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                        ) {
                            itemsIndexed(favoriteChapters, key = { index, song -> "${song.uid}:$index" }) { _, song ->
                                BookProgramRow(
                                    song = song,
                                    subtitle = chapterTitle(song),
                                    onClick = { bookAlbumOf(song)?.let(onOpenPlaylist) },
                                    onMore = { onMoreSong(song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun PlaylistListColumn(
    playlists: List<OnlinePlaylist>,
    onOpen: (OnlinePlaylist) -> Unit,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = chromeContentPadding(PaddingValues(horizontal = 16.dp, vertical = 6.dp)),
    ) {
        itemsIndexed(playlists, key = { _, item -> "${item.source}_${item.id}" }) { _, playlist ->
            PlaylistListRow(
                name = playlist.name,
                meta = listOfNotNull(
                    playlist.author.takeIf { it.isNotBlank() },
                    playlist.total.takeIf { it > 0 }?.let { "$it 首" },
                ).joinToString(" · ").ifBlank { "歌单" },
                img = playlist.img,
                seed = "${playlist.source}_${playlist.id}",
                fallback = if (playlist.isBookAlbum) Icons.Outlined.Headphones else Icons.AutoMirrored.Rounded.QueueMusic,
                onClick = { onOpen(playlist) },
            )
        }
    }
}

// ---------- 最近播放子页 ----------

/** 首页入口采用与最近页「全部」相同口径：单曲 + 容器 + 按专辑聚合的节目。 */
internal fun recentEntryCount(songs: List<OnlineSong>, containers: List<UserLibrary.RecentContainer>): Int =
    songs.count { !it.isBookChapter } + containers.size +
        songs.filter { it.isBookChapter }.distinctBy { it.albumId.ifBlank { it.uid } }.size

/** 全部数量与四个可见分类相加一致，听书容器不可漏计。 */
internal fun recentTabCounts(songs: Int, collections: Int, books: Int, programs: Int): List<Int> =
    listOf(songs + collections + books + programs, songs, collections, books, programs)

@Composable
private fun RecentsPage(
    recentSongs: List<OnlineSong>,
    recentContainers: List<UserLibrary.RecentContainer>,
    onBack: () -> Unit,
    onOpenContainer: (UserLibrary.RecentContainer) -> Unit,
    onOpenBookAlbum: (OnlinePlaylist) -> Unit,
    onMoreSong: (OnlineSong) -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 最近播放的五个分类各自保留滚动位置，切换分类不会重置列表。
    val listStates = listOf(
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
    )
    val listState = listStates[tab]
    val scrollToTop = rememberFastScrollToTop(listState)
    val songs = recentSongs.filterNot { it.isBookChapter }
    val chapters = recentSongs.filter { it.isBookChapter }.distinctBy { it.albumId.ifBlank { it.uid } }
    // 容器按类型分流：歌单/榜单/推荐 与 听书专辑
    val collectionContainers = recentContainers.filter { it.kind != "book" }
    val bookContainers = recentContainers.filter { it.kind == "book" }

    SubPageScaffold(
        title = "最近播放",
        onBack = onBack,
        onTitleClick = scrollToTop,
        actions = {
            if (recentSongs.isNotEmpty() || recentContainers.isNotEmpty()) {
                Text(
                    "清空",
                    fontSize = 13.sp,
                    color = TextSub,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { UserLibrary.clearRecents() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        },
        topBarContent = {
            TextTabs(
                labels = listOf("全部", "单曲", "歌单专辑", "听书", "节目"),
                counts = recentTabCounts(songs.size, collectionContainers.size, bookContainers.size, chapters.size),
                selected = tab,
                onSelect = { tab = it },
            )
            if (tab == 1 && songs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = TextSub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${songs.size} 条记录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSub)
                    Spacer(Modifier.weight(1f))
                    PlayAllPill { PlaybackController.playQueue(context, songs.toUiTracks(), 0) }
                }
            } else if (tab == 4 && chapters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null, tint = TextSub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${chapters.size} 个节目", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSub)
                }
            }
        },
    ) {
        when (tab) {
            // 全部：容器/单曲/节目各用自己的视图混排
            0 -> if (collectionContainers.isEmpty() && bookContainers.isEmpty() && songs.isEmpty() && chapters.isEmpty()) {
                EmptyState("暂无播放记录", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                ) {
                    if (recentContainers.isNotEmpty()) {
                        item(contentType = "section") { RecentSectionTitle("最近收听") }
                        items(recentContainers, key = { "c:${it.key}" }, contentType = { "container" }) { container ->
                            RecentContainerRow(container, onOpenContainer)
                        }
                    }
                    if (songs.isNotEmpty()) {
                        item(contentType = "section") { RecentSectionTitle("单曲") }
                        itemsIndexed(songs, key = { index, song -> "s:${song.uid}:$index" }, contentType = { _, _ -> "song" }) { index, song ->
                            OnlineSongRow(
                                song = song,
                                onMore = { onMoreSong(song) },
                                onClick = { PlaybackController.playTrack(context, UiTrack.fromOnline(song)) },
                            )
                        }
                    }
                    if (chapters.isNotEmpty()) {
                        item(contentType = "section") { RecentSectionTitle("节目") }
                        itemsIndexed(chapters, key = { index, song -> "b:${song.albumId}:$index" }, contentType = { _, _ -> "program" }) { _, song ->
                            BookProgramRow(
                                song = song,
                                subtitle = "最近播放：${chapterTitle(song)}",
                                onClick = { bookAlbumOf(song)?.let(onOpenBookAlbum) },
                                onMore = { onMoreSong(song) },
                            )
                        }
                    }
                }
            }

            // 单曲
            1 -> if (songs.isEmpty()) {
                EmptyState("暂无播放记录", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                ) {
                    itemsIndexed(songs, key = { index, song -> "${song.uid}:$index" }) { index, song ->
                        OnlineSongRow(
                            song = song,
                            onMore = { onMoreSong(song) },
                            onClick = { PlaybackController.playTrack(context, UiTrack.fromOnline(song)) },
                        )
                    }
                }
            }

            // 歌单/专辑/榜单/推荐
            2 -> if (collectionContainers.isEmpty()) {
                EmptyState("暂无歌单/专辑播放记录", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = chromeContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)),
                ) {
                    items(collectionContainers, key = { it.key }) { container ->
                        RecentContainerRow(container, onOpenContainer, showCard = true)
                    }
                }
            }

            // 听书专辑
            3 -> if (bookContainers.isEmpty()) {
                EmptyState("暂无听书播放记录", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = chromeContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)),
                ) {
                    items(bookContainers, key = { it.key }) { container ->
                        RecentContainerRow(container, onOpenContainer, showCard = true)
                    }
                }
            }

            // 节目（听书章节）
            else -> if (chapters.isEmpty()) {
                EmptyState("暂无节目播放记录", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                ) {
                    itemsIndexed(chapters, key = { index, song -> "b:${song.albumId}:$index" }, contentType = { _, _ -> "program" }) { _, song ->
                        BookProgramRow(
                            song = song,
                            subtitle = "最近播放：${chapterTitle(song)}",
                            onClick = { bookAlbumOf(song)?.let(onOpenBookAlbum) },
                            onMore = { onMoreSong(song) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextSub,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** 最近收听容器行：封面 + 名称 + 类型标签，点击进入对应页面。 */
@Composable
private fun RecentContainerRow(
    container: UserLibrary.RecentContainer,
    onOpen: (UserLibrary.RecentContainer) -> Unit,
    showCard: Boolean = false,
) {
    val row: @Composable () -> Unit = {
        PlaylistListRow(
            name = container.name,
            meta = containerLabel(container.kind),
            img = container.img,
            seed = container.key,
            fallback = if (container.kind == "book") Icons.Outlined.Headphones else Icons.AutoMirrored.Rounded.QueueMusic,
            onClick = { onOpen(container) },
        )
    }
    if (showCard) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardWhite,
            shadowElevation = 0.dp,
            border = MeloraAppearance.cardBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            row()
        }
    } else {
        row()
    }
}

/** 章节显示名：去掉专辑名前缀，保留"第xx集 标题"。 */
internal fun chapterTitle(song: OnlineSong): String =
    song.name.removePrefix(song.albumName).trim().ifBlank { song.name }

/** 由章节歌曲反推有声专辑实体，供点击进入节目页复用。 */
internal fun bookAlbumOf(song: OnlineSong): OnlinePlaylist? {
    val albumId = song.albumId
    if (albumId.isBlank()) return null
    val raw = JSONObject().apply {
        put("id", "book_album_$albumId")
        put("name", song.albumName.ifBlank { song.name })
        put("source", song.source)
        put("author", song.singer)
        put("img", song.img ?: "")
        put("description", song.raw.optString("description"))
        put("play_count", song.raw.optString("play_count"))
    }
    return OnlinePlaylist.from(raw)
}

/** 听书节目行：标题=节目名，副标题=最近播到哪一集；点击进节目。 */
@Composable
private fun BookProgramRow(
    song: OnlineSong,
    subtitle: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    val cover = rememberOnlineSongCover(song, enabled = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MeloraAppearance.tintBlue),
            contentAlignment = Alignment.Center,
        ) {
            SongArtwork(cover ?: song.img, song.uid, Modifier.fillMaxSize(), cornerRadius = 8)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = song.albumName.ifBlank { song.name },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------- 下载记录子页 ----------

@Composable
private fun DownloadsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by DownloadCenter.records.collectAsStateWithLifecycle()
    var actionRecordId by remember { mutableStateOf<String?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<OnlineSong?>(null) }
    val listState = rememberLazyListState()
    val scrollToTop = rememberFastScrollToTop(listState)

    val retry: (DownloadCenter.Record) -> Unit = { record ->
        if (record.song != null) {
            if (record.upgradeFrom != null) {
                Downloader.retry(context, record)
            } else {
                PlaybackController.postMessage(context, "开始下载：${record.name}")
                scope.launch {
                    Downloader.retry(context, record).await()
                        .onSuccess { PlaybackController.postMessage(context, it) }
                        .onFailure { PlaybackController.postMessage(context, it.message ?: "下载失败") }
                }
            }
        }
    }

    SubPageScaffold(
        title = "下载",
        onBack = onBack,
        onTitleClick = scrollToTop,
        expectedTopBarHeight = 64.dp,
        actions = {
            if (records.any { it.status == DownloadCenter.Status.Done || it.status == DownloadCenter.Status.Failed }) {
                Text(
                    "清除记录",
                    fontSize = 13.sp,
                    color = TextSub,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            DownloadCenter.clearFinished()
                            PlaybackController.postMessage(context, "已清除结束记录，本地文件仍保留")
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        },
    ) {
        if (records.isEmpty()) {
            EmptyState(
                "暂无下载记录\n在歌曲更多菜单中点击「下载音频」",
                Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = chromeContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)),
            ) {
                items(records, key = { it.id }) { record ->
                    SwipeToRevealDeleteCard(
                        onDelete = {
                            scope.launch {
                                Downloader.deletePermanently(context, record)
                                    .onSuccess { PlaybackController.postMessage(context, it) }
                                    .onFailure { PlaybackController.postMessage(context, it.message ?: "删除失败") }
                            }
                        },
                    ) {
                        DownloadRecordCard(
                            record = record,
                            onPause = { Downloader.pause(context, record.id) },
                            onResume = { retry(record) },
                            onRetry = { retry(record) },
                            onMore = { actionRecordId = record.id },
                        )
                    }
                }
            }
        }
    }

    records.firstOrNull { it.id == actionRecordId }?.let { record ->
        DownloadRecordSheet(
            record = record,
            onDismiss = { actionRecordId = null },
            onPlay = {
                if (PlaybackController.state.value.current?.uid == record.id) {
                    PlaybackController.toggle()
                } else record.song?.let { song ->
                    PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                }
                actionRecordId = null
            },
            onPause = {
                Downloader.pause(context, record.id)
                actionRecordId = null
            },
            onResume = {
                retry(record)
                actionRecordId = null
            },
            onRetry = {
                retry(record)
                actionRecordId = null
            },
            onDeleteFile = {
                scope.launch {
                    Downloader.deletePermanently(context, record)
                        .onSuccess { PlaybackController.postMessage(context, it) }
                        .onFailure { PlaybackController.postMessage(context, it.message ?: "删除失败") }
                }
                actionRecordId = null
            },
            onRemoveRecord = {
                val removed = Downloader.removeRecordOnly(context, record.id)
                PlaybackController.postMessage(
                    context,
                    if (removed) "已移除下载记录，本地文件仍保留" else "任务尚未结束，请先暂停或删除任务",
                )
                actionRecordId = null
            },
            onAddToPlaylist = {
                addToPlaylistSong = record.song
                actionRecordId = null
            },
        )
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistSheet(song) { addToPlaylistSong = null }
    }
}

/** 微信/iOS 风格联动推入滑动容器：卡片与删除按钮整行联动，手势跟手，弹性阻尼 */
@Composable
private fun SwipeToRevealDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val deleteButtonWidth = 72.dp
    val spacing = 8.dp
    val totalRevealWidth = deleteButtonWidth + spacing
    val totalRevealPx = with(density) { totalRevealWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = offsetX,
                targetValue = target,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) { value, _ -> offsetX = value }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        Layout(
            content = {
                // Child 0: 歌曲卡片（占满整个视口宽度）
                Box(
                    modifier = Modifier.clickable(
                        enabled = offsetX < -1f,
                        onClick = { settle(0f) },
                    ),
                ) {
                    content()
                }

                // Child 1: 红色删除按钮（整行跟随推入）
                Box(
                    modifier = Modifier
                        .width(deleteButtonWidth)
                        .clip(shape)
                        .background(AccentRed)
                        .clickable {
                            settleJob?.cancel()
                            offsetX = 0f
                            onDelete()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除本地文件或下载任务",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "删除",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(totalRevealPx) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            tracker.resetTracking()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            offsetX = (offsetX + dragAmount).coerceIn(-totalRevealPx, 0f)
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().x
                            val target = when {
                                velocity < -400f -> -totalRevealPx
                                velocity > 400f -> 0f
                                offsetX < -totalRevealPx * 0.4f -> -totalRevealPx
                                else -> 0f
                            }
                            if (kotlin.math.abs(target - offsetX) < 1f) offsetX = target else settle(target)
                        },
                        onDragCancel = { settle(0f) },
                    )
                },
        ) { measurables, constraints ->
            val cardWidth = constraints.maxWidth
            val deleteWidth = with(density) { deleteButtonWidth.roundToPx() }
            val gap = with(density) { spacing.roundToPx() }

            val cardPlaceable = measurables[0].measure(
                constraints.copy(minWidth = cardWidth, maxWidth = cardWidth),
            )
            val cardHeight = cardPlaceable.height
            val deletePlaceable = measurables[1].measure(
                constraints.copy(
                    minWidth = deleteWidth,
                    maxWidth = deleteWidth,
                    minHeight = cardHeight,
                    maxHeight = cardHeight,
                ),
            )

            layout(cardWidth, cardHeight) {
                cardPlaceable.placeRelative(0, 0)
                deletePlaceable.placeRelative(cardWidth + gap, 0)
            }
        }
    }
}

/** 下载记录卡：整卡不再触发播放，封面只承载暂停、继续与重试快捷操作。 */
@Composable
private fun DownloadRecordCard(
    record: DownloadCenter.Record,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
) {
    val resolvedArtwork = rememberOnlineSongCover(record.song, enabled = record.img == null)
    LaunchedEffect(record.id, resolvedArtwork) {
        resolvedArtwork?.let { DownloadCenter.updateArtwork(record.id, it) }
    }
    val artwork = record.img ?: resolvedArtwork

    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = CardWhite,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MeloraAppearance.tintBlue),
                contentAlignment = Alignment.Center,
            ) {
                SongArtwork(artwork, record.id, Modifier.fillMaxSize(), cornerRadius = 10)

                // 封面快捷操作遮罩：区分 暂停 / 恢复 / 重试
                when (record.status) {
                    DownloadCenter.Status.Downloading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.38f))
                                .clickable(onClick = onPause),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Pause,
                                contentDescription = "暂停下载",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    DownloadCenter.Status.Paused -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable(onClick = onResume),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "继续下载",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    DownloadCenter.Status.Failed -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable(onClick = onRetry),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "重试下载",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    DownloadCenter.Status.Done -> Unit
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = record.detail,
                    fontSize = 11.sp,
                    color = when (record.status) {
                        DownloadCenter.Status.Failed -> AccentRed
                        DownloadCenter.Status.Paused -> BrandBlue
                        else -> TextSub
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (record.status == DownloadCenter.Status.Downloading) {
                    LinearProgressIndicator(
                        progress = { record.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp),
                        color = BrandBlue,
                        trackColor = MeloraAppearance.divider,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            if (record.status == DownloadCenter.Status.Downloading) {
                Text(
                    "${record.percent}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 下载记录操作面板：播放/重试/删除本地文件/移除记录 + 单曲常用操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadRecordSheet(
    record: DownloadCenter.Record,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDeleteFile: () -> Unit,
    onRemoveRecord: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
    val song = record.song
    val isFavorite = song != null && song.uid in favoriteUids
    val playback by PlaybackController.state.collectAsStateWithLifecycle()
    val isCurrent = playback.current?.uid == record.id
    val isPlaying = isCurrent && playback.playing
    val selectedDownloadQuality by MeloraSettings.downloadQuality.collectAsStateWithLifecycle()

    MeloraBottomSheet(
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
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            // 头部卡片：48dp 封面/下载图标 + 任务名与状态详情
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (song != null && !song.img.isNullOrBlank()) {
                    SongArtwork(
                        song.img,
                        "more_dl_${song.uid}",
                        Modifier.size(48.dp),
                        cornerRadius = 12,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = record.detail,
                        fontSize = 12.sp,
                        color = if (record.status == DownloadCenter.Status.Failed) AccentRed else TextSub,
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
                when (record.status) {
                    DownloadCenter.Status.Downloading -> {
                        SheetAction(
                            icon = Icons.Rounded.Pause,
                            tint = BrandBlue,
                            label = "暂停下载",
                            subtitle = "暂停当前传输中的任务",
                        ) {
                            onPause()
                            onDismiss()
                        }
                        SheetAction(
                            icon = Icons.Outlined.DeleteOutline,
                            tint = AccentRed,
                            label = "删除下载任务",
                            subtitle = "停止传输并清理未完成任务",
                        ) {
                            onDeleteFile()
                            onDismiss()
                        }
                    }
                    DownloadCenter.Status.Paused -> {
                        SheetAction(
                            icon = Icons.Rounded.PlayArrow,
                            tint = BrandBlue,
                            label = "继续下载",
                            subtitle = "重新建立下载流并继续任务",
                        ) {
                            onResume()
                            onDismiss()
                        }
                        SheetAction(
                            icon = Icons.Outlined.DeleteOutline,
                            tint = AccentRed,
                            label = "删除下载任务",
                            subtitle = "清理暂停的下载任务",
                        ) {
                            onDeleteFile()
                            onDismiss()
                        }
                    }
                    DownloadCenter.Status.Done -> {
                        if (song != null) {
                            SheetAction(
                                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                tint = BrandBlue,
                                label = if (isPlaying) "暂停播放" else if (isCurrent) "继续播放" else "立即播放",
                                subtitle = if (isPlaying) "暂停当前歌曲" else if (isCurrent) "从当前位置继续播放" else "开始播放该歌曲",
                            ) { onPlay() }
                        }
                        if (song != null && !com.leyu.melora.playback.downloadQualityMatches(record.audioSpec, selectedDownloadQuality)) {
                            SheetAction(
                                icon = Icons.Outlined.Download,
                                tint = BrandBlue,
                                label = "重新下载所选音质",
                                subtitle = "按下载设置保存，新旧版本分别保留",
                            ) { onRetry() }
                        }
                        if (record.hasSavedResource && (record.fileName != null || record.savedUri != null)) {
                            SheetAction(
                                icon = Icons.Outlined.DeleteOutline,
                                tint = AccentRed,
                                label = "删除本地文件",
                                subtitle = "删除真实音频文件并同步清理下载记录",
                            ) { onDeleteFile() }
                        }
                        SheetAction(
                            icon = Icons.Outlined.RemoveCircleOutline,
                            tint = TextSub,
                            label = "仅移除下载记录",
                            subtitle = "从下载任务列表中清理该记录，保留本地音频文件",
                        ) { onRemoveRecord() }
                    }
                    DownloadCenter.Status.Failed -> {
                        if (song != null) {
                            SheetAction(
                                icon = Icons.Outlined.Refresh,
                                tint = BrandBlue,
                                label = "重试下载",
                                subtitle = "重新建立下载任务拉取音频",
                            ) { onRetry() }
                        }
                        if (record.hasSavedResource && (record.fileName != null || record.savedUri != null)) {
                            SheetAction(
                                icon = Icons.Outlined.DeleteOutline,
                                tint = AccentRed,
                                label = "删除本地文件",
                                subtitle = "删除仍保留的真实音频文件并清理记录",
                            ) { onDeleteFile() }
                        }
                        SheetAction(
                            icon = Icons.Outlined.RemoveCircleOutline,
                            tint = TextSub,
                            label = "移除失败记录",
                            subtitle = "仅清理下载列表记录，不触碰本地文件",
                        ) { onRemoveRecord() }
                    }
                }

                if (song != null) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MeloraAppearance.divider, thickness = 0.6.dp, modifier = Modifier.padding(horizontal = 6.dp))
                    Spacer(Modifier.height(4.dp))

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
                        onAddToPlaylist()
                    }
                }
            }
        }
    }
}

// ---------- 歌单编辑/新建弹窗 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEditSheet(
    initialName: String = "",
    isRename: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    var textValue by remember(initialName) {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(initialName.length)))
    }
    val canSubmit = textValue.text.trim().isNotEmpty()

    val submit = {
        if (canSubmit) {
            onConfirm(textValue.text.trim())
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        delay(220)
        runCatching { focusRequester.requestFocus() }
    }

    MeloraBottomSheet(
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
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .imePadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MeloraAppearance.tintBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isRename) Icons.Outlined.Edit else Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = BrandBlue,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isRename) "重命名歌单" else "新建歌单",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                    )
                    Text(
                        text = if (isRename) "输入新的歌单标题" else "为你的好音乐找个归宿",
                        fontSize = 12.sp,
                        color = TextSub,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // 精致圆角输入胶囊框
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MeloraAppearance.card,
                border = MeloraAppearance.chipBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMain,
                        ),
                        cursorBrush = SolidColor(BrandBlue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (textValue.text.isEmpty()) {
                                    Text(
                                        text = "歌单名称…",
                                        fontSize = 15.sp,
                                        color = TextMuted,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (textValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = { textValue = TextFieldValue("") },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "清空",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // 快捷灵感推荐标签（仅新建时展示）
            if (!isRename) {
                Spacer(Modifier.height(14.dp))
                val suggestions = listOf("我的私藏", "单曲循环", "夜晚微醺", "车载流行", "工作伴听", "宝藏旋律")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(suggestions) { tag ->
                        Surface(
                            onClick = {
                                textValue = TextFieldValue(tag, selection = TextRange(tag.length))
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MeloraAppearance.softFill,
                            border = MeloraAppearance.chipBorder,
                        ) {
                            Text(
                                text = tag,
                                fontSize = 12.sp,
                                color = TextSub,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 底部操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(22.dp),
                    color = MeloraAppearance.softFill,
                    border = MeloraAppearance.chipBorder,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("取消", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextSub)
                    }
                }
                Surface(
                    onClick = submit,
                    enabled = canSubmit,
                    shape = RoundedCornerShape(22.dp),
                    color = if (canSubmit) BrandBlue else BrandBlue.copy(alpha = 0.35f),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isRename) "保存修改" else "立即创建",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

// ---------- 歌单选项抽屉 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistMoreSheet(
    playlist: UserLibrary.UserPlaylist,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onAddToQueue: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    MeloraBottomSheet(
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
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            // 头部卡片：48dp 歌单封面/图集 + 歌单名与歌曲数量
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val firstCover = playlist.songs.firstOrNull()?.img
                if (!firstCover.isNullOrBlank()) {
                    SongArtwork(
                        firstCover,
                        "playlist_more_${playlist.id}",
                        Modifier.size(48.dp),
                        cornerRadius = 12,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrandBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${playlist.songs.size} 首歌曲 · 自建歌单",
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
                if (playlist.songs.isNotEmpty()) {
                    SheetAction(
                        icon = Icons.Rounded.PlayArrow,
                        tint = BrandBlue,
                        label = "播放全部",
                        subtitle = "立即开始播放歌单内全部歌曲",
                        onClick = { onDismiss(); onPlayAll() },
                    )
                    SheetAction(
                        icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                        tint = BrandBlue,
                        label = "添加全部歌曲到播放队列",
                        subtitle = "追加到队列末尾，不打断当前播放",
                        onClick = { onDismiss(); onAddToQueue() },
                    )
                }
                SheetAction(
                    icon = Icons.Outlined.Edit,
                    tint = Color(0xFF0284C7),
                    label = "重命名歌单",
                    subtitle = "修改该歌单的显示名称",
                    onClick = { onDismiss(); onRename() },
                )
                SheetAction(
                    icon = Icons.Outlined.DeleteOutline,
                    tint = AccentRed,
                    label = "删除歌单",
                    subtitle = "彻底删除此歌单及其收录记录",
                    onClick = { onDismiss(); onDelete() },
                )
            }
        }
    }
}

// ---------- 删除歌单确认弹窗 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDeleteConfirmSheet(
    playlist: UserLibrary.UserPlaylist,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    MeloraBottomSheet(
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
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MeloraAppearance.tintRed),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "删除歌单",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
            )
            Text(
                text = "确定要删除歌单「${playlist.name}」吗？\n包含 ${playlist.songs.size} 首歌曲，此操作不可撤销。",
                fontSize = 13.sp,
                color = TextSub,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MeloraAppearance.softFill,
                    border = MeloraAppearance.chipBorder,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable(onClick = onDismiss),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("取消", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextSub)
                    }
                }
                Surface(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(22.dp),
                    color = AccentRed,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("确认删除", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}

// ---------- 自建歌单子页 ----------

@Composable
private fun UserPlaylistsPage(
    playlists: List<UserLibrary.UserPlaylist>,
    onBack: () -> Unit,
    onOpen: (UserLibrary.UserPlaylist) -> Unit,
    onCreate: () -> Unit,
    onRename: (UserLibrary.UserPlaylist) -> Unit,
    onDelete: (UserLibrary.UserPlaylist) -> Unit,
) {
    val context = LocalContext.current
    var moreTarget by remember { mutableStateOf<UserLibrary.UserPlaylist?>(null) }
    val listState = rememberLazyListState()
    val scrollToTop = rememberFastScrollToTop(listState)

    SubPageScaffold(
        title = "自建歌单",
        onBack = onBack,
        onTitleClick = scrollToTop,
        expectedTopBarHeight = 64.dp,
        actions = {
            IconButton(onClick = onCreate) {
                Icon(Icons.Outlined.Add, contentDescription = "新建歌单", tint = BrandBlue)
            }
        },
    ) {
        if (playlists.isEmpty()) {
            EmptyState(
                "还没有自建歌单\n点右上角 + 新建",
                Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = chromeContentPadding(PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)),
            ) {
                itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
                    val first = playlist.songs.firstOrNull()
                    val cover = rememberOnlineSongCover(first, enabled = true)
                    Surface(
                        onClick = { onOpen(playlist) },
                        shape = RoundedCornerShape(18.dp),
                        color = CardWhite,
                        shadowElevation = 0.dp,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MeloraAppearance.tintBlue),
                                contentAlignment = Alignment.Center,
                            ) {
                                val img = cover ?: first?.img
                                if (!img.isNullOrBlank() && img.startsWith("http")) {
                                    SongArtwork(img, playlist.id, Modifier.fillMaxSize(), cornerRadius = 10)
                                } else {
                                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(21.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextMain)
                                Text("${playlist.songs.size} 首歌曲", fontSize = 11.sp, color = TextSub, modifier = Modifier.padding(top = 2.dp))
                            }
                            IconButton(onClick = { moreTarget = playlist }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多选项", tint = TextMuted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    moreTarget?.let { target ->
        PlaylistMoreSheet(
            playlist = target,
            onDismiss = { moreTarget = null },
            onPlayAll = {
                if (target.songs.isNotEmpty()) {
                    PlaybackController.playQueue(context, target.songs.toUiTracks(), 0)
                }
            },
            onAddToQueue = { PlaybackController.addToQueue(context, target.songs.toUiTracks()) },
            onRename = { onRename(target) },
            onDelete = { onDelete(target) },
        )
    }
}

@Composable
private fun PlayAllPill(onClick: () -> Unit) {
    ChromeActionSurface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp))
            Text("播放全部", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BrandBlue)
        }
    }
}

// ---------- 自建歌单详情 ----------

@Composable
private fun UserPlaylistDetail(
    playlist: UserLibrary.UserPlaylist,
    onBack: () -> Unit,
    onRename: (UserLibrary.UserPlaylist) -> Unit,
    onDelete: (UserLibrary.UserPlaylist) -> Unit,
) {
    val context = LocalContext.current
    val playlists by UserLibrary.playlists.collectAsStateWithLifecycle()
    val current = playlists.firstOrNull { it.id == playlist.id } ?: playlist
    val songs = current.songs
    val listState = key(current.id) { rememberLazyListState() }
    val scrollToTop = rememberFastScrollToTop(listState)
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 系统返回手势先退出二级歌单页，而不是直接退出应用
    BackHandler(onBack = onBack)

    ChromeScaffold(
        expectedTopBarHeight = 64.dp,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(chromeHeaderColor())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = TextMain)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .titleScrollToTop(scrollToTop),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        current.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (songs.isNotEmpty()) {
                    ChromeActionSurface(
                        onClick = {
                            PlaybackController.playQueue(context, songs.toUiTracks(), 0)
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = BrandBlue,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "播放全部",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandBlue,
                            )
                        }
                    }
                }
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "歌单选项", tint = TextMain, modifier = Modifier.size(20.dp))
                }
            }
        },
    ) {
        if (songs.isEmpty()) {
            EmptyState(
                "歌单还是空的\n在歌曲更多菜单中添加到歌单",
                Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
            ) {
                itemsIndexed(songs, key = { index, song -> "${song.uid}:$index" }) { index, song ->
                    OnlineSongRow(
                        song = song,
                        showAlbum = true,
                        onMore = { moreSong = song },
                        onClick = { PlaybackController.playTrack(context, UiTrack.fromOnline(song)) },
                    )
                }
            }
        }
    }

    if (showMoreMenu) {
        PlaylistMoreSheet(
            playlist = current,
            onDismiss = { showMoreMenu = false },
            onPlayAll = {
                if (songs.isNotEmpty()) {
                    PlaybackController.playQueue(context, songs.toUiTracks(), 0)
                }
            },
            onAddToQueue = { PlaybackController.addToQueue(context, songs.toUiTracks()) },
            onRename = { onRename(current) },
            onDelete = {
                onDelete(current)
            },
        )
    }

    moreSong?.let { song ->
        SongMoreSheet(
            song,
             onDismiss = { moreSong = null },
            onRemoveFromPlaylist = { UserLibrary.removeFromPlaylist(current.id, song.uid) },
        )
    }
}
