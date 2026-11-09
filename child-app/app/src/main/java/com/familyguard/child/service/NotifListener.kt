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
        handle(sbn, forward = true)
    }

    private fun handle(sbn: StatusBarNotification?, forward: Boolean) {
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

        // SAMSUNG "Active apps" CARD — FGS chalne par Samsung Device Care khud
        // post karta hai ("Device Health — 8 mins"). Ye HAMARA notification nahi,
        // system ka card hai — shade me hamesha dikhta rahta hai. Listener se pakad
        // kar CANCEL kar dete hain (Samsung har thodi der me repost karta hai, hum
        // har repost par phir cancel kar denge) aur parents ko forward bhi nahi karte.
        if (pkg in SAMSUNG_DEVICE_CARE) {
            val label = runCatching { getString(applicationInfo.labelRes) }.getOrDefault("Device Health")
            val tl = title.lowercase()
            if (tl.contains("active") || tl.contains("सक्रिय") || title.contains(label) || text.contains(label)) {
                runCatching { cancelNotifications(arrayOf(sb.key)) }
                return
            }
        }

        val obj = JsonObject()
        if (!forward) return // sirf saaf-safai sweep — purane notifications dubara parents ko na jayen
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
        // Pehle se padi hui Active apps cards bhi saaf karo (listener reconnect par
        // Samsung ka purana card shade me rah jata hai) — forward=false: sirf cancel.
        runCatching {
            for (s in activeNotifications ?: emptyArray<StatusBarNotification>()) handle(s, forward = false)
        }
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

    companion object {
        /** Samsung Device Care family — "Active apps" notification inhi ke naam se aati hai. */
        private val SAMSUNG_DEVICE_CARE = setOf(
            "com.samsung.android.lool",     // Device Care (One UI 3+)
            "com.samsung.android.sm.core"   // purana Smart Manager
        )
    }
}
