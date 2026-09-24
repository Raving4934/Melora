package com.leyu.melora.playback

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.leyu.melora.playback.local.LocalSortField
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 同一字段表负责v1导出、预校验、批量持久化与内存发布，避免收集/恢复两份字段清单漂移。 */
internal object BackupSettings {
    private class Field<T : Any>(
        val name: String, val key: String, val state: MutableStateFlow<T>,
        val decode: (Any) -> T, val encode: (T) -> Any,
    ) {
        fun current(): Any = encode(state.value)
        fun normalize(value: Any): Any = encode(decode(value))
        fun publish(value: Any) { state.value = decode(value) }
    }
    private fun <T : Any> field(
        name: String, key: String, state: MutableStateFlow<T>,
        decode: (Any) -> T, encode: (T) -> Any = { it },
    ) = Field(name, key, state, decode, encode)

    private val fields = listOf(
        field("autoPlayOnStart", MeloraSettings.KEY_AUTO_PLAY, MeloraSettings.autoPlayOnStart, ::backupBoolean),
        field("lastTab", MeloraSettings.KEY_LAST_TAB, MeloraSettings.lastTab, { backupNumber("lastTab", it).toInt() }),
        field("showExitButton", MeloraSettings.KEY_SHOW_EXIT, MeloraSettings.showExitButton, ::backupBoolean),
        field("themeMode", THEME_MODE_PREFERENCE, MeloraSettings.themeMode, { ThemeMode.restore(it as? String, null, MeloraSettings.themeMode.value) }, ThemeMode::storageValue),
        field("hideStatusBar", MeloraSettings.KEY_HIDE_STATUS_BAR, MeloraSettings.hideStatusBar, ::backupBoolean),
        field("blurTopBar", MeloraSettings.KEY_BLUR_TOP_BAR, MeloraSettings.blurTopBar, ::backupBoolean),
        field("showSongCovers", MeloraSettings.KEY_SHOW_SONG_COVERS, MeloraSettings.showSongCovers, ::backupBoolean),
        field("pullToRefresh", MeloraSettings.KEY_PULL_REFRESH, MeloraSettings.pullToRefresh, ::backupBoolean),
        field("sourceAliasEnabled", MeloraSettings.KEY_SOURCE_ALIAS, MeloraSettings.sourceAliasEnabled, ::backupBoolean),
        field("searchPlatform", MeloraSettings.KEY_SEARCH_PLATFORM, MeloraSettings.searchPlatform, ::backupString),
        field("leaderboardPlatform", MeloraSettings.KEY_LEADERBOARD_PLATFORM, MeloraSettings.leaderboardPlatform, ::backupString),
        field("playlistPlatform", MeloraSettings.KEY_PLAYLIST_PLATFORM, MeloraSettings.playlistPlatform, ::backupString),
        field("downloadPath", MeloraSettings.KEY_DOWNLOAD_PATH, MeloraSettings.downloadPath, ::backupString),
        field("rememberProgress", MeloraSettings.KEY_REMEMBER_PROGRESS, MeloraSettings.rememberProgress, ::backupBoolean),
        field("autoClearPlayed", MeloraSettings.KEY_AUTO_CLEAR_PLAYED, MeloraSettings.autoClearPlayed, ::backupBoolean),
        field("pauseOnOtherAudio", MeloraSettings.KEY_PAUSE_OTHER_AUDIO, MeloraSettings.pauseOnOtherAudio, ::backupBoolean),
        field("audioEffectPreset", MeloraSettings.KEY_AUDIO_EFFECT, MeloraSettings.audioEffectPreset, { AudioEffects.restoreId(it as? String, MeloraSettings.audioEffectPreset.value) }),
        field("playerThemeMode", MeloraSettings.KEY_PLAYER_THEME_MODE, MeloraSettings.playerThemeMode, { ThemeMode.restore(it as? String, null, MeloraSettings.playerThemeMode.value) }, ThemeMode::storageValue),
        field("keepScreenAwake", MeloraSettings.KEY_KEEP_SCREEN_AWAKE, MeloraSettings.keepScreenAwake, ::backupBoolean),
        field("miniLyricsEnabled", MeloraSettings.KEY_MINI_LYRICS_ENABLED, MeloraSettings.miniLyricsEnabled, ::backupBoolean),
        field("playerCoverStyle", MeloraSettings.KEY_PLAYER_COVER_STYLE, MeloraSettings.playerCoverStyle, { PlayerCoverStyle.restore(it as? String, MeloraSettings.playerCoverStyle.value) }, PlayerCoverStyle::storageValue),
        field("musicPlayMode", MeloraSettings.KEY_MUSIC_PLAY_MODE, MeloraSettings.musicPlayMode, { PlayMode.restore(it as? String, MeloraSettings.musicPlayMode.value) }, PlayMode::storageValue),
        field("playerLyrics", MeloraSettings.KEY_PLAYER_LYRICS, MeloraSettings.playerLyrics,
            { LyricsUiConfig.fromJson(it as? JSONObject ?: error("全屏歌词设置无效")) }, LyricsUiConfig::toJson),
        field("notificationCover", MeloraSettings.KEY_NOTIFICATION_COVER, MeloraSettings.showNotificationCover, ::backupBoolean),
        field("autoSwitchSource", MeloraSettings.KEY_AUTO_SWITCH_SOURCE, MeloraSettings.autoSwitchSource, ::backupBoolean),
        field("playQualityWifi", MeloraSettings.KEY_QUALITY_WIFI, MeloraSettings.playQualityWifi, ::backupString),
        field("playQualityMobile", MeloraSettings.KEY_QUALITY_MOBILE, MeloraSettings.playQualityMobile, ::backupString),
        field("downloadQuality", MeloraSettings.KEY_DOWNLOAD_QUALITY, MeloraSettings.downloadQuality, ::backupString),
        field("downloadSkipSameName", MeloraSettings.KEY_DL_SKIP_SAME, MeloraSettings.downloadSkipSameName, ::backupBoolean),
        field("downloadAutoSwitchSource", MeloraSettings.KEY_DL_AUTO_SWITCH, MeloraSettings.downloadAutoSwitchSource, ::backupBoolean),
        field("downloadConcurrentTasks", MeloraSettings.KEY_DL_CONCURRENT, MeloraSettings.downloadConcurrentTasks, { backupNumber("downloadConcurrentTasks", it).toInt() }),
        field("downloadFileNameFormat", MeloraSettings.KEY_DL_NAME_FORMAT, MeloraSettings.downloadFileNameFormat, ::backupString),
        field("downloadEmbedCover", MeloraSettings.KEY_DL_EMBED_COVER, MeloraSettings.downloadEmbedCover, ::backupBoolean),
        field("downloadEmbedLyric", MeloraSettings.KEY_DL_EMBED_LYRIC, MeloraSettings.downloadEmbedLyric, ::backupBoolean),
        field("maxCacheMb", MeloraSettings.KEY_MAX_CACHE_MB, MeloraSettings.maxCacheMb, { backupNumber("maxCacheMb", it).toInt() }),
        field("localUseMediaStore", MeloraSettings.KEY_LOCAL_MEDIA_STORE, MeloraSettings.localUseMediaStore, ::backupBoolean),
        field("localFolders", MeloraSettings.KEY_LOCAL_FOLDERS, MeloraSettings.localFolders, ::backupFolders, { JSONArray(it) }),
        field("localExcludeShort", MeloraSettings.KEY_LOCAL_EXCLUDE_SHORT, MeloraSettings.localExcludeShort, ::backupBoolean),
        field("localExcludeSmall", MeloraSettings.KEY_LOCAL_EXCLUDE_SMALL, MeloraSettings.localExcludeSmall, ::backupBoolean),
        field("localAutoFillInfo", MeloraSettings.KEY_LOCAL_AUTO_FILL, MeloraSettings.localAutoFillInfo, ::backupBoolean),
        field("localSortField", MeloraSettings.KEY_LOCAL_SORT_FIELD, MeloraSettings.localSortField, { LocalSortField.restore(it as? String) }, LocalSortField::storageValue),
        field("localSortAscending", MeloraSettings.KEY_LOCAL_SORT_ASCENDING, MeloraSettings.localSortAscending, ::backupBoolean),
        field("showDesktopLyrics", MeloraSettings.KEY_DESKTOP_LYRICS, MeloraSettings.showDesktopLyrics, ::backupBoolean),
        field("lockLyrics", MeloraSettings.KEY_LYRICS_LOCK, MeloraSettings.lockLyrics, ::backupBoolean),
        field("singleLineLyric", MeloraSettings.KEY_LYRICS_SINGLE_LINE, MeloraSettings.singleLineLyric, ::backupBoolean),
        field("lyricFontSize", MeloraSettings.KEY_LYRICS_FONT_SIZE, MeloraSettings.lyricFontSize, { backupNumber("lyricFontSize", it).toFloat() }),
        field("lyricMaxLines", MeloraSettings.KEY_LYRICS_MAX_LINES, MeloraSettings.lyricMaxLines, { backupNumber("lyricMaxLines", it).toFloat() }),
        field("lyricAlpha", MeloraSettings.KEY_LYRICS_ALPHA, MeloraSettings.lyricAlpha, { backupNumber("lyricAlpha", it).toFloat() }),
        field("lyricHAlign", MeloraSettings.KEY_LYRICS_H_ALIGN, MeloraSettings.lyricHAlign, { backupNumber("lyricHAlign", it).toInt() }),
        field("lyricVAlign", MeloraSettings.KEY_LYRICS_V_ALIGN, MeloraSettings.lyricVAlign, { backupNumber("lyricVAlign", it).toInt() }),
        field("desktopLyricPosition", MeloraSettings.KEY_DESKTOP_LYRIC_POSITION, MeloraSettings.desktopLyricPosition,
            { DesktopLyricPosition.fromJson(it as? JSONObject ?: error("桌面歌词位置无效")) }, DesktopLyricPosition::toJson),
        field("lyricColorIndex", MeloraSettings.KEY_LYRICS_COLOR_INDEX, MeloraSettings.lyricColorIndex, { backupNumber("lyricColorIndex", it).toInt() }),
        field("playlistTagId", MeloraSettings.KEY_PLAYLIST_TAG_ID, MeloraSettings.playlistTagId, ::backupString),
        field("playlistTagName", MeloraSettings.KEY_PLAYLIST_TAG_NAME, MeloraSettings.playlistTagName, ::backupString),
        field("playlistSort", MeloraSettings.KEY_PLAYLIST_SORT, MeloraSettings.playlistSort, { backupNumber("playlistSort", it).toInt() }),
        field("lyricBackground", MeloraSettings.KEY_LYRICS_BACKGROUND, MeloraSettings.lyricBackground, ::backupBoolean),
        field("notificationLyrics", MeloraSettings.KEY_NOTIFICATION_LYRICS, MeloraSettings.notificationLyrics, ::backupBoolean),
    )

    fun collect(): JSONObject = JSONObject().apply { fields.forEach { put(it.name, it.current()) } }

    /** 旧备份可只含部分字段；未知枚举沿用旧兼容策略，损坏类型/数值拒绝整份导入。 */
    fun prepare(node: JSONObject, readable: Set<String>, writable: Set<String>): Pair<JSONObject, String> {
        val input = JSONObject(node.toString())
        val legacyTheme = input.opt("followSystemTheme").let {
            (it as? Boolean) ?: (it as? String)?.lowercase()?.toBooleanStrictOrNull()
        }
        input.put("themeMode", ThemeMode.restore(input.opt("themeMode") as? String, legacyTheme, MeloraSettings.themeMode.value).storageValue)
        if (!input.has("playQualityWifi") && input.has("playQuality")) input.put("playQualityWifi", input.get("playQuality"))
        val prepared = JSONObject()
        fields.forEach { field ->
            if (input.has(field.name)) prepared.put(field.name, field.normalize(input.get(field.name)))
        }
        var notice = ""
        if (prepared.has("downloadPath")) {
            val saved = prepared.getString("downloadPath")
            val restored = restoredDownloadPath(saved, writable)
            prepared.put("downloadPath", restored)
            if (saved != restored) notice += "下载目录需要重新授权，已恢复默认目录。"
        }
        if (prepared.has("localFolders")) {
            val folders = backupFolders(prepared.get("localFolders"))
            val allowed = folders.filter { it in readable }
            prepared.put("localFolders", JSONArray(allowed))
            if (allowed.size != folders.size) notice += "部分本地音乐目录需要重新授权。"
        }
        return prepared to notice
    }

    fun validate(node: JSONObject) {
        // 权限只在真正导入时过滤；这里只完成所有已知字段的类型/范围校验。
        prepare(node, backupFolders(node.optJSONArray("localFolders") ?: JSONArray()).toSet(),
            setOf(node.optString("downloadPath")))
    }

    fun apply(prepared: JSONObject) {
        val values = fields.filter { prepared.has(it.name) }
        MeloraSettings.commitBackupValues(values.associate { field ->
            val value = field.normalize(prepared.get(field.name))
            field.key to if (value is JSONArray || value is JSONObject) value.toString() else value
        })
        values.forEach { it.publish(prepared.get(it.name)) }
    }
}

internal fun backupBoolean(value: Any): Boolean = when (value) {
    is Boolean -> value
    is String -> value.lowercase().toBooleanStrictOrNull() ?: error("备份布尔值无效")
    else -> error("备份布尔值无效")
}
private fun backupString(value: Any): String = (value as? String)?.also {
    require(it.length <= 16_384) { "备份设置文字过长" }
} ?: error("备份文字字段无效")
internal fun backupFolders(value: Any): List<String> {
    require(value is JSONArray && value.length() <= 256) { "本地目录列表无效或过大" }
    return List(value.length()) { backupString(value.get(it)).also { path ->
        require(path.isNotBlank()) { "本地目录不能为空" }
    } }.distinct()
}
private fun backupNumber(name: String, value: Any): Number {
    val number = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull()
        ?: error("$name 数值无效")
    val range = when (name) {
        "lastTab" -> 0.0..7.0
        "playlistSort" -> 0.0..1.0
        "downloadConcurrentTasks" -> 1.0..5.0
        "maxCacheMb" -> 0.0..1_048_576.0
        "lyricFontSize" -> 8.0..100.0
        "lyricAlpha" -> 0.0..100.0
        "lyricMaxLines" -> 1.0..20.0
        "lyricHAlign", "lyricVAlign" -> 0.0..2.0
        "lyricColorIndex" -> 0.0..32.0
        else -> error("未知数值设置$name")
    }
    require(number.isFinite() && number in range) { "$name 数值超出范围" }
    if (name !in setOf("lyricFontSize", "lyricMaxLines", "lyricAlpha")) {
        require(number == number.toInt().toDouble()) { "$name 必须为整数" }
    }
    return number
}

/** SharedPreferences只在一个commit中写入；同样用于事务回滚，失败必须显式上报。 */
@SuppressLint("UseKtx") // KTX.edit返回Unit，会丢失commit=false；恢复事务必须检测真实写入失败。
internal fun SharedPreferences.replaceValues(values: Map<String, *>, clear: Boolean = false) {
    val editor = edit()
    if (clear) editor.clear()
    values.forEach { (key, value) ->
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            else -> error("不支持的设置类型: $key")
        }
    }
    check(editor.commit()) { "无法保存设置，恢复操作已中止" }
}
