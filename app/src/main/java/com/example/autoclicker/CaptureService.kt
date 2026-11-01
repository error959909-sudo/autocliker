package com.example.autoclicker

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
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

// Android 10 aur neeche ke liye screen capture (Android 11+ pe iski zaroorat nahi)
class CaptureService : Service() {

    companion object {
        @Volatile
        var instance: CaptureService? = null
        var resultCode: Int = 0
        var resultData: Intent? = null

        fun isReady(): Boolean = instance?.reader != null
        fun grab(): Bitmap? = instance?.latest()
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var last: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val data = resultData
        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (reader != null) return START_NOT_STICKY
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                    stopSelf()
                }
            }, handler)

            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels

            val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = r
            display = proj.createVirtualDisplay(
                "AutoClickerCap", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            instance = this
        } catch (e: Exception) {
            release()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoClicker")
            .setContentText("Screen capture chalu hai")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    fun latest(): Bitmap? {
        val r = reader ?: return last
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
            try {
                val plane = img.planes[0]
                val bw = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(bw, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                last = if (bw != width) Bitmap.createBitmap(bmp, 0, 0, width, height) else bmp
            } catch (e: Exception) {
                // purani frame hi use hogi
            } finally {
                img.close()
            }
        }
        return last
    }

    private fun release() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        instance = null
    }

    override fun onDestroy() {
        val p = projection
        projection = null
        release()
        try { p?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
