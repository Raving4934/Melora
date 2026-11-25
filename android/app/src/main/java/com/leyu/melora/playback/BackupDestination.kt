package com.leyu.melora.playback

import android.net.Uri
import java.util.UUID
import okio.ByteString.Companion.encodeUtf8

internal enum class BackupDestination(val local: Boolean, val network: Boolean) {
    OFF(false, false),
    LOCAL(true, false),
    NETWORK(false, true),
    BOTH(true, true),
}

internal data class BackupPreferences(
    val directory: Uri? = null,
    val directoryName: String? = null,
    val automatic: BackupDestination = BackupDestination.OFF,
    val keepLatest: Boolean = false,
)

internal data class BackupFileId(val createdAt: Long, val device: String, val uuid: UUID) {
    val networkName get() = "melora-backup-$createdAt-$device-$uuid.json"
    val localName get() = "melora-local-backup-$createdAt-$device-$uuid.json"

    companion object {
        private val namePattern = Regex("(\\d{1,19})-([0-9a-f]{8})-([0-9a-f-]{36})\\.json")

        fun parseLocalName(name: String) = parse(name, "melora-local-backup-")
        fun parseNetworkName(name: String) = parse(name, "melora-backup-")

        private fun parse(name: String, prefix: String): BackupFileId? {
            if (!name.startsWith(prefix)) return null
            val match = namePattern.matchEntire(name.removePrefix(prefix)) ?: return null
            val createdAt = match.groupValues[1].toLongOrNull() ?: return null
            val rawId = match.groupValues[3]
            val id = runCatching { UUID.fromString(rawId) }.getOrNull()?.takeIf { it.toString() == rawId } ?: return null
            return BackupFileId(createdAt, match.groupValues[2], id)
        }
    }
}

internal class BackupSnapshot(val id: BackupFileId, val text: String) {
    val bytes by lazy { text.encodeUtf8() }
}
