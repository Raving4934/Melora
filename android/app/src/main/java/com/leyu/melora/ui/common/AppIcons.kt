package com.leyu.melora.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 应用自绘图标（原 res/drawable 矢量 1:1 转为 ImageVector，Compose 专用）。
 * 悬浮歌词窗、通知栏与启动图标仍保留 drawable 资源（View API / 系统要求）。
 */

private val MeloraMarkBlue = Color(0xFF5B89FA)

private const val MeloraMarkWave =
    "m1.335 16.48c4.49-8.13 7.9-13.79 11.52-13.79 3.14 0 4.86 3.15 6.45 6.5 0.58 1.23 1.58 1.11 2.06 0.18 2.63-5.04 4.87-8.37 7.74-8.37 3.88 0 7.84 6.77 11.75 14.35 0.84 1.69-0.31 2.96-1.29 2.49-1.37-0.9-6.95-11.49-11.01-11.49-3.02 0-5.79 6.93-7.43 9.89-0.66 1.15-1.75 1.05-2.41-0.25-1.81-3.77-3.26-6.52-4.94-6.52-1.8 0-3.49 4.21-5.83 8.19-1.05 1.83-2.84 2.87-4.29 2.87-2.26 0-3.22-2.19-2.32-4.05z"

private const val MeloraMarkPlay =
    "m26.795 20.15v-8.87c0-1.03 1.09-1.49 1.94-0.9l5.99 4.37c0.7 0.5 0.64 1.6-0.11 2.11l-6.01 4.22c-0.92 0.63-1.81 0.12-1.81-0.93z"

/**
 * 关于页品牌标识：声波与播放按钮（蓝色固定）。
 * 视口按内容包围盒裁剪（原稿顶部含约 1/4 透明留白）；字标由界面文本承担。
 * 注意：Image 按 ImageVector 固有尺寸渲染，展示尺寸由 defaultWidth/Height 决定。
 */
internal val MeloraAboutMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "MeloraAboutMark",
        defaultWidth = 150.dp,
        defaultHeight = 79.6.dp,
        viewportWidth = 42.146f,
        viewportHeight = 22.355f,
    ).apply {
        addPath(pathData = addPathNodes(MeloraMarkWave), fill = SolidColor(MeloraMarkBlue))
        addPath(pathData = addPathNodes(MeloraMarkPlay), fill = SolidColor(MeloraMarkBlue))
    }.build()
}

internal val AudioEffectsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AudioEffectsIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes("M3,14 V11 A9,9 0 0 1 21,11 V14"),
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M3.75,13 A1.75,1.75 0 0 1 5.5,14.75 V17.75 A1.75,1.75 0 0 1 3.75,19.5 A1.75,1.75 0 0 1 2,17.75 V14.75 A1.75,1.75 0 0 1 3.75,13 Z"),
            fill = SolidColor(Color(0xFF000000)),
        )
        addPath(
            pathData = addPathNodes("M20.25,13 A1.75,1.75 0 0 1 22,14.75 V17.75 A1.75,1.75 0 0 1 20.25,19.5 A1.75,1.75 0 0 1 18.5,17.75 V14.75 A1.75,1.75 0 0 1 20.25,13 Z"),
            fill = SolidColor(Color(0xFF000000)),
        )
        addPath(
            pathData = addPathNodes("M9,13.5 V12.5 A3,3 0 0 1 15,12.5 V13.5"),
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = addPathNodes("M12,12.8 A1.2,1.2 0 1 1 12,15.2 A1.2,1.2 0 1 1 12,12.8 Z"),
            fill = SolidColor(Color(0xFF000000)),
        )
    }.build()
}

/**
 * 音乐播放器随机播放图标（根据矢量 XML 1:1 转换）。
 * 视口 24 × 24，双轨平滑交叉流线与右向实心箭头。
 */
internal val MusicShuffleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MusicShuffleIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 上半部流线：左下起步平滑交叉向上，终于实心箭头
        addPath(
            pathData = addPathNodes("M2,18 C6,18 8.5,14 11.5,10 C14,6.5 16,6 18.5,6"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        // 下半部流线：左上起步平滑交叉向下，终于实心箭头
        addPath(
            pathData = addPathNodes("M2,6 C6,6 8.5,10 11.5,14 C14,17.5 16,18 18.5,18"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        // 右上箭头 (纯正实心微导角小三角，贴合原图)
        addPath(
            pathData = addPathNodes("M17.5,2.5 L22.5,6 L17.5,9.5 Z"),
            fill = SolidColor(Color.Black),
        )
        // 右下箭头 (纯正实心微导角小三角，贴合原图)
        addPath(
            pathData = addPathNodes("M17.5,14.5 L22.5,18 L17.5,21.5 Z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
