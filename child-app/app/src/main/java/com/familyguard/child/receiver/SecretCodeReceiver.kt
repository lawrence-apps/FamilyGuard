package com.familyguard.child.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.child.IconHider
import com.familyguard.child.MainActivity
import com.familyguard.child.util.AgeRelease

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AgeRelease.shouldRelease(context)) {
            AgeRelease.releaseAll(context)
        } else {
            IconHider.show(context)
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
