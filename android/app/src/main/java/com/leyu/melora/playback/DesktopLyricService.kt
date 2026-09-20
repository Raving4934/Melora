package com.leyu.melora.playback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt
import androidx.core.view.setPadding
import com.leyu.melora.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 桌面歌词悬浮窗：系统级浮层展示当前歌词行，支持拖动、锁定、对齐、主题色与透明度设置。 */
class DesktopLyricService : Service() {
    private lateinit var windowManager: WindowManager
    private var rootView: LinearLayout? = null
    private var lyricView: TextView? = null
    private var panelView: HorizontalScrollView? = null
    private var panelContentWidth = 0
    private var playPauseButton: ImageButton? = null
    private var lockButton: ImageButton? = null
    private val hidePanelRunnable = Runnable { setPanelVisible(false) }
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastLayoutSignature = ""
    private var lastVerticalAlignment = Int.MIN_VALUE
    private data class LyricRenderState(val text: String, val translationRange: IntRange?, val color: Int)
    private var lastLyricRender: LyricRenderState? = null

    private val themeColors = listOf(
        AndroidColor.WHITE,
        "#38BDF8".toColorInt(),
        "#4ADE80".toColorInt(),
        "#FBBF24".toColorInt(),
        "#FB7185".toColorInt(),
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && lyricView != null) applyVisualSettings()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addOverlay()
        observeLyrics()
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

    private fun addOverlay() {
        if (lyricView != null) return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val view = TextView(this).apply {
            setTextColor(AndroidColor.WHITE)
            setPadding(
                (22 * density).toInt(),
                (12 * density).toInt(),
                (22 * density).toInt(),
                (12 * density).toInt(),
            )
            setTypeface(Typeface.DEFAULT_BOLD)
            text = "乐屿桌面歌词已开启"
            layoutParams = LinearLayout.LayoutParams(
                (metrics.widthPixels * MeloraSettings.lyricWindowPercent.value / 100f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = resolveGravity() and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK }
        }
        val panel = buildControlPanel(density)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(view)
            addView(panel)
        }
        layoutParams = WindowManager.LayoutParams(
            (metrics.widthPixels * MeloraSettings.lyricWindowPercent.value / 100f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(locked = MeloraSettings.lockLyrics.value),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = resolveGravity()
            y = resolveVerticalOffset(metrics.heightPixels)
        }
        configureDragging(view)
        runCatching { windowManager.addView(root, layoutParams) }
        rootView = root
        panelView = panel
        lyricView = view
        applyVisualSettings()
        // 播放状态实时同步到迷你控制面板
        scope.launch {
            PlaybackController.state.collect { state -> updatePlayPauseIcon(state.playing) }
        }
    }

    private fun resolveGravity(): Int = Gravity.TOP or when (MeloraSettings.lyricHAlign.value) {
        0 -> Gravity.START
        2 -> Gravity.END
        else -> Gravity.CENTER_HORIZONTAL
    }

    private fun resolveVerticalOffset(screenHeight: Int): Int = when (MeloraSettings.lyricVAlign.value) {
        0 -> (screenHeight * 0.12f).toInt()
        2 -> (screenHeight * 0.76f).toInt()
        else -> (screenHeight * 0.45f).toInt()
    }

    // 点击展开控制面板，拖动定位；锁定时移除触摸监听。
    @SuppressLint("ClickableViewAccessibility")
    private fun configureDragging(view: TextView) {
        if (MeloraSettings.lockLyrics.value) {
            view.setOnTouchListener(null)
            return
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = (view.resources.displayMetrics.density * 8f)
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - touchX) > slop || kotlin.math.abs(event.rawY - touchY) > slop) {
                        moved = true
                    }
                    layoutParams.x = startX + (event.rawX - touchX).toInt()
                    layoutParams.y = startY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(rootView ?: view, layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 点按（非拖动）呼出/收起迷你播放控制面板
                    if (!moved) setPanelVisible(panelView?.visibility != View.VISIBLE)
                    true
                }
                else -> false
            }
        }
    }

    private val iconColor: Int get() = "#FF20242E".toColorInt()
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
                applyVisualSettings()
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (10 * density).toInt()
            }
            addView(panel)
        }
    }

    /** 统一的窗口 flags（不启用系统窗口模糊：部分 ROM 会把整屏都模糊掉）。 */
    private fun overlayFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
        if (visible == panel.isVisible) return
        rootView?.removeCallbacks(hidePanelRunnable)
        if (visible) {
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(150L).start()
            rootView?.postDelayed(hidePanelRunnable, 6000L)
        } else {
            panel.visibility = View.GONE
        }
        applyVisualSettings()
    }

    private fun applyVisualSettings() {
        val view = lyricView ?: return
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, MeloraSettings.lyricFontSize.value)
        view.maxLines = desktopLyricMaxLines(
            MeloraSettings.singleLineLyric.value,
            MeloraSettings.lyricMaxLines.value,
        )
        val color = themeColors[MeloraSettings.lyricColorIndex.value.coerceIn(0, themeColors.lastIndex)]
        if (view.currentTextColor != color) {
            view.setTextColor(color)
            lastLyricRender?.let { updateText(it.text, it.translationRange) }
        }
        view.gravity = when (MeloraSettings.lyricHAlign.value) {
            0 -> Gravity.START or Gravity.CENTER_VERTICAL
            2 -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.CENTER
        }
        view.alpha = MeloraSettings.lyricAlpha.value / 100f
        val metrics = resources.displayMetrics
        // 圆角胶囊底板（开）：磨砂玻璃——系统模糊 + 极浅半透明罩，呈现壁纸的模糊色而非深色块
        if (MeloraSettings.lyricBackground.value) {
            view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                "#66FFFFFF".toColorInt(),
                "#40FFFFFF".toColorInt(),
            )).apply {
                cornerRadius = 20f * metrics.density
            }
        } else {
            view.background = null
        }
        view.setShadowLayer(0f, 0f, 0f, 0)
        val width = (metrics.widthPixels * MeloraSettings.lyricWindowPercent.value / 100f).toInt()
        if (MeloraSettings.lockLyrics.value) setPanelVisible(false)
        val overlayWidth = desktopLyricOverlayWidth(width, panelContentWidth, metrics.widthPixels, panelView?.visibility == View.VISIBLE)
        val childParams = view.layoutParams as LinearLayout.LayoutParams
        val childGravity = resolveGravity() and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        if (childParams.width != width || childParams.gravity != childGravity) {
            childParams.width = width
            childParams.gravity = childGravity
            view.layoutParams = childParams
        }
        val signature = "${resolveGravity()}|${MeloraSettings.lockLyrics.value}|$overlayWidth"
        val verticalAlignment = MeloraSettings.lyricVAlign.value
        var layoutChanged = false
        lockButton?.setImageResource(
            if (MeloraSettings.lockLyrics.value) R.drawable.ic_lyric_lock else R.drawable.ic_lyric_unlock,
        )
        lockButton?.setColorFilter(
            if (MeloraSettings.lockLyrics.value) iconActive else "#FF333A46".toColorInt(),
        )
        if (signature != lastLayoutSignature) {
            lastLayoutSignature = signature
            layoutParams.gravity = resolveGravity()
            layoutParams.width = overlayWidth
            layoutParams.x = desktopLyricWindowOffset(
                layoutParams.x, MeloraSettings.lyricHAlign.value,
                view.layoutDirection == View.LAYOUT_DIRECTION_RTL, metrics.widthPixels - overlayWidth,
            )
            layoutParams.flags = overlayFlags(locked = MeloraSettings.lockLyrics.value)
            configureDragging(view)
            layoutChanged = true
        }
        if (verticalAlignment != lastVerticalAlignment) {
            lastVerticalAlignment = verticalAlignment
            layoutParams.y = resolveVerticalOffset(metrics.heightPixels)
            layoutChanged = true
        }
        if (layoutChanged) runCatching { windowManager.updateViewLayout(rootView ?: view, layoutParams) }
    }

    private fun observeLyrics() {
        scope.launch {
            combine(
                PlaybackController.state,
                PlaybackController.lyric,
                MeloraSettings.singleLineLyric,
                MeloraSettings.lyricMaxLines,
            ) { state, lyric, singleLine, maxLines ->
                val lines = lyric?.lines.orEmpty()
                val index = lines.indexOfLast { state.positionMs >= it.timeMs }
                if (index >= 0) {
                    // 多行窗口：当前行 + 翻译（小号）+ 后续段落，总行数不超过设置的最大行数
                    val window = desktopLyricWindow(lines, index, singleLine, maxLines)
                    window.joinToString("\n") to desktopLyricTranslationRange(window, lines[index], singleLine)
                } else {
                    (state.current?.let { "${it.title} - ${it.artist}" } ?: "乐屿桌面歌词已开启") to null
                }
            }.collect { (text, translationRange) ->
                applyVisualSettings()
                updateText(text, translationRange)
            }
        }
    }

    private fun updateText(text: String, translationRange: IntRange? = null) {
        val view = lyricView ?: return
        val rendered = LyricRenderState(text, translationRange, view.currentTextColor)
        if (rendered == lastLyricRender) return
        val animate = lastLyricRender?.text != text && view.text.toString() != text && MeloraSettings.lyricAnimEnabled.value
        lastLyricRender = rendered
        val styled = if (translationRange == null) text else SpannableString(text).apply {
            val end = translationRange.last + 1
            setSpan(RelativeSizeSpan(0.72f), translationRange.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(rendered.color and 0x00FFFFFF or 0x99000000.toInt()),
                translationRange.first,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        // 样式变化直接重绘并取消旧动画，避免旧的结束回调重新写回旧颜色。
        view.animate().cancel()
        val targetAlpha = (MeloraSettings.lyricAlpha.value / 100f).coerceIn(0.2f, 1f)
        if (!animate) {
            view.alpha = targetAlpha
            view.text = styled
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(110L)
            .withEndAction {
                view.text = styled
                view.animate().alpha(targetAlpha).setDuration(170L).start()
            }
            .start()
    }

    override fun onDestroy() {
        rootView?.removeCallbacks(hidePanelRunnable)
        scope.cancel()
        // 必须移除窗口根视图（LinearLayout 容器）；移除子视图会抛异常导致悬浮窗残留
        rootView?.let { root -> runCatching { windowManager.removeView(root) } }
        rootView = null
        panelView = null
        playPauseButton = null
        lockButton = null
        lyricView = null
        lastLyricRender = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4C59
        private const val ACTION_REFRESH = "com.leyu.melora.action.REFRESH_DESKTOP_LYRIC"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startForegroundService(Intent(context, DesktopLyricService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopLyricService::class.java))
        }

        fun refresh(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startForegroundService(
                Intent(context, DesktopLyricService::class.java).setAction(ACTION_REFRESH),
            )
        }
    }
}

internal fun desktopLyricMaxLines(singleLine: Boolean, configured: Float): Int =
    if (singleLine) 1 else configured.toInt().coerceIn(1, 8)

internal fun desktopLyricText(text: String, translation: String?, singleLine: Boolean, maxLines: Float): String =
    if (!singleLine && maxLines > 1f && !translation.isNullOrBlank()) "$text\n$translation" else text

/** 桌面歌词多行窗口：当前行 + 翻译小号行 + 后续段落，总视觉行数不超过 maxLines。 */
internal fun desktopLyricWindow(
    lines: List<LyricLine>,
    index: Int,
    singleLine: Boolean,
    maxLines: Float,
): List<String> {
    if (index !in lines.indices) return emptyList()
    val count = desktopLyricMaxLines(singleLine, maxLines)
    val out = mutableListOf<String>()
    for (i in index until lines.size) {
        if (out.size >= count) break
        val line = lines[i]
        if (line.text.isNotBlank()) out += line.text
        if (i == index && !singleLine && out.size < count && !line.translation.isNullOrBlank()) {
            out += line.translation
        }
    }
    return out
}

/** 展开时才扩展根窗口，歌词子视图始终保留用户设置的宽度。 */
internal fun desktopLyricOverlayWidth(lyricWidth: Int, panelWidth: Int, screenWidth: Int, panelVisible: Boolean): Int =
    (if (panelVisible) maxOf(lyricWidth, panelWidth) else lyricWidth).coerceAtMost(screenWidth)

/** 将窗口左缘限制在屏内，再换算回对应 Gravity 的 x 偏移。 */
internal fun desktopLyricWindowOffset(offset: Int, alignment: Int, rtl: Boolean, available: Int): Int {
    val space = available.coerceAtLeast(0)
    val rightAligned = if (rtl) alignment == 0 else alignment == 2
    val origin = when {
        rightAligned -> space
        alignment == 0 || alignment == 2 -> 0
        else -> space / 2
    }
    val direction = if (rightAligned) -1 else 1
    return ((origin + direction * offset).coerceIn(0, space) - origin) * direction
}

internal fun desktopLyricTranslationRange(window: List<String>, current: LyricLine, singleLine: Boolean): IntRange? {
    val translation = current.translation?.takeIf { it.isNotBlank() } ?: return null
    val index = if (current.text.isBlank()) 0 else 1
    if (singleLine || window.getOrNull(index) != translation) return null
    val start = if (index == 0) 0 else window[0].length + 1
    return start until start + translation.length
}
