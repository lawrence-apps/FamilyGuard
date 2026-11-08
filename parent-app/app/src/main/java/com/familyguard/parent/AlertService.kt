package com.familyguard.parent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.familyguard.parent.data.Store
import com.familyguard.parent.data.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var notifId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notif = statusNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIF_ID, notif)
        }
        if (Store.token.isEmpty()) {
            stopSelf()
            return
        }

        Graph.ws.connect()

        scope.launch {
            Graph.ws.events.collect { handle(it) }
        }
    }

    private fun handle(ev: WsEvent) {
        when (ev) {
            is WsEvent.Notif -> {
                val title = ev.notif.title.ifBlank { "Notification" }
                val text = ev.notif.text
                post("Child notification: $title — $text")
            }
            is WsEvent.Event -> post("FamilyGuard alert: ${prettify(ev.event.type)}")
            is WsEvent.ChildStatus -> {
                if (!ev.online) post("Child device went offline")
            }
            else -> Unit
        }
    }

    private fun prettify(type: String): String =
        type.split("_").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "New event" }

    private fun post(text: String) {
        if (!notificationsAllowed()) return
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("FamilyGuard")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(notifId++, n) }
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun statusNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("FamilyGuard connected")
            .setContentText("Listening for alerts from your child devices")
            .setOngoing(true)
            .build()

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "FamilyGuard Status", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "FamilyGuard Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Graph.ws.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_STATUS = "fg_status"
        const val CHANNEL_ALERTS = "alerts"
        const val FOREGROUND_NOTIF_ID = 1
    }
}
