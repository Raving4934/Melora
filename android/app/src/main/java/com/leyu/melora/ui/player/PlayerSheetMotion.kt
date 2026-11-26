package com.leyu.melora.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp

/** 播放面板、翻页与队列共用吸附节奏，保留手势速度但避免硬弹簧的急冲。 */
internal val PlayerPageSnapSpec = spring<Float>(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 1f,
)

/** 全屏正文在收起前20%行程内退出，避免控件随封面滑进迷你条。 */
internal const val PlayerContentFadeStart = 0.8f

/** 迷你控件在靠近收起端时淡入，这一段已经使用迷你条底色。 */
internal const val PlayerMiniFadeEnd = 0.16f
internal val MiniPlayerContentHeight = 64.dp
internal val DesktopMiniPlayerHeight = 72.dp

internal enum class PlayerSheetAnchor { Collapsed, Expanded }

internal enum class PlayerSheetBackAction { PassThrough, Consume, ReturnToPlayer, Collapse }

internal enum class PlayerSheetTransition { Expand, OpenQueue, ReturnToPlayer, Collapse }

/** 只有迷你条完全归位才把返回交还底页，不能复用视觉上的展开/收起阈值。 */
internal fun playerSheetBackAction(
    settled: PlayerSheetAnchor,
    target: PlayerSheetAnchor,
    progress: Float,
    animationRunning: Boolean = false,
    transition: PlayerSheetTransition? = null,
    queueReturnTarget: PlayerSheetAnchor? = null,
): PlayerSheetBackAction = when {
    transition == PlayerSheetTransition.Collapse || transition == PlayerSheetTransition.ReturnToPlayer ->
        PlayerSheetBackAction.Consume
    transition == PlayerSheetTransition.OpenQueue && queueReturnTarget == PlayerSheetAnchor.Expanded ->
        PlayerSheetBackAction.ReturnToPlayer
    transition != null -> PlayerSheetBackAction.Collapse
    animationRunning && target == PlayerSheetAnchor.Collapsed -> PlayerSheetBackAction.Consume
    settled == PlayerSheetAnchor.Collapsed && target == PlayerSheetAnchor.Collapsed && progress <= 0f ->
        PlayerSheetBackAction.PassThrough
    progress >= 0.99f && queueReturnTarget == PlayerSheetAnchor.Expanded -> PlayerSheetBackAction.ReturnToPlayer
    else -> PlayerSheetBackAction.Collapse
}

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

/** 底部圆角始终位于屏幕外：保持可见轮廓，同时让通常尺寸走规则圆角裁切。 */
internal fun playerSheetShape(progress: Float, offset: Dp): RoundedCornerShape {
    val radius = 22.dp * playerMotionPhase(progress, 0f, 0.04f) * (1f - playerMotionPhase(progress, 0.85f, 1f))
    val bottom = minOf(radius, offset.coerceAtLeast(0.dp))
    return RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = bottom, bottomEnd = bottom)
}

internal fun playerUsesDesktopMiniBar(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600f && heightDp >= 480f

/** 两个端点都在Sheet局部坐标中；整层位移与封面插值合成一条连续轨迹。 */
internal fun playerArtworkBounds(mini: Rect, full: Rect, progress: Float): Rect =
    lerp(mini, full, progress.coerceIn(0f, 1f))

/** 展开窗口给歌词独立阅读栏；紧凑横屏仍保留原有手机布局。 */
internal fun playerUsesExpandedLayout(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 840f && heightDp >= 480f

/** 高度受限的矮宽横屏窗口（手机横屏、平板上下分屏、矮宽小窗与车机）统一使用紧凑横屏双栏。 */
internal fun playerUsesCompactLandscape(widthDp: Float, heightDp: Float): Boolean =
    !playerUsesExpandedLayout(widthDp, heightDp) && widthDp > heightDp && heightDp < 480f

/** 展开大屏与紧凑横屏均使用双栏结构；中等宽度竖屏/方屏分屏仍保留单栏纵向布局。 */
internal fun playerUsesTwoPanes(widthDp: Float, heightDp: Float): Boolean =
    playerUsesExpandedLayout(widthDp, heightDp) || playerUsesCompactLandscape(widthDp, heightDp)

/** 翻页视口和单页始终等宽，内距只用于页内内容；横屏内侧由两栏间距负责。 */
internal fun playerPaneContentPadding(twoPanes: Boolean, controls: Boolean): PaddingValues = PaddingValues(
    start = if (twoPanes && controls) 0.dp else 22.dp,
    end = if (twoPanes && !controls) 0.dp else 22.dp,
)

/** 手机横屏隐藏状态栏后保留最低顶部呼吸留白，避免封面与歌词顶栏贴死屏幕上沿。 */
internal fun compactLandscapeTopInsetDp(systemTopInsetDp: Float, windowHeightDp: Float): Float {
    val minimum = if (windowHeightDp >= 350f) 16f else 8f
    return maxOf(systemTopInsetDp, minimum)
}

internal val CompactLandscapeHeadingGap = 14.dp

/** 为设备按钮及至少六个当前字号的汉字保留宽度；较窄的左栏将标题放到封面上方。 */
internal fun compactLandscapeInlineCoverLimit(paneWidth: Dp, titleSize: Dp): Dp? =
    (paneWidth - 48.dp - CompactLandscapeHeadingGap - titleSize * 6).takeIf { it >= 112.dp }

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

/** 下拉/纵向容器比横向翻页晚确认；只覆盖容器本身，子按钮仍使用系统原阈值。 */
@Composable
internal fun rememberPlayerVerticalViewConfiguration(): ViewConfiguration {
    val base = LocalViewConfiguration.current
    return remember(base) {
        object : ViewConfiguration by base {
            override val touchSlop: Float = base.touchSlop * 3f
        }
    }
}
