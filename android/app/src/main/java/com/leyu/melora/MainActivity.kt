package com.leyu.melora

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leyu.melora.playback.BackupRestoreTransaction
import com.leyu.melora.playback.local.LocalTagFiller
import com.leyu.melora.playback.lx.LxInspector
import com.leyu.melora.playback.lx.LxScriptStore
import com.leyu.melora.ui.MeloraApp
import com.leyu.melora.ui.common.SongListStateProvider
import com.leyu.melora.ui.theme.MeloraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        observeLocalTagWriteAuthorization()
        val app = application as MeloraApplication
        app.restoreFailure?.let { showStartupRecovery(it) }
        lifecycleScope.launch {
            app.awaitStartup()
            val initialTab = intent?.getStringExtra("tab")?.toIntOrNull()
                ?: com.leyu.melora.playback.MeloraSettings.lastTab.value
            setContent {
                MeloraTheme {
                    // 全局关闭系统"整屏拉伸"（橡皮筋）：纵向/横向滚动都到边即停，手感统一
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.LocalOverscrollFactory provides null,
                    ) {
                        SongListStateProvider {
                            MeloraApp(initialTab = initialTab)
                        }
                    }
                }
            }
            runDebugActions()
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
            "恢复记录损坏或无法读取，尚未载入应用数据。请勿清除应用数据。\n\n" +
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

    override fun onDestroy() {
        recoveryDialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            (application as MeloraApplication).awaitStartup()
            com.leyu.melora.playback.PlaybackController.checkLocalQueue(this@MainActivity)
        }
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
            Thread {
                val store = LxScriptStore(this)
                val script = store.list().firstOrNull { it.id.contains(keyword) || it.name.contains(keyword) }
                val message = if (script == null) {
                    "未找到匹配脚本: $keyword"
                } else {
                    LxInspector.inspect(this, store, script, resolveDemo = intent?.getBooleanExtra("lxResolve", false) == true).status
                }
                Log.i("LxInspector", message)
            }.start()
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
