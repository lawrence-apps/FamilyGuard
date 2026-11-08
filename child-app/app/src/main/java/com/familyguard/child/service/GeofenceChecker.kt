package com.familyguard.child.service

import android.location.Location
import com.familyguard.child.data.Api
import com.familyguard.child.data.Geofence
import com.familyguard.child.data.Store
import com.google.gson.JsonObject

object GeofenceChecker {

    private var fences: List<Geofence> = emptyList()
    private val state = HashMap<String, Boolean>()

    init {
        state.putAll(Store.loadFenceState())
    }

    fun update(list: List<Geofence>) {
        fences = list
        state.keys.retainAll(list.map { it.id }.toSet())
        Store.saveFenceState(state)
    }

    suspend fun check(fix: Locator.LocFix) {
        val res = FloatArray(1)
        for (f in fences.filter { it.active }) {
            Location.distanceBetween(fix.lat, fix.lng, f.lat, f.lng, res)
            val inside = res[0] <= f.radius
            val prev = state[f.id] ?: false
            if (inside != prev) {
                state[f.id] = inside
                Store.saveFenceState(state)
                val ev = when {
                    inside && f.type == "in" -> "geofence_enter"
                    !inside && f.type == "out" -> "geofence_exit"
                    else -> null
                }
                if (ev != null) {
                    val data = JsonObject()
                    data.addProperty("fence", f.id)
                    data.addProperty("name", f.name)
                    runCatching { Api.postEvent(ev, data) }
                    val m = JsonObject()
                    m.addProperty("type", ev)
                    m.add("data", data)
                    WsChild.send(m.toString())
                }
            }
        }
    }
}
