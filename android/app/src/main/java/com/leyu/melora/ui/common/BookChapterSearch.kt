package com.leyu.melora.ui.common

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal data class BookChapterMatch(val song: OnlineSong, val page: KwBookApi.BookChapters)

internal data class BookChapterSearchProgress(
    val matches: List<BookChapterMatch> = emptyList(),
    val scannedPages: Int = 0,
    val totalPages: Int? = null,
    val complete: Boolean = false,
)

/** 只读取目录，缓存按原始页复用；未到末页绝不把部分扫描当成整本无结果。 */
internal suspend fun searchBookChapters(
    query: String,
    pages: MutableMap<Int, KwBookApi.BookChapters>,
    load: suspend (Int) -> KwBookApi.BookChapters,
    onUpdate: (BookChapterSearchProgress) -> Unit,
) {
    val keyword = query.trim()
    if (keyword.isEmpty()) return
    val matchedPages = sortedMapOf<Int, List<BookChapterMatch>>()
    var totalPages: Int? = null
    fun collect(page: Int, data: KwBookApi.BookChapters) {
        matchedPages[page] = data.items.filter { it.name.contains(keyword, ignoreCase = true) }
            .map { BookChapterMatch(it, data) }
        BookCatalogPaging.pageCount(data.total)?.let { totalPages = it }
    }
    fun publish(complete: Boolean = false) = onUpdate(BookChapterSearchProgress(
        matches = matchedPages.values.flatten().distinctBy { it.song.uid },
        scannedPages = matchedPages.size,
        totalPages = totalPages,
        complete = complete,
    ))
    pages.toSortedMap().forEach { (page, data) -> collect(page, data) }
    currentCoroutineContext().ensureActive()
    publish()
    val seen = hashSetOf<String>()
    var page = 1
    while (true) {
        currentCoroutineContext().ensureActive()
        val data = pages[page] ?: load(page)
        currentCoroutineContext().ensureActive()
        // 章节接口的失败响应也是空页，不允许据此宣布“整本没找到”。
        check(data.items.isNotEmpty()) { "目录读取不完整，请重试" }
        if (data.items.all { it.uid in seen }) {
            pages.remove(page)
            error("目录返回了重复内容，请重试")
        }
        seen.addAll(data.items.map { it.uid })
        val accepted = data.copy(page = page)
        pages[page] = accepted
        collect(page, accepted)
        publish(complete = !data.hasMore)
        if (!data.hasMore) return
        page++
        yield() // 缓存整本目录时也允许取消，并让部分结果有机会绘制。
    }
}
