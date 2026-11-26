package com.leyu.melora.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun isCurrentListSong(uid: String): Boolean {
    val track = LocalSongListState.current.currentTrack
    val current by remember(uid, track) { derivedStateOf { track.value?.uid == uid } }
    return current
}

@Composable
internal fun SongPlaybackPrefix(isCurrent: Boolean) {
    if (isCurrent) {
        PlaybackBars(
            advancing = LocalSongListState.current.advancing.value,
            color = BrandBlue,
            modifier = Modifier.padding(end = 5.dp),
            motionEnabled = !com.leyu.melora.ui.theme.MeloraAppearance.isEInk,
        )
    }
}

@Composable
internal fun PlaybackBars(
    advancing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
) {
    val phase = if (advancing && motionEnabled && LocalPageActive.current) {
        rememberInfiniteTransition(label = "songPlayback").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
            label = "barPhase",
        )
    } else null

    Canvas(modifier.size(14.dp).semantics { contentDescription = if (advancing) "正在播放" else "当前曲目" }) {
        // 固定画布内共用一个时钟，逐帧只重绘三根音柱。
        val angle = (phase?.value ?: 0f) * 2.0 * PI
        val stroke = size.width / 5f
        val bottom = size.height - stroke / 2f
        for (bar in 0..2) {
            val wave = sin(angle * (if (bar == 1) 2 else 1) + bar * 2.1)
            val height = (0.50f + 0.32f * wave.toFloat()) * (size.height - stroke)
            val x = size.width * (bar + 0.5f) / 3f
            drawLine(color, Offset(x, bottom), Offset(x, bottom - height), stroke, StrokeCap.Round)
        }
    }
}
