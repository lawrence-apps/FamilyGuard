package com.familyguard.parent.data

import com.familyguard.parent.Graph
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder

sealed class WsEvent {
    data class ChildStatus(
        val cid: String,
        val online: Boolean,
        val battery: Int,
        val charging: Boolean,
        val currentApp: String
    ) : WsEvent()

    data class Location(val cid: String, val point: LocPoint) : WsEvent()
    data class Notif(val cid: String, val notif: NotifItem) : WsEvent()
    data class Event(val cid: String, val event: EventItem) : WsEvent()
    data class CommandResult(val cid: String, val result: CommandResult) : WsEvent()
    data class Apps(val cid: String, val apps: List<ChildApp>) : WsEvent()
    data class Pairing(val cid: String) : WsEvent()
    class MirrorFrame(val bytes: ByteArray) : WsEvent()
    object Pong : WsEvent()
}

class WsManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    @Volatile
    private var wantConnected = false
    private var backoffMs = BACKOFF_START_MS

    val connected = MutableStateFlow(false)
    val events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)

    @Synchronized
    fun connect() {
        if (webSocket != null) return
        val token = Store.token
        if (token.isEmpty()) return
        wantConnected = true
        val request = buildRequest(token)
        if (request == null) {
            scheduleReconnect()
            return
        }
        webSocket = Graph.client.newWebSocket(request, listener)
    }

    private fun buildRequest(token: String): Request? {
        var base = Store.serverUrl.trim().trimEnd('/')
        base = when {
            base.startsWith("wss://") || base.startsWith("ws://") -> base
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.isNotEmpty() -> "wss://$base"
            else -> return null
        }
        return try {
            val encoded = URLEncoder.encode(token, "UTF-8")
            Request.Builder().url("$base/ws?token=$encoded").build()
        } catch (e: Exception) {
            null
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.value = true
            backoffMs = BACKOFF_START_MS
            startPingLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            events.tryEmit(WsEvent.MirrorFrame(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected.value = false
            this@WsManager.webSocket = null
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.value = false
            this@WsManager.webSocket = null
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun handleText(text: String) {
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            return
        }
        val type = obj.str("type")
        if (type.isEmpty()) return
        val cid = obj.str("cid")
        when (type) {
            "child_status" -> events.tryEmit(
                WsEvent.ChildStatus(
                    cid = cid,
                    online = obj.bool("online"),
                    battery = obj.int("battery"),
                    charging = obj.bool("charging"),
                    currentApp = obj.str("currentApp")
                )
            )
            "location" -> {
                val p = obj.optObj("point") ?: return
                val point = runCatching { Graph.gson.fromJson(p, LocPoint::class.java) }.getOrNull() ?: return
                events.tryEmit(WsEvent.Location(cid, point))
            }
            "notification" -> {
                val n = obj.optObj("notif") ?: obj
                val item = runCatching { Graph.gson.fromJson(n, NotifItem::class.java) }.getOrNull() ?: return
                events.tryEmit(WsEvent.Notif(cid, item))
            }
            "event" -> {
                val e = obj.optObj("event") ?: obj
                val item = runCatching { Graph.gson.fromJson(e, EventItem::class.java) }.getOrNull() ?: return
                events.tryEmit(WsEvent.Event(cid, item))
            }
            "command_result" -> events.tryEmit(
                WsEvent.CommandResult(
                    cid,
                    CommandResult(
                        id = obj.str("id"),
                        ok = obj.bool("ok"),
                        data = obj.optObj("data")
                    )
                )
            )
            "apps" -> {
                val arr = obj.get("apps")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
                val list = arr.mapNotNull { el ->
                    runCatching { Graph.gson.fromJson(el, ChildApp::class.java) }.getOrNull()
                }
                events.tryEmit(WsEvent.Apps(cid, list))
            }
            "pairing" -> events.tryEmit(WsEvent.Pairing(cid))
            "ping" -> events.tryEmit(WsEvent.Pong)
        }
    }

    private fun JsonObject.optObj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    private fun JsonObject.lng(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    fun send(text: String): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(text)
        } catch (e: Exception) {
            false
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                send("{\"type\":\"ping\"}")
                delay(PING_INTERVAL_MS)
            }
        }
    }

    fun mirrorStart(cid: String) {
        val o = JsonObject()
        o.addProperty("type", "mirror_start")
        o.addProperty("cid", cid)
        send(Graph.gson.toJson(o))
    }

    fun mirrorStop(cid: String) {
        val o = JsonObject()
        o.addProperty("type", "mirror_stop")
        o.addProperty("cid", cid)
        send(Graph.gson.toJson(o))
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            connect()
        }
    }

    @Synchronized
    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        connected.value = false
        try {
            webSocket?.close(1000, "client closed")
        } catch (e: Exception) {
            // socket already dead; nothing to do
        }
        webSocket = null
    }

    companion object {
        private const val BACKOFF_START_MS = 3_000L
        private const val BACKOFF_MAX_MS = 60_000L
        private const val PING_INTERVAL_MS = 25_000L
    }
}
