package com.script.js.core.gesture

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * 脚本点击特效：任务执行 click 时在屏幕坐标处显示一个短暂的扩散圆环，
 * 让用户一眼看出「任务是这一下点在哪里」。
 *
 * - 无障碍服务在线时用 TYPE_ACCESSIBILITY_OVERLAY：免悬浮窗权限，层级高于普通应用窗口；
 * - 窗口带 FLAG_NOT_TOUCHABLE，特效纯展示，绝不拦截/影响任务自身的点击手势。
 */
object ClickEffect {

    private const val DURATION_MS = 280L

    /** 特效圆环直径（dp） */
    private const val SIZE_DP = 56f

    /** 特效窗口统一 post 到主线程添加/移除（脚本线程不能直接操作窗口） */
    private val main = Handler(Looper.getMainLooper())

    /** 在屏幕坐标 (x,y) 处播放扩散圆环特效（异步，不阻塞脚本执行） */
    fun show(x: Float, y: Float) {
        val svc = ScriptAccessibilityService.instance ?: return
        main.post { showOnScreen(svc, x, y) }
    }

    /** 主线程：创建特效窗口并播放动画 */
    private fun showOnScreen(svc: ScriptAccessibilityService, x: Float, y: Float) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val d = svc.resources.displayMetrics.density
        val size = (SIZE_DP * d).toInt()
        // 白色圆环 + 微透明白色填充，深浅背景上都可见
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((3 * d).toInt(), 0xFFFFFFFF.toInt())
            setColor(0x33FFFFFF)
        }
        val view = View(svc).apply { background = ring }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 圆环中心对准点击坐标
            this.x = (x - size / 2f).toInt()
            this.y = (y - size / 2f).toInt()
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            // 窗口添加失败（服务断开等）直接放弃，不影响任务
            return
        }
        // 从小到大扩散 + 淡出，结束后移除窗口
        ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val scale = a.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
                view.alpha = 1f - a.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    try {
                        wm.removeView(view)
                    } catch (_: Exception) {
                    }
                }
            })
            start()
        }
    }
}
