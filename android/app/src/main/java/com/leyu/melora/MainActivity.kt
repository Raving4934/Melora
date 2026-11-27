package com.leyu.melora

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leyu.melora.playback.ApkUpdatePhase
import com.leyu.melora.playback.ApkUpdater
import com.leyu.melora.playback.BackupRestoreTransaction
import com.leyu.melora.playback.DesktopLyricService
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlaylistAutoUpdate
import com.leyu.melora.playback.UpdateChecker
import com.leyu.melora.playback.WebDavSync
import com.leyu.melora.playback.updateInstallIntent
import com.leyu.melora.playback.updatePermissionIntent
import com.leyu.melora.playback.local.LocalTagFiller
import com.leyu.melora.playback.lx.LxInspector
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.startup.resolveStartupTab
import com.leyu.melora.ui.MeloraApp
import com.leyu.melora.ui.common.componentSurfacesBlurred
import com.leyu.melora.ui.common.LocalGlassBackdrop
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.common.LocalResetRootBack
import com.leyu.melora.ui.common.RootBackCallback
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.common.shouldResetRootBackOnKeyEvent
import com.leyu.melora.ui.settings.AboutModal
import com.leyu.melora.ui.settings.AboutModalSheet
import com.leyu.melora.ui.theme.MeloraTheme
import com.leyu.melora.ui.theme.AdaptiveDisplay
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch

private const val DESKTOP_LYRIC_PERMISSION_PENDING = "desktopLyricPermissionPending"

class MainActivity : ComponentActivity() {
    private var backHint: Toast? = null
    private val appUpdater by viewModels<ApkUpdater> { ApkUpdater.factory }
    private val rootBackCallback = RootBackCallback(
        nowMillis = SystemClock::elapsedRealtime,
        showHint = {
            backHint?.cancel()
            backHint = Toast.makeText(this, "再按一次返回桌面", Toast.LENGTH_SHORT).also { it.show() }
        },
        moveTaskToBack = {
            backHint?.cancel()
            moveTaskToBack(true)
        },
        dismissKeyboard = {
            val decor = window.decorView
            val visible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (visible) WindowInsetsControllerCompat(window, decor).hide(WindowInsetsCompat.Type.ime())
            visible
        },
    )
    private var recoveryDialog: AlertDialog? = null
    private var launchedLocalTagAuthorizationId: Long? = null
    private val localTagWriteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        LocalTagFiller.onWriteAuthorizationResult(
            this,
            result.resultCode == Activity.RESULT_OK,
        )
    }
    private val apkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { appUpdater.permissionReturned() }

    private var desktopLyricPermissionPending = false
    private val desktopLyricPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!desktopLyricPermissionPending) return@registerForActivityResult
        desktopLyricPermissionPending = false
        if (!DesktopLyricService.setEnabled(this, requested = true)) {
            PlaybackController.postMessage(this, "未获得悬浮窗权限，桌面歌词未开启")
        }
    }

    internal fun changeDesktopLyrics(enabled: Boolean) {
        if (!enabled) desktopLyricPermissionPending = false
        if (desktopLyricPermissionPending) return
        val applied = DesktopLyricService.setEnabled(this, enabled)
        if (!enabled || applied) return

        desktopLyricPermissionPending = true
        runCatching {
            desktopLyricPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            )
        }.onFailure {
            desktopLyricPermissionPending = false
            PlaybackController.postMessage(this, "无法打开悬浮窗权限设置")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        desktopLyricPermissionPending = savedInstanceState?.getBoolean(DESKTOP_LYRIC_PERMISSION_PENDING) == true
        // 使用公开的 Window.Callback，不覆盖 AndroidX 限制访问的 dispatchKeyEvent。
        // onUserInteraction 也会收到 BACK/触摸 DOWN，不能用它重置二次返回。
        val originalWindowCallback = window.callback
        window.callback = object : Window.Callback by originalWindowCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (shouldResetRootBackOnKeyEvent(event.action, event.keyCode)) {
                    resetRootBackConfirmation()
                }
                return originalWindowCallback.dispatchKeyEvent(event)
            }

            // Kotlin 的接口委托不转发 Java default 方法，需显式保留原窗口行为。
            override fun onPointerCaptureChanged(hasCapture: Boolean) =
                originalWindowCallback.onPointerCaptureChanged(hasCapture)

            override fun onProvideKeyboardShortcuts(
                data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int,
            ) = originalWindowCallback.onProvideKeyboardShortcuts(data, menu, deviceId)
        }
        // 在 setContent 之前登记：Compose 页面、侧栏与播放器回调后入栈，始终先返回。
        onBackPressedDispatcher.addCallback(this, rootBackCallback)
        requestHighRefreshRate()
        observeLocalTagWriteAuthorization()
        val app = application as MeloraApplication
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BackupRestoreTransaction.failure.collect { failure ->
                    if (failure != null) showStartupRecovery(failure)
                    else { recoveryDialog?.dismiss(); recoveryDialog = null }
                }
            }
        }
        lifecycleScope.launch {
            app.awaitStartup()
            val initialTab = resolveStartupTab(
                explicitTab = intent?.getStringExtra("tab")?.toIntOrNull(),
                lastTab = MeloraSettings.lastTab.value,
                restoreLastPage = MeloraSettings.restoreLastPage.value,
                hasSavedInstanceState = savedInstanceState != null,
                startupPage = MeloraSettings.startupPage.value,
            )
            setContent {
                AdaptiveDisplay {
                    MeloraTheme {
                        val glassBackdrop = if (componentSurfacesBlurred()) rememberHazeState() else null
                        // 全局关闭系统"整屏拉伸"（橡皮筋）：纵向/横向滚动都到边即停，手感统一
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.LocalOverscrollFactory provides null,
                            LocalResetRootBack provides ::resetRootBackConfirmation,
                            LocalGlassBackdrop provides glassBackdrop,
                        ) {
                            SongListStateProvider {
                                MeloraApp(
                                    initialTab = initialTab,
                                    modifier = if (glassBackdrop == null) Modifier else Modifier.hazeSource(glassBackdrop),
                                )
                                val update by appUpdater.state.collectAsStateWithLifecycle()
                                if (update.phase != ApkUpdatePhase.Idle) {
                                    AboutModalSheet(AboutModal.Update) { appUpdater.dismiss() }
                                }
                            }
                        }
                    }
                }
            }
            runDebugActions()
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (MeloraSettings.showDesktopLyrics.value) DesktopLyricService.setEnabled(this@MainActivity)
                launch {
                    appUpdater.state.collect { state ->
                        when (state.phase) {
                            ApkUpdatePhase.PermissionRequired -> appUpdater.launchPermission {
                                apkPermissionLauncher.launch(updatePermissionIntent(this@MainActivity))
                            }
                            ApkUpdatePhase.InstallRequested -> appUpdater.launchInstaller {
                                startActivity(updateInstallIntent(this@MainActivity, it))
                            }
                            else -> Unit
                        }
                    }
                }
                MeloraSettings.autoCheckAppUpdates.collectLatest { enabled ->
                    if (enabled) {
                        UpdateChecker.checkAutomatically(this@MainActivity)?.let(appUpdater::offerAutomaticUpdate)
                    } else {
                        appUpdater.dismiss(onlyAutomatic = true)
                    }
                }
            }
        }
    }

    private fun showStartupRecovery(error: Exception) {
        recoveryDialog?.dismiss()
        val app = application as MeloraApplication
        val builder = AlertDialog.Builder(this, R.style.Theme_Melora_RecoveryDialog)
        val density = resources.displayMetrics.density
        val secondary = TypedValue().also {
            builder.context.theme.resolveAttribute(android.R.attr.textColorSecondary, it, true)
        }.data
        val message = SpannableStringBuilder(
            "恢复尚未完成，已暂停保存设置与用户库。请勿清除应用数据。\n\n" +
                "可释放存储空间后重试；或保留恢复快照，使用当前数据继续（内容可能不完整）。\n\n",
        ).apply {
            val detailStart = length
            append("诊断信息\n").append(error.message ?: error.javaClass.simpleName)
            setSpan(RelativeSizeSpan(0.85f), detailStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(secondary), detailStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        recoveryDialog = builder
            .setCustomTitle(TextView(builder.context).apply {
                text = "上次恢复未完成"
                setTextColor(TypedValue().also {
                    builder.context.theme.resolveAttribute(android.R.attr.textColorPrimary, it, true)
                }.data)
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val inset = (24 * density).toInt()
                setPadding(inset, inset, inset, (8 * density).toInt())
            })
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("重试恢复") { _, _ ->
                if (!app.continueStartup()) showStartupRecovery(requireNotNull(app.restoreFailure))
            }
            .setNeutralButton("保留并继续") { _, _ ->
                try {
                    BackupRestoreTransaction.retain(this)
                    if (!app.continueStartup()) showStartupRecovery(requireNotNull(app.restoreFailure))
                } catch (failure: Exception) {
                    showStartupRecovery(failure)
                }
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show().apply {
                findViewById<TextView>(android.R.id.message)?.apply {
                    textSize = 15f
                    setLineSpacing(3 * density, 1f)
                    setTextIsSelectable(true)
                }
                getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(secondary)
                val surface = TypedValue().also {
                    context.theme.resolveAttribute(android.R.attr.colorBackgroundFloating, it, true)
                }.data
                window?.setBackgroundDrawable(GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = 24 * density
                })
                window?.decorView?.clipToOutline = true
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DESKTOP_LYRIC_PERMISSION_PENDING, desktopLyricPermissionPending)
    }

    override fun onDestroy() {
        resetRootBackConfirmation()
        recoveryDialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resetRootBackConfirmation()
        lifecycleScope.launch {
            (application as MeloraApplication).awaitStartup()
            // 进程可能仍在而播放服务已退出；重进界面也必须走同一个队列恢复入口。
            PlaybackController.init(this@MainActivity)
            PlaybackController.checkLocalQueue(this@MainActivity)
            PlaylistAutoUpdate.onForeground(this@MainActivity)
            WebDavSync.onForeground(this@MainActivity)
        }
    }

    private fun resetRootBackConfirmation() {
        rootBackCallback.reset()
        backHint?.cancel()
        backHint = null
    }

    override fun onPause() {
        resetRootBackConfirmation()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetRootBackConfirmation()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 只重置完成的应用内触摸；系统边缘返回会 CANCEL，不能把第二次返回手势清掉。
        if (event.actionMasked == MotionEvent.ACTION_UP) resetRootBackConfirmation()
        return super.dispatchTouchEvent(event)
    }

    private fun observeLocalTagWriteAuthorization() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalTagFiller.writeAuthorization.collect { authorization ->
                    if (authorization == null) {
                        launchedLocalTagAuthorizationId = null
                        return@collect
                    }
                    if (authorization.id == launchedLocalTagAuthorizationId) return@collect
                    launchedLocalTagAuthorizationId = authorization.id
                    runCatching { localTagWriteLauncher.launch(authorization.request) }
                        .onFailure {
                            LocalTagFiller.onWriteAuthorizationResult(this@MainActivity, granted = false)
                        }
                }
            }
        }
    }

    private fun runDebugActions() {
        // 开发调试：adb shell am start -n com.leyu.melora/.MainActivity --es sdkSmoke kw
        intent?.getStringExtra("sdkSmoke")?.let { source ->
            com.leyu.melora.playback.sdk.SdkSmoke.run(this, source)
        }
        // 开发调试：adb shell am start -n com.leyu.melora/.MainActivity --es lxInspect <脚本关键字>
        intent?.getStringExtra("lxInspect")?.let { keyword ->
            val includePlayback = intent?.getBooleanExtra("lxResolve", false) == true
            lifecycleScope.launch(Dispatchers.IO) {
                val store = LxScriptStore(applicationContext)
                val script = store.list().firstOrNull { it.id.contains(keyword) || it.name.contains(keyword) }
                val message = if (script == null) {
                    "未找到匹配脚本 · $keyword"
                } else {
                    runCatchingCancellable {
                        val report = LxInspector.testValidity(applicationContext, store, script, includePlayback).last()
                        buildString {
                            append("脚本协议检查通过 · ${report.declaredPlatforms.joinToString("、")}")
                            if (includePlayback && report.platforms.isEmpty()) {
                                append("\n脚本未声明在线平台的播放能力")
                            }
                            for (test in report.platforms) {
                                append("\n${test.source}/${test.requestedQuality.orEmpty()} ${test.elapsedMs}ms ${test.result?.message.orEmpty()}")
                            }
                        }
                    }.getOrElse { "检查失败 · ${it.message.orEmpty()}" }
                }
                Log.i("LxInspector", message)
            }
        }
    }

    // 高刷新率：向系统申请本窗口使用设备支持的最高刷新率，滚动更顺滑（系统仍会自动降帧省电）
    private fun requestHighRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay ?: return
            val maxRate = display.supportedModes.maxOfOrNull { it.refreshRate } ?: return
            if (maxRate <= 60.5f) return
            window.attributes = window.attributes.apply { preferredRefreshRate = maxRate }
        }
    }
}
