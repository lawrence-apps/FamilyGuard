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
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show even over the lock screen so a reboot + remote mirror request never
        // needs the child to unlock the phone.
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        }
        if (!Store.paired || Store.released || MirrorManager.hasProjection) {
            finish()
            return
        }
        // Arm the accessibility auto-confirm: EnforcementService will click
        // "Start now" on the system dialog within this window.
        val now = SystemClock.elapsedRealtime()
        Store.lastConsentRequestAt = now
        Store.projectionRequestedAt = now
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        runCatching { projLauncher.launch(mgr.createScreenCaptureIntent()) }.onFailure { finish() }
    }
}
