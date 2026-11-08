package com.familyguard.parent.data

import com.familyguard.parent.Graph
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object Api {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun baseUrl(): String {
        var url = Store.serverUrl.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http")) url = "https://$url"
        return url
    }

    fun mediaUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl() + (if (path.startsWith("/")) path else "/$path")
    }

    fun jsonBody(obj: Any): RequestBody = Graph.gson.toJson(obj).toRequestBody(jsonType)

    private fun builder(method: String, url: String, body: RequestBody? = null): Request.Builder {
        val b = Request.Builder().url(url)
        when (method) {
            "POST" -> b.post(body ?: jsonBody(JsonObject()))
            "PUT" -> b.put(body ?: jsonBody(JsonObject()))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        val token = Store.token
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return b
    }

    private inline fun <reified T> call(request: Request): T {
        Graph.client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) {
                // Token rejected (expired / server DB reset) — flip to login once.
                Session.kick("Session expired — login again")
                throw IOException("HTTP 401: session expired")
            }
            if (!resp.isSuccessful) {
                val snippet = if (text.length > 200) text.substring(0, 200) else text
                throw IOException("HTTP ${resp.code}: $snippet")
            }
            if (text.isBlank()) throw IOException("Empty response from server")
            return Graph.gson.fromJson(text, T::class.java)
                ?: throw IOException("Unexpected empty payload from server")
        }
    }

    // ---------- auth ----------

    suspend fun register(username: String, password: String): TokenResp = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }
        call(builder("POST", "${baseUrl()}/api/parent/register", jsonBody(body)).build())
    }

    suspend fun login(username: String, password: String): TokenResp = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }
        call(builder("POST", "${baseUrl()}/api/parent/login", jsonBody(body)).build())
    }

    // ---------- children ----------

    suspend fun children(): ChildrenResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/children").build())
    }

    suspend fun createChild(name: String, dob: String): Child = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("dob", dob)
        }
        val resp = call<ChildWrap>(builder("POST", "${baseUrl()}/api/children", jsonBody(body)).build())
        resp.child ?: throw IOException("Server returned no child profile")
    }

    suspend fun deleteChild(id: String): OkResp = withContext(Dispatchers.IO) {
        call(builder("DELETE", "${baseUrl()}/api/children/$id").build())
    }

    suspend fun regenerateCode(id: String): String = withContext(Dispatchers.IO) {
        call<CodeResp>(
            builder("POST", "${baseUrl()}/api/children/$id/regenerate-code", jsonBody(JsonObject())).build()
        ).pairingCode
    }

    // ---------- rules ----------

    suspend fun getRules(cid: String): Rules = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/rules/$cid").build())
    }

    suspend fun putRules(cid: String, rules: Rules): OkResp = withContext(Dispatchers.IO) {
        call(builder("PUT", "${baseUrl()}/api/rules/$cid", jsonBody(rules)).build())
    }

    // ---------- usage ----------

    suspend fun usage(cid: String, date: String? = null): UsageResp = withContext(Dispatchers.IO) {
        val q = if (date.isNullOrBlank()) "" else "?date=$date"
        call(builder("GET", "${baseUrl()}/api/usage/$cid$q").build())
    }

    // ---------- location ----------

    suspend fun locations(cid: String, limit: Int = 300): PointsResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/location/$cid?limit=$limit").build())
    }

    // ---------- geofences ----------

    suspend fun fences(cid: String): FencesResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/geofences/$cid").build())
    }

    suspend fun addFence(cid: String, f: Geofence): Geofence = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("name", f.name)
            addProperty("lat", f.lat)
            addProperty("lng", f.lng)
            addProperty("radius", f.radius)
            addProperty("type", f.type)
        }
        val resp = call<FenceResp>(builder("POST", "${baseUrl()}/api/geofences/$cid", jsonBody(body)).build())
        resp.fence ?: throw IOException("Server returned no geofence")
    }

    suspend fun deleteFence(cid: String, fid: String): OkResp = withContext(Dispatchers.IO) {
        call(builder("DELETE", "${baseUrl()}/api/geofences/$cid/$fid").build())
    }

    // ---------- feeds ----------

    suspend fun events(cid: String, limit: Int = 300): EventsResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/events/$cid?limit=$limit").build())
    }

    suspend fun notifications(cid: String, limit: Int = 300): NotifsResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/notifications/$cid?limit=$limit").build())
    }

    suspend fun calls(cid: String): CallsResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/calls/$cid").build())
    }

    suspend fun sms(cid: String): SmsResp = withContext(Dispatchers.IO) {
        call(builder("GET", "${baseUrl()}/api/sms/$cid").build())
    }

    // ---------- remote commands ----------

    suspend fun command(cid: String, action: String, params: JsonObject = JsonObject()): CmdIdResp =
        withContext(Dispatchers.IO) {
            val body = JsonObject().apply {
                addProperty("cid", cid)
                addProperty("action", action)
                add("params", params)
            }
            call(builder("POST", "${baseUrl()}/api/commands", jsonBody(body)).build())
        }
}
