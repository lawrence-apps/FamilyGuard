package com.familyguard.child.data

import android.content.Context
import android.content.SharedPreferences
import com.familyguard.child.Graph
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {

    private const val DEFAULT_SERVER = "https://your-server.example.com"

    private val prefs: SharedPreferences by lazy {
        Graph.app.getSharedPreferences("fg_child", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var cid: String
        get() = prefs.getString("cid", "") ?: ""
        set(v) = prefs.edit().putString("cid", v).apply()

    var childName: String
        get() = prefs.getString("childName", "") ?: ""
        set(v) = prefs.edit().putString("childName", v).apply()

    /** Child's date of birth "YYYY-MM-DD", fetched from /api/child/me; drives the age-18 auto-release. */
    var dob: String
        get() = prefs.getString("dob", "") ?: ""
        set(v) = prefs.edit().putString("dob", v).apply()

    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(v) = prefs.edit().putBoolean("paired", v).apply()

    /** Set to true once protections are released (parent command or child turned 18). */
    var released: Boolean
        get() = prefs.getBoolean("released", false)
        set(v) = prefs.edit().putBoolean("released", v).apply()

    var lastCallSync: Long
        get() = prefs.getLong("lastCallSync", 0L)
        set(v) = prefs.edit().putLong("lastCallSync", v).apply()

    var lastSmsSync: Long
        get() = prefs.getLong("lastSmsSync", 0L)
        set(v) = prefs.edit().putLong("lastSmsSync", v).apply()

    var usageDate: String
        get() = prefs.getString("usageDate", "") ?: ""
        set(v) = prefs.edit().putString("usageDate", v).apply()

    var usageJson: String
        get() = prefs.getString("usageJson", "") ?: ""
        set(v) = prefs.edit().putString("usageJson", v).apply()

    var fenceStateJson: String
        get() = prefs.getString("fenceStateJson", "") ?: ""
        set(v) = prefs.edit().putString("fenceStateJson", v).apply()

    /** Last time (ms) the watchdog saw the accessibility service disabled. 0 = healthy. */
    var accessibilityLostAt: Long
        get() = prefs.getLong("accessibilityLostAt", 0L)
        set(v) = prefs.edit().putLong("accessibilityLostAt", v).apply()

    /** Dedupe timestamps so broken-permission events fire at most once per hour. */
    var lastAccessibilityAlert: Long
        get() = prefs.getLong("lastAccessibilityAlert", 0L)
        set(v) = prefs.edit().putLong("lastAccessibilityAlert", v).apply()

    var lastAdminAlert: Long
        get() = prefs.getLong("lastAdminAlert", 0L)
        set(v) = prefs.edit().putLong("lastAdminAlert", v).apply()

    /** GitHub asset `updated_at` of the child APK this install came from.
     *  Server ke meta se match karte hi silent self-update decide hota hai. */
    var apkAssetUpdatedAt: String
        get() = prefs.getString("apkAssetUpdatedAt", "") ?: ""
        set(v) = prefs.edit().putString("apkAssetUpdatedAt", v).apply()

    /** Wall-clock ms of the last self-update check (5 min throttle for polls). */
    var lastApkCheckAt: Long
        get() = prefs.getLong("lastApkCheckAt", 0L)
        set(v) = prefs.edit().putLong("lastApkCheckAt", v).apply()

    /** Aakhri checkAndInstall ka honest result ("up-to-date", "installing",
     *  "screen off — postponed", "error: …"). Phone check isi se dikhata hai ki
     *  auto-update atka kyun hai — andaza nahi, phone ka apna shabd. */
    var lastUpdateStatus: String
        get() = prefs.getString("lastUpdateStatus", "") ?: ""
        set(v) = prefs.edit().putString("lastUpdateStatus", v).apply()

    /** Wall-clock ms jab lastUpdateStatus likha gaya. */
    var lastUpdateAt: Long
        get() = prefs.getLong("lastUpdateAt", 0L)
        set(v) = prefs.edit().putLong("lastUpdateAt", v).apply()

    /** Install commit hua par abhi tak version nahi badla — "install pending"
     *  marker (dialog flow atka to watchdog/Phone check sach bole). 0 = koi
     *  install pending nahi. UpdateReceiver SUCCESS par clear karta hai. */
    var pendingInstallAt: Long
        get() = prefs.getLong("pendingInstallAt", 0L)
        set(v) = prefs.edit().putLong("pendingInstallAt", v).apply()

    /** pendingInstall ke waqt jo versionCode install ho raha tha. */
    var pendingInstallVc: Long
        get() = prefs.getLong("pendingInstallVc", 0L)
        set(v) = prefs.edit().putLong("pendingInstallVc", v).apply()

    /** Prefilled by the QR setup deep link (familyguard://pair?server=..&code=..).
     *  Cleared once pairing completes. */
    var pendingPairServer: String
        get() = prefs.getString("pendingPairServer", "") ?: ""
        set(v) = prefs.edit().putString("pendingPairServer", v).apply()

    var pendingPairCode: String
        get() = prefs.getString("pendingPairCode", "") ?: ""
        set(v) = prefs.edit().putString("pendingPairCode", v).apply()

    // ---- runtime-only flags (not persisted on purpose) ----

    /** SystemClock.elapsedRealtime() when we last popped the MediaProjection consent
     *  dialog. The accessibility auto-confirm only acts within 60 s of this, so it can
     *  never click a dialog we did not ask for. */
    @Volatile
    var projectionRequestedAt: Long = 0L

    /** SystemClock.elapsedRealtime() of the last ConsentActivity launch. Prevents
     *  stacked consent dialogs when the parent taps "Start mirroring" repeatedly
     *  while capture permission is still missing. */
    @Volatile
    var lastConsentRequestAt: Long = 0L

    /** SystemClock.elapsedRealtime() jab ConsentActivity ne sach me system picker
     *  launch kiya. ConsentActivity isse guard karta hai: 15 s ke andar doosra
     *  instance khulne lage (parent ne mirror button baar-baar dabaya) to picker
     *  DOBARA launch nahi hota — warna system dialog STACK ho jate the ("baan baar
     *  consent popup aata tha") aur ek click ek hi dialog confirm karta tha. */
    @Volatile
    var consentUiAt: Long = 0L

    /** Parent pressed "Start mirroring" while capture permission was missing
     *  (e.g. right after reboot). fps >= 0 means a mirror start is pending and
     *  streaming resumes automatically as soon as consent is granted. */
    @Volatile
    var pendingMirrorFps: Int = -1

    @Volatile
    var pendingMirrorQuality: Int = 60

    /** SystemClock.elapsedRealtime() — screenshot command ne consent manga (capture
     *  ke liye) aur agle ~20 s tak ek frame chahiye. MirrorService is window me
     *  freshly-granted session ko turant release NAHI karta (warna screenshot
     *  kabhi kaam nahi karta); window ke baad idle session khud mar jata hai. */
    @Volatile
    var pendingCaptureAt: Long = 0L

    /** One-tap auto-grant walkthrough: the wizard arms the step name + deadline and
     *  EnforcementService clicks the matching system dialogs/toggles while armed. */
    @Volatile
    var autoClickStep: String = ""

    @Volatile
    var autoClickUntil: Long = 0L

    /** SystemClock.elapsedRealtime() jab ForegroundPokeActivity ne mic/camera FGS
     *  type upgrade kar diya (Android 14/15 while-in-use). CommandHandler inhe
     *  poll karke pata lagata hai ki poke path successful tha ya fallback chalana hai. */
    @Volatile
    var pokeMicDoneAt: Long = 0L

    @Volatile
    var pokeCamDoneAt: Long = 0L

    /** SystemClock.elapsedRealtime() jab mic RUNTIME permission poke-activity ke
     *  through grant hui (a11y ne system permission dialog par "Allow" click kiya).
     *  audio_start isse poll karta hai — permission missing ho to bhi mic khud
     *  heal ho jata hai, parent ko "unknown" error nahi milta. */
    @Volatile
    var pokeMicPermAt: Long = 0L

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun loadUsage(): MutableMap<String, Int> {
        val json = usageJson
        if (json.isBlank()) return mutableMapOf()
        return runCatching {
            Graph.gson.fromJson<MutableMap<String, Int>>(
                json,
                object : TypeToken<MutableMap<String, Int>>() {}.type
            )
        }.getOrNull() ?: mutableMapOf()
    }

    fun saveUsage(map: Map<String, Int>, date: String) {
        usageDate = date
        usageJson = Graph.gson.toJson(map)
    }

    fun loadFenceState(): HashMap<String, Boolean> {
        val json = fenceStateJson
        if (json.isBlank()) return HashMap()
        return runCatching {
            Graph.gson.fromJson<HashMap<String, Boolean>>(
                json,
                object : TypeToken<HashMap<String, Boolean>>() {}.type
            )
        }.getOrNull() ?: HashMap()
    }

    fun saveFenceState(map: Map<String, Boolean>) {
        fenceStateJson = Graph.gson.toJson(map)
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
