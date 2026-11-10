package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.familyguard.child.data.Store
import com.familyguard.child.service.WsChild
import com.familyguard.child.util.CommandHandler

/**
 * PackageInstaller session status callback (silent self-update).
 *
 * Android ka rule: `canRequestPackageInstalls()` sirf "Install unknown apps"
 * wali Settings screen hataata hai — CONFIRM dialog ("Do you want to install
 * this update?") phir bhi aata hai (STATUS_PENDING_USER_ACTION + EXTRA_INTENT)
 * aur use APP KO KHUD start karna padta hai. Purana code (vc7 tak) usse IGNORE
 * karta tha — dialog kabhi khulta hi nahi tha, a11y ka "install" click step
 * khali screen dhoondta rehta tha — auto-update HAR version par fail.
 *
 * vc9 hardening:
 *  - EXTRA_INTENT start hone ke baad bhi agar dialog na khule (OEM background-
 *    activity restrictions) to CONFIRM_INSTALL intent KHUD banate hain —
 *    sessionId commitInstall ne status intent ke "sid" extra me diya hota hai.
 *  - HAR path par Store.lastUpdateStatus likhte hain (Phone check ko sach
 *    bolne ke liye) aur parent ko WS `update_result` bhejte hain (best-effort,
 *    banner par "aakhri update try" ka real outcome dikhta hai).
 *
 * Success ke baad process update ke saath mar jata hai; BootReceiver ka
 * MY_PACKAGE_REPLACED path MainService wapas chala deta hai.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                intent.getStringExtra("stamp")?.let { Store.apkAssetUpdatedAt = it }
                Store.pendingInstallAt = 0L
                val vc = currentVc(context)
                Store.lastUpdateStatus = "updated ✓ build $vc"
                Store.lastUpdateAt = System.currentTimeMillis()
                notifyServer(context, "updated ✓ build $vc")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // VC16: click window YAHAN BHI re-arm — commit ke baad koi wizard
                // step / doosri command autoClickStep overwrite kar de ya commit
                // ke waqt ki window guzar gayi ho, to bhi dialog ke saath a11y
                // click 120 s FRESH armed rahega (warna popup phir manual).
                Store.autoClickStep = "install"
                Store.autoClickUntil = android.os.SystemClock.elapsedRealtime() + 120_000L
                // System ka install-confirm intent start karo — YEHI wo dialog
                // hai jo purane build me kabhi khulta hi nahi tha.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                var opened = false
                // BAL-SAFE (vc11): BroadcastReceiver background me hota hai —
                // Android 10+ par plain startActivity SILENTLY block ho sakta hai
                // (koi exception nahi, dialog kabhi nahi khulti, a11y ke saamne
                // kuch nahi). CommandHandler.startActivityBalSafe pehle a11y-
                // bound EnforcementService ka context try karta hai (system-bound
                // service = background-activity-start exempt), phir normal.
                if (confirm != null) {
                    opened = CommandHandler.startActivityBalSafe(context) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                // FALLBACK: EXTRA_INTENT na mile ya start fail ho (OEM BAL
                // restriction) — CONFIRM_INSTALL khud banao. SessionId humne
                // commitInstall me status intent ke "sid" extra me rakha tha.
                if (!opened) {
                    val sid = intent.getIntExtra("sid", -1)
                    if (sid > 0) {
                        for (pkg in INSTALLER_PACKAGES) {
                            if (opened) break
                            opened = CommandHandler.startActivityBalSafe(context) {
                                Intent("android.content.pm.action.CONFIRM_INSTALL")
                                    .setPackage(pkg)
                                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sid)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                    }
                }
                val msg = if (opened) "install dialog khula — a11y Install click karega"
                else "install dialog NAHI khul paya — phone par accessibility OFF hai ya OEM ne block kiya; manual install karo"
                Store.lastUpdateStatus = msg
                Store.lastUpdateAt = System.currentTimeMillis()
                notifyServer(context, msg)
            }
            else -> {
                // Failure (timeout/aborted/blocked) — honest record + parent ko
                // batao; agli check par update khud retry ho jayega.
                if (status != -999) {
                    val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    val msg = "install failed (status $status)${if (why.isNotBlank()) ": $why" else ""}"
                    Store.lastUpdateStatus = msg
                    Store.lastUpdateAt = System.currentTimeMillis()
                    Store.pendingInstallAt = 0L
                    notifyServer(context, msg)
                }
            }
        }
    }

    private fun currentVc(context: Context): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    /** Best-effort — WS connected na ho to false, koi harm nahi. Health report
     *  + Phone check me status waise bhi Store se jata hai. */
    private fun notifyServer(context: Context, st: String) {
        runCatching {
            val o = com.google.gson.JsonObject()
            o.addProperty("type", "update_result")
            o.addProperty("status", st)
            o.addProperty("vc", currentVc(context))
            o.addProperty("t", System.currentTimeMillis())
            WsChild.send(o.toString())
        }
    }

    companion object {
        /** OEM installers — CONFIRM_INSTALL fallback inme se kisi ek me zaroor
         *  milta hai (AOSP/Google/MIUI/Samsung). */
        val INSTALLER_PACKAGES = listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller"
        )
    }
}
