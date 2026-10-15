package com.script.js.core.floaty

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮窗管理：统一为一个悬浮控件（依赖 SYSTEM_ALERT_WINDOW 权限）。
 *
 * 控件有状态，就地变形而不是叠加多个窗口：
 * - 空闲态：胶囊「JS」，点击展开面板，拖动可移动并自动贴边（位置会记住）；
 * - 运行态：同一胶囊变成「▶ 任务名  ■ 停止」，点击停止中断脚本。
 *
 * 展开的面板按「任务 / 工具」分区：任务列表可滚动，工具区提供坐标拾取、取色器、悬浮日志。
 * 对应设计 docs/DESIGN.md 第 4.4 节「悬浮与调试工具」。
 */
object FloatyManager {

    private const val TAG = "ScriptJs"
    private const val PREFS = "floaty"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /** 用户是否希望显示悬浮球（默认 true = 首次启动自动开启） */
    private const val KEY_ENABLED = "enabled"

    /** 是否已自动引导过悬浮窗授权（只自动跳一次，避免每次启动都抢屏） */
    private const val KEY_ASKED_OVERLAY = "asked_overlay"

    // 配色（悬浮窗自成体系，不跟随 App 主题，保证在任何应用之上都可读）
    private const val COLOR_BG = 0xF21F1F27.toInt()
    private const val COLOR_BG_SOLID = 0xFF1B1B22.toInt()
    private const val COLOR_ACCENT = 0xFF03DAC5.toInt()
    private const val COLOR_TEXT = 0xFFEDEDF2.toInt()
    private const val COLOR_TEXT_DIM = 0xFF9A9AA8.toInt()
    private const val COLOR_RUNNING = 0xFF4CD964.toInt()
    private const val COLOR_STOP = 0xFFE5484D.toInt()
    private const val COLOR_DIVIDER = 0x33FFFFFF

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否启用悬浮球（默认开启） */
    fun isEnabledByUser(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabledByUser(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun wasOverlayAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_OVERLAY, false)

    fun markOverlayAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_OVERLAY, true).apply()
    }

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null

    /** 计时刷新用（主线程） */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 统一的悬浮控件（胶囊） */
    private var capsuleView: LinearLayout? = null
    private var capsuleParams: WindowManager.LayoutParams? = null

    /** 展开的任务/工具面板 */
    private var panelView: View? = null

    /** 当前运行中的任务名（null = 空闲态） */
    @Volatile
    private var runningTask: String? = null

    /** 点击[停止]时的回调（任务运行中） */
    @Volatile
    var onStopClicked: (() -> Unit)? = null

    /** 悬浮球面板中选择任务后的回调 */
    @Volatile
    var onTaskSelected: ((String) -> Unit)? = null

    /** 任务列表提供者（由宿主注入，避免 core 层循环依赖） */
    @Volatile
    var taskProvider: (() -> List<String>)? = null

    /** 是否已授予悬浮窗权限 */
    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** 跳转系统悬浮窗授权设置页 */
    fun requestOverlayPermission(activity: Activity) {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            ),
            // 部分模拟器设置应用不支持 package 定向，退回到悬浮窗应用列表。
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            // 再退回应用详情页，让用户从权限入口手动开启。
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            )
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // 尝试下一个系统设置入口。
            }
        }
        Log.w(TAG, "无法打开悬浮窗授权设置页")
    }

    /** 悬浮控件是否显示中 */
    fun isLauncherShowing(): Boolean = capsuleView?.isAttachedToWindow == true

    /**
     * 屏幕尺寸/方向变化（旋转、分屏、分辨率切换）后调用：
     * 按新的屏幕边界把悬浮球收回屏内并保存；面板位置依赖悬浮球，直接收起避免错位。
     */
    fun onScreenSizeChanged(context: Context) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        val m = context.resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: (52 * m.density).toInt()
        val h = view.height.takeIf { it > 0 } ?: (52 * m.density).toInt()
        params.x = params.x.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
        hideTaskPanel()
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
        savePosition(context, params.x, params.y)
        Log.i(TAG, "屏幕尺寸变化：悬浮球已收回屏内 (${params.x}, ${params.y})")
    }

    /** 是否处于运行态 */
    fun isShowing(): Boolean = runningTask != null

    /** 当前运行的任务名 */
    fun currentTask(): String? = runningTask

    // ==================== 悬浮控件 ====================

    /**
     * 显示悬浮控件（空闲态）。
     * 未授权返回 false；已显示直接返回 true。
     */
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun showLauncher(context: Context, keepAlive: Boolean = true): Boolean {
        appContext = context.applicationContext
        if (!hasOverlayPermission(context)) {
            if (keepAlive) FloatyForegroundService.stop(context)
            return false
        }
        if (capsuleView?.isAttachedToWindow == true) {
            if (keepAlive) FloatyForegroundService.start(context)
            return true
        }
        // 模拟器/系统可能移除了窗口但没有销毁应用进程；清掉陈旧引用后重新 addView。
        if (capsuleView != null) {
            hideLauncher(stopKeepAlive = false)
            Log.w(TAG, "检测到悬浮球窗口已脱离 WindowManager，正在重建")
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density

        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 背景/内边距按状态在 renderCapsule 中设置（空闲=圆球，运行=胶囊）
        }
        val metrics = context.resources.displayMetrics
        val defaultX = (12 * density).toInt()
        // 默认放在中下部并靠左：避开任务列表首屏的主要可点区域
        val defaultY = (metrics.heightPixels * 0.72f).toInt()
        val (posX, posY) = clampToScreen(
            context,
            loadPosition(context, KEY_X, defaultX),
            loadPosition(context, KEY_Y, defaultY)
        )
        val params = overlayParams(
            Gravity.TOP or Gravity.START,
            x = posX,
            y = posY
        )

        // 拖动移动；未拖动视为点击 → 展开/收起面板；松手后贴边
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        capsule.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 25) {
                        moved = true
                        hideTaskPanel()
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(capsule, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToEdge(context, wm, capsule, params)
                        savePosition(context, params.x, params.y)
                    } else {
                        toggleTaskPanel(context, wm, params)
                    }
                    false
                }
                else -> false
            }
        }

        return try {
            wm.addView(capsule, params)
            capsuleView = capsule
            capsuleParams = params
            windowManager = wm
            renderCapsule(context)
            Log.i(TAG, "悬浮控件已显示")
            if (keepAlive) FloatyForegroundService.start(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "悬浮控件显示失败 (overlayPermission=${hasOverlayPermission(context)}): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** 隐藏悬浮控件（含面板） */
    fun hideLauncher(context: Context? = null, stopKeepAlive: Boolean = true) {
        hideTaskPanel()
        capsuleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        capsuleView = null
        capsuleParams = null
        windowManager = null
        if (stopKeepAlive) {
            (context?.applicationContext ?: appContext)?.let { FloatyForegroundService.stop(it) }
        }
        Log.i(TAG, "悬浮控件已隐藏")
    }

    /** 切换到运行态：胶囊就地变成「● 任务名 计时 ■」 */
    fun setRunning(context: Context, taskName: String) {
        val wasIdle = runningTask == null
        runningTask = taskName
        if (wasIdle) runningStartedAt = System.currentTimeMillis()
        hideTaskPanel()
        if (capsuleView == null) {
            if (!showLauncher(context)) {
                Log.w(TAG, "运行态悬浮控件显示失败")
                return
            }
        }
        renderCapsule(context)
        startTicker(context)
        Log.i(TAG, "悬浮控件进入运行态: $taskName")
    }

    /** 回到空闲态（任务结束/停止后调用） */
    fun clearRunning(context: Context) {
        runningTask = null
        stopTicker()
        renderCapsule(context)
        // 胶囊收缩回圆球后贴回最近的边缘：避免停在屏幕中间（胶囊从边缘向内展开后的残留位置）
        capsuleView?.let { v ->
            windowManager?.let { wm ->
                capsuleParams?.let { p ->
                    v.post {
                        snapToEdge(context, wm, v, p)
                        savePosition(context, p.x, p.y)
                    }
                }
            }
        }
        Log.i(TAG, "悬浮控件回到空闲态")
    }

    /**
     * 按当前状态重建胶囊内容：
     * - 空闲：52dp 圆球「JS」+ 右下角状态点（绿=就绪），半透明避免遮挡；
     * - 运行：圆角胶囊 = 状态点 + 「▶ 任务名」+ 计时(mm:ss) + 红色停止键。
     */
    @SuppressLint("SetTextI18n")
    private fun renderCapsule(context: Context) {
        val capsule = capsuleView ?: return
        val d = context.resources.displayMetrics.density
        capsule.removeAllViews()
        val task = runningTask
        if (task == null) {
            capsule.setPadding(0, 0, 0, 0)
            capsule.background = null
            capsule.alpha = 0.92f
            capsule.addView(ballView(context))
        } else {
            capsule.setPadding(
                (7 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt()
            )
            capsule.background = roundedBackground(COLOR_BG, 26 * d, 1 * d, 0x22FFFFFF)
            capsule.alpha = 1f
            capsule.addView(statusDot(context))
            capsule.addView(
                textView(context, "▶ ${ellipsize(task, 8)}", 13f, COLOR_TEXT).apply {
                    maxLines = 1
                    setPadding((8 * d).toInt(), 0, (10 * d).toInt(), 0)
                }
            )
            timerView = textView(context, elapsedText(), 11f, COLOR_TEXT_DIM).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, (10 * d).toInt(), 0)
            }
            capsule.addView(timerView)
            capsule.addView(stopButton(context))
        }
        capsuleView?.let { view ->
            try {
                capsuleParams?.let { windowManager?.updateViewLayout(view, it) }
            } catch (_: Exception) {
            }
            // 圆球 ↔ 胶囊宽度不同：测量后把窗口收进屏幕内（贴右边缘时向左展开，避免被裁掉）
            view.post { keepInsideScreen(context) }
        }
    }

    /** 状态切换后按实际宽高把悬浮窗收回屏幕内 */
    private fun keepInsideScreen(context: Context) {
        val view = capsuleView ?: return
        val params = capsuleParams ?: return
        val wm = windowManager ?: return
        val w = view.width.takeIf { it > 0 } ?: return
        val h = view.height.takeIf { it > 0 } ?: return
        val m = context.resources.displayMetrics
        val nx = params.x.coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))
        val ny = params.y.coerceIn(0, (m.heightPixels - h).coerceAtLeast(0))
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    /** 空闲态：圆形球 + 状态点 */
    private fun ballView(context: Context): View {
        val d = context.resources.displayMetrics.density
        val size = (52 * d).toInt()
        val frame = android.widget.FrameLayout(context)
        frame.addView(
            View(context).apply {
                background = roundedBackground(COLOR_BG_SOLID, 26 * d, 1.5f * d, 0x33FFFFFF)
            },
            android.widget.FrameLayout.LayoutParams(size, size)
        )
        frame.addView(
            android.widget.ImageView(context).apply {
                setImageResource(com.script.js.R.drawable.ic_floaty_logo)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding((9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt())
            },
            android.widget.FrameLayout.LayoutParams(size, size)
        )
        // 右下角状态点：空闲=绿（就绪）
        frame.addView(
            statusDot(context),
            android.widget.FrameLayout.LayoutParams((12 * d).toInt(), (12 * d).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (2 * d).toInt()
                bottomMargin = (2 * d).toInt()
            }
        )
        return frame
    }

    /** 运行态左侧的状态点（绿=运行中） */
    private fun statusDot(context: Context): View {
        val d = context.resources.displayMetrics.density
        return View(context).apply {
            background = roundedBackground(COLOR_RUNNING, 5 * d, 0f, 0)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt())
        }
    }

    /** 运行态停止键：红色圆角方块，点击中断脚本 */
    private fun stopButton(context: Context): View {
        val d = context.resources.displayMetrics.density
        return textView(context, "■ 停止", 12f, 0xFFFFFFFF.toInt()).apply {
            background = roundedBackground(COLOR_STOP, 8 * d, 0f, 0)
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            isClickable = true
            setOnClickListener {
                Log.i(TAG, "悬浮控件停止按钮点击, 回调已绑定=${onStopClicked != null}")
                onStopClicked?.invoke()
            }
        }
    }

    // ==================== 运行计时 ====================

    private var runningStartedAt = 0L
    private var timerView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            timerView?.text = elapsedText()
            if (runningTask != null) handler.postDelayed(this, 1000L)
        }
    }

    private fun startTicker(context: Context) {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000L)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
        timerView = null
    }

    private fun elapsedText(): String {
        val s = ((System.currentTimeMillis() - runningStartedAt) / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    // ==================== 视图小工具 ====================

    private fun textView(context: Context, text: String, sp: Float, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
        }

    /** 圆角背景（radius=高度一半即胶囊/圆形） */
    private fun roundedBackground(
        color: Int,
        radiusPx: Float,
        strokePx: Float,
        strokeColor: Int
    ) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0f) setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
    }

    /** 悬浮球上过长任务名截断（保证胶囊不占满屏幕） */
    private fun ellipsize(name: String, max: Int): String =
        if (name.length <= max) name else name.take(max) + "…"


    /** 松手后贴到最近的左/右边缘 */
    private fun snapToEdge(
        context: Context,
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val screenW = context.resources.displayMetrics.widthPixels
        val viewW = view.width.takeIf { it > 0 } ?: (60 * context.resources.displayMetrics.density).toInt()
        val margin = (6 * context.resources.displayMetrics.density).toInt()
        params.x = if (params.x + viewW / 2 < screenW / 2) margin else screenW - viewW - margin
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun loadPosition(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, default)

    /**
     * 位置越界钳位：旋转屏幕/切换分辨率后，历史绝对坐标可能落到屏幕外，
     * 此时回落到默认位置，避免悬浮球「消失」。
     */
    /**
     * 位置钳位：旋转屏幕/切换分辨率后历史绝对坐标可能落到屏幕外。
     * 处理原则是**收紧到屏幕内**（而不是丢弃位置回默认）——用户把球拖到右边缘属于正常位置，
     * 早期实现按 120dp 余量判定「越界」并复位，导致贴边位置重启后丢失。
     */
    private fun clampToScreen(
        context: Context,
        x: Int,
        y: Int
    ): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val viewW = capsuleView?.width?.takeIf { it > 0 } ?: (56 * m.density).toInt()
        val viewH = capsuleView?.height?.takeIf { it > 0 } ?: (56 * m.density).toInt()
        val maxX = (m.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (m.heightPixels - viewH).coerceAtLeast(0)
        if (x < 0 || y < 0 || x > maxX || y > maxY) {
            val nx = x.coerceIn(0, maxX)
            val ny = y.coerceIn(0, maxY)
            Log.i(TAG, "悬浮控件位置越界(x=$x,y=$y)，收紧为($nx,$ny)")
            return nx to ny
        }
        return x to y
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    // ==================== 任务 / 工具面板 ====================

    /**
     * 展开/收起面板（每次展开重新读取任务列表与运行状态）。
     * 布局：卡片式，自上而下 = 头部（选择任务 + 关闭） / 任务列表（可滚动） / 工具按钮行 /
     * 运行中任务显示「■ 停止当前任务」。宽度固定 268dp，贴悬浮球同侧并做屏幕内钳位。
     */
    private fun toggleTaskPanel(
        context: Context,
        wm: WindowManager,
        capsule: WindowManager.LayoutParams
    ) {
        if (panelView != null) {
            hideTaskPanel()
            return
        }
        val tasks = taskProvider?.invoke() ?: emptyList()
        val d = context.resources.displayMetrics.density
        Log.i(TAG, "展开面板, 任务数=${tasks.size}, 运行中=${runningTask ?: "无"}")

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_BG, 16 * d, 1 * d, 0x22FFFFFF)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }

        // —— 头部：标题 + 任务数 + 关闭 ——
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        header.addView(
            textView(context, "选择任务", 13f, COLOR_TEXT).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(textView(context, "${tasks.size} 个", 11f, COLOR_TEXT_DIM))
        header.addView(
            textView(context, "✕", 14f, COLOR_TEXT_DIM).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { hideTaskPanel() }
            },
            LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt())
        )
        panel.addView(header)
        panel.addView(divider(context))

        // —— 任务列表（可滚动，最长 42% 屏高） ——
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (tasks.isEmpty()) {
            list.addView(menuText(context, "（暂无任务，先在 App 里新建）") { })
        } else {
            tasks.forEach { name ->
                val isRunning = runningTask == name
                list.addView(
                    taskRow(context, name, isRunning) {
                        if (runningTask == null) {
                            Log.i(TAG, "悬浮球选择任务: $name")
                            hideTaskPanel()
                            onTaskSelected?.invoke(name)
                        }
                    }
                )
            }
        }
        val scroll = ScrollView(context).apply {
            addView(list)
            isFillViewport = true
        }
        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (tasks.size * 46 * d).toInt().coerceAtMost(maxListHeight).coerceAtLeast((46 * d).toInt())
            )
        )

        // —— 底部：工具按钮行（横向紧凑，节省高度） ——
        panel.addView(divider(context))
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
        }
        tools.addView(toolChip(context, "取坐标") {
            hideTaskPanel()
            PickerOverlay.show(context, PickerOverlay.Mode.POINT)
        })
        tools.addView(toolChip(context, "取色") {
            hideTaskPanel()
            PickerOverlay.show(context, PickerOverlay.Mode.COLOR)
        })
        tools.addView(toolChip(context, "日志") {
            hideTaskPanel()
            ConsoleOverlay.toggle(context)
        })
        tools.addView(toolChip(context, "贴边") {
            capsuleView?.let { snapToEdge(context, wm, it, capsule) }
        })
        panel.addView(tools)

        // 运行中：提供停止入口（与胶囊上的停止键等价）
        if (runningTask != null) {
            val stopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            }
            stopRow.addView(
                textView(context, "■ 停止当前任务", 13f, 0xFFFFFFFF.toInt()).apply {
                    background = roundedBackground(COLOR_STOP, 10 * d, 0f, 0)
                    gravity = Gravity.CENTER
                    setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                    isClickable = true
                    setOnClickListener {
                        Log.i(TAG, "面板停止按钮点击")
                        hideTaskPanel()
                        onStopClicked?.invoke()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            panel.addView(stopRow)
        }

        // —— 定位：贴悬浮球同侧，做屏幕内钳位（不超出右/下边界） ——
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val panelW = (268 * d).toInt()
        val estH = (220 * d).toInt()
        val leftSide = capsule.x + capsuleW() / 2 < screenW / 2
        val panelX = if (leftSide) capsule.x else capsule.x + capsuleW() - panelW
        val panelY = (capsule.y + capsuleH() + (8 * d).toInt())
        val params = overlayParams(
            Gravity.TOP or Gravity.START,
            x = panelX.coerceIn(0, (screenW - panelW).coerceAtLeast(0)),
            y = panelY.coerceIn(0, (screenH - estH).coerceAtLeast(0))
        )
        params.width = panelW
        try {
            wm.addView(panel, params)
            panelView = panel
            windowManager = wm
        } catch (e: Exception) {
            Log.w(TAG, "面板显示失败: ${e.message}")
        }
    }

    private fun capsuleW(): Int = capsuleView?.width?.takeIf { it > 0 }
        ?: (60 * (capsuleView?.resources?.displayMetrics?.density ?: 3f)).toInt()

    private fun capsuleH(): Int = capsuleView?.height?.takeIf { it > 0 }
        ?: (52 * (capsuleView?.resources?.displayMetrics?.density ?: 3f)).toInt()

    private fun divider(context: Context): View {
        val d = context.resources.displayMetrics.density
        return View(context).apply {
            setBackgroundColor(COLOR_DIVIDER)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)
            )
        }
    }

    /** 任务行：左侧 ▶ 强调色 + 任务名 +（运行中）标记；整行 46dp 可点 */
    private fun taskRow(
        context: Context,
        name: String,
        running: Boolean,
        onClick: () -> Unit
    ): View {
        val d = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, (10 * d).toInt(), 0)
            isClickable = true
            setOnClickListener { onClick() }
            if (!running && runningTask != null) alpha = 0.45f
        }
        row.addView(textView(context, "▶", 11f, COLOR_ACCENT))
        row.addView(
            textView(context, name, 14f, if (running) COLOR_RUNNING else COLOR_TEXT).apply {
                maxLines = 1
                setPadding((10 * d).toInt(), 0, (8 * d).toInt(), 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (running) {
            row.addView(
                textView(context, "运行中", 10f, COLOR_RUNNING).apply {
                    background = roundedBackground(0x224CD964, 8 * d, 1 * d, 0x554CD964)
                    setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
                }
            )
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (46 * d).toInt()
        )
        return row
    }

    /** 工具按钮：浅底圆角小胶囊 */
    private fun toolChip(context: Context, label: String, onClick: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        return textView(context, label, 12f, COLOR_TEXT).apply {
            background = roundedBackground(0x1FFFFFFF, 16 * d, 1 * d, 0x22FFFFFF)
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * d).toInt() }
        }
    }

    private fun hideTaskPanel() {
        panelView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
    }

    /** 面板中的一行菜单项（空列表提示等） */
    private fun menuText(context: Context, label: String, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(0xFFEDEDF2.toInt())
            textSize = 14f
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 叠加窗口通用参数：置顶、不抢焦点、可触摸 */
    private fun overlayParams(gravity: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }
}
