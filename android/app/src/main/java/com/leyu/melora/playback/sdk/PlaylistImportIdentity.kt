package com.leyu.melora.playback.sdk

import java.net.URI

/** Comparison only. Never replace the persisted source URL with this key. */
internal data class PlaylistImportIdentity(val source: String, val kind: String, val id: String) {
    val isCanonical: Boolean get() = kind != "exact-url"
}

/**
 * Follows the bundled SDK's ID namespaces, without issuing network requests.
 * Short links, encrypted/detail-page aliases and ambiguous parameters stay exact URL keys.
 * The SDK currently returns songs/metadata, not its resolved ID; never infer one from songs/name.
 */
internal fun PlaylistImportLink.identity(): PlaylistImportIdentity {
    val fallback = PlaylistImportIdentity(source, "exact-url", value)
    if (runCatching { PlaylistImportLink.parse(value) }.getOrNull() != this) return fallback
    return runCatching {
        val uri = URI(value)
        val path = uri.rawPath.orEmpty()
        val fragment = uri.rawFragment.orEmpty()
        fun match(pattern: String, input: String = path) =
            Regex(pattern).find(input)?.groupValues?.get(1)
        fun param(name: String, query: String = uri.rawQuery.orEmpty()): String? {
            val values = Regex("(?:^|&)${Regex.escape(name)}=([^&#]*)(?=[&#]|$)")
                .findAll(query).map { it.groupValues[1] }.toList()
            // Even repeated equal IDs may be handled differently by the remote service.
            require(values.size <= 1)
            return values.singleOrNull()
        }
        fun numeric(name: String, query: String = uri.rawQuery.orEmpty()) =
            param(name, query)?.takeIf { it.matches(Regex("\\d+")) }
        fun key(kind: String, id: String?) = id?.let { PlaylistImportIdentity(source, kind, it) }
        when (source) {
            "tx" -> if (path.equals("/base/fcgi-bin/u", true)) null else key("playlist",
                match("(?:^|/)playlist/(\\d+)(?:\\.html)?/?$") ?: numeric("id"))
            "wy" -> if (uri.host.equals("163cn.tv", true)) null else key("playlist",
                match("(?:^|/)playlist/(\\d+)(?:/[^?#]*)?$")
                    ?: if (Regex("(?:^|/)playlist/?$").containsMatchIn(path)) numeric("id")
                    else match("^/?playlist/(\\d+)(?:/[^?#]*)?(?:[?#].*)?$", fragment)
                        ?: numeric("id", fragment.substringAfter('?', "")))
            "mg" -> if (uri.host.equals("c.migu.cn", true)) null else key("playlist",
                match("/music/playlist/(\\d+)/?$")
                    ?: if (path.endsWith("/playlist/index.html")) numeric("id") else numeric("playlistId", fragment.substringAfter('?', "")))
            "kw" -> {
                // The legacy SDK scans the whole URL, including nested query/fragment values.
                // Canonicalize only when that extraction agrees with the ordinary path/query.
                require(("/bodian/" in value) == ("/bodian/" in path))
                if ("/bodian/" in path) {
                    val id = numeric("playlistId")
                    val type = param("source")
                    require(match("playlistId=(\\d+)", value) == id)
                    require(match("source=(\\d+)", value) == type)
                    key("bodian:${type ?: "unspecified"}", id)
                } else {
                    val id = match("/playlist(?:_detail)?/(\\d+)/?$")
                    require(match("^.+/playlist(?:_detail)?/(\\d+)/?(?:\\?.*|&.*$|#.*$|$)", value) == id)
                    key("playlist", id)
                }
            }
            "kg" -> {
                if (Regex("^t\\d*\\.kugou\\.com$", RegexOption.IGNORE_CASE).matches(uri.host)) null
                else {
                    // SDK query keys are case-sensitive, bounded by '&', and decoded with
                    // decodeURIComponent (not form decoding: '+' is literal). Keep encoded,
                    // repeated or HTML-escaped inputs exact rather than guessing a lower ID.
                    require("&amp;" !in value)
                    fun kugouMatch(pattern: String) =
                        Regex(pattern, RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1)
                    fun kugouParam(name: String): String? {
                        val values = Regex("(?:^|&)${Regex.escape(name)}=([^&#]*)")
                            .findAll(uri.rawQuery.orEmpty()).map { it.groupValues[1] }.toList()
                        require(values.size <= 1)
                        return values.singleOrNull()?.takeIf { it.isNotEmpty() }?.also { require('%' !in it) }
                    }
                    val global = kugouParam("global_collection_id")
                        ?: Regex("/(collection_[A-Za-z0-9_-]+)\\.html$").find(path)?.groupValues?.get(1)
                    val detail = kugouMatch("/special/single/([A-Za-z0-9_-]+)\\.html$")
                    val gcid = kugouMatch("/songlist/(gcid_[A-Za-z0-9_-]+)(?:\\.html)?/?$")
                    when {
                        global != null -> {
                            require(global.matches(Regex("[A-Za-z0-9_-]+")))
                            key("global", global)
                        }
                        detail != null && detail.matches(Regex("\\d+")) && kugouParam("encryp") != "1" -> key("special", detail)
                        gcid != null -> key("gcid", gcid)
                        else -> {
                            val share = kugouMatch("/share/([A-Za-z0-9_-]+)\\.html$")?.takeUnless { it == "index" || it == "zlist" }
                            val chain = kugouParam("chain")
                                ?: (if (Regex("^/share(?:/index\\.php)?/?$").matches(path)) kugouParam("id") else null)
                                ?: share
                            key("chain", chain?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) })
                        }
                    }
                }
            }
            else -> null
        } ?: fallback
    }.getOrDefault(fallback)
}
