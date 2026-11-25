package com.leyu.melora.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.CardWhite
import com.leyu.melora.ui.common.chromeHeaderBlurred
import com.leyu.melora.ui.common.DividerSoft
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.sourceAliasDisplayMusic
import com.leyu.melora.ui.theme.MeloraAppearance

internal val SearchInputSectionHeight = 48.dp
internal val SearchInputSectionBottomSpacing = 14.dp
internal val SearchInputContainerBottomPadding = 8.dp

@Composable
internal fun SearchInputSection(
    category: SearchCategory,
    queryState: TextFieldState,
    selectedPlatform: PlatformSource,
    platformMenuExpanded: Boolean,
    suggestions: List<String>,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onPlatformMenuExpandedChange: (Boolean) -> Unit,
    onPlatformChange: (PlatformSource) -> Unit,
) {
    var capsuleWidthPx by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // --- 1. 跑道型一体化搜索胶囊 ---
        Box(
            modifier = Modifier.fillMaxWidth().onSizeChanged { capsuleWidthPx = it.width },
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = CardWhite,
                shadowElevation = 0.dp,
                // 模糊栏内不加外围描边，避免胶囊两端出现额外暗沿；内部控件边界保留。
                border = if (chromeHeaderBlurred()) null else MeloraAppearance.cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SearchInputSectionHeight),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (category == SearchCategory.Audiobook) 16.dp else 6.dp,
                            end = 6.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (category != SearchCategory.Audiobook) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MeloraAppearance.softFill,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPlatformMenuExpandedChange(true) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(selectedPlatform.color),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = sourceAliasDisplay(selectedPlatform.id, selectedPlatform.label),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextMain,
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = "选择平台",
                                    tint = TextSub,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .width(1.dp)
                                .height(18.dp)
                                .background(MeloraAppearance.divider),
                        )
                    }

                    BasicTextField(
                        state = queryState,
                        textStyle = TextStyle(fontSize = 14.sp, color = TextMain),
                        cursorBrush = SolidColor(BrandBlue),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { onSubmit(queryState.text.toString()) },
                        decorator = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (queryState.text.isEmpty()) {
                                    Text(
                                        text = when (category) {
                                            SearchCategory.Song -> "搜索单曲、歌手、专辑..."
                                            SearchCategory.Playlist -> "搜索精选歌单、专辑..."
                                            SearchCategory.Audiobook -> "搜索有声书、小说、评书..."
                                        },
                                        fontSize = 13.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    if (queryState.text.isNotEmpty()) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "清除",
                                tint = TextSub,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    val hasQuery = queryState.text.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (hasQuery) BrandBlue else MeloraAppearance.softFill,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = hasQuery, role = Role.Button) { onSubmit(queryState.text.toString()) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = "搜索",
                                tint = if (hasQuery) Color.White else TextSub,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }

            if (category != SearchCategory.Audiobook) {
                SearchPlatformMenu(
                    selectedPlatform = selectedPlatform,
                    expanded = platformMenuExpanded,
                    anchorWidthPx = capsuleWidthPx,
                    onDismiss = { onPlatformMenuExpandedChange(false) },
                    onSelect = onPlatformChange,
                )
            }
        }

        // --- 1.1 输入联想面板 ---
        if (suggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardWhite,
                shadowElevation = 0.dp,
                border = MeloraAppearance.cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    suggestions.forEachIndexed { index, suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSubmit(suggestion) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = suggestion,
                                fontSize = 13.sp,
                                color = TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index < suggestions.lastIndex) {
                            HorizontalDivider(
                                color = DividerSoft,
                                thickness = 0.6.dp,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(SearchInputSectionBottomSpacing))
    }
}

@Composable
private fun SearchPlatformMenu(
    selectedPlatform: PlatformSource,
    expanded: Boolean,
    anchorWidthPx: Int,
    onDismiss: () -> Unit,
    onSelect: (PlatformSource) -> Unit,
) {
    val contentDensity = LocalDensity.current
    val windowScale = contentDensity.density / LocalResources.current.displayMetrics.density
    DropdownMenu(
        expanded = expanded && anchorWidthPx > 0,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 8.dp),
        shape = RoundedCornerShape(20.dp * windowScale),
        containerColor = CardWhite,
        modifier = Modifier.layout { measurable, constraints ->
            // 锚点与原生 Popup 共享像素坐标，宽度只受 Popup 窗口的可用空间限制。
            val width = constraints.constrainWidth(anchorWidthPx)
            val menu = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(menu.width, menu.height) { menu.placeRelative(0, 0) }
        },
    ) {
        CompositionLocalProvider(LocalDensity provides contentDensity) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                val spacing = 8.dp
                val optionPadding = 12.dp
                val dotSize = 8.dp
                val checkSize = 15.dp
                val labelStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val selectedLabelStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold)
                val options = PlatformSource.entries.map { platform ->
                    platform to sourceAliasDisplayMusic(platform.id, platform.label)
                }
                val textMeasurer = rememberTextMeasurer(cacheSize = options.size * 2)
                val labelWidth = options.maxOf { (_, label) ->
                    maxOf(
                        textMeasurer.measure(label, labelStyle, maxLines = 1).size.width,
                        textMeasurer.measure(label, selectedLabelStyle, maxLines = 1).size.width,
                    )
                }
                val fittingColumns = with(contentDensity) {
                    val spacingPx = spacing.roundToPx()
                    val optionWidth = labelWidth + 2 * optionPadding.roundToPx() +
                        dotSize.roundToPx() + spacingPx + checkSize.roundToPx()
                    (constraints.maxWidth + spacingPx) / (optionWidth + spacingPx)
                }
                val columns = when {
                    maxWidth >= 760.dp && fittingColumns >= 6 -> 6
                    maxWidth >= 460.dp && fittingColumns >= 3 -> 3
                    fittingColumns >= 2 -> 2
                    else -> 1
                }
                Column(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    options.chunked(columns).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                        ) {
                            row.forEach { (platform, label) ->
                                val selected = selectedPlatform == platform
                                Surface(
                                    selected = selected,
                                    onClick = { onSelect(platform) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) BrandBlue.copy(alpha = 0.09f) else MeloraAppearance.softFill,
                                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = optionPadding, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(dotSize).background(platform.color, CircleShape))
                                        Spacer(Modifier.width(spacing))
                                        Text(
                                            text = label,
                                            style = if (selected) selectedLabelStyle else labelStyle,
                                            color = if (selected) BrandBlue else TextMain,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(Modifier.size(checkSize), contentAlignment = Alignment.Center) {
                                            if (selected) {
                                                Icon(
                                                    Icons.Outlined.Check,
                                                    contentDescription = null,
                                                    tint = BrandBlue,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
