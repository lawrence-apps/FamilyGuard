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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.familyguard.child.IconHider
import com.familyguard.child.RulesEngine
import com.familyguard.child.admin.AdminReceiver
import com.familyguard.child.data.Api
import com.familyguard.child.data.Rules
import com.familyguard.child.data.Store
import com.familyguard.child.service.EnforcementService
import com.familyguard.child.service.MainService
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.MirrorService
import com.familyguard.child.service.WsChild
import com.familyguard.child.ui.BlockOverlay
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object AgeRelease {

    private val fired = AtomicBoolean(false)
    /** One-shot per process: silent fallback notification + saari runtime permissions revoke. */
    private val cleanupDone = AtomicBoolean(false)
    private val loopHandler = Handler(Looper.getMainLooper())
    @Volatile private var loopRunning = false
    /** Last ACTION_DELETE fire time (elapsedRealtime) — stacked-dialog guard. */
    @Volatile private var lastFireAt = 0L

    fun ageYears(dob: String): Int {
        if (dob.isBlank()) return -1
        return runCatching {
            java.time.Period.between(java.time.LocalDate.parse(dob), java.time.LocalDate.now()).years
        }.getOrDefault(-1)
    }

    fun shouldRelease(ctx: Context): Boolean = Store.released || (Store.paired && ageYears(Store.dob) >= 18)

    fun releaseAll(ctx: Context) {
        if (Store.released) {
            fireUninstall(ctx)
            ensureUninstallLoop(ctx)
            return
        }
        Store.released = true
        fired.set(true)
        runCatching { WorkManager.getInstance(ctx).cancelAllWork() }
        BlockOverlay.hideAll()
        MirrorManager.stopStreaming()
        MirrorManager.release()
        // ARM the uninstall auto-click BEFORE anything touches accessibility:
        // jab system uninstall dialog khulega (parent ne dashboard se remove kiya,
        // app background me thi) to EnforcementService "Uninstall"/"OK" button
        // khud click kar dega — bachche ke phone par ek bhi tap nahi lagega.
        Store.autoClickStep = "uninstall"
        Store.autoClickUntil = SystemClock.elapsedRealtime() + 120_000L
        // NOTE: EnforcementService.disableSelfSafe() yahan JAAN BOOJH KAR hata diya.
        // Accessibility off karte hi uninstall dialog ka auto-click mar jata tha aur
        // dialog on-screen atki rehti thi. App uninstall hone par process mar jayega;
        // agar user dialog cancel kar de to bhi released state me koi protection
        // active nahi hai (Rules khali + BlockOverlay skip), to accessibility on
        // rehna harmless hai.
        removeAdmin(ctx)
        RulesEngine.update(Rules())
        WsChild.disconnect()
        runCatching { ctx.stopService(Intent(ctx, MainService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, MirrorService::class.java)) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Api.postEvent("released", JsonObject()) }
        }
        fireUninstall(ctx)
        ensureUninstallLoop(ctx)
    }

    /** Device admin ko hatao — uninstall dialog iske bina fail hota hai
     *  ("can't uninstall — active device administrator"). */
    private fun removeAdmin(ctx: Context) {
        runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val cn = ComponentName(ctx, AdminReceiver::class.java)
            if (dpm.isAdminActive(cn)) dpm.removeActiveAdmin(cn)
        }
    }

    /** Ek hi call me poora cleanup: silent tray fallback post karo (notification
     *  permission abhi zinda hai), uske BAAD saari runtime permissions revoke —
     *  parent ka flow: "permissions hat ke automatically uninstall". Accessibility
     *  JAAN BOOJH KAR on rehti hai (uninstall dialog ka OK auto-click usi se hota
     *  hai) aur app uninstall hote hi wo bhi apne aap chali jayegi. */
    private fun oneShotCleanup(ctx: Context) {
        if (!cleanupDone.compareAndSet(false, true)) return
        postSilentFallback(ctx)
        revokeOwnPermissions(ctx)
    }

    /** Saari GRANTED runtime permissions khud revoke kar lo — location, camera,
     *  mic, contacts, calls, SMS, notifications… Self-revocation system me allowed
     *  hai (same-uid). PackageManager.revokeRuntimePermission public SDK se hat
     *  chuki hai (API 34 me sirf UiAutomation par hai) — isliye REFLECTION se:
     *  framework me method abhi bhi zinda hai, hidden-api greylist se call hota
     *  hai. Kisi ROM par block ho to bhi harmless — uninstall phir bhi saari
     *  permissions apne aap hata deta hai. */
    private fun revokeOwnPermissions(ctx: Context) {
        runCatching {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            val revoke = PackageManager::class.java.getMethod(
                "revokeRuntimePermission",
                String::class.java, String::class.java, android.os.UserHandle::class.java
            )
            val me = android.os.Process.myUserHandle()
            for (p in pi.requestedPermissions ?: emptyArray()) {
                runCatching { revoke.invoke(pm, ctx.packageName, p, me) }
            }
        }
    }

    /** SILENT tray-only fallback (IMPORTANCE_LOW — popup/sound/vibration KUCH nahi).
     *  Sirf last-resort ke liye: agar OEM ne uninstall dialog ka background open
     *  block kar diya ho to tray entry tap karne par dialog khul jata hai (aur
     *  auto-click use confirm kar dega). Purana HIGH-importance channel delete —
     *  wo "Tap to finish uninstall" popup dikhata tha jo user ko pasand nahi. */
    private fun postSilentFallback(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("dh_uninstall")
            nm.createNotificationChannel(
                NotificationChannel("dh_uninstall2", "Device Health", NotificationManager.IMPORTANCE_LOW)
            )
            val pi = PendingIntent.getActivity(
                ctx, 9001,
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, "dh_uninstall2")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Device Health")
                .setContentText("Finishing device cleanup")
                .setContentIntent(pi)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
            if (
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < 33
            ) {
                NotificationManagerCompat.from(ctx).notify(9002, n)
            }
        } catch (_: Exception) {
        }
    }

    fun fireUninstall(ctx: Context) {
        // Admin wapas se hatao — fireUninstall alag path se bhi chalega (BootReceiver,
        // WatchdogReceiver, loop); admin live ho to system uninstall FAIL hota hai.
        removeAdmin(ctx)
        // Auto-click arm karo — uninstall dialog ke "OK"/"Uninstall" par khud click.
        Store.autoClickStep = "uninstall"
        Store.autoClickUntil = SystemClock.elapsedRealtime() + 120_000L
        oneShotCleanup(ctx)
        // Stacked-dialog guard: uninstall dialog abhi screen par hai (installer
        // window abhi dikhi) ya hum abhi-abhi fire kar chuke hain — dobara
        // ACTION_DELETE fire karke dialogs stack NA karo.
        val now = SystemClock.elapsedRealtime()
        if (now - EnforcementService.lastInstallerSeenAt < 8_000L) return
        if (now - lastFireAt < 8_000L) return
        lastFireAt = now
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** SELF-DRIVING uninstall loop — release ke baad bachche ke phone par EK BHI
     *  tap nahi lagna chahiye. Jab tak app zinda hai har ~4 s:
     *  1. auto-click arm fresh (120 s) — dialog kabhi expire na ho
     *  2. device admin dobara hatao (idempotent)
     *  3. uninstall dialog screen par NAHI hai to khud kholo — EnforcementService
     *     dialog khulte hi "OK"/"Uninstall" khud click kar dega.
     *  User dialog cancel kare, OEM block kare, phone reboot ho — loop/phir se
     *  khol dega. App uninstall hote hi process mar jata hai aur loop bhi — wahi
     *  success hai. WatchdogReceiver har 2 min is loop ko process restart ke
     *  baad bhi wapas chala deta hai, isliye uninstall kabhi "atak" nahi sakta. */
    fun ensureUninstallLoop(ctx: Context) {
        val app = ctx.applicationContext
        if (loopRunning) return
        loopRunning = true
        loopHandler.post(object : Runnable {
            override fun run() {
                if (!Store.released) {
                    loopRunning = false
                    return // safety — release flag kisi ne clear kiya to loop band
                }
                runCatching { fireUninstall(app) }
                loopHandler.postDelayed(this, 4_000L)
            }
        })
    }
}
