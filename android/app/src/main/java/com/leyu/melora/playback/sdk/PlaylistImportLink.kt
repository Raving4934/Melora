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
        private val linkPattern = Regex("(?i)https?://[^\\s<>\"'“”‘’、，。；！？）》】]+")
        private val id = "\\d+"

        private val hosts = mapOf(
            "kw" to setOf("kuwo.cn", "www.kuwo.cn", "m.kuwo.cn", "h5.kuwo.cn", "h5app.kuwo.cn"),
            "kg" to setOf("kugou.com", "www.kugou.com", "m.kugou.com", "t.kugou.com", "t1.kugou.com", "m3ws.kugou.com"),
            "tx" to setOf("y.qq.com", "i.y.qq.com", "c.y.qq.com"),
            "wy" to setOf("music.163.com", "y.music.163.com", "163cn.tv"),
            "mg" to setOf("music.migu.cn", "m.music.migu.cn", "h5.nf.migu.cn", "c.migu.cn"),
        )

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
            val source = hosts.entries.firstOrNull { host in it.value }?.key ?: return null
            val path = uri.rawPath.orEmpty()
            val target = buildString {
                append(path)
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
            val isPlaylist = when (source) {
                "kw" -> isKuwoPlaylist(path, target)
                "kg" -> isKugouPlaylist(path, target) || isKugouShortLink(host, path)
                "tx" -> isTencentPlaylist(path, target) || isTencentShortLink(host, path)
                "wy" -> isNeteasePlaylist(path, target) || isNeteaseShortLink(host, path)
                "mg" -> isMiguPlaylist(path, target) || isMiguShortLink(host, path)
                else -> false
            }
            return if (isPlaylist) PlaylistImportLink(source, value) else null
        }

        private fun isKuwoPlaylist(path: String, target: String): Boolean =
            Regex("/playlist(?:_detail)?/$id(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target) ||
                (path.endsWith("/bodian/collection.html", ignoreCase = true) &&
                    Regex("(?:^|[?&])playlistId=$id(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(target))

        private fun isKugouPlaylist(path: String, target: String): Boolean =
            path.matches(Regex("/share/[A-Za-z0-9_-]{8,}\\.html", RegexOption.IGNORE_CASE)) ||
            Regex("/(?:songlist|special/single)/[^/?#]+(?:/|\\.html|[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target) ||
                (path.contains("/share", ignoreCase = true) &&
                    Regex("(?:^|[?&])(?:chain|id|global_collection_id)=[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE).containsMatchIn(target))

        private fun isTencentPlaylist(path: String, target: String): Boolean =
            Regex("/playlist/$id(?:\\.html)?(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target) ||
                (path.contains("/share/details/taoge.html", ignoreCase = true) &&
                    Regex("(?:^|[?&])id=$id(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(target))

        private fun isNeteasePlaylist(path: String, target: String): Boolean =
            Regex("/playlist(?:/$id(?:/[^/?#]+)?)?(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target) &&
                Regex("(?:[?&#]|^)id=$id(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(target) ||
                Regex("/playlist/$id(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target)

        private fun isMiguPlaylist(path: String, target: String): Boolean =
            Regex("/v[35]/music/playlist/$id(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target) ||
                (path.endsWith("/playlist/index.html", ignoreCase = true) &&
                    Regex("(?:^|[?&])id=$id(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(target)) ||
                (target.contains("#/playlist", ignoreCase = true) &&
                    Regex("(?:[?&#]|^)playlistId=$id(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(target))

        private fun isKugouShortLink(host: String, path: String): Boolean =
            host in setOf("t.kugou.com", "t1.kugou.com") && path.matches(Regex("/[A-Za-z0-9_-]{5,40}/?"))

        private fun isTencentShortLink(host: String, path: String): Boolean =
            host == "c.y.qq.com" && path.equals("/base/fcgi-bin/u", ignoreCase = true)

        private fun isNeteaseShortLink(host: String, path: String): Boolean =
            host == "163cn.tv" && path.matches(Regex("/[A-Za-z0-9_-]{4,32}/?"))

        private fun isMiguShortLink(host: String, path: String): Boolean =
            host == "c.migu.cn" && path.matches(Regex("/[A-Za-z0-9_-]{4,16}/?"))

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
