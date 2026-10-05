package com.leyu.melora.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import com.leyu.melora.ui.theme.MeloraAppearance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.sdk.BOOK_CATALOG_PAGE_SIZE
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.ensureActive

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

/** 页码只用于缩小查找范围，实际匹配由UID或标题集号决定；没有匹配就保留原位置。 */
internal suspend fun findBookChapterPage(
    hintPage: Int,
    matches: (OnlineSong) -> Boolean,
    load: suspend (Int) -> KwBookApi.BookChapters,
): KwBookApi.BookChapters? {
    val hint = hintPage.coerceAtLeast(1)
    val hinted = load(hint)
    if (hinted.items.any(matches)) return hinted.copy(page = hint)
    val seen = hashSetOf<String>()
    var page = 1
    while (true) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val result = if (page == hint) hinted else load(page)
        if (result.items.any(matches)) return result.copy(page = page)
        if (result.items.isEmpty() || !result.hasMore || result.items.all { it.uid in seen }) return null
        // 收齐该页ID，避免服务端反复返回同页导致无界查找。
        seen.addAll(result.items.map { it.uid })
        page++
    }
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    var query by rememberSaveable { mutableStateOf("") }
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
        if (searchByTitle) { stopped = false; attempt++ }
        else input.episode?.let { episode -> closeSheet { onSelectEpisode(episode); onDismiss() } }
    }
    val pageCount = BookCatalogPaging.pageCount(total) ?: 0
    val invalidEpisode = !searchByTitle && input.isEpisode && input.episode == null

    MeloraBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanvasBackground) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            val density = LocalDensity.current
            val itemHeight = maxOf(36.dp, with(density) { 18.sp.toDp() } + 8.dp)
            val itemSpacing = 7.dp
            val columnCount = minOf(pageCount.coerceAtLeast(1),
                ((maxWidth.value + itemSpacing.value) / (80 * density.fontScale.coerceAtLeast(1f) + itemSpacing.value)).toInt().coerceAtLeast(1))
            val rows = ((pageCount + columnCount - 1) / columnCount).coerceAtMost(5)
            val gridHeight = (rows * itemHeight.value + (rows - 1).coerceAtLeast(0) * itemSpacing.value).dp
            val bodyHeight by animateDpAsState(if (searchByTitle) 260.dp else gridHeight,
                tween(220), label = "chapterPickerContentHeight")
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("book-chapter-picker")) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    total?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("$it 条", fontSize = 12.sp, lineHeight = 16.sp, color = TextMuted)
                    }
                }
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    color = MeloraAppearance.softFill,
                    border = if (invalidEpisode) BorderStroke(1.dp, AccentRed) else MeloraAppearance.cardBorder) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                        .padding(start = 12.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("输入集数或章节名", fontSize = 13.5.sp, lineHeight = 18.sp, color = TextMuted)
                            BasicTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                textStyle = TextStyle(fontSize = 14.sp, color = TextMain, fontWeight = FontWeight.Medium),
                                cursorBrush = SolidColor(BrandBlue),
                                // 不随自动识别结果切换键盘，避免输入中途收起/重启输入法。
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }),
                                modifier = Modifier.fillMaxWidth().testTag("book-chapter-query")
                                    .semantics { contentDescription = "集数或章节名" },
                            )
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空选集输入", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                        val canSubmit = searchByTitle || input.episode != null
                        IconButton(onClick = submit, enabled = canSubmit,
                            modifier = Modifier.size(32.dp).testTag("book-chapter-query-action")) {
                            Icon(if (searchByTitle) Icons.Rounded.Search else Icons.Rounded.MyLocation,
                                contentDescription = if (searchByTitle) "搜索章节名" else "定位集数",
                                tint = if (canSubmit) TextSub else TextMuted.copy(alpha = .5f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 28.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val status = when {
                            !searchByTitle -> "目录分段"
                            error != null -> error!!
                            progress.complete -> if (progress.matches.isEmpty()) "未找到匹配章节" else "找到 ${progress.matches.size} 条"
                            stopped -> "已停止 · 已查 ${progress.scannedPages} 页"
                            else -> "已查 ${progress.scannedPages}${progress.totalPages?.let { " / $it" }.orEmpty()} 页 · 找到 ${progress.matches.size} 条"
                        }
                        Text(status, Modifier.weight(1f).testTag("book-chapter-search-status"), fontSize = 11.5.sp, lineHeight = 16.sp,
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
                            !searchByTitle -> Text(if (descending) "倒序" else "每 100 条", fontSize = 11.sp, lineHeight = 16.sp, color = TextMuted)
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
                Spacer(Modifier.height(2.dp))
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
                                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(highlighted, Modifier.weight(1f), fontSize = 13.5.sp, lineHeight = 19.sp,
                                            color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(.5.dp).background(MeloraAppearance.divider))
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(columnCount),
                        modifier = Modifier.fillMaxWidth().height(bodyHeight).testTag("book-picker-body"),
                        horizontalArrangement = Arrangement.spacedBy(itemSpacing), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                        items(pageCount, key = { index -> if (descending) pageCount - index else index + 1 }) { index ->
                            val page = if (descending) pageCount - index else index + 1
                            val start = BookCatalogPaging.rangeStart(page)
                            val end = BookCatalogPaging.rangeEnd(page, total) ?: start
                            val selected = page == currentPage
                            Surface(onClick = { closeSheet { onSelectPage(page); onDismiss() } },
                                color = if (selected) BrandBlue else MeloraAppearance.softFill, shape = RoundedCornerShape(8.dp),
                                border = if (selected) BorderStroke(.5.dp, BrandBlue) else MeloraAppearance.chipBorder,
                                modifier = Modifier.fillMaxWidth().height(itemHeight)
                                    .semantics { contentDescription = "目录 $start–$end"; this.selected = selected }) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("$start–$end", fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) Color.White else TextMain)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
