package com.leyu.melora.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    query: String,
    selectedPlatform: PlatformSource,
    platformMenuExpanded: Boolean,
    suggestions: List<String>,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onPlatformMenuExpandedChange: (Boolean) -> Unit,
    onPlatformChange: (PlatformSource) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // --- 1. 跑道型一体化搜索胶囊 ---
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
                    .padding(start = 6.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (category != SearchCategory.Audiobook) {
                    Box {
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
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "选择平台",
                                tint = TextSub,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = platformMenuExpanded,
                        onDismissRequest = { onPlatformMenuExpandedChange(false) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.background(CardWhite),
                    ) {
                        PlatformSource.values().forEach { platform ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(platform.color),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = sourceAliasDisplayMusic(platform.id, platform.label),
                                            fontSize = 14.sp,
                                            fontWeight = if (selectedPlatform == platform) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedPlatform == platform) BrandBlue else TextMain,
                                        )
                                        if (selectedPlatform == platform) {
                                            Spacer(Modifier.weight(1f))
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = BrandBlue,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onPlatformChange(platform)
                                },
                            )
                        }
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

                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp),
                )

                Spacer(Modifier.width(6.dp))

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = TextStyle(fontSize = 14.sp, color = TextMain),
                    cursorBrush = SolidColor(BrandBlue),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
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

                if (query.isNotEmpty()) {
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
                }
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
                Column {
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
