package com.leyu.melora.ui.playlist

import com.leyu.melora.ui.theme.SystemBarsVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.Tag
import com.leyu.melora.playback.sdk.TagInfo
import com.leyu.melora.ui.common.ChromeActionSurface
import com.leyu.melora.ui.common.ShimmerBox
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.theme.MeloraAppearance

private const val TAG_CACHE_TTL = 15 * 60 * 1000L

/**
 * 歌单页顶栏右侧三件套（互相独立）：
 * 1. 最热/最新段选（仅酷我支持）
 * 2. 分类筛选胶囊 → 底部面板
 * 3. 平台胶囊 → 顶栏平台选择态（由宿主切换，五个平台）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistTopBarFilter(onOpenPlatformPicker: () -> Unit) {
    val platformId by MeloraSettings.playlistPlatform.collectAsStateWithLifecycle()
    val platform = playlistPlatforms.firstOrNull { it.id == platformId } ?: playlistPlatforms.first()
    val tagName by MeloraSettings.playlistTagName.collectAsStateWithLifecycle()
    val sortOrder by MeloraSettings.playlistSort.collectAsStateWithLifecycle()
    var showCategorySheet by remember { mutableStateOf(false) }
    val sortOptions = if (platform.id == "kw") listOf("最热", "最新") else emptyList()

    Row(
        modifier = Modifier
            .height(48.dp)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 排序（仅酷我）
        if (sortOptions.size > 1) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                modifier = Modifier.height(48.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    sortOptions.forEachIndexed { index, label ->
                        val isSelected = sortOrder == index
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = Color.Transparent,
                            modifier = Modifier
                                .height(44.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember(index) { MutableInteractionSource() },
                                ) { MeloraSettings.updatePlaylistSort(index) },
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MeloraAppearance.brand else MeloraAppearance.textSub,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        // 2. 分类筛选
        ChromeActionSurface(
            onClick = { showCategorySheet = true },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(48.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 9.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tagName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MeloraAppearance.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 86.dp),
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "选择歌单分类",
                    tint = MeloraAppearance.textSub,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = if (showCategorySheet) 180f else 0f },
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        // 3. 平台入口：点击进入顶栏平台选择态（五个平台）
        ChromeActionSurface(
            onClick = onOpenPlatformPicker,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(48.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 9.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(platform.color.copy(alpha = 0.55f)),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = sourceAliasDisplay(platform.id, platform.label),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MeloraAppearance.textMain,
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "切换平台",
                    tint = MeloraAppearance.textSub,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }

    if (showCategorySheet) {
        PlaylistCategorySheet(onDismiss = { showCategorySheet = false })
    }
}

/** 歌单分类面板：分维度 Section 呈现（热门/语种/风格/场景/主题），置顶全部歌单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistCategorySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val platformId by MeloraSettings.playlistPlatform.collectAsStateWithLifecycle()
    val platform = playlistPlatforms.firstOrNull { it.id == platformId } ?: playlistPlatforms.first()
    val selectedTagId by MeloraSettings.playlistTagId.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tagInfo by remember(platformId) {
        mutableStateOf(OnlineCache.get<TagInfo>("playlistTags.$platformId", TAG_CACHE_TTL) ?: TagInfo(emptyList(), emptyList()))
    }
    var loadingTags by remember(platformId) { mutableStateOf(tagInfo.hotTag.isEmpty() && tagInfo.tags.isEmpty()) }

    LaunchedEffect(platformId) {
        if (tagInfo.hotTag.isNotEmpty() || tagInfo.tags.isNotEmpty()) return@LaunchedEffect
        loadingTags = true
        runCatchingCancellable { OnlineRepository.playlistTags(context, platformId) }
            .onSuccess {
                tagInfo = it
                if (it.hotTag.isNotEmpty()) OnlineCache.put("playlistTags.$platformId", it)
            }
        loadingTags = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MeloraAppearance.canvas,
        tonalElevation = 0.dp,
    ) {
        SystemBarsVisibility()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "${sourceAliasDisplay(platform.id, platform.label)}歌单分类",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MeloraAppearance.textMain,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            HorizontalDivider(color = MeloraAppearance.divider, thickness = 0.6.dp)
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (loadingTags) {
                    PlaylistCategorySkeleton()
                } else {
                    // 1. 热门推荐分组（“全部歌单”置于首位）
                    val hotList = listOf(Tag("", "全部歌单")) + tagInfo.hotTag
                    CategoryGroupSection(
                        title = "热门推荐",
                        tags = hotList,
                        selectedTagId = selectedTagId,
                        onSelectTag = { tag ->
                            MeloraSettings.updatePlaylistTag(tag.id, tag.name)
                            onDismiss()
                        },
                    )

                    // 2. 平台各维度分组（如语种、曲风、场景、主题等）
                    tagInfo.tags.forEach { group ->
                        if (group.list.isNotEmpty()) {
                            CategoryGroupSection(
                                title = group.name,
                                tags = group.list,
                                selectedTagId = selectedTagId,
                                onSelectTag = { tag ->
                                    MeloraSettings.updatePlaylistTag(tag.id, tag.name)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistCategorySkeleton() {
    val chipLabels = listOf("全部歌单", "华语", "流行", "摇滚", "轻音乐", "影视专区")
    Column(
        modifier = Modifier.clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    Text(
                        text = "分类标题",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Transparent,
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(64.dp)
                            .height(12.dp),
                        cornerRadius = 4.dp,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chipLabels.forEach { label ->
                        Box {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Transparent,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                            ShimmerBox(Modifier.matchParentSize(), cornerRadius = 10.dp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGroupSection(
    title: String,
    tags: List<Tag>,
    selectedTagId: String,
    onSelectTag: (Tag) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MeloraAppearance.textSub,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                TagChip(
                    label = tag.name,
                    isSelected = selectedTagId == tag.id,
                    onClick = { onSelectTag(tag) },
                )
            }
        }
    }
}

@Composable
private fun TagChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MeloraAppearance.brand else MeloraAppearance.card,
        border = if (isSelected) null else MeloraAppearance.chipBorder,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else MeloraAppearance.textMain,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
