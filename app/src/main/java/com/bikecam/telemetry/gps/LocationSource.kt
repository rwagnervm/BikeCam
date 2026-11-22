package com.bikecam.telemetry.gps

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocationFix(
    /** SystemClock.elapsedRealtime() do recebimento (para frescor). */
    val receivedAtMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
)

/** GPS a 1 Hz via Fused Location Provider (alta precisão). */
class LocationSource(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            _fix.value = LocationFix(
                receivedAtMs = SystemClock.elapsedRealtime(),
                latitude = l.latitude,
                longitude = l.longitude,
                altitudeM = if (l.hasAltitude()) l.altitude else null,
                speedMps = if (l.hasSpeed()) l.speed else null,
                bearingDeg = if (l.hasBearing() && l.hasSpeed() && l.speed > 1f) l.bearing else null,
                accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            )
        }
    }

    @SuppressLint("MissingPermission") // o chamador verifica ACCESS_FINE_LOCATION
    fun start() {
        if (running) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            running = true
        } catch (e: SecurityException) {
            Log.w("BikeCam.GPS", "sem permissão de localização", e)
        }
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
    }
}
