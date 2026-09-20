package com.leyu.melora.ui.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp

/** 迷你文字退场与整张播放器背景交接使用同一进度，避免上下两截不同底色。 */
internal const val PlayerMiniFadeEnd = 0.16f

internal enum class PlayerSheetAnchor { Collapsed, Expanded }

/** 到达收起终点才释放详情，轻拖回弹或队列翻页都不是退出。 */
internal fun playerSheetIsCollapsed(settled: PlayerSheetAnchor, target: PlayerSheetAnchor, progress: Float): Boolean =
    settled == PlayerSheetAnchor.Collapsed && target == PlayerSheetAnchor.Collapsed && progress <= 0.001f

internal fun playerSheetProgress(offset: Float, travel: Float): Float =
    if (!offset.isFinite() || travel <= 0f) 0f else (1f - offset / travel).coerceIn(0f, 1f)

internal fun playerSheetTarget(
    offset: Float,
    travel: Float,
    velocity: Float,
    velocityThreshold: Float,
    settled: PlayerSheetAnchor,
): PlayerSheetAnchor = when {
    velocity > velocityThreshold -> PlayerSheetAnchor.Collapsed
    velocity < -velocityThreshold -> PlayerSheetAnchor.Expanded
    settled == PlayerSheetAnchor.Expanded && offset > travel * 0.35f -> PlayerSheetAnchor.Collapsed
    settled == PlayerSheetAnchor.Collapsed && offset < travel * 0.65f -> PlayerSheetAnchor.Expanded
    else -> settled
}

internal fun playerMotionPhase(progress: Float, start: Float, end: Float): Float {
    val value = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return value * value * (3f - 2f * value)
}

/** 两个端点都在Sheet局部坐标中；整层位移与封面插值合成一条连续轨迹。 */
internal fun playerArtworkBounds(mini: Rect, full: Rect, progress: Float): Rect =
    lerp(mini, full, progress.coerceIn(0f, 1f))

internal fun playerUsesTwoPanes(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp

/** 翻页视口和单页始终等宽，内距只用于页内内容；横屏内侧由两栏间距负责。 */
internal fun playerPaneContentPadding(twoPanes: Boolean, controls: Boolean): PaddingValues = PaddingValues(
    start = if (twoPanes && controls) 0.dp else 22.dp,
    end = if (twoPanes && !controls) 0.dp else 22.dp,
)

/**
 * 为手动上一首/下一首提供一个共享的、懒惰恢复的短时点击预算。
 * 时间只由调用方在真实点击时传入，因此不会排队、补发或启动计时任务。
 */
internal class PlayerSkipBurstGate(
    private val burstSize: Int = 3,
    private val cooldownMillis: Long = 600L,
) {
    private var remaining = burstSize
    private var lastTapAt: Long? = null

    init {
        require(burstSize > 0) { "burstSize must be positive" }
        require(cooldownMillis >= 0L) { "cooldownMillis must not be negative" }
    }

    internal fun tryAcquire(now: Long): Boolean {
        val previousTapAt = lastTapAt
        if (previousTapAt != null && now - previousTapAt >= cooldownMillis) {
            remaining = burstSize
        }

        // 拒绝点击也会更新时间戳，避免连续点击绕过冷却窗口。
        lastTapAt = now
        if (remaining == 0) return false

        remaining -= 1
        return true
    }
}
