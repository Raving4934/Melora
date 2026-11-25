package com.leyu.melora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.leyu.melora.ui.common.LocalPageActive
import com.leyu.melora.ui.common.glassBackdropSource
import com.leyu.melora.ui.common.glassPanelModifier
import com.leyu.melora.ui.common.glassScrimAlpha

@Composable
internal fun <T> SettingsChoiceMenu(
    title: String,
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ((T) -> ImageVector)? = null,
    enabled: Boolean = true,
    optionEnabled: (T) -> Boolean = { true },
    optionPreview: (@Composable (T) -> Unit)? = null,
) {
    val active = LocalPageActive.current
    var expanded by remember(active) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    val canChoose = enabled && options.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                anchor = IntRect(coordinates.positionInWindow().round(), coordinates.size)
            }
            .clickable(enabled = canChoose, role = Role.Button) { expanded = true }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsPreferenceLabel(title, subtitle, Modifier.weight(1f).padding(end = 12.dp))
        Row(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon(value), null, tint = SettingsTextSub, modifier = Modifier.size(16.dp))
            Text(
                label(value),
                modifier = Modifier.weight(1f, fill = false),
                color = SettingsTextSub,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.UnfoldMore, null, tint = SettingsTextSub, modifier = Modifier.size(18.dp))
        }
    }
    val row = if (expanded) anchor else null
    if (row != null && active) {
        AnchoredChoicePopup(title, row, onDismiss = { expanded = false }) {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(vertical = 6.dp),
            ) {
                options.forEach { option ->
                    key(option) {
                        val selected = option == value
                        val available = canChoose && optionEnabled(option)
                        val tint = when {
                            selected -> SettingsBrandBlue
                            available -> SettingsTextMain
                            else -> SettingsTextSub
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = selected, enabled = available, role = Role.RadioButton) {
                                    if (expanded) {
                                        expanded = false
                                        onSelect(option)
                                    }
                                }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) Icon(icon(option), null, tint = tint, modifier = Modifier.size(20.dp))
                            Text(
                                label(option),
                                color = tint,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (optionPreview != null) {
                                Box(Modifier.clearAndSetSemantics {}) { optionPreview(option) }
                            }
                            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                if (selected) Icon(Icons.Outlined.Check, null, tint = SettingsBrandBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchoredChoicePopup(
    title: String,
    anchor: IntRect,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val size = LocalWindowInfo.current.containerSize
    val safe = WindowInsets.safeDrawing
    val margin = with(density) { 8.dp.roundToPx() }
    val left = (safe.getLeft(density, direction) + margin).coerceAtMost(size.width)
    val right = (size.width - safe.getRight(density, direction) - margin).coerceAtLeast(left)
    val top = (safe.getTop(density) + margin).coerceAtMost(size.height)
    val bottom = (size.height - safe.getBottom(density) - margin).coerceAtLeast(top)
    if (right == left || bottom == top) return

    val gap = with(density) { 4.dp.roundToPx() }
    val below = (anchor.bottom + gap).coerceIn(top, bottom)
    val above = (anchor.top - gap).coerceIn(top, bottom)
    val spaceBelow = bottom - below
    val sideSpace = maxOf(spaceBelow, above - top)
    val fitsOneRow = sideSpace >= with(density) { 48.dp.roundToPx() }
    val maxHeight = if (fitsOneRow) sideSpace else bottom - top
    val trailing = with(density) { 16.dp.roundToPx() }
    // 行坐标、可用高度和菜单落点都取自宿主窗口，首帧即可确定展开方向。
    val position = remember(anchor, left, right, top, bottom, above, below, trailing, fitsOneRow) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val (width, height) = popupContentSize
                val x = if (layoutDirection == LayoutDirection.Ltr) anchor.right - trailing - width
                    else anchor.left + trailing
                val y = if (!fitsOneRow || height <= spaceBelow) below else above - height
                return IntOffset(
                    x.coerceIn(left, (right - width).coerceAtLeast(left)),
                    y.coerceIn(top, (bottom - height).coerceAtLeast(top)),
                )
            }
        }
    }

    val scrimAlpha = if (glassBackdropSource(SettingsCardBg) != null) glassScrimAlpha(SettingsCardBg) else 0.32f
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            clippingEnabled = false,
            excludeFromSystemGesture = false,
            scrimAlpha = scrimAlpha,
        ),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            val shape = RoundedCornerShape(20.dp)
            Surface(
                modifier = Modifier.widthIn(max = with(density) { (right - left).toDp() })
                    .heightIn(max = with(density) { maxHeight.toDp() })
                    .widthIn(min = 132.dp).width(IntrinsicSize.Max)
                    .shadow(8.dp, shape, clip = false)
                    .then(glassPanelModifier(SettingsCardBg, shape, backdropScrim = Color.Black.copy(alpha = scrimAlpha)))
                    .semantics { paneTitle = title },
                shape = shape,
                color = Color.Transparent,
                contentColor = SettingsTextMain,
                content = content,
            )
        }
    }
}
