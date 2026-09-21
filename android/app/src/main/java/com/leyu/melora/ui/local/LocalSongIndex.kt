package com.leyu.melora.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.local.LocalSongIndexLabels
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.theme.MeloraAppearance

/** 紧凑视觉与连续触摸共享同一等分坐标，不为每个字母叠加会互相抢占的点击区域。 */
@Composable
internal fun LocalSongIndex(
    sections: Map<String, Int>,
    currentSection: String?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    var pressed by remember(sections) { mutableStateOf<String?>(null) }
    val choose: (String) -> Unit = { label ->
        // 包括空分组：触摸每个字母都有反馈，但空分组不让列表跳到无关歌曲。
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        sections[label]?.let(select)
    }
    val chooseLatest by rememberUpdatedState(choose)

    BoxWithConstraints(
        modifier.width(76.dp).heightIn(max = 448.dp).fillMaxHeight(),
    ) {
        val cellHeight = maxHeight / LocalSongIndexLabels.size
        val fontSize = with(LocalDensity.current) { (cellHeight * 0.72f).toSp() }
            .let { if (it < 11.sp) it else 11.sp }
        Column(
            Modifier.align(Alignment.CenterEnd).width(18.dp).fillMaxHeight()
                .testTag("local-song-index")
                .pointerInput(sections) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun moveTo(y: Float) {
                            val slot = (y / size.height * LocalSongIndexLabels.size).toInt()
                                .coerceIn(LocalSongIndexLabels.indices)
                            val label = LocalSongIndexLabels[slot]
                            if (label != pressed) {
                                pressed = label
                                chooseLatest(label)
                            }
                        }
                        try {
                            moveTo(down.position.y)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                moveTo(change.position.y)
                                change.consume()
                            } while (true)
                        } finally {
                            pressed = null
                        }
                    }
                },
        ) {
            LocalSongIndexLabels.forEach { label ->
                val highlighted = label == (pressed ?: currentSection)
                Box(
                    Modifier.fillMaxWidth().weight(1f).semantics(mergeDescendants = true) {
                        contentDescription = "跳转到 $label"
                        role = Role.Button
                        selected = highlighted
                        onClick {
                            chooseLatest(label)
                            true
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            highlighted -> BrandBlue
                            label in sections -> TextSub
                            else -> TextMuted
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        pressed?.let { label ->
            val center = cellHeight * (LocalSongIndexLabels.indexOf(label) + 0.5f)
            val bubbleSize = minOf(44.dp, maxHeight)
            Box(
                Modifier.offset(y = (center - bubbleSize / 2).coerceIn(0.dp, maxHeight - bubbleSize))
                    .size(bubbleSize).background(MeloraAppearance.card.copy(alpha = 0.82f), CircleShape)
                    .testTag("local-song-index-preview"),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = BrandBlue, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
