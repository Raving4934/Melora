package com.leyu.melora.playback

/** 本地文件中的兼容 LRC 与保真 TTML 双表示歌词。 */
data class EmbeddedLyrics(val plain: String = "", val ttml: String = "") {
    val isBlank: Boolean get() = plain.isBlank() && ttml.isBlank()

    /** TTML 是保真来源；无效或不含可解析歌词时回退到兼容 LRC。 */
    fun parse(): List<LyricLine> {
        val xml = ttml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val rich = xml.takeIf { it.startsWith('<') }?.let { LyricParser.parse(it) }.orEmpty()
        return rich.ifEmpty { LyricParser.parse(plain) }
    }

    companion object {
        const val TTML_FIELD = "LYRICS_TTML"

        /** 从 LyricParser 的规范化行模型生成普通 LRC 底稿，并在需要时附带保真 TTML。 */
        fun fromLines(lines: List<LyricLine>): EmbeddedLyrics? {
            if (lines.isEmpty()) return null
            val normalized = lines.withIndex()
                .map { (index, line) -> index to normalize(line) }
                .sortedWith(compareBy<Pair<Int, LyricLine>> { it.second.startMs }.thenBy { it.first })
                .map { it.second }
                .filterNot { it.text.isBlank() && it.translation.isNullOrBlank() && it.romanization.isNullOrBlank() }
            if (normalized.isEmpty()) return null

            val plain = buildString {
                normalized.forEach { line ->
                    if (line.text.isBlank()) return@forEach
                    val timestamp = "[${formatLrcTime(line.startMs)}]"
                    line.text.split('\n').forEach { part ->
                        append(timestamp).append(part).append('\n')
                    }
                }
            }.trimEnd('\n')

            val plainLines = LyricParser.parse(plain)
            val needsTtml = plainLines != normalized || normalized.any { line ->
                line.words.isNotEmpty() || line.translation != null || line.romanization != null ||
                    line.endMs != null || line.alignment != LyricAlignment.Start || line.isBackground
            }
            if (!needsTtml) return EmbeddedLyrics(plain = plain)

            val ttml = serializeTtml(normalized)
            if (LyricParser.parse(ttml) != normalized) {
                // rich 不可无损读回时仍保留可解析的普通底稿；无 plain 歌词可保留时才返回 null。
                return EmbeddedLyrics(plain = plain).takeIf { plainLines.isNotEmpty() }
            }
            return EmbeddedLyrics(plain = plain, ttml = ttml)
        }

        private fun normalize(line: LyricLine): LyricLine {
            val text = normalizeText(line.text).trim()
            val words = line.words.mapIndexed { index, word ->
                val wordText = normalizeText(word.text).let {
                    when {
                        line.words.size == 1 -> it.trim()
                        index == 0 -> it.trimStart()
                        index == line.words.lastIndex -> it.trimEnd()
                        else -> it
                    }
                }
                word.copy(text = wordText)
            }
            val normalizedText = if (text.isBlank() && words.isNotEmpty()) words.joinToString("") { it.text } else text
            return line.copy(
                text = normalizedText,
                translation = line.translation?.let(::normalizeText)?.trim()?.takeIf(String::isNotEmpty),
                romanization = line.romanization?.let(::normalizeText)?.trim()?.takeIf(String::isNotEmpty),
                endMs = line.endMs ?: words.maxOfOrNull { it.endMs },
                words = words,
            )
        }

        private fun serializeTtml(lines: List<LyricLine>): String = buildString {
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\" ")
            append("xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")
            append("xmlns:tts=\"http://www.w3.org/ns/ttml#styling\">")
            append("<body><div>")
            lines.forEach { line ->
                append("<p begin=\"").append(line.startMs).append("ms\"")
                line.endMs?.let { append(" end=\"").append(it).append("ms\"") }
                if (line.alignment == LyricAlignment.End) append(" tts:textAlign=\"end\"")
                if (line.isBackground) append(" ttm:role=\"x-bg\"")
                append('>')
                if (line.words.isNotEmpty() && line.words.joinToString("") { it.text } == line.text) {
                    line.words.forEach { word ->
                        append("<span begin=\"").append(word.startMs).append("ms\" end=\"")
                            .append(word.endMs).append("ms\">")
                            .append(escapeXml(word.text)).append("</span>")
                    }
                } else {
                    append(escapeXml(line.text))
                }
                line.translation?.let { append("<span ttm:role=\"x-translation\">").append(escapeXml(it)).append("</span>") }
                line.romanization?.let { append("<span ttm:role=\"x-roman\">").append(escapeXml(it)).append("</span>") }
                append("</p>")
            }
            append("</div></body></tt>")
        }

        private fun normalizeText(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

        private fun formatLrcTime(value: Long): String {
            val safe = value.coerceAtLeast(0L)
            val minutes = safe / 60_000L
            val seconds = safe / 1_000L % 60L
            val millis = safe % 1_000L
            return "%02d:%02d.%03d".format(java.util.Locale.ROOT, minutes, seconds, millis)
        }

        private fun escapeXml(value: String): String = buildString(value.length) {
            value.forEach { char ->
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '\"' -> "&quot;"
                        '\'' -> "&apos;"
                        else -> char.toString()
                    },
                )
            }
        }
    }
}
