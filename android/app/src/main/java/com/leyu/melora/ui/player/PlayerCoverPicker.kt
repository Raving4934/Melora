package com.leyu.melora.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.ui.common.MeloraBottomSheet
import kotlinx.coroutines.delay

/** 悬浮卡片选择原有封面类型，主题模式复用播放页设置；拖动只聚焦，不写入偏好。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerCoverPicker(
    selected: PlayerCoverStyle,
    track: UiTrack?,
    immersive: Boolean,
    motionEnabled: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSelect: (PlayerCoverStyle) -> Unit,
    onToggleImmersive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPlayerColors.current
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableIntStateOf(selected.ordinal) }
    var width by remember { mutableIntStateOf(1) }
    LaunchedEffect(selected) { focused = selected.ordinal }
    val focus: (Float) -> Unit = { x ->
        val index = ((x / width) * PlayerCoverStyle.entries.size).toInt().coerceIn(0, PlayerCoverStyle.entries.lastIndex)
        if (focused != index) {
            focused = index
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        scrimColor = colors.background.copy(alpha = 0.82f),
        dragHandle = null,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState())
                .testTag("player-cover-picker"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("封面类型", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("滑动聚焦 · 点击应用", color = colors.textSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
            // 注明文字高度随系统字号预留，倾斜和抬升只改变绘制，不挤动相邻卡片。
            val cardsHeight = 172.dp + with(LocalDensity.current) { 32.sp.toDp() }
            Row(
                Modifier.fillMaxWidth().height(cardsHeight).testTag("player-cover-cards")
                    .onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .pointerInput(width) {
                        detectHorizontalDragGestures(
                            onDragStart = { focus(it.x) },
                            onHorizontalDrag = { change, _ -> change.consume(); focus(change.position.x) },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PlayerCoverStyle.entries.forEachIndexed { index, style ->
                    var appeared by remember { mutableStateOf(!motionEnabled) }
                    LaunchedEffect(motionEnabled) { if (motionEnabled) delay(index * 30L); appeared = true }
                    val entrance by animateFloatAsState(if (appeared) 1f else 0f,
                        if (motionEnabled) spring(dampingRatio = 0.88f, stiffness = 280f) else snap(), label = "coverEntrance")
                    val emphasis by animateFloatAsState(if (focused == index) 1f else 0f,
                        if (motionEnabled) spring(dampingRatio = 1f, stiffness = 350f) else snap(), label = "coverFocus")
                    val current = selected == style
                    val shape = RoundedCornerShape(13.dp)
                    Column(
                        Modifier.weight(1f).graphicsLayer {
                            translationY = (1f - entrance) * 65.dp.toPx() - emphasis * 12.dp.toPx()
                            rotationZ = (index - 1) * 6f * (1f - emphasis)
                            scaleX = 0.95f + emphasis * 0.05f; scaleY = scaleX
                            alpha = entrance.coerceIn(0f, 1f)
                        }.clip(shape).background(colors.background)
                            .border(if (current) 1.5.dp else 0.5.dp, if (current) colors.textPrimary else colors.outline, shape)
                            .selectable(current, role = Role.RadioButton, onClick = { onSelect(style) })
                            .testTag("player-cover-${style.storageValue}").padding(7.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                            NowPlayingArtwork(track?.artwork, track?.uid ?: "cover-preview", style,
                                Modifier.widthIn(max = 112.dp).fillMaxWidth().aspectRatio(1f), cornerRadius = 12)
                        }
                        Text(when (style) {
                            PlayerCoverStyle.Default -> "默认方形"
                            PlayerCoverStyle.Circle -> "圆形裁切"
                            PlayerCoverStyle.Vinyl -> "黑胶唱片"
                        }, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 3.dp))
                        Text(if (current) "当前封面" else "点击应用", color = colors.textSecondary,
                            fontSize = 9.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp, bottom = 3.dp, start = 3.dp))
                    }
                }
            }
            Text("主题模式", color = colors.textSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    val current = mode == themeMode
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (current) colors.cardSelected else colors.cardSurface)
                            .border(1.dp, if (current) colors.textPrimary.copy(alpha = 0.6f) else colors.outline, RoundedCornerShape(14.dp))
                            .selectable(selected = current, role = Role.RadioButton,
                                onClick = { onThemeModeChange(mode) })
                            .testTag("player-cover-theme-${mode.storageValue}")
                            .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(when (mode) {
                            ThemeMode.Auto -> "跟随系统"
                            ThemeMode.Light -> "浅色"
                            ThemeMode.Dark -> "深色"
                        }, color = if (current) colors.textPrimary else colors.textSecondary,
                            fontSize = 12.sp, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            TextButton(onClick = onToggleImmersive,
                modifier = Modifier.padding(top = 5.dp).heightIn(min = 48.dp).testTag("player-cover-immersive")) {
                Text(if (immersive) "退出沉浸模式" else "进入沉浸模式", color = colors.textPrimary, fontSize = 14.sp)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("player-cover-cancel")) {
                Text("取消", color = colors.textSecondary, fontSize = 12.sp)
            }
        }
    }
}
