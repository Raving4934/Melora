package com.leyu.melora.ui.player

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.toBitmap
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val BackdropDecodeSize = 128
private const val BackdropCacheCapacity = 4
private const val BackdropBlurRadius = 8
private const val BackdropBlurPasses = 3
private const val BackdropPaletteMaxAxisSamples = 12
private const val BackdropLoadTimeoutMillis = 15_000L

private const val PlayerBackdropRuntimeShaderSource = """
uniform shader image;
uniform float2 resolution;
uniform float2 imageSize;
uniform float time;

half4 main(float2 fragCoord) {
    float2 safeResolution = max(resolution, float2(1.0));
    float2 safeImageSize = max(imageSize, float2(1.0));
    float coverScale = max(
        safeResolution.x / safeImageSize.x,
        safeResolution.y / safeImageSize.y
    );
    float2 imageCoord = (fragCoord - safeResolution * 0.5) / coverScale + safeImageSize * 0.5;
    float2 uv = fragCoord / safeResolution;
    float2 flow = float2(
        sin(time * 0.17 + uv.y * 5.2) * 2.8,
        cos(time * 0.13 + uv.x * 4.3) * 2.8
    );
    half4 base = image.eval(imageCoord);
    half4 flowed = image.eval(imageCoord + flow);
    float mixAmount = 0.10 + 0.08 * (0.5 + 0.5 * sin(time * 0.11 + uv.x * 2.0 + uv.y * 1.5));
    return half4(mix(base.rgb, flowed.rgb, mixAmount), base.a);
}
"""

/**
 * 播放器动态效果共用的环境门控。调用方再叠加自己的业务条件，例如播放状态或页面类型。
 *
 * 歌词缩放/自动滚动可以直接复用这个接口；它不绑定 RuntimeShader，也不要求正在播放，
 * 因而不会把播放器背景的语义泄漏到其它动效。
 */
@Composable
internal fun rememberPlayerMotionEnabled(isVisible: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleResumed = remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleResumed.value = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val powerSaveMode = remember(powerManager) {
        mutableStateOf(powerManager?.isPowerSaveMode == true)
    }
    DisposableEffect(context, powerManager) {
        if (powerManager == null) {
            onDispose { }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    powerSaveMode.value = powerManager.isPowerSaveMode
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    val animationsEnabled = remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    LaunchedEffect(Unit) {
        val motionScale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow {
            motionScale?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
        }.collectLatest { scale ->
            animationsEnabled.value = scale > 0f
        }
    }

    return playerMotionEnabled(
        isVisible = isVisible,
        lifecycleResumed = lifecycleResumed.value,
        powerSaveMode = powerSaveMode.value,
        animationsEnabled = animationsEnabled.value,
    )
}

/** 纯策略函数供单元测试使用；不包含 API 或播放器业务语义。 */
internal fun playerMotionEnabled(
    isVisible: Boolean,
    lifecycleResumed: Boolean,
    powerSaveMode: Boolean,
    animationsEnabled: Boolean,
): Boolean = isVisible && lifecycleResumed && !powerSaveMode && animationsEnabled

/** API 33+ 的背景动态时钟还需满足播放中且已有可用封面；暂停不卸载 shader。 */
internal fun playerBackdropClockEnabled(
    apiLevel: Int,
    playing: Boolean,
    hasArtwork: Boolean,
    playerMotionEnabled: Boolean,
): Boolean = apiLevel >= Build.VERSION_CODES.TIRAMISU && playing && hasArtwork && playerMotionEnabled

/**
 * 全屏播放器的低内存封面背景。
 *
 * 这里只加载固定 128px 小图，复用应用已配置的 Coil 单例；模糊和代表色采样都在这条
 * 已有链路上完成，不参与每帧手势绘制。调用方可以继续用 graphicsLayer 控制整层透明度。
 */
@Composable
internal fun PlayerBackdrop(
    artwork: String?,
    entry: PlayerBackdropCacheEntry?,
    onEntryReady: (PlayerBackdropCacheEntry?) -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    playing: Boolean = false,
    motionEnabled: Boolean = rememberPlayerMotionEnabled(isVisible),
) {
    val context = LocalContext.current
    val source = remember(artwork) { normalizePlayerBackdropUri(artwork) }
    // 请求身份包含重开重试；A→B→A 时旧 A 也不能发布到新请求。
    val request = remember(source, isVisible) { Any() }
    val latestRequest by rememberUpdatedState(request)
    val displayedEntry by rememberUpdatedState(entry)
    val currentOnEntryReady by rememberUpdatedState(onEntryReady)

    LaunchedEffect(source, isVisible) {
        // 开始加载不发布 null：上一幅图与代表色只在 loading 期间保留。
        val result = try {
            Result.success(source?.let { uri ->
                PlayerBackdropCache.get(uri) ?: withTimeoutOrNull(BackdropLoadTimeoutMillis) {
                    val imageRequest = ImageRequest.Builder(context)
                        .data(uri)
                        .size(BackdropDecodeSize, BackdropDecodeSize)
                        .allowHardware(false)
                        .allowConversionToBitmap(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build()
                    val loaded = SingletonImageLoader.get(context).execute(imageRequest)
                    val image = (loaded as? SuccessResult)?.image ?: return@withTimeoutOrNull null
                    val ready = withContext(Dispatchers.Default) {
                        currentCoroutineContext().ensureActive()
                        val bitmap = blurBackdropBitmap(image.toBitmap())
                        PlayerBackdropCacheEntry(bitmap, representativeColorFromBitmap(bitmap))
                    }
                    currentCoroutineContext().ensureActive()
                    if (request === latestRequest) PlayerBackdropCache.put(uri, ready)
                    ready
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure<PlayerBackdropCacheEntry?>(error)
        }
        currentCoroutineContext().ensureActive()
        // 同一个 state 写入同时驱动背景和 Provider；错误/无封面/超时明确回中性。
        val next = playerBackdropAfterLoad(displayedEntry, request, latestRequest, result)
        if (next !== displayedEntry) currentOnEntryReady(next)
    }

    val overlay = LocalPlayerColors.current.backdrop
    val scrim = remember(overlay) { Brush.verticalGradient(*overlay.scrimStops.toTypedArray()) }
    val clockEnabled = playerBackdropClockEnabled(
        apiLevel = Build.VERSION.SDK_INT,
        playing = playing,
        hasArtwork = entry != null,
        playerMotionEnabled = motionEnabled && isVisible,
    )
    Box(modifier = modifier.background(overlay.baseColor)) {
        // 只对已就绪的 128px 环境图交叉淡化；缓存命中也走相同过渡，不排队等旧动画。
        Crossfade(
            targetState = entry?.bitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(PlayerBackdropTransitionMillis),
            label = "playerBackdropImage",
        ) { bitmap ->
            Box(Modifier.fillMaxSize().background(overlay.baseColor)) {
                if (bitmap != null) {
                    PlayerBackdropArtwork(
                        bitmap = bitmap,
                        alpha = overlay.artworkAlpha,
                        clockEnabled = clockEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

@Composable
private fun PlayerBackdropArtwork(
    bitmap: Bitmap,
    alpha: Float,
    clockEnabled: Boolean,
    modifier: Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PlayerBackdropRuntimeArtwork(
            bitmap = bitmap,
            alpha = alpha,
            clockEnabled = clockEnabled,
            modifier = modifier,
        )
    } else {
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            alpha = alpha,
        )
    }
}

@Composable
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun PlayerBackdropRuntimeArtwork(
    bitmap: Bitmap,
    alpha: Float,
    clockEnabled: Boolean,
    modifier: Modifier,
) {
    val imageShader = remember(bitmap) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    val runtimeShader = remember(bitmap) {
        RuntimeShader(PlayerBackdropRuntimeShaderSource).apply {
            setInputShader("image", imageShader)
        }
    }
    val paint = remember(runtimeShader) {
        Paint().apply {
            isAntiAlias = true
            shader = runtimeShader
        }
    }
    val currentAlpha = rememberUpdatedState(alpha)
    val animationTimeNanos = remember(bitmap) { mutableLongStateOf(0L) }
    LaunchedEffect(bitmap, clockEnabled) {
        if (clockEnabled) {
            var elapsedNanos = animationTimeNanos.longValue
            var previousFrameNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    if (previousFrameNanos != 0L) {
                        elapsedNanos += (frameNanos - previousFrameNanos).coerceIn(0L, 100_000_000L)
                    }
                    previousFrameNanos = frameNanos
                    animationTimeNanos.longValue = elapsedNanos
                }
            }
        }
    }

    Box(
        modifier = modifier.drawWithCache {
            // shader 按 bitmap 记忆；尺寸变化只更新 uniform，不重新编译 AGSL 源码。
            runtimeShader.setFloatUniform("imageSize", bitmap.width.toFloat(), bitmap.height.toFloat())
            runtimeShader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            onDrawBehind {
                // 只在绘制阶段读取时钟；帧 tick 只使 draw invalidation，不触发组合重建。
                runtimeShader.setFloatUniform(
                    "time",
                    animationTimeNanos.longValue / 1_000_000_000f,
                )
                paint.alpha = currentAlpha.value
                drawIntoCanvas { canvas ->
                    canvas.drawRect(0f, 0f, size.width, size.height, paint)
                }
            }
        },
    )
}

/** null result 表示尚在加载；完成后的 null/失败则清空，过期请求无权修改已显示结果。 */
internal fun <T> playerBackdropAfterLoad(
    displayed: T?,
    request: Any,
    latestRequest: Any,
    result: Result<T?>?,
): T? = if (request !== latestRequest || result == null) displayed else result.getOrNull()

private fun normalizePlayerBackdropUri(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isBlank() || candidate.length > 4096) return null
    val scheme = candidate.substringBefore(':', missingDelimiterValue = "").lowercase()
    return candidate.takeIf {
        scheme == "http" ||
            scheme == "https" ||
            scheme == "content" ||
            scheme == "file" ||
            scheme == "android.resource"
    }
}

internal data class PlayerBackdropCacheEntry(
    val bitmap: Bitmap,
    val representativeColor: Color?,
)

private object PlayerBackdropCache {
    private val lock = Any()
    private val entries = LinkedHashMap<String, PlayerBackdropCacheEntry>(BackdropCacheCapacity, 0.75f, true)

    fun get(key: String): PlayerBackdropCacheEntry? = synchronized(lock) { entries[key] }

    fun put(key: String, entry: PlayerBackdropCacheEntry) {
        synchronized(lock) {
            entries[key] = entry
            while (entries.size > BackdropCacheCapacity) {
                entries.entries.iterator().apply { next(); remove() }
            }
        }
    }
}

/**
 * 在已有 128px 模糊结果上取代表色，不创建第二个缓存。只访问最多 12x12 个边界均匀样本，
 * 忽略透明、近黑和近灰像素；没有可用色时返回 null，让外层使用中性回退。
 */
internal fun representativeColorFromBitmap(
    bitmap: Bitmap,
    maxAxisSamples: Int = BackdropPaletteMaxAxisSamples,
): Color? {
    require(!bitmap.isRecycled) { "bitmap is recycled" }
    require(bitmap.width > 0 && bitmap.height > 0) { "bitmap must not be empty" }
    return representativeColorFromSampler(bitmap.width, bitmap.height, maxAxisSamples) { x, y ->
        bitmap.getPixel(x, y)
    }
}

/** 纯函数版本，供单元测试和未来非 Bitmap 采样复用。 */
internal fun representativeColorFromPixels(
    width: Int,
    height: Int,
    source: IntArray,
    maxAxisSamples: Int = BackdropPaletteMaxAxisSamples,
): Color? {
    require(width > 0 && height > 0) { "sample dimensions must be positive" }
    require(width.toLong() * height.toLong() == source.size.toLong()) {
        "source size must equal width * height"
    }
    return representativeColorFromSampler(width, height, maxAxisSamples) { x, y ->
        source[y * width + x]
    }
}

private fun representativeColorFromSampler(
    width: Int,
    height: Int,
    maxAxisSamples: Int,
    pixelAt: (x: Int, y: Int) -> Int,
): Color? {
    require(maxAxisSamples in 1..16) { "maxAxisSamples must be between 1 and 16" }

    val columns = minOf(width, maxAxisSamples)
    val rows = minOf(height, maxAxisSamples)
    var red = 0f
    var green = 0f
    var blue = 0f
    var weightTotal = 0f

    for (row in 0 until rows) {
        val y = sampleAxisPosition(row, rows, height)
        for (column in 0 until columns) {
            val x = sampleAxisPosition(column, columns, width)
            val pixel = pixelAt(x, y)
            val alpha = (pixel ushr 24 and 0xFF) / 255f
            if (alpha < 0.25f) continue

            val sampleRed = (pixel ushr 16 and 0xFF) / 255f
            val sampleGreen = (pixel ushr 8 and 0xFF) / 255f
            val sampleBlue = (pixel and 0xFF) / 255f
            val maxChannel = maxOf(sampleRed, sampleGreen, sampleBlue)
            val minChannel = minOf(sampleRed, sampleGreen, sampleBlue)
            if (maxChannel < 0.10f) continue
            val saturation = (maxChannel - minChannel) / maxChannel
            if (saturation < 0.08f) continue

            // 提高有明确色相的样本权重，让白底专辑不会把代表色冲成灰。
            val weight = alpha * (0.35f + saturation)
            red += sampleRed * weight
            green += sampleGreen * weight
            blue += sampleBlue * weight
            weightTotal += weight
        }
    }

    if (weightTotal <= 0.0001f) return null
    val result = Color(
        red = (red / weightTotal).coerceIn(0f, 1f),
        green = (green / weightTotal).coerceIn(0f, 1f),
        blue = (blue / weightTotal).coerceIn(0f, 1f),
        alpha = 1f,
    )
    val resultMax = maxOf(result.red, result.green, result.blue)
    val resultMin = minOf(result.red, result.green, result.blue)
    val resultSaturation = if (resultMax <= 0.0001f) 0f else (resultMax - resultMin) / resultMax
    return result.takeIf { resultMax >= 0.10f && resultSaturation >= 0.08f }
}

private fun sampleAxisPosition(index: Int, count: Int, length: Int): Int {
    if (count <= 1) return (length - 1) / 2
    return (index * (length - 1) / (count - 1)).coerceIn(0, length - 1)
}

internal fun blurBackdropBitmap(
    source: Bitmap,
    radius: Int = BackdropBlurRadius,
    passes: Int = BackdropBlurPasses,
): Bitmap {
    require(!source.isRecycled) { "source bitmap is recycled" }
    require(source.width > 0 && source.height > 0) { "source bitmap must not be empty" }

    // Coil 可能命中更大尺寸的内存缓存；计算前再次缩到硬上限，不处理整张大图。
    val scale = minOf(1f, BackdropDecodeSize.toFloat() / maxOf(source.width, source.height))
    val small = if (scale < 1f) source.scale(
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1), true,
    ) else source
    try {
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val blurred = blurBackdropPixels(small.width, small.height, pixels, radius, passes)
        return Bitmap.createBitmap(blurred, small.width, small.height, Bitmap.Config.ARGB_8888)
    } finally {
        if (small !== source) small.recycle()
    }
}

/**
 * Three small separable box passes approximate a Gaussian blur without RenderScript or API-level forks.
 * The source array is never mutated; edge samples are clamped instead of wrapping around.
 */
internal fun blurBackdropPixels(
    width: Int,
    height: Int,
    source: IntArray,
    radius: Int = BackdropBlurRadius,
    passes: Int = BackdropBlurPasses,
): IntArray {
    require(width > 0 && height > 0) { "blur dimensions must be positive" }
    require(source.size == width * height) { "source size must equal width * height" }
    require(radius in 0..32) { "blur radius must be between 0 and 32" }
    require(passes in 1..3) { "blur passes must be between 1 and 3" }
    if (radius == 0) return source.copyOf()

    var current = source.copyOf()
    var next = IntArray(source.size)
    val horizontal = IntArray(source.size)
    repeat(passes) {
        blurHorizontal(current, horizontal, width, height, radius)
        blurVertical(horizontal, next, width, height, radius)
        val previous = current
        current = next
        next = previous
    }
    return current
}

private fun blurHorizontal(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var red = 0
        var green = 0
        var blue = 0
        var alpha = 0
        for (offset in -radius..radius) {
            val pixel = input[row + offset.coerceIn(0, width - 1)]
            alpha += pixel ushr 24 and 0xFF
            red += pixel ushr 16 and 0xFF
            green += pixel ushr 8 and 0xFF
            blue += pixel and 0xFF
        }
        for (x in 0 until width) {
            output[row + x] = packArgb(alpha / window, red / window, green / window, blue / window)
            val outgoing = input[row + (x - radius).coerceIn(0, width - 1)]
            val incoming = input[row + (x + radius + 1).coerceIn(0, width - 1)]
            alpha += (incoming ushr 24 and 0xFF) - (outgoing ushr 24 and 0xFF)
            red += (incoming ushr 16 and 0xFF) - (outgoing ushr 16 and 0xFF)
            green += (incoming ushr 8 and 0xFF) - (outgoing ushr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun blurVertical(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var red = 0
        var green = 0
        var blue = 0
        var alpha = 0
        for (offset in -radius..radius) {
            val pixel = input[offset.coerceIn(0, height - 1) * width + x]
            alpha += pixel ushr 24 and 0xFF
            red += pixel ushr 16 and 0xFF
            green += pixel ushr 8 and 0xFF
            blue += pixel and 0xFF
        }
        for (y in 0 until height) {
            output[y * width + x] = packArgb(alpha / window, red / window, green / window, blue / window)
            val outgoing = input[(y - radius).coerceIn(0, height - 1) * width + x]
            val incoming = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            alpha += (incoming ushr 24 and 0xFF) - (outgoing ushr 24 and 0xFF)
            red += (incoming ushr 16 and 0xFF) - (outgoing ushr 16 and 0xFF)
            green += (incoming ushr 8 and 0xFF) - (outgoing ushr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun packArgb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)
