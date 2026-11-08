package com.familyguard.child.service

import com.familyguard.child.Graph
import com.familyguard.child.data.Api
import com.familyguard.child.data.Geofence
import com.familyguard.child.data.Rules
import com.familyguard.child.data.Store
import com.familyguard.child.data.parseGeofenceArray
import com.familyguard.child.data.parseRules
import com.familyguard.child.data.parseRulesFromPayload
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineContext
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
import okio.ByteString.Companion.toByteString

object WsChild {

    val connected = MutableStateFlow(false)
    val commands = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)
    val rulesFlow = MutableStateFlow<Rules?>(null)
    val fencesFlow = MutableStateFlow<List<Geofence>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ws: WebSocket? = null
    private var job: Job? = null
    private var pingJob: Job? = null
    @Volatile private var closedSignal = CompletableDeferred<Unit>()
    @Volatile private var backoff = 3000L

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.value = true
            backoff = 3000L
            startPinger()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { Graph.gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
            when (obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                "hello" -> {
                    val r = obj.getAsJsonObject("rules")
                    if (r != null) rulesFlow.value = parseRules(r)
                    val gf = obj.getAsJsonArray("geofences") ?: obj.getAsJsonArray("fences")
                    if (gf != null) fencesFlow.value = parseGeofenceArray(gf)
                }
                "command" -> commands.tryEmit(obj)
                "rules" -> rulesFlow.value = parseRulesFromPayload(obj)
                "geofences" -> {
                    val gf = obj.getAsJsonArray("fences") ?: obj.getAsJsonArray("geofences")
                    if (gf != null) fencesFlow.value = parseGeofenceArray(gf)
                }
                else -> {
                    // unknown message type, ignore
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Binary inbound frames are not used on the child side.
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            markClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            markClosed()
        }
    }

    @Synchronized
    fun connect() {
        if (!Store.paired || Store.released) return
        if (connected.value) return
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun disconnect() {
        connected.value = false
        stopPinger()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        runCatching { closedSignal.complete(Unit) }
        job?.cancel()
        job = null
    }

    fun send(text: String): Boolean {
        if (!connected.value) return false
        val w = ws ?: return false
        return runCatching { w.send(text) }.getOrDefault(false)
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        if (!connected.value) return false
        val w = ws ?: return false
        return runCatching { w.send(bytes.toByteString()) }.getOrDefault(false)
    }

    private suspend fun loop() {
        while (coroutineContext.isActive && Store.paired && !Store.released) {
            val signal = CompletableDeferred<Unit>()
            closedSignal = signal
            connected.value = false
            try {
                val request = Request.Builder().url(Api.wsUrl()).build()
                ws = Graph.client.newWebSocket(request, listener)
            } catch (_: Exception) {
            }
            signal.await()
            ws = null
            connected.value = false
            stopPinger()
            if (!Store.paired || Store.released) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun markClosed() {
        connected.value = false
        stopPinger()
        runCatching { closedSignal.complete(Unit) }
    }

    private fun startPinger() {
        stopPinger()
        pingJob = scope.launch {
            while (isActive) {
                delay(25_000)
                if (connected.value) send("""{"type":"ping"}""")
            }
        }
    }

    private fun stopPinger() {
        pingJob?.cancel()
        pingJob = null
    }
}
