package com.leyu.melora.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.provider.Settings as AndroidSettings
import com.leyu.melora.playback.DesktopLyricService
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.ui.common.PageBackHandler
import com.leyu.melora.ui.common.LocalPageActive
import com.leyu.melora.ui.common.PlatformChoice
import com.leyu.melora.ui.common.PlatformUnderlineRow
import com.leyu.melora.ui.common.SourceAliasProvider
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.titleScrollToTop
import com.leyu.melora.ui.audiobook.AudiobooksScreen
import com.leyu.melora.ui.discover.DiscoverScreen
import com.leyu.melora.ui.leaderboard.LeaderboardScreen
import com.leyu.melora.ui.leaderboard.boardPlatforms
import com.leyu.melora.ui.local.LocalSongsPage
import com.leyu.melora.ui.my.MyLibraryScreen
import com.leyu.melora.ui.playlist.PlaylistTopBarFilter
import com.leyu.melora.ui.playlist.PlaylistsScreen
import com.leyu.melora.ui.playlist.playlistPlatforms
import com.leyu.melora.ui.settings.SettingsMasterScreen
import com.leyu.melora.ui.settings.SettingsSubPage
import com.leyu.melora.ui.common.ChromeActionSurface
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.player.ContinuousPlayerSheet
import com.leyu.melora.ui.theme.SystemBarsAppearance
import com.leyu.melora.ui.search.SearchCategory
import com.leyu.melora.ui.search.SearchScreen
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.leyu.melora.ui.theme.MeloraAppearance

private val MiniPlayerHeight = 64.dp
private val PrimaryTopBarHeight = 64.dp
// 统一全局背景底色：高端暖灰画布 (0xFFF4F5F7)
private val CanvasBackground: Color get() = MeloraAppearance.canvas
private val TextDark: Color get() = MeloraAppearance.textMain
private val TextGray: Color get() = MeloraAppearance.textSub

// 侧栏便当卡片：统一使用发丝微描边，彻底消除单向物理阴影带来的发脏不均
private val DrawerCardColor: Color get() = if (MeloraAppearance.isDark) Color(0xFF1E232C) else Color.White
private val DrawerSelectedFill: Color get() = if (MeloraAppearance.isDark) Color(0xFF2A313C) else MeloraAppearance.softFill

@Composable
private fun DrawerSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = DrawerCardColor,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = modifier,
        content = content,
    )
}

private data class MainTabItem(
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

// 按照用户需求定制的侧栏完整选项序列：搜索、排行榜、发现、歌单、听书、本地歌曲、我的列表、设置
private val tabs = listOf(
    MainTabItem("搜索", Icons.Outlined.Search, Color(0xFF1E88E5)),
    MainTabItem("排行榜", Icons.Outlined.Leaderboard, Color(0xFFE53935)),
    MainTabItem("发现", Icons.Outlined.Explore, Color(0xFFFB8C00)),
    MainTabItem("歌单", Icons.AutoMirrored.Outlined.QueueMusic, Color(0xFF00897B)),
    MainTabItem("听书", Icons.Outlined.Headphones, Color(0xFF8E24AA)),
    MainTabItem("本地歌曲", Icons.Outlined.MusicNote, Color(0xFF16A34A)),
    MainTabItem("我的列表", Icons.Outlined.FavoriteBorder, Color(0xFF3949AB)),
    MainTabItem("设置", Icons.Outlined.Settings, Color(0xFF546E7A)),
)

internal fun drawerTarget(offset: Float, width: Float, velocity: Float): Float = when {
    velocity > 500f -> width
    velocity < -500f -> 0f
    offset > width * 0.4f -> width
    else -> 0f
}

// 拖动和松手共用同一 Animatable；接手时先停旧动画，事件不得排队到松手之后。
@Composable
private fun Modifier.drawerSwipeable(
    drawerOffset: Animatable<Float, AnimationVector1D>,
    drawerWidthPx: Float,
    drawerSpring: AnimationSpec<Float>,
    scope: CoroutineScope,
): Modifier = draggable(
    state = rememberDraggableState { delta ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerOffset.snapTo((drawerOffset.value + delta).coerceIn(0f, drawerWidthPx))
        }
    },
    orientation = Orientation.Horizontal,
    startDragImmediately = drawerOffset.isRunning,
    onDragStarted = { drawerOffset.stop() },
    onDragStopped = { velocity ->
        drawerOffset.animateTo(
            drawerTarget(drawerOffset.value, drawerWidthPx, velocity),
            drawerSpring,
            initialVelocity = velocity,
        )
    },
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeloraApp(initialTab: Int = 5) {
    val playerState by PlaybackController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 当前选中的 Tab，默认停留在“本地歌曲” (索引 5)
    var currentTab by remember { mutableIntStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }
    // 搜索页分类状态 (歌曲/歌单/听书)
    var searchCategory by remember { mutableStateOf(SearchCategory.Song) }

    // 侧栏跳转设置主菜单；设置页的“关于乐屿”仍从设置内部进入。
    var settingsNavTarget by remember { mutableStateOf<SettingsSubPage?>(null) }
    var settingsNavSeq by remember { mutableIntStateOf(0) }
    var searchReturnTab by remember { mutableStateOf<Int?>(null) }
    // 排行榜/歌单顶栏的平台选择态；切 Tab 时自动复位
    var platformPickerOpen by remember { mutableStateOf(false) }
    var primaryScrollToTopRequest by remember { mutableIntStateOf(0) }
    fun navigateToTab(target: Int) {
        val next = target.coerceIn(0, tabs.lastIndex)
        currentTab = next
    }
    androidx.compose.runtime.LaunchedEffect(currentTab) { platformPickerOpen = false }

    val density = LocalDensity.current
    // 侧栏宽度调至紧凑精致的 208dp
    val drawerWidth = 208.dp
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val windowSize = LocalWindowInfo.current.containerSize
    val isCompactLandscape = windowSize.width > windowSize.height &&
        with(density) { windowSize.height.toDp() } < 600.dp

    // 侧栏横向位移：0f=关闭，drawerWidthPx=完全拉出
    val drawerOffset = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(drawerWidthPx) {
        val previousWidth = drawerOffset.upperBound
        val fraction = previousWidth?.takeIf { it > 0f }?.let { drawerOffset.value / it }
        drawerOffset.updateBounds(0f, drawerWidthPx)
        if (fraction != null) drawerOffset.snapTo(fraction * drawerWidthPx)
    }
    // 位移每帧改变；交互域只在开/关边界改变，不能带动整个导航树逐帧重组。
    val drawerOpen by remember { derivedStateOf { drawerOffset.value > 0.5f } }
    // 临界阻尼：侧栏推页不来回弹（0.85 会过冲，表现为页面左右"皮筋"）
    val drawerSpring = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
    var pendingDrawerTab by remember { mutableStateOf<Int?>(null) }
    fun closeDrawer() {
        pendingDrawerTab = null
        scope.launch { drawerOffset.animateTo(0f, drawerSpring) }
    }

    // 播放激活状态记录（清空队列时保持底栏，不突兀闪退页面）
    var hasActivePlayback by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(playerState.current) {
        if (playerState.current != null) hasActivePlayback = true
    }

    // 全局提示消息：显示 2.4 秒后自动消费
    androidx.compose.runtime.LaunchedEffect(playerState.message) {
        if (playerState.message != null) {
            kotlinx.coroutines.delay(2_400)
            PlaybackController.consumeMessage()
        }
    }

    // 启动恢复与设置联动：自动续播 / 桌面歌词恢复
    val settingsState = MeloraSettings
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (settingsState.autoPlayOnStart.value) {
            // 等播放服务把上次队列恢复出来；确实没有可恢复内容才兜底播最近播放
            kotlinx.coroutines.withTimeoutOrNull(2_500) {
                snapshotFlow { PlaybackController.state.value.ready }.first { it }
            }
            val snapshot = PlaybackController.state.value
            if (snapshot.current == null && snapshot.queue.isEmpty() && UserLibrary.recents.value.isNotEmpty()) {
                PlaybackController.playQueue(context, UserLibrary.recents.value.toUiTracks(), 0)
            }
        }
        if (settingsState.showDesktopLyrics.value && AndroidSettings.canDrawOverlays(context)) {
            DesktopLyricService.start(context)
        }
    }

    // 记住停留页面：下次启动回到上次的 Tab（搜索/发现/歌单…）
    androidx.compose.runtime.LaunchedEffect(currentTab) {
        MeloraSettings.updateLastTab(currentTab)
    }

    // 从听书页进入搜索时，系统返回回到听书页；搜索结果详情仍由详情页自己的返回处理。
    BackHandler(
        enabled = currentTab == 0 && searchReturnTab != null &&
            !drawerOpen,
    ) {
        navigateToTab(searchReturnTab ?: 4)
        searchReturnTab = null
    }

    // 侧栏打开时的系统返回拦截
    if (drawerOpen) {
        BackHandler { closeDrawer() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val ensurePermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SourceAliasProvider {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CanvasBackground),
        ) {
        // --- 1. 左侧抽屉 Bento 便当卡片群（质感白卡 + 柔和阴影，高级质感） ---
        Column(
            modifier = Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = drawerOffset.value - drawerWidthPx
                }
                .drawerSwipeable(
                    drawerOffset = drawerOffset,
                    drawerWidthPx = drawerWidthPx,
                    drawerSpring = drawerSpring,
                    scope = scope,
                )
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (isCompactLandscape) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp else 36.dp,
                )
                .navigationBarsPadding()
                .padding(bottom = MiniPlayerHeight + 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompactLandscape) 8.dp else 12.dp),
        ) {
            // Bento Card 1: 顶部项目品牌卡片
            DrawerSurface(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isCompactLandscape) 14.dp else 18.dp,
                            vertical = if (isCompactLandscape) 12.dp else 18.dp,
                        ),
                ) {
                    Text(
                        text = "乐屿 · Melora",
                        fontSize = if (isCompactLandscape) 17.sp else 19.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextDark,
                    )
                    Text(
                        text = "沉浸式音乐与听书体验",
                        fontSize = if (isCompactLandscape) 11.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextGray,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // 同一份导航始终可滚动：卡片按内容包裹，剩余空白留在卡片外。
            // 横屏/大字体时以剩余高度为上限，不再把后面的入口挤到屏幕外。
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DrawerSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        tabs.forEachIndexed { index, item ->
                            DrawerItemEntry(
                                item = item,
                                selected = currentTab == index,
                                onClick = {
                                    searchReturnTab = null
                                    if (index == 7) {
                                        settingsNavTarget = null
                                        settingsNavSeq++
                                    }
                                    if (index == currentTab) {
                                        closeDrawer()
                                    } else {
                                        // 页面首次组合/布局先完成，不能让耗时吃掉收回动画的开头。
                                        pendingDrawerTab = index
                                        navigateToTab(index)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (MeloraSettings.showExitButton.collectAsStateWithLifecycle().value) {
                DrawerSurface(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                (context as? android.app.Activity)?.finishAffinity()
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "退出乐屿",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextDark,
                        )
                    }
                }
            }
        }

        // --- 2. 主页面（同一底色，向右平推让位，右划跟手展开侧栏） ---
        ChromeScaffold(
            containerColor = Color.Transparent,
            expectedTopBarHeight = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = drawerOffset.value
                }
                .drawerSwipeable(
                    drawerOffset = drawerOffset,
                    drawerWidthPx = drawerWidthPx,
                    drawerSpring = drawerSpring,
                    scope = scope,
                ),
            bottomBar = {
                if (hasActivePlayback) {
                    Spacer(
                        Modifier
                            .navigationBarsPadding()
                            .height(MiniPlayerHeight),
                    )
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalPageActive provides (!drawerOpen)) {
                    // 同级切换由抽屉收回提供唯一运动，不再叠加横移/交叉淡化。
                    key(currentTab) {
                        val visibleTab = currentTab
                        Box(Modifier.fillMaxSize().onGloballyPositioned {
                            if (pendingDrawerTab == visibleTab && currentTab == visibleTab) closeDrawer()
                        }) {
                        val primaryHeader: @Composable () -> Unit = {
                            // 顶栏：普通态（侧栏 + 标题 + 平台）⇄ 平台选择态（取消 + 五个平台下划线）
                            // 平台选择在栏内切换；整条主栏随所属页面一起参与导航过渡。
                            val isPlatformTab = visibleTab == 1 || visibleTab == 3
                            val picking = platformPickerOpen && isPlatformTab
                            PageBackHandler(enabled = picking) { platformPickerOpen = false }
                            TopAppBar(
                                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                                // 栏内材质由共享框架统一处理，文字和按钮始终保持清晰。
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = chromeHeaderColor()),
                                title = {
                                    if (picking) {
                                        val selectedId by (if (visibleTab == 1) MeloraSettings.leaderboardPlatform else MeloraSettings.playlistPlatform).collectAsStateWithLifecycle()
                                        val choices = if (visibleTab == 1) {
                                            boardPlatforms.map {
                                                PlatformChoice(it.id, sourceAliasDisplay(it.id, it.name), boardPlatformColors[it.id] ?: MeloraAppearance.brand)
                                            }
                                        } else {
                                            playlistPlatforms.map {
                                                PlatformChoice(it.id, sourceAliasDisplay(it.id, it.label), it.color)
                                            }
                                        }
                                        PlatformUnderlineRow(
                                            choices = choices,
                                            selectedId = selectedId,
                                            onSelect = { choice ->
                                                if (choice.id == selectedId) {
                                                    platformPickerOpen = false
                                                } else if (visibleTab == 1) {
                                                    MeloraSettings.updateLeaderboardPlatform(choice.id)
                                                    platformPickerOpen = false
                                                } else {
                                                    MeloraSettings.updatePlaylistPlatform(choice.id)
                                                    MeloraSettings.updatePlaylistTag("", "全部歌单")
                                                    platformPickerOpen = false
                                                }
                                            },
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(PrimaryTopBarHeight)
                                                .titleScrollToTop { primaryScrollToTopRequest++ },
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(
                                                tabs[visibleTab].label,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 19.sp,
                                                color = TextDark,
                                            )
                                        }
                                    }
                                },
                                navigationIcon = {
                                    if (!picking) {
                                        IconButton(onClick = {
                                            scope.launch {
                                                if (drawerOffset.value > 10f) {
                                                    drawerOffset.animateTo(0f, drawerSpring)
                                                } else {
                                                    drawerOffset.animateTo(drawerWidthPx, drawerSpring)
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Rounded.Menu, contentDescription = "打开侧栏", tint = TextDark)
                                        }
                                    }
                                },
                                actions = {
                                    if (!picking) when (visibleTab) {
                                        1 -> LeaderboardPlatformFilter(onOpenPlatformPicker = { platformPickerOpen = true })
                                        // 发现页顶栏不放搜索入口，保持版面清爽
                                        2 -> Unit
                                        // 歌单页顶栏：最热/最新（酷我）+ 分类筛选 + 平台入口
                                        3 -> PlaylistTopBarFilter(onOpenPlatformPicker = { platformPickerOpen = true })
                                        // 听书页搜索直达搜索页的“听书”分类，并保留返回来源。
                                        4 -> IconButton(onClick = {
                                            searchCategory = SearchCategory.Audiobook
                                            searchReturnTab = 4
                                            navigateToTab(0)
                                        }) {
                                            Icon(Icons.Outlined.Search, contentDescription = "搜索听书", tint = TextDark)
                                        }
                                        // 我的列表页顶栏不放搜索入口
                                        6 -> Unit
                                        else -> IconButton(onClick = {
                                            searchReturnTab = null
                                            navigateToTab(0)
                                        }) {
                                            Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = TextDark)
                                        }
                                    }
                                },
                            )
                        }
                        when (visibleTab) {
                            0 -> SearchScreen(
                                category = searchCategory,
                                onCategoryChange = { searchCategory = it },
                                onOpenDrawer = {
                                    scope.launch { drawerOffset.animateTo(drawerWidthPx, drawerSpring) }
                                },
                            )
                            1 -> LeaderboardScreen(primaryHeader = primaryHeader, scrollToTopRequest = primaryScrollToTopRequest)
                            2 -> DiscoverScreen(primaryHeader = primaryHeader, onNavigateToTab = ::navigateToTab, scrollToTopRequest = primaryScrollToTopRequest)
                            3 -> PlaylistsScreen(primaryHeader = primaryHeader, scrollToTopRequest = primaryScrollToTopRequest)
                            4 -> AudiobooksScreen(primaryHeader = primaryHeader, scrollToTopRequest = primaryScrollToTopRequest)
                            5 -> LocalSongsPage(
                                onOpenDrawer = {
                                    scope.launch { drawerOffset.animateTo(drawerWidthPx, drawerSpring) }
                                },
                            )
                            6 -> MyLibraryScreen(
                                onOpenDrawer = {
                                    scope.launch { drawerOffset.animateTo(drawerWidthPx, drawerSpring) }
                                },
                            )
                            7 -> SettingsMasterScreen(
                                onOpenDrawer = {
                                    scope.launch { drawerOffset.animateTo(drawerWidthPx, drawerSpring) }
                                },
                                requestedSubPage = settingsNavTarget,
                                requestSeq = settingsNavSeq,
                            )
                        }
                        }
                    }
                }
            }
        }

        // --- 3. 抽屉展开时覆盖在主页面上的点击遮罩 ---
        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = drawerOffset.value
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        closeDrawer()
                    }
                    .drawerSwipeable(
                        drawerOffset = drawerOffset,
                        drawerWidthPx = drawerWidthPx,
                        drawerSpring = drawerSpring,
                        scope = scope,
                    ),
            )
        }

        // --- 4. 全局消息浮层（平滑淡入浮出 + 语义图标 + 磨砂质感深色胶囊 + 点按快速关闭） ---
        AnimatedVisibility(
            visible = playerState.message != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (hasActivePlayback) 88.dp else 32.dp)
                .padding(horizontal = 24.dp),
        ) {
            val message = playerState.message.orEmpty()
            if (message.isNotBlank()) {
                val isDark = MeloraAppearance.isDark
                val isError = message.contains("失败") || message.contains("不可用") || message.contains("错误") || message.contains("无法")
                val isSuccess = message.contains("已") || message.contains("成功") || message.contains("完成")
                val isWarning = message.contains("超时") || message.contains("未获得") || message.contains("限制") || message.contains("失效")

                val icon = when {
                    isError -> Icons.Rounded.ErrorOutline
                    isSuccess -> Icons.Rounded.CheckCircle
                    isWarning -> Icons.Rounded.WarningAmber
                    message.contains("下载") -> Icons.Outlined.Download
                    else -> Icons.Outlined.Info
                }
                val iconTint = if (isDark) {
                    when {
                        isError -> Color(0xFFF87171) // 柔和珊红
                        isSuccess -> Color(0xFF34D399) // 翠玉绿
                        isWarning -> Color(0xFFFBBF24) // 琥珀金
                        else -> Color(0xFF60A5FA) // 晴空蓝
                    }
                } else {
                    when {
                        isError -> Color(0xFFDC2626) // 绯红
                        isSuccess -> Color(0xFF059669) // 祖母绿
                        isWarning -> Color(0xFFD97706) // 暖琥珀
                        else -> Color(0xFF2563EB) // 品牌钴蓝
                    }
                }

                // 浅色模式采用纯净浅色半透明（0xF2FFFFFF）+ 细微浅边；深色模式纯黑（0xFF000000）+ 细微亮边；shadowElevation 归零彻底干掉怪光晕
                val capsuleBg = if (isDark) Color(0xFF000000) else Color(0xF2FFFFFF)
                val capsuleBorder = if (isDark) Color(0x2BFFFFFF) else Color(0x14000000)
                val textColor = if (isDark) Color(0xFFF9FAFB) else Color(0xFF111827)

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = capsuleBg,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, capsuleBorder),
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { PlaybackController.consumeMessage() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // --- 5. 一体化持久播放层 ---
        if (hasActivePlayback) {
            ContinuousPlayerSheet(
                state = playerState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            SystemBarsAppearance(darkStatusIcons = androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() > 0.5f)
        }
        }
    }
}

// 排行榜顶栏右侧：平台筛选（替换原搜索入口）
private val boardPlatformColors = mapOf(
    "kw" to Color(0xFFFFB300),
    "kg" to Color(0xFF0091EA),
    "tx" to Color(0xFF00C853),
    "wy" to Color(0xFFE53935),
    "mg" to Color(0xFF8E24AA),
)

@Composable
private fun LeaderboardPlatformFilter(onOpenPlatformPicker: () -> Unit) {
    val currentId by MeloraSettings.leaderboardPlatform.collectAsStateWithLifecycle()
    val platform = boardPlatforms.firstOrNull { it.id == currentId } ?: boardPlatforms.first()
    val dotColor = boardPlatformColors[platform.id] ?: MeloraAppearance.brand

    Box(modifier = Modifier.padding(end = 8.dp)) {
        ChromeActionSurface(
            onClick = onOpenPlatformPicker,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(48.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.55f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(sourceAliasDisplay(platform.id, platform.name), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "切换平台",
                    tint = TextGray,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

// 侧栏列表项：精致微徽标设计
@Composable
private fun DrawerItemEntry(item: MainTabItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) DrawerSelectedFill else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (MeloraAppearance.isDark) {
                        item.color.copy(alpha = 0.22f)
                    } else {
                        item.color.copy(alpha = 0.12f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = if (MeloraAppearance.isDark) lerp(item.color, Color.White, 0.18f) else item.color,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MeloraAppearance.brand.copy(alpha = 0.75f) else TextDark,
        )
    }
}
