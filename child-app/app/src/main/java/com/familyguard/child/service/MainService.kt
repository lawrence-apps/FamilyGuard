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
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.familyguard.child.R
import com.familyguard.child.RulesEngine
import com.familyguard.child.data.Api
import com.familyguard.child.util.AgeRelease
import com.familyguard.child.util.CommandHandler
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        val notif = buildNotif("Device Health active")
        runCatching {
            ServiceCompat.startForeground(this, 1, notif, types)
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

    override fun onDestroy() {
        WsChild.disconnect()
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
            runCatching {
                val m = JsonObject()
                m.addProperty("type", "status")
                m.addProperty("battery", batteryLevel())
                m.addProperty("charging", isCharging())
                m.addProperty("currentApp", EnforcementService.lastApp)
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
            nm.createNotificationChannel(NotificationChannel("fg_service", "FamilyGuard", NotificationManager.IMPORTANCE_MIN))
        }
    }

    private fun buildNotif(title: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, "fg_service")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText("Managed by FamilyGuard")
            .setOngoing(true)
            .build()
    }
}
