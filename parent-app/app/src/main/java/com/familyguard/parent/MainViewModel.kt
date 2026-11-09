package com.familyguard.parent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.parent.data.Api
import com.familyguard.parent.data.CallItem
import com.familyguard.parent.data.Child
import com.familyguard.parent.data.ChildApp
import com.familyguard.parent.data.CommandResult
import com.familyguard.parent.data.EventItem
import com.familyguard.parent.data.Geofence
import com.familyguard.parent.data.NotifItem
import com.familyguard.parent.data.PointsResp
import com.familyguard.parent.data.Rules
import com.familyguard.parent.data.SmsItem
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
    /** Call log sync child se (UsageWorker) — Reports screen par dikhta hai. */
    val calls = MutableStateFlow<List<CallItem>>(emptyList())
    val sms = MutableStateFlow<List<SmsItem>>(emptyList())
    val callsLoading = MutableStateFlow(false)
    val smsLoading = MutableStateFlow(false)
    val mirrorFrame = MutableStateFlow<ByteArray?>(null)
    /** Truth from the CHILD (mirror_state events), never an optimistic guess —
     *  a fake LIVE while consent was still pending made parents think mirroring
     *  was broken ("cast sign but no screen"). */
    val mirrorRunning = MutableStateFlow(false)
    /** last mirror frame arrival (System.currentTimeMillis()) for the staleness hint. */
    val mirrorLastFrameAt = MutableStateFlow(0L)
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
                    currentApp = ev.currentApp,
                    health = ev.health ?: status.value[ev.cid]?.health
                ))
                children.value = children.value.map {
                    if (it.id == ev.cid) {
                        it.copy(online = ev.online, battery = ev.battery, currentApp = ev.currentApp, health = ev.health ?: it.health)
                    } else {
                        it
                    }
                }
            }
            is WsEvent.Event -> events.value = (listOf(ev.event) + events.value).take(300)
            is WsEvent.Notif -> notifs.value = (listOf(ev.notif) + notifs.value).take(300)
            is WsEvent.CmdResult -> onCommandResult(ev.cid, ev.result)
            is WsEvent.Apps -> apps.value = ev.apps
            is WsEvent.Pairing -> refreshChildren()
            is WsEvent.MirrorState -> {
                if (ev.cid == activeChildId.value) mirrorRunning.value = ev.streaming
            }
            is WsEvent.MirrorFrame -> {
                mirrorLastFrameAt.value = System.currentTimeMillis()
                mirrorFrame.value = ev.bytes
            }
            is WsEvent.Location -> Unit // consumed live by LocationScreen
            WsEvent.Pong -> Unit
        }
    }

    private fun onCommandResult(cid: String, r: CommandResult) {
        lastResult.value = if (r.ok) {
            "OK" + (r.data?.let { " — $it" } ?: "")
        } else {
            "Command failed (id ${r.id})"
        }
        // Child confirmed actual streaming via the command result (status field set
        // by CommandHandler.mirror_start). This is the earliest truth we can get.
        if (r.ok && cid == activeChildId.value) {
            val st = r.data?.get("status")
            if (st?.isJsonPrimitive == true) {
                when (st.asString) {
                    "streaming" -> mirrorRunning.value = true
                    // Consent dialog popped & auto-confirm in progress: keep the UI
                    // honest ("starting…"), streaming flips true via mirror_state.
                    "consent_pending" -> mirrorRunning.value = false
                }
            }
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
        // ONE command path only. This used to fire BOTH a REST command and a WS
        // mirror_start — the child received two mirror_start commands, popped the
        // system screen-recording consent dialog TWICE ("bar bar aa raha tha") and
        // the stacked dialogs blocked the screen.
        mirrorRunning.value = false
        mirrorLastFrameAt.value = 0L
        val params = JsonObject().apply {
            addProperty("fps", 8)
            addProperty("quality", 60)
            addProperty("maxWidth", 720)
        }
        activeChild()?.id?.let { Graph.ws.watchMirror(it) }
        sendCommand("mirror_start", params)
    }

    fun stopMirror() {
        sendCommand("mirror_stop")
        Graph.ws.unwatchMirror()
        mirrorRunning.value = false
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

    /** Bachche ka call log fetch karo (naye pehle). Reports screen khulte hi + refresh par. */
    fun loadCalls(cid: String) {
        viewModelScope.launch {
            callsLoading.value = true
            runCatching { Api.calls(cid) }.onSuccess { resp ->
                calls.value = resp.calls.sortedByDescending { it.date }
            }
            callsLoading.value = false
        }
    }

    /** Bachche ke SMS fetch karo (naye pehle). */
    fun loadSms(cid: String) {
        viewModelScope.launch {
            smsLoading.value = true
            runCatching { Api.sms(cid) }.onSuccess { resp ->
                sms.value = resp.sms.sortedByDescending { it.date }
            }
            smsLoading.value = false
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
