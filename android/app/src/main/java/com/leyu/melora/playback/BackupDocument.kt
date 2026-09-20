package com.leyu.melora.playback

import com.leyu.melora.playback.lx.backupScriptId
import com.leyu.melora.playback.lx.LxSourceUrlPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
internal const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024
private const val MAX_ITEMS = 50_000
internal data class BackupScript(
    val fileName: String,
    val code: String,
    val enabled: Boolean,
    val originUrl: String? = null,
)
internal data class ParsedBackup(val settings: JSONObject?, val library: String?, val scripts: List<BackupScript>?)

/** 不信任provider报告的长度：实际读取计数有上限，取消检查覆盖每个块。 */
internal fun InputStream.readBackupText(limit: Int = MAX_BACKUP_BYTES, checkCancelled: () -> Unit = {}): String {
    val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (true) {
        checkCancelled()
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        require(count <= limit - output.size()) { "备份超过大小限制（${limit / 1024 / 1024} MiB）" }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
}

private fun backupObject(text: String): JSONObject {
    require(text.length <= MAX_BACKUP_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "备份超过16 MiB限制" }
    // JSONObject自身没有深度上限；在构造树之前拒绝异常嵌套，兼容历史JSONTokener的单双引号。
    var quote = '\u0000'
    var escape = false
    var depth = 0
    var structureTokens = 0
    text.forEach { char ->
        if (quote != '\u0000') {
            if (escape) escape = false else if (char == '\\') escape = true else if (char == quote) quote = '\u0000'
        } else {
            // 大量极小对象也能把16MiB文本膨胀成巨量对象；限制建树前的结构密度。
            if (char == '{' || char == '[' || char == ':' || char == ',') {
                structureTokens++
                require(structureTokens <= 500_000) { "备份JSON结构条目过多" }
            }
            when (char) {
                '"', '\'' -> quote = char
                '{', '[' -> { depth++; require(depth <= 64) { "备份嵌套过深" } }
                '}', ']' -> depth--
            }
        }
    }
    val parser = JSONTokener(text.removePrefix("\uFEFF"))
    val root = parser.nextValue()
    require(root is JSONObject && parser.nextClean() == '\u0000') { "备份必须为完整JSON对象" }
    return root
}

internal fun parseBackupDocument(text: String): ParsedBackup {
    val root = backupObject(text)
    if (root.has("version")) {
        val version = root.get("version")
        require((version is Number && version.toDouble() == 1.0) || version == "1") { "不支持的备份版本" }
    }
    require(listOf("settings", "library", "scripts").any(root::has)) { "文件不包含可恢复的备份内容" }
    val settings = root.opt("settings")?.let {
        require(it is JSONObject) { "settings 格式无效" }
        BackupSettings.validate(it)
        it
    }
    val library = root.opt("library")?.let {
        val value = when (it) {
            is JSONObject -> it
            is String -> backupObject(it)
            else -> error("library 格式无效")
        }
        validateBackupLibrary(value)
        value.toString()
    }
    val scripts = root.opt("scripts")?.let { value ->
        require(value is JSONArray && value.length() <= 128) { "音源列表无效或超过128个" }
        val ids = hashSetOf<String>()
        List(value.length()) { index ->
            val script = value.optJSONObject(index) ?: error("脚本 #${index + 1} 格式无效")
            val code = script.opt("code") as? String ?: error("脚本 #${index + 1} 缺少代码")
            require(code.isNotBlank() && code.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) { "脚本代码为空或超过4 MiB" }
            val name = if (script.has("fileName")) script.get("fileName") else script.opt("name") ?: "restored.js"
            require(name is String && name.isNotBlank()) { "脚本文件名无效" }
            val id = backupScriptId(name)
            require(ids.add(id)) { "备份包含重名音源: $id" }
            val originUrl = when (val origin = script.opt("originUrl")) {
                null -> null
                JSONObject.NULL -> null
                is String -> LxSourceUrlPolicy.validate(origin).toString()
                else -> error("脚本 #${index + 1} 来源链接无效")
            }
            BackupScript(
                fileName = id,
                code = code,
                enabled = if (script.has("enabled")) backupBoolean(script.get("enabled")) else true,
                originUrl = originUrl,
            )
        }
    }
    return ParsedBackup(settings, library, scripts)
}

private fun validateBackupLibrary(root: JSONObject) {
    var remaining = MAX_ITEMS
    fun array(owner: JSONObject, name: String, visit: (Any) -> Unit) {
        if (!owner.has(name)) return
        val list = owner.get(name)
        require(list is JSONArray && list.length() <= remaining) { "用户库$name 格式无效或条目过多" }
        remaining -= list.length()
        repeat(list.length()) { visit(list.get(it)) }
    }
    fun node(value: Any): JSONObject = value as? JSONObject ?: error("用户库含非对象条目")
    fun required(item: JSONObject, key: String, numeric: Boolean = false) {
        val value = item.opt(key)
        require((value is String || numeric && value is Number) && value.toString().isNotBlank()) { "用户库条目缺少有效$key" }
    }
    fun songs(owner: JSONObject, name: String) = array(owner, name) { value ->
        val item = node(value)
        required(item, "source")
        required(item, "songmid", numeric = true)
    }
    songs(root, "favorites")
    songs(root, "recents")
    array(root, "favoritePlaylists") { value ->
        val item = node(value); required(item, "id", true); required(item, "name")
    }
    for (key in listOf("favoriteAlbums", "favoriteArtists")) array(root, key) { required(node(it), "name") }
    array(root, "recentContainers") { value ->
        val item = node(value); required(item, "id", true); required(item, "name")
    }
    array(root, "searchHistory") { require(it is String && it.isNotBlank()) { "搜索历史条目无效" } }
    array(root, "playlists") { value ->
        val item = node(value); required(item, "id"); required(item, "name")
        require(item.has("songs")) { "自建歌单缺少歌曲列表" }
        songs(item, "songs")
    }
}

internal fun restoredDownloadPath(savedPath: String, writableTreeUris: Set<String>): String =
    if (savedPath.startsWith("content://") && savedPath !in writableTreeUris) MeloraSettings.DEFAULT_DOWNLOAD_PATH else savedPath

/** 导出时边编码边限额，而不是先拼接一个可能无限大的字符串再检查。 */
internal fun boundedBackupJson(value: Any, limit: Int = MAX_BACKUP_BYTES): String {
    val bytes = object : ByteArrayOutputStream(minOf(limit, 64 * 1024)) {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(length <= limit - size()) { "备份超过大小限制（${limit / 1024 / 1024} MiB）" }
            super.write(buffer, offset, length)
        }
        override fun write(value: Int) {
            require(size() < limit) { "备份超过大小限制" }
            super.write(value)
        }
    }
    val writer = bytes.writer(Charsets.UTF_8)
    fun encode(node: Any?, depth: Int) {
        require(depth <= 64) { "备份嵌套过深" }
        when (node) {
            is JSONObject -> {
                writer.write("{")
                node.keys().asSequence().forEachIndexed { i, key ->
                    if (i > 0) writer.write(",")
                    writer.write(JSONObject.quote(key)); writer.write(":")
                    encode(node.get(key), depth + 1)
                }
                writer.write("}")
            }
            is JSONArray -> {
                writer.write("[")
                repeat(node.length()) { i ->
                    if (i > 0) writer.write(",")
                    encode(node.get(i), depth + 1)
                }
                writer.write("]")
            }
            is String -> {
                require(node.length <= limit) { "备份文字过长" }
                writer.write(JSONObject.quote(node))
            }
            null, JSONObject.NULL -> writer.write("null")
            else -> writer.write(node.toString())
        }
    }
    writer.use { encode(value, 0) }
    return bytes.toString(Charsets.UTF_8.name())
}
