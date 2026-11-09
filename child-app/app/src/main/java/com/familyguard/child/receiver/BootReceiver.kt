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
        UsageWorker.runOnce(context)
    }
}
