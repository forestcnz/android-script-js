package com.script.js.core.image

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 截屏前台服务：持有 MediaProjection，通过 VirtualDisplay + ImageReader 持续取帧。
 *
 * Android 14+ 要求先 startForeground(type=mediaProjection) 再获取投影，
 * 且同一次授权令牌只能使用一次（请求新投影需重新弹窗授权）。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var latest: Bitmap? = null

    private var width = 0
    private var height = 0

    companion object {
        private const val TAG = "ScriptJs"
        private const val CHANNEL_ID = "screen_capture"
        private const val FRESH_DELAY_MS = 150L
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        @Volatile
        private var ready = false

        /** 投影就绪时刻：建立瞬间的首帧可能仍是授权弹窗画面，需按时间过滤 */
        @Volatile
        private var readyAtMillis = 0L

        @Volatile
        private var frameAtMillis = 0L

        @Volatile
        private var hasFreshFrame = false

        @Volatile
        private var readyLatch: CountDownLatch? = null

        /** 投影是否已就绪（可截屏） */
        fun isReady(): Boolean = ready

        /** 最近一帧截图；未就绪返回 null */
        fun latestFrame(): Bitmap? = instance?.latest

        /** 已刷新过的帧（跳过投影建立瞬间的初始帧）；脚本侧优先使用 */
        fun freshFrame(): Bitmap? = instance?.let { if (hasFreshFrame) it.latest else null }

        /** 启动前台服务并用授权结果建立投影，阻塞等待就绪 */
        fun startAndAwait(context: Context, resultCode: Int, data: Intent, timeoutMs: Long): Boolean {
            if (ready) return true
            val latch = CountDownLatch(1)
            readyLatch = latch
            ready = false
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ready
            } catch (e: Exception) {
                Log.w(TAG, "启动截屏服务失败: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun signalReady(ok: Boolean) {
            ready = ok
            readyLatch?.countDown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先进入前台（Android 14+ 获取投影的前置条件）
        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            Log.w(TAG, "截屏服务缺少授权数据")
            signalReady(false)
            stopSelf()
            return START_NOT_STICKY
        }
        setupProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "屏幕截取",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "脚本找图所需的屏幕截取" }
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JS脚本执行器")
            .setContentText("屏幕截取运行中（脚本找图）")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground 失败: ${e.message}")
        }
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        try {
            val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(android.view.WindowManager::class.java)
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
            width = metrics.first
            height = metrics.second

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(resultCode, data)
            if (mp == null) {
                Log.w(TAG, "获取 MediaProjection 失败")
                signalReady(false)
                stopSelf()
                return
            }
            projection = mp

            val ht = HandlerThread("screen-capture").apply { start() }
            thread = ht
            val h = Handler(ht.looper)
            handler = h

            // Android 14+ 要求 createVirtualDisplay 之前注册回调
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 已停止")
                    release()
                    stopSelf()
                }
            }, h)

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r -> consumeLatest(r) }, h)

            virtualDisplay = mp.createVirtualDisplay(
                "script-capture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                h
            )
            instance = this
            readyAtMillis = android.os.SystemClock.uptimeMillis()
            signalReady(true)
            Log.i(TAG, "截屏服务就绪: ${width}x$height")
        } catch (e: Exception) {
            Log.w(TAG, "建立投影失败: ${e.message}")
            signalReady(false)
            stopSelf()
        }
    }

    /** 取最新帧转 Bitmap（保序：只保留最近一帧） */
    private fun consumeLatest(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val frame = if (paddedWidth != width) {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            } else {
                padded
            }
            // 旧帧不回收：脚本侧可能仍持有引用
            latest = frame
            frameAtMillis = android.os.SystemClock.uptimeMillis()
            // 只有就绪后 150ms 之外到达的帧才算“已刷新”，跳过授权弹窗残留帧
            if (frameAtMillis - readyAtMillis > FRESH_DELAY_MS) hasFreshFrame = true
        } catch (e: Exception) {
            Log.w(TAG, "取帧失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun release() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        latest = null
        instance = null
        ready = false
        readyAtMillis = 0
        frameAtMillis = 0
        hasFreshFrame = false
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onDestroy() {
        release()
        signalReady(false)
        super.onDestroy()
    }
}
