package com.leyu.melora.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.ui.common.CardPlayButton
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.LocalChromeTopInset
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.ErrorState
import com.leyu.melora.ui.common.LoadMoreOnScroll
import com.leyu.melora.ui.common.ShimmerBox
import com.leyu.melora.ui.common.SkeletonGrid
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.launch

internal val TextMain: Color get() = MeloraAppearance.textMain
internal val TextSub: Color get() = MeloraAppearance.textSub
internal val CardBg: Color get() = MeloraAppearance.card
internal val BrandBlue: Color get() = MeloraAppearance.brand
internal val DividerSoft: Color get() = MeloraAppearance.divider

// 发现页歌单卡骨架：与 136dp 横滑全幅卡同构（1:1 整卡微光）
@Composable
internal fun SkeletonDiscoverPlaylistCard() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardBg,
        modifier = Modifier.width(136.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            ShimmerBox(Modifier.fillMaxSize(), cornerRadius = 18.dp)
        }
    }
}

// 发现页推荐位详情页描述（每日推荐/猜你喜欢/新歌推荐 30 首）
internal data class DiscoverSongs(
    val title: String,
    val subtitle: String,
    val source: String,
    val queueId: String,
    val cacheKey: String,
)

// 百万热播：从平台推荐位里筛真实播放量 ≥ 100 万的歌单；滚动到底自动补页（最多 6 页，避免请求风暴）
@Composable
internal fun MillionPlaylistsPage(
    onBack: () -> Unit,
    onOpen: (OnlinePlaylist) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    androidx.activity.compose.BackHandler(onBack = onBack)
    var items by remember { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }

    fun load(targetPage: Int, append: Boolean) {
        if (append && (loadingMore || !hasMore || targetPage > 6)) return
        if (append) loadingMore = true else loading = true
        scope.launch {
            runCatchingCancellable {
                OnlineRepository.playlists(context, "kw", "hot", "", targetPage).list
                    .filter { it.playNum >= 1_000_000L }
            }
                .onSuccess { list ->
                    items = if (append) (items + list).distinctBy { "${it.source}_${it.id}" } else list
                    page = targetPage
                    hasMore = list.isNotEmpty() && targetPage < 6
                    error = null
                }
                .onFailure { if (!append && items.isEmpty()) error = it.message ?: "加载失败" }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(Unit) { load(1, append = false) }

    ChromeScaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MeloraAppearance.canvas,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(chromeHeaderColor())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = MeloraAppearance.textMain,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("百万热播", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextMain)
                    Text("真实播放量 ≥ 100 万的平台歌单", fontSize = 11.sp, color = TextSub)
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            val columns = com.leyu.melora.ui.common.responsiveGridColumns()
            when {
                loading -> SkeletonGrid(
                    columns = columns,
                    cards = columns * 2,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = LocalChromeTopInset.current),
                )
                error != null -> ErrorState(
                    error!!,
                    onRetry = { load(1, append = false) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = LocalChromeTopInset.current),
                )
                items.isEmpty() -> EmptyState(
                    "暂时没有百万播放量级歌单",
                    Modifier
                        .fillMaxSize()
                        .padding(top = LocalChromeTopInset.current),
                )
                else -> {
                    LoadMoreOnScroll(
                        listState = listState,
                        enabled = hasMore && page < 6,
                        loading = loadingMore,
                        onLoadMore = { load(page + 1, append = true) },
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                    ) {
                        val rows = items.chunked(columns)
                        items(rows.size) { rowIndex ->
                            val rowItems = rows[rowIndex]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                rowItems.forEach { playlist ->
                                    com.leyu.melora.ui.common.OnlinePlaylistCard(
                                        playlist = playlist,
                                        onClick = { onOpen(playlist) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (hasMore && page < 6) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (loadingMore) "正在加载…" else "上滑加载更多",
                                        fontSize = 13.sp,
                                        color = BrandBlue,
                                        modifier = Modifier.clickable(enabled = !loadingMore) {
                                            load(page + 1, append = true)
                                        },
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

// 每日推荐卡片 (对齐参考图：醒目金黄日期角标 + 封面图 + 底部暗影遮罩 + 播放小三角)
@Composable
internal fun DailyRecommendCard(
    day: String,
    month: String,
    subtitle: String,
    loading: Boolean,
    artwork: String?,
    seed: String,
    queueId: String,
    onPlay: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1E2430),
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier
            .width(138.dp)
            .height(175.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景封面底图（关闭单曲封面或未加载时不显示占位图）
            if (artwork != null) {
                SongArtwork(
                    url = artwork,
                    seed = seed,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18,
                )
            }

            // 底部暗色渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xDD111827)),
                        ),
                    ),
            )

            // 左上角醒目金黄色日历胶囊 (严格对齐参考图)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFD600), // Vibrant Golden Yellow
                modifier = Modifier
                    .padding(10.dp)
                    .align(Alignment.TopStart),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = day,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF111827),
                        lineHeight = 18.sp,
                    )
                    Text(
                        text = month,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827),
                        lineHeight = 10.sp,
                    )
                }
            }

            // 底部文字与播放钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日推荐",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    RecommendationSubtitle(subtitle, loading)
                }

                // 右下角纯白播放钮：直放该推荐队列，播放中自动切暂停
                CardPlayButton(
                    queueId = queueId,
                    onPlay = onPlay,
                    size = 24.dp,
                    iconSize = 22.dp,
                    background = Color.Transparent,
                )
            }
        }
    }
}

// 猜你喜欢卡片 (对齐参考图：封面图 + 底部暗影遮罩 + 标题与副标 + 播放小三角)
@Composable
internal fun GuessYouLikeCard(
    subtitle: String,
    loading: Boolean,
    artwork: String?,
    seed: String,
    queueId: String,
    onPlay: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1E2430),
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier
            .width(138.dp)
            .height(175.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景封面底图（关闭单曲封面或未加载时不显示占位图）
            if (artwork != null) {
                SongArtwork(
                    url = artwork,
                    seed = seed,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18,
                )
            }

            // 底部暗色渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xDD111827)),
                        ),
                    ),
            )

            // 底部文字与播放钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "猜你喜欢",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    RecommendationSubtitle(subtitle, loading)
                }

                // 右下角纯白播放钮：直放该推荐队列，播放中自动切暂停
                CardPlayButton(
                    queueId = queueId,
                    onPlay = onPlay,
                    size = 24.dp,
                    iconSize = 22.dp,
                    background = Color.Transparent,
                )
            }
        }
    }
}

// 上下堆叠小方卡 (对齐参考图：百万收藏 / 新歌推荐 / 歌单广场 / 排行榜，带大号浮水印底图)
@Composable
internal fun WatermarkGradientCard(
    title: String,
    icon: ImageVector,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            // 右下角大号半透明浮水印背景图标 (严格对齐参考图百万收藏的红心浮印与新歌推荐的音符浮印)
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.26f),
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = 12.dp),
            )

            // 左上角大标题
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp),
            )
        }
    }
}

/** 骨架/文字占用同一字体行高，先返回的推荐内容不会使标题和播放按钮跳位。 */
@Composable
private fun RecommendationSubtitle(text: String, loading: Boolean) {
    val lineHeight = with(LocalDensity.current) { 24.sp.toDp() }
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp).height(lineHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (loading) {
            ShimmerBox(
                modifier = Modifier.fillMaxWidth(0.72f).height(9.dp),
                cornerRadius = 5.dp,
                baseColor = Color.White.copy(alpha = 0.20f),
                highlightColor = Color.White.copy(alpha = 0.34f),
            )
        } else {
            Text(
                text = text, fontSize = 11.sp, lineHeight = 24.sp,
                color = Color.White.copy(alpha = 0.85f), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
