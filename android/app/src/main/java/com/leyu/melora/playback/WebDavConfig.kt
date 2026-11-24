package com.leyu.melora.playback

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
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
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
    private val lock = Any()

    fun load(context: Context): WebDavConfig? = synchronized(lock) {
        val file = configFile(context)
        if (!file.isFile) return@synchronized null
        try {
            val envelope = JSONObject(file.inputStream().use { it.readBackupText(LIMIT) })
            val decoder = Base64.getDecoder()
            val cipher = Cipher.getInstance(CIPHER)
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: error("缺少本机密钥")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoder.decode(envelope.getString("iv"))))
            val json = JSONObject(cipher.doFinal(decoder.decode(envelope.getString("data"))).toString(Charsets.UTF_8))
            WebDavConfig(json.getString("server"), json.getString("account"), json.getString("password"),
                json.getString("folder")).validated()
        } catch (_: Exception) {
            throw IllegalStateException("无法读取本机 WebDAV 配置，请重新填写并保存")
        }
    }

    fun save(context: Context, config: WebDavConfig): WebDavConfig = synchronized(lock) {
        val validated = config.validated()
        try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: createKey()
            val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key) }
            val json = JSONObject().put("server", validated.server).put("account", validated.account)
                .put("password", validated.password).put("folder", validated.folder)
            val encoder = Base64.getEncoder()
            val envelope = JSONObject().put("iv", encoder.encodeToString(cipher.iv))
                .put("data", encoder.encodeToString(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))))
            writeTextAtomically(configFile(context), envelope.toString())
        } catch (_: Exception) {
            throw IllegalStateException("无法在本机保存 WebDAV 配置")
        }
        validated
    }

    fun deviceId(context: Context): String = synchronized(lock) {
        val file = File(context.noBackupFilesDir, "webdav-device-id")
        if (file.isFile) return@synchronized UUID.fromString(file.readText()).toString()
        UUID.randomUUID().toString().also { writeTextAtomically(file, it) }
    }

    private fun configFile(context: Context) = File(context.noBackupFilesDir, "webdav-config.json")

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
