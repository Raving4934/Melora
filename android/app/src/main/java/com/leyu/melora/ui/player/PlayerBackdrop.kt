package com.leyu.melora.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val BackdropDecodeSize = 128
private const val BackdropCacheCapacity = 4
private const val BackdropBlurRadius = 8
private const val BackdropBlurPasses = 3
private const val BackdropPaletteMaxAxisSamples = 12
private const val BackdropLoadTimeoutMillis = 15_000L

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
    Box(modifier = modifier.background(overlay.baseColor)) {
        // 只对已就绪的 128px 环境图交叉淡化；缓存命中也走相同过渡，不排队等旧动画。
        Crossfade(
            targetState = entry?.bitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(PlayerBackdropTransitionMillis),
            label = "playerBackdropImage",
        ) { bitmap ->
            val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
            Box(Modifier.fillMaxSize().background(overlay.baseColor)) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = overlay.artworkAlpha,
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
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
