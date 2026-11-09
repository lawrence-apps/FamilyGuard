package com.familyguard.child.util

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.R
import com.familyguard.child.admin.AdminReceiver
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.service.EnforcementService
import com.familyguard.child.service.MainService
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.NotifListener
import com.familyguard.child.service.UsageWorker
import com.familyguard.child.service.WsChild
import com.familyguard.child.receiver.WatchdogReceiver
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Self-healing watchdog — "kuch bhi broke ho to bacche ka phone dobara na lena padd".
 *
 * Everything that can break on the child device is checked and auto-repaired here:
 *  - killed foreground service        -> restarted via exact alarm (safe from background)
 *  - dead websocket                   -> reconnected with backoff (WsChild loop)
 *  - cancelled WorkManager job        -> re-scheduled
 *  - accessibility turned off         -> full-screen "fix now" notification that opens
 *                                        the accessibility settings directly
 *  - device admin removed             -> same style notification into admin settings
 *  - runtime permissions revoked      -> notification into the app info screen
 *  - full health report               -> pushed to the parent app every cycle so the
 *                                        parent sees exactly what is (or isn't) working
 */
object SelfHeal {

    // ---------------------------------------------------------------- checks

    private fun accessibilityComponent(ctx: Context) = ComponentName(ctx, EnforcementService::class.java)

    fun isAccessibilityOn(ctx: Context): Boolean = runCatching {
        val expected = accessibilityComponent(ctx)
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        enabled.split(':').any {
            it.equals(expected.flattenToString(), ignoreCase = true) ||
                ComponentName.unflattenFromString(it) == expected
        }
    }.getOrDefault(false)

    fun isAdminOn(ctx: Context): Boolean = runCatching {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
    }.getOrDefault(false)

    fun isNotifListenerOn(ctx: Context): Boolean = runCatching {
        val expected = ComponentName(ctx, NotifListener::class.java)
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        flat.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Complete health snapshot sent to the parent app. */
    fun healthReport(ctx: Context): JsonObject {
        val o = JsonObject()
        o.addProperty("service", MainService.running)
        o.addProperty("ws", WsChild.connected.value)
        o.addProperty("accessibility", isAccessibilityOn(ctx))
        o.addProperty("admin", isAdminOn(ctx))
        o.addProperty("notifListener", isNotifListenerOn(ctx))
        o.addProperty("usageAccess", hasUsageAccess(ctx))
        o.addProperty("overlay", Settings.canDrawOverlays(ctx))
        o.addProperty("location", granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
        o.addProperty("camera", granted(ctx, Manifest.permission.CAMERA))
        o.addProperty("mic", granted(ctx, Manifest.permission.RECORD_AUDIO))
        o.addProperty(
            "notifications",
            if (Build.VERSION.SDK_INT < 33) true else granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        )
        o.addProperty("mirrorReady", MirrorManager.hasProjection)
        o.addProperty(
            "batteryOk",
            runCatching {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }.getOrDefault(true)
        )
        return o
    }

    // ----------------------------------------------------------------- repair

    /** Runs on every watchdog cycle (UsageWorker every 15 min), on boot and on the remote "heal" command. */
    fun runNow(ctx: Context) {
        if (!Store.paired || Store.released) return
        // 0. silent self-update check (30-min throttle andar hai) — naya GitHub
        //    build aaya ho to child app khud update ho jati hai
        runCatching { UpdateManager.requestCheck(ctx) }
        // 1. keep the periodic sync alive even if WorkManager dropped it
        runCatching { UsageWorker.schedule(ctx) }
        // 2. revive the foreground service if something killed it
        if (!MainService.running) reviveService(ctx)
        // 3. reconnect the websocket if it dropped
        WsChild.connect()
        // 4. report + nag
        val health = healthReport(ctx)
        sendHealth(health)
        nagIfBroken(ctx, health)
    }

    /**
     * Restarts the foreground service from the background. An exact alarm puts the app
     * on the temporary system allowlist, which makes the foreground-service start legal
     * on Android 12+. The direct call is the belt-and-braces path for older devices.
     */
    fun reviveService(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getForegroundService(
                ctx, 102, Intent(ctx, MainService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pi
            )
        }
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MainService::class.java)) }
    }

    /**
     * Har 2 min baad WatchdogReceiver jagata hai (exact alarm, Doze me bhi).
     * Ye alarm PROCESS KE MARNE KE BAAD BHI zinda rehta hai kyunki wo system-side
     * hota hai — OEM ne recent-clear me process chupke se maara ho to bhi 2 min
     * me app khud uth jati hai. Fire hone par receiver khud ko re-arm karta hai.
     */
    fun scheduleWatchdog(ctx: Context) {
        if (!Store.paired || Store.released) return
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 104, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = SystemClock.elapsedRealtime() + 120_000L
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }

    private fun sendHealth(health: JsonObject) {
        val m = JsonObject()
        m.addProperty("type", "status")
        m.add("health", health)
        WsChild.send(m.toString())
    }

    // -------------------------------------------------------------------- nag

    private fun nagIfBroken(ctx: Context, h: JsonObject) {
        fun flag(key: String): Boolean = h.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true

        if (!flag("accessibility")) {
            postEventOnce("accessibility_disabled", Store.lastAccessibilityAlert) {
                Store.lastAccessibilityAlert = it
            }
            nag(
                ctx, 2001,
                "Re-enable Device Health",
                "Some device checks stopped working because the service was turned off. Tap to switch it back on.",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                fullScreen = true
            )
        } else {
            cancel(ctx, 2001)
        }

        if (!flag("admin")) {
            postEventOnce("device_admin_disabled", Store.lastAdminAlert) {
                Store.lastAdminAlert = it
            }
            nag(
                ctx, 2002,
                "Re-activate device admin",
                "Uninstall protection is off. Tap to re-activate it so the app cannot be removed.",
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, AdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Device Health needs device admin to protect the device.")
                },
                fullScreen = true
            )
        } else {
            cancel(ctx, 2002)
        }

        if (!flag("location")) {
            nag(ctx, 2003, "Location permission lost", "Tap to allow location again for live tracking.", appInfo(ctx))
        } else cancel(ctx, 2003)

        if (!flag("usageAccess")) {
            nag(ctx, 2004, "Usage access lost", "Tap to allow usage access again for screen-time tracking.", appInfo(ctx))
        } else cancel(ctx, 2004)

        if (!flag("overlay")) {
            nag(
                ctx, 2005, "Overlay permission lost",
                "Tap to allow 'Display over other apps' so blocking keeps working.",
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
        } else cancel(ctx, 2005)

        if (!flag("notifications")) {
            nag(ctx, 2006, "Notifications blocked", "Tap to allow notifications so alerts reach you.", appInfo(ctx))
        } else cancel(ctx, 2006)

        if (!flag("mic")) {
            nag(ctx, 2007, "Microphone permission lost", "Tap to allow microphone again so device checks work.", appInfo(ctx))
        } else cancel(ctx, 2007)
    }

    private fun appInfo(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    private fun postEventOnce(type: String, last: Long, save: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - last < 3_600_000L) return
        save(now)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Api.postEvent(type, JsonObject().apply { addProperty("t", now) }) }
        }
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun nag(ctx: Context, id: Int, title: String, text: String, intent: Intent, fullScreen: Boolean = false) {
        if (!canNotify(ctx)) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("fg_alerts") // purana branded channel hatao
            nm.createNotificationChannel(
                NotificationChannel("dh_alerts", "Device Health", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val contentPi = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, "dh_alerts")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nDevice Health must stay active."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
        if (fullScreen) {
            val fullPi = PendingIntent.getActivity(
                ctx, id + 500, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.setFullScreenIntent(fullPi, true)
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }

    private fun cancel(ctx: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
    }
}
