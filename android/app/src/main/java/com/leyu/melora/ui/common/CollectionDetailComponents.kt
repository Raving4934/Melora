package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.bookQueueId
import com.leyu.melora.playback.canonicalBookId
import com.leyu.melora.playback.sdk.AlbumProfile
import com.leyu.melora.playback.sdk.ArtistProfile
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 歌单、专辑和艺术家共用信息区；头像没有真实结果时用人物占位，不冒用歌曲封面。 */
@Composable
internal fun CollectionInfoHeader(
    image: String?,
    seed: String,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier,
    artist: Boolean = false,
    primaryMaxLines: Int = 1,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artist) {
            var failed by remember(image) { mutableStateOf(false) }
            Box(Modifier.size(64.dp).clip(CircleShape).background(MeloraAppearance.softFill), contentAlignment = Alignment.Center) {
                if (image.isNullOrBlank() || failed) {
                    Icon(Icons.Outlined.Person, null, tint = MeloraAppearance.textMuted, modifier = Modifier.size(32.dp))
                } else {
                    AsyncImage(
                        model = image, contentDescription = null, contentScale = ContentScale.Crop,
                        onError = { failed = true }, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else SongArtwork(image, seed, Modifier.size(64.dp), cornerRadius = 12)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(primary, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                color = MeloraAppearance.textMain, minLines = primaryMaxLines, maxLines = primaryMaxLines,
                overflow = TextOverflow.Ellipsis)
            Text(secondary.ifBlank { " " }, fontSize = 12.sp, lineHeight = 16.sp, color = MeloraAppearance.textSub,
                minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 详情页的队列身份必须与卡片/playBook 一致，否则无法识别“本集合正在播放”。 */
internal fun collectionQueueId(source: String, id: String, isBook: Boolean): String =
    canonicalBookId(source, id)?.takeIf { isBook }?.let(::bookQueueId) ?: "playlist.$source.$id"

internal enum class CollectionPlayIntent { PlayAll, Resume, Toggle }

internal data class CollectionPlayButton(val label: String, val pause: Boolean, val intent: CollectionPlayIntent)

/** 详情页主按钮与卡片播放钮同义：本队列已在播放器中只继续/暂停，不从头重建队列。 */
internal fun collectionPlayButton(
    active: Boolean,
    playing: Boolean,
    canResume: Boolean,
    resumeLabel: String,
): CollectionPlayButton = when {
    active && playing -> CollectionPlayButton("暂停播放", pause = true, CollectionPlayIntent.Toggle)
    active -> CollectionPlayButton(resumeLabel, pause = false, CollectionPlayIntent.Toggle)
    canResume -> CollectionPlayButton(resumeLabel, pause = false, CollectionPlayIntent.Resume)
    else -> CollectionPlayButton("播放全部", pause = false, CollectionPlayIntent.PlayAll)
}

/** 只订阅队列身份与播放意图，避免进度刷新重组整页。返回 (本队列活动, 本队列播放中)。 */
@Composable
internal fun rememberQueuePlayback(queueId: String): Pair<Boolean, Boolean> {
    fun project(state: PlayerUiState) = (state.queueId == queueId) to (state.queueId == queueId && state.playing)
    val initial = remember(queueId) { project(PlaybackController.state.value) }
    val flow = remember(queueId) { PlaybackController.state.map(::project).distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = initial).value
}

/** 只负责统一布局；收藏对象、队列与分页由调用页持有，不另建数据链路。 */
@Composable
internal fun CollectionActionsRow(
    canPlay: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: (() -> Unit)?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    playLabel: String = "播放全部",
    playPause: Boolean = false,
    middleActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val playColor = if (canPlay) MeloraAppearance.brand else MeloraAppearance.textMuted
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 11.5.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChromeActionSurface(onClick = onPlay, enabled = canPlay && !loading, shape = RoundedCornerShape(17.dp), modifier = Modifier.height(34.dp)) {
            Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (loading) ShimmerBox(Modifier.size(16.dp), cornerRadius = 4.dp)
                else Icon(if (playPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                    tint = playColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                if (loading) ShimmerTextLine(playLabel, fontSize = 12.5.sp,
                    modifier = Modifier.width(IntrinsicSize.Max), fontWeight = FontWeight.SemiBold)
                else Text(playLabel, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = playColor)
            }
        }
        if (middleActions != null) middleActions() else Spacer(Modifier.weight(1f))
        ChromeActionSurface(
            onClick = { onFavorite?.invoke() }, enabled = onFavorite != null && !loading,
            shape = RoundedCornerShape(17.dp), modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (loading) ShimmerBox(Modifier.size(18.dp), cornerRadius = 9.dp)
                else Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                    if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) MeloraAppearance.accent else MeloraAppearance.textSub,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (loading) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                ShimmerBox(Modifier.size(18.dp), cornerRadius = 5.dp)
            }
        } else SongSelectionButton(onClick = onSelect, enabled = canPlay)
    }
}

internal data class CollectionHeaderCopy(
    val primary: String,
    val secondary: String,
    val primaryMaxLines: Int = 2,
)

internal fun albumHeaderCopy(
    profile: AlbumProfile?,
    fallbackArtist: String,
    songs: List<OnlineSong>,
): CollectionHeaderCopy {
    val artist = profile?.artist?.takeIf(String::isNotBlank) ?: fallbackArtist.takeIf(String::isNotBlank).orEmpty()
    val primary = profile?.description?.takeIf(String::isNotBlank)
        ?: artist.takeIf(String::isNotBlank)
        ?: "暂无专辑简介"
    val facts = listOfNotNull(
        profile?.releaseDate?.takeIf(String::isNotBlank)?.let { "发行 $it" },
        profile?.type?.takeIf(String::isNotBlank)?.let(::localizedAlbumType),
        profile?.trackCount?.takeIf { it > 0 }?.let { "$it 首" },
        profile?.company?.takeIf(String::isNotBlank),
    )
    val tracks = songs.asSequence().map(OnlineSong::name).filter(String::isNotBlank).distinct().take(3).toList()
    val fallback = if (tracks.isNotEmpty()) tracks.joinToString("、", prefix = "收录：") else ""
    return CollectionHeaderCopy(primary, facts.joinToString(" · ").ifBlank { fallback })
}

internal fun artistHeaderCopy(profile: ArtistProfile?, songs: List<OnlineSong>): CollectionHeaderCopy {
    val works = songs.asSequence().map(OnlineSong::name).filter(String::isNotBlank).distinct().take(3).toList()
    val aliases = profile?.aliases.orEmpty().take(3)
    val primary = when {
        aliases.isNotEmpty() -> aliases.joinToString("、", prefix = "别名：")
        works.isNotEmpty() -> works.joinToString("、", prefix = "代表作：")
        else -> "暂无公开简介"
    }
    val counts = listOfNotNull(
        profile?.songCount?.takeIf { it > 0 }?.let { "$it 首作品" },
        profile?.albumCount?.takeIf { it > 0 }?.let { "$it 张专辑" },
    ).joinToString(" · ")
    val secondary = counts.ifBlank {
        if (aliases.isNotEmpty() && works.isNotEmpty()) works.joinToString("、", prefix = "代表作：") else ""
    }
    return CollectionHeaderCopy(primary, secondary)
}

internal fun bookHeaderCopy(intro: String, author: String, heat: String): CollectionHeaderCopy =
    CollectionHeaderCopy(
        primary = intro.takeIf(String::isNotBlank) ?: author.takeIf(String::isNotBlank) ?: "暂无节目简介",
        secondary = "热度 ${heat.takeIf(String::isNotBlank) ?: "—"}",
    )

private fun localizedAlbumType(type: String): String = when (type.lowercase()) {
    "single", "单曲" -> "单曲"
    "ep" -> "EP"
    "compilation" -> "合辑"
    else -> type
}
