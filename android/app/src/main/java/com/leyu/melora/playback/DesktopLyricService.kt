package com.leyu.melora.playback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.os.IBinder
import android.provider.Settings
import android.view.Choreographer
import android.view.WindowInsets
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.setPadding
import com.leyu.melora.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 桌面歌词悬浮窗：系统级浮层展示当前歌词行，支持拖动、锁定、对齐、主题色与透明度设置。 */
class DesktopLyricService : Service() {
    private lateinit var windowManager: WindowManager
    private var lyricView: DesktopLyricViewport? = null
    private var panelView: HorizontalScrollView? = null
    private var panelContentWidth = 0
    private var playPauseButton: ImageButton? = null
    private var lockButton: ImageButton? = null
    private val hidePanelRunnable = Runnable { setPanelVisible(false) }
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screenWidth = 0
    private var screenHeight = 0
    private var dragging = false
    private var dragEnabled: Boolean? = null
    private var screenInteractive = true
    private var screenReceiverRegistered = false
    private var framePosted = false
    private var lineTickPosted = false
    private val lineTick = Runnable {
        lineTickPosted = false
        renderLyrics()
        scheduleUpdates()
    }
    private var latestState = PlayerUiState()
    private var lyricLines: List<LyricLine> = emptyList()
    private var hasWordTimings = false
    private var renderedLines: List<LyricLine>? = null
    private var renderedIndex = Int.MIN_VALUE
    private var renderedTrack: UiTrack? = null
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            renderLyrics()
            scheduleUpdates()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenInteractive = intent?.action != Intent.ACTION_SCREEN_OFF
            if (!screenInteractive) lyricView?.stopScrolling()
            renderLyrics(force = true)
            scheduleUpdates()
        }
    }

    private val themeColors = listOf(
        AndroidColor.WHITE,
        "#38BDF8".toColorInt(),
        "#4ADE80".toColorInt(),
        "#FBBF24".toColorInt(),
        "#FB7185".toColorInt(),
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if ((application as? com.leyu.melora.MeloraApplication)?.finishBlockedServiceStart(this, startId) == true) {
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if ((application as? com.leyu.melora.MeloraApplication)?.restoreFailure != null) return
        if (!MeloraSettings.showDesktopLyrics.value || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        updateScreenBounds()
        screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        if (!addOverlay()) { stopSelf(); return }
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        observeLyrics()
        observeVisualSettings()
    }

    private fun startForegroundNotification() {
        val channelId = "melora-desktop-lyric"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "桌面歌词", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("乐屿桌面歌词运行中")
            .setContentText("歌词悬浮窗已开启，可在设置中关闭")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun addOverlay(): Boolean {
        val view = DesktopLyricViewport(this).apply { layoutDirection = resources.configuration.layoutDirection }
        lyricView = view
        panelView = buildControlPanel(resources.displayMetrics.density)
        layoutParams = overlayParams(MeloraSettings.lockLyrics.value)
        panelParams = overlayParams(false)
        view.setOnClickListener { setPanelVisible(panelView?.isAttachedToWindow != true) }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleUpdates() }
        view.setOnApplyWindowInsetsListener { _, insets ->
            val oldWidth = screenWidth; val oldHeight = screenHeight
            updateScreenBounds()
            if (oldWidth != screenWidth || oldHeight != screenHeight) applyVisualSettings()
            insets
        }
        applyVisualSettings()
        updatePlayback(PlaybackController.state.value, PlaybackController.lyric.value)
        return runCatching { windowManager.addView(view, layoutParams) }.isSuccess
    }

    @SuppressLint("RtlHardcoded") // 物理窗口坐标，文字与拖动锚点单独处理RTL。
    private fun overlayParams(locked: Boolean) = WindowManager.LayoutParams(1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlayFlags(locked), PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setFitInsetsIgnoringVisibility(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
    }

    private fun updateScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            screenWidth = (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1)
            screenHeight = (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        } else {
            screenWidth = resources.displayMetrics.widthPixels
            screenHeight = resources.displayMetrics.heightPixels
        }
    }

    private fun geometry() = DesktopLyricGeometry(screenWidth, screenHeight,
        layoutParams.width, layoutParams.height, MeloraSettings.lyricHAlign.value,
        lyricView?.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        lyricView?.focusAnchorY ?: 0f, (48 * resources.displayMetrics.density).roundToInt())

    private fun repositionOverlay() {
        val view = lyricView ?: return
        view.measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST))
        val oldWidth = layoutParams.width; val oldHeight = layoutParams.height
        val oldFlags = layoutParams.flags
        layoutParams.width = view.measuredWidth
        layoutParams.height = view.measuredHeight
        layoutParams.flags = overlayFlags(MeloraSettings.lockLyrics.value)
        val bounds = geometry()
        val (x, y) = if (dragging) bounds.clamp(layoutParams.x, layoutParams.y)
            else bounds.offset(MeloraSettings.desktopLyricPosition.value, MeloraSettings.lyricVAlign.value)
        val changed = oldWidth != layoutParams.width || oldHeight != layoutParams.height || oldFlags != layoutParams.flags ||
            layoutParams.x != x || layoutParams.y != y
        layoutParams.x = x; layoutParams.y = y
        if (changed && view.isAttachedToWindow) runCatching { windowManager.updateViewLayout(view, layoutParams) }
        positionPanel()
    }

    private fun finishDrag() {
        if (!dragging) return
        MeloraSettings.updateDesktopLyricPosition(geometry().position(layoutParams.x, layoutParams.y))
        dragging = false
    }

    private fun positionPanel() {
        val panel = panelView ?: return
        if (!panel.isAttachedToWindow) return
        measurePanel()
        val (x, y) = geometry().panelOffset(layoutParams.x, layoutParams.y, panelParams.width, panelParams.height,
            (8 * resources.displayMetrics.density).roundToInt())
        panelParams.x = x; panelParams.y = y
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    private fun measurePanel() {
        val panel = panelView ?: return
        panel.measure(View.MeasureSpec.makeMeasureSpec(minOf(panelContentWidth, screenWidth), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST))
        panelParams.width = panel.measuredWidth
        panelParams.height = panel.measuredHeight
    }

    // 点击交给performClick，拖动仅在超过阈值后移动，松手一次性保存而非逐帧写偏好。
    @SuppressLint("ClickableViewAccessibility")
    private fun configureDragging(view: DesktopLyricViewport) {
        val enabled = !MeloraSettings.lockLyrics.value
        if (dragEnabled == enabled) return
        dragEnabled = enabled
        view.isClickable = enabled
        if (!enabled) {
            finishDrag()
            view.setOnTouchListener(null)
            return
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var ignoreGesture = false
        val slop = view.resources.displayMetrics.density * 8f
        view.setOnTouchListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_DOWN && ignoreGesture) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ignoreGesture = false
                    startX = layoutParams.x; startY = layoutParams.y
                    touchX = event.rawX; touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    dragging = dragging || kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop
                    if (dragging) {
                        val (x, y) = geometry().clamp(startX + dx.roundToInt(), startY + dy.roundToInt())
                        layoutParams.x = x; layoutParams.y = y
                        runCatching { windowManager.updateViewLayout(view, layoutParams) }
                        positionPanel()
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    // 多指不接管当前拖拽，避免活动手指变化导致跳位或误点开控制条。
                    ignoreGesture = true
                    finishDrag()
                    true
                }
                MotionEvent.ACTION_UP -> { if (dragging) finishDrag() else view.performClick(); true }
                else -> false
            }
        }
    }

    private val iconActive: Int get() = "#FF2563EB".toColorInt()

    /** 迷你控制面板：锚定锁定 + 上一首 + 蓝色圆形播放键 + 下一首。实心浅色胶囊 + 细描边，无阴影。 */
    private fun buildControlPanel(density: Float): HorizontalScrollView {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor("#F5FFFFFF".toColorInt())
                setStroke((1 * density).toInt(), "#14000000".toColorInt())
            }
            setPadding(
                (10 * density).toInt(),
                (6 * density).toInt(),
                (10 * density).toInt(),
                (6 * density).toInt(),
            )
        }
        val ripple = android.util.TypedValue().let { tv ->
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            tv.resourceId
        }
        fun iconButton(iconRes: Int, iconSize: Int, touchSize: Int, tint: Int): ImageButton =
            ImageButton(this).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
                setBackgroundResource(ripple)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding((touchSize - iconSize) / 2, (touchSize - iconSize) / 2, (touchSize - iconSize) / 2, (touchSize - iconSize) / 2)
                layoutParams = LinearLayout.LayoutParams(touchSize, touchSize).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
            }.also { panel.addView(it) }

        val iconSize = (19 * density).toInt()
        val touchSize = (38 * density).toInt()
        val idle = "#FF333A46".toColorInt()

        lockButton = iconButton(
            if (MeloraSettings.lockLyrics.value) R.drawable.ic_lyric_lock else R.drawable.ic_lyric_unlock,
            iconSize,
            touchSize,
            if (MeloraSettings.lockLyrics.value) iconActive else idle,
        ).apply {
            setOnClickListener {
                MeloraSettings.updateLockLyrics(!MeloraSettings.lockLyrics.value)
                setPanelVisible(false)
            }
        }
        panel.addView(
            View(this).apply {
                setBackgroundColor("#12000000".toColorInt())
                layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), (16 * density).toInt()).apply {
                    marginStart = (5 * density).toInt()
                    marginEnd = (5 * density).toInt()
                }
            },
        )
        iconButton(R.drawable.ic_lyric_prev, iconSize, touchSize, idle).apply {
            setOnClickListener { PlaybackController.previous() }
        }

        // 播放/暂停：品牌蓝实心圆 + 白色图标，作为视觉焦点
        playPauseButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_lyric_play)
            setColorFilter(AndroidColor.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#FF2563EB".toColorInt())
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                marginStart = (2 * density).toInt()
                marginEnd = (2 * density).toInt()
            }
            setOnClickListener { PlaybackController.toggle() }
        }
        panel.addView(playPauseButton)

        iconButton(R.drawable.ic_lyric_next, iconSize, touchSize, idle).apply {
            setOnClickListener { PlaybackController.next() }
        }
        panel.addView(
            View(this).apply {
                setBackgroundColor("#12000000".toColorInt())
                layoutParams = LinearLayout.LayoutParams((1 * density).toInt(), (16 * density).toInt()).apply {
                    marginStart = (5 * density).toInt()
                    marginEnd = (5 * density).toInt()
                }
            },
        )
        iconButton(R.drawable.ic_lyric_close, (18 * density).toInt(), touchSize, "#FF8A93A3".toColorInt()).apply {
            setOnClickListener {
                // 关闭桌面歌词：同步设置开关并停止服务
                MeloraSettings.updateShowDesktopLyrics(false)
                stopSelf()
            }
        }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        panel.measure(unspecified, unspecified)
        panelContentWidth = panel.measuredWidth
        // 常规宽度完整展示；极窄屏仍可横向滚动，不缩小按钮的触摸面积。
        return HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            addView(panel)
        }
    }

    /** 统一的窗口 flags（不启用系统窗口模糊：部分 ROM 会把整屏都模糊掉）。 */
    private fun overlayFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun updatePlayPauseIcon(playing: Boolean) {
        playPauseButton?.setImageResource(
            if (playing) R.drawable.ic_lyric_pause else R.drawable.ic_lyric_play,
        )
    }

    private fun setPanelVisible(visible: Boolean) {
        val panel = panelView ?: return
        if (visible == panel.isAttachedToWindow) return
        lyricView?.removeCallbacks(hidePanelRunnable)
        panel.animate().cancel()
        if (!visible) {
            runCatching { windowManager.removeViewImmediate(panel) }
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        measurePanel()
        val (x, y) = geometry().panelOffset(layoutParams.x, layoutParams.y, panelParams.width, panelParams.height,
            (8 * resources.displayMetrics.density).roundToInt())
        panelParams.x = x; panelParams.y = y
        panel.alpha = 0f
        if (runCatching { windowManager.addView(panel, panelParams) }.isSuccess) {
            panel.animate().alpha(1f).setDuration(150L).start()
            lyricView?.postDelayed(hidePanelRunnable, 6000L)
        } else panel.visibility = View.GONE
    }

    private fun observeVisualSettings() {
        scope.launch {
            combine(MeloraSettings.showDesktopLyrics, MeloraSettings.lockLyrics,
                MeloraSettings.singleLineLyric, MeloraSettings.lyricFontSize,
                MeloraSettings.lyricMaxLines, MeloraSettings.lyricAlpha, MeloraSettings.lyricHAlign,
                MeloraSettings.lyricVAlign, MeloraSettings.lyricColorIndex, MeloraSettings.lyricBackground,
                MeloraSettings.desktopLyricPosition) { Unit }.collect {
                if (!MeloraSettings.showDesktopLyrics.value) stopSelf() else applyVisualSettings()
            }
        }
    }

    private fun applyVisualSettings() {
        val view = lyricView ?: return
        updateScreenBounds()
        view.configure(MeloraSettings.lyricFontSize.value,
            desktopLyricMaxLines(MeloraSettings.singleLineLyric.value, MeloraSettings.lyricMaxLines.value),
            when (MeloraSettings.lyricHAlign.value) {
                0 -> Gravity.START or Gravity.CENTER_VERTICAL
                2 -> Gravity.END or Gravity.CENTER_VERTICAL
                else -> Gravity.CENTER
            }, themeColors[MeloraSettings.lyricColorIndex.value.coerceIn(themeColors.indices)])
        view.alpha = (MeloraSettings.lyricAlpha.value / 100f).coerceIn(0.2f, 1f)
        val density = resources.displayMetrics.density
        // 仅保留原来的半透明底板，不启用系统窗口模糊。
        view.background = if (MeloraSettings.lyricBackground.value) {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf("#66FFFFFF".toColorInt(), "#40FFFFFF".toColorInt()))
                .apply { cornerRadius = 20f * density }
        } else null
        val locked = MeloraSettings.lockLyrics.value
        if (locked) setPanelVisible(false)
        lockButton?.setImageResource(if (locked) R.drawable.ic_lyric_lock else R.drawable.ic_lyric_unlock)
        lockButton?.setColorFilter(if (locked) iconActive else "#FF333A46".toColorInt())
        configureDragging(view)
        renderLyrics(force = true)
        if (!screenInteractive) repositionOverlay()
        scheduleUpdates()
    }

    private fun observeLyrics() {
        scope.launch {
            combine(PlaybackController.state, PlaybackController.lyric) { state, lyric -> state to lyric }
                .collect { (state, lyric) -> updatePlayback(state, lyric) }
        }
    }

    private fun updatePlayback(state: PlayerUiState, lyric: PlayerLyric?) {
        if (latestState.playing != state.playing) updatePlayPauseIcon(state.playing)
        latestState = state
        val lines = lyric?.takeIf { it.uid == state.current?.uid }?.lines.orEmpty()
        if (lines !== lyricLines) {
            lyricLines = lines
            hasWordTimings = lines.any { it.words.isNotEmpty() }
        }
        renderLyrics()
        scheduleUpdates()
    }

    private fun scheduleUpdates() {
        val active = desktopLyricNeedsFrames(latestState, true, screenInteractive,
            lyricView?.isShown == true && lyricView?.windowVisibility == View.VISIBLE)
        val needed = hasWordTimings && active
        if (needed && !framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else if (!needed && framePosted) {
            framePosted = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        if (lineTickPosted) {
            lyricView?.removeCallbacks(lineTick)
            lineTickPosted = false
        }
        // 普通 LRC 不空跑逐帧循环；按下一句的真实时间唤醒，避免 500ms 采样跳过短句。
        if (active && !needed) desktopLyricNextLineDelay(lyricLines, latestState, SystemClock.elapsedRealtime())?.let { delay ->
            lineTickPosted = lyricView?.postDelayed(lineTick, delay) == true
        }
    }

    private fun renderLyrics(force: Boolean = false) {
        val view = lyricView ?: return
        if (!screenInteractive) return
        val position = lyricPositionAt(latestState, SystemClock.elapsedRealtime())
        val index = lyricIndexAt(lyricLines, position)
        if (force || renderedLines !== lyricLines || renderedIndex != index || renderedTrack != latestState.current) {
            view.submit(lyricLines, index,
                latestState.current?.let { "${it.title} - ${it.artist}" } ?: "乐屿桌面歌词已开启",
                animate = !force && renderedTrack?.uid == latestState.current?.uid)
            renderedLines = lyricLines; renderedIndex = index; renderedTrack = latestState.current
            repositionOverlay()
        }
        view.renderPosition(position)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lyricView?.layoutDirection = newConfig.layoutDirection
        applyVisualSettings()
    }

    override fun onDestroy() {
        lyricView?.removeCallbacks(lineTick)
        lyricView?.removeCallbacks(hidePanelRunnable)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        finishDrag()
        scope.cancel()
        panelView?.animate()?.cancel()
        panelView?.takeIf { it.isAttachedToWindow }?.let { runCatching { windowManager.removeViewImmediate(it) } }
        lyricView?.takeIf { it.isAttachedToWindow }?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panelView = null
        playPauseButton = null
        lockButton = null
        lyricView = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4C59

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startForegroundService(Intent(context, DesktopLyricService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopLyricService::class.java))
        }


    }
}

internal fun desktopLyricMaxLines(singleLine: Boolean, configured: Float): Int =
    if (singleLine) 1 else configured.toInt().coerceIn(1, 8)

/** 歌词锚点可移动到屏幕边缘；只保留一块可抓取区域，而不是把整个空白窗口锁在屏幕内。 */
internal data class DesktopLyricGeometry(
    val screenWidth: Int, val screenHeight: Int, val width: Int, val height: Int,
    val hAlign: Int, val rtl: Boolean, val focusY: Float, val handleSize: Int,
) {
    private val anchor = when (hAlign) {
        0 -> if (rtl) 1f else 0f
        2 -> if (rtl) 0f else 1f
        else -> 0.5f
    }

    fun clamp(x: Int, y: Int): Pair<Int, Int> {
        val grabX = minOf(handleSize, width, screenWidth).coerceAtLeast(1)
        val grabY = minOf(handleSize, height, screenHeight).coerceAtLeast(1)
        val left = (-(width - grabX) * anchor).roundToInt()
        val top = (grabY / 2f - focusY).roundToInt()
        return x.coerceIn(left, left + (screenWidth - grabX).coerceAtLeast(0)) to
            y.coerceIn(top, top + (screenHeight - grabY).coerceAtLeast(0))
    }

    fun offset(position: DesktopLyricPosition, vAlign: Int): Pair<Int, Int> = clamp(
        position.xFraction?.let { (screenWidth * it - width * anchor).roundToInt() }
            ?: ((screenWidth - width) * anchor).roundToInt(),
        position.yFraction?.let { (screenHeight * it).roundToInt() }
            ?: (screenHeight * when (vAlign) { 0 -> 0.12f; 2 -> 0.76f; else -> 0.45f }).roundToInt(),
    )

    fun position(x: Int, y: Int): DesktopLyricPosition {
        val (clampedX, clampedY) = clamp(x, y)
        return DesktopLyricPosition((clampedX + width * anchor) / screenWidth.coerceAtLeast(1),
            clampedY.toFloat() / screenHeight.coerceAtLeast(1)).normalized()
    }

    fun panelOffset(x: Int, y: Int, panelWidth: Int, panelHeight: Int, gap: Int): Pair<Int, Int> {
        val below = y + height + gap
        val panelY = if (below + panelHeight <= screenHeight) below else y - panelHeight - gap
        return (x + (width - panelWidth) * anchor).roundToInt().coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0)) to
            panelY.coerceIn(0, (screenHeight - panelHeight).coerceAtLeast(0))
    }
}

internal fun desktopLyricNeedsFrames(state: PlayerUiState, timed: Boolean, interactive: Boolean, visible: Boolean): Boolean =
    timed && interactive && visible && state.positionAdvancing && !state.buffering && !state.resolving

/** 仅为普通歌词安排下一次换句，不虚构逐字时间。 */
internal fun desktopLyricNextLineDelay(lines: List<LyricLine>, state: PlayerUiState, nowMs: Long): Long? {
    if (!state.positionAdvancing || state.buffering || state.resolving || state.speed <= 0f) return null
    val position = lyricPositionAt(state, nowMs)
    val next = lines.getOrNull(lyricIndexAt(lines, position, includeBackground = true) + 1) ?: return null
    if (state.durationMs > 0 && next.startMs > state.durationMs) return null
    return kotlin.math.ceil((next.startMs - position) / state.speed.toDouble()).toLong().coerceAtLeast(1L)
}
