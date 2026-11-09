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
 * Owns the MediaProjection session. FRESH-SESSION DESIGN (Android 14/15 docs):
 * one consent = ONE MediaProjection = ONE createVirtualDisplay (targetSdk 34+
 * par doosri createVirtualDisplay call SecurityException deti hai, aur stop()
 * ke baad session dobara use nahi hota). Isliye:
 *  - mirror_start → fresh consent dialog (accessibility auto-confirm 2-5 s)
 *    → naya projection → naya VD → frames
 *  - stream band/stall hone par session TURANT release — system ka cast icon
 *    bhi saath me gaya (idle watchdog + 10 s give-up + WS-down safety net)
 *  - surface VD par attached rehta hai (detach/reattach OEM quirk frames
 *    todta hai); stream loop LAST frame ko cached JPEG se steady fps par
 *    re-send karta hai, static screen par bhi parent ko "no frames" nahi dikhta
 */
object MirrorManager {

    @Volatile
    var hasProjection = false
        private set

    /** Abhi actual streaming chal rahi hai? (Website diag ke liye) */
    val isStreaming: Boolean
        get() = synchronized(this) { streamJob?.isActive == true }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var surface: Surface? = null

    /** ImageReader/VirtualDisplay ka handler thread (MirrorService deta hai).
     *  recreateVirtualDisplay() isi par naya VD banata hai. */
    private var frameHandler: Handler? = null

    /** Surface VD par attached hai ya nahi. ANDROID 14/15 GOLDEN RULE: jitna ho
     *  sake surface ko detach MAT karo — Samsung/ColorOS par setSurface(null) →
     *  re-attach ke baad frames HAMESHA ke liye band ho jate the. Fresh-session
     *  design me VD banate hi surface attached rehta hai aur session kaafi chhota
     *  hota hai (idle watchdog 5-20 s me release kar deta hai). */
    @Volatile
    private var surfaceAttached = true

    /** Har naye captured frame par badhta hai — stream loop isi se jaanta hai
     *  ki frame NAYA hai (re-encode) ya purana hai (cached JPEG re-send). */
    @Volatile
    private var frameSeq = 0L

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
            surfaceAttached = true
            frameSeq = 0L
            // ANDROID 14/15 RESEARCH FIX: pehle idle par yahan setSurface(null)
            // hota tha — "kuch Samsung ROMs par re-attach par frames band ho jate
            // hain". Ab fresh-session design me idle session 5-20 s me khud
            // release ho jata hai, isliye detach ki zaroorat hi nahi — surface
            // attached rehta hai aur PEHLA frame bina kisi quirk ke aata hai.
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
                frameSeq++
            }
        } catch (_: Exception) {
        } finally {
            runCatching { image.close() }
        }
    }

    fun startStreaming(fps: Int, quality: Int): Boolean {
        if (!hasProjection) return false
        streamJob?.cancel()
        streamJob = null
        synchronized(this) { latestFrame = null }
        // ANDROID 14/15 RESEARCH FIX: pehle HAR start par stopStreaming() chalta
        // tha jo setSurface(null) karta tha, phir turant re-attach — yahi
        // detach/reattach dance Samsung/ColorOS ROMs par frames permanently tod
        // deta tha ("consent hui par screen kaali"). Ab surface attached hai to
        // CHHEDTE NAHI — sirf detached ho to wapas lagao.
        if (!surfaceAttached) {
            synchronized(this) {
                runCatching { vdisplay?.setSurface(surface) }
                surfaceAttached = true
            }
        }
        // Static screen par bhi pehla frame forced render ho (resize jiggle).
        kickVirtualDisplay()
        streamJob = scope.launch {
            val interval = 1000L / fps.coerceIn(1, 15)
            // Safety net: WS 30 s tak down rahe (parent app band, network drop, …)
            // to poora session release — cast icon bachche ke phone par baki nahi
            // rehta. Agla mirror_start fresh consent (auto-confirm) se chalega.
            var wsDownSince = 0L
            val startAt = android.os.SystemClock.elapsedRealtime()
            var lastKickAt = startAt
            var step = 0
            // JPEG cache: static screen par RGBA→JPEG har 125 ms encode karna
            // CPU jalata hai. Naya frame (frameSeq badla) to re-encode, warna
            // pichla JPEG seedha re-send — site ko steady fps milti rahegi aur
            // "no frames" box kabhi galat nahi dikhega.
            var lastEncodedSeq = -1L
            var lastEncoded: ByteArray? = null
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
                    val sinceKick = now - lastKickAt
                    // ANDROID 14/15 RECOVERY LADDER — official rule: EK projection
                    // = sirf EK createVirtualDisplay (targetSdk 34+ par doosri
                    // call SecurityException). Isliye sab kicks SAME-VD hain:
                    // resize-jiggle → surface-bounce → (purane Android par hi
                    // kaam karta) recreate → honest give-up.
                    when {
                        sinceKick > 1_000L && step == 0 -> { kickVirtualDisplay(); lastKickAt = now; step = 1 }
                        sinceKick > 1_000L && step == 1 -> { kickVirtualDisplay(); lastKickAt = now; step = 2 }
                        sinceKick > 1_200L && step == 2 -> { bounceSurface(); lastKickAt = now; step = 3 }
                        sinceKick > 1_500L && step == 3 -> { kickVirtualDisplay(); lastKickAt = now; step = 4 }
                        sinceKick > 1_500L && step == 4 -> {
                            recreateVirtualDisplay()
                            lastKickAt = now
                            step = 5
                        }
                        now - startAt > 10_000L && step < 6 -> {
                            // HONEST GIVE-UP: 10 s me ek bhi frame nahi bana.
                            // Pehle yahan loop chalta rehta tha — site par "streaming"
                            // dikhta magar kaali screen + "no frames" box hamesha.
                            // Ab: session release (cast icon bhi gaya) + REAL reason
                            // parent ko. Agla start fresh consent se — 2-5 s.
                            stopStreaming()
                            release()
                            sendMirrorState(false, stallReason())
                            onSessionDead?.invoke()
                            return@launch
                        }
                    }
                    delay(100)
                    continue
                }
                val seq = frameSeq
                var jpg = lastEncoded
                if (jpg == null || seq != lastEncodedSeq) {
                    val w = fw
                    val h = fh
                    if (w <= 0 || h <= 0) {
                        delay(100)
                        continue
                    }
                    jpg = runCatching {
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(snap))
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), bos)
                        bmp.recycle()
                        bos.toByteArray()
                    }.getOrNull()
                    if (jpg == null) {
                        delay(50)
                        continue
                    }
                    lastEncodedSeq = seq
                    lastEncoded = jpg
                }
                WsChild.sendBinary(jpg)
                delay(interval)
            }
        }
        return true
    }

    /** Parent ko honest mirror state bhejo (reason ke saath — site seedha wahi
     *  dikhata hai, generic "consent confirm ho raha hai" guess nahi). */
    private fun sendMirrorState(streaming: Boolean, reason: String? = null) {
        runCatching {
            val m = com.google.gson.JsonObject()
            m.addProperty("type", "mirror_state")
            m.addProperty("streaming", streaming)
            if (reason != null) m.addProperty("reason", reason)
            WsChild.send(m.toString())
        }
    }

    /** "Frames kyun nahi aa rahe" ka REAL jawab: screen OFF to wahi bolo,
     *  warna OEM capture-stall ka honest message. */
    private fun stallReason(): String {
        val screenOn = runCatching {
            val pm = com.familyguard.child.Graph.app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isInteractive
        }.getOrDefault(true)
        return if (screenOn) {
            "Screen ON hai phir bhi capture shuru nahi hua (phone ne block kiya) — Mirror dobara start karke try karo"
        } else {
            "Bachche ka screen OFF hai — screen ON karke Mirror dobara start karo"
        }
    }

    /** Android 14/15 static-screen ka SABSE reliable same-session kick: surface
     *  ko ek jhatke me detach + re-attach — SurfaceFlinger apna output dobara
     *  bind karta hai aur FRESH frame render karta hai. Same VirtualDisplay
     *  instance re-use hota hai (doosra createVirtualDisplay targetSdk 34+ par
     *  SecurityException deta hai). NOTE: ye mid-ladder step hai — kuch OEMs par
     *  bounce ke baad frames band ho jate hain, us case me ladder honest
     *  give-up par khatam hota hai (session release, cast icon gaya). */
    private fun bounceSurface() {
        synchronized(this) {
            val vd = vdisplay ?: return
            val sf = surface ?: return
            runCatching { vd.setSurface(null) }
            runCatching { vd.setSurface(sf) }
            surfaceAttached = true
        }
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
     *  ImageReader banao. ANDROID 14/15 NOTE (official docs): targetSdk 34+
     *  par doosri createVirtualDisplay() call SecurityException deti hai — ye
     *  fallback sirf Android 13- devices par kaam karta hai, 14+ par runCatching
     *  use pakad leta hai aur ladder honest give-up par aage badhta hai. Naya
     *  VD banne ke BAAD hi old reader close hota hai — beech me koi drop window
     *  nahi. */
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
        synchronized(this) { latestFrame = null }
        // ANDROID 14/15 RESEARCH FIX: yahan pehle setSurface(null) DETACH hota
        // tha — agli start par re-attach Samsung/ColorOS par frames permanently
        // tod deta tha. Fresh-session design me idle session jaldi release ho
        // jata hai (cast icon waise bhi gaya), isliye detach ki koi zaroorat nahi.
    }

    suspend fun captureOnce(ctx: Context): File? {
        if (!hasProjection) return null
        var snap = synchronized(this) { latestFrame }
        if (snap == null) {
            if (!surfaceAttached) {
                synchronized(this) {
                    runCatching { vdisplay?.setSurface(surface) }
                    surfaceAttached = true
                }
            }
            // Static screen par naya frame nahi aata (Android 14/15) — pehle
            // resize kick, phir surface-bounce (same-VD, 14-legal)
            kickVirtualDisplay()
            val deadline = System.currentTimeMillis() + 2500
            var bounced = false
            while (snap == null && System.currentTimeMillis() < deadline) {
                delay(80)
                snap = synchronized(this) { latestFrame }
                if (snap == null && !bounced && System.currentTimeMillis() > deadline - 1500) {
                    bounceSurface()
                    bounced = true
                }
            }
            // ANDROID 14/15 RESEARCH FIX: capture ke baad surface DETACH nahi
            // karte — Samsung re-attach quirk screenshot-retry ko tod deta tha.
            // Session waise bhi chhota hai (idle watchdog ≤20 s me release).
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
        val vdToRelease: VirtualDisplay?
        val rdToClose: ImageReader?
        synchronized(this) {
            vdToRelease = vdisplay
            rdToClose = reader
            hasProjection = false
            latestFrame = null
            vdisplay = null
            surface = null
            frameHandler = null
            surfaceAttached = false
        }
        // ORDER (official docs): pehle VirtualDisplay release (frame pipeline
        // turant band), phir projection.stop() (system ka CAST ICON isi se
        // jata hai), phir ImageReader close. Pehle vdisplay ko bina release
        // kiye null kar rahe the — SurfaceFlinger side ka output kabhi-kabhi
        // late tear-down hota tha.
        runCatching { vdToRelease?.release() }
        runCatching { projection?.stop() }
        runCatching { rdToClose?.close() }
        synchronized(this) {
            projection = null
            reader = null
        }
    }
}
