package com.leyu.melora.playback.sdk

import java.net.URI
import java.util.Locale

data class PlaylistImportLink(val source: String, val value: String) {
    val platformName: String
        get() = when (source) {
            "kw" -> "酷我音乐"
            "kg" -> "酷狗音乐"
            "tx" -> "QQ音乐"
            "wy" -> "网易云音乐"
            "mg" -> "咪咕音乐"
            else -> source
        }

    companion object {
        private val linkPattern = Regex("(?i)(?<!javascript:)https?://[^\\s<>\"'“”‘’、，。；！？）》】]+")
        private val id = "\\d+"

        fun parse(text: String): PlaylistImportLink {
            val links = linkPattern.findAll(text)
                .map { trimTrailingPunctuation(it.value) }
                .distinct()
                .mapNotNull(::parsePlaylistLink)
                .toList()
            require(links.isNotEmpty()) { "未找到支持的公开歌单链接，请检查分享内容。" }
            require(links.size == 1) { "分享内容中只能包含一个受支持的歌单链接。" }
            return links.single()
        }

        private fun parsePlaylistLink(value: String): PlaylistImportLink? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (!uri.isAbsolute || uri.rawUserInfo != null) return null
            if (uri.port != -1 && uri.port != if (scheme == "http") 80 else 443) return null

            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val source = sourceForHost(host) ?: return null
            val path = uri.rawPath.orEmpty()
            val isPlaylist = when (source) {
                "kw" -> isKuwoPlaylist(path, uri)
                "kg" -> isKugouPlaylist(host, path, uri)
                "tx" -> isTencentPlaylist(path, uri) || isTencentShortLink(host, path, uri)
                "wy" -> isNeteasePlaylist(path, uri) || isNeteaseShortLink(host, path)
                "mg" -> isMiguPlaylist(host, path, uri) || isMiguShortLink(host, path)
                else -> false
            }
            return if (isPlaylist) PlaylistImportLink(source, value) else null
        }

        private fun sourceForHost(host: String): String? = when {
            isHostInFamily(host, "kuwo.cn") -> "kw"
            isHostInFamily(host, "kugou.com") -> "kg"
            isHostInFamily(host, "y.qq.com") -> "tx"
            isHostInFamily(host, "music.163.com") || host == "163cn.tv" -> "wy"
            isHostInFamily(host, "music.migu.cn") || host == "h5.nf.migu.cn" || host == "c.migu.cn" -> "mg"
            else -> null
        }

        private fun isHostInFamily(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        private fun isKuwoPlaylist(path: String, uri: URI): Boolean =
            Regex("^/(?:(?:new)?h5app/)?playlist(?:_detail)?/$id/?$", RegexOption.IGNORE_CASE)
                .matches(path) ||
                (Regex("^/(?:m/)?bodian/collection\\.html$", RegexOption.IGNORE_CASE).matches(path) && hasNumericParam(uri, "playlistId"))

        private fun isKugouPlaylist(host: String, path: String, uri: URI): Boolean {
            if (Regex("^t\\d*\\.kugou\\.com$", RegexOption.IGNORE_CASE).matches(host) &&
                Regex("^/[A-Za-z0-9_-]{5,64}/?$").matches(path)
            ) return true
            if (host == "pc.service.kugou.com" &&
                Regex("^/(?:yueku/v\\d+/)?special/single/[A-Za-z0-9_-]+\\.html$", RegexOption.IGNORE_CASE).matches(path)
            ) return true

            if (Regex("^/songlist/gcid_[A-Za-z0-9_-]+(?:\\.html)?/?$", RegexOption.IGNORE_CASE).matches(path)) return true

            val special = Regex("^/yy/special/single/([A-Za-z0-9_-]+)\\.html$", RegexOption.IGNORE_CASE)
                .matchEntire(path)?.groupValues?.get(1)
            if (special != null) {
                if (special.matches(Regex(id))) return true
                if (special.matches(Regex("collection_[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE))) return true
                if (hasParam(uri, "encryp", "1")) return true
            }

            if (Regex("^/(?:yy/special/single|songlist)/collection_[A-Za-z0-9_-]+\\.html$", RegexOption.IGNORE_CASE)
                    .matches(path)
            ) return true

            if (Regex("^/share/[A-Za-z0-9_-]{8,}\\.html$", RegexOption.IGNORE_CASE).matches(path)) return true

            val chainSharePath = path.equals("/share", ignoreCase = true) ||
                path.equals("/share/", ignoreCase = true) ||
                path.equals("/share/index.php", ignoreCase = true) ||
                path.equals("/schain/transfer", ignoreCase = true)
            return chainSharePath && (listOf("chain", "global_collection_id")
                .any { hasParam(uri, it, "[A-Za-z0-9_-]+") } ||
                (path.startsWith("/share", ignoreCase = true) && hasParam(uri, "id", "[A-Za-z0-9_-]+")))
        }

        private fun isTencentPlaylist(path: String, uri: URI): Boolean {
            if (Regex("^/(?:n/(?:yqq|ryqq(?:_v2)?)/)?playlist/$id(?:\\.html)?/?$", RegexOption.IGNORE_CASE).matches(path)) return true

            val idSharePath = path.equals("/n/m/detail/taoge/index.html", ignoreCase = true) ||
                path.equals("/n3/other/pages/details/playlist.html", ignoreCase = true) ||
                path.equals("/musicmac/v6/playlist/detail.html", ignoreCase = true) ||
                path.equals("/n2/m/share/details/taoge.html", ignoreCase = true) ||
                path.equals("/n/m/share/details/taoge.html", ignoreCase = true) ||
                path.equals("/share/details/taoge.html", ignoreCase = true) ||
                path.equals("/taoge.html", ignoreCase = true)
            return idSharePath && hasNumericParam(uri, "id")
        }

        private fun isNeteasePlaylist(path: String, uri: URI): Boolean {
            if (Regex("^/playlist/$id(?:/[^/?#]+)?/?$", RegexOption.IGNORE_CASE).matches(path)) return true
            if ((path.equals("/playlist", ignoreCase = true) || path.equals("/m/playlist", ignoreCase = true)) &&
                hasNumericParam(uri, "id")
            ) return true

            val fragment = uri.rawFragment.orEmpty()
            if (Regex("^/?playlist/$id(?:/[^?#]*)?(?:[?#].*)?$", RegexOption.IGNORE_CASE).matches(fragment)) return true
            return Regex("^/?playlist(?:[/?]|$)", RegexOption.IGNORE_CASE).containsMatchIn(fragment) &&
                hasNumericParam(uri, "id")
        }

        private fun isMiguPlaylist(host: String, path: String, uri: URI): Boolean {
            if (Regex("^/v[35]/music/playlist/$id/?$", RegexOption.IGNORE_CASE).matches(path)) return true
            if (Regex("^/app/v\\d+/p/share/playlist/index\\.html$", RegexOption.IGNORE_CASE).matches(path) &&
                hasNumericParam(uri, "id")
            ) return true

            val fragment = uri.rawFragment.orEmpty()
            return isHostInFamily(host, "music.migu.cn") &&
                Regex("^/?playlist(?:[/?]|$)", RegexOption.IGNORE_CASE).containsMatchIn(fragment) &&
                hasNumericParam(uri, "playlistId")
        }

        private fun isTencentShortLink(host: String, path: String, uri: URI): Boolean =
            Regex("^c[^.]*\\.y\\.qq\\.com$", RegexOption.IGNORE_CASE).matches(host) &&
            path.equals("/base/fcgi-bin/u", ignoreCase = true) && !uri.rawQuery.isNullOrEmpty()

        private fun isNeteaseShortLink(host: String, path: String): Boolean =
            host == "163cn.tv" && path.matches(Regex("/[A-Za-z0-9_-]{4,32}/?"))

        private fun isMiguShortLink(host: String, path: String): Boolean =
            host == "c.migu.cn" && path.matches(Regex("/[A-Za-z0-9_-]{4,16}/?"))

        private fun hasNumericParam(uri: URI, name: String): Boolean = hasParam(uri, name, id)

        private fun hasParam(uri: URI, name: String, valuePattern: String): Boolean {
            val pattern = Regex(
                "(?:^|[?&#])${Regex.escape(name)}=$valuePattern(?:[&#]|$)",
                RegexOption.IGNORE_CASE,
            )
            return sequenceOf(uri.rawQuery, uri.rawFragment)
                .filterNotNull()
                .any { pattern.containsMatchIn(it) }
        }

        private fun trimTrailingPunctuation(raw: String): String {
            var value = raw
            while (value.isNotEmpty()) {
                val last = value.last()
                val unmatchedCloser = when (last) {
                    ')' -> value.count { it == ')' } > value.count { it == '(' }
                    ']' -> value.count { it == ']' } > value.count { it == '[' }
                    '}' -> value.count { it == '}' } > value.count { it == '{' }
                    else -> false
                }
                if (last in ".,;:!?，。；！？、…" || unmatchedCloser) value = value.dropLast(1) else break
            }
            return value
        }
    }
}
