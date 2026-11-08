package com.familyguard.parent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.parent.data.Api
import com.familyguard.parent.data.Child
import com.familyguard.parent.data.ChildApp
import com.familyguard.parent.data.CommandResult
import com.familyguard.parent.data.EventItem
import com.familyguard.parent.data.Geofence
import com.familyguard.parent.data.NotifItem
import com.familyguard.parent.data.PointsResp
import com.familyguard.parent.data.Rules
import com.familyguard.parent.data.StatusUi
import com.familyguard.parent.data.Store
import com.familyguard.parent.data.UsageResp
import com.familyguard.parent.data.WsEvent
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.IOException

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val children = MutableStateFlow<List<Child>>(emptyList())
    val activeChildId = MutableStateFlow(Store.activeChildId)
    val status = MutableStateFlow<Map<String, StatusUi>>(emptyMap())
    val events = MutableStateFlow<List<EventItem>>(emptyList())
    val notifs = MutableStateFlow<List<NotifItem>>(emptyList())
    val mirrorFrame = MutableStateFlow<ByteArray?>(null)
    val mirrorRunning = MutableStateFlow(false)
    val apps = MutableStateFlow<List<ChildApp>>(emptyList())
    val lastResult = MutableStateFlow("")
    val previewBytes = MutableStateFlow<ByteArray?>(null)

    private var eventsJob: Job? = null

    init {
        if (Store.token.isNotEmpty()) startSession()
    }

    /** Connects the WebSocket, starts the event collector and loads children. Safe to call again. */
    fun startSession() {
        if (Store.token.isEmpty()) return
        if (eventsJob?.isActive != true) {
            eventsJob = viewModelScope.launch {
                Graph.ws.events.collect { onWs(it) }
            }
        }
        Graph.ws.connect()
        refreshChildren()
    }

    fun activeChild(): Child? =
        children.value.firstOrNull { it.id == activeChildId.value } ?: children.value.firstOrNull()

    private fun onWs(ev: WsEvent) {
        when (ev) {
            is WsEvent.ChildStatus -> {
                status.value = status.value + (ev.cid to StatusUi(
                    online = ev.online,
                    battery = ev.battery,
                    charging = ev.charging,
                    currentApp = ev.currentApp
                ))
                children.value = children.value.map {
                    if (it.id == ev.cid) {
                        it.copy(online = ev.online, battery = ev.battery, currentApp = ev.currentApp)
                    } else {
                        it
                    }
                }
            }
            is WsEvent.Event -> events.value = (listOf(ev.event) + events.value).take(300)
            is WsEvent.Notif -> notifs.value = (listOf(ev.notif) + notifs.value).take(300)
            is WsEvent.CmdResult -> onCommandResult(ev.result)
            is WsEvent.Apps -> apps.value = ev.apps
            is WsEvent.Pairing -> refreshChildren()
            is WsEvent.MirrorFrame -> mirrorFrame.value = ev.bytes
            is WsEvent.Location -> Unit // consumed live by LocationScreen
            WsEvent.Pong -> Unit
        }
    }

    private fun onCommandResult(r: CommandResult) {
        lastResult.value = if (r.ok) {
            "OK" + (r.data?.let { " — $it" } ?: "")
        } else {
            "Command failed (id ${r.id})"
        }
        val url = r.data?.get("url")
        if (r.ok && url != null && url.isJsonPrimitive) {
            fetchPreview(Api.mediaUrl(url.asString))
        }
    }

    private fun fetchPreview(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                Graph.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.bytes() ?: ByteArray(0)
                }
            }.onSuccess { bytes ->
                if (bytes.isNotEmpty()) previewBytes.value = bytes
            }
        }
    }

    fun refreshChildren() {
        viewModelScope.launch {
            runCatching { Api.children() }.onSuccess { resp ->
                children.value = resp.children
                val current = activeChildId.value
                if (resp.children.none { it.id == current }) {
                    val first = resp.children.firstOrNull()
                    activeChildId.value = first?.id ?: ""
                    Store.activeChildId = first?.id ?: ""
                }
                seedStatus(resp.children)
            }
        }
    }

    private fun seedStatus(list: List<Child>) {
        val map = status.value.toMutableMap()
        list.forEach { c ->
            if (!map.containsKey(c.id)) {
                map[c.id] = StatusUi(
                    online = c.online,
                    battery = c.battery,
                    charging = false,
                    currentApp = c.currentApp
                )
            }
        }
        status.value = map
    }

    fun selectChild(id: String) {
        activeChildId.value = id
        Store.activeChildId = id
        refreshChildren()
    }

    fun createChild(name: String, dob: String, onResult: (Child?) -> Unit) {
        viewModelScope.launch {
            val child = runCatching { Api.createChild(name, dob) }.getOrNull()
            if (child != null) {
                activeChildId.value = child.id
                Store.activeChildId = child.id
                refreshChildren()
            }
            onResult(child)
        }
    }

    fun regenerate(cid: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val code = runCatching { Api.regenerateCode(cid) }.getOrNull()
            if (code != null) refreshChildren()
            onResult(code)
        }
    }

    fun sendCommand(action: String, params: JsonObject = JsonObject()) {
        val child = activeChild()
        if (child == null) {
            lastResult.value = "No child paired"
            return
        }
        viewModelScope.launch {
            runCatching { Api.command(child.id, action, params) }
                .onSuccess { lastResult.value = "Sent: $action" }
                .onFailure { lastResult.value = "Error: ${it.message ?: "command failed"}" }
        }
    }

    fun startMirror() {
        mirrorRunning.value = true
        val params = JsonObject().apply {
            addProperty("fps", 8)
            addProperty("quality", 60)
            addProperty("maxWidth", 720)
        }
        sendCommand("mirror_start", params)
        activeChild()?.id?.let { Graph.ws.mirrorStart(it) }
    }

    fun stopMirror() {
        mirrorRunning.value = false
        sendCommand("mirror_stop")
        activeChild()?.id?.let { Graph.ws.mirrorStop(it) }
    }

    fun loadEvents(cid: String) {
        viewModelScope.launch {
            runCatching { Api.events(cid) }.onSuccess { resp ->
                events.value = resp.events.sortedByDescending { it.t }
            }
        }
    }

    fun loadNotifs(cid: String) {
        viewModelScope.launch {
            runCatching { Api.notifications(cid) }.onSuccess { resp ->
                notifs.value = resp.notifications.sortedByDescending { it.date }
            }
        }
    }

    suspend fun usage(cid: String, date: String? = null): UsageResp? =
        runCatching { Api.usage(cid, date) }.getOrNull()

    suspend fun rules(cid: String): Rules? =
        runCatching { Api.getRules(cid) }.getOrNull()

    suspend fun putRules(cid: String, r: Rules): Boolean =
        runCatching { Api.putRules(cid, r) }.isSuccess

    suspend fun locations(cid: String, limit: Int = 300): PointsResp? =
        runCatching { Api.locations(cid, limit) }.getOrNull()

    suspend fun fences(cid: String): List<Geofence> =
        runCatching { Api.fences(cid).fences }.getOrDefault(emptyList())

    suspend fun addFence(cid: String, f: Geofence): Geofence? =
        runCatching { Api.addFence(cid, f) }.getOrNull()

    suspend fun deleteFence(cid: String, fid: String): Boolean =
        runCatching { Api.deleteFence(cid, fid) }.isSuccess
}
