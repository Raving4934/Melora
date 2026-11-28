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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.theme.MeloraAppearance

internal fun OnlinePlaylist.markCollectionPlayed(queueId: String) =
    recordContainerPlayback(
        UserLibrary.PlayContainer(
            kind = if (isBookAlbum) "book" else "playlist",
            id = id,
            name = name,
            img = img,
            source = source,
            queueId = queueId,
        ),
    )

// 歌单/有声专辑点击播放：优先使用缓存的首个可播页，queueId 供卡片按钮跟随状态。
fun playOnlinePlaylist(context: android.content.Context, playlist: OnlinePlaylist) {
    if (playlist.isBookAlbum) {
        PlaybackController.playBook(context, playlist)
        return
    }
    val queueId = collectionQueueId(playlist.source, playlist.id, playlist.isBookAlbum)
    playlist.markCollectionPlayed(queueId)
    PlaybackController.requestQueue(context, queueId, OnlineRepository.playlistEntryPageKey(playlist), SongPage::list) {
        OnlineRepository.playlistEntryPage(context, playlist)
    }
}

/** 使用网格实际可用宽度（已扣除外部边距），列间距与卡片共同占宽；窄屏保留双列。 */
fun responsiveGridColumns(
    availableWidth: Dp,
    minCardWidth: Dp = 180.dp,
    horizontalSpacing: Dp = 12.dp,
): Int = ((availableWidth + horizontalSpacing) / (minCardWidth + horizontalSpacing)).toInt().coerceAtLeast(2)

/** 歌单/专辑双列网格卡（全幅封面版）：封面铺满整卡，标题/作者叠在底部渐变上。 */
@Composable
fun OnlinePlaylistCard(
    playlist: OnlinePlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isBook = playlist.isBookAlbum
    val queueId = collectionQueueId(playlist.source, playlist.id, isBook)
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
            SongArtwork(playlist.img, Modifier.fillMaxSize(), cornerRadius = 18)

            // 底部渐变遮罩：保证白色文字在任意封面上可读
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.68f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.42f to Color.Black.copy(alpha = 0.42f),
                            0.76f to Color.Black.copy(alpha = 0.76f),
                            1f to Color.Black.copy(alpha = 0.88f),
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
                            if (isBook) Icons.Outlined.Whatshot else Icons.Outlined.Headphones,
                            contentDescription = null,
                            tint = if (isBook) Color(0xFFFF6D00) else Color.White,
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
                        playlist.total.takeIf { it > 0 }?.let { if (isBook) "$it 集" else "$it 首" },
                    ).joinToString(" · ").ifBlank { if (isBook) "乐屿精选有声" else "乐屿精选" },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
