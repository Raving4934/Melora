package com.leyu.melora.playback

import android.os.Build
import com.leyu.melora.BuildConfig
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal const val BACKUP_ORIGIN_KEY = "backupOrigin"
private const val MAX_MODEL_LENGTH = 96
private const val MAX_VERSION_LENGTH = 32

internal data class BackupOrigin(val model: String, val appVersion: String) {
    fun toJson(): JSONObject = JSONObject().put("model", model).put("appVersion", appVersion)

    companion object {
        fun current(): BackupOrigin {
            val model = Build.MODEL.normalizedDevicePart()
            val brand = Build.BRAND.normalizedDevicePart().ifBlank { Build.MANUFACTURER.normalizedDevicePart() }
            val device = when {
                model.isBlank() -> brand.ifBlank { "未知设备" }
                brand.isBlank() || model.startsWith(brand, ignoreCase = true) -> model
                else -> "$brand $model".label(MAX_MODEL_LENGTH)
            }
            return BackupOrigin(device, BuildConfig.VERSION_NAME.label(MAX_VERSION_LENGTH))
        }
    }
}

/** 只读备份开头的来源信息，允许其后的设置、用户库或音源正文被截断。 */
internal fun readBackupOrigin(prefix: String): BackupOrigin? {
    val reader = JSONTokener(prefix.removePrefix("\uFEFF"))
    return try {
        if (reader.nextClean() != '{') return null
        do {
            val name = reader.nextQuotedString() ?: return null
            if (reader.nextClean() != ':') return null
            if (name == BACKUP_ORIGIN_KEY) return reader.readOriginObject()
            if (name != "version" && name != "exportedAt") return null
            // 只跳过备份头的标量，避免解析未下载的正文或把正文中的键误认成根级字段。
            val first = reader.nextClean()
            if (first != '"' && first !in '0'..'9') return null
            reader.back()
            if (reader.nextValue() !is Number && first != '"') return null
        } while (reader.nextClean() == ',')
        null
    } catch (_: JSONException) {
        null
    }
}

private fun JSONTokener.nextQuotedString(): String? = if (nextClean() == '"') nextString('"') else null

private fun JSONTokener.readOriginObject(): BackupOrigin? {
    if (nextClean() != '{') return null
    val fields = HashMap<String, String>(2)
    repeat(2) { index ->
        val name = nextQuotedString() ?: return null
        if (nextClean() != ':') return null
        fields[name] = nextQuotedString() ?: return null
        if (nextClean() != (if (index == 0) ',' else '}')) return null
    }
    val model = fields["model"]?.validLabel(MAX_MODEL_LENGTH) ?: return null
    val version = fields["appVersion"]?.validLabel(MAX_VERSION_LENGTH) ?: return null
    return BackupOrigin(model, version)
}

private fun String.normalizedDevicePart() = label(MAX_MODEL_LENGTH).takeUnless { it.equals(Build.UNKNOWN, ignoreCase = true) }.orEmpty()

private fun String.label(limit: Int): String =
    filter(Char::isBackupLabelChar).trim().take(limit).trimEnd { it.isWhitespace() || it.isHighSurrogate() }

private fun String.validLabel(limit: Int): String? =
    takeIf { length in 1..limit && all(Char::isBackupLabelChar) }?.trim()?.takeIf(String::isNotEmpty)

private fun Char.isBackupLabelChar(): Boolean = !isISOControl() &&
    category != CharCategory.FORMAT && category != CharCategory.LINE_SEPARATOR && category != CharCategory.PARAGRAPH_SEPARATOR
