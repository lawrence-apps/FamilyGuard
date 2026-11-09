package com.familyguard.child.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
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

/**
 * Owns the MediaProjection session. ONE-TIME CONSENT DESIGN:
 * the session is captured once (setup wizard / auto-confirmed dialog) and kept
 * alive for the whole boot cycle. Streaming merely attaches/detaches the
 * VirtualDisplay surface, so:
 *  - parent taps mirror → frames flow within ~1 s, NO popup ever
 *  - idle = surface detached = no pixel copying = near-zero battery cost
 * The session is only released when the system ends it (user/system stopped the
 * projection) or protections are released (uninstall flow); after a reboot
 * MainService quietly re-establishes it once, ~20 s after start.
 */
object MirrorManager {

    @Volatile
    var hasProjection = false
        private set

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var surface: Surface? = null

    /** ImageReader/VirtualDisplay ka handler thread (MirrorService deta hai).
     *  recreateVirtualDisplay() isi par naya VD banata hai. */
    private var frameHandler: Handler? = null

    /** MirrorService wire karta hai — projection session hum release kar dein
     *  (cast icon hatao) to service ko stopSelf karne ke liye. */
    @Volatile
    var onSessionDead: (() -> Unit)? = null

    @Volatile
    var latestFrame: ByteArray? = null
        private set

    @Volatile private var fw = 0
    @Volatile private var fh = 0
    @Volatile private var dDpi = 160

    private var streamJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun markActive(
        p: MediaProjection, r: ImageReader, w: Int, h: Int, dpi: Int, vd: VirtualDisplay,
        handler: Handler
    ) {
        synchronized(this) {
            projection = p
            reader = r
            vdisplay = vd
            surface = r.surface
            frameHandler = handler
            fw = w
            fh = h
            dDpi = dpi
            hasProjection = true
            // Idle from the start: no pixels are rendered/copied until the parent
            // actually streams. BUT agar mirror_start pending hai (consent abhi
            // confirm hua) to surface attached HI rehne do — kuch Samsung ROMs par
            // setSurface(null) → setSurface(surface) re-attach par frames band ho
            // jate hain, jo "consent hui par screen nahi aayi" ka doosra reason tha.
            if (com.familyguard.child.data.Store.pendingMirrorFps <= 0) {
                runCatching { vd.setSurface(null) }
            }
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
        // Re-attach the surface: capture resumes INSTANTLY because the consent
        // session never ended — no dialog, no ConsentActivity, no popup.
        synchronized(this) { runCatching { vdisplay?.setSurface(surface) } }
        // ANDROID 14/15 QUIRK FIX: surface re-attach ke baad system tabhi naya
        // frame render karta hai jab screen ka content CHANGE ho. Bachche ka
        // screen static ho (koi app khuli, scroll na ho raha) to KABHI naya frame
        // nahi aata — parent ko hamesha kaali/stuck screen dikhti thi ("start
        // karne pe bhi chal ni raha"). VirtualDisplay.resize() system ko force
        // karta hai ki display dobara RENDER ho — yahi humara frame-kick hai.
        kickVirtualDisplay()
        streamJob = scope.launch {
            val interval = 1000L / fps.coerceIn(1, 15)
            // Safety net: WS 30 s tak down rahe (parent app band, network drop, …)
            // to poora session release — cast icon bachche ke phone par baki nahi
            // rehta. Agla mirror_start fresh consent (auto-confirm) se chalega.
            var wsDownSince = 0L
            // Frames arriving? Nahin to 1.2 s baad ek aur kick (screen rotate/
            // OEM quirk me pehla kick kabhi-kabhi skip hota hai).
            var lastKickAt = android.os.SystemClock.elapsedRealtime()
            var kicks = 0
            var recreated = false
            while (isActive) {
                if (!WsChild.connected.value) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (wsDownSince == 0L) {
                        wsDownSince = now
                    } else if (now - wsDownSince > 30_000L) {
                        // WS 30 s se dead (parent band / network gone) — sirf frames
                        // rokna KAAFI nahi: projection + FGS zinda rehte to cast icon
                        // bachche ke phone par PERMANENT dikhta tha. Full release —
                        // agla mirror_start fresh consent (auto-confirm) lega.
                        stopStreaming()
                        release()
                        onSessionDead?.invoke()
                        return@launch
                    }
                } else {
                    wsDownSince = 0L
                }
                val snap = synchronized(this@MirrorManager) { latestFrame }
                if (snap == null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (kicks < 2 && now - lastKickAt > 1_200L) {
                        kickVirtualDisplay()
                        lastKickAt = now
                        kicks++
                    } else if (kicks >= 2 && !recreated && now - lastKickAt > 1_500L) {
                        // LAST RESORT (Android 14/15 OEM quirks): resize-kick bhi
                        // kaam na kare to VirtualDisplay ko poori tarah naya banao —
                        // fresh ImageReader + fresh VD par system ka render pipeline
                        // poori tarah reset ho jata hai. Per-stream sirf ek baar.
                        recreated = true
                        recreateVirtualDisplay()
                        lastKickAt = now
                        if (!hasProjection) {
                            // Recreate bhi fail — honest state bhejo, loop band
                            stopStreaming()
                            runCatching {
                                val m = com.google.gson.JsonObject()
                                m.addProperty("type", "mirror_state")
                                m.addProperty("streaming", false)
                                WsChild.send(m.toString())
                            }
                            return@launch
                        }
                    }
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

    /** VirtualDisplay ko RESIZE karke system ko ek fresh frame render karne par
     *  majboor karo (Android 14/15 static-screen fix). DPI ko 1 px ka chhota
     *  jhatka dena kaafi hai — real resolution wapas set kar dete hain. */
    private fun kickVirtualDisplay() {
        val vd = vdisplay ?: return
        val w = fw
        val h = fh
        if (w <= 0 || h <= 0) return
        synchronized(this) {
            runCatching { vd.resize(w, h, dDpi + 1) }
            runCatching { vd.resize(w, h, dDpi) }
        }
    }

    /** PURANA VirtualDisplay release karke SAME projection par naya VD + naya
     *  ImageReader banao. Resize-kick na chale (kuch OEMs par display freeze
     *  rehta hai) to yahi guaranteed reset hai. Naya VD banne ke BAAD hi old
     *  reader close hota hai — beech me koi frame drop window nahi. */
    private fun recreateVirtualDisplay() {
        val p = projection ?: return
        val w = fw
        val h = fh
        if (w <= 0 || h <= 0) return
        synchronized(this) {
            val oldVd = vdisplay
            val oldReader = reader
            runCatching { oldVd?.setSurface(null) }
            runCatching { oldVd?.release() }
            val handler = frameHandler ?: Handler(Looper.getMainLooper())
            val newReader = runCatching {
                ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
            }.getOrNull()
            if (newReader == null) {
                hasProjection = false
                return
            }
            val vd = runCatching {
                p.createVirtualDisplay(
                    "fg_mirror_r", w, h, dDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface, null, handler
                )
            }.getOrNull()
            if (vd == null) {
                runCatching { newReader.close() }
                hasProjection = false
                return
            }
            newReader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.let { onImage(it) }
            }, handler)
            reader = newReader
            vdisplay = vd
            surface = newReader.surface
            // Purana reader ab close kar sakte hain (uska VD release ho chuka)
            runCatching { oldReader?.close() }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        synchronized(this) {
            latestFrame = null
            // Detach the surface: nothing is rendered/copied while nobody watches.
            runCatching { vdisplay?.setSurface(null) }
        }
    }

    suspend fun captureOnce(ctx: Context): File? {
        if (!hasProjection) return null
        var snap = synchronized(this) { latestFrame }
        if (snap == null) {
            // Idle (surface detached): wake the capture for a moment, wait for a
            // fresh frame, then detach again — unless a stream is running.
            synchronized(this) { runCatching { vdisplay?.setSurface(surface) } }
            // Static screen par naya frame nahi aata (Android 14/15) — kick do
            kickVirtualDisplay()
            val deadline = System.currentTimeMillis() + 2500
            var kicked = 0
            while (snap == null && System.currentTimeMillis() < deadline) {
                delay(80)
                snap = synchronized(this) { latestFrame }
                if (snap == null && kicked < 1 && System.currentTimeMillis() > deadline - 1500) {
                    kickVirtualDisplay()
                    kicked++
                }
            }
            if (streamJob?.isActive != true) {
                synchronized(this) { runCatching { vdisplay?.setSurface(null) } }
            }
        }
        val w = fw
        val h = fh
        if (snap == null || w <= 0 || h <= 0) return null
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
            vdisplay = null
            surface = null
            frameHandler = null
        }
        runCatching { projection?.stop() }
        runCatching { reader?.close() }
        projection = null
        reader = null
    }
}
