package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.familyguard.child.data.Store

/**
 * PackageInstaller session status callback (silent self-update).
 *
 * STATUS_PENDING_USER_ACTION = system ka install confirm dialog abhi bhi
 * chahiye. Android ka rule: `canRequestPackageInstalls()` sirf "Install
 * unknown apps" wali Settings screen hataata hai — CONFIRM dialog
 * ("Do you want to install this update?") phir bhi aata hai, aur uska
 * intent EXTRA_INTENT me milta hai jise APP KO KHUD start karna padta hai.
 *
 * PURANA BUG: ye receiver PENDING_USER_ACTION ko ignore karta tha ("kabhi
 * nahi aayega" galat assumption) — dialog kabhi khulta hi nahi, a11y ka
 * "install" click step khali screen dhoondta rehta tha, session timeout ho
 * jata tha — auto-update HAR VERSION par fail. Ab: intent start karte hain,
 * dialog khulta hai aur EnforcementService ka armed "install" step 2–5 sec
 * me Install button click kar deta hai (bachche ko kuch nahi karna).
 *
 * Success par GitHub asset stamp save hota hai — agli check par "up-to-date"
 * lagega. Process update ke saath mar jata hai; BootReceiver ka
 * MY_PACKAGE_REPLACED path MainService wapas chala deta hai.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                intent.getStringExtra("stamp")?.let { Store.apkAssetUpdatedAt = it }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System ka install-confirm intent start karo — YEHI wo dialog
                // hai jo pehle kabhi khulta hi nahi tha (silent update ka
                // hidden dependency). A11y "install" step pehle se armed hai
                // (commit se pehle), dialog khulte hi Install click ho jayega.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                runCatching {
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (confirm != null) context.startActivity(confirm)
                }
            }
            else -> {
                // Failure par kuch mat karo — agli check par update khud retry
                // ho jayega (version-aware skip me safe hai).
            }
        }
    }
}
