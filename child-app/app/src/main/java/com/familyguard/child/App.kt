package com.familyguard.child

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.familyguard.child.data.Store
import com.familyguard.child.service.MainService
import com.familyguard.child.service.UsageWorker
import com.familyguard.child.util.AgeRelease
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.app = this
        Graph.gson = Gson()
        Graph.client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        UsageWorker.schedule(this)
        if (Store.paired && !AgeRelease.shouldRelease(this)) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, MainService::class.java))
            }
        }
    }
}

object Graph {
    lateinit var client: OkHttpClient
    lateinit var gson: Gson
    lateinit var app: App
}
