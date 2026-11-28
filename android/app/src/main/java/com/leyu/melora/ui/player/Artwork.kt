package com.leyu.melora.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.ui.common.artworkRetryDelayMs
import com.leyu.melora.ui.common.isLoadableArtworkUri

// 沟槽与环境反光固定，中心标签随唱片转动，避免整层亮斑像贴纸一样旋转。
@Composable
fun VinylDisc(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val outerRadius = size.minDimension / 2f
        if (outerRadius <= 0f) return@Canvas
        val discRadius = outerRadius * 0.96f
        // 柔和环境投影，避免黑胶在浅色/深色背景上像平面贴纸
        drawCircle(
            Brush.radialGradient(
                0.84f to Color.Black.copy(alpha = 0.34f),
                0.95f to Color.Black.copy(alpha = 0.14f),
                1f to Color.Transparent,
                center = center,
                radius = outerRadius,
            ),
            outerRadius,
        )
        // 黑胶本体
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF181A1F),
                0.55f to Color(0xFF111216),
                0.86f to Color(0xFF191B20),
                0.97f to Color(0xFF0E0F13),
                1f to Color(0xFF23252B),
                center = center,
                radius = discRadius,
            ),
            discRadius,
        )
        // 细密微沟槽：压低透明度并微调疏密，模拟真实音轨
        for (step in 46..93) {
            val alpha = when {
                step % 7 == 0 -> 0.042f
                step % 3 == 0 -> 0.028f
                else -> 0.015f
            }
            drawCircle(
                Color.White.copy(alpha = alpha),
                discRadius * step / 100f,
                style = Stroke(width = 0.45.dp.toPx()),
            )
        }
        // 只落在音轨环带上的柔和镜面反光（不盖住外缘与中心纸标）
        val grooveMidRadius = discRadius * 0.695f
        val grooveBandWidth = discRadius * 0.50f
        drawCircle(
            Brush.sweepGradient(
                0f to Color.Transparent,
                0.08f to Color.White.copy(alpha = 0.07f),
                0.18f to Color.Transparent,
                0.50f to Color.Transparent,
                0.58f to Color.White.copy(alpha = 0.055f),
                0.68f to Color.Transparent,
                1f to Color.Transparent,
            ),
            grooveMidRadius,
            style = Stroke(width = grooveBandWidth),
        )
        // 外圈微弱高光描边
        drawCircle(Color.White.copy(alpha = 0.12f), discRadius * 0.992f, style = Stroke(0.7.dp.toPx()))
        // 中心纸标卡口与内凹阴影（匹配 42% 中心封面比例）
        drawCircle(Color(0xFF0A0B0E), discRadius * 0.445f)
        drawCircle(Color.White.copy(alpha = 0.09f), discRadius * 0.432f, style = Stroke(0.8.dp.toPx()))
        drawCircle(Color(0xFF26282E), discRadius * 0.422f)
        drawCircle(Color(0xFF0C0D10), discRadius * 0.036f)
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

private const val VinylTonearmParkedAngle = -45f
private val VinylTonearmSwingSpec = tween<Float>(520, easing = FastOutSlowInEasing)

/** 封面交接时两层从同一角度绘制。收起或关闭动画后直接同步停靠位置。 */
@Composable
internal fun rememberVinylTonearmAngle(
    playing: Boolean,
    motionEnabled: Boolean,
    recordChange: VinylRecordChange? = null,
): () -> Float {
    val target = if (playing) 4f else VinylTonearmParkedAngle
    val angle = remember { Animatable(target) }
    LaunchedEffect(target, motionEnabled) {
        if (angle.value == target) return@LaunchedEffect
        if (motionEnabled) angle.animateTo(target, VinylTonearmSwingSpec) else angle.snapTo(target)
    }
    return remember(angle, recordChange) {
        {
            val playbackAngle = angle.value
            playbackAngle + (VinylTonearmParkedAngle - playbackAngle) * (recordChange?.armLift ?: 0f)
        }
    }
}

/** 封面几何、标签和歌词共享节奏，快速换目标时从当前显示值继续。 */
internal val PlayerCoverMorphSpec = tween<Float>(280, easing = FastOutSlowInEasing)

internal fun artworkRestingRotation(angle: Float): Float = -((angle + 180f) % 360f - 180f)

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
    artwork: PlayerArtwork,
    style: PlayerCoverStyle,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 18,
    rotationDegrees: () -> Float = { 0f },
    motionEnabled: Boolean = true,
    tonearmAngle: (() -> Float)? = null,
    tonearmAlpha: () -> Float = { 1f },
    frameCornerRadius: (() -> Dp)? = null,
    recordChange: VinylRecordChange? = null,
) {
    val roundness = animateFloatAsState(
        if (nowPlayingArtworkShape(style) == NowPlayingArtworkShape.Circle) 1f else 0f,
        if (motionEnabled) PlayerCoverMorphSpec else snap(), label = "coverRoundness",
    )
    val vinyl = animateFloatAsState(if (style == PlayerCoverStyle.Vinyl) 1f else 0f,
        if (motionEnabled) PlayerCoverMorphSpec else snap(), label = "coverVinyl")
    val rotationAlignment = animateFloatAsState(
        if (style == PlayerCoverStyle.Vinyl) 0f else artworkRestingRotation(rotationDegrees()),
        if (motionEnabled) PlayerCoverMorphSpec else snap(), label = "coverRotationAlignment",
    )
    val record = recordChange?.takeIf { style == PlayerCoverStyle.Vinyl }
    val discMotion = if (record == null) Modifier else Modifier.graphicsLayer {
        val scale = record.scale
        scaleX = scale
        scaleY = scale
    }
    Box(modifier) {
        val showVinyl = style == PlayerCoverStyle.Vinyl || vinyl.value > 0f
        Box(Modifier.fillMaxSize().then(discMotion), contentAlignment = Alignment.Center) {
            if (showVinyl) {
                VinylDisc(Modifier.fillMaxSize().graphicsLayer { alpha = vinyl.value })
            }
            // 封面的外轮廓裁切只作用于图片；盘面和唱臂各有自己的绘制范围。
            val frameClip = if (frameCornerRadius == null) Modifier else Modifier.graphicsLayer {
                shape = RoundedCornerShape(frameCornerRadius() * (1f - roundness.value) +
                    (size.minDimension / (2f * density)).dp * roundness.value)
                clip = true
            }
            // 同一张已加载图片只改变裁切和变换，切换封面样式时继续使用原图。
            val artworkModifier = Modifier.fillMaxSize().then(frameClip).graphicsLayer {
                val scale = 1f - vinyl.value * 0.595f
                scaleX = scale
                scaleY = scale
                rotationZ = rotationDegrees() + rotationAlignment.value
                shape = RoundedCornerShape((cornerRadius * (1f - roundness.value) +
                    size.minDimension / (2f * density) * roundness.value).dp)
                clip = true
            }
            artwork.Draw(artworkModifier, style)
            if (showVinyl) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = vinyl.value }) {
                    val radius = size.minDimension / 2f
                    drawCircle(Color.Black.copy(alpha = 0.42f), radius * 0.052f)
                    drawCircle(Color(0xFFD6D8DD).copy(alpha = 0.82f), radius * 0.036f, style = Stroke(1.1.dp.toPx()))
                    drawCircle(Color(0xFF121317), radius * 0.031f)
                }
            }
        }
        if (showVinyl && tonearmAngle != null) {
            Canvas(Modifier.matchParentSize().graphicsLayer { alpha = vinyl.value * tonearmAlpha() }) {
                drawVinylTonearm(tonearmAngle())
            }
        }
    }
}

private fun DrawScope.drawVinylTonearm(angle: Float) {
    val side = size.minDimension
    if (side <= 0f) return

    // 盘片、臂杆、描边与阴影均按同一边长缩放。停靠针尖越过唱片边缘，整条摆动仍留在封面范围内。
    val pivot = center + Offset(0.30f, -0.436f) * side
    val armRadians = angle * PI / 180.0
    val sine = sin(armRadians).toFloat()
    val cosine = cos(armRadians).toFloat()
    fun point(x: Float, y: Float) = pivot + Offset(x * cosine - y * sine, x * sine + y * cosine) * side

    val elbow = point(0.02f, 0.176f)
    val tip = point(0.006f, 0.235f)
    val shadowOffset = Offset(0.003f, 0.009f) * side
    val shadow = Color.Black.copy(alpha = 0.22f)
    drawLine(shadow, pivot + shadowOffset, elbow + shadowOffset, 0.013f * side, StrokeCap.Round)
    drawLine(shadow, elbow + shadowOffset, tip + shadowOffset, 0.017f * side, StrokeCap.Round)
    drawLine(Color(0xFFE2E4E9), pivot, elbow, 0.0105f * side, StrokeCap.Round)
    drawLine(Color(0xFF9EA4AE), pivot, elbow, 0.004f * side, StrokeCap.Round)
    drawLine(Color(0xFF2B2E35), elbow, tip, 0.020f * side, StrokeCap.Round)
    drawLine(
        color = Color(0xFFD5D8DF),
        start = (elbow + tip) / 2f,
        end = tip,
        strokeWidth = 0.006f * side,
        cap = StrokeCap.Round,
    )

    // 固定轴座
    val baseRadius = side * 0.044f
    drawCircle(Color.Black.copy(alpha = 0.24f), baseRadius * 1.12f, center = pivot)
    drawCircle(Color(0xFFD8DBE2), baseRadius, center = pivot)
    drawCircle(Color(0xFF2A2D34), baseRadius * 0.62f, center = pivot)
    drawCircle(Color(0xFFAEB4BE), baseRadius * 0.24f, center = pivot)
}

private const val PreviousTrackArtworkHoldMs = 450L

@Stable
internal class PlayerArtwork(val placeholderTint: ColorProducer) {
    internal class Layer(val image: CoilImage?) {
        val opacity = Animatable(0f)
    }

    var displayed by mutableStateOf<CoilImage?>(null)
    var incoming by mutableStateOf<Layer?>(null)
}

@Composable
internal fun rememberPlayerArtwork(
    url: String?,
    uid: String,
    sizePx: Int,
    retryOnError: Boolean,
    motionEnabled: Boolean,
    placeholderTint: ColorProducer,
    recordChange: VinylRecordChange? = null,
): PlayerArtwork {
    val context = LocalContext.current
    val tint by rememberUpdatedState(placeholderTint)
    val artwork = remember { PlayerArtwork { tint() } }
    val source = url?.takeIf(::isLoadableArtworkUri)
    val request = remember(context, source, sizePx) {
        source?.takeIf { sizePx > 0 }?.let {
            ImageRequest.Builder(context).data(it).size(sizePx).scale(Scale.FILL)
                .precision(Precision.INEXACT).build()
        }
    }
    val result = remember(source, request) {
        mutableStateOf(if (source == null) PlayerArtwork.Layer(null) else null)
    }
    var attempt by remember(request) { mutableIntStateOf(0) }
    val retryDelay = artworkRetryDelayMs(attempt)
    LaunchedEffect(request, attempt) {
        if (request == null) return@LaunchedEffect
        val loaded = SingletonImageLoader.get(context).execute(request) as? SuccessResult
        result.value = PlayerArtwork.Layer(loaded?.image)
    }
    if (retryOnError && request != null) {
        LaunchedEffect(request, uid) {
            // 重新展开后允许已耗尽预算的封面重新请求。
            if (artworkRetryDelayMs(attempt) == null && result.value?.let { it.image == null } == true) {
                result.value = null
                attempt = 0
            }
        }
        if (retryDelay != null && result.value?.let { it.image == null } == true) {
            LaunchedEffect(request, attempt) {
                delay(retryDelay)
                result.value = null
                attempt++
            }
        }
    }

    val currentUid by rememberUpdatedState(uid)
    val activeResult by rememberUpdatedState(
        result.value?.takeIf {
            (recordChange == null || recordChange.displayedArtwork == url) &&
                (it.image != null || request == null || !retryOnError || retryDelay == null)
        },
    )
    var coverUid by remember { mutableStateOf<String?>(null) }
    // 连续跳过尚未取得封面的曲目时，上一张图的等待窗口只计算一次。
    val holdingPreviousTrack = coverUid != null && coverUid != uid
    LaunchedEffect(holdingPreviousTrack) {
        if (holdingPreviousTrack) {
            delay(PreviousTrackArtworkHoldMs)
            if (coverUid == currentUid) return@LaunchedEffect
            artwork.displayed = null
            artwork.incoming = null
            coverUid = null
        }
    }

    LaunchedEffect(motionEnabled) {
        if (!motionEnabled) {
            artwork.incoming?.let { artwork.displayed = it.image }
            artwork.incoming = null
        }
        // 黑胶在抬臂期间即可解码，换碟后接管绘制。共享同一张封面的相邻曲目只更新归属。
        snapshotFlow { activeResult?.let { currentUid to it } }.filterNotNull().collect { (owner, layer) ->
            val next = layer.image
            coverUid = owner.takeIf { next != null }
            if (next === artwork.displayed) return@collect
            if (motionEnabled && next != null) {
                artwork.incoming = layer
                layer.opacity.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                if (artwork.incoming !== layer) return@collect
            }
            artwork.displayed = next
            artwork.incoming = null
        }
    }
    return artwork
}

@Composable
private fun PlayerArtwork.Draw(modifier: Modifier, style: PlayerCoverStyle) {
    val context = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        val base = displayed
        when {
            base != null -> Image(remember(base, context) { base.asPainter(context) }, null,
                Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            style == PlayerCoverStyle.Vinyl -> Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1E2128), Color(0xFF14161A)))),
                contentAlignment = Alignment.Center,
            ) {
                VinylDisc(Modifier.fillMaxWidth(0.88f).aspectRatio(1f))
            }
            else -> Icon(rememberVectorPainter(Icons.Outlined.MusicNote), placeholderTint, null, Modifier.fillMaxSize(0.28f))
        }
        val layer = incoming
        if (layer?.image != null) {
            val image = layer.image
            Image(remember(image, context) { image.asPainter(context) }, null,
                Modifier.matchParentSize().graphicsLayer { alpha = layer.opacity.value }, contentScale = ContentScale.Crop)
        }
    }
}
