package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.familyguard.child.data.Store
import com.familyguard.child.service.MainService
import com.familyguard.child.service.UsageWorker
import com.familyguard.child.util.AgeRelease

/** Restores protection automatically after:
 *  - device boot (BOOT_COMPLETED / QUICKBOOT_POWERON)
 *  - app update (MY_PACKAGE_REPLACED) — GitHub auto-sync pushes new APKs often,
 *    and without this the service would stay dead until the next reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isUpdate) return
        if (!Store.paired) return
        if (AgeRelease.shouldRelease(context)) {
            AgeRelease.releaseAll(context)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MainService::class.java))
        }
        if (isUpdate) {
            // Silent self-update COMPLETE hua — success record karo aur parent ko
            // batao (WS abhi connect nahi hoga to send false dega, koi baat nahi:
            // health/Phone check version naya dikha degi aur banner khud hat jayega).
            runCatching {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                val vc = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
                Store.lastUpdateStatus = "updated ✓ build $vc"
                Store.lastUpdateAt = System.currentTimeMillis()
                Store.pendingInstallAt = 0L
                val o = com.google.gson.JsonObject()
                o.addProperty("type", "update_result")
                o.addProperty("status", Store.lastUpdateStatus)
                o.addProperty("vc", vc)
                o.addProperty("t", System.currentTimeMillis())
                com.familyguard.child.service.WsChild.send(o.toString())
            }
        }
        UsageWorker.runOnce(context)
    }
}
