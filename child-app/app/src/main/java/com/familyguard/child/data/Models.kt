package com.familyguard.child.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class Bedtime(
    val enabled: Boolean = false,
    val start: String = "21:30",
    val end: String = "07:00"
)

data class Schedule(
    val name: String = "",
    val days: List<Int> = emptyList(),
    val start: String = "",
    val end: String = "",
    val blockAll: Boolean = true,
    val allowedApps: List<String> = emptyList()
)

data class AppLimit(val dailyMin: Int = 0)

data class Rules(
    val dailyScreenTimeMin: Int = 0,
    val bedtime: Bedtime = Bedtime(),
    val schedules: List<Schedule> = emptyList(),
    val blockedApps: List<String> = emptyList(),
    val appLimits: Map<String, AppLimit> = emptyMap(),
    val alwaysAllowed: List<String> = emptyList()
)

data class Geofence(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radius: Double = 150.0,
    val type: String = "in",
    val active: Boolean = true
)

data class ChildApp(val pkg: String = "", val name: String = "")

data class ChildInfo(val id: String = "", val name: String = "")

data class PairResp(val token: String = "", val child: ChildInfo? = null)

data class MeResp(val id: String = "", val name: String = "", val dob: String = "")

data class OkResp(val ok: Boolean = false)

data class MediaResp(val url: String = "")

data class UsagePost(val min: Int, val label: String)

// ---------------------------------------------------------------------------
// Defensive parsers. Gson bypasses Kotlin constructors, so missing JSON fields
// would become null even on non-null properties. These helpers guarantee fully
// populated objects.
// ---------------------------------------------------------------------------

private fun JsonObject.str(key: String, def: String = ""): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: def

private fun JsonObject.int(key: String, def: Int = 0): Int =
    get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: def

private fun JsonObject.dbl(key: String, def: Double = 0.0): Double =
    get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: def

private fun JsonObject.bool(key: String, def: Boolean = false): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: def

private fun JsonObject.strList(key: String): List<String> {
    val arr: JsonArray = getAsJsonArray(key) ?: return emptyList()
    return arr.mapNotNull { el -> if (el.isJsonPrimitive) el.asString else null }
}

private fun JsonObject.intList(key: String): List<Int> {
    val arr: JsonArray = getAsJsonArray(key) ?: return emptyList()
    return arr.mapNotNull { el -> if (el.isJsonPrimitive) el.asInt else null }
}

fun parseRules(o: JsonObject): Rules {
    val bt = if (o.get("bedtime") != null && o.get("bedtime").isJsonObject) {
        val b = o.getAsJsonObject("bedtime")
        Bedtime(
            enabled = b.bool("enabled", false),
            start = b.str("start", "21:30"),
            end = b.str("end", "07:00")
        )
    } else Bedtime()

    val schedules = ArrayList<Schedule>()
    val schedArr = o.getAsJsonArray("schedules")
    schedArr?.forEach { el ->
        if (el.isJsonObject) {
            val s = el.asJsonObject
            schedules.add(
                Schedule(
                    name = s.str("name"),
                    days = s.intList("days"),
                    start = s.str("start"),
                    end = s.str("end"),
                    blockAll = s.bool("blockAll", true),
                    allowedApps = s.strList("allowedApps")
                )
            )
        }
    }

    val limits = HashMap<String, AppLimit>()
    val limitsObj = o.getAsJsonObject("appLimits")
    limitsObj?.entrySet()?.forEach { (k, v) ->
        if (v.isJsonObject) {
            limits[k] = AppLimit(dailyMin = v.asJsonObject.int("dailyMin", 0))
        }
    }

    return Rules(
        dailyScreenTimeMin = o.int("dailyScreenTimeMin", 0),
        bedtime = bt,
        schedules = schedules,
        blockedApps = o.strList("blockedApps"),
        appLimits = limits,
        alwaysAllowed = o.strList("alwaysAllowed")
    )
}

fun parseRulesFromPayload(o: JsonObject): Rules {
    val nested = o.getAsJsonObject("rules")
    return if (nested != null && nested.isJsonObject) parseRules(nested) else parseRules(o)
}

fun parseGeofence(o: JsonObject): Geofence = Geofence(
    id = o.str("id"),
    name = o.str("name"),
    lat = o.dbl("lat", 0.0),
    lng = o.dbl("lng", 0.0),
    radius = o.dbl("radius", 150.0),
    type = o.str("type", "in"),
    active = o.bool("active", true)
)

fun parseGeofenceArray(arr: JsonArray?): List<Geofence> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { el -> if (el.isJsonObject) parseGeofence(el.asJsonObject) else null }
}

fun parseGeofencesPayload(o: JsonObject): List<Geofence> =
    parseGeofenceArray(o.getAsJsonArray("geofences") ?: o.getAsJsonArray("fences"))

fun parsePairResp(o: JsonObject): PairResp {
    val childObj = o.getAsJsonObject("child")
    val child = if (childObj != null && childObj.isJsonObject) {
        ChildInfo(id = childObj.str("id"), name = childObj.str("name"))
    } else null
    return PairResp(token = o.str("token"), child = child)
}

fun parseMeResp(o: JsonObject): MeResp = MeResp(
    id = o.str("id"),
    name = o.str("name"),
    dob = o.str("dob")
)
