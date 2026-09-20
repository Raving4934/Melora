package com.leyu.melora.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.ui.theme.MeloraAppearance

internal data class VisualChoiceItem<T>(
    val value: T,
    val label: String,
    val preview: @Composable (isSelected: Boolean, tint: Color) -> Unit,
)

@Composable
internal fun <T> VisualChoiceCards(
    options: List<VisualChoiceItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { item ->
            val isSelected = selected == item.value
            val targetBg = if (isSelected) MeloraAppearance.tintBlue else MeloraAppearance.softFill
            val targetBorder = if (isSelected) BorderStroke(1.5.dp, MeloraAppearance.brand) else MeloraAppearance.chipBorder
            val targetTint = if (isSelected) MeloraAppearance.brand else MeloraAppearance.textSub
            val targetTextColor = if (isSelected) MeloraAppearance.brand else MeloraAppearance.textMain

            val bg by animateColorAsState(targetBg, animationSpec = tween(180), label = "choiceBg")
            val tint by animateColorAsState(targetTint, animationSpec = tween(180), label = "choiceTint")
            val textColor by animateColorAsState(targetTextColor, animationSpec = tween(180), label = "choiceText")

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .border(targetBorder, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelect(item.value) },
                    )
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.height(26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        item.preview(isSelected, tint)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThemeModeVisualSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember {
        listOf(
            VisualChoiceItem(ThemeMode.Auto, "跟随系统") { _, tint ->
                Icon(
                    imageVector = Icons.Rounded.BrightnessAuto,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            },
            VisualChoiceItem(ThemeMode.Light, "浅色模式") { _, tint ->
                Icon(
                    imageVector = Icons.Rounded.LightMode,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            },
            VisualChoiceItem(ThemeMode.Dark, "深色模式") { _, tint ->
                Icon(
                    imageVector = Icons.Rounded.DarkMode,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            },
        )
    }
    VisualChoiceCards(
        options = items,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

@Composable
internal fun CoverStyleVisualSelector(
    selected: PlayerCoverStyle,
    onSelect: (PlayerCoverStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember {
        listOf(
            VisualChoiceItem(PlayerCoverStyle.Default, "默认方形") { _, tint ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tint.copy(alpha = 0.12f))
                        .border(1.2.dp, tint, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(13.dp),
                    )
                }
            },
            VisualChoiceItem(PlayerCoverStyle.Circle, "圆形裁切") { _, tint ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.12f))
                        .border(1.2.dp, tint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(tint),
                    )
                }
            },
            VisualChoiceItem(PlayerCoverStyle.Vinyl, "黑胶唱片") { _, tint ->
                Icon(
                    imageVector = Icons.Outlined.Album,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(25.dp),
                )
            },
        )
    }
    VisualChoiceCards(
        options = items,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
    )
}

@Composable
internal fun ThemeModeSettingRow(title: String, mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    ThemeModeVisualSelector(selected = mode, onSelect = onSelect)
}
