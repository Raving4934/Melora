package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.leyu.melora.playback.local.LocalSortField
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局设置中心：所有设置项持久化于 SharedPreferences 并以 StateFlow 暴露给 UI 与播放层。
 * 替代各页面零散的 remember 状态，保证设置真正生效且重启保留。
 */
object MeloraSettings {
    internal const val PREFS = "melora-settings"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            remove(LEGACY_BUILTIN_KUWO_UNLOCKED)
            remove(LEGACY_BUILTIN_KUWO_ENABLED)
        }
        load()
        initialized = true
    }

    internal fun commitBackupValues(values: Map<String, Any>) {
        if (initialized) prefs.replaceValues(values)
    }

    internal fun reloadAfterRestore() { if (initialized) load() }

    private fun persist(block: SharedPreferences.Editor.() -> Unit) {
        if (!initialized) return
        prefs.edit { block() }
    }

    // --- 基本设置 ---
    val autoPlayOnStart = MutableStateFlow(false)
    // 上次停留的主页 Tab：启动时恢复（0 搜索 / 1 排行榜 / 2 发现 / 3 歌单 / 4 听书 / 5 本地歌曲 / 6 我的 / 7 设置）
    val lastTab = MutableStateFlow(5)
    val showExitButton = MutableStateFlow(true)
    val themeMode = MutableStateFlow(ThemeMode.Auto)
    val hideStatusBar = MutableStateFlow(false)
    val blurTopBar = MutableStateFlow(false)
    val showSongCovers = MutableStateFlow(false)
    val pullToRefresh = MutableStateFlow(true)
    val downloadPath = MutableStateFlow(DEFAULT_DOWNLOAD_PATH)
    val downloadQuality = MutableStateFlow("320k")
    // 下载设置：同名跳过 / 自动换源 / 并发数 / 命名格式 / 嵌入内容
    val downloadSkipSameName = MutableStateFlow(true)
    val downloadAutoSwitchSource = MutableStateFlow(true)
    val downloadConcurrentTasks = MutableStateFlow(2)
    val downloadFileNameFormat = MutableStateFlow("song-artist")
    val downloadEmbedCover = MutableStateFlow(false)
    val downloadEmbedLyric = MutableStateFlow(false)

    // --- 音源设置 ---
    // 数据通道：auto=App 优先自动回退，app=App 优先，web=网页端优先
    val dataChannel = MutableStateFlow("auto")
    val autoSwitchSource = MutableStateFlow(true)

    // --- 页面平台选择（持久化） ---
    val searchPlatform = MutableStateFlow("all")
    val leaderboardPlatform = MutableStateFlow("kw")
    val playlistPlatform = MutableStateFlow("kw")
    // 歌单页筛选：分类与排序（排序仅酷我"最热/最新"有效）
    val playlistTagId = MutableStateFlow("")
    val playlistTagName = MutableStateFlow("全部歌单")
    val playlistSort = MutableStateFlow(0)

    // --- 播放设置 ---
    val rememberProgress = MutableStateFlow(true)
    val autoClearPlayed = MutableStateFlow(false)
    val pauseOnOtherAudio = MutableStateFlow(true)
    val playerThemeMode = MutableStateFlow(ThemeMode.Auto)
    val keepScreenAwake = MutableStateFlow(false)
    val miniLyricsEnabled = MutableStateFlow(true)
    val playerCoverStyle = MutableStateFlow(PlayerCoverStyle.Default)
    // 音效预设ID由AudioEffects唯一目录校验，实际应用结果由播放服务发布。
    val audioEffectPreset = MutableStateFlow("off")
    val showNotificationCover = MutableStateFlow(true)
    val playQualityWifi = MutableStateFlow("320k")
    val playQualityMobile = MutableStateFlow("128k")
    val maxCacheMb = MutableStateFlow(1024)

    // --- 桌面歌词 ---
    val showDesktopLyrics = MutableStateFlow(false)
    val lockLyrics = MutableStateFlow(false)
    val lyricAnimEnabled = MutableStateFlow(true)
    val singleLineLyric = MutableStateFlow(false)
    val lyricFontSize = MutableStateFlow(20f)
    val lyricWindowPercent = MutableStateFlow(85f)
    val lyricMaxLines = MutableStateFlow(2f)
    val lyricAlpha = MutableStateFlow(90f)
    val lyricHAlign = MutableStateFlow(1)
    val lyricVAlign = MutableStateFlow(1)
    val lyricColorIndex = MutableStateFlow(1)
    // 桌面歌词底板：默认关闭=纯文字（阴影保证浅色壁纸可读），开=圆角深色底板
    val lyricBackground = MutableStateFlow(false)
    // 通知栏歌词：开启后系统通知栏（控制中心/锁屏）实时显示当前歌词行
    val notificationLyrics = MutableStateFlow(false)
    // 歌曲来源显示名称：默认显示别名（盒子音乐等），关闭后显示原名
    val sourceAliasEnabled = MutableStateFlow(true)

    // --- 本地歌曲 ---
    val localUseMediaStore = MutableStateFlow(true)
    val localFolders = MutableStateFlow<List<String>>(emptyList())
    val localExcludeShort = MutableStateFlow(true)
    val localExcludeSmall = MutableStateFlow(true)
    val localAutoFillInfo = MutableStateFlow(false)
    internal val localSortField = MutableStateFlow(LocalSortField.FileName)
    internal val localSortAscending = MutableStateFlow(true)

    private fun load() {
        autoPlayOnStart.value = prefs.getBoolean(KEY_AUTO_PLAY, false)
        lastTab.value = prefs.getInt(KEY_LAST_TAB, 5)
        showExitButton.value = prefs.getBoolean(KEY_SHOW_EXIT, true)
        themeMode.value = loadThemeMode(prefs)
        hideStatusBar.value = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)
        blurTopBar.value = prefs.getBoolean(KEY_BLUR_TOP_BAR, false)
        showSongCovers.value = prefs.getBoolean(KEY_SHOW_SONG_COVERS, false)
        pullToRefresh.value = prefs.getBoolean(KEY_PULL_REFRESH, true)
        downloadPath.value = prefs.getString(KEY_DOWNLOAD_PATH, DEFAULT_DOWNLOAD_PATH) ?: DEFAULT_DOWNLOAD_PATH
        downloadQuality.value = prefs.getString(KEY_DOWNLOAD_QUALITY, "320k") ?: "320k"
        downloadSkipSameName.value = prefs.getBoolean(KEY_DL_SKIP_SAME, true)
        downloadAutoSwitchSource.value = prefs.getBoolean(KEY_DL_AUTO_SWITCH, true)
        downloadConcurrentTasks.value = prefs.getInt(KEY_DL_CONCURRENT, 2)
        downloadFileNameFormat.value = prefs.getString(KEY_DL_NAME_FORMAT, "song-artist") ?: "song-artist"
        downloadEmbedCover.value = prefs.getBoolean(KEY_DL_EMBED_COVER, false)
        downloadEmbedLyric.value = prefs.getBoolean(KEY_DL_EMBED_LYRIC, false)
        autoSwitchSource.value = prefs.getBoolean(KEY_AUTO_SWITCH_SOURCE, true)
        dataChannel.value = prefs.getString(KEY_DATA_CHANNEL, "auto") ?: "auto"
        searchPlatform.value = prefs.getString(KEY_SEARCH_PLATFORM, "all") ?: "all"
        leaderboardPlatform.value = prefs.getString(KEY_LEADERBOARD_PLATFORM, "kw") ?: "kw"
        playlistPlatform.value = prefs.getString(KEY_PLAYLIST_PLATFORM, "kw") ?: "kw"
        playlistTagId.value = prefs.getString(KEY_PLAYLIST_TAG_ID, "") ?: ""
        playlistTagName.value = prefs.getString(KEY_PLAYLIST_TAG_NAME, "全部歌单") ?: "全部歌单"
        playlistSort.value = prefs.getInt(KEY_PLAYLIST_SORT, 0)
        rememberProgress.value = prefs.getBoolean(KEY_REMEMBER_PROGRESS, true)
        autoClearPlayed.value = prefs.getBoolean(KEY_AUTO_CLEAR_PLAYED, false)
        pauseOnOtherAudio.value = prefs.getBoolean(KEY_PAUSE_OTHER_AUDIO, true)
        playerThemeMode.value = ThemeMode.restore(
            prefs.getString(KEY_PLAYER_THEME_MODE, null),
            legacyFollowSystem = null,
            fallback = ThemeMode.Auto,
        )
        keepScreenAwake.value = prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)
        miniLyricsEnabled.value = prefs.getBoolean(KEY_MINI_LYRICS_ENABLED, true)
        playerCoverStyle.value = PlayerCoverStyle.restore(
            prefs.getString(KEY_PLAYER_COVER_STYLE, null),
        )
        audioEffectPreset.value = AudioEffects.restoreId(prefs.getString(KEY_AUDIO_EFFECT, null))
        showNotificationCover.value = prefs.getBoolean(KEY_NOTIFICATION_COVER, true)
        // 旧版单一音质键迁移为 WiFi 音质
        playQualityWifi.value = prefs.getString(KEY_QUALITY_WIFI, prefs.getString(KEY_QUALITY, "320k")) ?: "320k"
        playQualityMobile.value = prefs.getString(KEY_QUALITY_MOBILE, "128k") ?: "128k"
        maxCacheMb.value = prefs.getInt(KEY_MAX_CACHE_MB, 1024)
        showDesktopLyrics.value = prefs.getBoolean(KEY_DESKTOP_LYRICS, false)
        lockLyrics.value = prefs.getBoolean(KEY_LYRICS_LOCK, false)
        lyricAnimEnabled.value = prefs.getBoolean(KEY_LYRICS_ANIM, true)
        singleLineLyric.value = prefs.getBoolean(KEY_LYRICS_SINGLE_LINE, false)
        lyricFontSize.value = prefs.getFloat(KEY_LYRICS_FONT_SIZE, 20f)
        lyricWindowPercent.value = prefs.getFloat(KEY_LYRICS_WINDOW_PERCENT, 85f)
        lyricMaxLines.value = prefs.getFloat(KEY_LYRICS_MAX_LINES, 2f)
        lyricAlpha.value = prefs.getFloat(KEY_LYRICS_ALPHA, 90f)
        lyricHAlign.value = prefs.getInt(KEY_LYRICS_H_ALIGN, 1)
        lyricVAlign.value = prefs.getInt(KEY_LYRICS_V_ALIGN, 1)
        lyricColorIndex.value = prefs.getInt(KEY_LYRICS_COLOR_INDEX, 1)
        lyricBackground.value = prefs.getBoolean(KEY_LYRICS_BACKGROUND, false)
        notificationLyrics.value = prefs.getBoolean(KEY_NOTIFICATION_LYRICS, false)
        sourceAliasEnabled.value = prefs.getBoolean(KEY_SOURCE_ALIAS, true)
        localUseMediaStore.value = prefs.getBoolean(KEY_LOCAL_MEDIA_STORE, true)
        localFolders.value = runCatching {
            org.json.JSONArray(prefs.getString(KEY_LOCAL_FOLDERS, "[]") ?: "[]").let { array ->
                List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
            }
        }.getOrElse { emptyList() }
        localExcludeShort.value = prefs.getBoolean(KEY_LOCAL_EXCLUDE_SHORT, true)
        localExcludeSmall.value = prefs.getBoolean(KEY_LOCAL_EXCLUDE_SMALL, true)
        localAutoFillInfo.value = prefs.getBoolean(KEY_LOCAL_AUTO_FILL, false)
        localSortField.value = LocalSortField.restore(prefs.getString(KEY_LOCAL_SORT_FIELD, null))
        localSortAscending.value = prefs.getBoolean(KEY_LOCAL_SORT_ASCENDING, true)
    }

    fun updateAutoPlay(value: Boolean) = synchronized(BackupStateLock.monitor) { autoPlayOnStart.value = value; persist { putBoolean(KEY_AUTO_PLAY, value) } }
    fun updateLastTab(value: Int) = synchronized(BackupStateLock.monitor) { lastTab.value = value; persist { putInt(KEY_LAST_TAB, value) } }
    fun updateShowExit(value: Boolean) = synchronized(BackupStateLock.monitor) { showExitButton.value = value; persist { putBoolean(KEY_SHOW_EXIT, value) } }
    fun updateThemeMode(value: ThemeMode) = synchronized(BackupStateLock.monitor) { themeMode.value = value; persist { putString(THEME_MODE_PREFERENCE, value.storageValue) } }
    fun updateHideStatusBar(value: Boolean) = synchronized(BackupStateLock.monitor) { hideStatusBar.value = value; persist { putBoolean(KEY_HIDE_STATUS_BAR, value) } }
    fun updateBlurTopBar(value: Boolean) = synchronized(BackupStateLock.monitor) { blurTopBar.value = value; persist { putBoolean(KEY_BLUR_TOP_BAR, value) } }
    fun updateShowSongCovers(value: Boolean) = synchronized(BackupStateLock.monitor) { showSongCovers.value = value; persist { putBoolean(KEY_SHOW_SONG_COVERS, value) } }
    fun updatePullToRefresh(value: Boolean) = synchronized(BackupStateLock.monitor) { pullToRefresh.value = value; persist { putBoolean(KEY_PULL_REFRESH, value) } }
    fun updateSearchPlatform(value: String) = synchronized(BackupStateLock.monitor) { searchPlatform.value = value; persist { putString(KEY_SEARCH_PLATFORM, value) } }
    fun updateLeaderboardPlatform(value: String) = synchronized(BackupStateLock.monitor) { leaderboardPlatform.value = value; persist { putString(KEY_LEADERBOARD_PLATFORM, value) } }
    fun updatePlaylistPlatform(value: String) = synchronized(BackupStateLock.monitor) { playlistPlatform.value = value; persist { putString(KEY_PLAYLIST_PLATFORM, value) } }
    fun updatePlaylistTag(id: String, name: String) = synchronized(BackupStateLock.monitor) {
        playlistTagId.value = id
        playlistTagName.value = name
        persist { putString(KEY_PLAYLIST_TAG_ID, id); putString(KEY_PLAYLIST_TAG_NAME, name) }
    }
    fun updatePlaylistSort(value: Int) = synchronized(BackupStateLock.monitor) { playlistSort.value = value; persist { putInt(KEY_PLAYLIST_SORT, value) } }
    fun updateDownloadPath(value: String) = synchronized(BackupStateLock.monitor) { downloadPath.value = value; persist { putString(KEY_DOWNLOAD_PATH, value) } }
    fun updateDownloadQuality(value: String) = synchronized(BackupStateLock.monitor) { downloadQuality.value = value; persist { putString(KEY_DOWNLOAD_QUALITY, value) } }
    fun updateDownloadSkipSameName(value: Boolean) = synchronized(BackupStateLock.monitor) { downloadSkipSameName.value = value; persist { putBoolean(KEY_DL_SKIP_SAME, value) } }
    fun updateDownloadAutoSwitchSource(value: Boolean) = synchronized(BackupStateLock.monitor) { downloadAutoSwitchSource.value = value; persist { putBoolean(KEY_DL_AUTO_SWITCH, value) } }
    fun updateDownloadConcurrentTasks(value: Int) = synchronized(BackupStateLock.monitor) { downloadConcurrentTasks.value = value; persist { putInt(KEY_DL_CONCURRENT, value) } }
    fun updateDownloadFileNameFormat(value: String) = synchronized(BackupStateLock.monitor) { downloadFileNameFormat.value = value; persist { putString(KEY_DL_NAME_FORMAT, value) } }
    fun updateDownloadEmbedCover(value: Boolean) = synchronized(BackupStateLock.monitor) { downloadEmbedCover.value = value; persist { putBoolean(KEY_DL_EMBED_COVER, value) } }
    fun updateDownloadEmbedLyric(value: Boolean) = synchronized(BackupStateLock.monitor) { downloadEmbedLyric.value = value; persist { putBoolean(KEY_DL_EMBED_LYRIC, value) } }
    fun updateAutoSwitchSource(value: Boolean) = synchronized(BackupStateLock.monitor) { autoSwitchSource.value = value; persist { putBoolean(KEY_AUTO_SWITCH_SOURCE, value) } }
    fun updateDataChannel(value: String) = synchronized(BackupStateLock.monitor) { dataChannel.value = value; persist { putString(KEY_DATA_CHANNEL, value) } }
    fun updateRememberProgress(value: Boolean) = synchronized(BackupStateLock.monitor) { rememberProgress.value = value; persist { putBoolean(KEY_REMEMBER_PROGRESS, value) } }
    fun updateAutoClearPlayed(value: Boolean) = synchronized(BackupStateLock.monitor) { autoClearPlayed.value = value; persist { putBoolean(KEY_AUTO_CLEAR_PLAYED, value) } }
    fun updatePauseOnOtherAudio(value: Boolean) = synchronized(BackupStateLock.monitor) { pauseOnOtherAudio.value = value; persist { putBoolean(KEY_PAUSE_OTHER_AUDIO, value) } }
    fun updatePlayerThemeMode(value: ThemeMode) = synchronized(BackupStateLock.monitor) { playerThemeMode.value = value; persist { putString(KEY_PLAYER_THEME_MODE, value.storageValue) } }
    fun updateKeepScreenAwake(value: Boolean) = synchronized(BackupStateLock.monitor) { keepScreenAwake.value = value; persist { putBoolean(KEY_KEEP_SCREEN_AWAKE, value) } }
    fun updateMiniLyricsEnabled(value: Boolean) = synchronized(BackupStateLock.monitor) { miniLyricsEnabled.value = value; persist { putBoolean(KEY_MINI_LYRICS_ENABLED, value) } }
    fun updatePlayerCoverStyle(value: PlayerCoverStyle) = synchronized(BackupStateLock.monitor) { playerCoverStyle.value = value; persist { putString(KEY_PLAYER_COVER_STYLE, value.storageValue) } }
    fun toggleAudioEffectPreset(value: String) = synchronized(BackupStateLock.monitor) {
        // 读取即时值而不是UI捕获的快照，连续两次点击也能正确开/关。
        updateAudioEffectPreset(if (audioEffectPreset.value == value) AudioEffects.OFF else value)
    }
    fun updateAudioEffectPreset(value: String) = synchronized(BackupStateLock.monitor) {
        val id = AudioEffects.restoreId(value)
        audioEffectPreset.value = id
        persist { putString(KEY_AUDIO_EFFECT, id) }
    }
    fun updateNotificationCover(value: Boolean) = synchronized(BackupStateLock.monitor) { showNotificationCover.value = value; persist { putBoolean(KEY_NOTIFICATION_COVER, value) } }
    fun updatePlayQualityWifi(value: String) = synchronized(BackupStateLock.monitor) { playQualityWifi.value = value; persist { putString(KEY_QUALITY_WIFI, value) } }
    fun updatePlayQualityMobile(value: String) = synchronized(BackupStateLock.monitor) { playQualityMobile.value = value; persist { putString(KEY_QUALITY_MOBILE, value) } }
    fun updateMaxCacheMb(value: Int) = synchronized(BackupStateLock.monitor) { maxCacheMb.value = value; persist { putInt(KEY_MAX_CACHE_MB, value) } }

    fun updateLocalUseMediaStore(value: Boolean) = synchronized(BackupStateLock.monitor) { localUseMediaStore.value = value; persist { putBoolean(KEY_LOCAL_MEDIA_STORE, value) } }
    fun updateLocalFolders(value: List<String>) = synchronized(BackupStateLock.monitor) {
        localFolders.value = value
        persist { putString(KEY_LOCAL_FOLDERS, org.json.JSONArray(value).toString()) }
    }
    fun updateLocalExcludeShort(value: Boolean) = synchronized(BackupStateLock.monitor) { localExcludeShort.value = value; persist { putBoolean(KEY_LOCAL_EXCLUDE_SHORT, value) } }
    fun updateLocalExcludeSmall(value: Boolean) = synchronized(BackupStateLock.monitor) { localExcludeSmall.value = value; persist { putBoolean(KEY_LOCAL_EXCLUDE_SMALL, value) } }
    fun updateLocalAutoFillInfo(value: Boolean) = synchronized(BackupStateLock.monitor) { localAutoFillInfo.value = value; persist { putBoolean(KEY_LOCAL_AUTO_FILL, value) } }
    internal fun updateLocalSortField(value: LocalSortField) = synchronized(BackupStateLock.monitor) { localSortField.value = value; persist { putString(KEY_LOCAL_SORT_FIELD, value.storageValue) } }
    internal fun updateLocalSortAscending(value: Boolean) = synchronized(BackupStateLock.monitor) { localSortAscending.value = value; persist { putBoolean(KEY_LOCAL_SORT_ASCENDING, value) } }

    fun updateShowDesktopLyrics(value: Boolean) = synchronized(BackupStateLock.monitor) { showDesktopLyrics.value = value; persist { putBoolean(KEY_DESKTOP_LYRICS, value) } }
    fun updateLockLyrics(value: Boolean) = synchronized(BackupStateLock.monitor) { lockLyrics.value = value; persist { putBoolean(KEY_LYRICS_LOCK, value) } }
    fun updateLyricAnim(value: Boolean) = synchronized(BackupStateLock.monitor) { lyricAnimEnabled.value = value; persist { putBoolean(KEY_LYRICS_ANIM, value) } }
    fun updateSingleLine(value: Boolean) = synchronized(BackupStateLock.monitor) { singleLineLyric.value = value; persist { putBoolean(KEY_LYRICS_SINGLE_LINE, value) } }
    fun updateLyricFontSize(value: Float) = synchronized(BackupStateLock.monitor) { lyricFontSize.value = value; persist { putFloat(KEY_LYRICS_FONT_SIZE, value) } }
    fun updateLyricWindowPercent(value: Float) = synchronized(BackupStateLock.monitor) { lyricWindowPercent.value = value; persist { putFloat(KEY_LYRICS_WINDOW_PERCENT, value) } }
    fun updateLyricMaxLines(value: Float) = synchronized(BackupStateLock.monitor) { lyricMaxLines.value = value; persist { putFloat(KEY_LYRICS_MAX_LINES, value) } }
    fun updateLyricAlpha(value: Float) = synchronized(BackupStateLock.monitor) { lyricAlpha.value = value; persist { putFloat(KEY_LYRICS_ALPHA, value) } }
    fun updateLyricHAlign(value: Int) = synchronized(BackupStateLock.monitor) { lyricHAlign.value = value; persist { putInt(KEY_LYRICS_H_ALIGN, value) } }
    fun updateLyricVAlign(value: Int) = synchronized(BackupStateLock.monitor) { lyricVAlign.value = value; persist { putInt(KEY_LYRICS_V_ALIGN, value) } }
    fun updateLyricColorIndex(value: Int) = synchronized(BackupStateLock.monitor) { lyricColorIndex.value = value; persist { putInt(KEY_LYRICS_COLOR_INDEX, value) } }
    fun updateLyricBackground(value: Boolean) = synchronized(BackupStateLock.monitor) { lyricBackground.value = value; persist { putBoolean(KEY_LYRICS_BACKGROUND, value) } }
    fun updateNotificationLyrics(value: Boolean) = synchronized(BackupStateLock.monitor) { notificationLyrics.value = value; persist { putBoolean(KEY_NOTIFICATION_LYRICS, value) } }
    fun updateSourceAlias(value: Boolean) = synchronized(BackupStateLock.monitor) { sourceAliasEnabled.value = value; persist { putBoolean(KEY_SOURCE_ALIAS, value) } }

    const val DEFAULT_DOWNLOAD_PATH = "/storage/emulated/0/Music/Melora"
    internal const val KEY_AUTO_PLAY = "basic.autoPlayOnStart"
    internal const val KEY_LAST_TAB = "basic.lastTab"
    internal const val KEY_SHOW_EXIT = "basic.showExitButton"
    internal const val KEY_HIDE_STATUS_BAR = "basic.hideStatusBar"
    internal const val KEY_BLUR_TOP_BAR = "basic.blurTopBar"
    internal const val KEY_SHOW_SONG_COVERS = "basic.showSongCovers"
    internal const val KEY_PULL_REFRESH = "basic.pullToRefresh"
    internal const val KEY_DOWNLOAD_PATH = "basic.downloadPath"
    internal const val KEY_DOWNLOAD_QUALITY = "basic.downloadQuality"
    internal const val KEY_DL_SKIP_SAME = "download.skipSameName"
    internal const val KEY_DL_AUTO_SWITCH = "download.autoSwitchSource"
    internal const val KEY_DL_CONCURRENT = "download.concurrentTasks"
    internal const val KEY_DL_NAME_FORMAT = "download.fileNameFormat"
    internal const val KEY_DL_EMBED_COVER = "download.embedCover"
    internal const val KEY_DL_EMBED_LYRIC = "download.embedLyric"
    internal const val KEY_AUTO_SWITCH_SOURCE = "source.autoSwitch"
    internal const val KEY_DATA_CHANNEL = "source.dataChannel"
    internal const val KEY_SEARCH_PLATFORM = "ui.searchPlatform"
    internal const val KEY_LEADERBOARD_PLATFORM = "ui.leaderboardPlatform"
    internal const val KEY_PLAYLIST_PLATFORM = "ui.playlistPlatform"
    internal const val KEY_PLAYLIST_TAG_ID = "ui.playlistTagId"
    internal const val KEY_PLAYLIST_TAG_NAME = "ui.playlistTagName"
    internal const val KEY_PLAYLIST_SORT = "ui.playlistSort"
    internal const val KEY_REMEMBER_PROGRESS = "playback.rememberProgress"
    internal const val KEY_AUTO_CLEAR_PLAYED = "playback.autoClearPlayed"
    internal const val KEY_PAUSE_OTHER_AUDIO = "playback.pauseOnOtherAudio"
    internal const val KEY_PLAYER_THEME_MODE = "player.themeMode"
    internal const val KEY_KEEP_SCREEN_AWAKE = "player.keepScreenAwake"
    internal const val KEY_MINI_LYRICS_ENABLED = "player.miniLyricsEnabled"
    internal const val KEY_PLAYER_COVER_STYLE = "player.coverStyle"
    internal const val KEY_AUDIO_EFFECT = "playback.audioEffect"
    internal const val KEY_NOTIFICATION_COVER = "playback.notificationCover"
    private const val KEY_QUALITY = "playback.quality"
    internal const val KEY_QUALITY_WIFI = "playback.qualityWifi"
    internal const val KEY_QUALITY_MOBILE = "playback.qualityMobile"
    internal const val KEY_MAX_CACHE_MB = "playback.maxCacheMb"
    internal const val KEY_DESKTOP_LYRICS = "lyrics.show"
    internal const val KEY_LYRICS_LOCK = "lyrics.lock"
    internal const val KEY_LYRICS_ANIM = "lyrics.anim"
    internal const val KEY_LYRICS_BACKGROUND = "lyrics.background"
    internal const val KEY_NOTIFICATION_LYRICS = "lyrics.notification"
    private const val LEGACY_BUILTIN_KUWO_UNLOCKED = "source.builtinKuwoUnlocked"
    private const val LEGACY_BUILTIN_KUWO_ENABLED = "source.builtinKuwoEnabled"
    internal const val KEY_SOURCE_ALIAS = "source.alias"
    internal const val KEY_LYRICS_SINGLE_LINE = "lyrics.singleLine"
    internal const val KEY_LYRICS_FONT_SIZE = "lyrics.fontSize"
    internal const val KEY_LYRICS_WINDOW_PERCENT = "lyrics.windowPercent"
    internal const val KEY_LYRICS_MAX_LINES = "lyrics.maxLines"
    internal const val KEY_LYRICS_ALPHA = "lyrics.alpha"
    internal const val KEY_LYRICS_H_ALIGN = "lyrics.hAlign"
    internal const val KEY_LYRICS_V_ALIGN = "lyrics.vAlign"
    internal const val KEY_LYRICS_COLOR_INDEX = "lyrics.colorIndex"
    internal const val KEY_LOCAL_MEDIA_STORE = "local.useMediaStore"
    internal const val KEY_LOCAL_FOLDERS = "local.folders"
    internal const val KEY_LOCAL_EXCLUDE_SHORT = "local.excludeShort"
    internal const val KEY_LOCAL_EXCLUDE_SMALL = "local.excludeSmall"
    internal const val KEY_LOCAL_AUTO_FILL = "local.autoFillInfo"
    internal const val KEY_LOCAL_SORT_FIELD = "local.sortField"
    internal const val KEY_LOCAL_SORT_ASCENDING = "local.sortAscending"
}
