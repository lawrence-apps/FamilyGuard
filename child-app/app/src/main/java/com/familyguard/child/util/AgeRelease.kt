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

    fun fireUninstall(ctx: Context) {
        // Admin wapas se hatao — fireUninstall alag path se bhi chalega (UI button,
        // BootReceiver release path); admin live ho to system uninstall FAIL hota hai.
        removeAdmin(ctx)
        // Auto-click arm karo (agar releaseAll skip hua ho ya user bohot baad me
        // app khol kar button dabaaye to dialog phir bhi khud confirm ho jaye).
        Store.autoClickStep = "uninstall"
        Store.autoClickUntil = SystemClock.elapsedRealtime() + 120_000L
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
        // Heads-up fallback: agar background activity start OEM ne block kiya to
        // bhi user ko ek SAFA dikhe — tap karke uninstall dialog khul jayega.
        // IMPORTANCE_MIN notification chhup jati thi (puraani "uninstall atak
        // gaya" wali problem ka main reason).
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("dh_uninstall", "Device Health", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                ctx, 9001,
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, "dh_uninstall")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Device Health")
                .setContentText("Tap to finish uninstall")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
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
}
