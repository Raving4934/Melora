package com.leyu.melora.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.ui.common.SongArtwork

// 沟槽与环境反光固定，中心标签随唱片转动，避免整层亮斑像贴纸一样旋转。
@Composable
fun VinylDisc(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(CircleShape)) {
        val radius = size.minDimension / 2f
        if (radius <= 0f) return@Canvas
        drawCircle(Brush.radialGradient(
            0f to Color(0xFF191B20), 0.52f to Color(0xFF111216),
            0.88f to Color(0xFF24262B), 1f to Color(0xFF101114),
            center = center, radius = radius,
        ), radius)
        for (step in 27..47) {
            drawCircle(
                Color.White.copy(alpha = if (step % 3 == 0) 0.09f else 0.045f),
                radius * step / 50f,
                style = Stroke(width = 0.6.dp.toPx()),
            )
        }
        drawCircle(Brush.sweepGradient(
            0f to Color.Transparent, 0.12f to Color.White.copy(alpha = 0.12f),
            0.24f to Color.Transparent, 0.50f to Color.Transparent,
            0.64f to Color.White.copy(alpha = 0.10f), 0.76f to Color.Transparent,
            1f to Color.Transparent,
        ), radius * 0.98f)
        drawCircle(Color.White.copy(alpha = 0.10f), radius * 0.98f, style = Stroke(0.8.dp.toPx()))
        drawCircle(Color(0xFF111216), radius * 0.52f)
        drawCircle(Color(0xFF33363C), radius * 0.50f)
        drawCircle(Color(0xFF0C0D10), radius * 0.04f)
    }
}

private const val VinylRevolutionMillis = 24_000

internal fun vinylRotationRemainingMillis(angle: Float): Int =
    ((360f - angle.coerceIn(0f, 360f)) / 360f * VinylRevolutionMillis).roundToInt().coerceAtLeast(1)

/** 一个时钟供迷你封面、全屏封面和共享过渡层使用；暂停/后台保留角度，不做补转。 */
@Composable
internal fun rememberVinylRotation(playing: Boolean, style: PlayerCoverStyle): () -> Float {
    val angle = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, style, lifecycle) {
        if (!playing || style != PlayerCoverStyle.Vinyl) return@LaunchedEffect
        val motionScale = currentCoroutineContext()[MotionDurationScale]
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 系统关闭动画时静止，避免零时长无限动画忙循环；重新开启动画可恢复。
            snapshotFlow { motionScale?.scaleFactor ?: 1f }.collectLatest { scale ->
                if (scale <= 0f) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    angle.animateTo(360f, tween(vinylRotationRemainingMillis(angle.value), easing = LinearEasing))
                    angle.snapTo(0f)
                }
            }
        }
    }
    return remember(angle) { { angle.value } }
}

/**
 * 默认方形与圆形裁切模式下的专属通用音乐封面占位。
 * 极简克制高级感：深邃哑光中性渐变 + 柔和微透亮内描边 + 居中半透明优雅音符，彻底抛弃突兀的蓝色彩圈。
 */
@Composable
fun ModernMusicCoverPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF242730), Color(0xFF14161B)),
                ),
            )
            .border(0.8.dp, Color.White.copy(alpha = 0.06f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.40f),
            modifier = Modifier.fillMaxSize(0.28f),
        )
    }
}

@Composable
fun ArtworkPlaceholder(
    seed: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 14,
    style: PlayerCoverStyle = PlayerCoverStyle.Default,
) {
    when (style) {
        PlayerCoverStyle.Vinyl -> {
            Box(
                modifier = modifier
                    .clip(if (cornerRadius >= 500) CircleShape else RoundedCornerShape(cornerRadius.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E2128), Color(0xFF14161A)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                VinylDisc(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f),
                )
            }
        }
        PlayerCoverStyle.Circle -> {
            ModernMusicCoverPlaceholder(
                modifier = modifier,
                shape = CircleShape,
            )
        }
        PlayerCoverStyle.Default -> {
            ModernMusicCoverPlaceholder(
                modifier = modifier,
                shape = if (cornerRadius >= 500) CircleShape else RoundedCornerShape(cornerRadius.dp),
            )
        }
    }
}

private const val CircleArtworkCornerRadius = 1_000

internal enum class NowPlayingArtworkShape {
    Rounded,
    Circle,
}

internal fun nowPlayingArtworkShape(style: PlayerCoverStyle): NowPlayingArtworkShape = when (style) {
    PlayerCoverStyle.Default -> NowPlayingArtworkShape.Rounded
    PlayerCoverStyle.Circle,
    PlayerCoverStyle.Vinyl -> NowPlayingArtworkShape.Circle
}

/**
 * Player artwork renderer shared by the mini bar, full cover page and the shared morph layer.
 * Vinyl reuses the same disc background and keeps the same circular clip at both endpoints.
 */
@Composable
internal fun NowPlayingArtwork(
    url: String?,
    seed: String,
    style: PlayerCoverStyle,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 18,
    retryOnError: Boolean = false,
    smoothChanges: Boolean = false,
    rotationDegrees: () -> Float = { 0f },
) {
    val isCircular = nowPlayingArtworkShape(style) == NowPlayingArtworkShape.Circle
    val artworkModifier = if (style == PlayerCoverStyle.Vinyl) {
        Modifier
            .fillMaxWidth(0.50f)
            .aspectRatio(1f)
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = modifier.then(if (isCircular) Modifier.clip(CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (style == PlayerCoverStyle.Vinyl) {
            VinylDisc(modifier = Modifier.fillMaxSize())
        }
        val rotatingArtwork = if (style == PlayerCoverStyle.Vinyl) {
            artworkModifier.graphicsLayer { rotationZ = rotationDegrees() }
        } else artworkModifier
        val radius = if (isCircular) CircleArtworkCornerRadius else cornerRadius
        if (smoothChanges && url?.startsWith("http") == true) {
            SmoothPlayerArtwork(url, seed, rotatingArtwork, radius, retryOnError, style = style)
        } else {
            SongArtwork(url, seed, rotatingArtwork, radius, retryOnError, style = style)
        }
        if (style == PlayerCoverStyle.Vinyl) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                drawCircle(Color.Black.copy(alpha = 0.35f), radius * 0.052f)
                drawCircle(Color(0xFF111216), radius * 0.035f)
                drawCircle(Color.White.copy(alpha = 0.28f), radius * 0.035f, style = Stroke(0.6.dp.toPx()))
            }
        }
    }
}

/** 新图完全不透明之前都保留实心底图，避免Crossfade两层同时半透明而透出彩色背景。 */
internal fun artworkNeedsOpaqueBase(incomingReady: Boolean, incomingAlpha: Float): Boolean =
    !incomingReady || incomingAlpha < 1f

@Composable
private fun SmoothPlayerArtwork(
    url: String,
    seed: String,
    modifier: Modifier,
    cornerRadius: Int,
    retryOnError: Boolean,
    style: PlayerCoverStyle = PlayerCoverStyle.Default,
) {
    var settledPainter by remember { mutableStateOf<Painter?>(null) }
    var incomingPainter by remember(url) { mutableStateOf<Painter?>(null) }
    val incomingAlpha = remember(url) { Animatable(0f) }
    val latestUrl by rememberUpdatedState(url)

    LaunchedEffect(url, incomingPainter) {
        val incoming = incomingPainter ?: return@LaunchedEffect
        incomingAlpha.snapTo(0f)
        incomingAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        // 换歌会取消此协程；不会在第三首之后补交接前两首。
        settledPainter = incoming
    }

    Box(modifier.clip(RoundedCornerShape(cornerRadius.dp))) {
        if (artworkNeedsOpaqueBase(incomingPainter != null, incomingAlpha.value)) {
            val previous = settledPainter
            if (previous != null) {
                Image(
                    painter = previous,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    alpha = 1f,
                )
            } else {
                ArtworkPlaceholder(seed, Modifier.fillMaxSize(), cornerRadius = cornerRadius, style = style)
            }
        }
        // 仍只有这一份Coil请求：旧图只是已解码Painter，没有第二次联网或重试链路。
        SongArtwork(
            url = url,
            seed = seed,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (incomingPainter != null) incomingAlpha.value else 0f
            },
            cornerRadius = 0,
            retryOnError = retryOnError,
            crossfade = false,
            style = style,
            onResult = { painter ->
                if (url == latestUrl) {
                    incomingPainter = painter
                    if (painter == null) settledPainter = null
                }
            },
        )
    }
}
