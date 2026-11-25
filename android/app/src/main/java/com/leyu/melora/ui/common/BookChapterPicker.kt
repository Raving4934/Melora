package com.leyu.melora.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.sdk.BOOK_CATALOG_PAGE_SIZE
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal object BookCatalogPaging {
    const val PAGE_SIZE = BOOK_CATALOG_PAGE_SIZE

    fun pageForOrdinal(ordinal: Int): Int? =
        if (ordinal < 1) null else ((ordinal - 1) / PAGE_SIZE) + 1

    fun pageCount(total: Int?): Int? = total
        ?.takeIf { it > 0 }
        ?.let { ((it.toLong() + PAGE_SIZE - 1) / PAGE_SIZE).toInt() }

    fun rangeStart(page: Int): Int = (page.coerceAtLeast(1) - 1) * PAGE_SIZE + 1

    fun rangeEnd(page: Int, total: Int?): Int? {
        val pages = pageCount(total) ?: return null
        if (page > pages) return null
        val chapterCount = total ?: return null
        return minOf(page.coerceIn(1, pages).toLong() * PAGE_SIZE, chapterCount.toLong()).toInt()
    }

    /** 目录显示页映射到底层正序 API 页；未知总数时拒绝伪造倒序起点。 */
    fun pageForDirection(displayPage: Int, total: Int?, descending: Boolean): Int? {
        val page = displayPage.coerceAtLeast(1)
        if (!descending) return page
        val pages = pageCount(total) ?: return null
        return (pages - page + 1).coerceIn(1, pages)
    }


}

internal fun bookCatalogPageCacheKey(detailCacheKey: String, page: Int): String =
    "$detailCacheKey.window.${page.coerceAtLeast(1)}"

/** 保存连续的原始页；展示时按 UID 去重，单页缓存与播放续页契约不变。 */
internal data class BookCatalogState(
    val pages: Map<Int, KwBookApi.BookChapters> = emptyMap(),
    val requestGeneration: Long = 0L,
) {
    val items get() = pages.toSortedMap().values.flatMap { it.items }.distinctBy { it.uid }

    fun nextPage(descending: Boolean): Int? = if (descending) {
        pages.keys.minOrNull()?.minus(1)?.takeIf { it > 0 }
    } else {
        pages.maxByOrNull { it.key }?.takeIf { it.value.hasMore }?.key?.plus(1)
    }

    fun beginRequest(): Pair<BookCatalogState, Long> {
        val next = copy(requestGeneration = requestGeneration + 1)
        return next to next.requestGeneration
    }

    fun isCurrentRequest(generation: Long): Boolean = generation == requestGeneration

    fun accept(
        generation: Long,
        requestedPage: Int,
        response: KwBookApi.BookChapters,
        append: Boolean = false,
    ): BookCatalogState {
        if (!isCurrentRequest(generation) || response.items.isEmpty()) return this
        if (append && requestedPage !in pages &&
            requestedPage != nextPage(false) && requestedPage != nextPage(true)) return this
        val retained = if (append) pages else emptyMap()
        // 接口重复返回上一页不能无限续载；不丢掉已经显示的行。
        val existingUids = retained.filterKeys { it != requestedPage }.values
            .flatMap { it.items }.mapTo(hashSetOf()) { it.uid }
        if (append && response.items.all { it.uid in existingUids }) return this
        return copy(pages = retained + (requestedPage to response.copy(page = requestedPage)))
    }
}

private val markedBookEpisode = Regex("第\\s*([0-9０-９零〇一二三四五六七八九十百千万两]+)\\s*[集章回]")
private val leadingBookEpisode = Regex("^\\s*([0-9０-９]{1,6})(?:[集章回]|[\\s.、:：_\\-])")

/** 集号来自标题明确标记；目录位置、track序号、书名中的数字都不能替代集号。 */
internal fun bookEpisodeNumber(title: String): Int? {
    val text = (markedBookEpisode.find(title) ?: leadingBookEpisode.find(title))
        ?.groupValues?.get(1) ?: return null
    val digits = "零一二三四五六七八九"
    val normalized = text.map { char ->
        when {
            char in '０'..'９' -> ('0'.code + char.code - '０'.code).toChar()
            char == '〇' -> '零'
            char == '两' -> '二'
            else -> char
        }
    }.joinToString("")
    normalized.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
    if (normalized.all { it in digits }) {
        return normalized.map { digits.indexOf(it) }.joinToString("").toIntOrNull()?.takeIf { it > 0 }
    }
    var result = 0
    var section = 0
    var number = 0
    for (char in normalized) {
        val digit = digits.indexOf(char)
        if (digit >= 0) number = digit else when (char) {
            '十', '百', '千' -> {
                val unit = when (char) { '十' -> 10; '百' -> 100; else -> 1000 }
                section += (if (number == 0) 1 else number) * unit
                number = 0
            }
            '万' -> { result += (section + number) * 10000; section = 0; number = 0 }
            else -> return null
        }
    }
    return (result + section + number).takeIf { it > 0 }
}

/** 听书目录在共用播放操作行中插入选集与排序，不再另起一行。 */
@Composable
internal fun RowScope.BookChapterActions(
    page: Int,
    total: Int?,
    descending: Boolean,
    loading: Boolean,
    onPick: () -> Unit,
    onToggleDirection: () -> Unit,
    placeholder: Boolean = false,
) {
    val start = BookCatalogPaging.rangeStart(page)
    val end = BookCatalogPaging.rangeEnd(page, total)
    val rangeLabel = if (end != null) "目录 $start–$end" else "目录 $start 起"
    val canReverse = (descending || BookCatalogPaging.pageCount(total) != null) && !loading
    // 弹性空白留在按钮外，按压反馈只覆盖文字，不铺满到排序图标。
    Box(Modifier.weight(1f)) {
        ChromeActionSurface(onClick = onPick, enabled = !placeholder, shape = RoundedCornerShape(17.dp),
            modifier = Modifier.height(34.dp).testTag("book-chapter-picker-trigger")) {
            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                if (placeholder) ShimmerTextLine(rangeLabel, fontSize = 11.5.sp,
                    modifier = Modifier.width(IntrinsicSize.Max), fontWeight = FontWeight.Medium)
                else Text(rangeLabel, fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium, color = BrandBlue,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    ChromeActionSurface(onClick = onToggleDirection, enabled = canReverse && !placeholder,
        shape = RoundedCornerShape(17.dp), modifier = Modifier.size(34.dp)
            .semantics { stateDescription = if (descending) "倒序" else "正序" }) {
        Box(contentAlignment = Alignment.Center) {
            if (placeholder) ShimmerBox(Modifier.size(18.dp), cornerRadius = 5.dp)
            else Icon(if (descending) ChapterDescendingIcon else ChapterAscendingIcon,
                contentDescription = if (descending) "切换为正序" else "切换为倒序",
                tint = if (!canReverse) TextMuted else TextSub,
                modifier = Modifier.size(18.dp))
        }
    }
}

internal data class BookChapterQuery(val keyword: String, val isEpisode: Boolean, val episode: Int?)

private val explicitBookEpisodeQuery = Regex("(?:第\\s*)?[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[集章回]")

/** 只有纯数字或完整集号表达式才定位；含集号的长标题仍作为名称搜索。 */
internal fun parseBookChapterQuery(raw: String): BookChapterQuery {
    val keyword = raw.trim()
    val digits = keyword.isNotEmpty() && keyword.all { it in '0'..'9' || it in '０'..'９' }
    val isEpisode = digits || explicitBookEpisodeQuery.matches(keyword)
    val episode = if (!isEpisode) null else bookEpisodeNumber(when {
        digits -> "第${keyword}集"
        keyword.startsWith("第") -> keyword
        else -> "第$keyword"
    })
    return BookChapterQuery(keyword, isEpisode, episode)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookChapterPicker(
    total: Int?,
    currentPage: Int,
    descending: Boolean,
    onDismiss: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    searchPages: () -> MutableMap<Int, KwBookApi.BookChapters>,
    loadSearchPage: suspend (Int) -> KwBookApi.BookChapters,
    onSelectChapter: (BookChapterMatch) -> Unit,
    onLocateCurrent: (() -> Unit)? = null,
) {
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val input = remember(query) { parseBookChapterQuery(query) }
    var forceTitleSearch by remember(query) { mutableStateOf(false) }
    val searchByTitle = input.keyword.isNotEmpty() && (!input.isEpisode || forceTitleSearch)
    var attempt by remember { mutableIntStateOf(0) }
    var stopped by remember(query, forceTitleSearch) { mutableStateOf(false) }
    var loading by remember(query, forceTitleSearch) { mutableStateOf(false) }
    var error by remember(query, forceTitleSearch) { mutableStateOf<String?>(null) }
    var progress by remember(query, forceTitleSearch) { mutableStateOf(BookChapterSearchProgress()) }
    LaunchedEffect(query, forceTitleSearch, attempt, stopped) {
        if (!searchByTitle || stopped) return@LaunchedEffect
        loading = true
        error = null
        progress = progress.copy(complete = false)
        try {
            delay(300)
            searchBookChapters(input.keyword, searchPages(), loadSearchPage) { progress = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "目录读取失败，请重试"
        } finally {
            loading = false
        }
    }
    val submit: () -> Unit = {
        val submitted = queryState.text.toString()
        if (submitted == query && searchByTitle) {
            stopped = false
            attempt++
        } else {
            parseBookChapterQuery(submitted).episode?.let { episode ->
                closeSheet { onSelectEpisode(episode); onDismiss() }
            }
        }
    }
    val pageCount = BookCatalogPaging.pageCount(total) ?: 0
    val invalidEpisode = !searchByTitle && input.isEpisode && input.episode == null
    val locateCurrent = input.keyword.isEmpty()
    val actionLabel = when {
        locateCurrent -> "定位当前"
        searchByTitle -> "搜索章节"
        else -> "定位集数"
    }

    MeloraBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MeloraAppearance.sheetCard) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val itemSpacing = 8.dp
            val itemHeight = maxOf(44.dp, with(density) { ChapterRangeStyle.lineHeight.toDp() } + 20.dp)
            val lastRange = "${BookCatalogPaging.rangeStart(pageCount)}–${total ?: 0}"
            val rangeWidth = with(density) {
                textMeasurer.measure(lastRange, ChapterRangeStyle.copy(fontWeight = FontWeight.SemiBold)).size.width.toDp()
            }
            val cellWidth = maxOf(80.dp, rangeWidth + 18.dp)
            val columnCount = ((maxWidth + itemSpacing) / (cellWidth + itemSpacing)).toInt()
                .coerceIn(1, minOf(3, pageCount.coerceAtLeast(1)))
            val rows = ((pageCount + columnCount - 1) / columnCount).coerceIn(1, 5)
            val gridHeight = itemHeight * rows + itemSpacing * (rows - 1)
            val currentIndex = (if (descending) pageCount - currentPage else currentPage - 1)
                .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val firstRow = (currentIndex / columnCount - rows / 2).coerceAtLeast(0)
            val gridState = key(columnCount, pageCount, currentPage, descending) {
                rememberLazyGridState(initialFirstVisibleItemIndex = firstRow * columnCount)
            }
            val bodyHeight by animateDpAsState(if (searchByTitle) 260.dp else gridHeight,
                tween(220), label = "chapterPickerContentHeight")
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("book-chapter-picker")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", Modifier.semantics { heading() }, fontSize = 21.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold, color = TextMain)
                    total?.let {
                        Spacer(Modifier.weight(1f))
                        Text("共 $it 集", fontSize = 12.sp, lineHeight = 16.sp, color = TextSub,
                            style = TextStyle(fontFeatureSettings = "tnum"), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                BookChapterSearchBar(
                    query = query,
                    state = queryState,
                    actionLabel = actionLabel,
                    searchByTitle = searchByTitle,
                    actionEnabled = if (locateCurrent) onLocateCurrent != null else searchByTitle || input.episode != null,
                    invalidEpisode = invalidEpisode,
                    onSubmit = submit,
                    onAction = {
                        if (locateCurrent) {
                            onLocateCurrent?.let { locate ->
                                closeSheet {
                                    onDismiss()
                                    locate()
                                }
                            }
                        } else {
                            submit()
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                val statusHeight = maxOf(44.dp, with(density) { 18.sp.toDp() } + 12.dp)
                Box(Modifier.fillMaxWidth().height(statusHeight)) {
                    Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        val status = when {
                            !searchByTitle -> "目录分段"
                            error != null -> error!!
                            progress.complete -> if (progress.matches.isEmpty()) "未找到匹配章节" else "找到 ${progress.matches.size} 条"
                            stopped -> "已停止 · 已查 ${progress.scannedPages} 页"
                            else -> "已查 ${progress.scannedPages}${progress.totalPages?.let { " / $it" }.orEmpty()} 页 · 找到 ${progress.matches.size} 条"
                        }
                        Text(status, Modifier.weight(1f).testTag("book-chapter-search-status"), fontSize = 12.sp, lineHeight = 17.sp,
                            fontWeight = if (searchByTitle) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (error != null) AccentRed else TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            searchByTitle && !progress.complete -> TextButton(onClick = {
                                if (loading) stopped = true else { stopped = false; attempt++ }
                            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 24.dp)) {
                                Text(if (loading) "停止" else "继续查找", fontSize = 11.5.sp, lineHeight = 16.sp, color = BrandBlue)
                            }
                            !searchByTitle && input.isEpisode -> TextButton(onClick = { forceTitleSearch = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 24.dp)) {
                                Text("按章节名查找", fontSize = 11.5.sp, lineHeight = 16.sp, color = BrandBlue)
                            }
                            !searchByTitle && pageCount > 0 -> Text(
                                if (descending) "倒序" else "每段 ${BookCatalogPaging.PAGE_SIZE} 集",
                                fontSize = 11.5.sp, lineHeight = 16.sp, color = TextSub, maxLines = 1,
                            )
                        }
                    }
                    if (searchByTitle && loading) {
                        val totalPages = progress.totalPages
                        val indicator = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).align(Alignment.BottomCenter)
                        if (totalPages != null) LinearProgressIndicator(
                            progress = { (progress.scannedPages.toFloat() / totalPages).coerceIn(0f, 1f) },
                            modifier = indicator, color = BrandBlue, trackColor = MeloraAppearance.softFill,
                        ) else LinearProgressIndicator(modifier = indicator, color = BrandBlue, trackColor = MeloraAppearance.softFill)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (searchByTitle) {
                    Box(Modifier.fillMaxWidth().height(bodyHeight).testTag("book-picker-body")) {
                        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().testTag("book-chapter-search-results")) {
                            items(progress.matches, key = { it.song.uid }) { match ->
                                Surface(onClick = { closeSheet { onSelectChapter(match); onDismiss() } }, color = Color.Transparent,
                                    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    val title = match.song.name
                                    val highlighted = buildAnnotatedString {
                                        var cursor = 0
                                        while (true) {
                                            val start = title.indexOf(input.keyword, cursor, ignoreCase = true)
                                            if (start < 0) break
                                            append(title.substring(cursor, start))
                                            withStyle(SpanStyle(color = BrandBlue, fontWeight = FontWeight.SemiBold)) {
                                                append(title.substring(start, start + input.keyword.length))
                                            }
                                            cursor = start + input.keyword.length
                                        }
                                        append(title.substring(cursor))
                                    }
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(highlighted, Modifier.weight(1f), fontSize = 13.5.sp, lineHeight = 19.sp,
                                            color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(.5.dp).background(MeloraAppearance.divider))
                            }
                        }
                    }
                } else if (pageCount == 0) {
                    Box(Modifier.fillMaxWidth().height(bodyHeight).testTag("book-picker-body"), contentAlignment = Alignment.CenterStart) {
                        Text("暂无目录分段", fontSize = 12.sp, color = TextSub)
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(columnCount),
                        state = gridState,
                        modifier = Modifier.fillMaxWidth().height(bodyHeight).testTag("book-picker-body"),
                        horizontalArrangement = Arrangement.spacedBy(itemSpacing), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                        items(pageCount, key = { index -> if (descending) pageCount - index else index + 1 }) { index ->
                            val page = if (descending) pageCount - index else index + 1
                            val start = BookCatalogPaging.rangeStart(page)
                            val end = BookCatalogPaging.rangeEnd(page, total) ?: start
                            val selected = page == currentPage
                            Surface(onClick = { closeSheet { onSelectPage(page); onDismiss() } },
                                color = if (selected) BrandBlue.copy(alpha = .08f) else MeloraAppearance.softFill.copy(alpha = .65f),
                                shape = RoundedCornerShape(12.dp),
                                border = if (selected) BorderStroke(1.dp, BrandBlue.copy(alpha = .36f)) else MeloraAppearance.cardBorder,
                                modifier = Modifier.fillMaxWidth().height(itemHeight)
                                    .semantics { contentDescription = "目录 $start–$end"; this.selected = selected }) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("$start–$end", Modifier.padding(horizontal = 8.dp),
                                        style = ChapterRangeStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) BrandBlue else TextMain)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ChapterRangeStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
private val ChapterActionStyle = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
private val ChapterInputStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
private const val ChapterSearchHint = "输入集数或章节名"

@Composable
private fun BookChapterSearchBar(
    query: String,
    state: TextFieldState,
    actionLabel: String,
    searchByTitle: Boolean,
    actionEnabled: Boolean,
    invalidEpisode: Boolean,
    onSubmit: () -> Unit,
    onAction: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val labelWidth = with(density) {
            maxOf(
                textMeasurer.measure("定位当前", ChapterActionStyle).size.width,
                textMeasurer.measure("定位集数", ChapterActionStyle).size.width,
                textMeasurer.measure("搜索章节", ChapterActionStyle).size.width,
            ).toDp()
        }
        val inputWidth = with(density) { textMeasurer.measure(ChapterSearchHint, ChapterInputStyle).size.width.toDp() }
        val controlHeight = maxOf(48.dp, with(density) { ChapterInputStyle.lineHeight.toDp() } + 16.dp)
        val actionWidth = labelWidth + 46.dp
        val gap = 8.dp
        val action: @Composable (Modifier) -> Unit = { modifier ->
            Surface(
                onClick = onAction,
                enabled = actionEnabled,
                color = if (actionEnabled) MeloraAppearance.tintBlue else MeloraAppearance.softFill,
                contentColor = if (actionEnabled) BrandBlue else TextMuted,
                shape = CircleShape,
                border = if (actionEnabled) BorderStroke(1.dp, BrandBlue.copy(alpha = .16f)) else MeloraAppearance.cardBorder,
                modifier = modifier.width(actionWidth).height(controlHeight).testTag("book-chapter-query-action")
                    .semantics { contentDescription = if (searchByTitle) "搜索章节名" else actionLabel },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (searchByTitle) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.MyLocation,
                        contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(actionLabel, style = ChapterActionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (maxWidth >= inputWidth + 46.dp + actionWidth + gap) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                BookChapterSearchField(query, state, invalidEpisode, onSubmit, controlHeight, Modifier.weight(1f))
                action(Modifier)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.End) {
                BookChapterSearchField(query, state, invalidEpisode, onSubmit, controlHeight, Modifier.fillMaxWidth())
                action(Modifier)
            }
        }
    }
}

@Composable
private fun BookChapterSearchField(
    query: String,
    state: TextFieldState,
    invalidEpisode: Boolean,
    onSubmit: () -> Unit,
    controlHeight: Dp,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = ChapterInputStyle.copy(color = TextMain),
        cursorBrush = SolidColor(BrandBlue),
        // 固定键盘类型，输入集数和中文标题时不会重启输入法。
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        onKeyboardAction = { onSubmit() },
        modifier = modifier.testTag("book-chapter-query").onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "集数或章节名" },
        decorator = { innerTextField ->
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MeloraAppearance.softFill,
                border = when {
                    invalidEpisode -> BorderStroke(1.dp, AccentRed)
                    focused -> BorderStroke(1.dp, BrandBlue.copy(alpha = .4f))
                    else -> MeloraAppearance.cardBorder
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = controlHeight).padding(start = 12.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSub, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(ChapterSearchHint, style = ChapterInputStyle, color = TextSub,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { state.clearText() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空选集输入", tint = TextSub, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
    )
}
