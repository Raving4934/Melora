package com.leyu.melora.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(
    trackUid: String?,
    position: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    activeColor: Color,
    inactiveColor: Color,
    clockColor: Color,
    clockSize: TextUnit,
    enabled: Boolean = true,
    inlineClocks: Boolean = false,
) = key(trackUid) {
    // 固定 0..1，媒体从未知时长切到实测时长时保留同一 Slider 和手势。
    val slider = remember { SliderState() }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val canSeek = enabled && durationMs > 0
    LaunchedEffect(canSeek) {
        if (!canSeek) dragFraction = null
    }
    val positionMs by position
    val previewMs = dragFraction?.let { (it.toDouble() * durationMs).toLong() } ?: positionMs
    val fraction = dragFraction ?: if (durationMs > 0) {
        (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    } else 0f
    SideEffect { slider.value = fraction }

    val clockStyle = LocalTextStyle.current.copy(fontSize = clockSize, fontFeatureSettings = "tnum")
    val clock: @Composable (String) -> Unit = { text ->
        Text(text, style = clockStyle, color = clockColor, maxLines = 1)
    }
    val seek: @Composable (Modifier) -> Unit = { modifier ->
        val barHeight = if (inlineClocks && dragFraction != null) 4.dp else 2.5.dp
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Slider(
                state = slider,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    if (canSeek) dragFraction?.let { onSeek((it.toDouble() * durationMs).toLong()) }
                    dragFraction = null
                },
                enabled = canSeek,
                colors = SliderDefaults.colors(activeTrackColor = activeColor, inactiveTrackColor = inactiveColor),
                thumb = {},
                track = {
                    Box(
                        Modifier.fillMaxWidth().height(if (inlineClocks) 16.dp else barHeight),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(barHeight)
                                .clip(RoundedCornerShape(if (inlineClocks) 2.dp else 1.5.dp))
                                .background(inactiveColor),
                        ) {
                            Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(activeColor))
                        }
                    }
                },
                modifier = modifier.height(if (inlineClocks) 16.dp else 20.dp)
                    .testTag(if (inlineClocks) "player-mini-seek-slider" else "player-seek-slider")
                    .semantics {
                        val range = 0f..durationMs.coerceAtLeast(1L).toFloat()
                        val displayed = previewMs.toFloat().coerceIn(range)
                        progressBarRangeInfo = ProgressBarRangeInfo(displayed, range)
                        if (canSeek) setProgress { requested ->
                            val target = requested.toLong().coerceIn(0L, durationMs)
                            if (target == previewMs) false else {
                                onSeek(target)
                                true
                            }
                        }
                    },
            )
        }
    }

    if (inlineClocks) {
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            clock(formatClock(previewMs, padMinutes = false))
            seek(Modifier.weight(1f))
            clock(if (durationMs > 0) formatClock(durationMs, padMinutes = false) else "--:--")
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            seek(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                clock(formatClock(previewMs))
                Spacer(Modifier.weight(1f))
                clock(formatClock(durationMs))
            }
        }
    }
}
