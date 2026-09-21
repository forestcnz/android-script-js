package com.script.js.core.floaty

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.script.js.core.image.ScreenCapture
import kotlin.math.abs

/**
 * 坐标拾取器 / 取色器：全屏透明悬浮层，拖动准星取点，点击生成脚本代码。
 * - 坐标模式：生成 `click(x, y);`
 * - 取色模式：从最近一帧截屏读取该点像素，生成 `images.findColor(captureScreen(), "#rrggbb", ...)`
 *
 * 注意：取色准确性依赖“被采样像素未被悬浮层本身覆盖”，因此准星中心留空且不做全屏压暗。
 * 对应设计 docs/DESIGN.md 第 4.4 节「坐标拾取器 / 取色器」。
 */
object PickerOverlay {

    private const val TAG = "ScriptJs"

    enum class Mode {
        /** 取坐标，生成 click(x, y) */
        POINT,

        /** 取像素颜色，生成 findColor 颜色串 */
        COLOR
    }

    /** 拾取结果回调（宿主写入运行日志等） */
    @Volatile
    var onPicked: ((String) -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var pickerView: PickerView? = null

    fun isShowing(): Boolean = pickerView != null

    /** 显示拾取层；重复调用先关闭再打开 */
    fun show(context: Context, mode: Mode): Boolean {
        if (pickerView != null) {
            hide()
            return true
        }
        if (!FloatyManager.hasOverlayPermission(context)) {
            Log.w(TAG, "拾取器需要悬浮窗权限")
            return false
        }
        // 取色依赖截屏：**任意通道**都不可用时才先申请授权（静默通道下无需授权，直接打开）
        if (mode == Mode.COLOR && !ScreenCapture.captureAvailable(context)) {
            Thread {
                val ok = ScreenCapture.requestBlocking()
                mainHandler.post {
                    if (ok) {
                        show(context, Mode.COLOR)
                    } else {
                        Toast.makeText(context, "取色需要截屏授权", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return true
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = PickerView(context, mode) { code -> deliver(context, code) }
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        return try {
            wm.addView(view, params)
            windowManager = wm
            pickerView = view
            Log.i(TAG, "拾取器已开启: $mode")
            true
        } catch (e: Exception) {
            Log.w(TAG, "拾取器显示失败: ${e.message}")
            false
        }
    }

    fun hide() {
        pickerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        pickerView = null
        windowManager = null
    }

    /** 生成结果：复制到剪贴板 + 提示 + 回调宿主 */
    private fun deliver(context: Context, code: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("script-js", code))
        } catch (_: Exception) {
        }
        Toast.makeText(context, "已复制: $code", Toast.LENGTH_LONG).show()
        Log.i(TAG, "拾取结果: $code")
        onPicked?.invoke(code)
        hide()
    }

    @SuppressLint("ViewConstructor")
    private class PickerView(
        context: Context,
        initialMode: Mode,
        private val onResult: (String) -> Unit
    ) : View(context) {

        /** 当前模式：可在拾取层内就地切换，无需退回悬浮球 */
        private var mode: Mode = initialMode

        private val density = resources.displayMetrics.density

        private var px = 0f
        private var py = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        /** 本次手势是否被右上角按钮消费（避免 UP 又当成取点确认） */
        private var consumedByButton = false

        private var color = 0
        private var hasColor = false
        private var sampling = false

        /** 用户已点击但取色尚未返回：取样完成后自动确认 */
        private var pendingConfirm = false

        /** 截屏不可用（未授权）时提示，而不是一直显示“取色中” */
        private var captureMissing = false

        /** 悬浮层在屏幕上的原点：窗口通常从状态栏下方开始，需换算成屏幕坐标 */
        private val location = IntArray(2)

        private fun toScreenX(localX: Float): Int = (localX + location[0]).toInt()

        private fun toScreenY(localY: Float): Int = (localY + location[1]).toInt()

        /** 取消按钮区域（右上角） */
        private val cancelRect = RectF()

        /** 模式切换按钮区域（右上角，取消左侧） */
        private val switchRect = RectF()

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            color = Color.WHITE
            isFakeBoldText = true
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            color = 0xFFBBBBBB.toInt()
        }
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6000000.toInt()
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF03DAC5.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        init {
            // 初始位置取屏幕中心
            post {
                if (px == 0f && py == 0f) {
                    px = width / 2f
                    py = height / 2f
                    if (mode == Mode.COLOR) sample()
                    invalidate()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(location)
            val gap = 9f * density
            val arm = 46f * density
            // 准星（中心留空，避免遮挡被采样像素）
            canvas.drawLine(px - arm, py, px - gap, py, crossPaint)
            canvas.drawLine(px + gap, py, px + arm, py, crossPaint)
            canvas.drawLine(px, py - arm, px, py - gap, crossPaint)
            canvas.drawLine(px, py + gap, px, py + arm, crossPaint)
            canvas.drawCircle(px, py, 10f * density, crossPaint)

            // 右上角按钮：模式切换 + 取消
            val btnW = 64f * density
            val btnH = 34f * density
            val btnGap = 8f * density
            cancelRect.set(width - btnW - 16f * density, 16f * density, width - 16f * density, 16f * density + btnH)
            switchRect.set(cancelRect.left - btnW - btnGap, 16f * density, cancelRect.left - btnGap, 16f * density + btnH)
            val switchLabel = if (mode == Mode.POINT) "取色" else "取坐标"
            canvas.drawRoundRect(switchRect, 8f * density, 8f * density, panelPaint)
            canvas.drawText(
                switchLabel,
                switchRect.centerX() - textPaint.measureText(switchLabel) / 2,
                switchRect.centerY() + 5f * density,
                textPaint
            )
            canvas.drawRoundRect(cancelRect, 8f * density, 8f * density, panelPaint)
            canvas.drawText(
                "取消",
                cancelRect.centerX() - textPaint.measureText("取消") / 2,
                cancelRect.centerY() + 5f * density,
                textPaint
            )

            // 信息面板（避开准星中心，右下偏移）
            val lines = ArrayList<String>()
            lines.add("x=${toScreenX(px)}  y=${toScreenY(py)}")
            if (mode == Mode.COLOR) {
                lines.add(
                    when {
                        hasColor -> "#%06x".format(color and 0xFFFFFF)
                        captureMissing -> "需截屏授权"
                        else -> "取色中…"
                    }
                )
            }
            val lineH = 18f * density
            val pad = 10f * density
            var panelW = 0f
            lines.forEach { panelW = maxOf(panelW, maxOf(textPaint.measureText(it), subPaint.measureText(it))) }
            panelW += pad * 2 + if (mode == Mode.COLOR) 26f * density else 0f
            val panelH = pad * 2 + lineH * lines.size + 16f * density
            var left = px + 22f * density
            var top = py + 22f * density
            if (left + panelW > width) left = px - 22f * density - panelW
            if (top + panelH > height) top = py - 22f * density - panelH
            left = left.coerceIn(0f, maxOf(0f, width - panelW))
            top = top.coerceIn(0f, maxOf(0f, height - panelH))
            canvas.drawRoundRect(
                RectF(left, top, left + panelW, top + panelH),
                8f * density, 8f * density, panelPaint
            )
            var ty = top + pad + 14f * density
            canvas.drawText(lines[0], left + pad, ty, textPaint)
            if (mode == Mode.COLOR) {
                ty += lineH
                canvas.drawText(lines[1], left + pad + 26f * density, ty, textPaint)
                swatchPaint.color = if (hasColor) color else 0xFF444444.toInt()
                canvas.drawRect(
                    left + pad, ty - 12f * density, left + pad + 18f * density, ty + 2f * density,
                    swatchPaint
                )
            }
            ty += lineH + 2f * density
            canvas.drawText(
                if (mode == Mode.POINT) "点击=生成 click(x,y)，拖动=移动准星"
                else "点击=生成颜色串，拖动=移动准星",
                left + pad, ty, subPaint
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    consumedByButton = false
                    if (cancelRect.contains(event.x, event.y)) {
                        consumedByButton = true
                        PickerOverlay.hide()
                        return true
                    }
                    if (switchRect.contains(event.x, event.y)) {
                        consumedByButton = true
                        mode = if (mode == Mode.POINT) Mode.COLOR else Mode.POINT
                        hasColor = false
                        captureMissing = false
                        if (mode == Mode.COLOR) sample()
                        invalidate()
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > 6f * density || abs(event.y - downY) > 6f * density) {
                        moved = true
                    }
                    px = event.x
                    py = event.y
                    if (mode == Mode.COLOR) sample()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !consumedByButton) {
                        px = event.x
                        py = event.y
                        confirm()
                    }
                    consumedByButton = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /** 取最近一帧截屏在准星处的像素（不阻塞 UI 线程；坐标为屏幕坐标） */
        private fun sample() {
            if (sampling) return
            sampling = true
            val x = toScreenX(px)
            val y = toScreenY(py)
            Thread {
                // 取最近一帧即可（timeout=0 不等待），移动过程中会持续有新帧
                val frame = ScreenCapture.capture(0)
                val c = if (frame != null && x >= 0 && y >= 0 && x < frame.width && y < frame.height) {
                    frame.getPixel(x, y)
                } else {
                    null
                }
                post {
                    sampling = false
                    if (c != null) {
                        color = c
                        hasColor = true
                        captureMissing = false
                        // 用户点了一下但当时还没取到颜色：这次取样完成后自动完成确认，
                        // 让取色和取坐标一样「单击一次即出结果」
                        if (pendingConfirm) {
                            pendingConfirm = false
                            confirm()
                        }
                    } else {
                        captureMissing = !ScreenCapture.captureAvailable(context)
                    }
                    invalidate()
                }
            }.start()
        }

        /** 点击确认：生成代码（坐标为屏幕坐标，与 click/gesture API 一致） */
        private fun confirm() {
            val x = toScreenX(px)
            val y = toScreenY(py)
            when (mode) {
                Mode.POINT -> onResult("click($x, $y);")
                Mode.COLOR -> {
                    if (!hasColor) {
                        // 首次点击时异步取样可能还没回来：记下待确认，取样完成后自动确认
                        pendingConfirm = true
                        Toast.makeText(
                            context,
                            if (captureMissing) "取色需要截屏授权，请先在主界面「截屏」去授权"
                            else "正在取色…",
                            Toast.LENGTH_SHORT
                        ).show()
                        sample()
                        return
                    }
                    val hex = "#%06x".format(color and 0xFFFFFF)
                    onResult("images.findColor(captureScreen(), \"$hex\", { threshold: 16 });   // ($x, $y)")
                }
            }
        }
    }
}
