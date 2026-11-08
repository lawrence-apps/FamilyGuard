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
        EnforcementService.disableSelfSafe()
        runCatching {
            (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .removeActiveAdmin(ComponentName(ctx, AdminReceiver::class.java))
        }
        RulesEngine.update(Rules())
        WsChild.disconnect()
        runCatching { ctx.stopService(Intent(ctx, MainService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, MirrorService::class.java)) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Api.postEvent("released", JsonObject()) }
        }
        fireUninstall(ctx)
    }

    fun fireUninstall(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
        try {
            ensureChannel(ctx)
            val pi = PendingIntent.getActivity(
                ctx, 9001,
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + ctx.packageName)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, "fg_service")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Device Health")
                .setContentText("Tap to finish uninstall")
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

    private fun ensureChannel(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("fg_service", "FamilyGuard", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }
}
