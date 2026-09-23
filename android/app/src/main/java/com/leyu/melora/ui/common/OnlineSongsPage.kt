package com.leyu.melora.ui.common

import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong

private const val SONGS_PAGE_TTL = 15 * 60 * 1000L

/**
 * 固定推荐歌曲页（每日推荐/猜你喜欢/新歌推荐）：
 * - 秒开：先取缓存渲染，过期后台静默刷新；过滤数据缺失才显示骨架
 * - 推荐域返回完整列表，不以满30首推断分页，不追加榜单数据
 * - 播放全部/点歌均带 queueId，卡片播放按钮据此跟随播放状态
 */
@Composable
fun OnlineSongsPage(
    title: String,
    subtitle: String,
    queueId: String,
    cacheKey: String,
    onBack: () -> Unit,
    container: UserLibrary.PlayContainer? = null,
    fetchSongs: suspend () -> List<OnlineSong>,
) {
    val context = LocalContext.current
    val listState = key(cacheKey) { rememberLazyListState() }
    val scrollToTop = rememberFastScrollToTop(listState)
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
    // 系统返回键先退出本页回到列表，而不是退出应用
    BackHandler(onBack = onBack)

    fun markContainer(list: List<OnlineSong>) {
        val base = container ?: return
        if (base.img == null) {
            val first = list.firstOrNull()
            val cover = first?.let { CoverLoader.cachedUrl(it) } ?: first?.img
            UserLibrary.markContainerPlayed(if (cover == null) base else base.copy(img = cover))
        } else {
            UserLibrary.markContainerPlayed(base)
        }
    }

    var songs by remember(cacheKey) {
        mutableStateOf(OnlineCache.peek<List<OnlineSong>>(cacheKey).orEmpty())
    }
    var loading by remember(cacheKey) { mutableStateOf(songs.isEmpty()) }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    var retryKey by remember(cacheKey) { mutableIntStateOf(0) }
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }

    LaunchedEffect(cacheKey, retryKey) {
        val fresh = OnlineCache.get<List<OnlineSong>>(cacheKey, SONGS_PAGE_TTL)
        if (fresh != null && retryKey == 0) {
            songs = fresh
            loading = false
            error = null
            return@LaunchedEffect
        }
        if (songs.isEmpty()) loading = true
        error = null
        runCatchingCancellable { fetchSongs() }
            .onSuccess { result ->
                songs = result
                if (result.isNotEmpty()) OnlineCache.put(cacheKey, result)
            }
            .onFailure { if (songs.isEmpty()) error = it.message ?: "加载失败" }
        loading = false
    }

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
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = TextMain,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .titleScrollToTop(scrollToTop),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(subtitle, fontSize = 11.sp, color = TextMuted, maxLines = 1)
                }
                if (songs.isNotEmpty()) {
                    ChromeActionSurface(
                        onClick = {
                            markContainer(songs)
                            PlaybackController.playQueue(context, songs.toUiTracks(), 0, queueId)
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 8.dp),
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
            }
        },
    ) {
        when {
            loading -> SkeletonSongList(
                modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
            )
            error != null -> ErrorState(
                error!!,
                onRetry = { retryKey++ },
                modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
            )
            songs.isEmpty() -> EmptyState(
                "暂无歌曲",
                Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
            )
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                ) {
                    itemsIndexed(songs, key = { _, song -> song.uid }) { _, song ->
                        OnlineSongRow(
                            song = song,
                            showAlbum = true,
                            isFavorite = song.uid in favoriteUids,
                            onMore = { moreSong = song },
                            onClick = {
                                markContainer(songs)
                                PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                            },
                        )
                    }
                }
            }
        }
    }

    moreSong?.let { song -> SongMoreSheet(song) { moreSong = null } }
}
