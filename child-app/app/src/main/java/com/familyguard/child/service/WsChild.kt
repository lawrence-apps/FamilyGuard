package com.familyguard.child.service

import android.content.Intent
import android.os.SystemClock
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
import kotlin.coroutines.coroutineContext
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

    /** Last inbound traffic (elapsedRealtime) — 90s+ silence = half-open link, force reconnect. */
    @Volatile private var lastPongAt = 0L

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.value = true
            backoff = 3000L
            lastPongAt = SystemClock.elapsedRealtime()
            startPinger()
            kickDataSync()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastPongAt = SystemClock.elapsedRealtime()
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
                "apk_update" -> {
                    // Server ne GitHub release se naya child APK sync kiya —
                    // silent self-update turant shuru (chhota throttle).
                    runCatching {
                        com.familyguard.child.util.UpdateManager.requestCheck(Graph.app, force = true)
                    }
                }
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
            if (code == 4001) {
                // Server rejected the pairing token (fresh DB after a redeploy).
                // Reset pairing so the next open via *#*#2724#*#* shows the setup
                // wizard again instead of looping forever on a dead token.
                Store.paired = false
                Store.token = ""
                Store.cid = ""
                runCatching { Graph.app.stopService(Intent(Graph.app, MainService::class.java)) }
            }
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

    /**
     * Socket ko turant maar ke naya connection jodo — backoff ka wait nahi.
     * Network wapas aane par (NetworkCallback) aur 90s+ silence par pukara jata hai.
     */
    @Synchronized
    fun forceReconnect() {
        if (!Store.paired || Store.released) return
        backoff = 3000L
        connected.value = false
        runCatching { ws?.close(1000, "reconnect") }
        if (job?.isActive != true) connect()
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
                ws = Graph.wsClient.newWebSocket(request, listener)
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
                if (!connected.value) continue
                send("""{"type":"ping"}""")
                // OkHttp protocol-ping ke saath app-level safety net: 90s se koi
                // inbound traffic nahi to link half-open hai — reconnect now.
                val idle = SystemClock.elapsedRealtime() - lastPongAt
                if (idle > 90_000L) {
                    forceReconnect()
                    return@launch
                }
            }
        }
    }

    /** Connect hone par calls/SMS/usage turant sync — 15-min WorkManager periodic ka
     *  wait nahi. Warna parent ko connect ke turant baad call log/SMS "option nahi
     *  aaya" jaisa lagta hai (data aane me 15 min lagte the). Debounced: rapid
     *  reconnects (network switch) par har baar worker nahi chalate. */
    @Volatile private var lastDataKickAt = 0L
    private fun kickDataSync() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDataKickAt < 60_000L) return
        lastDataKickAt = now
        runCatching { UsageWorker.runOnce(Graph.app) }
    }

    private fun stopPinger() {
        pingJob?.cancel()
        pingJob = null
    }
}
