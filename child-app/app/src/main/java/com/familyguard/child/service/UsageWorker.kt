package com.familyguard.child.service

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familyguard.child.RulesEngine
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.data.UsagePost
import com.familyguard.child.util.AgeRelease
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max

class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (AgeRelease.shouldRelease(ctx)) {
            AgeRelease.releaseAll(ctx)
            return Result.success()
        }
        if (Store.cid.isBlank()) return Result.success()

        runCatching { Api.me() }.onFailure { e ->
            // Pairing token no longer valid on the server (e.g. server DB reset).
            // Unpair so the device can be re-paired from the setup wizard.
            if ((e.message ?: "").contains("401")) {
                Store.clearAll()
                runCatching {
                    applicationContext.stopService(android.content.Intent(applicationContext, MainService::class.java))
                }
                return Result.success()
            }
        }.getOrNull()?.let {
            Store.childName = it.name
            if (it.dob.isNotBlank()) Store.dob = it.dob
        }
        runCatching { Api.getRules() }.getOrNull()?.let { RulesEngine.update(it) }
        runCatching { Api.getFences() }.getOrNull()?.let { GeofenceChecker.update(it) }

        syncUsage(ctx)
        syncCalls(ctx)
        syncSms(ctx)
        return Result.success()
    }

    // -------------------------------------------------------------- usage

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private suspend fun syncUsage(ctx: Context) {
        if (!hasUsageAccess(ctx)) return
        runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis
            val now = System.currentTimeMillis()

            val map = HashMap<String, Int>()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now).forEach { stat ->
                if (stat.packageName != ctx.packageName) {
                    map[stat.packageName] = max(
                        map[stat.packageName] ?: 0,
                        (stat.totalTimeInForeground / 60000L).toInt()
                    )
                }
            }
            EnforcementService.minutesToday.forEach { (k, v) ->
                if (k != ctx.packageName) map[k] = max(map[k] ?: 0, v)
            }
            if (map.isNotEmpty()) {
                val pm = ctx.packageManager
                val body = map.mapValues { (pkg, min) ->
                    val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
                    UsagePost(min, label)
                }
                runCatching { Api.postUsage(Store.today(), body) }
                Store.saveUsage(map, Store.today())
            }
        }
    }

    // -------------------------------------------------------------- calls

    private suspend fun syncCalls(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val arr = JsonArray()
            var maxDate = Store.lastCallSync
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            )
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE}>?",
                arrayOf(Store.lastCallSync.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                var count = 0
                while (c.moveToNext() && count < 200) {
                    val date = c.getLong(4)
                    if (date > maxDate) maxDate = date
                    val o = JsonObject()
                    o.addProperty("number", c.getString(0) ?: "")
                    o.addProperty("name", c.getString(1) ?: "")
                    o.addProperty(
                        "type",
                        when (c.getInt(2)) {
                            CallLog.Calls.INCOMING_TYPE -> "in"
                            CallLog.Calls.OUTGOING_TYPE -> "out"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            else -> "other"
                        }
                    )
                    o.addProperty("duration", c.getLong(3))
                    o.addProperty("date", date)
                    arr.add(o)
                    count++
                }
            }
            if (arr.size() > 0) {
                runCatching { Api.postCalls(arr) }
                Store.lastCallSync = maxDate
            }
        }
    }

    // ---------------------------------------------------------------- sms

    private suspend fun syncSms(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val arr = JsonArray()
            var maxDate = Store.lastSmsSync
            val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE}>?",
                arrayOf(Store.lastSmsSync.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                var count = 0
                while (c.moveToNext() && count < 200) {
                    val date = c.getLong(2)
                    if (date > maxDate) maxDate = date
                    val o = JsonObject()
                    o.addProperty("number", c.getString(0) ?: "")
                    o.addProperty("name", c.getString(0) ?: "")
                    o.addProperty("body", c.getString(1) ?: "")
                    o.addProperty("type", "in")
                    o.addProperty("date", date)
                    arr.add(o)
                    count++
                }
            }
            if (arr.size() > 0) {
                runCatching { Api.postSms(arr) }
                Store.lastSmsSync = maxDate
            }
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UsageWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("fg_usage", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runOnce(ctx: Context) {
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("fg_once", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<UsageWorker>().build())
        }
    }
}
