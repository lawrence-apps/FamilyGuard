package com.familyguard.parent.data

import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.Period

// ---------- core entities ----------

data class Child(
    val id: String = "",
    val name: String = "",
    val dob: String = "",
    val paired: Boolean = false,
    val pairingCode: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0L,
    val battery: Int = 0,
    val deviceName: String = "",
    val model: String = "",
    val currentApp: String = "",
    val health: JsonObject? = null
)

/** Age in full years parsed from dob "YYYY-MM-DD"; returns -1 when dob is blank or unparsable. */
fun Child.ageYears(): Int {
    if (dob.isBlank()) return -1
    return try {
        val born = LocalDate.parse(dob.trim())
        Period.between(born, LocalDate.now()).years
    } catch (e: Exception) {
        -1
    }
}

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

data class EventItem(
    val id: String = "",
    val type: String = "",
    val data: JsonObject? = null,
    val t: Long = 0L
)

data class NotifItem(
    val pkg: String = "",
    val title: String = "",
    val text: String = "",
    val date: Long = 0L
)

data class CallItem(
    val number: String = "",
    val name: String = "",
    val type: String = "",
    val duration: Long = 0L,
    val date: Long = 0L
)

data class SmsItem(
    val number: String = "",
    val name: String = "",
    val body: String = "",
    val type: String = "",
    val date: Long = 0L
)

data class UsageApp(val min: Int = 0, val label: String = "")

data class LocPoint(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val t: Long = 0L,
    val battery: Int = 0
)

data class CommandResult(
    val id: String = "",
    val ok: Boolean = false,
    val data: JsonObject? = null
)

data class ChildApp(val pkg: String = "", val name: String = "")

data class StatusUi(
    val online: Boolean = false,
    val battery: Int = 0,
    val charging: Boolean = false,
    val currentApp: String = "",
    val health: JsonObject? = null
)

// ---------- API response envelopes ----------

data class ChildrenResp(val children: List<Child> = emptyList())

data class ChildWrap(val child: Child? = null)

data class TokenResp(val token: String = "")

data class OkResp(val ok: Boolean = false)

data class CodeResp(val pairingCode: String = "")

data class UsageResp(
    val date: String = "",
    val apps: Map<String, UsageApp> = emptyMap(),
    val totalMin: Int = 0
)

data class PointsResp(
    val points: List<LocPoint> = emptyList(),
    val last: LocPoint? = null
)

data class FencesResp(val fences: List<Geofence> = emptyList())

data class FenceResp(val fence: Geofence? = null)

data class EventsResp(val events: List<EventItem> = emptyList())

data class NotifsResp(val notifications: List<NotifItem> = emptyList())

data class CallsResp(val calls: List<CallItem> = emptyList())

data class SmsResp(val sms: List<SmsItem> = emptyList())

data class CmdIdResp(val id: String = "")

data class MediaResp(val url: String = "")

data class ApkInfo(
    val uploaded: Boolean = false,
    val url: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val uploadedAt: Long = 0
)

data class MeResp(
    val role: String = "",
    val username: String = "",
    val name: String = ""
)
