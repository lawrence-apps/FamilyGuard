package com.familyguard.child.service

import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.familyguard.child.RulesEngine
import com.familyguard.child.data.Api
import com.familyguard.child.data.Store
import com.familyguard.child.util.AgeRelease
import com.familyguard.child.util.SelfHeal
import java.util.concurrent.TimeUnit

/**
 * 15-min periodic sync worker. Saara sync logic ab [SyncEngine] me hai —
 * website ka "Sync now" button (CommandHandler "sync_usage") bhi WAHI engine
 * inline chalata hai, isliye dono ka behavior hamesha same rehta hai.
 */
class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (AgeRelease.shouldRelease(ctx)) {
            AgeRelease.releaseAll(ctx)
            return Result.success()
        }
        if (Store.cid.isBlank()) return Result.success()

        // Watchdog: this worker also runs right after boot, so use every cycle to
        // revive a killed service, reconnect the websocket and report health —
        // the child device heals itself without ever being touched again.
        runCatching { SelfHeal.runNow(ctx) }

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

        SyncEngine.run(ctx)
        return Result.success()
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
