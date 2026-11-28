package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal data class WebDavConfig(
    val server: String = "",
    val account: String = "",
    val password: String = "",
    val folder: String = DEFAULT_FOLDER,
) {
    fun validated(): WebDavConfig {
        val address = server.trim().takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请输入完整的 WebDAV 服务器地址")
        require(address.username.isEmpty() && address.password.isEmpty() && address.query == null && address.fragment == null) {
            "服务器地址请填写 WebDAV 根路径，账号和密码请单独填写"
        }
        val username = account.trim()
        require(username.length <= 256 && ':' !in username && username.none(Char::isISOControl)) {
            "WebDAV 账号格式无效"
        }
        require(password.length <= 4096 && password.none(Char::isISOControl)) { "WebDAV 密码格式无效" }
        require(username.isNotEmpty() || password.isEmpty()) { "请输入 WebDAV 账号" }
        val directory = folder.trim().ifBlank { DEFAULT_FOLDER }
        require(directory.length <= 64 && directory != "." && directory != ".." &&
            directory.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "子文件夹名称须为单个目录名，最多 64 个字符"
        }
        val base = address.newBuilder().encodedPath(address.encodedPath.trimEnd('/') + "/").build()
        return copy(server = base.toString(), account = username, folder = directory)
    }

    val scopeKey: String
        get() {
            val config = validated()
            val identity = "${config.server}\n${config.account}\n${config.folder}"
            return identity.sha256Hex()
        }

    // 避免配置在日志或异常上下文中输出凭据。
    override fun toString(): String = "WebDavConfig"

    companion object {
        const val DEFAULT_FOLDER = "Melora"
    }
}

/** 与系统和应用备份隔离。配置只在当前安装中由 Android Keystore 解密。 */
internal object WebDavConfigStore {
    private const val KEY_ALIAS = "melora-webdav-config"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val LIMIT = 64 * 1024
    private const val PREFS = "webdav-sync"
    private const val CONFIG = "encrypted-config"
    private const val SCOPE = "enabled-scope"
    private val lock = Any()

    fun load(context: Context): WebDavConfig? = synchronized(lock) {
        val prefs = preferences(context)
        try {
            prefs.getString(CONFIG, null)?.let { return@synchronized decode(it) }
            val oldFile = legacyFile(context)
            val text = oldFile.readEncrypted() ?: return@synchronized null
            val config = decode(text)
            runCatching {
                prefs.saveConnection(text, prefs.getString(SCOPE, null)?.takeIf { it == config.scopeKey })
                oldFile.delete()
            }
            config
        } catch (_: Exception) {
            throw IllegalStateException("无法读取本机 WebDAV 配置，请重新填写并保存")
        }
    }

    fun save(context: Context, config: WebDavConfig): WebDavConfig = synchronized(lock) {
        val validated = config.validated()
        val prefs = preferences(context)
        val scope = prefs.getString(SCOPE, null)?.takeIf { requested ->
            requested == validated.scopeKey && (prefs.contains(CONFIG) || runCatching {
                legacyFile(context).readEncrypted()?.let(::decode)?.scopeKey == requested
            }.getOrDefault(false))
        }
        try {
            val json = JSONObject().put("server", validated.server).put("account", validated.account)
                .put("password", validated.password).put("folder", validated.folder)
            val plain = json.toString().toByteArray(Charsets.UTF_8)
            val store = keyStore()
            val envelope = try {
                encrypt(plain, store.getKey(KEY_ALIAS, null) as? SecretKey ?: createKey())
            } catch (error: Exception) {
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                val invalidated = causes.any { it is KeyPermanentlyInvalidatedException }
                val unavailable = causes.any { it is UnrecoverableKeyException }
                val canReplace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    causes.filterIsInstance<KeyStoreException>().firstOrNull()?.let {
                        it.numericErrorCode in setOf(KeyStoreException.ERROR_KEY_CORRUPTED, KeyStoreException.ERROR_KEY_DOES_NOT_EXIST)
                    } ?: unavailable
                } else unavailable
                if (!invalidated && !canReplace) throw error
                store.deleteEntry(KEY_ALIAS)
                encrypt(plain, createKey())
            }
            prefs.saveConnection(envelope.toString(), scope)
        } catch (error: Exception) {
            throw IllegalStateException("无法在本机保存 WebDAV 配置", error)
        }
        runCatching { legacyFile(context).delete() }
        validated
    }

    fun enabledScope(context: Context): String? = synchronized(lock) { preferences(context).getString(SCOPE, null) }

    fun setEnabledScope(context: Context, scope: String?) = synchronized(lock) {
        val prefs = preferences(context)
        prefs.saveConnection(prefs.getString(CONFIG, null), scope)
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun SharedPreferences.saveConnection(text: String?, scope: String?) =
        commitOrRestore("无法保存 WebDAV 设置") { putString(CONFIG, text).putString(SCOPE, scope) }

    private fun File.readEncrypted(): String? = takeIf(File::isFile)?.inputStream()?.use { it.readBackupText(LIMIT) }

    fun deviceId(context: Context): String = synchronized(lock) {
        val directory = context.noBackupFilesDir
        val file = File(directory, "webdav-device-id")
        val backup = File(directory, "webdav-device-id.bak")
        val current = runCatching { readDeviceId(file) }.getOrNull()
        val saved = runCatching { readDeviceId(backup) }.getOrNull()
        val id = current ?: saved ?: confirmedDeviceId(directory) ?: UUID.randomUUID().toString()
        if (current != id) writeTextAtomically(file, id)
        if (saved != id) runCatching { writeTextAtomically(backup, id) }
        id
    }

    private fun readDeviceId(file: File): String? {
        if (!file.isFile || file.length() !in 1..64) return null
        val text = file.readText().trim()
        val id = runCatching { UUID.fromString(text).toString() }.getOrNull()
        return id?.takeIf { it.equals(text, ignoreCase = true) }
    }

    private fun confirmedDeviceId(directory: File): String? =
        directory.listFiles { entry -> entry.isDirectory && entry.name.startsWith("records-") }
            ?.mapNotNull { ledger ->
                val confirmed = File(ledger, "confirmed")
                if (!confirmed.isFile || confirmed.length() !in 1..256) return@mapNotNull null
                val name = runCatching { confirmed.readText() }.getOrNull() ?: return@mapNotNull null
                RecordReplica.filename.matchEntire(name)?.groupValues?.get(1)
            }
            ?.distinct()
            ?.singleOrNull()

    private fun legacyFile(context: Context) = File(context.noBackupFilesDir, "webdav-config.json")

    private fun decode(text: String): WebDavConfig {
        val envelope = JSONObject(text)
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(CIPHER)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: error("缺少本机密钥")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoder.decode(envelope.getString("iv"))))
        val json = JSONObject(cipher.doFinal(decoder.decode(envelope.getString("data"))).toString(Charsets.UTF_8))
        return WebDavConfig(json.getString("server"), json.getString("account"), json.getString("password"),
            json.getString("folder")).validated()
    }

    private fun encrypt(plain: ByteArray, key: SecretKey): JSONObject {
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encoder = Base64.getEncoder()
        return JSONObject().put("iv", encoder.encodeToString(cipher.iv))
            .put("data", encoder.encodeToString(cipher.doFinal(plain)))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }
}

internal fun WebDavConfig.folderUrl() = server.toHttpUrl().newBuilder()
    .addPathSegment(folder)
    .addPathSegment("")
    .build()
