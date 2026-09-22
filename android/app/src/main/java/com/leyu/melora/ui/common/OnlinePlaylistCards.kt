package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.ui.theme.MeloraAppearance

// 歌单/有声专辑点击播放：优先缓存秒播，未缓存则拉第一页再播（带 queueId 供卡片按钮跟随状态）
fun playOnlinePlaylist(context: android.content.Context, playlist: OnlinePlaylist) {
    if (playlist.isBookAlbum) {
        PlaybackController.playBook(context, playlist)
        return
    }
    val queueId = "playlist.${playlist.source}.${playlist.id}"
    val cacheKey = "playlistSongs.${playlist.source}.${playlist.id}"
    UserLibrary.markContainerPlayed(
        UserLibrary.PlayContainer(
            kind = "playlist",
            id = playlist.id,
            name = playlist.name,
            img = playlist.img,
            source = playlist.source,
            queueId = queueId,
        ),
    )
    PlaybackController.requestQueue(context, queueId, cacheKey, songs = { it }) {
        OnlineRepository.playlistSongs(context, playlist.source, playlist.id, 1).list
    }
}

/**
 * 歌单/听书网格自适应列数：
 * 手机竖屏 2 列；横屏与平板大屏自适应 4 列（宽屏平板可到 5-6 列）。
 * 基于 LocalWindowInfo 同帧直接计算，不引入二次测量重排或闪烁抖动。
 */
@Composable
fun responsiveGridColumns(minCardWidth: androidx.compose.ui.unit.Dp = 180.dp): Int {
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val screenWidth = with(LocalDensity.current) { containerWidth.toDp() }
    return (screenWidth / minCardWidth).toInt().coerceIn(2, 6)
}

/** 歌单/专辑双列网格卡（全幅封面版）：封面铺满整卡，标题/作者叠在底部渐变上。 */
@Composable
fun OnlinePlaylistCard(
    playlist: OnlinePlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val queueId = "playlist.${playlist.source}.${playlist.id}"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = CardWhite,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            SongArtwork(playlist.img, "${playlist.source}_${playlist.id}", Modifier.fillMaxSize(), cornerRadius = 18)

            // 底部渐变遮罩：保证白色文字在任意封面上可读
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.38f),
                                Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )

            if (playlist.playCountLabel.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Headphones,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = playlist.playCountLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }

            CardPlayButton(
                queueId = queueId,
                onPlay = { playOnlinePlaylist(context, playlist) },
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomEnd),
                size = 28.dp,
                iconSize = 18.dp,
            )

            // 标题/作者叠在封面底部，右侧留出直放按钮空间
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 42.dp, bottom = 10.dp),
            ) {
                Text(
                    text = playlist.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                )

                Spacer(Modifier.height(3.dp))

                Text(
                    text = listOfNotNull(
                        playlist.author.takeIf { it.isNotBlank() },
                        playlist.total.takeIf { it > 0 }?.let { "$it 首" },
                    ).joinToString(" · ").ifBlank { "乐屿精选" },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 有声专辑网格卡（全幅封面版）：封面铺满整卡，「有声」角标+热度+标题/集数叠在封面上。 */
@Composable
fun OnlineAudiobookCard(
    playlist: OnlinePlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val queueId = "playlist.${playlist.source}.${playlist.id}"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = CardWhite,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            SongArtwork(playlist.img, "${playlist.source}_${playlist.id}", Modifier.fillMaxSize(), cornerRadius = 18)

            // 底部渐变遮罩：保证白色文字在任意封面上可读
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.38f),
                                Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )

            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, bottomEnd = 8.dp),
                color = Color(0xCC7C3AED),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    text = "有声",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            if (playlist.playCountLabel.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Whatshot,
                            contentDescription = null,
                            tint = Color(0xFFFF6D00),
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = playlist.playCountLabel,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }

            CardPlayButton(
                queueId = queueId,
                onPlay = { playOnlinePlaylist(context, playlist) },
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomEnd),
                size = 28.dp,
                iconSize = 18.dp,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 42.dp, bottom = 10.dp),
            ) {
                Text(
                    text = playlist.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                )

                Spacer(Modifier.height(3.dp))

                Text(
                    text = listOfNotNull(
                        playlist.author.takeIf { it.isNotBlank() },
                        playlist.total.takeIf { it > 0 }?.let { "$it 集" },
                    ).joinToString(" · ").ifBlank { "乐屿精选有声" },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
