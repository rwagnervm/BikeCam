package com.bikecam.telemetry

import android.os.SystemClock
import com.bikecam.hud.TrackBuilder
import com.bikecam.telemetry.ble.BleSensorManager
import com.bikecam.telemetry.gps.LocationSource
import com.bikecam.telemetry.motion.ElevationSource
import com.bikecam.telemetry.motion.HeadingSource
import com.bikecam.telemetry.sim.SimulatedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Funde GPS, bússola, barômetro/acelerômetro e sensores BLE num [TelemetrySnapshot] emitido a
 * taxa fixa ([RATE_HZ]). Também acumula a trilha do mini-mapa.
 * Roda em Dispatchers.Default, totalmente independente do pipeline de vídeo (RNF03): os
 * consumidores só leem o último snapshot pronto.
 */
class TelemetryRepository(
    private val location: LocationSource,
    private val heading: HeadingSource,
    private val ble: BleSensorManager,
    private val elevation: ElevationSource,
) {
    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    @Volatile private var resetRequested = false

    @Volatile var simulate: Boolean = false
        set(value) {
            if (value && !field) sim = SimulatedSource()
            if (value != field) resetRequested = true // não mistura trilha simulada com a real
            field = value
        }

    private var sim = SimulatedSource()
    private val trip = TripComputer()
    private val elevationEstimator = ElevationEstimator()
    private val track = TrackBuilder()
    private var job: Job? = null
    private var lastDeclinationUpdateMs = Long.MIN_VALUE

    /** Zera distância, média, ganho de elevação e trilha (chamado ao iniciar uma gravação). */
    fun resetTrip() {
        resetRequested = true
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val period = 1000L / RATE_HZ
            var last = SystemClock.elapsedRealtime()
            var next = last
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                tick(now, now - last)
                last = now
                next += period
                val wait = next - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait) else next = SystemClock.elapsedRealtime()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun tick(now: Long, dtMs: Long) {
        if (resetRequested) {
            trip.reset()
            elevationEstimator.reset()
            track.reset()
            resetRequested = false
        }
        val epoch = System.currentTimeMillis()
        _snapshot.value = if (simulate) simulated(now, epoch, dtMs) else fused(now, epoch, dtMs)
    }

    private fun fused(now: Long, epoch: Long, dtMs: Long): TelemetrySnapshot {
        val fix = location.fix.value?.takeIf { now - it.receivedAtMs <= GPS_STALE_MS }
        val b = ble.readings(now)

        val (speed, source) = SpeedSelector.select(
            nowMs = now,
            bleSpeedMps = b.speedMps, bleUpdatedAtMs = b.speedUpdatedAtMs,
            gpsSpeedMps = fix?.speedMps, gpsUpdatedAtMs = fix?.receivedAtMs ?: 0L,
        )
        trip.update(dtMs, speed)

        if (fix != null && now - lastDeclinationUpdateMs > 60_000L) {
            heading.updateDeclination(fix.latitude, fix.longitude, fix.altitudeM ?: 0.0)
            lastDeclinationUpdateMs = now
        }
        // Com o celular no guidão a bússola sofre interferência; em movimento o rumo do GPS é melhor.
        val gpsBearing = fix?.bearingDeg?.takeIf { (fix.speedMps ?: 0f) > GPS_BEARING_MIN_SPEED }
        val hdg = gpsBearing ?: heading.heading.value

        val elev = elevationEstimator.update(
            dtMs = dtMs,
            baroAltM = elevation.baroAltitudeM(now),
            gpsAltM = fix?.altitudeM,
            gpsAccuracyM = fix?.accuracyM,
            distanceM = trip.distanceM,
            speedMps = speed,
            forwardTilt = elevation.forwardTilt(now),
        )
        if (fix != null && (fix.accuracyM ?: 0f) <= TRACK_MAX_ACCURACY_M) track.add(fix.latitude, fix.longitude)

        return TelemetrySnapshot(
            epochMs = epoch,
            speedMps = speed,
            speedSource = source,
            avgSpeedMps = trip.avgSpeedMps,
            distanceM = trip.distanceM,
            movingTimeMs = trip.movingTimeMs,
            heartRateBpm = b.heartRateBpm?.takeIf { now - b.hrUpdatedAtMs <= SENSOR_STALE_MS },
            cadenceRpm = b.cadenceRpm?.takeIf { now - b.cadenceUpdatedAtMs <= SENSOR_STALE_MS },
            headingDeg = hdg,
            altitudeM = elev.altitudeM,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            gpsAccuracyM = fix?.accuracyM,
            gradePercent = elev.gradePercent,
            elevationGainM = elev.gainM,
            track = track.path,
            hrConnected = b.hrConnected,
            cscConnected = b.cscConnected,
            gpsFix = fix != null,
        )
    }

    private fun simulated(now: Long, epoch: Long, dtMs: Long): TelemetrySnapshot {
        val s = sim.sample(now)
        trip.update(dtMs, s.speedMps)
        val elev = elevationEstimator.update(
            dtMs = dtMs, baroAltM = s.altitudeM, gpsAltM = s.altitudeM, gpsAccuracyM = 3f,
            distanceM = trip.distanceM, speedMps = s.speedMps, forwardTilt = null,
        )
        track.add(s.latitude, s.longitude)
        return TelemetrySnapshot(
            epochMs = epoch,
            speedMps = s.speedMps,
            speedSource = SpeedSource.SIMULATED,
            avgSpeedMps = trip.avgSpeedMps,
            distanceM = trip.distanceM,
            movingTimeMs = trip.movingTimeMs,
            heartRateBpm = s.heartRate,
            cadenceRpm = s.cadence,
            headingDeg = s.headingDeg,
            altitudeM = elev.altitudeM,
            latitude = s.latitude,
            longitude = s.longitude,
            gpsAccuracyM = 3f,
            gradePercent = elev.gradePercent,
            elevationGainM = elev.gainM,
            track = track.path,
            hrConnected = true,
            cscConnected = true,
            gpsFix = true,
        )
    }

    companion object {
        const val RATE_HZ = 5
        private const val GPS_STALE_MS = 5_000L
        private const val SENSOR_STALE_MS = 5_000L
        private const val GPS_BEARING_MIN_SPEED = 2.5f
        private const val TRACK_MAX_ACCURACY_M = 25f
    }
}
