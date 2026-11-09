package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.familyguard.child.data.Store

/**
 * PackageInstaller session status callback (silent self-update).
 * Success par GitHub asset stamp save hota hai — agli check par "up-to-date"
 * lagega aur download/install loop dobara nahi chalega. Process update ke
 * saath mar jata hai; BootReceiver ka MY_PACKAGE_REPLACED path MainService
 * wapas chala deta hai.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            intent.getStringExtra("stamp")?.let { Store.apkAssetUpdatedAt = it }
        }
        // STATUS_PENDING_USER_ACTION kabhi nahi aana chahiye (session humne commit
        // kiya aur dialog accessibility se click hota hai); failure par kuch mat
        // karo — agli check par update khud retry ho jayega.
    }
}
