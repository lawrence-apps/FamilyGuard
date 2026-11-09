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
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.data.UsagePost
import com.google.gson.JsonArray
import java.util.Calendar
import kotlin.math.max

/**
 * Usage + Call log + SMS sync ka SINGLE engine — UsageWorker (15-min periodic)
 * aur CommandHandler "sync_usage" (website ka Sync now button) dono isi ko
 * chalate hain.
 *
 * Pehle "Sync now" sirf WorkManager ka one-time worker schedule karta tha —
 * usme SelfHeal + Api.me + rules fetch sab pehle chalta tha, data post hone me
 * 5-15 s lagte the aur parent ko counts kabhi nahi milte the ("Sync now dabaya,
 * kuch hua hi nahi" wali feeling). Ab command INLINE chalata hai aur RESULT me
 * real counts + permission flags wapas jate hain — website turant "12 calls ·
 * 3 SMS synced" dikha kar logs reload kar leta hai.
 */
object SyncEngine {

    data class Result(val usageApps: Int, val calls: Int, val sms: Int)

    fun hasCallsPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun hasSmsPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun run(ctx: Context): Result {
        val apps = syncUsage(ctx)
        val calls = syncCalls(ctx)
        val sms = syncSms(ctx)
        return Result(apps, calls, sms)
    }

    // -------------------------------------------------------------- usage

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** @return kitne apps ka screen-time post hua */
    fun syncUsage(ctx: Context): Int {
        if (!hasUsageAccess(ctx)) return 0
        return runCatching {
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
            map.size
        }.getOrDefault(0)
    }

    // -------------------------------------------------------------- calls

    /** @return kitni nayi call entries post hui */
    fun syncCalls(ctx: Context): Int {
        if (!hasCallsPerm(ctx)) return 0
        return runCatching {
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
                    val o = com.google.gson.JsonObject()
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
            arr.size()
        }.getOrDefault(0)
    }

    // ---------------------------------------------------------------- sms

    /** @return kitne naye SMS post hue */
    fun syncSms(ctx: Context): Int {
        if (!hasSmsPerm(ctx)) return 0
        return runCatching {
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
                    val o = com.google.gson.JsonObject()
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
            arr.size()
        }.getOrDefault(0)
    }
}
