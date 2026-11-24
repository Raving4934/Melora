package com.leyu.melora.ui.settings

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.leyu.melora.ui.common.LocalPageActive
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlin.math.roundToInt

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
) {
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    val canChoose = enabled && options.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                anchor = coordinates.screenOrigin()?.let { IntRect(it, coordinates.size) }
            }
            .clickable(enabled = canChoose, role = Role.Button) { expanded = true }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = SettingsTextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = SettingsTextSub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
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
    if (row != null && LocalPageActive.current) {
        AnchoredChoiceDialog(title, row, onDismiss = { expanded = false }) {
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
private fun AnchoredChoiceDialog(
    title: String,
    anchor: IntRect,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val contentDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow?.setDimAmount(0.32f)
        }
        CompositionLocalProvider(LocalDensity provides contentDensity) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AnchoredChoiceContent(title, anchor, content)
                }
            }
        }
    }
}

@Composable
private fun AnchoredChoiceContent(title: String, anchor: IntRect, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf<IntOffset?>(null) }
    Layout(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.screenOrigin() },
        content = {
            if (origin != null) {
                Surface(
                    modifier = Modifier.widthIn(min = 132.dp).width(IntrinsicSize.Max)
                        .semantics { paneTitle = title },
                    shape = RoundedCornerShape(16.dp),
                    color = SettingsCardBg,
                    shadowElevation = 8.dp,
                    border = MeloraAppearance.cardBorder,
                    content = content,
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val child = measurables.singleOrNull()
        val offset = origin
        if (child == null || offset == null) return@Layout layout(width, height) {}

        val edgeX = minOf(8.dp.roundToPx(), width / 2)
        val edgeY = minOf(8.dp.roundToPx(), height / 2)
        val gap = 4.dp.roundToPx()
        val below = (anchor.bottom - offset.y + gap).coerceIn(edgeY, height - edgeY)
        val above = (anchor.top - offset.y - gap).coerceIn(edgeY, height - edgeY)
        val spaceBelow = height - edgeY - below
        val spaceAbove = above - edgeY
        val sideSpace = maxOf(spaceAbove, spaceBelow)
        val useSide = sideSpace >= 48.dp.roundToPx()
        val menu = child.measure(Constraints(
            maxWidth = width - 2 * edgeX,
            maxHeight = if (useSide) sideSpace else height - 2 * edgeY,
        ))

        val inset = 16.dp.roundToPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) anchor.right - offset.x - inset - menu.width
            else anchor.left - offset.x + inset
        val opensBelow = !useSide || menu.height <= spaceBelow || spaceBelow >= spaceAbove
        val y = if (opensBelow) below else above - menu.height
        layout(width, height) {
            menu.place(
                x.coerceIn(edgeX, width - edgeX - menu.width),
                y.coerceIn(edgeY, height - edgeY - menu.height),
            )
        }
    }
}

private fun LayoutCoordinates.screenOrigin(): IntOffset? {
    val local = positionOnScreen()
    return if (local.isSpecified) IntOffset(local.x.roundToInt(), local.y.roundToInt()) else null
}
