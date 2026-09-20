package com.leyu.melora

import android.app.Application
import android.util.Log
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.leyu.melora.playback.BackupManager
import com.leyu.melora.playback.CrashLogger
import com.leyu.melora.playback.DownloadCenter
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.playback.sdk.LxScriptPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MeloraApplication : Application(), SingletonImageLoader.Factory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        // 尽早安装崩溃捕获，连设置初始化阶段的异常也能留下现场。
        CrashLogger.init(this)
        // 正常启动仅检查日志是否存在；中断恢复须早于Activity与后台Service读取任何设置。
        BackupManager.recoverInterruptedRestore(this)
        MeloraSettings.init(this)

        // 用户库、下载记录和一次性音源迁移互不依赖，并行放到 IO 线程；
        // Activity 等待统一屏障后再首次组合，避免先渲染空列表再整体闪变。
        startupScope.launch {
            coroutineScope {
                listOf(
                    async { runStartupStep("用户库") { UserLibrary.init(this@MeloraApplication) } },
                    async { runStartupStep("下载记录") { DownloadCenter.init(this@MeloraApplication) } },
                    async { runStartupStep("本地媒体") { LocalMediaStore.init(this@MeloraApplication) } },
                    async { runStartupStep("历史音源迁移") { removeLegacyPresetSources() } },
                ).forEach { it.await() }
            }
            withContext(Dispatchers.Main.immediate) {
                PlaybackController.init(this@MeloraApplication)
                LxScriptPool.warmEnabled(this@MeloraApplication)
                startupReady.complete(Unit)
            }
        }
    }

    suspend fun awaitStartup() = startupReady.await()

    private inline fun runStartupStep(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.e(TAG, "$name 初始化失败", it) }
    }

    // 应用完全使用用户自行导入的音源脚本；一次性清理历史版本随包自动安装的副本。
    private fun removeLegacyPresetSources() {
        val migration = getSharedPreferences("melora-migration", MODE_PRIVATE)
        if (migration.getBoolean(KEY_LEGACY_PRESETS_REMOVED, false)) return
        val store = LxScriptStore(this)
        LEGACY_PRESET_IDS.forEach { id ->
            if (store.code(id) != null) store.remove(id)
        }
        migration.edit { putBoolean(KEY_LEGACY_PRESETS_REMOVED, true) }
    }

    private companion object {
        const val TAG = "MeloraStartup"
        const val KEY_LEGACY_PRESETS_REMOVED = "legacy-preset-sources-removed"
        val LEGACY_PRESET_IDS = listOf("xinghai.js", "yuxi.js")
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
