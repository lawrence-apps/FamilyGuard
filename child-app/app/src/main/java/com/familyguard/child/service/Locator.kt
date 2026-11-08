package com.familyguard.child.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object Locator {

    data class LocFix(val lat: Double, val lng: Double, val accuracy: Float, val speed: Float, val battery: Int)

    @Suppress("DEPRECATION")
    suspend fun getCurrent(context: Context): LocFix? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val batt = battery(context)

        // Seed with last known locations from all providers.
        var best: LocFix? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let { loc ->
                        val f = LocFix(loc.latitude, loc.longitude, loc.accuracy, loc.speed, batt)
                        val cur = best
                        if (cur == null || f.accuracy < cur.accuracy) best = f
                    }
                }
            }
        }
        best?.let { if (it.accuracy <= 100f) return it }

        // Wait for a fresh single update from GPS or NETWORK.
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<LocFix?> { cont ->
                val listener = LocationListener { loc ->
                    val f = LocFix(loc.latitude, loc.longitude, loc.accuracy, loc.speed, batt)
                    val cur = best
                    if (cur == null || f.accuracy < cur.accuracy) {
                        best = f
                        if (cont.isActive) cont.resume(f)
                    }
                }
                try {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
                } catch (_: Exception) {
                }
                try {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null)
                } catch (_: Exception) {
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
        return fresh ?: best
    }

    fun battery(context: Context): Int = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)
}
