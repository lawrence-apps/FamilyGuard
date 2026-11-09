package com.familyguard.child.data

import com.familyguard.child.Graph
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder

object Api {

    private val client get() = Graph.client
    private val gson get() = Graph.gson

    private fun baseUrl(): String = Store.serverUrl.trimEnd('/')

    private fun auth(b: Request.Builder): Request.Builder {
        if (Store.token.isNotBlank()) b.header("Authorization", "Bearer ${Store.token}")
        return b
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val b = auth(Request.Builder().url(baseUrl() + path).get())
        client.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            s
        }
    }

    private suspend fun postJson(path: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val payload = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = auth(Request.Builder().url(baseUrl() + path).post(payload))
        client.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            s
        }
    }

    private fun obj(text: String): JsonObject =
        runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: JsonObject()

    // ------------------------------------------------------------------ auth

    suspend fun pair(code: String, deviceName: String, model: String): PairResp {
        val body = JsonObject()
        body.addProperty("code", code)
        body.addProperty("deviceName", deviceName)
        body.addProperty("model", model)
        return parsePairResp(obj(postJson("/api/child/pair", body)))
    }

    suspend fun me(): MeResp = parseMeResp(obj(get("/api/child/me")))

    // ------------------------------------------------------------------ apk

    /** Server ke paas abhi konsa child APK hai (GitHub auto-sync wala). */
    suspend fun getApkMeta(): JsonObject = obj(get("/api/child/apk"))

    /** APK ko server se cache file me download karo (silent, koi notification nahi). */
    suspend fun downloadApk(dest: File, urlPath: String): Boolean = withContext(Dispatchers.IO) {
        val b = auth(Request.Builder().url(baseUrl() + urlPath).get())
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty body")
            dest.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        true
    }

    // ----------------------------------------------------------------- rules

    suspend fun getRules(): Rules = parseRules(obj(get("/api/rules/${Store.cid}")))

    // ----------------------------------------------------------------- usage

    suspend fun postUsage(date: String, apps: Map<String, UsagePost>) {
        val appsObj = JsonObject()
        apps.forEach { (pkg, up) ->
            val o = JsonObject()
            o.addProperty("min", up.min)
            o.addProperty("label", up.label)
            appsObj.add(pkg, o)
        }
        val body = JsonObject()
        body.addProperty("date", date)
        body.add("apps", appsObj)
        postJson("/api/usage/${Store.cid}", body)
    }

    // -------------------------------------------------------------- location

    suspend fun postLocation(lat: Double, lng: Double, accuracy: Float, speed: Float, battery: Int) {
        val body = JsonObject()
        body.addProperty("lat", lat)
        body.addProperty("lng", lng)
        body.addProperty("accuracy", accuracy)
        body.addProperty("speed", speed)
        body.addProperty("battery", battery)
        postJson("/api/location/${Store.cid}", body)
    }

    // ------------------------------------------------------------- geofences

    suspend fun getFences(): List<Geofence> = parseGeofencesPayload(obj(get("/api/geofences/${Store.cid}")))

    // ---------------------------------------------------------------- events

    suspend fun postEvent(type: String, data: JsonObject) {
        val body = JsonObject()
        body.addProperty("type", type)
        body.add("data", data)
        postJson("/api/events/${Store.cid}", body)
    }

    // ------------------------------------------------------- calls / sms / notifs

    suspend fun postCalls(arr: JsonArray) {
        val body = JsonObject()
        body.add("calls", arr)
        postJson("/api/calls/${Store.cid}", body)
    }

    suspend fun postSms(arr: JsonArray) {
        val body = JsonObject()
        body.add("sms", arr)
        postJson("/api/sms/${Store.cid}", body)
    }

    suspend fun postNotifications(arr: JsonArray) {
        val body = JsonObject()
        body.add("notifications", arr)
        postJson("/api/notifications/${Store.cid}", body)
    }

    // ----------------------------------------------------------------- media

    suspend fun uploadMedia(file: File): String = withContext(Dispatchers.IO) {
        val mime = if (file.name.endsWith(".png")) "image/png" else "image/jpeg"
        val part = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val b = auth(Request.Builder().url(baseUrl() + "/api/media/${Store.cid}").post(part))
        client.newCall(b.build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            runCatching { gson.fromJson(s, MediaResp::class.java).url }.getOrNull().orEmpty()
        }
    }

    // -------------------------------------------------------------------- ws

    fun wsUrl(): String {
        val base = baseUrl() + "/ws?token=" + URLEncoder.encode(Store.token, "UTF-8")
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
    }
}
