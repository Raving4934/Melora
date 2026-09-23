package com.leyu.melora

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import kotlinx.coroutines.CancellationException
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
    private var startupStarted = false
    @Volatile internal var restoreFailure: Exception? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // 尽早安装崩溃捕获，连设置初始化阶段的异常也能留下现场。
        CrashLogger.init(this)
        continueStartup()
    }

    /** 恢复失败只开放恢复入口；不让界面或后台服务使用半回滚的数据。 */
    internal fun continueStartup(): Boolean {
        if (startupStarted) return true
        try {
            BackupManager.recoverInterruptedRestore(this)
            restoreFailure = null
        } catch (error: Exception) {
            restoreFailure = error
            Log.e(TAG, "启动恢复未完成，已保留现场", error)
            return false
        }
        MeloraSettings.init(this)
        startupStarted = true

        // 用户库、下载记录和一次性音源迁移互不依赖，并行放到 IO 线程；
        // Activity 等待统一屏障后再首次组合，避免先渲染空列表再整体闪变。
        startupScope.launch {
            try {
                coroutineScope {
                    listOf(
                        async { runStartupStep("用户库") { UserLibrary.init(this@MeloraApplication) } },
                        async { runStartupStep("下载记录") { DownloadCenter.init(this@MeloraApplication) } },
                        async { runStartupStep("本地媒体") { LocalMediaStore.init(this@MeloraApplication) } },
                        async { runStartupStep("历史音源迁移") { removeLegacyPresetSources() } },
                    ).forEach { it.await() }
                }
                withContext(Dispatchers.Main.immediate) {
                    runStartupStep("播放控制器") { PlaybackController.init(this@MeloraApplication) }
                    runStartupStep("音源预热") { LxScriptPool.warmEnabled(this@MeloraApplication) }
                }
            } finally {
                // 任一可选模块失败都不能让 Activity 永久等待空白首屏。
                startupReady.complete(Unit)
            }
        }
        return true
    }

    /** 已收到前台启动请求时，先履行系统通知契约再停止，避免恢复入口被FGS超时杀死。 */
    internal fun finishBlockedServiceStart(service: Service, startId: Int): Boolean {
        if (restoreFailure == null) return false
        val channel = "melora-startup-recovery"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "启动恢复", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        service.startForeground(0x4D52, Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("上次恢复未完成")
            .setContentText("请打开乐屿处理恢复记录，无需清除应用数据")
            .setContentIntent(open).build())
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf(startId)
        return true
    }

    suspend fun awaitStartup() = startupReady.await()

    private inline fun runStartupStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "$name 初始化失败", error)
        }
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
