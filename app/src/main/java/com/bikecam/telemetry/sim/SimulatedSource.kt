package com.bikecam.telemetry.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Dados sintéticos para testar o pipeline de vídeo/OSD sem sensores nem GPS. */
class SimulatedSource {
    private var startMs = -1L
    private var lat = -15.7939
    private var lon = -47.8828
    private var lastMs = -1L

    data class Sample(
        val speedMps: Float, val heartRate: Int, val cadence: Int, val headingDeg: Float,
        val altitudeM: Double, val latitude: Double, val longitude: Double,
    )

    fun sample(nowMs: Long): Sample {
        if (startMs < 0) { startMs = nowMs; lastMs = nowMs }
        val t = (nowMs - startMs) / 1000.0
        val speed = (7.0 + 2.5 * sin(t / 20.0)).toFloat()
        // Rumo que serpenteia e vai girando devagar: a trilha do mini-mapa não fica num círculo só.
        val heading = (((t * 1.2 + 40.0 * sin(t / 45.0) + 25.0 * sin(t / 17.0)) % 360.0 + 360.0) % 360.0).toFloat()
        val dt = (nowMs - lastMs) / 1000.0
        lastMs = nowMs
        val d = speed * dt
        val rad = heading * PI / 180.0
        lat += d * cos(rad) / 111_320.0
        lon += d * sin(rad) / (111_320.0 * cos(lat * PI / 180.0))
        return Sample(
            speedMps = speed,
            heartRate = (132 + 18 * sin(t / 30.0)).toInt(),
            cadence = (86 + 8 * sin(t / 7.0)).toInt(),
            headingDeg = heading,
            altitudeM = 1_050.0 + 25.0 * sin(t / 60.0),
            latitude = lat,
            longitude = lon,
        )
    }
}
