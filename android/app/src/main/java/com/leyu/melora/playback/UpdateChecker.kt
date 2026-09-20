package com.leyu.melora.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.TimeUnit

data class UpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val pageUrl: String,
    val message: String? = null,
)

internal data class AndroidRelease(
    val tagName: String,
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val pageUrl: String,
)

object UpdateChecker {
    private const val GITHUB_REPO = "Raving4934/Melora"
    private const val GITHUB_API_ORIGIN = "https://api.github.com"
    private const val RELEASES_PATH = "/repos/$GITHUB_REPO/releases"
    private const val RELEASE_PAGE_SIZE = 100
    private const val CHECK_TIMEOUT_MS = 30_000L
    private const val TIMEOUT_MESSAGE = "检查超时，请稍后重试。"
    // 每页读取 100 条；若 NAS Release 超过一页，依赖 GitHub Link rel=next 继续查找，后续仍可改用 tags endpoint。
    private const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO/releases"
    private val androidTagPattern = Regex("^android-v(\\d+(?:\\.\\d+)*)$")
    private val repositoryReleasePathPattern = Regex("^/repositories/\\d+/releases$")
    private val nextLinkPattern = Regex("<([^>]+)>\\s*;\\s*rel=\"([^\"]+)\"")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHECK_TIMEOUT_MS)
            runCatching {
                var page = 1
                var exhausted = false
                var latest: AndroidRelease? = null
                var failureMessage: String? = null
                val visitedPages = mutableSetOf<Int>()

                while (!exhausted && visitedPages.add(page)) {
                    currentCoroutineContext().ensureActive()
                    val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                    if (remainingMillis <= 0L) {
                        return@runCatching noUpdate(currentVersion, TIMEOUT_MESSAGE)
                    }

                    val request = Request.Builder()
                        .url(releasePageUrl(page))
                        .header("User-Agent", "Melora-App/$currentVersion")
                        .header("Accept", "application/vnd.github+json")
                        .build()
                    val call = client.newCall(request)
                    call.timeout().timeout(remainingMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

                    call.execute().use { response ->
                        when {
                            response.code == 404 -> exhausted = true
                            !response.isSuccessful -> {
                                failureMessage = "检查失败 (HTTP ${response.code})"
                                exhausted = true
                            }
                            else -> {
                                val releases = JSONArray(response.body.string())
                                val pageLatest = selectAndroidRelease(releases)
                                if (pageLatest != null) {
                                    // GitHub 按发布时间倒序返回 Release；找到本页最新 Android 版本即可停止。
                                    latest = pageLatest
                                    exhausted = true
                                } else {
                                    val nextPage = nextPageFromLink(response.header("Link"), page)
                                    if (nextPage == null) exhausted = true else page = nextPage
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                }

                when {
                    failureMessage != null -> noUpdate(currentVersion, failureMessage)
                    latest == null -> noUpdate(currentVersion, "尚未找到Android发布版本")
                    else -> {
                        val versionComparison = compareVersions(latest.version, currentVersion)
                        UpdateResult(
                            hasUpdate = versionComparison > 0,
                            latestVersion = latest.tagName,
                            currentVersion = currentVersion,
                            releaseNotes = latest.releaseNotes,
                            downloadUrl = latest.downloadUrl,
                            pageUrl = latest.pageUrl,
                            message = if (versionComparison < 0) {
                                "当前安装版本高于 ${latest.tagName}，不会自动降级。"
                            } else {
                                null
                            },
                        )
                    }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (System.nanoTime() >= deadlineNanos) {
                    noUpdate(currentVersion, TIMEOUT_MESSAGE)
                } else {
                    noUpdate(currentVersion, error.message ?: "网络连接异常")
                }
            }
        } ?: noUpdate(currentVersion, TIMEOUT_MESSAGE)
    }

    /** 只构造当前仓库的 Release URL；Link 头只提供下一页页码，不直接作为请求目标。 */
    private fun releasePageUrl(page: Int): String =
        "$GITHUB_API_ORIGIN$RELEASES_PATH?per_page=$RELEASE_PAGE_SIZE&page=$page"

    /** 仅接受 GitHub API 的 releases rel=next；只取页码并重建当前仓库 URL，拒绝外部跳转。 */
    internal fun nextPageFromLink(linkHeader: String?, currentPage: Int): Int? {
        if (linkHeader.isNullOrBlank()) return null
        val nextUrl = nextLinkPattern.findAll(linkHeader)
            .firstOrNull { match -> match.groupValues[2].split(' ').contains("next") }
            ?.groupValues
            ?.get(1)
            ?: return null
        val uri = runCatching { URI(nextUrl) }.getOrNull() ?: return null
        val isCurrentReleasePath = uri.path == RELEASES_PATH ||
            repositoryReleasePathPattern.matches(uri.path.orEmpty())
        if (
            uri.scheme != "https" ||
            uri.host != "api.github.com" ||
            uri.port !in listOf(-1, 443) ||
            uri.userInfo != null ||
            !isCurrentReleasePath
        ) return null

        val page = uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) null else pair.substring(0, separator) to pair.substring(separator + 1)
            }
            .firstOrNull { it.first == "page" }
            ?.second
            ?.toIntOrNull()
            ?: return null
        return page.takeIf { it > currentPage }
    }

    /**
     * 只选择稳定的 Android 发布：标签必须是 android-v<数字版本>，并且至少有一个 APK 资产。
     * FPK 或没有直接下载地址的资产不会被当作可更新版本。
     */
    internal fun selectAndroidRelease(releases: JSONArray): AndroidRelease? {
        var selected: AndroidRelease? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue

            val tagName = release.optString("tag_name").trim()
            val version = androidTagPattern.matchEntire(tagName)?.groupValues?.get(1) ?: continue
            val downloadUrl = findApkUrl(release.optJSONArray("assets")) ?: continue
            val candidate = AndroidRelease(
                tagName = tagName,
                version = version,
                releaseNotes = release.optString("body").trim().ifBlank { "暂无更新说明" },
                downloadUrl = downloadUrl,
                pageUrl = release.optString("html_url").trim().ifBlank { RELEASES_PAGE_URL },
            )

            if (selected == null || compareVersions(candidate.version, selected.version) > 0) {
                selected = candidate
            }
        }
        return selected
    }

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val maxLength = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxLength) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        return 0
    }

    internal fun isVersionNewer(latest: String, current: String): Boolean =
        compareVersions(latest, current) > 0

    private fun findApkUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url").trim()
            val urlPath = url.substringBefore('?').substringBefore('#')
            if (url.isBlank() || urlPath.endsWith(".fpk", ignoreCase = true)) continue
            return url
        }
        return null
    }

    private fun versionParts(value: String): List<Int> =
        Regex("\\d+")
            .findAll(value)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()

    private fun noUpdate(currentVersion: String, message: String): UpdateResult = UpdateResult(
        hasUpdate = false,
        latestVersion = currentVersion,
        currentVersion = currentVersion,
        releaseNotes = "",
        downloadUrl = "",
        pageUrl = RELEASES_PAGE_URL,
        message = message,
    )
}
