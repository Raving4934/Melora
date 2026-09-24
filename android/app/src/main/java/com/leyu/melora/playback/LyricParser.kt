package com.leyu.melora.playback

import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** 统一歌词解析入口：LRC、增强/LX 逐字歌词与 Apple/AMLL 风格 TTML。 */
object LyricParser {
    private val lrcTimeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(?:(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?|([+-]?\d+),([+-]?\d+))>""")
    private val offsetTag = Regex("""(?i)\[offset\s*:\s*([+-]?\d+)\s*]""")
    private val booleanValues = setOf("1", "true", "yes", "on")

    /**
     * 解析主歌词，并将可独立返回的翻译/罗马音按行关联。
     *
     * 翻译-only 行不会被丢弃：当没有唯一的主歌词行可关联时，结果会保留空 text 的行。
     */
    fun parse(raw: String, translation: String = "", romanization: String = "", wordByWord: String = ""): List<LyricLine> {
        val offset = findOffset(raw)
        val ordinary = parseDocument(raw, offset)
        val wordOffset = if (offsetTag.containsMatchIn(wordByWord)) findOffset(wordByWord) else offset
        val timed = parseDocument(wordByWord, wordOffset).filter { it.words.isNotEmpty() }
        // 只为同一句补全时间轴；逐字轨缺行/损坏/与正文不符，不能覆盖已有歌词。
        val primary = enrich(ordinary, timed)
        val sidecarOffset = if (ordinary.isEmpty()) wordOffset else offset
        val translations = parseDocument(translation, sidecarOffset).map { it.copy(text = it.translation ?: it.text) }
        val romanizations = parseDocument(romanization, sidecarOffset).map { it.copy(text = it.romanization ?: it.text) }
        return attachSidecars(primary, translations, romanizations)
    }

    /** 自动补词只使用同时间、同正文且唯一匹配的行；不会替换本地正文或已有逐字时间轴。 */
    internal fun enrich(ordinary: List<LyricLine>, candidates: List<LyricLine>): List<LyricLine> {
        val timed = candidates.filter { it.words.isNotEmpty() }
        if (timed.isEmpty()) return ordinary
        if (ordinary.isEmpty()) return timed
        val byLine = timed.groupBy { it.startMs to it.text }
        return ordinary.map { line ->
            val match = byLine[line.startMs to line.text]?.singleOrNull()
            if (line.words.isEmpty() && match != null) line.copy(
                words = match.words, endMs = match.endMs,
            ) else line
        }
    }

    private fun parseDocument(raw: String, offset: Long): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (trimmed.startsWith('<')) parseXml(trimmed) else parseLrc(raw, offset)
    }

    private fun findOffset(raw: String): Long =
        offsetTag.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

    private data class LrcWordSeed(val startMs: Long, val text: String, val endMs: Long? = null)

    private data class LrcEntry(
        val order: Int,
        val startMs: Long,
        val text: String,
        val words: List<LrcWordSeed>,
    )

    private fun parseLrc(raw: String, offset: Long): List<LyricLine> {
        val entries = mutableListOf<LrcEntry>()
        var order = 0
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\uFEFF")
            val matches = lrcTimeTag.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val body = line.replace(lrcTimeTag, "")
            matches.forEach { match ->
                val rawStart = parseLrcTime(match.groupValues[1], match.groupValues[2], match.groupValues.getOrNull(3))
                    ?: return@forEach
                val (visible, words) = parseWordBody(body, rawStart, offset)
                val text = visible.trim()
                if (text.isNotBlank()) entries += LrcEntry(order++, (rawStart - offset).coerceAtLeast(0L), text, words)
            }
        }

        val sorted = collapseOrdinaryDuplicates(
            entries.sortedWith(compareBy<LrcEntry> { it.startMs }.thenBy { it.order }),
        )
        val nextStarts = arrayOfNulls<Long>(sorted.size)
        var nextDifferentStart: Long? = null
        for (index in sorted.lastIndex downTo 0) {
            nextStarts[index] = nextDifferentStart
            if (index == 0 || sorted[index - 1].startMs != sorted[index].startMs) {
                nextDifferentStart = sorted[index].startMs
            }
        }

        return sorted.mapIndexed { index, entry ->
            val inferredEnd = nextStarts[index]?.takeIf { it > entry.startMs }
            val words = makeWords(entry.words, inferredEnd, entry.text)
            LyricLine(
                startMs = entry.startMs,
                text = entry.text,
                endMs = words.maxOfOrNull { it.endMs } ?: inferredEnd?.takeIf { entry.words.isNotEmpty() },
                words = words,
            )
        }
    }

    /** 普通 LRC 的同一时间行沿用旧语义：去重后以换行拼接；增强行不走此合并。 */
    private fun collapseOrdinaryDuplicates(entries: List<LrcEntry>): List<LrcEntry> =
        entries.groupBy { it.startMs }.values.flatMap { group ->
            if (group.all { it.words.isEmpty() }) listOf(group.first().copy(text = group.map { it.text }.distinct().joinToString("\n")))
            else group
        }
    /** 绝对时间标签与 LX 的相对偏移/持续时间共用词种子，LX 不用下一字起点猜结束时间。 */
    private fun parseWordBody(body: String, lineStartMs: Long, offset: Long): Pair<String, List<LrcWordSeed>> {
        val matches = wordTag.findAll(body).toList()
        if (matches.isEmpty()) return body to emptyList()
        val visible = body.replace(wordTag, "")
        val relative = matches.any { it.groupValues[4].isNotEmpty() }
        val seeds = matches.mapIndexed { index, match ->
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            var text = body.substring(match.range.last + 1, textEnd)
            if (relative && index == 0) text = text.trimStart()
            if (relative && index == matches.lastIndex) text = text.trimEnd()
            if (match.groupValues[4].isEmpty()) {
                val start = parseLrcTime(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                    ?: return visible to emptyList()
                LrcWordSeed((start - offset).coerceAtLeast(0L), text)
            } else {
                val start = match.groupValues[4].toLongOrNull()?.takeIf { it >= 0 } ?: return visible to emptyList()
                val duration = match.groupValues[5].toLongOrNull()?.takeIf { it >= 0 } ?: return visible to emptyList()
                try {
                    val absoluteStart = Math.addExact(lineStartMs, start)
                    val absoluteEnd = Math.addExact(absoluteStart, duration)
                    LrcWordSeed(Math.subtractExact(absoluteStart, offset).coerceAtLeast(0L), text,
                        Math.subtractExact(absoluteEnd, offset).coerceAtLeast(0L))
                } catch (_: ArithmeticException) { return visible to emptyList() }
            }
        }
        if (relative && seeds.zipWithNext().any { (a, b) -> a.startMs > b.startMs }) return visible to emptyList()
        return visible to seeds
    }

    private fun makeWords(
        seeds: List<LrcWordSeed>,
        lineEndMs: Long?,
        lineText: String,
    ): List<LyricWord> {
        if (seeds.isEmpty()) return emptyList()
        val sorted = seeds.sortedWith(compareBy<LrcWordSeed> { it.startMs })
        val words = sorted.mapIndexedNotNull { index, seed ->
            if (seed.text.isEmpty()) return@mapIndexedNotNull null
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val end = when {
                seed.endMs != null -> seed.endMs
                nextStart != null && nextStart > seed.startMs -> nextStart
                lineEndMs != null && lineEndMs > seed.startMs -> lineEndMs
                else -> null
            }
            end?.let { LyricWord(seed.text, seed.startMs, it) }
        }
        if (words.size != sorted.count { it.text.isNotEmpty() }) return emptyList()
        if (words.joinToString(separator = "") { it.text } != lineText) return emptyList()
        return words
    }

    private fun parseLrcTime(minutes: String, seconds: String, fraction: String?): Long? {
        val minuteValue = minutes.toLongOrNull() ?: return null
        val secondValue = seconds.toLongOrNull() ?: return null
        val fractionValue = fraction.orEmpty()
        val millis = when {
            fractionValue.isEmpty() -> 0L
            fractionValue.length == 1 -> fractionValue.toLongOrNull()?.times(100) ?: return null
            fractionValue.length == 2 -> fractionValue.toLongOrNull()?.times(10) ?: return null
            else -> fractionValue.take(3).toLongOrNull() ?: return null
        }
        return (minuteValue * 60 + secondValue) * 1_000 + millis
    }

    private fun attachSidecars(
        primary: List<LyricLine>,
        translations: List<LyricLine>,
        romanizations: List<LyricLine>,
    ): List<LyricLine> {
        val rows = primary.toMutableList()
        attach(rows, translations, SidecarKind.Translation)
        attach(rows, romanizations, SidecarKind.Romanization)
        return rows.sortedWith(compareBy<LyricLine> { it.startMs })
    }

    private enum class SidecarKind { Translation, Romanization }

    private fun attach(rows: MutableList<LyricLine>, sidecars: List<LyricLine>, kind: SidecarKind) {
        val indicesByStart = rows.withIndex()
            .groupBy { it.value.startMs }
            .mapValuesTo(mutableMapOf()) { (_, indexed) -> indexed.map { it.index }.toMutableList() }

        sidecars.forEach { sidecar ->
            val value = when (kind) {
                SidecarKind.Translation -> sidecar.translation ?: sidecar.text
                SidecarKind.Romanization -> sidecar.romanization ?: sidecar.text
            }.trim()
            if (value.isBlank()) return@forEach

            val sameTime = indicesByStart[sidecar.startMs].orEmpty()
            if (kind == SidecarKind.Translation && sameTime.any { rows[it].text.trim() == value }) {
                return@forEach
            }
            val targetIndex = sameTime.firstOrNull { index ->
                val row = rows[index]
                when (kind) {
                    SidecarKind.Translation -> row.translation.isNullOrBlank() && row.text.isNotBlank()
                    SidecarKind.Romanization -> row.romanization.isNullOrBlank() && row.text.isNotBlank()
                }
            } ?: sameTime.firstOrNull { index ->
                val row = rows[index]
                when (kind) {
                    SidecarKind.Translation -> row.translation.isNullOrBlank() && row.text.isBlank()
                    SidecarKind.Romanization -> row.romanization.isNullOrBlank() && row.text.isBlank()
                }
            } ?: sameTime.firstOrNull { index ->
                when (kind) {
                    SidecarKind.Translation -> !rows[index].translation.isNullOrBlank()
                    SidecarKind.Romanization -> !rows[index].romanization.isNullOrBlank()
                }
            }

            if (targetIndex == null) {
                rows += LyricLine(
                    startMs = sidecar.startMs,
                    text = "",
                    translation = if (kind == SidecarKind.Translation) value else null,
                    endMs = sidecar.endMs,
                    romanization = if (kind == SidecarKind.Romanization) value else null,
                    alignment = sidecar.alignment,
                    isBackground = sidecar.isBackground,
                )
                indicesByStart.getOrPut(sidecar.startMs) { mutableListOf() }.add(rows.lastIndex)
            } else {
                val row = rows[targetIndex]
                rows[targetIndex] = when (kind) {
                    SidecarKind.Translation -> row.copy(translation = combineText(row.translation, value))
                    SidecarKind.Romanization -> row.copy(romanization = combineText(row.romanization, value))
                }
            }
        }
    }

    private fun combineText(existing: String?, incoming: String?): String = when {
        incoming.isNullOrBlank() -> existing.orEmpty()
        existing.isNullOrBlank() -> incoming
        existing == incoming -> existing
        else -> "$existing\n$incoming"
    }

    private data class TtmlMetadata(
        val translations: Map<String, String>,
        val romanizations: Map<String, String>,
        val groupAgents: Set<String> = emptySet(),
        val primaryAgent: String? = null,
    )

    private enum class TtmlRole { Main, Translation, Romanization }

    private data class TtmlCandidate(
        val order: Int,
        val startMs: Long,
        val endMs: Long?,
        val text: String,
        val translation: String?,
        val romanization: String?,
        val words: List<LyricWord>,
        val alignment: LyricAlignment,
        val isBackground: Boolean,
        val role: TtmlRole,
        val key: String?,
        val agent: String?,
    )

    private data class WordSeed(
        val order: Int,
        val startMs: Long,
        val endMs: Long?,
        val text: String,
    )

    private fun parseXml(raw: String): List<LyricLine> = runCatching {
        val document = secureDocument(raw)
        val candidates = mutableListOf<TtmlCandidate>()
        val translations = linkedMapOf<String, String>()
        val romanizations = linkedMapOf<String, String>()
        visitTimedElements(
            element = document.documentElement,
            parentStartMs = 0L,
            candidates = candidates,
            translations = translations,
            romanizations = romanizations,
        )
        if (candidates.isEmpty()) return@runCatching emptyList()
        val agents = documentElements(document.documentElement).filter { localName(it) == "agent" }
        val groupAgents = agents.filter { attribute(it, "type") == "group" }.mapNotNull { attribute(it, "id") }.toSet()
        val primaryAgent = agents.firstOrNull { attribute(it, "type") != "group" }?.let { attribute(it, "id") }
        val lines = candidatesToLines(candidates, TtmlMetadata(translations, romanizations, groupAgents, primaryAgent))
        if (attribute(document.documentElement, "timing").equals("Line", ignoreCase = true))
            lines.map { it.copy(words = emptyList()) } else lines
    }.getOrElse { emptyList() }

    private fun secureDocument(raw: String): Document {
        require(!Regex("""(?is)<!\s*(?:DOCTYPE|ENTITY)\b""").containsMatchIn(raw)) {
            "DTD and entity declarations are not supported in lyric XML"
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.isExpandEntityReferences = false }
        runCatching { factory.isXIncludeAware = false }
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        ).forEach { feature ->
            runCatching { factory.setFeature(feature, feature.endsWith("disallow-doctype-decl")) }
        }
        // Android 编译 API 不暴露 XMLConstants.ACCESS_EXTERNAL_*；使用 JAXP 属性名字符串，
        // 不支持时由前面的 DTD/ENTITY 拒绝、关闭实体展开和空 EntityResolver 兜底。
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(InputSource(StringReader(raw)))
    }

    private fun visitTimedElements(
        element: Element,
        parentStartMs: Long,
        candidates: MutableList<TtmlCandidate>,
        translations: MutableMap<String, String>,
        romanizations: MutableMap<String, String>,
        inMetadata: Boolean = false,
        inTranslation: Boolean = false,
        inRomanization: Boolean = false,
    ) {
        val name = localName(element)
        val metadataScope = inMetadata || name.equals("iTunesMetadata", ignoreCase = true)
        val translationScope = metadataScope &&
            (inTranslation || name.equals("translation", ignoreCase = true))
        val romanizationScope = metadataScope &&
            (inRomanization || name.equals("transliteration", ignoreCase = true) ||
                name.equals("romanization", ignoreCase = true))
        if (metadataScope && name.equals("text", ignoreCase = true)) {
            val key = attribute(element, "for")?.takeIf { it.isNotBlank() }
            val value = visibleText(element).takeIf { it.isNotBlank() }
            if (key != null && value != null) {
                when {
                    translationScope -> translations[key] = combineText(translations[key], value)
                    romanizationScope -> romanizations[key] = combineText(romanizations[key], value)
                }
            }
        }

        val startMs = resolveElementStart(element, parentStartMs)
        val endMs = resolveElementEnd(element, startMs)
        if (name.equals("p", ignoreCase = true)) {
            val primary = parseTtmlLine(element, startMs, endMs, candidates.size)
            candidates += primary
            fun backgrounds(parent: Element) {
                childElements(parent).forEach { child ->
                    if ("x-bg" in roleNames(child)) {
                        val bgStart = resolveElementStart(child, startMs)
                        val bgEnd = resolveElementEnd(child, bgStart) ?: endMs
                        candidates += parseTtmlLine(child, bgStart, bgEnd, candidates.size).copy(
                            agent = attribute(child, "agent") ?: primary.agent,
                            alignment = if (attribute(child, "textAlign", "align") != null) alignmentOf(child) else primary.alignment,
                        )
                    } else backgrounds(child)
                }
            }
            backgrounds(element)
            return
        }
        childElements(element).forEach { child ->
            visitTimedElements(
                element = child,
                parentStartMs = startMs,
                candidates = candidates,
                translations = translations,
                romanizations = romanizations,
                inMetadata = metadataScope,
                inTranslation = translationScope,
                inRomanization = romanizationScope,
            )
        }
    }

    private fun parseTtmlLine(element: Element, startMs: Long, endMs: Long?, order: Int): TtmlCandidate {
        val role = ttmlRole(element)
        val mainText = if (role == TtmlRole.Main) visibleText(element) else ""
        val inlineTranslation = combineText(
            explicitAttributeText(element, "x-translation"),
            collectRoleText(element, "x-translation"),
        ).takeIf { it.isNotBlank() }
        val inlineRomanization = combineText(
            explicitAttributeText(element, "x-roman"),
            collectRoleText(element, "x-roman"),
        ).takeIf { it.isNotBlank() }
        val words = if (role == TtmlRole.Main) collectWords(element, startMs, endMs) else emptyList()
        val inferredStart = if (element.hasAttribute("begin") || words.isEmpty()) startMs else words.minOf { it.startMs }
        val lineEnd = endMs ?: words.maxOfOrNull { it.endMs }
        return TtmlCandidate(
            order = order,
            startMs = inferredStart,
            endMs = lineEnd,
            text = if (role == TtmlRole.Main) mainText else "",
            translation = if (role == TtmlRole.Translation) visibleText(element) else inlineTranslation,
            romanization = if (role == TtmlRole.Romanization) visibleText(element) else inlineRomanization,
            words = words,
            alignment = alignmentOf(element),
            isBackground = "x-bg" in roleNames(element),
            role = role,
            key = attribute(element, "key"),
            agent = attribute(element, "agent"),
        )
    }

    private fun candidatesToLines(candidates: List<TtmlCandidate>, metadata: TtmlMetadata): List<LyricLine> {
        val mainCandidates = candidates.filter { it.role == TtmlRole.Main }
        val mainIndicesByKey = mainCandidates.withIndex()
            .filter { it.value.key != null }
            .groupBy { it.value.key!! }
            .mapValues { (_, indexed) -> indexed.map { it.index } }
        val mainIndicesByStart = mainCandidates.withIndex()
            .groupBy { it.value.startMs }
            .mapValues { (_, indexed) -> indexed.map { it.index } }
        val primaryAgent = metadata.primaryAgent ?: mainCandidates.firstOrNull { !it.isBackground && it.agent != null && it.agent !in metadata.groupAgents }?.agent
        val output = mainCandidates.map { candidate ->
            val metadataTranslation = candidate.key?.let { metadata.translations[it] }
            val metadataRomanization = candidate.key?.let { metadata.romanizations[it] }
            LyricLine(
                startMs = candidate.startMs,
                text = candidate.text,
                translation = combineText(candidate.translation, metadataTranslation)
                    .takeIf { it.isNotBlank() },
                endMs = candidate.endMs,
                words = candidate.words,
                romanization = combineText(candidate.romanization, metadataRomanization)
                    .takeIf { it.isNotBlank() },
                alignment = if (candidate.agent != null && candidate.agent != primaryAgent && candidate.agent !in metadata.groupAgents) LyricAlignment.End else candidate.alignment,
                isBackground = candidate.isBackground,
            )
        }.toMutableList()
        val attached = mutableSetOf<Int>()
        val orphanLines = mutableListOf<Pair<Int, LyricLine>>()

        candidates.filter { it.role != TtmlRole.Main }.forEach { candidate ->
            val targetIndex = candidate.key?.let { mainIndicesByKey[it] }
                ?.singleOrNull()
                ?: mainIndicesByStart[candidate.startMs]?.singleOrNull()
            if (targetIndex != null) {
                val current = output[targetIndex]
                output[targetIndex] = when (candidate.role) {
                    TtmlRole.Translation -> current.copy(
                        translation = combineText(current.translation, candidate.translation ?: candidate.text)
                            .takeIf { it.isNotBlank() },
                        isBackground = current.isBackground || candidate.isBackground,
                    )
                    TtmlRole.Romanization -> current.copy(
                        romanization = combineText(current.romanization, candidate.romanization ?: candidate.text)
                            .takeIf { it.isNotBlank() },
                        isBackground = current.isBackground || candidate.isBackground,
                    )
                    TtmlRole.Main -> current
                }
                attached += candidate.order
            } else if (candidate.order !in attached) {
                orphanLines += candidate.order to LyricLine(
                    startMs = candidate.startMs,
                    text = "",
                    translation = candidate.translation?.takeIf { it.isNotBlank() },
                    endMs = candidate.endMs,
                    romanization = candidate.romanization?.takeIf { it.isNotBlank() },
                    alignment = candidate.alignment,
                    isBackground = candidate.isBackground,
                )
            }
        }

        val ordered = mainCandidates.mapIndexed { index, candidate -> candidate.order to output[index] } + orphanLines
        return ordered.sortedWith(compareBy<Pair<Int, LyricLine>> { it.second.startMs }.thenBy { it.first })
            .map { it.second }
    }

    private fun collectWords(element: Element, lineStartMs: Long, lineEndMs: Long?): List<LyricWord> {
        val seeds = mutableListOf<WordSeed>()
        var prefix = ""
        var incomplete = false
        fun visit(parent: Element, parentStart: Long) {
            childNodes(parent).forEach { node ->
                if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                    val text = node.nodeValue.orEmpty()
                    if (text.isNotBlank()) incomplete = true
                    else if (seeds.isEmpty()) prefix += text
                    else seeds[seeds.lastIndex] = seeds.last().copy(text = seeds.last().text + text)
                } else if (node is Element && ttmlRole(node) == TtmlRole.Main && "x-bg" !in roleNames(node)) {
                    val start = resolveElementStart(node, parentStart)
                    val nestedTiming = documentElements(node).drop(1).any { hasTimingAttribute(it) }
                    if (hasTimingAttribute(node) && !nestedTiming) {
                        seeds += WordSeed(seeds.size, start, resolveElementEnd(node, start), prefix + visibleText(node, trim = false))
                        prefix = ""
                    } else visit(node, start)
                }
            }
        }
        visit(element, lineStartMs)
        if (incomplete || seeds.isEmpty()) return emptyList()
        val words = seeds.mapIndexed { index, seed ->
            val end = seed.endMs ?: seeds.getOrNull(index + 1)?.startMs ?: lineEndMs ?: return emptyList()
            if (end < seed.startMs) return emptyList()
            val text = seed.text.let { if (index == 0) it.trimStart() else it }
                .let { if (index == seeds.lastIndex) it.trimEnd() else it }
            LyricWord(text, seed.startMs, end)
        }
        return words.takeIf { it.joinToString("") { word -> word.text } == visibleText(element) }.orEmpty()
    }

    private fun visibleText(node: Node, trim: Boolean = true): String {
        val out = StringBuilder()
        if (node is Element) childNodes(node).forEach { appendVisibleText(it, out) } else appendVisibleText(node, out)
        val text = out.toString().replace("\r\n", "\n").replace('\r', '\n')
        return if (trim) text.trim() else text
    }

    private fun appendVisibleText(node: Node, out: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(node.nodeValue.orEmpty())
            Node.ELEMENT_NODE -> {
                val element = node as Element
                val role = ttmlRole(element)
                if (role != TtmlRole.Main || "x-bg" in roleNames(element)) return
                if (localName(element).equals("br", ignoreCase = true)) {
                    out.append('\n')
                } else {
                    childNodes(element).forEach { appendVisibleText(it, out) }
                }
            }
        }
    }

    private fun collectRoleText(node: Element, targetRole: String): String {
        val values = linkedSetOf<String>()
        fun visit(parent: Element) {
            childElements(parent).forEach { element ->
                when {
                    "x-bg" in roleNames(element) -> Unit
                    targetRole in roleNames(element) -> roleText(element, targetRole).takeIf { it.isNotBlank() }?.let(values::add)
                    else -> visit(element)
                }
            }
        }
        visit(node)
        return values.joinToString("\n")
    }

    private fun roleText(node: Node, targetRole: String): String {
        val out = StringBuilder()
        fun append(nodeToRead: Node) {
            when (nodeToRead.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(nodeToRead.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val element = nodeToRead as Element
                    val roles = roleNames(element)
                    if (roles.any { it != targetRole && (it == "x-translation" || it == "x-roman") }) return
                    childNodes(element).forEach(::append)
                }
            }
        }
        append(node)
        return out.toString().trim()
    }

    private fun ttmlRole(element: Element): TtmlRole {
        val roles = roleNames(element)
        return when {
            "x-translation" in roles -> TtmlRole.Translation
            "x-roman" in roles -> TtmlRole.Romanization
            else -> TtmlRole.Main
        }
    }

    private fun roleNames(element: Element): Set<String> {
        val role = attribute(element, "role").orEmpty()
        val roles = role.split(Regex("[\\s,]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toMutableSet()
        if (hasBooleanAttribute(element, "x-translation")) roles += "x-translation"
        if (hasBooleanAttribute(element, "x-roman")) roles += "x-roman"
        if (hasBooleanAttribute(element, "x-bg")) roles += "x-bg"
        return roles
    }

    private fun explicitAttributeText(element: Element, name: String): String? {
        val value = attribute(element, name)?.trim() ?: return null
        return value.takeUnless { it.lowercase() in booleanValues || it.isEmpty() }
    }

    private fun hasBooleanAttribute(element: Element, name: String): Boolean =
        attribute(element, name)?.trim()?.lowercase() in booleanValues

    private fun alignmentOf(element: Element): LyricAlignment = when (
        attribute(element, "textAlign", "text-align", "align")?.trim()?.lowercase()
    ) {
        "end", "right" -> LyricAlignment.End
        else -> LyricAlignment.Start
    }

    private fun resolveElementStart(element: Element, parentStartMs: Long): Long {
        val parsed = attribute(element, "begin")?.let(::parseTtmlTime) ?: return parentStartMs
        return parsed
    }

    private fun resolveElementEnd(element: Element, startMs: Long): Long? {
        attribute(element, "end")?.let { end ->
            parseTtmlTime(end)?.let { return it }
        }
        attribute(element, "dur")?.let { duration ->
            parseTtmlTime(duration)?.let { return startMs + it }
        }
        return null
    }

    /**
     * Apple/AMLL lyric TTML 使用媒体时间轴上的绝对 clock/seconds begin/end；只有 dur
     * 是相对当前元素起点的时长。这里不按数值大小猜测父子相对关系，也不承诺 W3C
     * 完整 timeContainer/布局语义。
     */

    private fun hasTimingAttribute(element: Element): Boolean =
        attribute(element, "begin", "end", "dur") != null

    private fun parseTtmlTime(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val unitMatch = Regex("""^([+-]?(?:\d+(?:\.\d*)?|\.\d+))(ms|s|m|h)$""").matchEntire(text)
        if (unitMatch != null) {
            val number = unitMatch.groupValues[1].toBigDecimalOrNull() ?: return null
            val multiplier = when (unitMatch.groupValues[2]) {
                "ms" -> BigDecimal.ONE
                "s" -> BigDecimal(1_000)
                "m" -> BigDecimal(60_000)
                "h" -> BigDecimal(3_600_000)
                else -> return null
            }
            return number.multiply(multiplier).setScale(0, RoundingMode.HALF_UP).longValueExactOrNull()
        }

        val parts = text.split(':')
        return when (parts.size) {
            1 -> decimalMillis(parts[0], 1_000)
            2 -> decimalMillis(parts[0], 60_000)?.plus(decimalMillis(parts[1], 1_000) ?: return null)
            3 -> decimalMillis(parts[0], 3_600_000)
                ?.plus(decimalMillis(parts[1], 60_000) ?: return null)
                ?.plus(decimalMillis(parts[2], 1_000) ?: return null)
            else -> null
        }
    }

    private fun decimalMillis(value: String, multiplier: Long): Long? = runCatching {
        value.toBigDecimal()
            .multiply(BigDecimal(multiplier))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

    private fun BigDecimal.longValueExactOrNull(): Long? = runCatching { longValueExact() }.getOrNull()

    private fun attribute(element: Element, vararg names: String): String? {
        val wanted = names.map { it.substringAfterLast(':') }.toSet()
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val node = attributes.item(index)
            val local = (node.localName ?: node.nodeName.substringAfter(':')).substringAfterLast(':')
            if (local in wanted) return node.nodeValue
        }
        return null
    }

    private fun localName(node: Node): String =
        (node.localName ?: node.nodeName.substringAfter(':')).substringAfterLast(':')

    private fun childElements(node: Node): List<Element> = buildList {
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
        }
    }

    private fun childNodes(node: Node): List<Node> = buildList {
        val children = node.childNodes
        for (index in 0 until children.length) add(children.item(index))
    }

    private fun documentElements(root: Element): List<Element> = buildList {
        fun walk(node: Node) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                add(element)
                childElements(element).forEach(::walk)
            }
        }
        walk(root)
    }


}
