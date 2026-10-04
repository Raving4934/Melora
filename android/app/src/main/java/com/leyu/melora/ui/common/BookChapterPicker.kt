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

internal data class BookCatalogWindowState(
    val page: Int,
    val chapters: KwBookApi.BookChapters?,
    val requestGeneration: Long = 0L,
) {
    /** 同一窗口状态同时拥有可见页与请求代次，避免另建互相漂移的 gate/token。 */
    fun beginRequest(): Pair<BookCatalogWindowState, Long> {
        val nextGeneration = requestGeneration + 1
        return copy(requestGeneration = nextGeneration) to nextGeneration
    }

    fun isCurrentRequest(generation: Long): Boolean = generation == requestGeneration

    /** 仅新鲜且非空的当前响应可替换页窗口；空/旧响应保留原页与位置。 */
    fun accept(
        generation: Long,
        requestedPage: Int,
        response: KwBookApi.BookChapters,
    ): BookCatalogWindowState {
        if (!isCurrentRequest(generation) || response.items.isEmpty()) return this
        val page = requestedPage.coerceAtLeast(1)
        return copy(page = page, chapters = response.copy(page = page))
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
    val rangeLabel = if (end != null) "第 $start–$end 章" else "从第 $start 章开始"
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
    currentOrdinal: Int?,
    descending: Boolean,
    onDismiss: () -> Unit,
    onSelectOrdinal: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    var ordinalText by rememberSaveable(total, currentOrdinal) {
        mutableStateOf(currentOrdinal?.toString().orEmpty())
    }
    val validOrdinal = ordinalText.toIntOrNull()?.takeIf { it > 0 && (total == null || it <= total) }
    val pageCount = BookCatalogPaging.pageCount(total) ?: 0
    val select: (Int) -> Unit = { value -> closeSheet { onSelectOrdinal(value); onDismiss() } }

    MeloraBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanvasBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)
            .testTag("book-chapter-picker")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选集", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                total?.let { Text("共 $it 章", fontSize = 13.sp, color = TextMuted) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = ordinalText,
                    onValueChange = { value -> ordinalText = value.filter { it.isDigit() }.take(9) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("输入章节序号", fontSize = 14.sp) },
                    leadingIcon = { Text("第", color = TextSub) }, trailingIcon = { Text("章", color = TextSub) },
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
                    keyboardActions = KeyboardActions(onGo = { validOrdinal?.let(select) }),
                    isError = ordinalText.isNotEmpty() && validOrdinal == null,
                )
                Button(onClick = { validOrdinal?.let(select) }, enabled = validOrdinal != null,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White)) {
                    Text("定位", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (pageCount > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("章节分段", Modifier.weight(1f), fontSize = 13.sp, color = TextSub)
                    Text(if (descending) "倒序" else "每 100 章", fontSize = 12.sp, color = TextMuted)
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(100.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pageCount, key = { index -> if (descending) pageCount - index else index + 1 }) { index ->
                        val page = if (descending) pageCount - index else index + 1
                        val start = BookCatalogPaging.rangeStart(page)
                        val end = BookCatalogPaging.rangeEnd(page, total) ?: start
                        val selected = page == BookCatalogPaging.pageForOrdinal(currentOrdinal ?: 0)
                        Surface(onClick = { select(if (descending) end else start) },
                            color = if (selected) BrandBlue.copy(alpha = 0.1f) else MeloraAppearance.softFill,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                                .semantics { contentDescription = "第 $start–$end 章"; this.selected = selected }) {
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
