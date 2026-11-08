package com.familyguard.child.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.familyguard.child.data.Api
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NotifListener : NotificationListenerService() {

    private val queue = ArrayDeque<JsonObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sb = sbn ?: return
        val pkg = sb.packageName ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val n = sb.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        if (title.isBlank() && text.isBlank()) return

        val obj = JsonObject()
        obj.addProperty("type", "notification")
        obj.addProperty("pkg", pkg)
        obj.addProperty("title", title)
        obj.addProperty("text", text)
        obj.addProperty("date", System.currentTimeMillis())

        if (WsChild.connected.value) {
            WsChild.send(obj.toString())
        } else {
            synchronized(queue) {
                queue.addLast(obj)
                if (queue.size > 100) queue.removeFirst()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch {
            while (isActive) {
                delay(30_000)
                val batch: JsonArray? = synchronized(queue) {
                    if (queue.isEmpty()) {
                        null
                    } else {
                        val arr = JsonArray()
                        while (queue.isNotEmpty()) arr.add(queue.removeFirst())
                        arr
                    }
                }
                if (batch != null && batch.size() > 0) {
                    runCatching { Api.postNotifications(batch) }
                }
            }
        }
    }
}
