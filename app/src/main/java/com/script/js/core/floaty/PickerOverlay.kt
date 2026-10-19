package com.script.js.core.floaty

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import android.widget.Toast
import com.script.js.core.image.ScreenCapture
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * 坐标拾取器 / 取色器 / 截图器：全屏透明悬浮层，拖动准星取点，点击生成脚本代码。
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
        COLOR,

        /** 拖拽框选屏幕区域并保存截图 */
        SCREENSHOT
    }

    /** 拾取结果回调（宿主写入运行日志等） */
    @Volatile
    var onPicked: ((String) -> Unit)? = null

    /** 截图保存成功回调（宿主写入运行日志等） */
    @Volatile
    var onScreenshotSaved: ((File) -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var pickerView: PickerView? = null
    private var savePromptView: FrameLayout? = null
    private var savePromptWindowManager: WindowManager? = null

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
        // 取色/截图依赖截屏：**任意通道**都不可用时才先申请授权（静默通道下无需授权，直接打开）
        if ((mode == Mode.COLOR || mode == Mode.SCREENSHOT) && !ScreenCapture.captureAvailable(context)) {
            Thread {
                val ok = ScreenCapture.requestBlocking()
                mainHandler.post {
                    if (ok) {
                        show(context, mode)
                    } else {
                        Toast.makeText(
                            context,
                            if (mode == Mode.COLOR) "取色需要截屏授权" else "截图需要截屏授权",
                            Toast.LENGTH_LONG
                        ).show()
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

    /** 关闭截图重命名窗口 */
    fun hideSavePrompt() {
        savePromptView?.let { view ->
            try {
                savePromptWindowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        savePromptView = null
        savePromptWindowManager = null
    }

    /** 打开截图重命名窗口；文件保存到应用专属外部存储 screenshots/ */
    private fun showSavePrompt(context: Context, bitmap: Bitmap) {
        hideSavePrompt()
        hide()
        val density = context.resources.displayMetrics.density
        val padding = (18f * density).toInt()
        val root = FrameLayout(context).apply {
            setBackgroundColor(0xFF1D2B46.toInt())
            isClickable = true
            isFocusable = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(context).apply {
            text = "保存截图"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val input = EditText(context).apply {
            setText(defaultScreenshotName())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelection(text.length)
            setTextColor(Color.WHITE)
            hint = "文件名"
        }
        val pathHint = TextView(context).apply {
            text = screenshotDir(context).absolutePath
            setTextColor(0xFFBAC9E4.toInt())
            textSize = 11f
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancelButton = Button(context).apply { text = "取消" }
        val saveButton = Button(context).apply { text = "保存" }
        buttons.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttons.addView(
            saveButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val fieldGap = (14f * density).toInt()
        content.addView(title)
        content.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = fieldGap }
        )
        content.addView(
            pathHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8f * density).toInt() }
        )
        content.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = fieldGap }
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                marginStart = (24f * density).toInt()
                marginEnd = (24f * density).toInt()
            }
        )

        cancelButton.setOnClickListener { hideSavePrompt() }
        saveButton.setOnClickListener {
            val rawName = input.text.toString().trim()
            val name = sanitizeScreenshotName(rawName)
            if (name == null) {
                input.error = "请输入合法文件名"
                return@setOnClickListener
            }
            saveButton.isEnabled = false
            input.isEnabled = false
            Thread {
                val saved = saveScreenshot(context, bitmap, name)
                mainHandler.post {
                    if (saved == null) {
                        saveButton.isEnabled = true
                        input.isEnabled = true
                        Toast.makeText(context, "截图保存失败", Toast.LENGTH_LONG).show()
                    } else {
                        hideSavePrompt()
                        Toast.makeText(
                            context,
                            "已保存 ${saved.name} (${bitmap.width}×${bitmap.height})",
                            Toast.LENGTH_LONG
                        ).show()
                        onScreenshotSaved?.invoke(saved)
                    }
                }
            }.start()
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(root, params)
            savePromptView = root
            savePromptWindowManager = wm
            input.requestFocus()
        } catch (e: Exception) {
            Log.w(TAG, "截图保存窗口显示失败: ${e.message}")
            Toast.makeText(context, "截图保存窗口显示失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun screenshotDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "screenshots").apply { mkdirs() }
    }

    private fun defaultScreenshotName(): String =
        "screenshot_${android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())}"

    private fun sanitizeScreenshotName(rawName: String): String? {
        if (rawName.isEmpty() || rawName.length > 100) return null
        if (rawName.contains('/') || rawName.contains('\\')) return null
        val name = if (rawName.endsWith(".png", true)) rawName else "$rawName.png"
        return name.takeIf { !Regex("[\"*:<>?|\\u0000-\\u001F]").containsMatchIn(it) }
    }

    private fun saveScreenshot(context: Context, bitmap: Bitmap, requestedName: String): File? {
        val dir = screenshotDir(context)
        var target = File(dir, requestedName)
        if (target.exists()) {
            val base = requestedName.removeSuffix(".png")
            var index = 1
            while (target.exists()) {
                target = File(dir, "${base}_$index.png")
                index += 1
            }
        }
        return try {
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "截图保存失败: ${e.message}")
            target.delete()
            null
        }
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

        private var selectionStartX = 0f
        private var selectionStartY = 0f
        private var selectionEndX = 0f
        private var selectionEndY = 0f
        private var hasSelection = false
        private var isSelecting = false
        private var isCapturing = false

        /** 截图确认按钮区域 */
        private val saveRect = RectF()

        /** 截图重选按钮区域 */
        private val resetRect = RectF()

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
            color = 0xFFBAC9E4.toInt()
        }
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF21D2B46.toInt()
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt()
            style = Paint.Style.FILL
        }
        private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x00FFFFFF
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
            if (mode == Mode.SCREENSHOT) {
                drawScreenshot(canvas)
                return
            }
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

        private fun drawScreenshot(canvas: Canvas) {
            val btnW = 76f * density
            val btnH = 38f * density
            val btnGap = 10f * density
            val margin = 18f * density
            cancelRect.set(width - btnW - margin, margin, width - margin, margin + btnH)
            resetRect.set(cancelRect.left - btnW - btnGap, margin, cancelRect.left - btnGap, margin + btnH)
            saveRect.set(resetRect.left - btnW - btnGap, margin, resetRect.left - btnGap, margin + btnH)

            if (hasSelection) {
                val left = min(selectionStartX, selectionEndX)
                val top = min(selectionStartY, selectionEndY)
                val right = max(selectionStartX, selectionEndX)
                val bottom = max(selectionStartY, selectionEndY)
                canvas.drawRect(0f, 0f, width.toFloat(), top, dimPaint)
                canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dimPaint)
                canvas.drawRect(0f, top, left, bottom, dimPaint)
                canvas.drawRect(right, top, width.toFloat(), bottom, dimPaint)
                canvas.drawRect(left, top, right, bottom, selectionFillPaint)
                canvas.drawRect(left, top, right, bottom, crossPaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            }

            val captureText = if (isCapturing) "截图中" else "保存"
            drawButton(canvas, saveRect, captureText)
            drawButton(canvas, resetRect, "重选")
            drawButton(canvas, cancelRect, "取消")

            val hint = when {
                isCapturing -> "正在截取所选区域…"
                hasSelection -> "${selectionWidth()}×${selectionHeight()}，可拖拽调整"
                else -> "按住并拖动，框选要保存的区域"
            }
            val textX = margin
            val textY = if (saveRect.bottom + 34f * density < height) {
                saveRect.bottom + 26f * density
            } else {
                height - margin
            }
            canvas.drawText(hint, textX, textY, subPaint)
        }

        private fun drawButton(canvas: Canvas, rect: RectF, label: String) {
            canvas.drawRoundRect(rect, 9f * density, 9f * density, panelPaint)
            val textX = rect.centerX() - textPaint.measureText(label) / 2
            canvas.drawText(label, textX, rect.centerY() + 5f * density, textPaint)
        }

        private fun selectionWidth(): Int = abs(selectionEndX - selectionStartX).toInt()

        private fun selectionHeight(): Int = abs(selectionEndY - selectionStartY).toInt()

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
                        mode = when (mode) {
                            Mode.POINT -> Mode.COLOR
                            Mode.COLOR -> Mode.SCREENSHOT
                            Mode.SCREENSHOT -> Mode.POINT
                        }
                        hasColor = false
                        captureMissing = false
                        hasSelection = false
                        isSelecting = false
                        isCapturing = false
                        if (mode == Mode.COLOR) sample()
                        invalidate()
                        return true
                    }
                    if (mode == Mode.SCREENSHOT) {
                        if (saveRect.contains(event.x, event.y) && hasSelection && !isCapturing) {
                            consumedByButton = true
                            captureSelection()
                            return true
                        }
                        if (resetRect.contains(event.x, event.y)) {
                            consumedByButton = true
                            hasSelection = false
                            isSelecting = true
                            selectionStartX = event.x
                            selectionStartY = event.y
                            selectionEndX = event.x
                            selectionEndY = event.y
                            invalidate()
                            return true
                        }
                        isSelecting = true
                        hasSelection = false
                        selectionStartX = event.x
                        selectionStartY = event.y
                        selectionEndX = event.x
                        selectionEndY = event.y
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > 6f * density || abs(event.y - downY) > 6f * density) {
                        moved = true
                    }
                    if (mode == Mode.SCREENSHOT) {
                        if (isSelecting && !isCapturing) {
                            selectionEndX = event.x
                            selectionEndY = event.y
                            hasSelection = true
                            invalidate()
                        }
                        return true
                    }
                    px = event.x
                    py = event.y
                    if (mode == Mode.COLOR) sample()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == Mode.SCREENSHOT) {
                        if (isSelecting && !isCapturing) {
                            isSelecting = false
                            selectionEndX = event.x
                            selectionEndY = event.y
                            hasSelection = selectionWidth() >= 2 && selectionHeight() >= 2
                        }
                        invalidate()
                        return true
                    }
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
            if (mode == Mode.SCREENSHOT) return
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

                Mode.SCREENSHOT -> Unit
            }
        }

        private fun captureSelection() {
            val left = toScreenX(min(selectionStartX, selectionEndX).toInt().toFloat())
            val top = toScreenY(min(selectionStartY, selectionEndY).toInt().toFloat())
            val width = selectionWidth().coerceAtLeast(1)
            val height = selectionHeight().coerceAtLeast(1)
            isCapturing = true
            visibility = View.INVISIBLE
            invalidate()
            Thread {
                val frame = ScreenCapture.capture()
                post {
                    isCapturing = false
                    if (frame == null) {
                        visibility = View.VISIBLE
                        Toast.makeText(context, "截图失败：无法获取屏幕画面", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val cropX = left.coerceIn(0, frame.width - 1)
                    val cropY = top.coerceIn(0, frame.height - 1)
                    val cropW = min(width, frame.width - cropX)
                    val cropH = min(height, frame.height - cropY)
                    if (cropW < 2 || cropH < 2) {
                        visibility = View.VISIBLE
                        Toast.makeText(context, "选区超出屏幕范围", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    val bitmap = try {
                        Bitmap.createBitmap(frame, cropX, cropY, cropW, cropH)
                    } catch (_: Exception) {
                        null
                    }
                    if (bitmap == null) {
                        visibility = View.VISIBLE
                        Toast.makeText(context, "截图失败：选区裁剪异常", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    PickerOverlay.showSavePrompt(context, bitmap)
                }
            }.start()
        }
    }
}
