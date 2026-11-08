package com.familyguard.parent.data

import android.content.Context
import android.content.SharedPreferences

object Store {

    private const val PREFS = "fg_parent"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun check() {
        if (!::prefs.isInitialized) {
            throw IllegalStateException("Store.init(context) must be called before use")
        }
    }

    var serverUrl: String
        get() {
            check()
            return prefs.getString("serverUrl", null) ?: "https://your-server.example.com"
        }
        set(value) {
            check()
            prefs.edit().putString("serverUrl", value).apply()
        }

    var token: String
        get() {
            check()
            return prefs.getString("token", null) ?: ""
        }
        set(value) {
            check()
            prefs.edit().putString("token", value).apply()
        }

    var username: String
        get() {
            check()
            return prefs.getString("username", null) ?: ""
        }
        set(value) {
            check()
            prefs.edit().putString("username", value).apply()
        }

    var activeChildId: String
        get() {
            check()
            return prefs.getString("activeChildId", null) ?: ""
        }
        set(value) {
            check()
            prefs.edit().putString("activeChildId", value).apply()
        }

    fun clearAll() {
        check()
        prefs.edit().clear().apply()
    }
}
