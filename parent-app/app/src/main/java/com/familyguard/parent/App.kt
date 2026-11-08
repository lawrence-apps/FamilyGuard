package com.familyguard.parent

import android.app.Application
import com.familyguard.parent.data.Store
import com.familyguard.parent.data.WsManager
import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import java.io.File
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

        Store.init(this)

        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        cfg.osmdroidBasePath = File(cacheDir, "osmdroid")
        cfg.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
    }
}

object Graph {
    lateinit var client: OkHttpClient
    lateinit var gson: Gson
    lateinit var app: App

    val ws: WsManager by lazy { WsManager() }
}
