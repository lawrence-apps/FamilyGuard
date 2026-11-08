package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.familyguard.child.data.Store
import com.familyguard.child.service.MainService
import com.familyguard.child.service.UsageWorker
import com.familyguard.child.util.AgeRelease

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store.paired) {
            if (AgeRelease.shouldRelease(context)) {
                AgeRelease.releaseAll(context)
            } else {
                runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, MainService::class.java))
                }
                UsageWorker.runOnce(context)
            }
        }
    }
}
