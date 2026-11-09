package com.familyguard.child.service

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

object MirrorManager {

    @Volatile
    var hasProjection = false
        private set

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null

    @Volatile
    var latestFrame: ByteArray? = null
        private set

    @Volatile private var fw = 0
    @Volatile private var fh = 0

    private var streamJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun markActive(p: MediaProjection, r: ImageReader, w: Int, h: Int) {
        synchronized(this) {
            projection = p
            reader = r
            fw = w
            fh = h
            hasProjection = true
        }
    }

    fun onImage(image: Image) {
        try {
            // Use the IMAGE's own dimensions, not the configured ones: if the virtual
            // display delivers a different size (rotation, OEM quirk, first frame
            // before markActive), reading with stale fw/fh threw and every frame was
            // silently dropped — cast icon on but parent never saw a picture.
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val buf = plane.buffer
            val out = ByteArray(w * h * 4)
            if (rowStride == w * 4 && pixStride == 4) {
                buf.get(out)
            } else {
                var pos = 0
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(out, pos, w * 4)
                    pos += w * 4
                }
            }
            synchronized(this) {
                fw = w
                fh = h
                latestFrame = out
            }
        } catch (_: Exception) {
        } finally {
            runCatching { image.close() }
        }
    }

    fun startStreaming(fps: Int, quality: Int): Boolean {
        if (!hasProjection) return false
        stopStreaming()
        streamJob = scope.launch {
            val interval = 1000L / fps.coerceIn(1, 15)
            // Safety net: while streaming, if the WS stays down (parent app closed
            // without pressing Stop, network drop, …) for 30 s, end the projection
            // session entirely — otherwise the cast icon stayed stuck on the child's
            // status bar long after nobody was watching.
            var wsDownSince = 0L
            while (isActive) {
                if (!WsChild.connected.value) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (wsDownSince == 0L) {
                        wsDownSince = now
                    } else if (now - wsDownSince > 30_000L) {
                        stopStreaming()
                        release()
                        return@launch
                    }
                } else {
                    wsDownSince = 0L
                }
                val snap = synchronized(this@MirrorManager) { latestFrame }
                if (snap == null) {
                    delay(100)
                    continue
                }
                val w = fw
                val h = fh
                if (w <= 0 || h <= 0) {
                    delay(100)
                    continue
                }
                runCatching {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(snap))
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), bos)
                    bmp.recycle()
                    WsChild.sendBinary(bos.toByteArray())
                }
                delay(interval)
            }
        }
        return true
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    fun captureOnce(ctx: Context): File? {
        val snap = latestFrame ?: return null
        val w = fw
        val h = fh
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(snap))
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            val file = File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { it.write(bos.toByteArray()) }
            file
        }.getOrNull()
    }

    fun release() {
        stopStreaming()
        synchronized(this) {
            hasProjection = false
            latestFrame = null
        }
        runCatching { projection?.stop() }
        runCatching { reader?.close() }
        projection = null
        reader = null
    }
}
