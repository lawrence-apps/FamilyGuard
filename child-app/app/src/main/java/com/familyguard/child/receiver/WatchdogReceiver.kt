package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.child.util.AgeRelease
import com.familyguard.child.util.SelfHeal

/**
 * Har 2 min self-re-arming exact alarm.
 *
 * "Recent apps" se app clear karne par kayi ROMs process ko chupke se maar deti
 * hain — one-shot onTaskRemoved/onDestroy alarm bhi usi force-stop me cancel ho
 * jata hai, aur phir sirf 15-min WorkManager bachata hai (jo aggressive OEMs par
 * khud deferred hota hai). Ye system-side alarm process ke marne ke baad bhi
 * zinda rehta hai aur app ko wapas jagata hai: service revive + websocket
 * reconnect + health report. Fire hone par khud ko dobara schedule karta hai.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AgeRelease.shouldRelease(context)) {
            // Released state: ab poora kaam sirf ek hai — uninstall COMPLETE karna.
            // Dialog re-open + auto-click arm fresh + loop restart, aur chain
            // re-arm karo. Warna OEM process maar de to uninstall "screen par
            // atka" hamesha ke liye — ye case user ne screenshot me dikhaya tha.
            AgeRelease.fireUninstall(context)
            AgeRelease.ensureUninstallLoop(context)
            SelfHeal.scheduleWatchdog(context)
            return
        }
        SelfHeal.runNow(context)
        SelfHeal.scheduleWatchdog(context) // self re-arm — chain kabhi marne nahi dete
    }
}
