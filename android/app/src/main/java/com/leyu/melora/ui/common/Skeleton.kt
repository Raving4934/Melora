package com.leyu.melora.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.ui.theme.MeloraAppearance

// 骨架屏微光块：浅灰底 + 无方向的柔和呼吸，避免定向扫光的"张开"错觉
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    baseColor: Color? = null,
    highlightColor: Color? = null,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val base = baseColor ?: MeloraAppearance.skeleton
    val highlight = highlightColor ?: lerp(base, MeloraAppearance.card, 0.55f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(lerp(base, highlight, pulse)),
    ) {}
}

/** 以真实字体度量占位，仅将可见部分绘制为微光条，兼容系统字体缩放。 */
@Composable
fun ShimmerTextLine(
    placeholder: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    shimmerFraction: Float = 1f,
    shimmerHeight: Dp = 12.dp,
    cornerRadius: Dp = 6.dp,
) {
    Box(modifier = modifier.clearAndSetSemantics {}) {
        Text(
            text = placeholder,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = Color.Transparent,
            maxLines = 1,
        )
        ShimmerBox(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(shimmerFraction)
                .height(shimmerHeight),
            cornerRadius = cornerRadius,
        )
    }
}

/** 骨架↔内容切换：统一短淡入淡出，消除硬切造成的视觉跳动。 */
@Composable
fun SkeletonCrossfade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Crossfade(
        targetState = visible,
        animationSpec = tween(durationMillis = 220),
        label = "skeletonSwap",
        modifier = modifier,
    ) { showSkeleton ->
        if (showSkeleton) skeleton() else content()
    }
}

/**
 * 歌曲列表骨架：与 OnlineSongRow 同构。
 * 封面 42dp、文字两行、右侧更多按钮的触控位都保留，避免加载完布局跳动。
 */
@Composable
fun SkeletonSongList(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    showMore: Boolean = true,
    rowSpacing: Dp = 0.dp,
    showDividers: Boolean = false,
) {
    val showCovers by LocalSongListState.current.showCovers
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        repeat(rows) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 10.5.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCovers) {
                    ShimmerBox(Modifier.size(42.dp), cornerRadius = 10.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                ) {
                    ShimmerTextLine(
                        placeholder = "歌曲标题占位",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        shimmerFraction = 0.52f,
                        shimmerHeight = 14.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ShimmerTextLine(
                        placeholder = "歌手名称占位",
                        fontSize = 12.sp,
                        shimmerFraction = 0.30f,
                        shimmerHeight = 11.dp,
                        cornerRadius = 5.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                }
                if (showMore) {
                    // 与竖排「更多」图标同形：细长竖条占位，避免加载完跳变
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        ShimmerBox(
                            Modifier.size(width = 5.dp, height = 20.dp),
                            cornerRadius = 2.5.dp,
                        )
                    }
                }
            }
            if (showDividers && index < rows - 1) {
                HorizontalDivider(
                    color = DividerSoft,
                    thickness = 0.6.dp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/** 网格骨架：自适应列数，镜像 PlaylistGridCard / AudiobookGridCard。 */
@Composable
fun SkeletonGrid(
    modifier: Modifier = Modifier,
    columns: Int = 2,
    cards: Int = columns * 2,
    spacing: Dp = 12.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        val rows = (cards + columns - 1) / columns
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                repeat(columns) { SkeletonGridCard(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SkeletonGridCard(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardWhite,
        modifier = modifier.fillMaxWidth(),
    ) {
        // 全幅封面卡骨架：整卡一张方形微光，与内容同构
        ShimmerBox(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 18.dp,
        )
    }
}

/** 榜单预览行骨架：与 BoardPeekCard 里 序号+歌名+歌手 的三行完全同构。 */
@Composable
fun SkeletonPreviewRows(modifier: Modifier = Modifier, rows: Int = 3) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        repeat(rows) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerTextLine(
                    placeholder = "0",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    shimmerFraction = 2f / 3f,
                    shimmerHeight = 11.dp,
                    cornerRadius = 4.dp,
                    modifier = Modifier.width(18.dp),
                )
                ShimmerBox(Modifier.fillMaxWidth(0.46f).height(15.dp), cornerRadius = 6.dp)
                Spacer(Modifier.width(4.dp))
                ShimmerBox(Modifier.fillMaxWidth(0.22f).height(13.dp), cornerRadius = 5.dp)
                Spacer(Modifier.weight(1f))
            }
            if (index < rows - 1) {
                HorizontalDivider(
                    color = DividerSoft,
                    thickness = 0.6.dp,
                    modifier = Modifier.padding(start = 18.dp),
                )
            }
        }
    }
}

/** 排行榜主页骨架：镜像官方榜标题、三张无标题主榜卡与三列垂类方卡。 */
@Composable
fun SkeletonLeaderboard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerBox(Modifier.size(18.dp), cornerRadius = 6.dp)
            Spacer(Modifier.width(6.dp))
            ShimmerTextLine(
                placeholder = "平台官方榜",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                shimmerHeight = 17.dp,
                modifier = Modifier.width(96.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        repeat(3) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CardWhite,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .height(108.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(128.dp)
                            .height(108.dp),
                    ) {
                        ShimmerBox(
                            modifier = Modifier
                                .size(80.dp)
                                .align(Alignment.CenterEnd),
                            cornerRadius = 40.dp,
                        )
                        ShimmerBox(
                            modifier = Modifier
                                .size(88.dp)
                                .align(Alignment.CenterStart),
                            cornerRadius = 16.dp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SkeletonPreviewRows(modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShimmerBox(Modifier.size(18.dp), cornerRadius = 6.dp)
            Spacer(Modifier.width(6.dp))
            ShimmerTextLine(
                placeholder = "更多垂类榜单",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                shimmerHeight = 17.dp,
                modifier = Modifier.width(110.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { SkeletonGenreCard(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SkeletonGenreCard(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardWhite,
        modifier = modifier.fillMaxWidth(),
    ) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            cornerRadius = 16.dp,
        )
    }
}

