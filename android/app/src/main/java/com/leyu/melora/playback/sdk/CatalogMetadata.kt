package com.leyu.melora.playback.sdk

import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class ArtistProfile(
    val image: String? = null,
    val aliases: List<String> = emptyList(),
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

data class AlbumProfile(
    val artist: String = "",
    val image: String? = null,
    val description: String = "",
    val releaseDate: String = "",
    val type: String = "",
    val trackCount: Int? = null,
    val company: String = "",
)

/** 公开音乐目录元数据：艺术家头像/作品量与专辑发行资料共用一次查询、一次缓存。 */
object CatalogMetadata {
    private data class Cached<T>(val value: T?)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun artist(name: String): ArtistProfile? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        val key = "catalog.artist.${normalized.lowercase(Locale.ROOT)}"
        return try {
            OnlineCache.refresh<Cached<ArtistProfile>>(key, OnlineCache.CATALOG_TTL_MS) {
                Cached(requestSearch(normalized, type = 100).let { artistProfileFromSearch(it, normalized) })
            }.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun album(title: String, artist: String?): AlbumProfile? {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return null
        val normalizedArtist = artist?.trim().orEmpty()
        val key = "catalog.album.${normalizedTitle.lowercase(Locale.ROOT)}|${normalizedArtist.lowercase(Locale.ROOT)}"
        return try {
            OnlineCache.refresh<Cached<AlbumProfile>>(key, OnlineCache.CATALOG_TTL_MS) {
                Cached(
                    requestSearch(normalizedTitle, type = 10)
                        .let { albumProfileFromSearch(it, normalizedTitle, normalizedArtist) },
                )
            }.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun requestSearch(keyword: String, type: Int): String = try {
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val request = Request.Builder()
                .url("https://music.163.com/api/search/get/web?s=$encoded&type=$type&offset=0&total=true&limit=8")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://music.163.com/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("目录信息查询失败：HTTP ${response.code}")
                response.body.string()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    }
}

internal fun artistProfileFromSearch(body: String, name: String): ArtistProfile? {
    val root = checkedSearchRoot(body)
    val artists = root.optJSONObject("result")?.optJSONArray("artists") ?: return null
    val candidates = (0 until artists.length()).mapNotNull(artists::optJSONObject)
    val matched = candidates.firstOrNull { exactCatalogName(it.optString("name"), name) }
        ?: candidates.firstOrNull { sameCatalogName(it.optString("name"), name) }
    if (matched != null) {
        return ArtistProfile(
            image = cleanUrl(matched.optString("picUrl")),
            aliases = stringList(matched.optJSONArray("alias") ?: matched.optJSONArray("alia")),
            songCount = matched.optInt("musicSize").takeIf { it > 0 },
            albumCount = matched.optInt("albumSize").takeIf { it > 0 },
        )
    }
    // 非同名结果只允许兜底头像，禁止把他人的作品数/专辑数展示给当前艺术家。
    return candidates.firstNotNullOfOrNull { cleanUrl(it.optString("picUrl")) }?.let { ArtistProfile(image = it) }
}

internal fun albumProfileFromSearch(body: String, title: String, artist: String): AlbumProfile? {
    val root = checkedSearchRoot(body)
    val albums = root.optJSONObject("result")?.optJSONArray("albums") ?: return null
    val candidates = (0 until albums.length()).mapNotNull(albums::optJSONObject)
    val exactTitleMatches = candidates.filter { exactCatalogName(it.optString("name"), title) }
    val titleMatches = exactTitleMatches.ifEmpty {
        candidates.filter { sameCatalogName(it.optString("name"), title) }
    }
    val selected = when {
        artist.isBlank() -> titleMatches.firstOrNull()
        else -> titleMatches.firstOrNull { albumArtistNames(it).any { candidate -> artistNamesOverlap(candidate, artist) } }
    } ?: return null
    return AlbumProfile(
        artist = albumArtistNames(selected).joinToString("、"),
        image = cleanUrl(selected.optString("picUrl")),
        description = cleanCatalogText(
            selected.optString("description").ifBlank { selected.optString("briefDesc") },
        ),
        releaseDate = selected.optLong("publishTime").takeIf { it > 0 }?.let(::catalogDate).orEmpty(),
        type = cleanCatalogText(selected.optString("type")),
        trackCount = selected.optInt("size").takeIf { it > 0 },
        company = cleanCatalogText(selected.optString("company")),
    )
}

private fun checkedSearchRoot(body: String): JSONObject {
    val root = JSONObject(body)
    if (root.has("code") && root.optInt("code") != 200) {
        throw IOException("目录信息查询失败：${root.optInt("code")}")
    }
    if (!root.has("result")) throw IOException("目录信息查询响应缺少结果")
    return root
}

private fun albumArtistNames(album: JSONObject): List<String> {
    val names = mutableListOf<String>()
    val primary = album.optJSONObject("artist")?.optString("name").orEmpty().trim()
    if (primary.isNotBlank()) names += primary
    val artists = album.optJSONArray("artists")
    if (artists != null) {
        for (i in 0 until artists.length()) {
            val name = artists.optJSONObject(i)?.optString("name").orEmpty().trim()
            if (name.isNotBlank()) names += name
        }
    }
    return names.distinct()
}

private fun artistNamesOverlap(left: String, right: String): Boolean {
    val leftNames = splitArtistNames(left).map(::catalogNameKey).filter(String::isNotBlank)
    val rightNames = splitArtistNames(right).map(::catalogNameKey).filter(String::isNotBlank)
    return leftNames.any { it in rightNames }
}

private fun splitArtistNames(value: String): List<String> =
    value.split('、', '/', ',', '，', '&').map(String::trim)

private fun exactCatalogName(left: String, right: String): Boolean =
    left.trim().equals(right.trim(), ignoreCase = true) && left.isNotBlank()

private fun sameCatalogName(left: String, right: String): Boolean =
    catalogNameKey(left) == catalogNameKey(right) && catalogNameKey(left).isNotBlank()

private fun catalogNameKey(value: String): String = buildString {
    value.lowercase(Locale.ROOT).forEach { char -> if (char.isLetterOrDigit()) append(char) }
}

private fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        cleanCatalogText(array.optString(index)).takeIf(String::isNotBlank)
    }.distinct()
}

private fun cleanUrl(value: String): String? =
    value.trim().takeIf { it.isNotBlank() && it != "null" }

private fun cleanCatalogText(value: String): String = value
    .replace(Regex("<[^>]*>"), "")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun catalogDate(timestampMs: Long): String =
    Instant.ofEpochMilli(timestampMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
