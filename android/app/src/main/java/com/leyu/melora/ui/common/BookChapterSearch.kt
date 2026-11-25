package com.leyu.melora.ui.common

import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.bookCatalogPageCacheKey
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
    detailCacheKey: String? = null,
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
    scanBookChapterPages(load, pages, detailCacheKey) {
        collect(it.page, it)
        publish(complete = !it.hasMore)
        false
    }
}

/** 页码只是提示；定位按 UID 或标题集号准确匹配。 */
internal suspend fun findBookChapterPage(
    hintPage: Int,
    matches: (OnlineSong) -> Boolean,
    load: suspend (Int) -> KwBookApi.BookChapters,
): KwBookApi.BookChapters? {
    val hint = hintPage.coerceAtLeast(1)
    val hinted = load(hint).copy(page = hint)
    currentCoroutineContext().ensureActive()
    if (hinted.items.any(matches)) return hinted
    // 只定位一章时保留提示页和已见 UID；不用为一次跳转在内存保留整本目录。
    return scanBookChapterPages(load = { if (it == hint) hinted else load(it) }) { it.items.any(matches) }
}

private suspend fun scanBookChapterPages(
    load: suspend (Int) -> KwBookApi.BookChapters,
    pages: MutableMap<Int, KwBookApi.BookChapters>? = null,
    detailCacheKey: String? = null,
    stopAt: (KwBookApi.BookChapters) -> Boolean,
): KwBookApi.BookChapters? {
    val seen = hashSetOf<String>()
    var page = 1
    fun containsNewChapter(data: KwBookApi.BookChapters) = data.items.any { it.uid !in seen }
    fun validate(data: KwBookApi.BookChapters) {
        check(data.items.isNotEmpty()) { "目录读取不完整，请重试" }
        check(containsNewChapter(data)) { "目录返回了重复内容，请重试" }
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        // 章节接口的失败响应也是空页，不允许据此宣布“整本没找到”。
        val data = pages?.get(page)?.takeIf(::containsNewChapter) ?: run {
            pages?.remove(page)
            detailCacheKey?.let { key ->
                OnlineCache.refresh<KwBookApi.BookChapters>(
                    bookCatalogPageCacheKey(key, page), OnlineCache.CATALOG_TTL_MS,
                    cancelWhenUnobserved = true,
                    acceptCached = { it.page == page && containsNewChapter(it) },
                ) { load(page).copy(page = page).also(::validate) }
            } ?: load(page)
        }
        currentCoroutineContext().ensureActive()
        validate(data)
        data.items.forEach { seen.add(it.uid) }
        val accepted = data.takeIf { it.page == page } ?: data.copy(page = page)
        pages?.set(page, accepted)
        if (stopAt(accepted)) return accepted
        if (!data.hasMore) return null
        page++
        yield() // 缓存整本目录时也允许取消，并让部分结果有机会绘制。
    }
}
