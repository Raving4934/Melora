package com.leyu.melora.ui.common

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookChapterPicker(
    total: Int?,
    currentPage: Int,
    descending: Boolean,
    onDismiss: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    var episodeText by rememberSaveable(currentPage) {
        mutableStateOf("")
    }
    val validEpisode = episodeText.toIntOrNull()?.takeIf { it > 0 }
    val pageCount = BookCatalogPaging.pageCount(total) ?: 0
    val selectEpisode: (Int) -> Unit = { value -> closeSheet { onSelectEpisode(value); onDismiss() } }

    MeloraBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanvasBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)
            .testTag("book-chapter-picker")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选集", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                total?.let { Text("共 $it 条", fontSize = 13.sp, color = TextMuted) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = episodeText,
                    onValueChange = { value -> episodeText = value.filter { it.isDigit() }.take(9) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("输入集数／章号", fontSize = 14.sp) },
                    leadingIcon = { Text("第", color = TextSub) }, trailingIcon = { Text("集", color = TextSub) },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MeloraAppearance.softFill,
                        unfocusedContainerColor = MeloraAppearance.softFill,
                        errorContainerColor = MeloraAppearance.softFill,
                        focusedTextColor = TextMain, unfocusedTextColor = TextMain, errorTextColor = AccentRed,
                        cursorColor = BrandBlue,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { validEpisode?.let(selectEpisode) }),
                    isError = episodeText.isNotEmpty() && validEpisode == null,
                )
                Button(onClick = { validEpisode?.let(selectEpisode) }, enabled = validEpisode != null,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White)) {
                    Text("定位", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (pageCount > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("目录分段", Modifier.weight(1f), fontSize = 13.sp, color = TextSub)
                    Text(if (descending) "倒序" else "每 100 条", fontSize = 12.sp, color = TextMuted)
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(100.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pageCount, key = { index -> if (descending) pageCount - index else index + 1 }) { index ->
                        val page = if (descending) pageCount - index else index + 1
                        val start = BookCatalogPaging.rangeStart(page)
                        val end = BookCatalogPaging.rangeEnd(page, total) ?: start
                        val selected = page == currentPage
                        Surface(onClick = { closeSheet { onSelectPage(page); onDismiss() } },
                            color = if (selected) BrandBlue.copy(alpha = 0.1f) else MeloraAppearance.softFill,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                                .semantics { contentDescription = "目录 $start–$end"; this.selected = selected }) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("$start–$end", fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) BrandBlue else TextMain)
                            }
                        }
                    }
                }
            }
        }
    }
}
